package com.sesame.app.data.profile

/**
 * The timer route's capability block (`timer_98260`).
 *
 * It is a Kotlin-side sibling of the native route, not a `route` branch: the
 * timer tool is a separate shell binary delivered over Shizuku, the native
 * exploit never runs it, and the native wire never carries this block. The
 * app validates it and hands the full canonical document to the tool (W2
 * §1.5); the tool re-checks its own invariants.
 *
 * 64-bit kernel addresses are written as quoted hex strings in the profile
 * (`"0xffffffc008191af8"`): a HOCON/JSON number cannot carry a kernel VA
 * above 2^63, and the app's parser reads an unquoted `0x...` as a string.
 * Sizes and offsets stay numbers.
 */
data class CredOverlay(
    val usageSize: Long,
    val capsOffset: Long,
    val capsCount: Long,
    val securityOffset: Long,
    val userOffset: Long,
    val userNsOffset: Long?,
    val ucountsOffset: Long?,
    val groupInfoOffset: Long,
    val sidOffset: Long?,
)

/** Where the `mm_page_alloc` raw record carries `gfp_flags` (5.10: 0x14/4, 6.x: 0x18/8). */
data class TraceLayout(val gfpOffset: Long, val gfpSize: Long)

/**
 * How `exec_mmap()` can be made to fail after `de_thread()` — the per-kernel
 * trigger the tool selects. `KILLABLE`: 6.1+ takes `mmap_read_lock_killable`,
 * so a pending fatal signal aborts it. `CORE_STATE`: 5.10/5.15 takes the
 * non-killable lock and fails only while a coredump is in progress on the
 * old mm.
 */
enum class TimerTrigger { KILLABLE, CORE_STATE }

data class Timer98260Config(
    val version: Int,
    val trigger: TimerTrigger,
    val fakeSupported: Boolean,
    val timerSiteLower21Bit: Long,
    val timerObjectSize: Long,
    val credRequestSize: Long,
    val credAllocSize: Long,
    val credUidOffset: Long,
    val timerNodeOffset: Long?,
    val staticTimerSite: ULong?,
    val taskAllocSiteLower21Bit: Long?,
    val taskAllocSize: Long?,
    val taskCredOffset: ULong?,
    val dataSymbols: Map<String, ULong>,
    val cred: CredOverlay?,
    val trace: TraceLayout?,
) {
    val endgameLabel: String
        get() = if (fakeSupported) "real,fake" else "real"
}

/**
 * Fail-closed parser and validator for the block, mirroring the timer tool's
 * loader. Absence of the block is not an error: the profile is then a
 * native-only profile and the timer route is unavailable.
 */
object Timer98260 {
    const val BLOCK_KEY = "timer_98260"

    private val BlockKeys = setOf(
        "version",
        "trigger",
        "timer_site_lower_21bit", "timer_object_size", "cred_request_size", "cred_alloc_size", "cred_uid_offset",
        "static_timer_site", "task_alloc_site_lower_21bit", "task_alloc_size", "task_cred_offset",
        "selinux_state", "panic_on_warn", "root_user", "init_user_ns", "init_ucounts",
        "init_groups",
        "cred_usage_size", "cred_caps_offset", "cred_caps_count", "cred_security_offset",
        "cred_user_offset", "cred_user_ns_offset", "cred_ucounts_offset",
        "cred_group_info_offset", "security_sid_offset",
        "timer_node_offset", "page_alloc_gfp_offset", "page_alloc_gfp_size",
    )
    private val FakeKeys = setOf(
        "static_timer_site", "task_alloc_site_lower_21bit", "task_alloc_size", "task_cred_offset",
        "selinux_state", "panic_on_warn", "root_user", "init_user_ns", "init_ucounts",
        "init_groups",
        "cred_usage_size", "cred_caps_offset", "cred_caps_count", "cred_security_offset",
        "cred_user_offset", "cred_user_ns_offset", "cred_ucounts_offset",
        "cred_group_info_offset", "security_sid_offset",
    )
    private val DataSymbols = listOf(
        "selinux_state", "panic_on_warn", "root_user", "init_user_ns", "init_ucounts",
        "init_groups",
    )
    /** A numeric field: a JSON number or a `"0x..."` / decimal string. */
    private fun asLong(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Number -> value.toDouble().takeIf { it == Math.floor(it) }?.toLong()
        is String -> {
            val text = value.trim()
            if (text.startsWith("0x", ignoreCase = true)) {
                text.substring(2).toLongOrNull(16)
            } else {
                text.toLongOrNull()
            }
        }
        else -> null
    }

    /** A 64-bit field: accepts a number or a `"0x..."` string as ULong. */
    private fun asULong(value: Any?): ULong? = when (value) {
        is ULong -> value
        is String -> {
            val text = value.trim()
            if (text.startsWith("0x", ignoreCase = true)) {
                text.substring(2).toULongOrNull(16)
            } else {
                text.toULongOrNull()
            }
        }
        else -> asLong(value)?.takeIf { it >= 0 }?.toULong()
    }

    private fun isKernelVa(value: ULong): Boolean = value >= 0xffff_0000_0000_0000uL

    /** Validate the block; returns the parse result plus any errors. */
    fun parse(value: Any?): Pair<Timer98260Config?, List<ConfigError>> {
        val errors = mutableListOf<ConfigError>()
        if (value == null) return null to errors
        val block = value as? Map<*, *>
        if (block == null) {
            errors += ConfigError(BLOCK_KEY, "not an object")
            return null to errors
        }
        for (key in block.keys) {
            val name = key.toString()
            if (name !in BlockKeys) errors += ConfigError("$BLOCK_KEY.$name", "unknown key")
        }
        fun err(field: String, message: String) {
            errors += ConfigError("$BLOCK_KEY.$field", message)
        }
        fun long(field: String, required: Boolean = true): Long? {
            val raw = block[field]
            if (raw == null) {
                if (required) err(field, "missing")
                return null
            }
            val parsed = asLong(raw)
            if (parsed == null) err(field, "not an integer")
            return parsed
        }
        fun ulong(field: String, required: Boolean = true): ULong? {
            val raw = block[field]
            if (raw == null) {
                if (required) err(field, "missing")
                return null
            }
            val parsed = asULong(raw)
            if (parsed == null) err(field, "not an integer")
            return parsed
        }

        val version = long("version")
        if (version != null && version != 1L) err("version", "must be 1")
        // The block carries no mode key: any fake constant selects the fake
        // set, and the real five are always required.
        val fakeSupported = FakeKeys.any { block.containsKey(it) }
        // The exec trigger is a per-kernel fact. A fake block must carry it
        // (the way-2 path runs the trigger); a legacy real-only block predates
        // the key and keeps the 6.1-era default.
        val triggerRaw = block["trigger"]
        val trigger = when {
            triggerRaw == null -> {
                if (fakeSupported) err("trigger", "required with the fake constants")
                TimerTrigger.KILLABLE
            }
            triggerRaw !is String -> {
                err("trigger", "must be a string")
                TimerTrigger.KILLABLE
            }
            triggerRaw == "killable" -> TimerTrigger.KILLABLE
            triggerRaw == "core_state" -> TimerTrigger.CORE_STATE
            else -> {
                err("trigger", "must be \"killable\" or \"core_state\"")
                TimerTrigger.KILLABLE
            }
        }
        // --- real path -----------------------------------------------------
        val timerSiteLow21 = long("timer_site_lower_21bit")
        if (timerSiteLow21 != null && timerSiteLow21 !in 0L..0x1fffffL) {
            err("timer_site_lower_21bit", "exceeds 21 bits")
        }
        val timerAlloc = long("timer_object_size")
        if (timerAlloc != null && timerAlloc !in 16L..4096L) err("timer_object_size", "out of range")
        val credReq = long("cred_request_size")
        if (credReq != null && credReq !in 16L..1024L) err("cred_request_size", "out of range")
        val credAlloc = long("cred_alloc_size")
        if (credAlloc != null && credReq != null && credAlloc !in credReq..1024L) {
            err("cred_alloc_size", "out of range")
        }
        val credUidOff = long("cred_uid_offset")
        if (credUidOff != null && credReq != null && credUidOff + 4 > credReq) {
            err("cred_uid_offset", "falls outside the cred object")
        }

        // --- fake path -----------------------------------------------------
        var staticTimerSite: ULong? = null
        var taskAllocSiteLow21: Long? = null
        var taskAllocSize: Long? = null
        var taskCredOffset: ULong? = null
        val dataSymbols = mutableMapOf<String, ULong>()
        var cred: CredOverlay? = null
        if (fakeSupported) {
            staticTimerSite = ulong("static_timer_site")
            if (staticTimerSite != null && !isKernelVa(staticTimerSite)) {
                err("static_timer_site", "not a kernel address")
            }
            if (staticTimerSite != null && timerSiteLow21 != null &&
                (staticTimerSite and 0x1fffffUL) != timerSiteLow21.toULong()
            ) {
                err("static_timer_site", "low 21 bits disagree with timer_site_lower_21bit")
            }
            taskAllocSiteLow21 = long("task_alloc_site_lower_21bit")
            if (taskAllocSiteLow21 != null && taskAllocSiteLow21 !in 0L..0x1fffffL) {
                err("task_alloc_site_lower_21bit", "exceeds 21 bits")
            }
            taskAllocSize = long("task_alloc_size")
            if (taskAllocSize != null && taskAllocSize !in 512L..16384L) {
                err("task_alloc_size", "out of range")
            }
            taskCredOffset = ulong("task_cred_offset")
            if (taskCredOffset != null && taskCredOffset !in 0x400uL..0x2000uL) {
                err("task_cred_offset", "out of range")
            }
            if (taskCredOffset != null && taskAllocSize != null &&
                taskCredOffset + 8uL > taskAllocSize.toULong()
            ) {
                err("task_cred_offset", "falls outside the task object")
            }
            for (field in DataSymbols) {
                val raw = block[field] ?: continue
                val parsed = asULong(raw)
                when {
                    parsed == null -> err(field, "not an integer")
                    !isKernelVa(parsed) -> err(field, "not a kernel address")
                    else -> dataSymbols[field] = parsed
                }
            }
            for (field in DataSymbols) {
                if (field == "init_ucounts") continue
                if (block[field] == null) err(field, "required with the fake constants")
            }
            val usage = long("cred_usage_size", required = false) ?: 4L
            val capsOffset = long("cred_caps_offset")
            val capsCount = long("cred_caps_count", required = false) ?: 5L
            val security = long("cred_security_offset")
            val user = long("cred_user_offset")
            val userNs = long("cred_user_ns_offset", required = false)
            val ucounts = long("cred_ucounts_offset", required = false)
            val groupInfo = long("cred_group_info_offset")
            val sid = long("security_sid_offset", required = false)
            if (usage != 4L && usage != 8L) err("cred_usage_size", "must be 4 or 8")
            if (capsCount !in 1L..8L) err("cred_caps_count", "out of range")
            if (credReq != null) {
                fun within(offset: Long?, size: Long, field: String) {
                    if (offset != null && offset + size > credReq) {
                        err(field, "falls outside the cred object")
                    }
                }
                within(capsOffset, capsCount * 8, "cred_caps_offset")
                within(security, 8, "cred_security_offset")
                within(user, 8, "cred_user_offset")
                within(userNs, 8, "cred_user_ns_offset")
                within(ucounts, 8, "cred_ucounts_offset")
                within(groupInfo, 8, "cred_group_info_offset")
            }
            if (sid == null) err("security_sid_offset", "required with the fake constants")
            else if (sid !in 0L..0x100L - 4) err("security_sid_offset", "out of range")
            if (ucounts != null && dataSymbols["init_ucounts"] == null) {
                err("init_ucounts", "required when the cred has ucounts")
            }
            if (capsOffset != null && security != null && user != null &&
                groupInfo != null && usage.let { it == 4L || it == 8L } &&
                capsCount in 1L..8L && sid != null
            ) {
                cred = CredOverlay(
                    usageSize = usage, capsOffset = capsOffset, capsCount = capsCount,
                    securityOffset = security, userOffset = user, userNsOffset = userNs,
                    ucountsOffset = ucounts, groupInfoOffset = groupInfo, sidOffset = sid,
                )
            }
        }

        // --- shared layout -------------------------------------------------
        val timerNodeOff = long("timer_node_offset", required = false)
        if (timerNodeOff != null && timerNodeOff !in 0x40L..0x400L) {
            err("timer_node_offset", "out of range")
        }
        val gfpOffset = long("page_alloc_gfp_offset")
        if (gfpOffset != null && gfpOffset !in 8L..64L) {
            err("page_alloc_gfp_offset", "out of range")
        }
        val gfpSize = long("page_alloc_gfp_size")
        if (gfpSize != null && gfpSize != 4L && gfpSize != 8L) {
            err("page_alloc_gfp_size", "must be 4 or 8")
        }
        val trace = if (gfpOffset != null && gfpOffset in 8L..64L &&
            (gfpSize == 4L || gfpSize == 8L)
        ) TraceLayout(gfpOffset, gfpSize) else null

        if (errors.isNotEmpty()) return null to errors
        val config = Timer98260Config(
            version = 1,
            trigger = trigger,
            fakeSupported = fakeSupported,
            timerSiteLower21Bit = timerSiteLow21!!,
            timerObjectSize = timerAlloc!!,
            credRequestSize = credReq!!,
            credAllocSize = credAlloc!!,
            credUidOffset = credUidOff!!,
            timerNodeOffset = timerNodeOff,
            staticTimerSite = staticTimerSite,
            taskAllocSiteLower21Bit = taskAllocSiteLow21,
            taskAllocSize = taskAllocSize,
            taskCredOffset = taskCredOffset,
            dataSymbols = dataSymbols,
            cred = cred,
            trace = trace,
        )
        return config to errors
    }

    /** The block's validation errors (empty when absent or valid). */
    fun validate(value: Any?): List<ConfigError> = parse(value).second
}
