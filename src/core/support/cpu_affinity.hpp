#ifndef GHOSTLOCK_SUPPORT_CPU_AFFINITY_HPP
#define GHOSTLOCK_SUPPORT_CPU_AFFINITY_HPP

/*
 * The one CPU-affinity primitive used by every caller: the arm64 native, the
 * armv7 compat helper, and the host tests. It is deliberately dependency-free
 * (libc only, no logging, no exceptions) because the compat helper is a
 * static armv7 binary that must not pull in the native layers.
 *
 * Every caller keeps its own policy and log format:
 *   - the native's pin_to_core() stays fatal on a failed set;
 *   - the race threads map a failed exact pin to ROUTE_RETRYABLE / step 34;
 *   - the compat helper falls back to another CPU;
 *   - the CPU picker probes candidates without changing the final mask.
 *
 * The facts this returns are distinct on purpose:
 *   set_ok       sched_setaffinity returned 0 (a single-CPU mask also means the
 *                CPU was in cpu_active_mask at that instant);
 *   readback_ok  sched_getaffinity succeeded afterwards;
 *   exact        the readback is exactly the requested CPU (CPU_COUNT == 1).
 * A successful set with a non-exact readback is a vendor/cpuset override and
 * is visible, not retried here.
 */

#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <sched.h>

namespace ghostlock::cpu_affinity {
    struct PinResult final {
        bool set_ok = false;
        bool readback_ok = false;
        bool exact = false;
        int error = 0;
        cpu_set_t mask{};
        int now_cpu = -1;
    };

    [[nodiscard]] inline bool valid_cpu(int cpu) noexcept {
        return cpu >= 0 && cpu < CPU_SETSIZE;
    }

    [[nodiscard]] inline bool read_mask(cpu_set_t *out) noexcept {
        if (out == nullptr) return false;
        CPU_ZERO(out);
        return sched_getaffinity(0, sizeof(*out), out) == 0;
    }

    [[nodiscard]] inline bool mask_is_exact(const cpu_set_t &mask, int cpu) noexcept {
        return valid_cpu(cpu) && CPU_COUNT(&mask) == 1 && CPU_ISSET(cpu, &mask);
    }

    /* The kernel's Cpus_allowed_list form ("0,2-3" is not produced; commas
     * only), for the callers' logs. */
    inline bool format_mask(const cpu_set_t &mask, char *out, std::size_t cap) noexcept {
        if (out == nullptr || cap == 0) return false;
        std::size_t used = 0;
        out[0] = '\0';
        for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
            if (!CPU_ISSET(cpu, &mask)) continue;
            const int written = std::snprintf(out + used, cap - used,
                                               used ? ",%d" : "%d", cpu);
            if (written < 0 || static_cast<std::size_t>(written) >= cap - used) {
                out[cap - 1] = '\0';
                return false;
            }
            used += static_cast<std::size_t>(written);
        }
        if (used == 0) std::snprintf(out, cap, "none");
        return true;
    }

    inline PinResult set_mask(const cpu_set_t &want) noexcept {
        PinResult result;
        errno = 0;
        result.set_ok = sched_setaffinity(0, sizeof(want), &want) == 0;
        result.error = result.set_ok ? 0 : errno;
        result.readback_ok = result.set_ok && read_mask(&result.mask);
        if (result.set_ok && !result.readback_ok) result.error = errno;
        result.now_cpu = sched_getcpu();
        return result;
    }

    /* Set one CPU on the calling thread and require the readback to be that
     * CPU. */
    inline PinResult pin_current_thread(int cpu) noexcept {
        PinResult result;
        if (!valid_cpu(cpu)) {
            result.error = EINVAL;
            result.now_cpu = sched_getcpu();
            return result;
        }
        cpu_set_t want;
        CPU_ZERO(&want);
        CPU_SET(cpu, &want);
        result = set_mask(want);
        result.exact = result.readback_ok && mask_is_exact(result.mask, cpu);
        return result;
    }

    /* The reset mask: every CPU bit, intersected by the kernel with the
     * process's cpuset. */
    inline PinResult pin_all_current_thread() noexcept {
        cpu_set_t want;
        std::memset(&want, 0xff, sizeof(want));
        return set_mask(want);
    }

    /* Probe one CPU without changing the caller's final mask: save, pin, read
     * back, restore. This is the authoritative "can this (possibly 32-bit)
     * thread run here" test; the readback must be the single CPU. */
    [[nodiscard]] inline bool probe_cpu(int cpu, cpu_set_t *saved_out = nullptr,
                                        int *error_out = nullptr) noexcept {
        cpu_set_t saved;
        if (!read_mask(&saved)) return false;
        if (saved_out != nullptr) *saved_out = saved;
        const PinResult result = pin_current_thread(cpu);
        if (error_out != nullptr) *error_out = result.error;
        (void) sched_setaffinity(0, sizeof(saved), &saved);
        return result.exact;
    }
} // namespace ghostlock::cpu_affinity

#endif // GHOSTLOCK_SUPPORT_CPU_AFFINITY_HPP
