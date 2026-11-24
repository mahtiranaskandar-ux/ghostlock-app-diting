package com.sesame.app.data.profile

import com.sesame.app.data.valueMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleBlockTest {
    private val release = "5.4.233-qgki-gbb70cde46897"

    private fun block(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> = valueMapOf(
        "asset" to "modules/$release.ko",
        "sha256" to "a".repeat(64),
        "vendor_files" to listOf("/vendor/lib64/libbinderdebug.so"),
    ) + overrides

    private fun errors(overrides: Map<String, Any?>): List<String> =
        ModuleBlock.validate(block(overrides)).map { it.fieldPath }

    @Test
    fun `a complete block parses`() {
        val (config, errors) = ModuleBlock.parse(block())
        assertTrue(errors.isEmpty())
        assertEquals("modules/$release.ko", config?.asset)
        assertEquals("a".repeat(64), config?.assetSha256)
        assertEquals(listOf("/vendor/lib64/libbinderdebug.so"), config?.vendorFiles)
    }

    @Test
    fun `the vendor list is optional`() {
        val (config, errors) = ModuleBlock.parse(block(mapOf("vendor_files" to null)))
        assertTrue(errors.isEmpty())
        assertEquals(emptyList<String>(), config?.vendorFiles)
    }

    @Test
    fun `absence is not an error and a non-object is`() {
        assertTrue(ModuleBlock.validate(null).isEmpty())
        assertTrue(ModuleBlock.validate("module").any { it.fieldPath == "ghostlock.module" })
        val (config, errors) = ModuleBlock.parse(block(mapOf("asset" to 7L)))
        assertNull(config)
        assertTrue(errors.any { it.fieldPath == "ghostlock.module.asset" })
    }

    @Test
    fun `unknown keys are refused`() {
        assertTrue("ghostlock.module.surprise" in errors(mapOf("surprise" to 1)))
        /* The retired schema stated a block version. */
        assertTrue("ghostlock.module.version" in errors(mapOf("version" to 2L)))
    }

    @Test
    fun `the asset must be a relative modules ko`() {
        for (asset in listOf("x.ko", "modules/../x.ko", "modules/x.so", "/modules/x.ko")) {
            assertTrue(
                "asset $asset",
                "ghostlock.module.asset" in errors(mapOf("asset" to asset)),
            )
        }
    }

    @Test
    fun `the hash must be 64 hex characters`() {
        assertTrue("ghostlock.module.sha256" in errors(mapOf("sha256" to "abc")))
        assertTrue("ghostlock.module.sha256" in errors(mapOf("sha256" to "g".repeat(64))))
    }

    @Test
    fun `the vendor targets are validated`() {
        for (files in listOf(
            listOf("libbinderdebug.so"),
            listOf("/system/lib64/x.so"),
            listOf("/vendor/lib64/../x.so"),
            listOf("/vendor/lib64/x.so", "/vendor/lib64/x.so"),
            emptyList<String>(),
        )) {
            assertTrue(
                "files $files",
                "ghostlock.module.vendor_files" in errors(mapOf("vendor_files" to files)),
            )
        }
    }
}
