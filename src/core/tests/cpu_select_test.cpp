/* Host fixed-vector test for the runtime CPU pair ranking: big cores first,
 * then CPU id; fewer than two distinct CPUs is a refusal, not a guess. */
#include "../session/cpu_select.h"

#include <cassert>
#include <cstdint>
#include <cstdio>
#include <vector>

using ghostlock::cpu_select::Candidate;

int32_t main(void) {
    int main_cpu = -1;
    int consumer_cpu = -1;

    /* The duchamp's real shape: 0-3 little, 4-6 big, 7 prime. */
    std::vector<Candidate> duchamp = {
        {0, 2200000}, {1, 2200000}, {2, 2200000}, {3, 2200000},
        {4, 3200000}, {5, 3200000}, {6, 3200000}, {7, 3350000},
    };
    assert(ghostlock::cpu_select::select_pair(duchamp, &main_cpu, &consumer_cpu));
    assert(main_cpu == 7 && consumer_cpu == 4);

    /* An unavailable prime drops out of the candidate list before ranking. */
    std::vector<Candidate> no_seven = {
        {0, 2200000}, {4, 3200000}, {5, 3200000}, {6, 3200000},
    };
    assert(ghostlock::cpu_select::select_pair(no_seven, &main_cpu, &consumer_cpu));
    assert(main_cpu == 4 && consumer_cpu == 5);

    /* Equal frequencies tie-break by CPU id. */
    std::vector<Candidate> ties = {{5, 3200000}, {4, 3200000}, {7, 3350000}};
    assert(ghostlock::cpu_select::select_pair(ties, &main_cpu, &consumer_cpu));
    assert(main_cpu == 7 && consumer_cpu == 4);

    /* No cpufreq node (QEMU or an unreadable sysfs): id order. */
    std::vector<Candidate> no_freq = {{1, 0}, {0, 0}};
    assert(ghostlock::cpu_select::select_pair(no_freq, &main_cpu, &consumer_cpu));
    assert(main_cpu == 0 && consumer_cpu == 1);

    /* One usable CPU cannot host both roles. */
    std::vector<Candidate> one = {{4, 3200000}};
    assert(!ghostlock::cpu_select::select_pair(one, &main_cpu, &consumer_cpu));
    assert(!ghostlock::cpu_select::select_pair({}, &main_cpu, &consumer_cpu));

    puts("cpu_select_test: ok");
    return 0;
}
