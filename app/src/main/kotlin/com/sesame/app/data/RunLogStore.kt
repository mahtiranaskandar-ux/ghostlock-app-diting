package com.sesame.app.data

import android.content.Context
import android.net.Uri
import com.sesame.app.domain.model.ImportedEvidence
import com.sesame.app.domain.model.RunLogEntry
import com.sesame.app.domain.model.RunLogState
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Where a run's artifacts live: `files/runs/<stamp>/` — the log, the resolved
 * profile, the root script's kernel dumps, and a `meta` line saying how the run
 * ended.
 *
 * Why not the Downloads folder, which is where these used to go: shared storage
 * is reached through MediaStore/FUSE, so written bytes sit in that mount's page
 * cache until write-back. A failed run on this ROM ends with the device going
 * down hard, and a hard restart takes the page cache with it — leaving files
 * whose size was committed and whose content is NUL. A user's report arrived in
 * exactly that shape (three files, correct sizes, zero non-zero bytes), so the
 * one artifact that would have explained the failure was destroyed by the
 * failure. The app's own data directory is not FUSE-backed and its descriptors
 * are the app's to fsync, so a log written here survives the crash it is about.
 *
 * It is also why nothing is written to shared storage at run time any more:
 * every run used to leave a folder behind in Downloads (182 of them on the
 * development device) and nothing ever removed them.
 *
 * The logic takes a root directory rather than a `Context` so the rotation,
 * ordering and interrupted-marking rules are testable on the host; the
 * `Context` overloads are the only Android-facing part.
 */
internal object RunLogStore {
    /** Runs kept on the device. Older ones are deleted as new runs start. */
    const val Keep = 10

    const val LogName = "log"
    const val MetaName = "meta"

    /** The first line of every run log: which boot the run was born in. */
    const val BootTag = "[boot]"

    /** Crash-evidence captures live beside the runs, under this directory. */
    internal const val CrashDirectoryName = "last-crash"

    private const val DirName = "runs"
    private const val IndexName = "index.txt"

    fun root(context: Context): File = File(context.filesDir, DirName)

    /** Creates this run's directory and rotates the old ones out. */
    fun open(context: Context, entry: String): RunLogWriter =
        open(root(context), entry, stampNow())

    internal fun open(root: File, entry: String, stamp: String): RunLogWriter {
        val dir = File(root, stamp)
        dir.mkdirs()
        val writer = RunLogWriter(dir, stamp, entry)
        rotate(root)
        return writer
    }

    /** Newest first, with a run that never finished ahead of the rest. */
    fun list(context: Context): List<RunLogEntry> = list(root(context))

    internal fun list(root: File): List<RunLogEntry> = root.listFiles()
        .orEmpty()
        .filter { it.isDirectory }
        .map { dir ->
            val meta = readMeta(dir)
            RunLogEntry(
                stamp = dir.name,
                entry = meta.entry,
                state = meta.state,
                bytes = dir.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() },
            )
        }
        .sortedWith(
            compareByDescending<RunLogEntry> { it.state == RunLogState.INTERRUPTED }
                .thenByDescending { it.stamp },
        )

    /**
     * A run writes `running` when it opens and a terminal state when it ends, so
     * anything still `running` at the next app start was killed with the device.
     * Naming that fact is the point: on the report that prompted this, we could
     * only infer it from file sizes.
     */
    fun markInterrupted(context: Context): Int = markInterrupted(root(context))

    internal fun markInterrupted(root: File): Int = root.listFiles()
        .orEmpty()
        .count { dir ->
            if (!dir.isDirectory) return@count false
            val meta = readMeta(dir)
            meta.state == RunLogState.RUNNING &&
                writeMeta(dir, meta.entry, RunLogState.INTERRUPTED)
        }

    /** The stored log text of one retained run; null when it cannot be read. */
    fun readLog(context: Context, stamp: String): String? = runCatching {
        File(File(root(context), stamp), LogName).readText()
    }.getOrNull()

    /** Streams one run into [target] as `<stamp>/…`, the shape a report is read in. */
    fun exportZip(context: Context, stamp: String, target: Uri): Boolean = runCatching {
        val dir = File(root(context), stamp)
        if (!dir.isDirectory) return@runCatching false
        val out = context.contentResolver.openOutputStream(target, "w")
            ?: return@runCatching false
        zip(dir, out)
    }.getOrDefault(false)

    internal fun zip(dir: File, out: OutputStream): Boolean = runCatching {
        val files = dir.listFiles().orEmpty()
            .filter { it.isFile && it.name != MetaName }
            .sortedBy { it.name }
        if (files.isEmpty()) return@runCatching false
        var written = 0
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            written = zipDir(dir, dir.name, zip)
        }
        written > 0
    }.getOrDefault(false)

    /**
     * Everything the app has, in one report: every retained run plus the
     * crash-evidence captures. The root `index.txt` says which run was in
     * which boot and which capture came from which boot, so the analyst can
     * correlate the two without the app pretending to know more than it does.
     */
    fun exportBundle(
        context: Context,
        target: Uri,
        pendingImport: ImportedEvidence? = null,
    ): Boolean = runCatching {
        val out = context.contentResolver.openOutputStream(target, "w")
            ?: return@runCatching false
        bundleZip(
            root(context),
            File(context.filesDir, CrashDirectoryName),
            out,
            pendingImport,
        )
    }.getOrDefault(false)

    /** The bundle's suggested file name. */
    fun reportName(): String = "ghostlock-report-${stampNow()}.zip"

    internal fun bundleZip(
        runsRoot: File,
        crashRoot: File,
        out: OutputStream,
        pendingImport: ImportedEvidence? = null,
    ): Boolean = runCatching {
        val runDirs = runsRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedByDescending { it.name }
        val crashDirs = crashRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            /* Imported reports are session state now; old on-disk imports from
             * earlier builds must never silently ride along in a bundle. */
            .filter { CrashEvidenceStore.readFacts(it)["source"] != "bugreport" }
            .sortedByDescending { it.name }
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(
                ZipEntry(IndexName).apply { time = System.currentTimeMillis() },
            )
            zip.write(indexText(runDirs, crashDirs, pendingImport).toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            for (dir in runDirs) zipDir(dir, dir.name, zip)
            for (dir in crashDirs) {
                zipDir(dir, "$CrashDirectoryName/${dir.name}", zip)
            }
            if (pendingImport != null) {
                val prefix = "$CrashDirectoryName/${importDirName(pendingImport)}"
                for ((fileName, data) in pendingImport.files.toSortedMap()) {
                    zip.putNextEntry(
                        ZipEntry("$prefix/$fileName").apply { time = pendingImport.capturedMs },
                    )
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }
        true
    }.getOrDefault(false)

    /**
     * Copies one directory into [zip] under [prefix]. One unreadable file must
     * not cost the whole report: the root script's dumps land root-owned when
     * it runs (it chmods them, but a truncated dump or an older build may
     * not), and half a zip is still a report.
     *
     * The zip entry keeps the file's mtime: a run's log mtime is the only
     * "when did this die" fact that survives, and the index repeats it.
     */
    private fun zipDir(dir: File, prefix: String, zip: ZipOutputStream): Int {
        var written = 0
        for (file in dir.listFiles().orEmpty()
            .filter { it.isFile && it.name != MetaName }
            .sortedBy { it.name }) {
            val copied = runCatching {
                /* Open before declaring the entry: a file that cannot be read
                 * must be absent from the zip, not present and empty -- an
                 * empty kernel-dmesg.log reads as "no dump", which is a
                 * different claim from "the app could not read it". */
                file.inputStream().use { input ->
                    zip.putNextEntry(
                        ZipEntry("$prefix/${file.name}").apply { time = file.lastModified() },
                    )
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            }.isSuccess
            if (copied) written++
        }
        return written
    }

    private fun indexText(
        runDirs: List<File>,
        crashDirs: List<File>,
        pendingImport: ImportedEvidence?,
    ): String {
        val lines = mutableListOf(
            "ghostlock report ${stampNow()}",
            "runs:",
        )
        for (dir in runDirs) {
            val meta = readMeta(dir)
            val log = File(dir, LogName)
            val (bootId, start) = bootFields(log)
            lines += "  ${dir.name} entry=${meta.entry} state=${meta.state.token} " +
                "boot_id=$bootId start=$start log_mtime=${log.lastModified()}"
        }
        if (crashDirs.isNotEmpty() || pendingImport != null) {
            lines += "crash-evidence:"
            for (dir in crashDirs) {
                val facts = readFacts(dir)
                val files = dir.listFiles().orEmpty()
                    .filter { it.isFile }
                    .map { it.name }
                    .sorted()
                    .joinToString(",")
                lines += "  $CrashDirectoryName/${dir.name} " +
                    "source=${facts["source"].orEmpty()} " +
                    "capture_boot_id=${facts["capture_boot_id"].orEmpty()} " +
                    "captured=${facts["captured"].orEmpty()} files=$files"
            }
            if (pendingImport != null) {
                val files = pendingImport.files.keys.sorted().joinToString(",")
                lines += "  $CrashDirectoryName/${importDirName(pendingImport)} " +
                    "source=bugreport " +
                    "capture_boot_id=${pendingImport.captureBootId} " +
                    "captured=${pendingImport.capturedMs} files=$files"
            }
        }
        lines += "note: kernel evidence is from the boot before the capture boot; " +
            "correlate it with the run log mtimes and the boot history in " +
            "last-crash/*/boot.txt"
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun importDirName(imported: ImportedEvidence): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(imported.capturedMs)) + "-import"

    private fun bootFields(log: File): Pair<String, String> {
        val line = runCatching { log.bufferedReader().use { it.readLine() } }
            .getOrNull()
            .orEmpty()
        if (!line.startsWith(BootTag)) return "unknown" to "unknown"
        val id = Regex("""id=(\S+)""").find(line)?.groupValues?.get(1) ?: "unknown"
        val start = Regex("""start=(\d+)""").find(line)?.groupValues?.get(1) ?: "unknown"
        return id to start
    }

    private fun readFacts(dir: File): Map<String, String> = runCatching {
        File(dir, "boot.txt").takeIf { it.isFile }?.readLines().orEmpty()
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.take(at) to line.substring(at + 1)
            }
            .toMap()
    }.getOrDefault(emptyMap())

    fun clear(context: Context) {
        runCatching { root(context).deleteRecursively() }
    }

    private fun rotate(root: File) {
        val dirs = root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedByDescending { it.name }
        for (dir in dirs.drop(Keep)) {
            runCatching { dir.deleteRecursively() }
        }
    }

    private fun stampNow(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    internal fun writeMeta(dir: File, entry: String, state: RunLogState): Boolean = runCatching {
        /* Non-state lines (the measured overlay's provenance) survive every
         * state write, so a finished run still says what it ran with. */
        val extras = File(dir, MetaName).takeIf { it.isFile }?.readLines().orEmpty()
            .filterNot { it.startsWith("entry=") || it.startsWith("state=") }
        val text = buildString {
            append("entry=$entry\nstate=${state.token}\n")
            extras.forEach { append("$it\n") }
        }
        FileOutputStream(File(dir, MetaName), false).use { out ->
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            out.fd.syncQuietly()
        }
        true
    }.getOrDefault(false)

    /**
     * Adds or replaces one non-state meta line (the measured overlay's
     * provenance) without disturbing `entry=`/`state=`. [line] is `key=value`.
     */
    internal fun appendMeta(dir: File, line: String): Boolean = runCatching {
        val values = File(dir, MetaName).takeIf { it.isFile }?.readLines().orEmpty()
        val entry = values.firstOrNull { it.startsWith("entry=") } ?: "entry=run"
        val state = values.firstOrNull { it.startsWith("state=") } ?: "state=running"
        val key = line.substringBefore('=')
        val extras = values.filterNot {
            it.startsWith("entry=") || it.startsWith("state=") || it.startsWith("$key=")
        } + line
        val text = buildString {
            append("$entry\n$state\n")
            extras.forEach { append("$it\n") }
        }
        FileOutputStream(File(dir, MetaName), false).use { out ->
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            out.fd.syncQuietly()
        }
        true
    }.getOrDefault(false)

    /**
     * An unreadable or unknown state reads as [RunLogState.INTERRUPTED]: a run
     * whose own record did not survive is not one to call finished.
     */
    internal fun readMeta(dir: File): Meta = runCatching {
        val values = File(dir, MetaName).readLines().mapNotNull { line ->
            val at = line.indexOf('=')
            if (at <= 0) null else line.take(at) to line.substring(at + 1)
        }.toMap()
        Meta(
            entry = values["entry"].orEmpty().ifEmpty { "run" },
            state = RunLogState.fromToken(values["state"].orEmpty()),
            overlay = values["overlay"],
        )
    }.getOrDefault(Meta("run", RunLogState.INTERRUPTED))

    internal data class Meta(
        val entry: String,
        val state: RunLogState,
        /** The measured overlay's provenance (`path:document->effective(source)`). */
        val overlay: String? = null,
    )
}

/**
 * One run's writer. Owns the log descriptor so it can fsync, and records how the
 * run ended so the next start can tell a finished run from a killed one.
 */
internal class RunLogWriter(
    val folderFile: File,
    val stamp: String,
    private val entry: String,
) {
    private val log = FileOutputStream(File(folderFile, RunLogStore.LogName), false)
    private val pacer = SyncPacer { log.fd.syncQuietly() }

    init {
        RunLogStore.writeMeta(folderFile, entry, RunLogState.RUNNING)
        /* The first thing this run writes, and the only line that says which
         * boot it belongs to: a later crash report's `last-crash` evidence is
         * from the boot *before* the capture boot, and this line is how the
         * analyst places the run against the boot history. Synced immediately:
         * a run can die before the pacer's first tick. */
        append(bootLine())
        pacer.force()
    }

    private fun bootLine(): String {
        val id = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrNull()
            .orEmpty()
            .ifEmpty { "unknown" }
        return "${RunLogStore.BootTag} id=$id start=${System.currentTimeMillis()}"
    }

    /** Must be called with the owning [DebugAttackLog]'s lock held. */
    fun append(line: String) {
        log.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
        log.flush()
        pacer.maybe()
    }

    /** Must be called with the owning [DebugAttackLog]'s lock held. */
    fun writeSidecar(name: String, bytes: ByteArray) {
        FileOutputStream(File(folderFile, name), false).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.syncQuietly()
        }
    }

    fun finish(state: RunLogState) {
        RunLogStore.writeMeta(folderFile, entry, state)
    }

    fun close() {
        pacer.force()
        runCatching { log.close() }
    }
}

/**
 * fsync that cannot fail a caller whose write already succeeded: a stream that
 * will not sync is a reason to report it, not to lose the line.
 */
internal fun FileDescriptor.syncQuietly() {
    runCatching { sync() }
}

/**
 * fsync, but not on every line: a stage can log every few milliseconds, and an
 * fsync per line would put a synchronous disk round trip inside the run's own
 * logging path. Coalesced to [IntervalNs], which bounds what a hard reset can
 * cost to the last fraction of a second of lines. 0.5 s was measured against
 * 0.1 s on 2026-10-10: the tighter interval appeared to cost resets (3 dead in
 * 5 runs against 2 in 12) while buying about half a second of tail, and even at
 * 0.5 s a one-second death kept its log (4,855 bytes).
 */
internal class SyncPacer(private val sync: () -> Unit) {
    private var lastNs = System.nanoTime()

    fun maybe() {
        val now = System.nanoTime()
        if (now - lastNs < IntervalNs) return
        force()
        lastNs = now
    }

    fun force() {
        runCatching { sync() }
    }

    companion object {
        /* The 0.5 s cadence was the 2026-10-10 default. This test build pins
         * the fsyncing tailer off the route's cores and tightens it to 0.1 s:
         * the tail lost to a reset is smaller, and the flush no longer runs on
         * the cores the stamp depends on. */
        const val IntervalNs = 100_000_000L
    }
}
