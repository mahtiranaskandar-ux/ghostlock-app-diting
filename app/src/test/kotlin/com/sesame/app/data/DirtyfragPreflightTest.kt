package com.sesame.app.data

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirtyfragPreflightTest {
    private fun layout(
        execOffset: Long = 0x1000,
        execFilesz: Long = 0x900,
        hook: Long = 0x1800,
        fileLength: Long = 0x4000,
    ) = DirtyfragPlan.CaveLayout(execOffset, execFilesz, hook, fileLength)

    /* The two shipped blobs and their `stub_code` offsets. */
    private val plain = File("src/main/assets/dirtyfrag_stub.bin")
    private val module = File("src/main/assets/dirtyfrag_stub_module.bin")

    private fun fileSize(file: File): Int {
        assertTrue("missing ${file.absolutePath}", file.isFile)
        return file.length().toInt()
    }

    @Test
    fun `a fitting cave passes`() {
        assertTrue(DirtyfragPlan.checkCave(layout(), 420, 0x50).isEmpty())
    }

    @Test
    fun `a page remainder smaller than the stub is rejected`() {
        val reasons = DirtyfragPlan.checkCave(layout(execFilesz = 0xf00), 420, 0x50)
        assertTrue(reasons.any { it.contains("cave page remainder") })
    }

    @Test
    fun `a cave at a page boundary is rejected`() {
        val reasons = DirtyfragPlan.checkCave(layout(execFilesz = 0x1000), 420, 0x50)
        assertTrue(reasons.any { it.contains("page boundary") })
    }

    @Test
    fun `a cave past the file end is rejected`() {
        val reasons = DirtyfragPlan.checkCave(layout(fileLength = 0x1900), 420, 0x50)
        assertTrue(reasons.any { it.contains("cave outside the file") })
    }

    @Test
    fun `a branch out of the arm64 range is rejected`() {
        val reasons = DirtyfragPlan.checkCave(
            layout(
                execOffset = 0x2000_0000,
                execFilesz = 0x900,
                hook = 0x1000,
                fileLength = 0x2000_2000,
            ),
            420,
            0x50,
        )
        assertTrue(reasons.any { it.contains("hook branch out of range") })
    }

    @Test
    fun `branch fits the signed 26-bit range with 4-byte alignment`() {
        assertTrue(DirtyfragPlan.branchFits(0, 0x7ff_fffc))
        assertFalse(DirtyfragPlan.branchFits(0, 0x800_0000))
        assertTrue(DirtyfragPlan.branchFits(0x800_0000, 0))
        assertFalse(DirtyfragPlan.branchFits(0x800_0004, 0))
        assertFalse(DirtyfragPlan.branchFits(0, 2))
    }

    @Test
    fun `the plain blob fits the devices the module blob does not`() {
        val plainSize = fileSize(plain)
        val moduleSize = fileSize(module)
        /* The OnePlus 6.6.66's exec-segment page remainder. */
        val onePlus = layout(execFilesz = 0xd80) // 640 B remainder
        assertTrue(
            "plain $plainSize B must fit the 640 B remainder",
            DirtyfragPlan.checkCave(onePlus, plainSize, 0x50).isEmpty(),
        )
        assertTrue(
            "module $moduleSize B must not fit the 640 B remainder",
            DirtyfragPlan.checkCave(onePlus, moduleSize, 0xd0)
                .any { it.contains("cave page remainder") },
        )
    }

    @Test
    fun `the module blob fits both module-route devices`() {
        val moduleSize = fileSize(module)
        /* star 5.4.233: 1496 B; venus 5.4.274: 2136 B. */
        for ((name, filesz) in listOf("star" to 0xa28L, "venus" to 0x7a8L)) {
            assertTrue(
                "module $moduleSize B must fit $name's cave",
                DirtyfragPlan.checkCave(layout(execFilesz = filesz), moduleSize, 0xd0).isEmpty(),
            )
        }
    }

    @Test
    fun `each stub ends with the two patched tail words`() {
        for ((name, file) in listOf("plain" to plain, "module" to module)) {
            val bytes = file.readBytes()
            assertArrayEquals(
                "$name: the tail must be the original-insn and return-branch placeholders",
                byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte(), 0x00, 0x00, 0x00, 0x14),
                bytes.copyOfRange(bytes.size - 8, bytes.size),
            )
        }
    }
}
