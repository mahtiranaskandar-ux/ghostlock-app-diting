# The compat helper's CPU pair fallback (2026-11-19)

**Why.** The 6.1.134 Realme Neo7 report on the r10 build (versionCode 756, 2026-11-17) shows every
compat run ending at the pre-dance CPU pair handshake:
`status=1 step=34 err=22 route=0 calls=0`, with the consumer's preferred CPU refused and the
retry reporting `target=-1`. This is not a same-CPU dance (the handshake correctly refused); the
resolver had no alternative.

**Root cause.** Two defects in `src/core/compat/carrier_engine.cpp`'s fallback:

1. Its candidate universe was the calling thread's `sched_getaffinity` mask. The 32-bit helper is
   `fork()`ed from the native main thread after `pin_to_core(main_cpu)`, so the helper's threads
   inherit a single-CPU mask even when the cgroup's cpuset still permits others. Once the
   preferred consumer CPU was refused, the fallback's world was that one CPU minus the waiter's,
   i.e. empty.
2. Its usability test was `sched_setaffinity() == 0`, while `pin_cpu()` requires the readback to
   be exactly the requested CPU. A vendor affinity override (setaffinity returns success, the
   readback differs) makes those disagree, so the fallback could return the CPU `pin_cpu` had
   just refused on every retry.

**Reproduction.** The helper gained a test-only `--pair-test=<waiter>,<consumer>` mode: it
creates the waiter and consumer threads, runs the real `run_pair_handshake()`, and returns
before any route setup. On a rooted rig, confine the process to a two-CPU cpuset and start it
`taskset`-ed to one CPU (the inherited-mask shape), with the preferred consumer outside the
cpuset. Old code: 3/3 refusals (`target=-1`, result 34). The same setup also demonstrates the
ROM's 32-bit affinity policy: a little-cluster `taskset` request is rewritten, a single big-CPU
request is kept — which is what makes the one-CPU inherited mask reproducible.

**Fix.** `fallback_cpu()` probes every CPU `0..CPU_SETSIZE-1` with `pin_cpu()` (set + readback),
skips the other role's CPU, ranks the verified candidates by `cpuinfo_max_freq`, and restores the
thread's original mask; `resolve_cpu()` no longer takes the unused `allowed` mask. After the fix
the same repro establishes a distinct verified pair 3/3; with no alternative CPU available it
still refuses (fail-closed).

**Gates.** Helper and native builds zero warnings, `lint-tidy` clean, the full native host suite
passes. In the full app on the duchamp (6.1.138, the compat multicast profile), the device's
affinity policy refused both picked CPUs (7/4) for the helper; the fallback established pair 0/1
and W1, W1p ×2, W2 and W3-0 completed. The chain then died at W3 attempt 1 and the device
rebooted (`kernel_panic`). That is downstream of this change: the helper was confined to little
cores, and whether the compat route is viable on a little-core pair is open (the route's known
rate is the other candidate). No pstore was captured (no Shizuku, no from-boot `su`).

**Open.** (a) The runtime pair picker's `available_candidates()` still probes with
`sched_setaffinity` only; a pick based on an overridden pin is left to the handshake's fallback.
(b) Review whether a fallback pair on a different cluster than the preferred CPU should be
allowed for this carrier, or refused until the timing is measured on the little cores.
