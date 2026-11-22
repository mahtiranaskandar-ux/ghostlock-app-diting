# Runtime CPU pair selection and a verified helper pin (plan)

Status: operator-directed 2026-11-16 ("picking a pair is not critical — pick 2, big cores first,
available at runtime"). Level L (attack path: the route's CPU pair and the compat helper's dance).
Evidence that motivated it: `external/port/realme-pin-repro-2026-11-16/` (the same-core
reproduction on the duchamp) and `external/github_error_reports/6.1.134-.../README.md` (issue #6's
two `last_kmsg` records, both on the waiter's CPU 4 against a 4/5 profile pair).

## Problem

The pair is a compile/profile constant (4/5) pinned blindly:

- `RuntimeConfig::init()` defaults to 0/1 and only checks `sched_getaffinity` (the cpuset);
  `apply_profile()` takes the profile pair if both are in that mask. A CPU can be in the mask and
  still refuse `sched_setaffinity` (`cpu_active` on the vendor core_ctl — the diting's class,
  and the Realme's `CPU: 4` evidence), and the check cannot see that.
- The compat helper's `pin_cpu()` is `(void) sched_setaffinity(...)`: the waiter and consumer both
  keep their inherited mask when the pin fails. On the Realme that put the consumer on the
  waiter's CPU, and the scheduler's own context switch overwrote the stamped waiter on the dead
  stack frame. On the duchamp reproduction the kernel even printed
  `Overriding affinity for ... (ghostlock32) to CPUs 0-3` and the walk still crashed.

The pair itself is not critical: the route only requires the waiter and consumer on **two
distinct CPUs**, and the stamp survives when the consumer is not on the waiter's CPU.

## Design

1. **Native: choose the pair at runtime.** In `apply_profile()` (after the profile pair is read
   for the log), enumerate the process's usable CPUs: every CPU in the current `sched_getaffinity`
   mask that also accepts a `sched_setaffinity({cpu})` probe. Rank by
   `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq` descending, then CPU id ascending, and
   take the first two as `main`/`consumer`. The profile pair remains the logged recommendation and
   the fallback when fewer than two CPUs are usable.
2. **Helper: verify the pins.** `pin_cpu()` returns false unless `sched_setaffinity` succeeds and
   `sched_getaffinity` shows exactly the requested CPU. The waiter and consumer check at thread
   start (and that the two CPUs differ); on failure they set a `cpu_error` flag, stop the other
   threads, and the main thread returns `status = Retryable`, `step = 34` **without running the
   dance**. The native logs the attempt and backs off; the stage fails cleanly instead of
   panicking the kernel.
3. **Residual.** A vendor affinity override that lands *after* the helper's start check is not
   preventable without root; the pair selection and the check remove the observed failure (a pin
   that never took effect) and turn the remaining race into the carrier's ordinary rate.

## Gates

- Native zero-warning build and `lint-tidy`.
- A new host test for the pure ranking (`cpu_select_test`), plus the full `native-host-tests`.
- The helper rebuilds clean.
- Device: the standalone same-core harness on the duchamp with a pair whose pins the shell cpuset
  refuses must now return `step=34` and **not** reboot; then an app-path run on the duchamp with
  the compat multicast external profile, checking the logged runtime pair and W1.

## 2026-11-21 amendment — the native race threads were the remaining gap

The venus `5.4.274-qgki` report (the page-free sigreturn route dying inside the first W1 attempt)
and the QEMU gate record for it (`external/port/venus-pin-qemu-2026-11-21/`) exposed two facts the
original design did not cover:

- **The waiter was never pinned.** Design item 2 verified the *compat helper's* waiter/consumer;
  the native race pinned only the main thread and the consumer, and the consumer's
  `pin_to_core()` logged a failure but kept going (`SYSCHK` → `pr_error` → exit). The waiter —
  the thread whose stack carries the strip and whose tid the consumer's `sched_setattr` targets —
  inherited the whole cpuset, so the "pair" the route cares about (waiter vs consumer) was never
  enforced on sigreturn/select.
- **The same-CPU failure is a wedge when `panic_on_oops=0`.** The QEMU gate reproduces the known
  clobber on this exact Image (`_raw_spin_trylock+0x1c` ← `rt_mutex_adjust_prio_chain`); with the
  Image's default `panic_on_oops=1` it panics, with `panic_on_oops=0` it wedges (RCU stall, no
  Kernel Exception) — the phone's `long_power_key` signature.

### What was implemented

1. **Logging first (no behavior change):** `log_startup_context` reads and logs
   `/proc/sys/kernel/panic_on_oops`; `pin_to_core`/`reset_cpu_pin` log the TID, the return code and
   errno, the CPU after the call and the `Cpus_allowed_list` readback; `available_candidates()`
   logs the allowed mask and each probe refusal; the waiter and owner threads log their CPU and
   allowed mask (they are unpinned), the waiter logs its pre-wait CPU, and the consumer logs once
   before its first walk.
2. **The verified pins (design item 2, extended):** the waiter is pinned to `main_cpu` and the
   consumer to `consumer_cpu` with a set **and** an exact `sched_getaffinity` readback
   (`CPU_COUNT == 1`, the requested CPU). Either worker failing (or `main_cpu == consumer_cpu`)
   sets `PiRace::cpu_error`; the waiter checks it at its three sync points (after the pin, before
   the `WAIT_REQUEUE_PI`, after the wait), and `PiRace::run()` checks it in the parked-waiter loop
   and again before `CMP_REQUEUE_PI`. The outcome is `ROUTE_RETRYABLE`, `step = 34`,
   `EINVAL` — no dance, no kernel walk.
3. The owner stays unpinned (it only blocks on the chain); its CPU is logged for diagnosis.

### Gates and status

- Native zero-warning build, `lint-tidy`, full `native-host-tests` (the `cpu_select_test` link
  gained `support/native_resource.cpp` because `cpu_select.cpp` now includes the logging header).
- `cmp_disasm` against a worktree build of HEAD (`d2afc0c6`, pre-change): `owner_thread` 70→140,
  `waiter_thread` 278→420, `consumer_thread` 187→239; the only other entries are `LAYOUT-SHIFT`
  address annotations on `do_kernel5_fake_lock_route` (8 operands) and `do_one_write` (3), with no
  instruction-shape change. The verdict `FAIL` is the documented missing `multicast_worker`
  control, not a diff. The instruction changes are confined to the three thread functions.
- **No device gate this pass** — the phones are in A/B use; the first report from the new build
  will carry the pin verdicts and `panic_on_oops`. The device gates above remain open. Note the
  behavioral change: the waiter is now pinned (to `main_cpu`), so the race layout on every route
  changes; the device gates must confirm it before this reaches a release.
- **2026-11-21 device check (APK 778, non-diting devices):** on the CPH2723 (`6.6.66`, OnePlus)
  the `pselect` route ran through the native race with the verified pins — runtime pair `6/7`,
  `pin: … core=6 rc=0 errno=0 now_cpu=6 allowed=6`, `waiter pin verified: cpu=6 allowed=6`,
  `consumer pin verified: cpu=7 allowed=7`, `pselect route done calls=1 success=1 status=0
  clean=1/1 step=0`, chain completed (`KernelSU ready`). The duchamp (`6.1.138`) run completed
  too, but its selected profile uses the compat multicast helper, so it did not exercise the
  native race. Observation from the logs: the waiter already inherits the creator's affinity
  (the main thread is pinned before the workers start), so the pre-pin mask is the main CPU; the
  new pin makes that explicit and catches a failed main pin or a vendor affinity override.
  `panic_on_oops` reads `unreadable` for both `untrusted_app` and shell (SELinux proc denial), so
  the venus panic-vs-wedge question still needs a root-time read or the reboot/KE behaviour.
  The refused-pair path (`cpu_error` → `step 34`) was not inducible on these devices; that gate
  stays open.

## 2026-11-21 (later) — one shared pin primitive; the compat AArch32 set is now logged

The pin code had drifted into four hand-written variants: the native's `pin_to_core`, the race's
`pin_thread_verified`, the compat helper's `pin_cpu`/`fallback_cpu`, and the picker's probe. They
now all call **`src/core/support/cpu_affinity.hpp`**: a dependency-free header (libc only, no
logging, no exceptions, so the static armv7 helper can include it) with:

- `PinResult { set_ok, readback_ok, exact, error, mask, now_cpu }` — the three facts kept
  distinct: the syscall accepted, the readback succeeded, and the readback is exactly the
  requested CPU;
- `pin_current_thread(cpu)`, `set_mask()`, `pin_all_current_thread()`, `probe_cpu()` (save, pin,
  read back, restore), `read_mask()`, `mask_is_exact()`, `format_mask()`.

Each caller keeps its policy and log format: `pin_to_core` stays fatal on a failed **set** (a
non-exact readback is logged as `(not exact)` and continues); the race threads map a non-exact pin
to `cpu_error`/`step 34`; the compat helper falls back; the picker probes without changing its
final mask.

**The compat helper now reports the kernel's AArch32-capable set.** At the first `resolve_cpu` it
probes a single-CPU mask on every possible CPU and logs, once per invocation:

```
compat-engine: aarch32 cpus=0,1,2,3 first_error=22
```

On the duchamp (`MT6897`, MIDR `0x411fd461` A510 on 0–3, `0x411fd4d0` A715 on 4–7) that set is the
A510 cluster; the native's 64-bit pair `7/4` is refused for the 32-bit helper (EINVAL) and the
helper falls back to `0/1`. The pair-selection fix (choose the compat pair inside this set
instead of relying on the fallback) remains the next L-level item; the log makes the reason
visible now.

**Gates:** native zero-warning, `lint-tidy`, full `native-host-tests`, and both device runs above
re-run on the refactored build — the OnePlus `pselect` race still reports `pin verified: cpu=6/7`
and completes `clean=1/1` three times; the duchamp compat run completes with the AArch32 log. The
pre-refactor APK native is stripped, so `cmp_disasm` has no usable baseline for this pure move;
the behavior is covered by the host suite and the identical device pin lines.

**Device fact surfaced by the harness:** the duchamp's root drain (KSU late-loaded by the previous
run) read `panic_on_oops=0` and `panic_on_rcu_stall=0`. The app itself cannot read the node
(SELinux), but this confirms that at least one HyperOS 6.1 device runs with `panic_on_oops=0` —
the same runtime setting that turns the QEMU same-CPU clobber into a wedge instead of a panic.
The venus value is still unproven.
