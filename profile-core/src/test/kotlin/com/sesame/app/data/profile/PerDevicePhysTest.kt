package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.valueMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerDevicePhysTest {
    private fun document(
        load: Any? = valueMapOf("diting" to "0xa8000000", "duchamp" to 0x40000000L),
        offset: Any? = valueMapOf("diting" to null, "duchamp" to 0x40000000L),
    ): ValueMap = valueMapOf(
        "schema_version" to 3L,
        "release" to "6.1.138-android14-11-g44bda9e8f6e9-ab13792638",
        "devices" to devices(load, offset),
    )

    private fun devices(load: Any?, offset: Any?): Any? {
        val loadMap = load.asValueMap()
        val offsetMap = offset.asValueMap()
        if (loadMap == null && offsetMap == null) return null
        val ids = linkedSetOf<String>()
        loadMap?.keys?.forEach { ids += it }
        offsetMap?.keys?.forEach { ids += it }
        return ids.associateWithTo(linkedMapOf()) { id ->
            valueMapOf(
                "phys" to valueMapOf(
                    "load" to loadMap?.get(id),
                    "offset" to offsetMap?.get(id),
                ),
            )
        }
    }

    @Test
    fun `the running device's entry is selected and kept alone`() {
        val resolved = PerDevicePhys.resolveFor(document(), listOf("duchamp"))
        assertEquals("duchamp", resolved.device)
        val devices = resolved.document["devices"].asValueMap()
        assertEquals(1, devices?.size)
        assertEquals(0x40000000L, ProfileResolver.physOf(resolved.document).first)
        assertEquals(0x40000000L, ProfileResolver.physOf(resolved.document).second)
    }

    @Test
    fun `candidates are tried in order and case is ignored`() {
        val resolved = PerDevicePhys.resolveFor(document(), listOf("other", "DITING"))
        assertEquals("diting", resolved.device)
        assertNull(ProfileResolver.physOf(resolved.document).second)
        assertEquals(0xa8000000L, ProfileResolver.physOf(resolved.document).first)
    }

    @Test
    fun `an unlisted device is refused, not resolved to the default`() {
        val refused = runCatching { PerDevicePhys.resolveFor(document(), listOf("stranger")) }
        assertTrue(refused.isFailure)
        val neutral = PerDevicePhys.resolveFor(
            document(), listOf("stranger"), requireMatch = false,
        )
        assertNull(neutral.device)
    }

    @Test
    fun `absent and null devices are the native default`() {
        for (devices in listOf(null, linkedMapOf<String, Any?>())) {
            val document = document().apply { put("devices", devices) }
            val resolved = PerDevicePhys.resolveFor(document, listOf("diting"))
            assertNull(resolved.device)
            assertEquals(null to null, ProfileResolver.physOf(resolved.document))
        }
    }

    @Test
    fun `the neutral path drops the map`() {
        val neutral = PerDevicePhys.resolveNeutral(document())
        assertTrue(neutral.containsKey("devices"))
        assertNull(neutral["devices"])
    }

    @Test
    fun `a non-object devices block is refused`() {
        val bad = document().apply { put("devices", "diting") }
        assertTrue(runCatching { PerDevicePhys.resolveFor(bad, listOf("diting")) }.isFailure)
    }

    @Test
    fun `an old schema version is refused`() {
        val bad = document().apply { put("schema_version", 2L) }
        val error = runCatching { PerDevicePhys.resolveFor(bad, listOf("diting")) }.exceptionOrNull()
        assertEquals(ProfileResolver.schemaErrorMessage(2), error?.message)
    }

    @Test
    fun `the device-neutral path refuses an old schema too`() {
        val bad = document().apply { put("schema_version", 2L) }
        val error = runCatching { PerDevicePhys.resolveNeutral(bad) }.exceptionOrNull()
        assertEquals(ProfileResolver.schemaErrorMessage(2), error?.message)
    }
}
