package com.sesame.app.data

import com.sesame.app.data.profile.ProfileMerger
import com.sesame.app.data.profile.ProfileTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the multicast branch's geometry and the shared anchor through the
 * Kotlin half of the pipeline: authored map -> merge/conversion -> transport
 * document. The strip's task/lock offsets are one fact and live in
 * `kernel.struct.waiter`; the branch states only its own copy depths.
 */
class MulticastAnchorRoundTripTest {
    private val release = "5.10.synthetic-anchor-vector"
    private val anchor = 0x400000L
    private val anchorBytes = 0x600L
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
            route = "multicast_waiter",
            branch = valueMapOf(
                "waiter_off" to 48L,
                "buffer_size" to 264L,
            ),
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
            ProfileTransport.render(merged, route = "multicast_waiter"),
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
        val native = ((syscalls["multicast_waiter"] as ValueMap)["native"] as ValueMap)
        assertEquals(48L, (native["waiter_off"] as Number).toLong())
        assertEquals(264L, (native["buffer_size"] as Number).toLong())
        /* task/lock are the shared waiter layout, not branch copies. */
        val waiter = (((document["kernel"] as ValueMap)["struct"] as ValueMap)["waiter"] as ValueMap)
        assertEquals(48L, (waiter["task"] as Number).toLong())
        assertEquals(56L, (waiter["lock"] as Number).toLong())
    }

    @Test
    fun `the compat carrier states its own geometry`() {
        val document = document(withAnchor = true)
        val ghostlock = document["ghostlock"] as ValueMap
        ghostlock["carrier"] = "compat"
        val branch = (ghostlock["syscalls"] as ValueMap)["multicast_waiter"] as ValueMap
        branch["native"] = null
        branch["compat"] = valueMapOf(
            "waiter_off" to 0xa8L,
            "buffer_size" to 0x104L,
            "futex_abi" to "time64",
        )
        val rendered = transport(document)
        val syscalls = (rendered["ghostlock"] as ValueMap)["syscalls"] as ValueMap
        val compat = ((syscalls["multicast_waiter"] as ValueMap)["compat"] as ValueMap)
        assertEquals(0xa8L, (compat["waiter_off"] as Number).toLong())
        assertEquals(0x104L, (compat["buffer_size"] as Number).toLong())
        assertEquals("time64", compat["futex_abi"])
    }

    @Test
    fun `an absent anchor stays absent`() {
        val document = transport(document(withAnchor = false))
        assertNull((document["ghostlock"] as ValueMap)["anchor"])
    }
}
