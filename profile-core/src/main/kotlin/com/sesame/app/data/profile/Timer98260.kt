package com.sesame.app.data.profile

import com.sesame.app.data.asValueMap

/** How `exec_mmap()` can be made to fail after `de_thread()`. */
enum class TimerTrigger { KILLABLE, CORE_STATE }

/**
 * The 98260 timer tool's facts, as the document states them (`timer`, plus the
 * shared `kernel` facts the fake-cred overlay needs).
 *
 * The tool is a separate shell binary delivered over Shizuku; it re-checks its
 * own invariants. The app validates the block so the picker offers only a
 * runnable engine, and hands the whole document to the tool.
 */
data class TimerBlock(
    val trigger: TimerTrigger,
    val matchBits: Long,
    val timerOffset: ULong?,
    val taskAllocLowBits: ULong?,
    val objectSize: Long?,
    val taskAllocSize: Long?,
    val nodeOffset: Long?,
    val credRequestSize: Long?,
    val credAllocSize: Long?,
    val gfpOffset: Long?,
    val gfpSize: Long?,
) {
    val endgameLabel: String get() = "real,fake"
}

/**
 * Fail-closed parser and validator for the `timer` block, plus the
 * document-level `fakeSupported` gate (the shared kernel facts must supply the
 * fake-cred group). Absence of the block is not an error: the profile is then
 * a native-only profile and the timer engine is unavailable.
 */
object Timer98260 {
    const val BLOCK_KEY = "timer"

    /** Validate the block; returns the typed block plus any errors. */
    fun parse(value: Any?): Pair<TimerBlock?, List<ConfigError>> {
        val errors = mutableListOf<ConfigError>()
        if (value == null) return null to errors
        val block = value as? Map<*, *>
        if (block == null) {
            errors += ConfigError(BLOCK_KEY, "not an object")
            return null to errors
        }
        ProfileResolver.validateTimerBlock(block, errors)
        val trigger = when (block["trigger"]) {
            "core_state" -> TimerTrigger.CORE_STATE
            else -> TimerTrigger.KILLABLE
        }
        val sites = block["sites"].asValueMap()
        val timerSite = sites?.get("timer").asValueMap()
        val taskSite = sites?.get("task_alloc").asValueMap()
        val cred = block["cred"].asValueMap()
        val gfp = block["trace"].asValueMap()?.get("gfp").asValueMap()
        val parsed = TimerBlock(
            trigger = trigger,
            matchBits = long(block["match_bits"]) ?: 0L,
            timerOffset = unsigned(timerSite?.get("offset")),
            taskAllocLowBits = unsigned(taskSite?.get("low_bits")),
            objectSize = long(block["object_size"]),
            taskAllocSize = long(block["task_alloc_size"]),
            nodeOffset = long(block["node_offset"]),
            credRequestSize = long(cred?.get("request_size")),
            credAllocSize = long(cred?.get("alloc_size")),
            gfpOffset = long(gfp?.get("offset")),
            gfpSize = long(gfp?.get("size")),
        )
        return parsed to errors
    }

    /** Alias used by callers that only want the errors. */
    fun validate(value: Any?): List<ConfigError> = parse(value).second

    /**
     * The tool's fake path needs the complete credential overlay and symbol
     * kit from the shared `kernel` block (`kernel.symbols`, `kernel.struct.cred`,
     * `kernel.image_base`), and the block's own sites and sizes.
     */
    fun fakeSupported(document: Map<String, Any?>): Boolean {
        val kernel = document[ProfileResolver.KernelKey].asValueMap() ?: return false
        if (unsigned(kernel["image_base"]) == null) return false
        val symbols = kernel["symbols"].asValueMap() ?: return false
        for (key in listOf(
            "selinux_state", "panic_on_warn", "root_user", "init_user_ns", "init_groups",
        )) {
            if (unsigned(symbols[key]) == null) return false
        }
        val structure = kernel["struct"].asValueMap() ?: return false
        val cred = structure["cred"].asValueMap() ?: return false
        if (long(cred["security_sid"]) == null) return false
        if (long(cred["security"]) == null) return false
        val usage = cred["usage"].asValueMap() ?: return false
        if (long(usage["size"]) == null) return false
        val caps = cred["caps"].asValueMap() ?: return false
        if (long(caps["offset"]) == null || long(caps["count"]) == null) return false
        val refs = cred["refs"].asValueMap() ?: return false
        for (name in listOf("user", "user_ns", "group_info")) {
            val ref = refs[name].asValueMap() ?: return false
            if (long(ref["offset"]) == null) return false
        }
        val ucounts = refs["ucounts"].asValueMap()
        if (long(ucounts?.get("offset")) != null && unsigned(symbols["init_ucounts"]) == null) {
            return false
        }
        val block = parse(document[ProfileResolver.TimerKey]).first ?: return false
        if (block.timerOffset == null || block.taskAllocLowBits == null) return false
        if (block.objectSize == null || block.taskAllocSize == null) return false
        if (block.credRequestSize == null || block.credAllocSize == null) return false
        if (block.gfpOffset == null || block.gfpSize == null) return false
        return true
    }

    private fun long(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().let { text ->
            if (text.startsWith("0x", ignoreCase = true)) text.substring(2).toLongOrNull(16)
            else text.toLongOrNull()
        }
        else -> null
    }

    private fun unsigned(value: Any?): ULong? = when (value) {
        is Number -> value.toLong().toULong()
        is String -> value.trim().let { text ->
            if (text.startsWith("0x", ignoreCase = true)) text.substring(2).toULongOrNull(16)
            else text.toULongOrNull()
        }
        else -> null
    }
}
