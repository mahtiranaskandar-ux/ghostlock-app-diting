package com.sesame.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sesame.app.R

/** Localized display name for a field path, falling back to the key. */
@Composable
internal fun fieldLabel(path: String, fallback: String): String =
    fieldLabelRes(path)?.let { stringResource(it) } ?: fallback

/** The tuning block keeps its historical keys under `ghostlock.execution`. */
private val ExecutionLabels: Map<String, Int> = mapOf(
    "execution.handoff.enforce_poll_attempts" to R.string.field_execution_handoff_enforce_poll_attempts,
    "execution.handoff.enforce_poll_interval_ms" to R.string.field_execution_handoff_enforce_poll_interval_ms,
    "execution.handoff.module_poll_attempts" to R.string.field_execution_handoff_module_poll_attempts,
    "execution.handoff.module_poll_interval_ms" to R.string.field_execution_handoff_module_poll_interval_ms,
    "execution.handoff.pre_dispatch_settle_ms" to R.string.field_execution_handoff_pre_dispatch_settle_ms,
    "execution.heap.kernelsnitch_timeout_ms" to R.string.field_execution_heap_kernelsnitch_timeout_ms,
    "execution.heap.prepare_max_attempts" to R.string.field_execution_heap_prepare_max_attempts,
    "execution.heap.prepare_timeout_ms" to R.string.field_execution_heap_prepare_timeout_ms,
    "execution.race.route_wait_ms" to R.string.field_execution_race_route_wait_ms,
    "execution.race.route_done_timeout_ms" to R.string.field_execution_race_route_done_timeout_ms,
    "execution.race.setup_settle_us" to R.string.field_execution_race_setup_settle_us,
    "execution.race.state_poll_interval_us" to R.string.field_execution_race_state_poll_interval_us,
    "execution.recommended_cpus.consumer" to R.string.field_execution_recommended_cpus_consumer,
    "execution.recommended_cpus.main" to R.string.field_execution_recommended_cpus_main,
    "execution.routes.select_stack.consumer_burst_calls" to R.string.field_execution_routes_select_stack_consumer_burst_calls,
    "execution.routes.select_stack.consumer_max_calls" to R.string.field_execution_routes_select_stack_consumer_max_calls,
    "execution.routes.select_stack.enter_delay_us" to R.string.field_execution_routes_select_stack_enter_delay_us,
    "execution.routes.select_stack.timeout_us" to R.string.field_execution_routes_select_stack_timeout_us,
    "execution.routes.tcp_zerocopy.arm_sequence" to R.string.field_execution_routes_tcp_zerocopy_arm_sequence,
    "execution.routes.tcp_zerocopy.attempts" to R.string.field_execution_routes_tcp_zerocopy_attempts,
    "execution.routes.tcp_zerocopy.post_receive_hold_iterations" to R.string.field_execution_routes_tcp_zerocopy_post_receive_hold_iterations,
    "execution.stages.w1_attempts" to R.string.field_execution_stages_w1_attempts,
    "execution.stages.w1_scratch_repair_attempts" to R.string.field_execution_stages_w1_scratch_repair_attempts,
    "execution.stages.w1_settle_us" to R.string.field_execution_stages_w1_settle_us,
    "execution.stages.w2_attempts" to R.string.field_execution_stages_w2_attempts,
    "execution.stages.w2_settle_us" to R.string.field_execution_stages_w2_settle_us,
    "execution.stages.w3_attempts" to R.string.field_execution_stages_w3_attempts,
    "execution.stages.w3_chain_rounds" to R.string.field_execution_stages_w3_chain_rounds,
    "execution.stages.w3_settle_us" to R.string.field_execution_stages_w3_settle_us,
)

private val SymbolLabels: Map<String, Int> = mapOf(
    "init_task" to R.string.field_off_init_task,
    "init_cred" to R.string.field_off_init_cred,
    "root_task_group" to R.string.field_off_root_task_group,
    "empty_zero_page" to R.string.field_off_empty_zero_page,
    "selinux_state" to R.string.field_symbol_selinux_state,
    "selinux_blob_sizes" to R.string.field_off_selinux_blob_sizes,
    "security_hook_heads" to R.string.field_off_security_hook_heads,
    "nf_logger" to R.string.field_off_slide_nfulnl_logger,
    "loggers_0_1" to R.string.field_off_slide_loggers_0_1,
    "boot_id" to R.string.field_off_slide_boot_id,
    "panic_on_warn" to R.string.field_symbol_panic_on_warn,
    "root_user" to R.string.field_symbol_root_user,
    "init_user_ns" to R.string.field_symbol_init_user_ns,
    "init_ucounts" to R.string.field_symbol_init_ucounts,
    "init_groups" to R.string.field_symbol_init_groups,
)

private val TaskLabels: Map<String, Int> = mapOf(
    "prio" to R.string.field_task_prio,
    "normal_prio" to R.string.field_task_normal_prio,
    "sched_task_group" to R.string.field_task_sched_task_group,
    "pi_lock" to R.string.field_task_pi_lock,
    "pi_waiters" to R.string.field_task_pi_waiters,
    "pi_top_task" to R.string.field_task_pi_top_task,
    "pi_blocked_on" to R.string.field_task_pi_blocked_on,
    "pid" to R.string.field_task_pid,
    "tgid" to R.string.field_task_tgid,
    "atomic_flags" to R.string.field_task_atomic_flags,
    "real_cred" to R.string.field_task_real_cred,
    "cred" to R.string.field_task_cred,
    "comm" to R.string.field_task_comm,
    "tasks" to R.string.field_task_tasks,
    "seccomp" to R.string.field_task_seccomp,
)

private val RefLabels: Map<String, Int> = mapOf(
    "user.offset" to R.string.field_cred_ref_user_offset,
    "user.image" to R.string.field_cred_ref_user_image,
    "user_ns.offset" to R.string.field_cred_ref_user_ns_offset,
    "user_ns.image" to R.string.field_cred_ref_user_ns_image,
    "ucounts.offset" to R.string.field_cred_ref_ucounts_offset,
    "ucounts.image" to R.string.field_cred_ref_ucounts_image,
    "group_info.offset" to R.string.field_cred_ref_group_info_offset,
    "group_info.image" to R.string.field_cred_ref_group_info_image,
)

internal fun fieldLabelRes(path: String): Int? {
    if (path.startsWith("ghostlock.execution.")) {
        return ExecutionLabels[path.removePrefix("ghostlock.")]
    }
    if (path.startsWith("devices.")) {
        return when {
            path.endsWith(".phys.load") -> R.string.field_kernel_phys_load
            path.endsWith(".phys.offset") -> R.string.field_kernel_phys_offset
            else -> null
        }
    }
    if (path.startsWith("kernel.symbols.")) {
        return SymbolLabels[path.removePrefix("kernel.symbols.")]
    }
    if (path.startsWith("kernel.struct.task.")) {
        return TaskLabels[path.removePrefix("kernel.struct.task.")]
    }
    if (path.startsWith("kernel.struct.cred.refs.")) {
        return RefLabels[path.removePrefix("kernel.struct.cred.refs.")]
    }
    if (path.startsWith("ghostlock.syscalls.multicast_waiter.")) {
        return when {
            path.endsWith(".waiter_off") -> R.string.field_mcast_waiter_off
            path.endsWith(".buffer_size") -> R.string.field_mcast_buffer_size
            path.endsWith(".futex_abi") -> R.string.field_futex_abi
            else -> null
        }
    }
    if (path.startsWith("ghostlock.syscalls.select_stack.")) {
        return when {
            path.endsWith(".waiter_shift") -> R.string.field_pselect_waiter_shift
            path.endsWith(".waiter_home") -> R.string.field_compat_waiter_home
            path.endsWith(".dst_depth") -> R.string.field_compat_dst_depth
            path.endsWith(".futex_abi") -> R.string.field_futex_abi
            else -> null
        }
    }
    if (path.startsWith("ghostlock.syscalls.sigreturn.")) {
        return when {
            path.endsWith(".plain") -> R.string.field_sigret_waiter_off_plain
            path.endsWith(".sve") -> R.string.field_sigret_waiter_off_sve
            else -> null
        }
    }
    if (path.startsWith("ghostlock.module.")) {
        return when (path.removePrefix("ghostlock.module.")) {
            "asset" -> R.string.field_module_asset
            "sha256" -> R.string.field_module_sha256
            "vendor_files" -> R.string.field_module_vendor_files
            else -> null
        }
    }
    return when (path) {
        "kernel.heap.stride" -> R.string.field_mm_struct_sz
        "kernel.heap.collisions" -> R.string.field_kernelsnitch_collisions
        "kernel.dirtyfrag" -> R.string.field_dirtyfrag
        "kernel.kdp" -> R.string.field_kdp
        "kernel.struct.cred.copy_size" -> R.string.field_cred_copy_size
        "kernel.struct.cred.usage.offset" -> R.string.field_cred_usage_offset
        "kernel.struct.cred.usage.size" -> R.string.field_cred_usage_size
        "kernel.struct.cred.usage.value" -> R.string.field_cred_usage_value
        "kernel.struct.cred.uid" -> R.string.field_cred_uid
        "kernel.struct.cred.security" -> R.string.field_cred_security
        "kernel.struct.cred.security_sid" -> R.string.field_cred_security_sid
        "kernel.struct.cred.caps.offset" -> R.string.field_cred_caps_offset
        "kernel.struct.cred.caps.count" -> R.string.field_cred_caps_count
        "kernel.struct.cred.caps.mask" -> R.string.field_cred_caps_value
        "kernel.struct.waiter.shape" -> R.string.field_waiter_shape
        "kernel.struct.waiter.pi_tree" -> R.string.field_waiter_pi_tree
        "kernel.struct.waiter.task" -> R.string.field_waiter_task
        "kernel.struct.waiter.lock" -> R.string.field_waiter_lock
        "kernel.struct.waiter.wake_state" -> R.string.field_waiter_wake_state
        "kernel.struct.rt_mutex.size" -> R.string.field_rt_mutex_size
        "kernel.struct.selinux_state.enforcing" -> R.string.field_off_selinux_enforcing
        "kernel.struct.selinux_state.initialized" -> R.string.field_selinux_initialized
        "ghostlock.syscall" -> R.string.field_syscall
        "ghostlock.carrier" -> R.string.field_carrier
        "ghostlock.anchor.image" -> R.string.field_anchor_image
        "ghostlock.anchor.bytes" -> R.string.field_anchor_bytes
        "ghostlock.anchor.stride" -> R.string.field_anchor_stride
        "ghostlock.anchor.scratch" -> R.string.field_anchor_scratch
        "timer.trigger" -> R.string.field_timer_trigger
        "timer.match_bits" -> R.string.field_timer_match_bits
        "timer.sites.timer.offset" -> R.string.field_timer_site
        "timer.sites.task_alloc.low_bits" -> R.string.field_timer_task_low_bits
        "timer.object_size" -> R.string.field_timer_object_size
        "timer.task_alloc_size" -> R.string.field_timer_task_alloc_size
        "timer.node_offset" -> R.string.field_timer_node_offset
        "timer.cred.request_size" -> R.string.field_timer_cred_request_size
        "timer.cred.alloc_size" -> R.string.field_timer_cred_alloc_size
        "timer.trace.gfp.offset" -> R.string.field_timer_gfp_offset
        "timer.trace.gfp.size" -> R.string.field_timer_gfp_size
        else -> null
    }
}
