// The KernelSnitch leak address grid.
//
// A scan step reaches object j of a slab when it divides j*stride, and reaches
// every object exactly when it divides the stride. The observed family strides
// (960, 1024, 1088, 1216, 1280) are all multiples of 32, so 32 is the primary
// step; 8 is the universal last resort for any 8-aligned object.
#pragma once

#include <cstddef>
#include <cstdint>

namespace ghostlock::kernelsnitch {
    inline constexpr std::size_t SCAN_PRIMARY_INNER = 32;
    inline constexpr std::size_t SCAN_LAST_RESORT_INNER = 8;
    /* 4 KiB instead of the order-3 32 KiB: removes the slab-order assumption
     * from the outer grid without changing the candidate count. */
    inline constexpr std::size_t SCAN_OUTER = 4096;

    constexpr bool scan_step_covers(std::size_t inner, std::size_t stride) {
        return inner != 0 && stride % inner == 0;
    }

    /* Scan phases. Phase 1 keeps the built-in identity ceiling; phase 2 covers
     * only the model tail beyond it, so a device whose measured span (or the
     * compiled model end) fits below the ceiling never pays the extra passes.
     * The second phase runs only after every phase-1 shape missed. */
    constexpr std::size_t scan_phase1_ceiling(std::size_t model_end,
                                              std::size_t identity_end) {
        return model_end < identity_end ? model_end : identity_end;
    }

    constexpr bool scan_has_tail(std::size_t model_end, std::size_t phase1_ceiling) {
        return model_end > phase1_ceiling;
    }

    struct scan_bounds {
        std::size_t start;
        std::size_t end;
    };

    /* Intersect one bank with [floor, ceiling); false when the intersection is
     * empty. A bank straddling the phase-1 ceiling contributes only its tail. */
    constexpr bool clip_scan_range(std::uint64_t bank_start, std::uint64_t bank_end,
                                   std::size_t floor, std::size_t ceiling,
                                   scan_bounds *out) {
        const std::uint64_t start = bank_start > floor ? bank_start : floor;
        const std::uint64_t end = bank_end < ceiling ? bank_end : ceiling;
        if (start >= end) return false;
        if (out) {
            out->start = static_cast<std::size_t>(start);
            out->end = static_cast<std::size_t>(end);
        }
        return true;
    }
} // namespace ghostlock::kernelsnitch
