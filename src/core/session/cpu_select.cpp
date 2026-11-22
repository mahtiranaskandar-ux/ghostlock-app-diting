#include "session/cpu_select.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>

#include <sched.h>

#include "kernelsnitch/utils.h"

namespace ghostlock::cpu_select {
    namespace {
        long read_max_khz(int cpu) {
            char path[96];
            std::snprintf(path, sizeof(path),
                          "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
            FILE *f = std::fopen(path, "r");
            if (!f) return 0;
            char buffer[32] = {};
            const bool read = std::fgets(buffer, sizeof(buffer), f) != nullptr;
            std::fclose(f);
            return read ? std::strtol(buffer, nullptr, 10) : 0;
        }
    } // namespace

    bool select_pair(std::vector<Candidate> candidates, int *main_cpu,
                     int *consumer_cpu) {
        candidates.erase(
            std::remove_if(candidates.begin(), candidates.end(),
                           [](const Candidate &c) { return c.cpu < 0; }),
            candidates.end());
        std::sort(candidates.begin(), candidates.end(),
                  [](const Candidate &a, const Candidate &b) {
                      if (a.max_khz != b.max_khz) return a.max_khz > b.max_khz;
                      return a.cpu < b.cpu;
                  });
        if (candidates.size() < 2) return false;
        if (main_cpu) *main_cpu = candidates[0].cpu;
        if (consumer_cpu) *consumer_cpu = candidates[1].cpu;
        return true;
    }

    std::vector<Candidate> available_candidates() {
        std::vector<Candidate> out;
        cpu_set_t allowed;
        if (!cpu_affinity::read_mask(&allowed)) {
            pr_warning("cpu select: sched_getaffinity failed errno=%d\n", errno);
            return out;
        }
        char allowed_text[128] = {};
        cpu_affinity::format_mask(allowed, allowed_text, sizeof(allowed_text));
        pr_info("cpu select: allowed=%s\n", allowed_text);
        for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
            if (!CPU_ISSET(cpu, &allowed)) continue;
            /* A cpuset ceiling or an inactive CPU refuses here even though the
             * affinity mask still lists it; this probe is the only truth. The
             * shared probe restores the mask after each candidate. */
            int probe_error = 0;
            if (!cpu_affinity::probe_cpu(cpu, nullptr, &probe_error)) {
                pr_warning("cpu select: cpu%d refused pin errno=%d\n",
                           cpu, probe_error);
                continue;
            }
            out.push_back({cpu, read_max_khz(cpu)});
        }
        return out;
    }
} // namespace ghostlock::cpu_select
