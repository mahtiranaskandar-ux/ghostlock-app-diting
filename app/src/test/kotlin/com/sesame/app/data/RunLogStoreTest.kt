package com.sesame.app.data

import com.sesame.app.domain.model.RunLogState
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store is the only copy of a run now, so its rules get host tests: what a
 * run writes, which runs survive rotation, which one the picker offers first,
 * and how a run that died is told from one that finished.
 */
class RunLogStoreTest {
    private fun root(): File = Files.createTempDirectory("runs").toFile()

    private fun run(
        root: File,
        stamp: String,
        entry: String = "direct",
        state: RunLogState = RunLogState.COMPLETED,
        lines: List<String> = listOf("<k> line"),
        sidecar: String? = null,
    ) {
        val writer = RunLogStore.open(root, entry, stamp)
        lines.forEach(writer::append)
        sidecar?.let { writer.writeSidecar("profile.conf", it.toByteArray()) }
        writer.finish(state)
        writer.close()
    }

    @Test
    fun `a run keeps its log, its sidecar and the state it ended in`() {
        val root = root()
        run(root, "20261009-212803", entry = "shizuku", sidecar = "release = \"x\"\n")

        val dir = File(root, "20261009-212803")
        val lines = File(dir, RunLogStore.LogName).readLines()
        assertTrue("the boot line opens the log", lines.first().startsWith(RunLogStore.BootTag))
        assertTrue("the boot line names the boot", lines.first().contains("id="))
        assertTrue("the boot line carries a wall clock", lines.first().contains("start="))
        assertEquals("<k> line", lines.last())
        assertEquals("release = \"x\"\n", File(dir, "profile.conf").readText())
        assertEquals(RunLogState.COMPLETED, RunLogStore.readMeta(dir).state)
        assertEquals("shizuku", RunLogStore.readMeta(dir).entry)
    }

    @Test
    fun `rotation keeps the newest ten and deletes the rest`() {
        val root = root()
        for (day in 1..12) {
            run(root, String.format("20261009-%06d", day))
        }
        val kept = root.listFiles()!!.map { it.name }.sorted()
        assertEquals(RunLogStore.Keep, kept.size)
        assertFalse("oldest must be gone", kept.contains("20261009-000001"))
        assertTrue("newest must stay", kept.contains("20261009-000012"))
    }

    @Test
    fun `an unfinished run is marked and offered first`() {
        val root = root()
        run(root, "20261009-100000", state = RunLogState.COMPLETED)
        /* A run leaves `running` behind only when nothing wrote a terminal state. */
        run(root, "20261009-110000", state = RunLogState.RUNNING)
        run(root, "20261009-120000", state = RunLogState.COMPLETED)

        assertEquals(1, RunLogStore.markInterrupted(root))

        val listed = RunLogStore.list(root)
        assertEquals("20261009-110000", listed.first().stamp)
        assertEquals(RunLogState.INTERRUPTED, listed.first().state)
        /* Idempotent: a second pass finds nothing still running. */
        assertEquals(0, RunLogStore.markInterrupted(root))
    }

    @Test
    fun `the export zip carries the run under its own folder`() {
        val root = root()
        run(root, "20261009-212803", sidecar = "release = \"x\"\n")

        val bytes = ByteArrayOutputStream()
        assertTrue(RunLogStore.zip(File(root, "20261009-212803"), bytes))

        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val log = entries["20261009-212803/${RunLogStore.LogName}"].orEmpty()
        assertTrue(log.startsWith(RunLogStore.BootTag))
        assertTrue(log.endsWith("<k> line\n"))
        assertEquals("release = \"x\"\n", entries["20261009-212803/profile.conf"])
        assertFalse("internal bookkeeping stays out", entries.keys.any { it.endsWith("/meta") })
    }

    @Test
    fun `the report bundle carries every run, the crash evidence and an index`() {
        val root = root()
        val crashRoot = Files.createTempDirectory("last-crash").toFile()
        run(root, "20261009-212803", sidecar = "release = \"x\"\n")
        val crash = File(crashRoot, "20261010-101500-deadbeef")
        crash.mkdirs()
        File(crash, "boot.txt").writeText(
            "source=shizuku\ncapture_boot_id=deadbeef\ncaptured=1760000000000\n",
        )
        File(crash, "console-ramoops-0").writeText("[    0.000000] Booting Linux\n")

        val bytes = ByteArrayOutputStream()
        assertTrue(RunLogStore.bundleZip(root, crashRoot, bytes))

        val entries = mutableMapOf<String, String>()
        val times = mutableMapOf<String, Long>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                times[entry.name] = entry.time
            }
        }
        val index = entries["index.txt"].orEmpty()
        assertTrue(index.contains("20261009-212803"))
        assertTrue(index.contains("state=completed"))
        assertTrue(index.contains("boot_id="))
        assertTrue(index.contains("log_mtime="))
        assertTrue(index.contains("crash-evidence"))
        assertTrue(index.contains("source=shizuku"))
        assertTrue(entries.containsKey("20261009-212803/${RunLogStore.LogName}"))
        assertEquals(
            "[    0.000000] Booting Linux\n",
            entries["last-crash/20261010-101500-deadbeef/console-ramoops-0"],
        )
        assertFalse(entries.keys.any { it.endsWith("/meta") })
        /* The index and the entries must carry the same wall clock for a run:
         * the zip time is only a fallback for tools that drop the index. DOS
         * zip times have two-second granularity, hence the tolerance. */
        val logMtime = File(File(root, "20261009-212803"), RunLogStore.LogName).lastModified()
        val zipMtime = times["20261009-212803/${RunLogStore.LogName}"] ?: 0L
        assertTrue(
            "zip time must track the log mtime",
            kotlin.math.abs(zipMtime - logMtime) <= 2_000L,
        )
    }

    @Test
    fun `the report bundle carries a session import from memory`() {
        val root = root()
        val crashRoot = Files.createTempDirectory("last-crash").toFile()
        run(root, "20261009-212803")
        val imported = com.sesame.app.domain.model.ImportedEvidence(
            bugreportName = "bugreport-test.zip",
            capturedMs = 1_760_000_000_000L,
            captureBootId = "boot-test",
            facts = linkedMapOf(
                "source" to "bugreport",
                "capture_boot_id" to "boot-test",
                "captured" to "1760000000000",
                "bugreport_name" to "bugreport-test.zip",
                "crash_evidence" to "yes",
            ),
            history = "reboot,shell,123",
            files = linkedMapOf(
                CrashEvidenceStore.BootFactsName to
                    "source=bugreport\ncapture_boot_id=boot-test\n".toByteArray(),
                CrashEvidenceStore.KernelLogName to "current boot\n".toByteArray(),
                "stability-last_kmsg" to "previous boot oops\n".toByteArray(),
            ),
            crashEvidence = true,
        )

        val bytes = ByteArrayOutputStream()
        assertTrue(RunLogStore.bundleZip(root, crashRoot, bytes, imported))

        val entries = mutableMapOf<String, ByteArray>()
        val times = mutableMapOf<String, Long>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                times[entry.name] = entry.time
            }
        }
        val importEntry = entries.keys.firstOrNull {
            it.startsWith("last-crash/") && it.endsWith("-import/stability-last_kmsg")
        }
        assertTrue("the in-memory import must be in the bundle", importEntry != null)
        assertEquals(
            "previous boot oops\n",
            entries.getValue(importEntry!!).toString(Charsets.UTF_8),
        )
        val index = entries.getValue("index.txt").toString(Charsets.UTF_8)
        assertTrue(index.contains("source=bugreport"))
        assertTrue(index.contains("capture_boot_id=boot-test"))
        assertTrue(index.contains("captured=1760000000000"))
        assertTrue(index.contains("stability-last_kmsg"))
        assertTrue(kotlin.math.abs((times[importEntry] ?: 0L) - 1_760_000_000_000L) <= 2_000L)
    }

    @Test
    fun `an unreadable file is skipped, not fatal to the whole report`() {
        /* The root script's dumps land root-owned; on a run where it did not
         * chmod them the app cannot read them. That must cost the dump, not the
         * log -- the log is the whole point of the export. */
        val root = root()
        run(root, "20261009-212803")
        val locked = File(File(root, "20261009-212803"), "kernel-dmesg.log")
        locked.writeText("[    0.000000] Booting Linux\n")
        locked.setReadable(false)
        if (locked.canRead()) return // running as root: the premise cannot hold

        val bytes = ByteArrayOutputStream()
        assertTrue(RunLogStore.zip(File(root, "20261009-212803"), bytes))

        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(
            "the log must survive",
            entries["20261009-212803/${RunLogStore.LogName}"]?.isNotEmpty() == true,
        )
        assertFalse(
            "the unreadable dump is absent, not present-and-empty",
            entries.keys.any { it.endsWith("kernel-dmesg.log") },
        )
        locked.setReadable(true)
    }

    @Test
    fun `an unknown state reads as interrupted rather than finished`() {
        assertEquals(RunLogState.COMPLETED, RunLogState.fromToken("completed"))
        assertEquals(RunLogState.INTERRUPTED, RunLogState.fromToken(""))
        assertEquals(RunLogState.INTERRUPTED, RunLogState.fromToken("garbage"))
    }
}
