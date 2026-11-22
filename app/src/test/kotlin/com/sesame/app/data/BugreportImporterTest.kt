package com.sesame.app.data

import com.sesame.app.domain.model.ImportedEvidence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BugreportImporterTest {
    private fun buildZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray =
        buildZip(entries.map { it.first to it.second.toByteArray() })

    private fun zipRaw(vararg entries: Pair<String, ByteArray>): ByteArray =
        buildZip(entries.toList())

    private fun extract(
        bytes: ByteArray,
        name: String = "bugreport-test.zip",
        bootId: String = "boot-test",
    ): ImportedEvidence? = BugreportImporter.extract(
        ByteArrayInputStream(bytes),
        name,
        bootId,
        1_760_000_000_000L,
    )

    private fun ImportedEvidence.text(name: String): String =
        String(files.getValue(name), StandardCharsets.UTF_8)

    private val mainText = buildString {
        appendLine("[ro.boot.bootreason]: [kernel_panic]")
        appendLine("[sys.boot.reason]: [kernel_panic]")
        appendLine("[sys.boot.reason.last]: [kernel_panic]")
        appendLine("[persist.sys.boot.reason.history]: [kernel_panic,1794794473")
        appendLine("reboot,shell,1794794355")
        appendLine("reboot,shell,1794794267]")
        appendLine("noise")
        appendLine("------ KERNEL LOG (dmesg) ------")
        appendLine("[    0.000000] Booting Linux")
        appendLine("[   12.345678] Unable to handle kernel NULL pointer dereference")
        appendLine("------ 0.013s was the duration of 'KERNEL LOG (dmesg)' ------")
        appendLine("more noise")
    }

    @Test
    fun `a bugreport keeps only the kernel evidence and the boot facts`() {
        val evidence = extract(
            zipRaw(
                "bugreport-test.txt" to mainText.toByteArray(),
                "FS/data/misc/recovery/last_kmsg" to "last kmsg bytes\n".toByteArray(),
                "FS/data/misc/recovery/last_kmsg.1" to "older kmsg\n".toByteArray(),
                "FS/proc/meminfo" to "MemTotal: 1 kB\n".toByteArray(),
            ),
        )
        assertNotNull(evidence)
        evidence!!
        assertEquals("bugreport", evidence.facts["source"])
        assertEquals("boot-test", evidence.captureBootId)
        assertEquals("bugreport-test.zip", evidence.bugreportName)
        assertEquals("kernel_panic", evidence.facts["ro.boot.bootreason"])
        assertEquals("kernel_panic", evidence.facts["sys.boot.reason"])
        assertTrue(evidence.files.containsKey("last_kmsg"))
        assertTrue(evidence.files.containsKey("last_kmsg.1"))
        assertFalse(evidence.files.containsKey("meminfo"))
        val kernel = evidence.text(CrashEvidenceStore.KernelLogName)
        assertTrue(kernel.contains("Booting Linux"))
        assertTrue(kernel.contains("Unable to handle kernel NULL pointer dereference"))
        assertFalse(kernel.contains("more noise"))
        assertEquals(
            "kernel_panic,1794794473\nreboot,shell,1794794355\nreboot,shell,1794794267",
            evidence.text(CrashEvidenceStore.HistoryName),
        )
        assertTrue(evidence.crashEvidence)
        assertTrue(evidence.files.containsKey(CrashEvidenceStore.BootFactsName))
        assertTrue(evidence.toInfo().crashEvidence)
    }

    @Test
    fun `a single-line boot history is still kept`() {
        val evidence = extract(
            zip("bugreport-test.txt" to buildString {
                appendLine("[ro.boot.bootreason]: [reboot]")
                appendLine("[persist.sys.boot.reason.history]: [reboot,shell,123]")
            }),
        )
        assertNotNull(evidence)
        assertEquals(
            "reboot,shell,123",
            evidence!!.text(CrashEvidenceStore.HistoryName),
        )
        assertFalse(evidence.crashEvidence)
    }

    @Test
    fun `a zip with nothing relevant leaves no evidence behind`() {
        assertNull(extract(zip("readme.txt" to "not a bugreport"), "not-a-bugreport.zip"))
    }

    @Test
    fun `a corrupt zip fails`() {
        assertNull(extract("not a zip".toByteArray(), "corrupt.zip"))
    }

    @Test
    fun `oops-named and last_kmsg files are kept`() {
        val evidence = extract(
            zipRaw(
                "bugreport-test.txt" to mainText.toByteArray(),
                "FS/mnt/rescue/recovery/last_kmsg" to "last kmsg\n".toByteArray(),
                "FS/vendor/console-ramoops-0" to "console oops\n".toByteArray(),
                "FS/vendor/kernel-oops.log" to "kernel oops text\n".toByteArray(),
                "FS/vendor/meminfo" to "MemTotal: 1 kB\n".toByteArray(),
            ),
        )
        assertNotNull(evidence)
        evidence!!
        assertTrue(evidence.files.containsKey("last_kmsg"))
        assertTrue(evidence.files.containsKey("console-ramoops-0"))
        assertTrue(evidence.files.containsKey("kernel-oops.log"))
        assertFalse(evidence.files.containsKey("meminfo"))
        assertEquals("yes", evidence.facts["crash_evidence"])
        assertTrue(evidence.crashEvidence)
    }

    @Test
    fun `nested stability zip keeps only the pure-text evidence`() {
        val nested = zipRaw(
            "temp/last_kmsg" to
                ("Kernel panic - not syncing: test\n" +
                    "Call trace:\n" +
                    " rt_mutex_adjust_prio_chain+0x188\n" +
                    "[  312638.352673] tail\n").toByteArray(),
            "temp/md_history" to "1) OS3.0.3.0|2026-11-14 16:24:22|kpanic|oops\n".toByteArray(),
            "log/mtdoops.md" to "## Index: 1\n### LOG TYPE: LAST KMSG\n".toByteArray(),
            "temp/last_logcat" to "11-14 16:39:21 I app: last logcat line\n".toByteArray(),
            "temp/minidump.gz" to ByteArray(4096) { 0x41 },
            "temp/last_tzlog_key" to "must not be kept".toByteArray(),
        )
        val evidence = extract(
            zipRaw(
                "bugreport-test.txt" to mainText.toByteArray(),
                "FS/data/miuilog/stability/reboot/-1_ke_abc123_unknown_stable_venus_OS2.0.6.0.UKBCNXM_1794794400_123456789.zip" to nested,
            ),
        )
        assertNotNull(evidence)
        evidence!!
        assertTrue(evidence.files.containsKey("stability-last_kmsg"))
        assertTrue(evidence.files.containsKey("md_history.txt"))
        assertTrue(evidence.files.containsKey("mtdoops.md"))
        assertTrue(evidence.files.containsKey("last_logcat.txt"))
        assertFalse(evidence.files.containsKey("minidump.gz"))
        assertFalse(evidence.files.containsKey("last_tzlog_key"))
        assertEquals("yes", evidence.facts["crash_evidence"])
        assertTrue(
            evidence.facts["origin.stability-last_kmsg"].orEmpty()
                .contains("1794794400_123456789.zip!temp/last_kmsg"),
        )
        assertEquals("1794794400", evidence.facts["nested.zip_epoch.stability-last_kmsg"])
        assertEquals("312638", evidence.facts["nested.last_uptime.stability-last_kmsg"])
        assertFalse(evidence.facts.containsKey("nested.stale.stability-last_kmsg"))
        assertTrue(evidence.text("stability-last_kmsg").contains("rt_mutex_adjust_prio_chain"))
        assertTrue(evidence.crashEvidence)
    }

    @Test
    fun `a stability zip outside the crashed boot window is stale`() {
        val nested = zipRaw(
            "temp/last_kmsg" to
                ("=== Dump occured at 2022-03-21 4:58:56 ===\n" +
                    "[  312638.352673] tail\n").toByteArray(),
        )
        val evidence = extract(
            zipRaw(
                "bugreport-test.txt" to mainText.toByteArray(),
                "FS/data/miuilog/stability/reboot/-1_ke_old_unknown_stable_venus_OS2.0.6.0.UKBCNXM_1794000000_987654321.zip" to nested,
            ),
        )
        assertNotNull(evidence)
        evidence!!
        assertFalse(evidence.files.containsKey("stability-last_kmsg"))
        // Only the main text's own crash excerpt remains, as the heuristic flag.
        assertEquals("heuristic", evidence.facts["crash_evidence"])
        assertEquals("1794000000", evidence.facts["nested.zip_epoch.stability-last_kmsg"])
        assertEquals("312638", evidence.facts["nested.last_uptime.stability-last_kmsg"])
        assertTrue(
            evidence.facts["nested.stale.stability-last_kmsg"].orEmpty()
                .contains("1794000000"),
        )
    }

    @Test
    fun `xiaomi reboot records stop at the next section`() {
        val text = buildString {
            append(mainText)
            appendLine("kernel poweroff   : ps_hold")
            appendLine("record time_stamp : 2026-11-14 16:37:06")
            appendLine("kernel reboot     : kpanic")
            appendLine("record time_stamp : 2026-11-14 16:36:59")
            appendLine("------------------------------------")
            appendLine("-------- Kernel reboot records --------")
            appendLine("record time      :2026-11-14 16:24:22")
            appendLine("kernel reboot    :Kernel Exception")
            appendLine("----------- dgt and det match ---------")
            appendLine("sum              : Unable to handle kernel NULL pointer dereference")
            appendLine("Call trace:")
            appendLine(" rt_mutex_adjust_prio_chain+0x188/0x1948")
            appendLine("------ SYSTEM LOG (logcat) ------")
            appendLine("not part of the block")
        }
        val evidence = extract(zip("bugreport-test.txt" to text))
        assertNotNull(evidence)
        evidence!!
        val block = evidence.text("reboot-records.txt")
        assertTrue(block.contains("kernel poweroff"))
        assertTrue(block.contains("Kernel reboot records"))
        assertTrue(block.contains("rt_mutex_adjust_prio_chain"))
        assertFalse(block.contains("not part of the block"))
        assertEquals("yes", evidence.facts["crash_evidence"])
    }

    @Test
    fun `vendor panic records keep their context`() {
        val text = buildString {
            append(mainText)
            appendLine("boot noise before the record")
            appendLine("11-17 00:20:32.027 D BootReceiver: record Panic Reboot result = " +
                "@,@cause@:@COMM=sh|FST_FRAME=trace_android_vh_sysrq_crash+0x0/0x90|" +
                "KEYINFO= Kernel panic - not syncing: sysrq triggered crash@,@stackhash@")
            appendLine("11-17 00:20:32.037 V DebugLogService: mModule = CriticalLogKernel " +
                "mIssue = panic")
            appendLine("record tail")
        }
        val evidence = extract(zip("bugreport-test.txt" to text))
        assertNotNull(evidence)
        evidence!!
        val panic = evidence.text("panic-record.txt")
        assertTrue(panic.contains("record Panic Reboot"))
        assertTrue(panic.contains("CriticalLogKernel"))
        assertTrue(panic.contains("boot noise before the record"))
        assertTrue(panic.contains("record tail"))
        assertEquals("yes", evidence.facts["crash_evidence"])
    }

    @Test
    fun `a report with only the section inventory is imported as no-crash`() {
        val evidence = extract(
            zip("bugreport-test.txt" to "------ TEST SECTION ------\nbody\n"),
        )
        assertNotNull(evidence)
        evidence!!
        assertTrue(evidence.files.containsKey("report-sections.txt"))
        assertEquals("no", evidence.facts["crash_evidence"])
        assertEquals("no crash evidence found in the report", evidence.facts["note"])
        assertFalse(evidence.crashEvidence)
    }

    @Test
    fun `a report whose only evidence is the reboot block is imported`() {
        val text = buildString {
            appendLine("kernel reboot     : kpanic")
            appendLine("-------- Kernel reboot records --------")
            appendLine("record time      :2026-11-14 16:24:22")
            appendLine("----------- dgt and det match ---------")
            appendLine("sum              : Unable to handle kernel NULL pointer dereference")
            appendLine("Call trace:")
            appendLine(" rt_mutex_adjust_prio_chain+0x188/0x1948")
        }
        val evidence = extract(zip("bugreport-test.txt" to text))
        assertNotNull(evidence)
        evidence!!
        assertTrue(evidence.files.containsKey("reboot-records.txt"))
        assertEquals("yes", evidence.facts["crash_evidence"])
        assertTrue(evidence.crashEvidence)
    }

    @Test
    fun `crlf and bare cr endings split like lf`() {
        val text = "[ro.boot.bootreason]: [kernel_panic]\r\n" +
            "------ KERNEL LOG (dmesg) ------\r\n" +
            "Unable to handle kernel NULL pointer dereference\r\n" +
            "Kernel panic - not syncing\r" +
            "------ TEST SECTION ------\r\n" +
            "------ 0.013s was the duration of 'KERNEL LOG (dmesg)' ------\r\n"
        val evidence = extract(zip("bugreport-test.txt" to text))
        assertNotNull(evidence)
        evidence!!
        assertEquals("kernel_panic", evidence.facts["ro.boot.bootreason"])
        val kernel = evidence.text(CrashEvidenceStore.KernelLogName)
        assertTrue(kernel.contains("Unable to handle kernel NULL pointer dereference"))
        assertTrue(kernel.contains("Kernel panic - not syncing"))
        assertTrue(evidence.text("crash-excerpts.txt").contains("Kernel panic - not syncing"))
        assertTrue(evidence.text("report-sections.txt").contains("------ TEST SECTION ------"))
        assertTrue(evidence.crashEvidence)
    }

    @Test
    fun `a long line crossing the reader buffer is scanned and kept`() {
        val padding = "x".repeat(200_000)
        val text = buildString {
            appendLine("------ KERNEL LOG (dmesg) ------")
            appendLine(padding)
            appendLine("Unable to handle kernel paging request")
            appendLine("------ 0.013s was the duration of 'KERNEL LOG (dmesg)' ------")
        }
        val evidence = extract(zip("bugreport-test.txt" to text))
        assertNotNull(evidence)
        evidence!!
        val kernel = evidence.text(CrashEvidenceStore.KernelLogName)
        assertTrue(kernel.contains(padding))
        val crash = evidence.text("crash-excerpts.txt")
        assertTrue(crash.contains(padding))
        assertTrue(crash.contains("Unable to handle kernel paging request"))
        assertTrue(evidence.crashEvidence)
    }

    @Test
    fun `mixed-case compound panic lines still match`() {
        val evidence = extract(
            zip("bugreport-test.txt" to buildString {
                appendLine("------ TEST SECTION ------")
                appendLine("Boot Reason : WATCHDOG")
            }),
        )
        assertNotNull(evidence)
        evidence!!
        assertTrue(evidence.files.containsKey("panic-record.txt"))
        assertTrue(evidence.text("panic-record.txt").contains("Boot Reason : WATCHDOG"))
        assertTrue(evidence.crashEvidence)
    }

    @Test
    fun `real reports from GHOSTLOCK_REPORT_DIR`() {
        val source = System.getenv("GHOSTLOCK_REPORT_DIR")
        assumeTrue("GHOSTLOCK_REPORT_DIR not set", source != null)
        val reports = File(source!!).listFiles { file -> file.isFile && file.extension == "zip" }
            .orEmpty()
            .sortedBy { it.name }
        assumeTrue("no report zips in GHOSTLOCK_REPORT_DIR", reports.isNotEmpty())
        val summary = System.getenv("GHOSTLOCK_REPORT_OUT")?.let(::File)
        summary?.writeText("")
        val dump = System.getenv("GHOSTLOCK_REPORT_DUMP_DIR")?.let(::File)
        for (file in reports) {
            val started = System.nanoTime()
            val evidence = file.inputStream().use {
                BugreportImporter.extract(it, file.name, "boot-test", 1_760_000_000_000L)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertNotNull("$file did not import", evidence)
            evidence!!
            if (dump != null) {
                val dir = File(dump, file.nameWithoutExtension).apply { mkdirs() }
                for ((entryName, entryBytes) in evidence.files) {
                    File(dir, entryName).writeBytes(entryBytes)
                }
            }
            val line = "${file.name} ok=true ms=$elapsedMs crash=${evidence.crashEvidence} " +
                "capture=${evidence.captureBootId} main=${evidence.facts["main_text"]} " +
                "files=${evidence.files.keys.sorted()}"
            summary?.appendText(line + "\n")
            println(line)
        }
    }
}
