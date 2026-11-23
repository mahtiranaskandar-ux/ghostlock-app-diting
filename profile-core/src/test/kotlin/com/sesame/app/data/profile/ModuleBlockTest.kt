package com.sesame.app.data.profile

import com.sesame.app.data.valueMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleBlockTest {
    private val release = "5.4.233-qgki-gbb70cde46897"

    private fun block(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> = valueMapOf(
        "version" to 1L,
        "asset" to "modules/$release.ko",
        "asset_sha256" to "a".repeat(64),
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
        assertTrue(ModuleBlock.validate("module").any { it.fieldPath == "module" })
        val (config, errors) = ModuleBlock.parse(block(mapOf("version" to 2L)))
        assertNull(config)
        assertTrue(errors.any { it.fieldPath == "module.version" })
    }

    @Test
    fun `unknown keys and a wrong version are refused`() {
        assertTrue("module.surprise" in errors(mapOf("surprise" to 1)))
        assertTrue("module.version" in errors(mapOf("version" to 2L)))
        assertTrue("module.version" in errors(mapOf("version" to "x")))
    }

    @Test
    fun `the asset must be a relative modules ko`() {
        assertTrue("module.asset" in errors(mapOf("asset" to "/data/x.ko")))
        assertTrue("module.asset" in errors(mapOf("asset" to "modules/x.img")))
        assertTrue("module.asset" in errors(mapOf("asset" to "modules/../x.ko")))
        assertTrue("module.asset" in errors(mapOf("asset" to "modules//x.ko")))
        assertTrue("module.asset" in errors(mapOf("asset" to "kernel_profiles/x.ko")))
    }

    @Test
    fun `the hash must be 64 hex characters`() {
        assertTrue("module.asset_sha256" in errors(mapOf("asset_sha256" to "abc")))
        assertTrue("module.asset_sha256" in errors(mapOf("asset_sha256" to "g".repeat(64))))
        assertEquals(emptyList<String>(), errors(mapOf("asset_sha256" to "A".repeat(64))))
    }

    @Test
    fun `the vendor targets are validated`() {
        assertTrue("module.vendor_files" in errors(mapOf("vendor_files" to emptyList<String>())))
        assertTrue("module.vendor_files" in errors(mapOf("vendor_files" to "/vendor/x.so")))
        assertTrue("module.vendor_files" in errors(mapOf("vendor_files" to listOf("/system/lib64/x.so"))))
        assertTrue("module.vendor_files" in errors(mapOf("vendor_files" to listOf("/vendor/lib64/"))))
        assertTrue("module.vendor_files" in errors(mapOf("vendor_files" to listOf("/vendor/../x.so"))))
        assertTrue(
            "module.vendor_files" in errors(
                mapOf("vendor_files" to listOf("/vendor/lib64/" + "a".repeat(50))),
            ),
        )
        assertTrue(
            "module.vendor_files" in errors(
                mapOf(
                    "vendor_files" to listOf(
                        "/vendor/lib64/libbinderdebug.so",
                        "/vendor/lib64/libbinderdebug.so",
                    ),
                ),
            ),
        )
        /* The stub's field holds 63 bytes plus the NUL. */
        assertEquals(
            emptyList<String>(),
            errors(mapOf("vendor_files" to listOf("/vendor/lib64/" + "a".repeat(49)))),
        )
    }
}
