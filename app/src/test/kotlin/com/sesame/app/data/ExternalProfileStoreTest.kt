package com.sesame.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalProfileStoreTest {
    private val release = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"

    @Test
    fun `parses an object document and converts the legacy layout`() {
        val text = """
            [
              {
                "release": "$release",
                "route": "select_stack",
                "pselect_waiter_shift": -2,
                "compact_waiter": 1
              }
            ]
        """.trimIndent()

        val entries = ProfileDocumentParser.entries(text)

        assertNotNull(entries)
        assertEquals(1, entries!!.size)
        val entry = entries.single()
        assertEquals(release, entry["release"])
        /* The converter moves the flat select keys into the route branch. */
        val route = entry["route"].asValueMap()
        assertNotNull(route)
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
            schema_version = 1
        """.trimIndent()

        val entries = ProfileDocumentParser.entries(text) { target ->
            if (target == "included.conf") {
                "kernel_major = 5\nroute { select_stack { waiter_shift = -2 } }"
            } else {
                null
            }
        }

        val entry = entries?.single()
        assertEquals(5L, (entry?.get("kernel_major") as Number?)?.toLong())
        assertNotNull(entry?.get("route"))
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
    fun `an unversioned scalar copy gets schema 1`() {
        val text = """
            release = "$release"
            kernel_major = 5
            route { select_stack { waiter_shift = -2 } }
        """.trimIndent()

        val upgraded = ImportedProfileSchema.ensureSchema(text)
        val entry = checkNotNull(HoconSupport.parseValue(upgraded).asValueMap())

        assertEquals(1L, (entry["schema_version"] as Number).toLong())
    }

    @Test
    fun `an unversioned copy with phys maps gets schema 2`() {
        val text = """
            release = "$release"
            kernel_phys_load = { diting = 1 }
        """.trimIndent()

        val upgraded = ImportedProfileSchema.ensureSchema(text)
        val entry = checkNotNull(HoconSupport.parseValue(upgraded).asValueMap())

        assertEquals(2L, (entry["schema_version"] as Number).toLong())
    }

    @Test
    fun `a versioned copy is left unchanged`() {
        val text = """
            release = "$release"
            schema_version = 2
            kernel_phys_load = { diting = 1 }
        """.trimIndent()

        assertEquals(text, ImportedProfileSchema.ensureSchema(text))
    }

    @Test
    fun `a multi-entry copy is versioned per entry`() {
        val text = """
            [
              { release = "6.1.1-a" },
              { release = "$release", schema_version = 2 }
            ]
        """.trimIndent()

        val upgraded = ImportedProfileSchema.ensureSchema(text)
        val entries = checkNotNull(HoconSupport.parseValue(upgraded).asValueList())
            .mapNotNull { it.asValueMap() }

        assertEquals(1L, (entries[0]["schema_version"] as Number).toLong())
        assertEquals(2L, (entries[1]["schema_version"] as Number).toLong())
    }

    @Test
    fun `the lineage header survives the schema rewrite`() {
        val text = """
            # ghostlock-profile parent=external:my-profile.conf parent_sha256=abc
            release = "$release"
        """.trimIndent()

        val upgraded = ImportedProfileSchema.ensureSchema(text)

        assertTrue(upgraded.startsWith("# ghostlock-profile parent=external:my-profile.conf"))
        assertTrue(upgraded.contains("schema_version = 1"))
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
