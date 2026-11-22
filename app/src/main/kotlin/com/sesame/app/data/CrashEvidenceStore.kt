package com.sesame.app.data

import android.content.Context
import com.sesame.app.domain.model.CrashEvidenceInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash evidence captured beside the runs: the kernel console from the boot
 * before the capture boot, plus the facts that let a report say which boot
 * that was.
 *
 * The store deliberately makes no claim about which run the evidence belongs
 * to. A capture records the boot it was taken on and the boot history it saw;
 * the run logs record their own birth boot and their last write time, and the
 * analyst joins the two. The export bundle carries the raw facts.
 *
 * Names sort by the capture wall clock (`yyyyMMdd-HHmmss-<bootid8>`), so the
 * newest capture is the first entry and rotation is a name sort.
 */
internal object CrashEvidenceStore {
    /** Captures kept on the device; a reboot before export must not erase the crash. */
    const val Keep = 3

    const val BootFactsName = "boot.txt"
    const val HistoryName = "boot-reason-history.txt"
    const val KernelLogName = "kernel-log.txt"
    const val RawRootFactsName = "root-facts.txt"

    /**
     * The pstore names a capture probes. The directory is not listable on
     * Android, so the caller probes known names and records misses; both the
     * suffixed and unsuffixed spellings exist across kernels.
     */
    val PstoreNames = listOf(
        "console-ramoops-0",
        "console-ramoops",
        "pmsg-ramoops-0",
        "pmsg-ramoops",
        "dmesg-ramoops-0",
        "dmesg-ramoops-1",
        "dmesg-ramoops-2",
        "dmesg-ramoops-3",
    )

    fun root(context: Context): File =
        File(context.filesDir, RunLogStore.CrashDirectoryName)

    /** Newest first. Partial directories (no boot.txt yet) are skipped. */
    internal fun captures(root: File): List<CrashCapture> = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .mapNotNull(::read)
        .sortedByDescending { it.name }

    fun latest(context: Context): CrashCapture? = captures(root(context)).firstOrNull()

    /** Newest automatic capture; imported reports are session state now. */
    internal fun latestAutomatic(root: File): CrashCapture? =
        captures(root).firstOrNull { it.source != "bugreport" }

    /**
     * One-time migration: imported reports are no longer persisted. Their
     * extracted evidence is derived from the original zip, which remains in
     * the system's bugreports folder.
     */
    internal fun clearImported(root: File) {
        captures(root)
            .filter { it.source == "bugreport" }
            .forEach { runCatching { it.dir.deleteRecursively() } }
        rotate(root)
    }

    internal fun read(dir: File): CrashCapture? {
        if (!dir.isDirectory) return null
        val facts = readFacts(dir)
        val bootId = facts["capture_boot_id"].orEmpty()
        if (bootId.isEmpty()) return null
        val files = dir.listFiles().orEmpty()
            .filter { it.isFile }
            .map { it.name }
            .sorted()
        val pstore = files.filter { it in PstoreNames || it.startsWith("last_kmsg") }
        val kernelLog = File(dir, KernelLogName).isFile
        val crashEvidence = when (facts["crash_evidence"]) {
            "yes", "heuristic" -> true
            "no" -> false
            else -> pstore.isNotEmpty() || files.any(::isCrashEvidenceFileName)
        }
        return CrashCapture(
            dir = dir,
            name = dir.name,
            source = facts["source"].orEmpty(),
            captureBootId = bootId,
            capturedMs = facts["captured"]?.toLongOrNull() ?: dir.lastModified(),
            files = files,
            pstoreFiles = pstore,
            bootReason = facts["ro.boot.bootreason"].orEmpty(),
            sysBootReason = facts["sys.boot.reason"].orEmpty(),
            historyFile = File(dir, HistoryName).takeIf { it.isFile && it.length() > 0 },
            bugreportName = facts["bugreport_name"].orEmpty(),
            hasKernelLog = pstore.isNotEmpty() || kernelLog,
            crashEvidence = crashEvidence,
        )
    }

    private fun isCrashEvidenceFileName(name: String): Boolean =
        name == "stability-last_kmsg" ||
            name == "md_history.txt" ||
            name == "mtdoops.md" ||
            name == "reboot-records.txt" ||
            name == "panic-record.txt" ||
            name == "crash-excerpts.txt"

    /** The capture for [bootId], complete or bare, if one exists. */
    internal fun findForBoot(root: File, bootId: String): CrashCapture? =
        captures(root).firstOrNull { it.captureBootId == bootId }

    /** Creates a new capture directory; the caller fills it, then calls [complete]. */
    internal fun newDir(root: File, bootId: String, capturedMs: Long): File {
        root.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(capturedMs))
        val short = shortBootId(bootId)
        var dir = File(root, "$stamp-$short")
        var suffix = 1
        while (dir.exists()) {
            dir = File(root, "$stamp-$short-$suffix")
            suffix++
        }
        dir.mkdirs()
        rotate(root)
        return dir
    }

    /**
     * Writes the canonical `boot.txt` and the raw history, and deletes the
     * root shell's intermediate facts file. The boot.txt is the completion
     * marker: a directory without it is a capture that never finished and is
     * ignored by [captures].
     */
    internal fun complete(
        root: File,
        dir: File,
        facts: Map<String, String>,
        history: String?,
        capturedMs: Long,
    ): CrashCapture? {
        val merged = LinkedHashMap<String, String>()
        merged["source"] = facts["source"].orEmpty().ifEmpty { "unknown" }
        merged["capture_boot_id"] = facts["capture_boot_id"].orEmpty().ifEmpty { "unknown" }
        merged["captured"] = capturedMs.toString()
        for (key in listOf(
            "uptime_s",
            "ro.boot.bootreason",
            "sys.boot.reason",
            "sys.boot.reason.last",
            "bugreport_name",
            "bugreport_bytes",
        )) {
            facts[key]?.let { merged[key] = it }
        }
        for ((key, value) in facts) {
            if (!merged.containsKey(key) && !key.startsWith("history")) {
                merged[key] = value
            }
        }
        if (!history.isNullOrBlank()) {
            runCatching {
                File(dir, HistoryName).writeText(history, StandardCharsets.UTF_8)
            }
            merged["history_file"] = HistoryName
        }
        writeFacts(dir, merged)
        File(dir, RawRootFactsName).delete()
        rotate(root)
        return read(dir)
    }

    /** Writes key=value lines atomically; values are flattened to one line. */
    internal fun writeFacts(dir: File, facts: Map<String, String>) {
        val text = facts.entries.joinToString("\n", postfix = "\n") { (key, value) ->
            "$key=${value.replace('\n', ' ').replace('\r', ' ')}"
        }
        val target = File(dir, BootFactsName)
        val temp = File(dir, "$BootFactsName.new")
        FileOutputStream(temp, false).use { out ->
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    internal fun readFacts(dir: File): Map<String, String> = runCatching {
        File(dir, BootFactsName).takeIf { it.isFile }?.readLines().orEmpty()
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.take(at) to line.substring(at + 1)
            }
            .toMap()
    }.getOrDefault(emptyMap())

    internal fun rotate(root: File) {
        root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedByDescending { it.name }
            .drop(Keep)
            .forEach { runCatching { it.deleteRecursively() } }
    }

    internal fun clear(root: File) {
        runCatching { root.deleteRecursively() }
    }

    private fun shortBootId(bootId: String): String =
        bootId.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "unknown" }
}

/** One capture directory, parsed. */
internal data class CrashCapture(
    val dir: File,
    val name: String,
    val source: String,
    val captureBootId: String,
    val capturedMs: Long,
    val files: List<String>,
    val pstoreFiles: List<String>,
    val bootReason: String,
    val sysBootReason: String,
    val historyFile: File?,
    val bugreportName: String,
    val hasKernelLog: Boolean,
    val crashEvidence: Boolean,
) {
    fun toInfo(): CrashEvidenceInfo = CrashEvidenceInfo(
        source = source,
        captureBootId = captureBootId,
        capturedMs = capturedMs,
        files = files,
        bootReason = bootReason,
        bugreportName = bugreportName,
        crashEvidence = crashEvidence,
    )
}
