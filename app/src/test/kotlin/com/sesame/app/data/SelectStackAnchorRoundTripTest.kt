package com.sesame.app.data

import com.sesame.app.data.profile.ProfileMerger
import com.sesame.app.data.profile.ProfileTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the select branch's anchor keys across the whole Kotlin half of the
 * pipeline: authored map -> merge/conversion -> transport document. The anchor
 * lives once in `ghostlock.anchor`; the branch states its own geometry.
 */
class SelectStackAnchorRoundTripTest {
    private val release = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"
    private val anchor = 44279184L // dump_skip.zeroes, image +0x2a3a590
    private val anchorBytes = 0x1000L
    private val anchorStride = 0x20L

    private fun document(withAnchor: Boolean): ValueMap {
        val anchorBlock = if (withAnchor) {
            valueMapOf(
                "image" to "0x" + anchor.toString(16),
                "bytes" to anchorBytes,
                "stride" to anchorStride,
                "scratch" to null,
            )
        } else {
            null
        }
        return CleanedProfileFixture.document(
            release = release,
            route = "select_stack",
            branch = valueMapOf("waiter_shift" to -2L),
            anchor = anchorBlock,
        )
    }

    private fun transport(document: ValueMap): ValueMap {
        val merged = ProfileMerger.resolveMerged(
            deviceRelease = release,
            builtin = null,
            imported = document,
            tuningExecution = null,
            routePresets = emptyMap(),
        )
        return HoconSupport.parseValue(
            ProfileTransport.render(merged, route = "select_stack"),
        ).asValueMap()!!
    }

    @Test
    fun `the anchor and the branch geometry reach the document`() {
        val document = transport(document(withAnchor = true))
        val anchorBlock = (document["ghostlock"] as ValueMap)["anchor"] as ValueMap
        assertEquals("0x" + anchor.toString(16), anchorBlock["image"])
        assertEquals(anchorBytes, (anchorBlock["bytes"] as Number).toLong())
        assertEquals(anchorStride, (anchorBlock["stride"] as Number).toLong())
        val syscalls = (document["ghostlock"] as ValueMap)["syscalls"] as ValueMap
        val native = ((syscalls["select_stack"] as ValueMap)["native"] as ValueMap)
        assertEquals(-2L, (native["waiter_shift"] as Number).toLong())
        assertEquals("compact", document.getLongAt("kernel.struct.waiter.shape").let { null } ?: ((document["kernel"] as ValueMap)["struct"] as ValueMap).let {
            ((it["waiter"] as ValueMap)["shape"])
        })
    }

    @Test
    fun `an absent anchor stays absent`() {
        val document = transport(document(withAnchor = false))
        assertNull((document["ghostlock"] as ValueMap)["anchor"])
    }
}
