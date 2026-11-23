/*
 * Host test for the W2 victim spin protocol (session/victim_spin.h).
 *
 * The device build drives these helpers across a fork(): the child publishes
 * "armed", spins on the release sequence and makes no syscall in between. Here
 * the same sequence logic runs on two host threads, which pins the property the
 * protocol exists for -- a stale ready/release from the previous attempt must
 * never satisfy the next one.
 */

#include "session/victim_spin.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <thread>

namespace {
    using ghostlock::session::victim::spin_child_begin;
    using ghostlock::session::victim::spin_child_ready;
    using ghostlock::session::victim::spin_child_released;
    using ghostlock::session::victim::spin_parent_arm;
    using ghostlock::session::victim::spin_parent_armed;
    using ghostlock::session::victim::spin_parent_release;
    using ghostlock::session::victim::SpinPage;

    bool expect(bool condition, const char *message) {
        if (!condition) std::printf("FAIL: %s\n", message);
        return condition;
    }

    bool test_helpers() {
        SpinPage page{};
        bool ok = true;
        const uint32_t s1 = spin_parent_arm(&page);
        ok &= expect(s1 == 1, "helpers: first arm is sequence 1");
        ok &= expect(!spin_parent_armed(&page, s1),
                     "helpers: not armed before the child reports");
        ok &= expect(!spin_child_released(&page, s1),
                     "helpers: not released before the parent sets go");
        spin_child_ready(&page, s1);
        ok &= expect(spin_parent_armed(&page, s1),
                     "helpers: armed after the child reports");
        spin_parent_release(&page, s1);
        ok &= expect(spin_child_released(&page, s1),
                     "helpers: released after go");
        /* Stale state from the previous round must not satisfy the next one. */
        const uint32_t s2 = spin_parent_arm(&page);
        ok &= expect(s2 == 2, "helpers: second arm increments");
        ok &= expect(!spin_parent_armed(&page, s2),
                     "helpers: stale armed_seq does not arm the next round");
        ok &= expect(!spin_child_released(&page, s2),
                     "helpers: stale go_seq does not release the next round");
        return ok;
    }

    bool test_round_trip() {
        SpinPage page{};
        std::atomic<int32_t> stop{0};
        std::atomic<int32_t> child_exits{0};
        std::thread child([&] {
            uint32_t seen = 0;
            while (stop.load(std::memory_order_acquire) == 0) {
                const uint32_t seq = spin_child_begin(&page);
                if (seq == seen) {
                    std::this_thread::yield();
                    continue;
                }
                seen = seq;
                spin_child_ready(&page, seq);
                while (!spin_child_released(&page, seq)) {
                    if (stop.load(std::memory_order_acquire) != 0) break;
                }
            }
            child_exits.store(1, std::memory_order_release);
        });

        bool ok = true;
        for (int32_t round = 0; round < 1000; round++) {
            const uint32_t seq = spin_parent_arm(&page);
            int32_t armed = 0;
            for (int32_t spin = 0; spin < 100000 && armed == 0; spin++) {
                armed = spin_parent_armed(&page, seq);
            }
            ok &= expect(armed == 1, "round trip: child reports armed");
            if (armed == 0) break;
            spin_parent_release(&page, seq);
        }
        stop.store(1, std::memory_order_release);
        /* Release a round the child may still be spinning in. */
        spin_parent_release(&page, __atomic_load_n(&page.arm_seq, __ATOMIC_ACQUIRE));
        child.join();
        ok &= expect(child_exits.load(std::memory_order_acquire) == 1,
                     "round trip: child exits");
        return ok;
    }
} // namespace

int main() {
    bool ok = true;
    ok &= test_helpers();
    ok &= test_round_trip();
    if (ok) {
        std::puts("victim_spin_test: ok");
        return 0;
    }
    return 1;
}
