#ifndef GHOSTLOCK_COMPAT_CARRIER_ENGINE_H
#define GHOSTLOCK_COMPAT_CARRIER_ENGINE_H

/*
 * The compat carrier's run mode inside the armv7 helper.
 *
 * One command is one attempt: the engine runs the waiter/owner/consumer dance
 * with the native-packed bitmap, parks the waiter in `_newselect`, drains and
 * disarms, and fills the result counters. It never interprets the bitmap's
 * kernel words and never sees a profile.
 */

#include "compat/carrier_protocol.h"

namespace ghostlock::compat {
    /* Diagnostics go to this fd; the helper duplicates its stdout above the
     * route's parked 0..319 range and installs it here. */
    void set_log_fd(int fd);

    /* Blocking. Fills result->status/step/error_number/select_result/
     * select_errno/calls/successes/drain_ms/userspace_clean/kernel_disarmed.
     * A dirty outcome (status 3) leaves the process safe to exit; the caller
     * writes the result and exits 70 to mirror the native's dirty stop. */
    void run_carrier_attempt(const CarrierCommand &command, CarrierResult *result);

    /* Test-only: create the waiter and consumer threads, run the pre-dance
     * CPU pair handshake for the two given CPUs, and return before any route
     * setup. 0 when a distinct, readback-verified pair was established; the
     * refusal step (34 or 41) otherwise. The process must exit afterwards:
     * the threads and their engine/command are intentionally leaked. */
    int32_t run_pair_test(int32_t waiter_cpu, int32_t consumer_cpu) noexcept;
} // namespace ghostlock::compat

#endif
