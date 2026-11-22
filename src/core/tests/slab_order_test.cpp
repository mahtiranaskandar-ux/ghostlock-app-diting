/* Host test for the runtime mm_struct slab-order derivation (S8).
 *
 * Pins the calculate_order prior against the two measured CPU counts, the
 * candidate predicate's exact pass fractions (the true order always passes; a
 * false order passes min(S,S_true)/max(S,S_true) of the leaks), and the vote's
 * keep-the-prior / demote-on-failure policy.
 */
#include "../kernelsnitch/slab_order.h"

#include <cassert>
#include <cstddef>
#include <cstdint>

using ghostlock::kernelsnitch::SLAB_ORDER_MAX;
using ghostlock::kernelsnitch::SLAB_ORDER_MIN;
using ghostlock::kernelsnitch::SlabOrderVote;
using ghostlock::kernelsnitch::slab_bytes;
using ghostlock::kernelsnitch::slab_candidate_passes;
using ghostlock::kernelsnitch::slab_order_prior;

namespace {
    constexpr std::uint32_t kStride = 960;
    constexpr std::uintptr_t kBase = 0x10000000;

    /* Every true slab position (aligned to the true slab) and every object in
     * it, scored against one candidate. */
    std::size_t count_passes(std::uint32_t true_order, std::uint32_t cand_order) {
        const std::size_t true_bytes = slab_bytes(true_order);
        const std::size_t objects = true_bytes / kStride;
        std::size_t passed = 0;
        if (cand_order >= true_order) {
            const std::size_t sub_blocks = slab_bytes(cand_order) / true_bytes;
            for (std::size_t j = 0; j < sub_blocks; ++j) {
                for (std::size_t i = 0; i < objects; ++i) {
                    const std::uintptr_t leaked = kBase + j * true_bytes + i * kStride;
                    if (slab_candidate_passes(leaked, kStride, cand_order)) ++passed;
                }
            }
        } else {
            for (std::size_t i = 0; i < objects; ++i) {
                const std::uintptr_t leaked = kBase + i * kStride;
                if (slab_candidate_passes(leaked, kStride, cand_order)) ++passed;
            }
        }
        return passed;
    }
} // namespace

int main() {
    /* calculate_order prior: 960 at 1/2/4/8/16 possible CPUs; the two other
     * sampled strides at 1/4/8. The 8-CPU row is the measured phone order. */
    assert(slab_order_prior(960, 1) == 1);
    assert(slab_order_prior(960, 2) == 2);
    assert(slab_order_prior(960, 4) == 2);
    assert(slab_order_prior(960, 8) == 3);
    assert(slab_order_prior(960, 16) == 3);
    assert(slab_order_prior(1024, 1) == 1);
    assert(slab_order_prior(1024, 4) == 2);
    assert(slab_order_prior(1024, 8) == 3);
    assert(slab_order_prior(1280, 1) == 2);
    assert(slab_order_prior(1280, 4) == 3);
    assert(slab_order_prior(1280, 8) == 3);
    /* A zero CPU count cannot happen; fls(0)+1 keeps the rule total. */
    assert(slab_order_prior(960, 0) == 1);

    /* The predicate's exact fractions. True order 1: 8 objects, candidate 0
     * catches the first 4, candidates 2/3 the first sub-block (8/16, 8/32).
     * True order 2: 17 objects. True order 3: 34 objects. */
    assert(count_passes(1, 0) == 4);
    assert(count_passes(1, 1) == 8);
    assert(count_passes(1, 2) == 8);
    assert(count_passes(1, 3) == 8);
    assert(count_passes(2, 0) == 4);
    assert(count_passes(2, 1) == 8);
    assert(count_passes(2, 2) == 17);
    assert(count_passes(2, 3) == 17);
    assert(count_passes(3, 0) == 4);
    assert(count_passes(3, 1) == 8);
    assert(count_passes(3, 2) == 17);
    assert(count_passes(3, 3) == 34);

    /* No observations: the prior decides, clamped into the candidate range. */
    {
        SlabOrderVote vote;
        assert(vote.observations() == 0);
        assert(vote.choose(1) == 1);
        assert(vote.choose(2) == 2);
        assert(vote.choose(3) == 3);
        assert(vote.choose(0) == SLAB_ORDER_MIN);
        assert(vote.choose(9) == SLAB_ORDER_MAX);
    }

    /* A leak at a slab base passes every candidate; the prior is kept. */
    {
        SlabOrderVote vote;
        vote.observe(kBase, kStride);
        assert(vote.observations() == 1);
        assert(vote.passes(3) == 1 && vote.fails(3) == 0);
        assert(vote.choose(3) == 3);
    }

    /* True order 1, prior 3: a slab at the second 8 KiB sub-block fails the
     * 16/32 KiB candidates and only order 1 survives. */
    {
        SlabOrderVote vote;
        vote.observe(kBase + 0x2000, kStride);
        assert(vote.fails(3) == 1);
        assert(vote.fails(2) == 1);
        assert(vote.passes(1) == 1);
        assert(vote.choose(3) == 1);
    }

    /* True order 3, prior 1: object 20 lies past the 4/16 KiB fits and only
     * order 3 survives. */
    {
        SlabOrderVote vote;
        vote.observe(kBase + 20 * kStride, kStride);
        assert(vote.fails(1) == 1);
        assert(vote.fails(2) == 1);
        assert(vote.passes(3) == 1);
        assert(vote.choose(1) == 3);
    }

    /* True order 2, prior 3: the sub-block-1 leak keeps orders 1 and 2; the
     * nearest survivor to the prior wins. */
    {
        SlabOrderVote vote;
        vote.observe(kBase + 0x4000, kStride);
        assert(vote.fails(3) == 1);
        assert(vote.passes(2) == 1);
        assert(vote.choose(3) == 2);
    }

    /* A failed prior stays demoted even when a later leak happens to pass it:
     * a failure is evidence against that order, and the intersection keeps
     * only orders that never failed. */
    {
        SlabOrderVote vote;
        vote.observe(kBase + 0x2000, kStride);
        assert(vote.choose(3) == 1);
        vote.observe(kBase, kStride);
        assert(vote.passes(3) == 1 && vote.fails(3) == 1);
        assert(vote.choose(3) == 1);
    }

    /* A stride that matches no candidate (a wrong profile value) leaves the
     * prior standing instead of voting for nothing. */
    {
        SlabOrderVote vote;
        vote.observe(kBase + 7, 1280);
        assert(vote.fails(1) == 1 && vote.fails(2) == 1 && vote.fails(3) == 1);
        assert(vote.choose(2) == 2);
    }

    return 0;
}
