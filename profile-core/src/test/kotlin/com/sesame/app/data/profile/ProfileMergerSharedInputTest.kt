package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.valueMapOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exporter resolves a whole index in one process and hands every profile
 * the same tuning/preset maps. `deepMergeValues` writes into the maps it is
 * given, so aliasing a caller's map used to leak the first profile's values
 * into every later one. These tests pin the contract: resolving never mutates
 * what the caller passed in.
 */
class ProfileMergerSharedInputTest {
    private val tuning: ValueMap = valueMapOf(
        "execution" to valueMapOf("race" to valueMapOf("route_wait_ms" to 1000L)),
    )
    private val presets: Map<String, ValueMap> = mapOf(
        "select_stack" to valueMapOf("enter_delay_us" to 50_000L),
    )

    private fun profile(release: String, routeWaitMs: Long?): ValueMap = valueMapOf(
        "schema_version" to 3L,
        "release" to release,
        "devices" to null,
        "kernel" to valueMapOf(
            "image_base" to null,
            "symbols" to null,
            "struct" to null,
            "heap" to null,
            "dirtyfrag" to "infeasible",
            "kdp" to false,
        ),
        "ghostlock" to valueMapOf(
            "syscall" to "select_stack",
            "carrier" to null,
            "anchor" to null,
            "syscalls" to valueMapOf(
                "select_stack" to valueMapOf(
                    "native" to valueMapOf("waiter_shift" to 1L),
                    "compat" to null,
                ),
                "sigreturn" to null,
                "multicast_waiter" to null,
                "tcp_zerocopy" to null,
            ),
            "module" to null,
            "execution" to (routeWaitMs?.let {
                valueMapOf("race" to valueMapOf("route_wait_ms" to it))
            } ?: valueMapOf()),
        ),
        "timer" to null,
    )

    private fun ValueMap.routeWaitMs(): Long? =
        ((this["ghostlock"] as? Map<*, *>)?.get("execution").asValueMap())
            ?.get("race").asValueMap()?.get("route_wait_ms") as? Long

    private fun ValueMap.execution(): ValueMap =
        (this["ghostlock"] as ValueMap)["execution"].asValueMap()!!

    @Test
    fun `a profile that sets route_wait_ms does not leak it into the next one`() {
        val first = ProfileMerger.resolveMerged(
            deviceRelease = "diting", builtin = profile("diting", routeWaitMs = 30L),
            imported = null,
            tuningExecution = tuning["execution"].asValueMap(),
            routePresets = presets,
        )
        val second = ProfileMerger.resolveMerged(
            deviceRelease = "other", builtin = profile("other", routeWaitMs = null),
            imported = null,
            tuningExecution = tuning["execution"].asValueMap(),
            routePresets = presets,
        )
        assertEquals(30L, first.routeWaitMs())
        assertEquals(1000L, second.routeWaitMs())
    }

    @Test
    fun `resolving leaves the caller's tuning and presets untouched`() {
        ProfileMerger.resolveMerged(
            deviceRelease = "diting", builtin = profile("diting", routeWaitMs = 30L),
            imported = null,
            tuningExecution = tuning["execution"].asValueMap(),
            routePresets = presets,
        )
        assertEquals(
            1000L,
            (tuning["execution"].asValueMap()?.get("race").asValueMap()
                ?.get("route_wait_ms")) as Long,
        )
        assertEquals(
            50_000L,
            presets.getValue("select_stack").getValue("enter_delay_us"),
        )
    }
}
