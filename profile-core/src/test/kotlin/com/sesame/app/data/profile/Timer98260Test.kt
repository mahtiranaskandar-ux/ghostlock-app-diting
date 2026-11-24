package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.valueMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Timer98260Test {
    private fun block(overrides: Map<String, Any?> = emptyMap()): ValueMap = valueMapOf(
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
    ).apply { putAll(overrides) }

    private fun symbols(): ValueMap = valueMapOf(
        "selinux_state" to "0x222a000", "panic_on_warn" to "0x1fd0000",
        "root_user" to "0x2001000", "init_user_ns" to "0x2000000",
        "init_ucounts" to "0x2003000", "init_groups" to "0x2002000",
        "init_task" to "0x1ff0000", "init_cred" to "0x2002000",
        "root_task_group" to "0x21d5000", "empty_zero_page" to "0x21cf000",
        "selinux_blob_sizes" to "0x15b5000", "security_hook_heads" to "0x15b3000",
        "nf_logger" to "0x1fe2000", "loggers_0_1" to "0x1fe1000",
        "boot_id" to "0x22cc000",
    )

    private fun document(
        symbols: Any? = symbols(),
        imageBase: Any? = "0xffffffc008000000",
        block: Any? = block(),
        ucounts: Any? = valueMapOf("offset" to 144L, "image" to null),
    ): ValueMap = valueMapOf(
        "kernel" to valueMapOf(
            "image_base" to imageBase,
            "symbols" to symbols,
            "struct" to valueMapOf(
                "cred" to valueMapOf(
                    "security" to 120L,
                    "security_sid" to 4L,
                    "usage" to valueMapOf("offset" to null, "size" to 4L, "value" to 1L),
                    "caps" to valueMapOf("offset" to 40L, "count" to 5L, "mask" to "0x1ffffffffff"),
                    "refs" to valueMapOf(
                        "user" to valueMapOf("offset" to 128L, "image" to null),
                        "user_ns" to valueMapOf("offset" to 136L, "image" to null),
                        "ucounts" to ucounts,
                        "group_info" to valueMapOf("offset" to 152L, "image" to null),
                    ),
                ),
            ),
        ),
        "timer" to block,
    )

    @Test
    fun `a complete block parses into the tool's facts`() {
        val (parsed, errors) = Timer98260.parse(block())
        assertEquals(emptyList<ConfigError>(), errors)
        requireNotNull(parsed)
        assertEquals(TimerTrigger.KILLABLE, parsed.trigger)
        assertEquals(21L, parsed.matchBits)
        assertEquals(0x191af8uL, parsed.timerOffset)
        assertEquals(0xab680uL, parsed.taskAllocLowBits)
        assertEquals(264L, parsed.objectSize)
        assertEquals(4800L, parsed.taskAllocSize)
        assertEquals(120L, parsed.nodeOffset)
        assertEquals(176L, parsed.credRequestSize)
        assertEquals(192L, parsed.credAllocSize)
        assertEquals(24L, parsed.gfpOffset)
        assertEquals(8L, parsed.gfpSize)
        assertEquals("real,fake", parsed.endgameLabel)
    }

    @Test
    fun `core_state is the other trigger`() {
        val (parsed, errors) = Timer98260.parse(block(mapOf("trigger" to "core_state")))
        assertEquals(emptyList<ConfigError>(), errors)
        assertEquals(TimerTrigger.CORE_STATE, parsed?.trigger)
    }

    @Test
    fun `absence is not an error but the block is unavailable`() {
        assertEquals(null to emptyList<ConfigError>(), Timer98260.parse(null))
        assertFalse(Timer98260.fakeSupported(valueMapOf("kernel" to null, "timer" to null)))
    }

    @Test
    fun `the block's fixed values are enforced`() {
        fun fields(vararg overrides: Pair<String, Any?>) =
            Timer98260.validate(block(overrides.toMap())).map { it.fieldPath }

        assertTrue("timer.match_bits" in fields("match_bits" to 20L))
        assertTrue("timer.trigger" in fields("trigger" to "surprise"))
        assertTrue("timer.object_size" in fields("object_size" to 8L))
        assertTrue("timer.task_alloc_size" in fields("task_alloc_size" to 100L))
        assertTrue("timer.node_offset" in fields("node_offset" to 4L))
        assertTrue("timer.sites.task_alloc.low_bits" in fields(
            "sites" to valueMapOf(
                "timer" to valueMapOf("offset" to "0x191af8"),
                "task_alloc" to valueMapOf("low_bits" to "0x200000"),
            ),
        ))
        assertTrue("timer.bogus" in fields("bogus" to 1L))
        assertTrue("timer.trace.gfp.size" in fields(
            "trace" to valueMapOf("gfp" to valueMapOf("offset" to 24L, "size" to 2L)),
        ))
    }

    @Test
    fun `the fake group needs the shared kernel facts`() {
        assertTrue(Timer98260.fakeSupported(document()))
        /* No image base: the tool cannot reconstruct the VAs. */
        assertFalse(Timer98260.fakeSupported(document(imageBase = null)))
        /* A required symbol is absent. */
        assertFalse(Timer98260.fakeSupported(document(symbols = null)))
        /* The credential overlay is incomplete. */
        val noSid = document().apply {
            val struct = this["kernel"].asValueMap()!!["struct"].asValueMap()!!
            (struct["cred"].asValueMap()!!)["security_sid"] = null
        }
        assertFalse(Timer98260.fakeSupported(noSid))
        /* ucounts needs init_ucounts. */
        val symbolsWithoutUcounts = symbols().apply { remove("init_ucounts") }
        assertFalse(Timer98260.fakeSupported(document(symbols = symbolsWithoutUcounts)))
        /* ...and a cred without ucounts does not. */
        assertTrue(
            Timer98260.fakeSupported(
                document(symbols = symbolsWithoutUcounts, ucounts = null),
            ),
        )
    }
}
