package com.sesame.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `--options-out` contract: the app reads every option and applies the
 * display rules itself (auto-take one safe option, offer several without page,
 * offer page only when nothing safe exists). The cut selects the route, carrier
 * and plan inside the extractor's cleaned document; it assembles nothing.
 */
class OptionSupersetTest {
    private val release = "6.1.138-android14-11-g44bda9e8f6e9-ab13792638"

    private fun plan(
        route: String,
        anchor: Long? = null,
        scratch: Long? = null,
        pageFree: Boolean = false,
        conditioned: Boolean = false,
        anchorName: String? = null,
        scratchSource: String? = null,
        inAnchor: Boolean = false,
    ): ValueMap = valueMapOf(
        "route" to route,
        "anchor" to anchor?.let {
            valueMapOf("image" to it, "bytes" to 4096L, "stride" to 32L)
        },
        "scratch" to scratch?.let {
            valueMapOf(
                "image" to it,
                "window" to 0L,
                "reserved_first" to if (inAnchor) 8L else null,
                "reserved_last" to if (inAnchor) 8L else null,
            )
        },
        "page_free" to pageFree,
        "conditioned" to conditioned,
        "anchor_name" to anchorName,
        "scratch_source" to scratchSource,
        "reason" to if (pageFree) "unconditional page-free plan" else "page",
    )

    private fun sample(): OptionSuperset {
        val document = CleanedProfileFixture.document(release)
        /* The pair's one home: the schema-shaped device map. */
        document["devices"] = valueMapOf(
            "duchamp" to valueMapOf(
                "phys" to valueMapOf("load" to "0x40000000", "offset" to "0x40000000"),
            ),
        )
        val kernel = document["kernel"].asValueMap()!!
        kernel["dirtyfrag"] = "feasible"
        val ghostlock = document["ghostlock"].asValueMap()!!
        ghostlock["syscalls"] = valueMapOf(
            "select_stack" to valueMapOf(
                "native" to valueMapOf("waiter_shift" to 1L),
                "compat" to valueMapOf(
                    "waiter_home" to 432L,
                    "dst_depth" to 480L,
                    "futex_abi" to "time32",
                ),
            ),
            "sigreturn" to null,
            "multicast_waiter" to null,
            "tcp_zerocopy" to valueMapOf(
                "native" to valueMapOf(),
                "compat" to null,
            ),
        )
        val plans = valueListOf(
            valueMapOf(
                "route" to "select_stack",
                "page" to plan("select_stack"),
                "anchor_only" to plan(
                    "select_stack", anchor = 35802320L,
                    anchorName = "__dump_skip.zeroes",
                ),
                "page_free" to valueListOf(
                    plan(
                        "select_stack", anchor = 35802320L, scratch = 35459072L,
                        pageFree = true, anchorName = "__dump_skip.zeroes",
                        scratchSource = "bm_pmd",
                    ),
                    plan(
                        "select_stack", anchor = 35459072L, scratch = 35459072L,
                        pageFree = true, anchorName = "bm_pmd",
                        scratchSource = "in-anchor", inAnchor = true,
                    ),
                    plan(
                        "select_stack", anchor = 35947744L, scratch = 35459072L,
                        pageFree = true, conditioned = true,
                        anchorName = "s3c24xx_serial_ports",
                        scratchSource = "bm_pmd",
                    ),
                ),
            ),
            valueMapOf(
                "route" to "tcp_zerocopy",
                "page" to plan("tcp_zerocopy"),
                "page_free" to valueListOf(),
            ),
        )
        val envelope = valueMapOf(
            "schema_version" to 3L,
            "release" to release,
            "kernel_major" to 6L,
            "routes" to valueListOf(
                valueMapOf(
                    "route" to "select_stack",
                    "carrier" to "native",
                    "verdict" to "feasible",
                    "reason" to "pselect/futex geometry derived",
                    "anchor_capable" to true,
                    "geometry" to valueMapOf("compact_waiter" to 1L, "waiter_shift" to 1L),
                    "alternatives" to valueListOf(
                        valueMapOf(
                            "carrier" to "compat",
                            "verdict" to "feasible",
                            "reason" to "AArch32 compat carrier",
                            "geometry" to valueMapOf(
                                "compat_carrier" to 1L,
                                "waiter_home" to 432L,
                                "select_dst_depth" to 480L,
                                "compat_futex_abi" to 0L,
                            ),
                        ),
                    ),
                ),
                valueMapOf(
                    "route" to "tcp_zerocopy",
                    "carrier" to "native",
                    "verdict" to "feasible",
                    "reason" to "cap 0x40",
                    "anchor_capable" to false,
                    "geometry" to valueMapOf("compact_waiter" to 1L),
                ),
            ),
            "document" to document,
            "device_analysis" to valueListOf(
                valueMapOf(
                    "device" to "duchamp",
                    "phys_source" to "vendor_boot DTB /memory",
                    "candidates" to valueListOf(),
                    "plans" to plans,
                ),
            ),
        )
        return requireNotNull(OptionSuperset.parse(HoconSupport.render(envelope)))
    }

    @Test
    fun `all options are exposed and the UI filters them`() {
        val superset = sample()
        assertEquals(release, superset.release)
        val plans = requireNotNull(superset.plansFor("select_stack"))
        assertEquals(2, plans.safeOptions.size)
        assertEquals(1, plans.conditionalOptions.size)
        assertEquals("__dump_skip.zeroes + bm_pmd", plans.safeOptions[0].label)
        assertEquals("bm_pmd", plans.safeOptions[1].label)
        assertNotNull(plans.anchorOnly)
        assertEquals("__dump_skip.zeroes", plans.anchorOnly?.anchorName)
        assertEquals(ScratchInAnchor, plans.safeOptions[1].scratchSource)
        assertFalse(plans.safeOptions[0].scratchSource == ScratchInAnchor)
        val tcp = requireNotNull(superset.plansFor("tcp_zerocopy"))
        assertTrue(tcp.safeOptions.isEmpty())
        assertNull(tcp.anchorOnly)
    }

    @Test
    fun `the display rule follows the operator decision`() {
        val superset = sample()
        val plans = requireNotNull(superset.plansFor("select_stack"))
        val decision = plans.decision()
        require(decision is OptionDecision.Offer)
        assertEquals(2, decision.options.size)
        assertFalse(decision.pageOffered)

        val single = plans.copy(pageFree = listOf(plans.safeOptions.first()))
        require(single.decision() is OptionDecision.Auto)

        val conditionalOnly = plans.copy(pageFree = listOf(plans.conditionalOptions.first()))
        val offer = conditionalOnly.decision()
        require(offer is OptionDecision.Offer)
        assertTrue(offer.pageOffered)
        assertEquals(1, offer.options.size)

        val none = plans.copy(pageFree = emptyList())
        assertTrue(none.decision() is OptionDecision.NoPageFree)
    }

    @Test
    fun `the cut carries the document's dirtyfrag verdict`() {
        val superset = sample()
        val plan = superset.plansFor("select_stack")!!.safeOptions[0]
        val cut = requireNotNull(superset.cut("select_stack", plan))
        /* The fixed ladder is the document's own verdict, not a list. */
        assertTrue(cut.contains("dirtyfrag = \"feasible\""))
        assertFalse(cut.contains("pipeline ="))
    }

    @Test
    fun `carrier alternatives are exposed and the cut selects one`() {
        val superset = sample()
        val route = requireNotNull(superset.route("select_stack"))
        assertEquals("native", route.carrier)
        val compat = route.alternatives.single()
        assertEquals("compat", compat.carrier)
        val plan = superset.plansFor("select_stack")!!.safeOptions[0]
        val native = requireNotNull(superset.cut("select_stack", plan))
        assertTrue(native.contains("carrier = \"native\""))
        /* The catalogue lists every carrier block; the run-time carrier picks. */
        assertTrue(native.contains("compat {"))
        val chosen = requireNotNull(superset.cut("select_stack", plan, carrier = "compat"))
        assertTrue(chosen.contains("carrier = \"compat\""))
        assertTrue(chosen.contains("waiter_home = 432"))
        assertTrue(chosen.contains("dst_depth = 480"))
        assertTrue(chosen.contains("futex_abi = \"time32\""))
        assertNull(superset.cut("select_stack", plan, carrier = "bogus"))
    }

    @Test
    fun `cut materializes the selected route and plan`() {
        val superset = sample()
        val plan = superset.plansFor("select_stack")!!.safeOptions[0]
        val cut = requireNotNull(superset.cut("select_stack", plan))
        assertTrue(cut.contains("release = \"$release\""))
        assertTrue(cut.contains("waiter_shift = 1"))
        assertTrue(cut.contains("shape = \"compact\""))
        assertTrue(cut.contains("image = \"0x2224cd0\""))
        assertTrue(cut.contains("scratch = \"0x21d1000\""))
        assertTrue(cut.contains("selinux_state {"))
        assertTrue(cut.contains("prio = 1"))
        val page = requireNotNull(superset.cut("select_stack", superset.plansFor("select_stack")!!.page))
        assertTrue(page.contains("anchor = null"))
        assertNull(superset.cut("sigreturn", plan))
    }

    @Test
    fun `a superset without a document cannot cut`() {
        val superset = sample().copy(document = null)
        val plan = superset.plansFor("select_stack")!!.safeOptions[0]
        assertNull(superset.cut("select_stack", plan))
    }
}
