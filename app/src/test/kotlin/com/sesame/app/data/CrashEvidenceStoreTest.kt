package com.sesame.app.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashEvidenceStoreTest {
    private fun root(): File = Files.createTempDirectory("last-crash").toFile()

    private fun capture(
        root: File,
        bootId: String,
        capturedMs: Long,
        source: String = "shizuku",
        history: String? = null,
        pstore: Boolean = false,
    ): File {
        val dir = CrashEvidenceStore.newDir(root, bootId, capturedMs)
        if (pstore) {
            File(dir, "console-ramoops-0").writeText("[    0.000000] Booting Linux\n")
        }
        CrashEvidenceStore.complete(
            root,
            dir,
            mapOf(
                "source" to source,
                "capture_boot_id" to bootId,
                "ro.boot.bootreason" to "kernel_panic",
                "pstore.console-ramoops-0" to if (pstore) "ok" else "missing",
            ),
            history,
            capturedMs,
        )
        return dir
    }

    @Test
    fun `stored imports are ignored and removed`() {
        val root = root()
        capture(root, "auto", 1_760_000_000_000L, source = "shizuku", pstore = true)
        capture(root, "imported", 1_760_000_001_000L, source = "bugreport")
        assertEquals("auto", CrashEvidenceStore.latestAutomatic(root)?.captureBootId)
        CrashEvidenceStore.clearImported(root)
        val left = CrashEvidenceStore.captures(root)
        assertEquals(1, left.size)
        assertEquals("auto", left.single().captureBootId)
        assertTrue(left.none { it.source == "bugreport" })
    }

    @Test
    fun `rotation keeps the newest three captures`() {
        val root = root()
        for (index in 1..5) {
            capture(root, "boot$index", 1_760_000_000_000L + index * 1_000L)
        }
        val captures = CrashEvidenceStore.captures(root)
        assertEquals(CrashEvidenceStore.Keep, captures.size)
        assertEquals("boot5", captures.first().captureBootId)
        assertEquals("boot3", captures.last().captureBootId)
    }

    @Test
    fun `a capture parses its facts, history and kernel file`() {
        val root = root()
        capture(
            root,
            "deadbeef",
            1_760_000_000_000L,
            history = "kernel_panic,1794794473\nreboot,shell,1794794355",
            pstore = true,
        )
        val capture = CrashEvidenceStore.captures(root).single()
        assertEquals("shizuku", capture.source)
        assertEquals("deadbeef", capture.captureBootId)
        assertEquals(1_760_000_000_000L, capture.capturedMs)
        assertEquals("kernel_panic", capture.bootReason)
        assertTrue(capture.hasKernelLog)
        assertNotNull(capture.historyFile)
        assertEquals(
            "kernel_panic,1794794473\nreboot,shell,1794794355",
            capture.historyFile!!.readText(),
        )
        assertTrue(capture.pstoreFiles.contains("console-ramoops-0"))
    }

    @Test
    fun `an incomplete directory is not a capture`() {
        val root = root()
        File(root, "20260101-000000-abcd").mkdirs()
        assertTrue(CrashEvidenceStore.captures(root).isEmpty())
        assertNull(CrashEvidenceStore.findForBoot(root, "abcd"))
    }

    @Test
    fun `findForBoot returns the capture for that boot`() {
        val root = root()
        capture(root, "bootA", 1_760_000_000_000L)
        capture(root, "bootB", 1_760_000_001_000L)
        val found = CrashEvidenceStore.findForBoot(root, "bootA")
        assertNotNull(found)
        assertEquals("bootA", found!!.captureBootId)
        assertNull(CrashEvidenceStore.findForBoot(root, "bootC"))
    }

    @Test
    fun `a bare capture has no kernel log`() {
        val root = root()
        capture(root, "bootA", 1_760_000_000_000L, source = "none")
        val capture = CrashEvidenceStore.captures(root).single()
        assertEquals("none", capture.source)
        assertFalse(capture.hasKernelLog)
    }
}
