package com.sesame.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document parser: text in, entries out, as authored. It does not convert
 * or upgrade a layout — schema v3 is enforced by the resolver, and a v1/v2 (or
 * unversioned) document is refused there.
 */
class ExternalProfileStoreTest {
    private val release = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"

    @Test
    fun `parses an object document without rewriting its layout`() {
        val text = """
            [
              {
                "release": "$release",
                "schema_version": 3,
                "route": { "select_stack": { "waiter_shift": -2 } }
              }
            ]
        """.trimIndent()

        val entries = ProfileDocumentParser.entries(text)

        assertNotNull(entries)
        assertEquals(1, entries!!.size)
        val entry = entries.single()
        assertEquals(release, entry["release"])
        val route = entry["route"].asValueMap()
        assertEquals(
            -2L,
            (route!!.get("select_stack").asValueMap()?.get("waiter_shift") as Number?)?.toLong(),
        )
    }

    @Test
    fun `resolves an include through the supplied loader`() {
        val text = """
            include "included.conf"
            release = "$release"
            schema_version = 3
        """.trimIndent()

        val entries = ProfileDocumentParser.entries(text) { target ->
            if (target == "included.conf") {
                "kernel { heap { stride = 960 } }\ndevices = null\nghostlock = null\ntimer = null"
            } else {
                null
            }
        }

        val entry = entries?.single()
        assertNotNull(entry?.get("kernel"))
        val stride = (((entry?.get("kernel") as Map<*, *>)["heap"] as Map<*, *>)["stride"] as Number)
        assertEquals(960L, stride.toLong())
    }

    @Test
    fun `a missing include fails the parse instead of guessing`() {
        val text = """
            include "absent.conf"
            release = "$release"
        """.trimIndent()

        assertNull(ProfileDocumentParser.entries(text))
        assertNull(ProfileDocumentParser.entries(text) { null })
    }

    @Test
    fun `entryForRelease picks the matching entry of a multi-release document`() {
        val text = """
            [
              { release = "6.1.1-a", kernel_major = 6 },
              { release = "$release", kernel_major = 5 }
            ]
        """.trimIndent()

        val entry = ProfileDocumentParser.entryForRelease(text, release)

        assertEquals(5L, (entry?.get("kernel_major") as Number?)?.toLong())
        assertNull(ProfileDocumentParser.entryForRelease(text, "9.9.9-unknown"))
    }

    @Test
    fun `a document without a release is not a profile`() {
        assertNull(ProfileDocumentParser.entries("kernel_major = 5\n"))
        assertNull(ProfileDocumentParser.entries("not hocon {{{"))
    }

    @Test
    fun `sha256 is the document identity`() {
        assertEquals(
            "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
            sha256Of("a"),
        )
        assertTrue(sha256Of("a") != sha256Of("b"))
    }
}
