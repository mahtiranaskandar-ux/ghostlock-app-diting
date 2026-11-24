package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.valueMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge stamps the device's release after checking every layer's identity,
 * keeps the document's own blocks, and fills the tuning under
 * `ghostlock.execution`.
 */
class ProfileMergerTest {
    private val device = "6.12.23-android16-5-g5a0e85dd9db0-ab14499855-4k"
    private val foreign = "6.6.66-android15-8-g29d86c5fc9dd-abogki428889875-4k"

    private fun document(release: String, overrides: Map<String, Any?> = emptyMap()): ValueMap =
        valueMapOf(
            "schema_version" to 3L,
            "release" to release,
            "devices" to null,
            "kernel" to valueMapOf(
                "image_base" to null,
                "symbols" to null,
                "struct" to valueMapOf(
                    "task" to null,
                    "cred" to null,
                    "waiter" to null,
                    "rt_mutex" to null,
                    "selinux_state" to valueMapOf("enforcing" to 0L, "initialized" to 1L),
                ),
                "heap" to null,
                "dirtyfrag" to "infeasible",
                "kdp" to false,
            ),
            "ghostlock" to valueMapOf(
                "syscall" to "select_stack",
                "carrier" to null,
                "anchor" to valueMapOf(
                    "image" to null, "bytes" to null, "stride" to null, "scratch" to 44279296L,
                ),
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
                "execution" to valueMapOf("race" to valueMapOf("route_wait_ms" to 1000L)),
            ),
            "timer" to null,
        ).apply { putAll(overrides) }

    @Test
    fun `a foreign imported profile is wrong_image, not a value dispute`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProfileMerger.resolveMerged(
                deviceRelease = device,
                builtin = document(device),
                imported = valueMapOf("release" to foreign),
                tuningExecution = null,
                routePresets = emptyMap(),
            )
        }
        assertTrue(error.message!!.contains("wrong_image"))
        assertTrue(error.message!!.contains(foreign))
        assertTrue(error.message!!.contains(device))
    }

    @Test
    fun `the kernel blocks survive the merge`() {
        val resolved = ProfileMerger.resolveMerged(
            deviceRelease = device,
            builtin = document(device),
            imported = null,
            tuningExecution = null,
            routePresets = emptyMap(),
        )
        val kernel = resolved["kernel"].asValueMap()!!
        assertEquals(1L, ((kernel["struct"] as ValueMap)["selinux_state"] as ValueMap)["initialized"])
        val anchor = (resolved["ghostlock"] as ValueMap)["anchor"].asValueMap()!!
        assertEquals(44279296L, (anchor["scratch"] as Number).toLong())
    }

    @Test
    fun `a matching imported profile still merges`() {
        val imported = document(device).apply {
            (this["timer"] as? Any?)?.let { }
            put("timer", valueMapOf(
                "trigger" to "killable",
                "match_bits" to 21L,
                "sites" to valueMapOf(
                    "timer" to valueMapOf("offset" to "0x191af8"),
                    "task_alloc" to valueMapOf("low_bits" to "0xab680"),
                ),
                "object_size" to 264L,
                "task_alloc_size" to 4800L,
                "node_offset" to 120L,
                "cred" to valueMapOf("request_size" to 176L, "alloc_size" to 192L),
                "trace" to valueMapOf("gfp" to valueMapOf("offset" to 24L, "size" to 8L)),
            ))
        }
        val resolved = ProfileMerger.resolveMerged(
            deviceRelease = device,
            builtin = document(device),
            imported = imported,
            tuningExecution = null,
            routePresets = emptyMap(),
        )
        assertEquals(device, resolved["release"])
        assertEquals(21L, ((resolved["timer"] as ValueMap)["match_bits"] as Number).toLong())
    }

    @Test
    fun `an older schema layer is refused before the merge stamps v3`() {
        val old = document(device).apply { put("schema_version", 2L) }
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProfileMerger.resolveMerged(
                deviceRelease = device,
                builtin = old,
                imported = null,
                tuningExecution = null,
                routePresets = emptyMap(),
            )
        }
        assertEquals(ProfileResolver.schemaErrorMessage(2), error.message)
    }
}
