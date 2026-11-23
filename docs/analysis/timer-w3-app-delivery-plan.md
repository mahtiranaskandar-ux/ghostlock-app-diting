# Timer route (CVE-2026-98260) — W3, the app delivery plan

Status 2026-11-22 — **proposed, no code yet.** Authority for the mechanism, the profiles and the
device record: `CVE-2026-98260-ABSORB-PLAN.md` (the root repo) §3 W3, §8.2, §8.4. This document
covers only the app wiring that plan leaves open; it changes no exploit mechanism, no native wire,
and no profile schema.

## 1. Scope

Make the shell timer route **runnable on the device through the app**: the app stages the tool
over Shizuku, hands it the canonical profile document, runs it, owns the run log and the
reboot-per-miss contract, and puts a consented entry in the UI.

Non-goals (later workstreams):

- the timer-route *selection* policy (W4: top-level fallback/coverage ordering against
  GhostLock/DirtyFrag);
- the counterfactual/extractor work and any new profile derivation (W2 is closed);
- any change to the tool's exploit logic (the shipped source is frozen; see §3.1);
- the duchamp 6.1 leg (paused by the absorb plan).

## 2. What already exists

- **Profile side (W2, shipped):** `timer_98260` block parsed and validated
  (`profile-core` `Timer98260`), both timer assets bundled (5.10.236 `core_state` + fake group;
  6.1.138), `ProfileResolver` accepts the optional block, `BundledTimerBlocksTest` checks every
  bundled block, `ship_golden_profiles.py` owns the store-to-bundle values.
- **Canonical document:** `profile-core` `ProfileDocument.render(merged)` emits the self-contained
  JSON with top-level `release`, `schema_version`, `kernel_major`, `kernel`, `native`,
  `timer_98260` — exactly the document the tool reads. It is **not yet called by any app path**;
  this plan is its first caller.
- **Shizuku delivery:** `GhostlockUserService` is a Shizuku UserService (uid 2000, Seccomp 0) that
  already (a) locates `libghostlock.so` in `applicationInfo.nativeLibraryDir`, (b) creates
  `/data/local/tmp/ghostlock-app`, (c) runs `ManagerScanner` and stages a suitable `ksud`,
  (d) launches a binary with `ProcessBuilder`, writes its output to a log file and tails it to the
  app over `IGhostlockCallback`, so the exploit is never backpressured by binder.
- **Run store:** `withDebugAttackLog(entry, …)` opens `files/runs/<stamp>/`, timestamps every
  line, records `state=completed|failed`, and `markInterruptedRunLogs()` marks a run with no
  terminal state at the next app start (the hard-reset path). The run-store entry is the metric.
- **Shizuku request flow (existing):** `onStatusClick()` opens Shizuku when it is not running and
  requests permission when required, and `SupportStatus.AccessPending` is the home's "profile
  exists but access is not ready" state. The timer entry reuses both — with one difference: the
  timer path does not consult the native `shizukuEnabled` toggle (see §3.5).
- **Boot identity:** `currentBootId()` reads `/proc/sys/kernel/random/boot_id` (used by the anchor
  state); it is the reboot-contract key.
- **Native packaging:** `src/Makefile` target → `build/native/<bin>` → `prepareGhostlockJniLibs`
  → `app/src/main/jniLibs/arm64-v8a/lib*.so`; `useLegacyPackaging` extracts them to
  `nativeLibraryDir` at install, which is where the service executes them.

## 3. Decisions

### 3.1 Ship the tool from source, built by the app's native build

The tool source is on disk at `external/cve-2026-98260/work/src/timer/` — `timer.c` (md5
`6e2e001fd0be442ddeacbb8ee9726e31`, the boundary build), `profile_json.c/.h`, `kbuilds.h`,
`kbuilds/`. It is **vendored into the fork** as `src/timer/` (the app repo must be self-contained)
and built by a new `make timer` target using the same ONDK clang as the exploit:

```
aarch64-linux-android24-clang -O2 -g -Wall -Wextra -Wno-unused-parameter \
  -static -Isrc/timer -o build/native/timer \
  src/timer/timer.c src/timer/profile_json.c -lz -pthread
```

Verified 2026-11-22 in a scratch dir with ONDK r30.1: warning-free, static aarch64 ELF. Release
packaging strips it; the debug APK may keep the symbols. `prepareGhostlockJniLibs` copies it as
`libtimer.so` beside `libghostlock.so`. `kbuilds/` is 12 KiB and stays for the tool's no-profile
diagnostic mode; the product path always passes `--profile`.

Why source and not a prebuilt asset: the existing native delivery already has a build/copy path,
the tool's md5 is pinned in the record, and a binary blob in `assets/` would bypass the
warning/rebuild gates the rest of the payload gets.

### 3.2 Delivery through the existing UserService

Add one AIDL method (transaction 7):

```
void runTimer(in String profileJson, in String mode,
              @nullable String managerPackage, IGhostlockCallback callback) = 7;
```

The service, on its own thread:

1. verifies `Process.myUid() == SHELL_UID` and `Seccomp: 0` (same refusal as `runExploit`);
2. stages `nativeLibraryDir/libtimer.so` → `/data/local/tmp/ghostlock-app/timer`, chmod 0755;
3. writes `profileJson` → `/data/local/tmp/ghostlock-app/timer-profile.json` (0644);
4. resolves a suitable ksud with `ManagerScanner.resolve(...)` and stages it both to the work dir
   and to **`/data/local/tmp/ksud`** (the path the tool's own handoff script already probes when
   the installed manager's `libksud.so` lacks `late-load`, e.g. `com.sukisu.ultra`); refuses when
   none is suitable, as the native path does;
5. launches `timer --profile <workdir>/timer-profile.json <mode>` with cwd/env like the native
   (`HOME`, `TMPDIR`), stderr merged, output redirected to a per-run log file, and the same
   tailer-free-of-backpressure relay to `onLog`;
6. terminates on a **log marker**, not on process exit (the tool parks on both success and several
   failures):
   - `[+] done; fake cred page held by this process, reboot to clean` → `onComplete(0)`;
   - `[!] ` containing `parking without reap` → `onComplete(2)` (parked failure);
   - the process exiting first → its exit code;
   - a 10-minute watchdog → `onComplete(3)` (still alive, no verdict);
7. never kills the tool after a verdict: on success the parent must keep parking to hold the fake
   cred page, and on a parked failure it holds the failed-plant zombies. The app may unbind; the
   orphaned process is reparented to init and keeps parking until the reboot.

Mode is `fake` for the shipped way-2 profiles. `probe` is the same method with `mode = "probe"`
(S1's safe gate); `zero` (way 1) is not offered in the UI.

### 3.3 The document comes from the resolved profile

New controller method `renderTimerDocument(release, pair): String?`: `resolve(release, pair)`,
force `release` to the running `uname -r` (the tool refuses a mismatch), and call
`ProfileDocument.render(view)`. It returns null when the resolved profile has no `timer_98260`
block. The measured database is **not** merged in: every timer constant is profile data, and the
tool re-checks its own invariants.

The document is handed to the service as a string; the service writes it under
`/data/local/tmp/ghostlock-app/`. The app keeps a copy as a run sidecar (`timer-profile.json`,
plus its sha256) so a device report carries the exact input.

### 3.4 Outcome, run store, and the reboot contract

- A timer run is `withDebugAttackLog("timer", …)`: one run-store entry per attempt, the service's
  lines archived, the document stored as a sidecar.
- `completed` only on the hold marker (which follows the root script's `KSU_LOADED`); a parked
  failure or a watchdog is `failed`. A hard reset leaves no terminal state, and the existing
  `markInterruptedRunLogs()` marks it at the next start.
- **No retry on the same boot after a parked miss.** On a `failed` timer run the app records
  `timer_miss_boot_id = <boot_id>`; a new timer run is refused while the current boot id matches,
  with "reboot first". The key is cleared implicitly by a new boot. A panic/reboot needs nothing:
  the boot id changes.
- The UI status uses the existing `RunStatusService`/run sheet; success text says the root is held
  by a parked process and points at reboot-to-clean.

### 3.5 Preconditions and UX

**Shizuku is a mandatory start gate, not a visibility gate.** The module way (way 2, the product
gate) is Shizuku-only: the app has no other bridge to a shell context (the oracle survey closes
the app-native half), so when the module way is the selected — or the only — route, the app must
**ask the user for Shizuku before execution**, not hide the route.

What controls entry visibility:

- the resolved profile carries `timer_98260` **with the complete fake group** (way 2), and the
  kernel release matches exactly. That is the applicability fact; it does not depend on Shizuku.

What happens on tap, in order:

1. **Shizuku not ready** — reuse the existing flow (`onStatusClick` / `SupportStatus.AccessPending`):
   `NOT_RUNNING` → guide the user to start Shizuku (the existing `OpenShizuku` effect);
   `PERMISSION_REQUIRED` → request permission. The timer flow runs this **regardless of the
   native `shizukuEnabled` toggle** (that toggle is the native path's setting; Shizuku is
   mandatory here). The entry stays visible in the "Shizuku required" state and explains why;
   a missing Shizuku must never read as "kernel unsupported". Measured 2026-11-22: the snapshot's
   `shizukuStatus` is `NOT_REQUIRED` while the toggle is off (the repository gates it on
   `shizukuActive`), so the gate reads `repository.shizukuShellStatus()` instead — without that,
   the timer tap did **nothing visible** with the toggle off (found while verifying the consent
   removal on the duChamp).
2. **Shizuku READY** — the run starts. There is **no consent dialog** (operator decision,
   2026-11-22: "I don't think we need one actually"): the picker already names the timer route,
   and the reboot-bound facts (a miss parks the tool and needs a reboot; SELinux stays
   permissive until then) are logged as the run's first lines instead.

If the timer route is the only viable route (no native route/profile for the kernel), it is the
primary action and the Shizuku request is the first step of the flow. If the native route is
available, the timer entry is secondary and the same ask applies when it is chosen. Which route
is primary is W4's ordering decision; W3 guarantees the ask-and-continue behaviour either way.

`ManagerScanner` finding a late-load-capable ksud is checked at start by the service (it refuses
without one, as the native path does); it is not a user-actionable visibility gate.

A debug-only intent (`--ez timer_test true`, `BuildConfig.DEBUG`) runs the same path for the gate
without the dialog.

Placement: a capability-gated row on the home screen (secondary action, not the primary 执行), plus
an Advanced section showing Shizuku status, the document source (bundled/external), the ksud
verdict and the last timer run's state. W4 later decides the fallback ordering; W3 only needs the
entry, the Shizuku ask, and the consent.

## 4. Slices and gates

Each slice is independently shippable locally; nothing is pushed.

**S1 — delivery, no exploit.** Vendor `src/timer/`, add `make timer`, `libtimer.so`, the AIDL
method and the service staging; add `renderTimerDocument`; run the tool in `probe` mode from a
debug intent.
Gate: `make -C src timer` warning-free; existing host tests; profile-core/app tests; device
(diting): the app-driven probe prints `uname = <release>` + `profile matches the running kernel`
and the run-store sidecar has the same sha256 as the resolved document. No root, no reboot risk.

**S2 — the fake run and the run store.** The `fake` mode, marker-based outcomes, the document
sidecar, the failed/parked recording.
Gate (diting): one consented app-driven run → `[+] done; fake cred page held`, `KSU_LOADED`,
`su -c id` = uid 0 `u:r:ksu:s0`, run store `completed`, SELinux restored; one forced/observed
miss → `failed`/parked and no retry on the same boot. Exactly one timer run per fresh boot unless
the operator overrides.

**S3 — UX, the Shizuku ask, and consent.** The home row, the ask-and-continue Shizuku flow, the
reboot-first refusal, the Advanced facts. *(The consent dialog was built and then removed on
2026-11-22 — see step 2 above; the gate below no longer includes consent.)*
Gate (diting): with Shizuku not ready, the entry is **visible** and its tap opens Shizuku /
requests permission (including when the native `shizukuEnabled` toggle is off) — not "unsupported";
after READY the chain completes on a fresh boot; after a parked miss the entry refuses
with "reboot first" until the boot id changes.

**S4 — manager handoff.** Stage the app-chosen ksud to `/data/local/tmp/ksud`, log the chosen
manager and verdict, and cover the no-late-load manager case.
Gate (OnePlus 6.6.66): the app-driven way 2 finds/stages a working ksud on a device whose
installed manager is not late-load capable. (The timer plan's diting-first rule keeps S4 off
S1–S3's critical path.)

## 5. Gates that apply to all slices

- Native: `make -C src timer` zero warnings; the existing `make -C src ghostlock`, `lint-tidy`,
  `native-host-tests`.
- App: `:profile-core:test`, `:app:testDebugUnitTest`, `:app:assembleDebug` (the post-rebase tree
  is green: 70 + 264 tests, Sesame 828).
- New tests: the timer document render includes `timer_98260` and the exact `release`; the mode
  selection (`fake` iff the fake group is complete); the timer entry is **visible without
  Shizuku** and its action resolves through the Shizuku request path, independent of the native
  `shizukuEnabled` toggle; the outcome-marker parser (`done; fake cred page held` /
  `parking without reap` / exit / timeout); the reboot-first refusal keyed on the boot id.
- Device runs are preceded by the standing conditions (`PORT-PLAN.md`): awake screen, cpuset gate,
  CPU-active hold where the harness is used; the app path itself does not use the native driver.

## 6. Risks and open questions

- **Parked-process lifecycle.** The tool must outlive the app/service binding. The plan relies on
  orphaning to init; S2 verifies the hold line survives an app force-stop before the reboot. If it
  does not, the service keeps its binding until reboot (a foreground service already exists).
- **Shizuku teardown while the child is between stages.** The service thread reports a verdict
  before it returns; a teardown between the hold marker and the app's store write is covered by
  the store's `markInterrupted`.
- **Document drift.** The tool's first act is the release check and a document load; S1's probe is
  the cheap place to catch a renderer regression.
- **Log volume/backpressure.** The tool runs with its quiet/boundary build (the shipped source);
  the service writes to a file and tails it, never a pipe into binder.
- **Static binary size.** 9.5 MiB with debug info; strip in release (~0.3 MiB).
- **Open:** whether S1's probe should be visible in the debug UI only or also in a release
  diagnostics screen (operator call); whether the timer entry should live on the home screen or
  under Advanced until W4 (reference: the 2026-11-10 native-route placement arguments).

## 7. Record

Evidence directory for the gates: `external/port/timer-w3-app-<date>/` in the root repo, with the
built APK md5, the tool md5, the run-store stamps and the document sha256. The design decision
record (this file) is committed on the fork's `timer-route-absorb` branch, not pushed.

## 8. S1 implementation record (2026-11-22)

Implemented on the fork's `timer-route-absorb` (uncommitted at this writing):

- `src/timer/` — the boundary-build source (`timer.c` md5 `6e2e001f…`, `profile_json.c/.h`,
  `kbuilds.h`, `kbuilds/`), MIT header preserved; `profile_json_test.c` kept for a later host
  gate.
- `src/Makefile` — `make timer` with the API-24 ONDK clang, `-static -lz -pthread`; clean.
- `build.gradle.kts` — `buildTimerTool`; `prepareGhostlockJniLibs` ships it as `libtimer.so` and
  strips it (1,028,408 B packaged vs 9,514,896 B with DWARF).
- `IGhostlockUserService.runTimer` (transaction 7) + `GhostlockUserService` staging/launch/tail
  and the marker verdicts; the tool's parked processes are left alive.
- `ShizukuExploitRunner.runTimer` — a post-start drop is **not** retried (a second plant on the
  same boot is what the parked state forbids).
- `AndroidProfileConfigController.renderTimerDocument` → `ProfileDocument.render`, `release`
  forced to `uname -r`; `GhostlockRepository.timerProfileDocument` / `runTimerProbe`.
- Debug hook `am start --ez timer_test true` (DEBUG only).

Gates run: `make -C src timer` (warning-free), `make -C src ghostlock` (warning-free),
`lint-tidy`, `native-host-tests` (all pass), `:profile-core:test` 70/0,
`:app:testDebugUnitTest` 269/0 (the two new timer tests included), `:app:assembleDebug`
(Sesame v1.2(828)).

**S1 device gate — passed 2026-11-22 on the duchamp** (operator choice; the diting's installed
Sesame 836 is release-signed and ours is debug-signed, so the diting gate waits). The app-driven
`probe` completed on `6.1.138-…`: document 4520 B (sha256 `bf86dcd5…`), Shizuku READY as shell
(uid 2000), UserService connected, tool staged and run, `profile matches the running kernel`,
verdict 0, run store `state=completed`. Evidence:
`external/port/timer-w3-app-2026-11-22/` (root repo).

**A new product requirement came out of that gate:** the Shizuku UserService of **13.6.0** never
connects on MTK/HyperOS (the starter dies in `LoadedApk.makeApplicationInner` with an NPE — the
known 13.6.0 regression, RikkaApps/Shizuku #2048/#1198). 13.5.4 works. W3 must therefore treat a
UserService bind failure as a version/ROM fact with an actionable message (and document the
13.5.4 requirement), not as the generic "did not connect after 3 attempts" retry. The probe
evidence carries the version and the sources. **S2 (the `fake` run) is not run yet** — it can
panic and reboot the daily duChamp, so its device gate waits for the operator.

**S2 device gate — passed 2026-11-22 on the duchamp** (via the `--ez timer_run` debug hook; the
consent UI is S3). Run `20261122-103353`, `entry=timer`, **`state=completed`**: fake cred hit →
full-cap uid 0 (`CapEff 0x1ffffffffff`, Seccomp 0) → `ksud late-load rc=0` from the app-staged
`/data/local/tmp/ksud` (the manager scan found none) → `KSU_LOADED` → Enforcing restored → the
tool parked (`pid 32133`, `sigsuspend`) → verdict 0. A same-boot retry created no run entry and
started no second tool (refusal 4). Evidence:
`external/port/timer-w3-app-2026-11-22/s2-fake-duchamp/`. Two caveats: the module was already
live from an earlier late-load, and the cleanup reboot left the duChamp unrooted (its boot image
is not KSU-patched), so a **fresh stock-boot run was done next**: run `20261122-160657` on the
unrooted boot loaded `kernelsu` from the app-staged `/data/local/tmp/ksud` (`late-load rc=0`,
`KSU_LOADED`) and left `su` at `u:r:ksu:s0` with Enforcing — that gate now passes too (evidence:
`s2b-fresh-duchamp/`). The child's post-overlay log lines are missing in both runs (file writes
from the fake-cred child can be denied — the parent's hold marker and the device state carry the
result).

**S2 host-side — implemented 2026-11-22 (uncommitted).** `runTimerRoute`
(mode `fake`) joins `runTimerProbe` in the repository; `TimerBootContract` refuses a same-boot
retry after any attempt that reached the tool (pref `timer_attempt_boot_id`; refusal code 4);
the `--ez timer_run true` DEBUG hook drives it; the bind-failure log now names the Shizuku 13.6.0
UserService regression instead of only retrying; and the tool-log reader is `TimerLogTail` with a
final drain and unit tests — the S1 re-probe exposed that the last lines of a fast exit were being
lost (the probe's own facts vanished from one run's log). Gates: `:profile-core:test` 70/0,
`:app:testDebugUnitTest` **277/0** (5 new tests), `:app:assembleDebug`. **S3 is not built**: the
home-vs-Advanced placement and the consent wording are the operator's call, and the current
dialog dispatch maps every plain `Confirm` to "enable Shizuku", so a timer consent needs its own
typed dialog rather than reusing that path. *(History: S3 did land with a typed `TimerConsent`;
the operator removed it on 2026-11-22 together with the ask fix above.)*

## 9. Device runs — POCO 6.1.138 and OnePlus 6.6.66 (2026-11-23)

The standalone tool (`build/native/timer`, md5 `d49e871e…`) with each rig's golden `timer_98260`
block rendered via `make-doc.py` and passed as `--profile`; one attempt per fresh boot, detached
(the success path parks), a reboot after each:

- **POCO X6 Pro (`duchamp`, 6.1.138): 1/1 full chain.** `[wake] child spinning on cpu=3`, `cred
  writes landed`, `[child] woke uid=0`, `setuid(0)=0`, worker → ReSukiSU 4.2.0-rc3
  `/data/local/tmp/ksud`, `[ksu] late-load rc=0`, `KSU_LOADED`, parked.
- **OnePlus 13s (`CPH2723`, 6.6.66): 1/2.** Attempt 1 was a plant miss (`child woke uid=2000`,
  `setuid(0)=-1`, the fake-page uid plant `free_seen=0`); attempt 2 completed the chain (`woke
  uid=0`, KernelSU-Next 3.4.2 `/data/adb/ksu/bin/ksud`, `late-load rc=0`, `KSU_LOADED`, parked).

Both probes were clean (tracefs dir/trigger/raw, slab and config matched). The 6.6.66 block was
checked against the device's own Image BTF (`task_struct.cred 0x820`, `real_cred 0x818`, task
4800, cred 184, uid 8, security 128, caps 48), so attempt 1 was a timing miss, not a wrong fact.
`--profile` supplies the per-kernel kbuild; the compiled `kbuilds/` headers are only the
profile-less fallback. The 6.6.66 block is on the 859 line only, not in the 835 asset — an
app-level 6.6.66 timer run needs that line (or a generated profile). Both rigs are guard-less:
regression only; the evasion is still the Neo7's to show.
