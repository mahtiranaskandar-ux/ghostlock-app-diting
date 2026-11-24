package com.sesame.app.data

import com.sesame.app.data.profile.PerDevicePhys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The store envelope keeps every device pair in the schema shape
 * (`document.devices.<codename>.phys`) and only the evidence in
 * `device_analysis[]`. The cut narrows the document to the chosen device; it
 * never assembles a pair.
 */
class OptionSupersetDeviceCutTest {
    private val release = "5.10.198-android12-9-00085-g226a9632f13d-ab11136126"

    private val bothPairs: ValueMap = valueMapOf(
        "diting" to valueMapOf(
            "phys" to valueMapOf("load" to "0xa8000000", "offset" to null),
        ),
        "yunluo" to valueMapOf(
            "phys" to valueMapOf("load" to "0x40000000", "offset" to "0x40000000"),
        ),
    )

    private fun superset(documentDevices: Any? = bothPairs): OptionSuperset {
        val document = CleanedProfileFixture.document(release)
        document["devices"] = documentDevices
        val ghostlock = document["ghostlock"].asValueMap()!!
        ghostlock["syscalls"] = valueMapOf(
            "select_stack" to valueMapOf(
                "native" to valueMapOf("waiter_shift" to -2L),
                "compat" to null,
            ),
            "sigreturn" to null,
            "multicast_waiter" to null,
            "tcp_zerocopy" to null,
        )
        val plans = valueListOf(
            valueMapOf(
                "route" to "select_stack",
                "page" to valueMapOf(
                    "route" to "select_stack",
                    "anchor" to null,
                    "scratch" to null,
                    "page_free" to false,
                    "conditioned" to false,
                    "reason" to "page",
                ),
                "page_free" to valueListOf(),
            ),
        )
        val envelope = valueMapOf(
            "schema_version" to 3L,
            "release" to release,
            "kernel_major" to 5L,
            "routes" to valueListOf(
                valueMapOf(
                    "route" to "select_stack",
                    "carrier" to "native",
                    "verdict" to "feasible",
                    "reason" to "",
                    "anchor_capable" to true,
                    "geometry" to valueMapOf("waiter_shift" to -2L),
                ),
            ),
            "document" to document,
            "device_analysis" to valueListOf(
                valueMapOf(
                    "device" to "diting",
                    "phys_source" to "uefi memory map",
                    "candidates" to valueListOf(),
                    "plans" to plans,
                ),
                valueMapOf(
                    "device" to "yunluo",
                    "phys_source" to "vendor_boot header (MediaTek)",
                    "candidates" to valueListOf(),
                    "plans" to plans,
                ),
            ),
        )
        return requireNotNull(OptionSuperset.parse(HoconSupport.render(envelope)))
    }

    private fun cutDocument(text: String): ValueMap =
        HoconSupport.parseValue(text).asValueMap() ?: error("cut is not an object")

    private fun pair(document: ValueMap, device: String): Pair<Long?, Long?> =
        com.sesame.app.data.profile.ProfileResolver.physOf(
            PerDevicePhys.resolveFor(document, listOf(device)).document,
        )

    @Test
    fun `the cut narrows the document to the primary device`() {
        val superset = superset()
        assertEquals("diting", superset.device)
        val plans = superset.plansFor("select_stack") ?: error("no plans")
        val document = cutDocument(superset.cut("select_stack", plans.page)!!)
        assertEquals(3, (document["schema_version"] as Number).toInt())
        assertEquals(2818572288L to null, pair(document, "diting"))
        /* The other device's pair is not in the cut. */
        assertNull(document["devices"].asValueMap()?.get("yunluo"))
    }

    @Test
    fun `the cut can select another device`() {
        val superset = superset()
        val plans = superset.plansFor("select_stack") ?: error("no plans")
        val document = cutDocument(superset.cut("select_stack", plans.page, "yunluo")!!)
        assertEquals(1073741824L to 1073741824L, pair(document, "yunluo"))
    }

    @Test
    fun `a document without a pair carries no pair`() {
        /* A pass with no device sidecar set states `devices = null`: the cut
         * keeps the compiled defaults, and a platform that needs the pair
         * refuses it at import (fail-closed). */
        val superset = superset(documentDevices = null)
        val plans = superset.plansFor("select_stack") ?: error("no plans")
        val document = cutDocument(superset.cut("select_stack", plans.page)!!)
        assertEquals(3, (document["schema_version"] as Number).toInt())
        assertEquals(null to null,
            com.sesame.app.data.profile.ProfileResolver.physOf(document))
    }
}
