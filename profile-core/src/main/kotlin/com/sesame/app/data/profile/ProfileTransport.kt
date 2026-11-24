package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.copyValue

/**
 * The transport boundary: the resolved profile tree as the one document every
 * backend reads.
 *
 * The resolved tree is already the cleaned shape, so rendering is just the
 * run's additions — `mode` (the app's resolved chain), `safe_mode`, and the
 * picker's engine selection when it differs from the document's. The device
 * entry was reduced to the running device by [PerDevicePhys] before this
 * point; the document is never rewritten.
 */
object ProfileTransport {
    fun render(
        resolved: Map<String, Any?>,
        route: String? = null,
        carrier: String? = null,
        pipeline: String? = null,
        safeMode: Boolean = false,
    ): String = ProfileDocument.renderJson(
        document(resolved, route, carrier, pipeline, safeMode),
    )

    /** The transport document tree (run keys included). */
    fun document(
        resolved: Map<String, Any?>,
        route: String? = null,
        carrier: String? = null,
        pipeline: String? = null,
        safeMode: Boolean = false,
    ): Map<String, Any?> {
        val document = resolved.copyValue().asValueMap() ?: ValueMap()
        document["mode"] = pipeline
        document["safe_mode"] = safeMode
        val ghostlock = document[ProfileResolver.GhostlockKey].asValueMap()
        if (ghostlock != null) {
            if (route != null) ghostlock["syscall"] = route
            if (carrier != null) ghostlock["carrier"] = carrier
            val syscall = route ?: (ghostlock["syscall"] as? String)
            ghostlock["execution"] = execution(ghostlock["execution"].asValueMap(), syscall)
        }
        return document
    }

    /**
     * The native's exact execution shape: the CPU pair left to the native's
     * runtime choice (0/1 is its neutral fallback) and the consumer cadence
     * hoisted out of the selected route's preset (`execution.routes.*` also
     * holds the app-side `consumer_*` keys, which never cross the transport).
     */
    private fun execution(execution: ValueMap?, syscall: String?): Map<String, Any?>? {
        if (execution == null) return null
        fun body(name: String): Map<*, *>? = execution[name] as? Map<*, *>
        fun long(map: Map<*, *>?, key: String): Long =
            (map?.get(key) as? Number)?.toLong() ?: 0L

        val routes = execution["routes"] as? Map<*, *>
        fun route(name: String): Map<*, *>? = routes?.get(name) as? Map<*, *>
        val selected = syscall?.let { route(it) }
        val select = route("select_stack")
        val tcp = route("tcp_zerocopy")
        fun consumer(key: String): Long {
            for (source in listOf(selected, select)) {
                val value = long(source, key)
                if (value != 0L) return value
            }
            return 0L
        }
        val heap = body("heap")
        val race = body("race")
        val stages = body("stages")
        val handoff = body("handoff")
        return linkedMapOf(
            "recommended_cpus" to linkedMapOf("main" to 0L, "consumer" to 1L),
            "heap" to linkedMapOf(
                "prepare_max_attempts" to long(heap, "prepare_max_attempts"),
                "prepare_timeout_ms" to long(heap, "prepare_timeout_ms"),
                "kernelsnitch_timeout_ms" to long(heap, "kernelsnitch_timeout_ms"),
            ),
            "race" to linkedMapOf(
                "route_wait_ms" to long(race, "route_wait_ms"),
                "route_done_timeout_ms" to long(race, "route_done_timeout_ms"),
                "setup_settle_us" to long(race, "setup_settle_us"),
                "state_poll_interval_us" to long(race, "state_poll_interval_us"),
            ),
            "stages" to linkedMapOf(
                "w1_attempts" to long(stages, "w1_attempts"),
                "w1_settle_us" to long(stages, "w1_settle_us"),
                "w1_scratch_repair_attempts" to long(stages, "w1_scratch_repair_attempts"),
                "w2_attempts" to long(stages, "w2_attempts"),
                "w2_settle_us" to long(stages, "w2_settle_us"),
                "w3_chain_rounds" to long(stages, "w3_chain_rounds"),
                "w3_attempts" to long(stages, "w3_attempts"),
                "w3_settle_us" to long(stages, "w3_settle_us"),
            ),
            "handoff" to linkedMapOf(
                "pre_dispatch_settle_ms" to long(handoff, "pre_dispatch_settle_ms"),
                "module_poll_attempts" to long(handoff, "module_poll_attempts"),
                "module_poll_interval_ms" to long(handoff, "module_poll_interval_ms"),
                "enforce_poll_attempts" to long(handoff, "enforce_poll_attempts"),
                "enforce_poll_interval_ms" to long(handoff, "enforce_poll_interval_ms"),
            ),
            "consumer" to linkedMapOf(
                "max_calls" to consumer("consumer_max_calls"),
                "burst_calls" to consumer("consumer_burst_calls"),
            ),
            "routes" to linkedMapOf(
                "select_stack" to linkedMapOf(
                    "enter_delay_us" to long(select, "enter_delay_us"),
                    "timeout_us" to long(select, "timeout_us"),
                ),
                "tcp_zerocopy" to linkedMapOf(
                    "attempts" to long(tcp, "attempts"),
                    "arm_sequence" to long(tcp, "arm_sequence"),
                    "post_receive_hold_iterations" to
                        long(tcp, "post_receive_hold_iterations"),
                ),
            ),
        )
    }
}
