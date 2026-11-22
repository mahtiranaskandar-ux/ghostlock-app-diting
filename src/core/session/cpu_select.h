#ifndef GHOSTLOCK_SESSION_CPU_SELECT_H
#define GHOSTLOCK_SESSION_CPU_SELECT_H

#include <vector>

namespace ghostlock::cpu_select {
    /* One usable CPU and its advertised maximum frequency (kHz; 0 when the
     * cpufreq node is absent, e.g. QEMU). */
    struct Candidate final {
        int cpu = -1;
        long max_khz = 0;
    };

    /* Pure ranking: max_khz descending, then CPU id ascending. Fills the two
     * roles and returns false when fewer than two distinct CPUs are given.
     * The caller decides the roles; the pair only has to be two CPUs. */
    bool select_pair(std::vector<Candidate> candidates, int *main_cpu,
                     int *consumer_cpu);

    /* The CPUs this process may use *now*: every CPU in its affinity mask that
     * also accepts a sched_setaffinity probe (a cpuset or cpu_active refusal
     * excludes it), with its max frequency. Restores the original mask before
     * returning. */
    std::vector<Candidate> available_candidates();
} // namespace ghostlock::cpu_select

#endif
