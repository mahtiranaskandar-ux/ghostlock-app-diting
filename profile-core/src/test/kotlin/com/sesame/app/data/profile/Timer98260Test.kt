package com.sesame.app.data.profile

import com.sesame.app.data.NativeProfileDocument
import com.sesame.app.data.valueMapOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Timer98260Test {
    private fun realBlock() = valueMapOf(
        "version" to 1L,
        "trigger" to "killable",
        "timer_site_lower_21bit" to 507136L,
        "timer_object_size" to 264L,
        "cred_request_size" to 168L,
        "cred_alloc_size" to 192L,
        "cred_uid_offset" to 4L,
        "timer_node_offset" to 120L,
        "page_alloc_gfp_offset" to 20L,
        "page_alloc_gfp_size" to 4L,
    )

    private fun fakeBlock(): Map<String, Any?> = LinkedHashMap(realBlock()).apply {
        put("static_timer_site", "0xffffffc00827bd00")
        put("task_alloc_site_lower_21bit", 1206088L)
        put("task_alloc_size", 4736L)
        put("task_cred_offset", "0x780")
        put("selinux_state", "0xffffffc00aa42b90")
        put("panic_on_warn", "0xffffffc00a789dc0")
        put("root_user", "0xffffffc00a7af5e0")
        put("init_user_ns", "0xffffffc00a7af678")
        put("init_groups", "0xffffffc00a7b0b08")
        put("cred_usage_size", 4L)
        put("cred_caps_offset", 40L)
        put("cred_caps_count", 5L)
        put("cred_security_offset", 120L)
        put("cred_user_offset", 128L)
        put("cred_user_ns_offset", 136L)
        put("cred_group_info_offset", 144L)
        put("security_sid_offset", 4L)
    }

    @Test
    fun `a real block parses and validates`() {
        val (config, errors) = Timer98260.parse(realBlock())
        assertTrue(errors.toString(), errors.isEmpty())
        assertNotNull(config)
        assertEquals("real", config!!.endgameLabel)
        assertEquals(507136L, config.timerSiteLower21Bit)
        assertEquals(264L, config.timerObjectSize)
        assertEquals(168L, config.credRequestSize)
        assertEquals(192L, config.credAllocSize)
        assertEquals(4L, config.credUidOffset)
        assertEquals(120L, config.timerNodeOffset)
        assertEquals(TraceLayout(20L, 4L), config.trace)
        assertNull(config.cred)
    }

    @Test
    fun `a real,fake block parses the overlay and the hex fields`() {
        val (config, errors) = Timer98260.parse(fakeBlock())
        assertTrue(errors.toString(), errors.isEmpty())
        assertNotNull(config)
        assertEquals("real,fake", config!!.endgameLabel)
        assertTrue(config.fakeSupported)
        assertEquals(0xffffffc00827bd00uL, config.staticTimerSite)
        assertEquals(0x780uL, config.taskCredOffset)
        assertEquals(0xffffffc00aa42b90uL, config.dataSymbols["selinux_state"])
        assertEquals(5L, config.cred?.capsCount)
        assertEquals(4L, config.cred!!.sidOffset)
    }

    @Test
    fun `the trigger is carried into the config`() {
        val (core, coreErrors) = Timer98260.parse(
            realBlock() + valueMapOf("trigger" to "core_state"),
        )
        assertTrue(coreErrors.toString(), coreErrors.isEmpty())
        assertEquals(TimerTrigger.CORE_STATE, core!!.trigger)
        val (fake, fakeErrors) = Timer98260.parse(fakeBlock())
        assertTrue(fakeErrors.toString(), fakeErrors.isEmpty())
        assertEquals(TimerTrigger.KILLABLE, fake!!.trigger)
    }

    @Test
    fun `the trigger is validated`() {
        fun errorsOf(block: Map<String, Any?>) = Timer98260.validate(block)

        // a legacy real-only block predates the key and keeps the default
        assertTrue(errorsOf(realBlock() - "trigger").isEmpty())
        // a fake block must carry it
        assertTrue(
            errorsOf(fakeBlock() - "trigger")
                .any { it.fieldPath == "timer_98260.trigger" },
        )
        // unknown or non-string values are refused
        assertTrue(
            errorsOf(realBlock() + valueMapOf("trigger" to "both"))
                .any { it.fieldPath == "timer_98260.trigger" },
        )
        assertTrue(
            errorsOf(realBlock() + valueMapOf("trigger" to 2L))
                .any { it.fieldPath == "timer_98260.trigger" },
        )
    }

    @Test
    fun `absence of the block is not an error`() {
        val (config, errors) = Timer98260.parse(null)
        assertTrue(errors.isEmpty())
        assertNull(config)
    }

    @Test
    fun `unknown and malformed fields are refused`() {
        fun errorsOf(block: Map<String, Any?>) = Timer98260.validate(block)

        assertTrue(
            errorsOf(realBlock() + valueMapOf("surprise" to 1L))
                .any { it.fieldPath == "timer_98260.surprise" },
        )
        assertTrue(
            errorsOf(realBlock() - "cred_uid_offset")
                .any { it.fieldPath == "timer_98260.cred_uid_offset" },
        )
        // any fake key selects the fake set: a lone data symbol is refused
        assertTrue(
            errorsOf(realBlock() + valueMapOf("selinux_state" to "0xffffffc00aa42b90"))
                .any { it.fieldPath == "timer_98260.static_timer_site" },
        )
        assertTrue(
            errorsOf(realBlock() + valueMapOf("timer_site_lower_21bit" to 0x200000L))
                .any { it.fieldPath == "timer_98260.timer_site_lower_21bit" },
        )
        // the fake static site must agree with the masked site
        assertTrue(
            errorsOf(fakeBlock() + valueMapOf("static_timer_site" to "0xffffffc00827bd08"))
                .any { it.fieldPath == "timer_98260.static_timer_site" },
        )
        assertTrue(
            errorsOf(fakeBlock() + valueMapOf("static_timer_site" to "0xffffffc01807bd00"))
                .isEmpty(),
        )
        // the cred offset must fall inside the task object
        assertTrue(
            errorsOf(fakeBlock() + valueMapOf("task_cred_offset" to "0x2000"))
                .any { it.fieldPath == "timer_98260.task_cred_offset" },
        )
        assertTrue(
            errorsOf(realBlock() - "page_alloc_gfp_offset")
                .any { it.fieldPath == "timer_98260.page_alloc_gfp_offset" },
        )
        assertTrue(
            errorsOf(realBlock() + valueMapOf("page_alloc_gfp_size" to 2L))
                .any { it.fieldPath == "timer_98260.page_alloc_gfp_size" },
        )
        // fake without the cred overlay keys
        assertTrue(
            errorsOf(fakeBlock() - "cred_caps_offset")
                .any { it.fieldPath == "timer_98260.cred_caps_offset" },
        )
        // the old nested cred object is not a known key
        assertTrue(
            errorsOf(fakeBlock() + valueMapOf("cred" to valueMapOf("caps_offset" to 40L)))
                .any { it.fieldPath == "timer_98260.cred" },
        )
        // hex-string form and number form agree
        assertTrue(errorsOf(fakeBlock() + valueMapOf("timer_object_size" to "264")).isEmpty())
        // a ucounts overlay requires init_ucounts
        val withUcounts = LinkedHashMap(fakeBlock()).apply {
            put("cred_ucounts_offset", 144L)
            put("cred_group_info_offset", 152L)
        }
        assertTrue(
            errorsOf(withUcounts).any { it.fieldPath == "timer_98260.init_ucounts" },
        )
    }

    @Test
    fun `the resolver accepts the block as a known top-level key`() {
        val errors = ProfileResolver.validateMerged(
            valueMapOf("timer_98260" to realBlock()), "select_stack", "none",
        )
        assertTrue(errors.none { it.fieldPath == "timer_98260" })
        val unknownKeyErrors = ProfileResolver.validateMerged(
            valueMapOf("timer_98260" to (realBlock() + valueMapOf("surprise" to 1L))),
            "select_stack", "none",
        )
        assertTrue(unknownKeyErrors.any { it.fieldPath == "timer_98260.surprise" })
    }

    /**
     * The native wire must not see the block: the same profile with and without
     * it produces the identical binary document (and therefore the same wire
     * digest). The block ships through the canonical JSON document instead.
     */
    @Test
    fun `the native document is byte-identical with and without the block`() {
        val base = valueMapOf(
            "release" to "6.1.138-test",
            "schema_version" to 3L,
            "kernel_major" to 6L,
            "route" to valueMapOf("select_stack" to valueMapOf("waiter_shift" to -2L)),
            "offset" to valueMapOf("init_task" to 123L),
        )
        fun binary(profile: Map<String, Any?>) = NativeProfileDocument.from(
            release = "6.1.138-test",
            route = "select_stack",
            fallbackTo = null,
        ) { path -> ProfileResolver.nativeValue(profile, "select_stack", null, path) }.toBinary()

        assertArrayEquals(binary(base), binary(base + valueMapOf("timer_98260" to fakeBlock())))
    }

    @Test
    fun `the canonical document carries the block with hex strings and numbers`() {
        val merged = valueMapOf(
            "release" to "5.10.236-test",
            "schema_version" to 3L,
            "kernel_major" to 5L,
            "route" to valueMapOf("select_stack" to valueMapOf("waiter_shift" to -2L)),
            "timer_98260" to fakeBlock(),
        )
        val document = ProfileDocument.render(merged)
        assertTrue(document.contains("\"release\": \"5.10.236-test\""))
        assertTrue(document.contains("\"timer_98260\""))
        assertTrue(document.contains("\"static_timer_site\": \"0xffffffc00827bd00\""))
        assertTrue(document.contains("\"timer_site_lower_21bit\": 507136"))
        assertTrue(document.contains("\"page_alloc_gfp_offset\": 20"))
        assertTrue(document.contains("\"kernel\""))
        assertTrue(document.contains("\"native\""))
        // canonical: sorted keys and stable byte-for-byte output
        assertEquals(document, ProfileDocument.render(merged))
    }
}
