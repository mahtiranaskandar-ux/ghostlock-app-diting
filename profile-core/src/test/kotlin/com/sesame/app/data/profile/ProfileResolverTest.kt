package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.valueMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileResolverTest {
    private val release = "6.1.138-android14-11-g44bda9e8f6e9-ab13792638"

    private fun task() = valueMapOf(
        "prio" to 132L, "normal_prio" to 140L, "sched_task_group" to 840L,
        "pi_lock" to 2340L, "pi_waiters" to 2360L, "pi_top_task" to 2376L,
        "pi_blocked_on" to 2384L, "pid" to 1584L, "tgid" to 1588L,
        "atomic_flags" to 1520L, "real_cred" to 2096L, "cred" to 2104L,
        "comm" to 2120L, "tasks" to 1360L, "seccomp" to 2304L,
    )

    private fun ref(offset: Long?) = valueMapOf("offset" to offset, "image" to null)

    private fun cred() = valueMapOf(
        "copy_size" to 128L,
        "usage" to valueMapOf("offset" to null, "size" to 4L, "value" to 1L),
        "uid" to 4L,
        "security" to 120L,
        "security_sid" to 4L,
        "caps" to valueMapOf("offset" to 40L, "count" to 5L, "mask" to "0xffffffffffffffff"),
        "refs" to valueMapOf(
            "user" to ref(128L),
            "user_ns" to ref(136L),
            "ucounts" to ref(144L),
            "group_info" to ref(152L),
        ),
    )

    private fun symbols() = valueMapOf(
        "init_task" to "0x1ff0000", "init_cred" to "0x2002000",
        "root_task_group" to "0x21d5000", "empty_zero_page" to "0x21cf000",
        "selinux_state" to "0x222a000", "selinux_blob_sizes" to "0x15b5000",
        "security_hook_heads" to "0x15b3000", "nf_logger" to "0x1fe2000",
        "loggers_0_1" to "0x1fe1000", "boot_id" to "0x22cc000",
        "panic_on_warn" to "0x1fd0000", "root_user" to "0x2001000",
        "init_user_ns" to "0x2000000", "init_ucounts" to "0x2003000",
        "init_groups" to "0x2002000",
    )

    private fun kernel() = valueMapOf(
        "image_base" to "0xffffffc008000000",
        "symbols" to symbols(),
        "struct" to valueMapOf(
            "task" to task(),
            "cred" to cred(),
            "waiter" to valueMapOf(
                "shape" to "compact", "pi_tree" to 24L, "task" to 48L,
                "lock" to 56L, "wake_state" to 64L,
            ),
            "rt_mutex" to valueMapOf("size" to 32L),
            "selinux_state" to valueMapOf("enforcing" to 0L, "initialized" to 2L),
        ),
        "heap" to valueMapOf("stride" to 1024L, "collisions" to 4L),
        "dirtyfrag" to "feasible",
        "kdp" to false,
    )

    private fun selectBranch() = valueMapOf(
        "native" to valueMapOf("waiter_shift" to 1L),
        "compat" to valueMapOf(
            "waiter_home" to 488L, "dst_depth" to 496L, "futex_abi" to "time64",
        ),
    )

    private fun execution() = valueMapOf(
        "recommended_cpus" to valueMapOf("main" to 0L, "consumer" to 1L),
        "heap" to valueMapOf(
            "prepare_max_attempts" to 4L, "prepare_timeout_ms" to 240000L,
            "kernelsnitch_timeout_ms" to 60000L,
        ),
        "race" to valueMapOf(
            "route_wait_ms" to 1000L, "route_done_timeout_ms" to 300000L,
            "setup_settle_us" to 50000L, "state_poll_interval_us" to 1000L,
        ),
        "stages" to valueMapOf(
            "w1_attempts" to 15L, "w1_settle_us" to 100000L,
            "w1_scratch_repair_attempts" to 3L, "w2_attempts" to 15L,
            "w2_settle_us" to 100000L, "w3_chain_rounds" to 3L,
            "w3_attempts" to 6L, "w3_settle_us" to 50000L,
        ),
        "handoff" to valueMapOf(
            "pre_dispatch_settle_ms" to 2000L, "module_poll_attempts" to 30L,
            "module_poll_interval_ms" to 100L, "enforce_poll_attempts" to 200L,
            "enforce_poll_interval_ms" to 100L,
        ),
        "consumer" to valueMapOf("max_calls" to 128L, "burst_calls" to 8L),
        "routes" to valueMapOf(
            "select_stack" to valueMapOf("enter_delay_us" to 5000L, "timeout_us" to 30000L),
            "tcp_zerocopy" to valueMapOf(
                "attempts" to 3L, "arm_sequence" to 1L,
                "post_receive_hold_iterations" to 100L,
            ),
        ),
    )

    private fun ghostlock() = valueMapOf(
        "syscall" to "select_stack",
        "carrier" to null,
        "anchor" to valueMapOf(
            "image" to "0x22260e8", "bytes" to 4096L, "stride" to 32L,
            "scratch" to "0x2226100",
        ),
        "syscalls" to valueMapOf(
            "select_stack" to selectBranch(),
            "sigreturn" to valueMapOf(
                "native" to valueMapOf(
                    "waiter_off" to valueMapOf("plain" to 152L, "sve" to 168L),
                ),
                "compat" to null,
            ),
            "multicast_waiter" to valueMapOf(
                "native" to valueMapOf("waiter_off" to 96L, "buffer_size" to 264L),
                "compat" to valueMapOf(
                    "waiter_off" to 72L, "buffer_size" to 260L,
                    "futex_abi" to "time64",
                ),
            ),
            "tcp_zerocopy" to valueMapOf("native" to linkedMapOf<String, Any?>(), "compat" to null),
        ),
        "module" to null,
        "execution" to execution(),
    )

    private fun timer() = valueMapOf(
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
    )

    private fun document(): ValueMap = valueMapOf(
        "schema_version" to 3L,
        "release" to release,
        "devices" to valueMapOf(
            "duchamp" to valueMapOf(
                "phys" to valueMapOf("load" to "0x40000000", "offset" to null),
            ),
        ),
        "kernel" to kernel(),
        "ghostlock" to ghostlock(),
        "timer" to timer(),
    )

    private fun errorsOf(document: Map<String, Any?>) =
        ProfileResolver.validateMerged(document)

    private fun pathErrors(document: Map<String, Any?>, path: String) =
        errorsOf(document).filter { it.fieldPath == path }

    @Test
    fun `a complete document validates cleanly`() {
        assertEquals(emptyList<String>(), errorsOf(document()).map { it.toString() })
    }

    @Test
    fun `a chain that needs W1 requires a measured stride`() {
        val nullStride = document().apply {
            (this["kernel"] as ValueMap)["heap"] =
                valueMapOf("stride" to null, "collisions" to 4L)
        }
        assertTrue(pathErrors(nullStride, "kernel.heap.stride").isNotEmpty())
        /* The generation flow's probe frame is the one document that may carry
         * the null: it is the measurement request. */
        assertTrue(
            ProfileResolver.validateMerged(nullStride, requireStride = false)
                .filter { it.fieldPath == "kernel.heap.stride" }
                .isEmpty(),
        )

        /* The DirtyFrag ladder (KDP included) also needs W1. */
        val kdp = document().apply {
            val kernel = this["kernel"] as ValueMap
            kernel["kdp"] = true
            kernel["heap"] = valueMapOf("stride" to null, "collisions" to 4L)
        }
        assertTrue(pathErrors(kdp, "kernel.heap.stride").isNotEmpty())

        /* A value, not the native default, satisfies it. */
        val measured = document().apply {
            (this["kernel"] as ValueMap)["heap"] =
                valueMapOf("stride" to 1280L, "collisions" to 4L)
        }
        assertEquals(emptyList<String>(), pathErrors(measured, "kernel.heap.stride"))
    }

    @Test
    fun `module-only and timer-only documents may leave the stride null`() {
        val moduleOnly = document().apply {
            (this["kernel"] as ValueMap)["heap"] =
                valueMapOf("stride" to null, "collisions" to 4L)
            (this["ghostlock"] as ValueMap)["module"] = valueMapOf(
                "asset" to "modules/example.ko",
                "sha256" to "a".repeat(64),
            )
        }
        assertEquals(emptyList<String>(), pathErrors(moduleOnly, "kernel.heap.stride"))

        val timerOnly = document().apply {
            put("ghostlock", null)
            (this["kernel"] as ValueMap)["heap"] =
                valueMapOf("stride" to null, "collisions" to 4L)
        }
        assertEquals(emptyList<String>(), pathErrors(timerOnly, "kernel.heap.stride"))
    }

    @Test
    fun `every declared key must be present, null or not`() {
        val missing = document().apply { remove("timer") }
        assertTrue(pathErrors(missing, "timer").isNotEmpty())
        val kernelMissing = document().apply {
            (this["kernel"] as ValueMap).remove("kdp")
        }
        assertTrue(pathErrors(kernelMissing, "kernel.kdp").isNotEmpty())
        val branchMissing = document().apply {
            val ghostlock = this["ghostlock"] as ValueMap
            val syscalls = ghostlock["syscalls"] as ValueMap
            (syscalls["select_stack"] as ValueMap).remove("compat")
        }
        assertTrue(
            pathErrors(branchMissing, "ghostlock.syscalls.select_stack.compat").isNotEmpty(),
        )
    }

    @Test
    fun `unknown keys are refused in the blocks the app owns`() {
        val top = document().apply { put("route", valueMapOf()) }
        assertTrue(pathErrors(top, "route").isNotEmpty())
        val kernel = document().apply {
            (this["kernel"] as ValueMap).put("bogus", 1L)
        }
        assertTrue(pathErrors(kernel, "kernel.bogus").isNotEmpty())
        val branch = document().apply {
            val syscalls = (this["ghostlock"] as ValueMap)["syscalls"] as ValueMap
            (syscalls["select_stack"] as ValueMap).put("bogus", 1L)
        }
        assertTrue(pathErrors(branch, "ghostlock.syscalls.select_stack.bogus").isNotEmpty())
    }

    @Test
    fun `the schema version is fixed`() {
        val old = document().apply { put("schema_version", 2L) }
        assertEquals(
            listOf(ProfileResolver.schemaErrorMessage(2)),
            pathErrors(old, "schema_version").map { it.message },
        )
        val absent = document().apply { remove("schema_version") }
        /* An absent key is also "missing required key"; the schema sentence
         * must still be among its errors. */
        assertTrue(
            pathErrors(absent, "schema_version").map { it.message }
                .contains(ProfileResolver.schemaErrorMessage(null)),
        )
    }

    @Test
    fun `the transport-only keys are not part of an authored document`() {
        for (key in listOf("mode", "safe_mode")) {
            val doc = document().apply { put(key, if (key == "mode") "ghostlock" else true) }
            assertTrue(pathErrors(doc, key).isNotEmpty())
        }
    }

    @Test
    fun `the device physical pair is validated`() {
        val bad = document().apply {
            put("devices", valueMapOf("duchamp" to valueMapOf(
                "phys" to valueMapOf("load" to "not-an-address", "offset" to null),
            )))
        }
        assertTrue(pathErrors(bad, "devices.duchamp.phys.load").isNotEmpty())
        val nullDevices = document().apply { put("devices", null) }
        assertTrue(errorsOf(nullDevices).isEmpty())
    }

    @Test
    fun `a KDP image needs the DirtyFrag ladder`() {
        val kdp = document().apply {
            (this["kernel"] as ValueMap)["kdp"] = true
        }
        assertTrue(errorsOf(kdp).isEmpty())
        val wrong = document().apply {
            val kernel = this["kernel"] as ValueMap
            kernel["kdp"] = true
            kernel["dirtyfrag"] = "infeasible"
        }
        assertTrue(pathErrors(wrong, "kernel.dirtyfrag").isNotEmpty())
    }

    @Test
    fun `the selected syscall and carrier must exist`() {
        val nullSyscall = document().apply {
            (this["ghostlock"] as ValueMap)["syscall"] = null
        }
        assertTrue(pathErrors(nullSyscall, "ghostlock.syscall").isNotEmpty())
        val nullBranch = document().apply {
            (this["ghostlock"] as ValueMap)["syscall"] = "sigreturn"
            val syscalls = (this["ghostlock"] as ValueMap)["syscalls"] as ValueMap
            syscalls["sigreturn"] = null
        }
        assertTrue(pathErrors(nullBranch, "ghostlock.syscall").isNotEmpty())
        val noCarrier = document().apply {
            (this["ghostlock"] as ValueMap)["carrier"] = "compat"
            (this["ghostlock"] as ValueMap)["syscall"] = "sigreturn"
            val syscalls = (this["ghostlock"] as ValueMap)["syscalls"] as ValueMap
            (syscalls["sigreturn"] as ValueMap)["native"] = null
        }
        assertTrue(pathErrors(noCarrier, "ghostlock.carrier").isNotEmpty())
    }

    @Test
    fun `exactly one of syscalls and module is stated`() {
        val both = document().apply {
            (this["ghostlock"] as ValueMap)["module"] = valueMapOf(
                "asset" to "modules/x.ko",
                "sha256" to "a".repeat(64),
                "vendor_files" to listOf("/vendor/lib64/libx.so"),
            )
        }
        assertTrue(pathErrors(both, "ghostlock.module").isNotEmpty())
        val neither = document().apply {
            (this["ghostlock"] as ValueMap)["syscalls"] = null
            (this["ghostlock"] as ValueMap)["syscall"] = null
        }
        assertTrue(pathErrors(neither, "ghostlock.syscalls").isNotEmpty())
    }

    @Test
    fun `a module-only document validates and derives the module chain`() {
        val moduleOnly = valueMapOf(
            "schema_version" to 3L,
            "release" to "5.4.233-qgki-gbb70cde46897",
            "devices" to null,
            "kernel" to valueMapOf(
                "image_base" to null,
                "symbols" to null,
                "struct" to null,
                "heap" to null,
                "dirtyfrag" to "unknown",
                "kdp" to false,
            ),
            "ghostlock" to valueMapOf(
                "syscall" to null,
                "carrier" to null,
                "anchor" to null,
                "syscalls" to null,
                "module" to valueMapOf(
                    "asset" to "modules/x.ko",
                    "sha256" to "a".repeat(64),
                    "vendor_files" to listOf("/vendor/lib64/libx.so"),
                ),
                "execution" to null,
            ),
            "timer" to null,
        )
        assertEquals(emptyList<String>(), errorsOf(moduleOnly).map { it.toString() })
        assertEquals(listOf("module"), ProfileResolver.pipelineOrder(moduleOnly))
        assertFalse(ProfileResolver.requiresW1(ProfileResolver.pipelineOrder(moduleOnly)))
    }

    @Test
    fun `a timer-only document is the fixed shape with nulls`() {
        val timerOnly = document().apply {
            put("devices", null)
            put("ghostlock", null)
            (this["kernel"] as ValueMap)["dirtyfrag"] = null
        }
        assertEquals(emptyList<String>(), errorsOf(timerOnly).map { it.toString() })
        assertEquals(listOf("timer"), ProfileResolver.pipelineOrder(timerOnly))
        assertFalse(ProfileResolver.requiresW1(ProfileResolver.pipelineOrder(timerOnly)))
        assertTrue(Timer98260.fakeSupported(timerOnly))
    }

    @Test
    fun `the fixed ladder is derived from the verdict`() {
        assertEquals(
            listOf("dirtyfrag", "ghostlock"),
            ProfileResolver.pipelineOrder(document()),
        )
        val infeasible = document().apply {
            (this["kernel"] as ValueMap)["dirtyfrag"] = "infeasible"
        }
        assertEquals(listOf("ghostlock"), ProfileResolver.pipelineOrder(infeasible))
        val kdp = document().apply {
            (this["kernel"] as ValueMap)["kdp"] = true
        }
        assertEquals(listOf("dirtyfrag"), ProfileResolver.pipelineOrder(kdp))
    }

    @Test
    fun `the anchor scratch must leave the collateral window inside the anchor`() {
        fun withScratch(scratch: String) = document().apply {
            val anchor = (this["ghostlock"] as ValueMap)["anchor"] as ValueMap
            anchor["scratch"] = scratch
        }
        assertTrue(errorsOf(withScratch("0x2226100")).isEmpty())
        /* The window runs from alias(scratch)+0x100 for 0x18 bytes; this
         * scratch's window leaves the 4096-byte region. */
        assertTrue(errorsOf(withScratch("0x22270e0")).isNotEmpty())
        /* Outside the region is a second region and is allowed. */
        assertTrue(errorsOf(withScratch("0x3000000")).isEmpty())
    }

    @Test
    fun `the waiter shape refuses multicast and sigreturn on the node shape`() {
        val node = document().apply {
            val structure = ((this["kernel"] as ValueMap)["struct"] as ValueMap)
            (structure["waiter"] as ValueMap)["shape"] = "node"
            (this["ghostlock"] as ValueMap)["syscall"] = "sigreturn"
        }
        assertTrue(pathErrors(node, "ghostlock.syscalls.sigreturn").isNotEmpty())
    }

    @Test
    fun `the multicast compat strip must fit its buffer`() {
        val tight = document().apply {
            val syscalls = (this["ghostlock"] as ValueMap)["syscalls"] as ValueMap
            val compat = ((syscalls["multicast_waiter"] as ValueMap)["compat"] as ValueMap)
            compat["buffer_size"] = 100L
        }
        assertTrue(
            pathErrors(tight, "ghostlock.syscalls.multicast_waiter.compat.waiter_off").isNotEmpty(),
        )
    }

    @Test
    fun `the routes and carriers enumerate in the fixed order`() {
        val syscalls = ProfileResolver.syscallsOf(document())
        assertEquals(listOf("select_stack", "sigreturn", "multicast_waiter", "tcp_zerocopy"),
            syscalls.map { it.first })
        assertEquals(listOf("native", "compat"), syscalls.first { it.first == "select_stack" }.second)
        assertEquals(listOf("native"), syscalls.first { it.first == "sigreturn" }.second)
        assertEquals(listOf("native", "compat"),
            syscalls.first { it.first == "multicast_waiter" }.second)
    }

    @Test
    fun `the physical pair is read from the resolved device`() {
        val (load, offset) = ProfileResolver.physOf(document())
        assertEquals(0x40000000L, load)
        assertNull(offset)
        val none = document().apply { put("devices", null) }
        assertEquals(null to null, ProfileResolver.physOf(none))
    }

    @Test
    fun `the device map resolves for the running device and refuses a stranger`() {
        val resolved = PerDevicePhys.resolveFor(document(), listOf("duchamp"))
        assertEquals("duchamp", resolved.device)
        val devices = resolved.document["devices"].asValueMap()
        assertEquals(1, devices?.size)
        val refused = runCatching {
            PerDevicePhys.resolveFor(document(), listOf("other"))
        }
        assertTrue(refused.isFailure)
        val neutral = PerDevicePhys.resolveNeutral(document())
        assertNull(neutral["devices"])
    }

}
