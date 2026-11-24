package com.sesame.app.data

/**
 * A minimal cleaned v3 document for the tests that exercise merge/render.
 * The caller states only what it cares about; the fixed shape is complete.
 */
internal object CleanedProfileFixture {
    fun document(
        release: String,
        route: String? = "select_stack",
        branch: Map<String, Any?> = emptyMap(),
        anchor: Map<String, Any?>? = null,
        carrier: String? = null,
    ): ValueMap = valueMapOf(
        "schema_version" to 3L,
        "release" to release,
        "devices" to null,
        "kernel" to valueMapOf(
            "image_base" to null,
            "symbols" to valueMapOf(
                "init_task" to "0x1",
                "init_cred" to "0x2",
                "root_task_group" to "0x3",
                "empty_zero_page" to "0x4",
                "selinux_state" to "0x5",
                "selinux_blob_sizes" to "0x6",
                "security_hook_heads" to "0x7",
                "nf_logger" to "0x8",
                "loggers_0_1" to "0x9",
                "boot_id" to "0xa",
                "panic_on_warn" to null,
                "root_user" to null,
                "init_user_ns" to null,
                "init_ucounts" to null,
                "init_groups" to null,
            ),
            "struct" to valueMapOf(
                "task" to valueMapOf(
                    "prio" to 1L, "normal_prio" to 2L, "sched_task_group" to 3L,
                    "pi_lock" to 4L, "pi_waiters" to 5L, "pi_top_task" to 6L,
                    "pi_blocked_on" to 7L, "pid" to 8L, "tgid" to 9L,
                    "atomic_flags" to 10L, "real_cred" to 11L, "cred" to 12L,
                    "comm" to 13L, "tasks" to 14L, "seccomp" to 15L,
                ),
                "cred" to valueMapOf(
                    "copy_size" to 128L,
                    "usage" to valueMapOf("offset" to null, "size" to 4L, "value" to 1L),
                    "uid" to 4L,
                    "security" to 120L,
                    "security_sid" to null,
                    "caps" to valueMapOf("offset" to 40L, "count" to 5L, "mask" to null),
                    "refs" to valueMapOf(
                        "user" to valueMapOf("offset" to 128L, "image" to null),
                        "user_ns" to valueMapOf("offset" to 136L, "image" to null),
                        "ucounts" to valueMapOf("offset" to 144L, "image" to null),
                        "group_info" to valueMapOf("offset" to 152L, "image" to null),
                    ),
                ),
                "waiter" to valueMapOf(
                    "shape" to "compact", "pi_tree" to 24L, "task" to 48L,
                    "lock" to 56L, "wake_state" to 64L,
                ),
                "rt_mutex" to valueMapOf("size" to 32L),
                "selinux_state" to valueMapOf("enforcing" to 0L, "initialized" to 2L),
            ),
            "heap" to valueMapOf("stride" to null, "collisions" to 4L),
            "dirtyfrag" to "unknown",
            "kdp" to false,
        ),
        "ghostlock" to valueMapOf(
            "syscall" to route,
            "carrier" to carrier,
            "anchor" to anchor,
            "syscalls" to valueMapOf(
                "select_stack" to if (route == "select_stack") {
                    valueMapOf(
                        "native" to branch,
                        "compat" to null,
                    )
                } else {
                    null
                },
                "sigreturn" to if (route == "sigreturn") {
                    valueMapOf("native" to branch, "compat" to null)
                } else {
                    null
                },
                "multicast_waiter" to if (route == "multicast_waiter") {
                    valueMapOf("native" to branch, "compat" to null)
                } else {
                    null
                },
                "tcp_zerocopy" to if (route == "tcp_zerocopy") {
                    valueMapOf("native" to branch, "compat" to null)
                } else {
                    null
                },
            ),
            "module" to null,
            "execution" to null,
        ),
        "timer" to null,
    )
}
