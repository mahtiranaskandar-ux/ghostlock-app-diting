package com.sesame.app.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import com.sesame.app.data.BugreportzOutput
import com.sesame.app.data.CrashEvidenceStore
import com.sesame.app.data.DropboxKmsg
import com.sesame.app.data.ManagerScanner
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Keep
class GhostlockUserService(private val context: Context) : IGhostlockUserService.Stub() {
    private val running = AtomicBoolean(false)

    private companion object {
        const val StatusMarker = "\u001eGLK_STATUS"
        const val StatusAck = "\u001eGLK_STATUS_ACK\n"
        const val StatusDisabled = "\u001eGLK_STATUS_DISABLED"

        /* A console-ramoops entry is a few hundred KiB; 4 MiB is far above
         * anything BootReceiver stores and still bounded. The listing read is
         * capped separately: DropBox itself keeps at most 1000 entries. */
        const val MAX_DROPBOX_BYTES = 4L * 1024 * 1024
        const val MAX_DROPBOX_LISTING_BYTES = 2L * 1024 * 1024
        /* A full bugreport takes ~2 minutes on the rigs; 10 is the ceiling
         * before the subprocess is killed and the app gets an empty stream. */
        const val REPORT_TIMEOUT_MINUTES = 10L
        /* A timer attempt is tens of seconds; a hit parks. The watchdog only
         * bounds a wedged run, it never bounds a legitimate park (the park
         * has already produced its marker by then). */
        const val TIMER_TIMEOUT_MS = 10L * 60 * 1000
    }

    override fun runExploit(
        safeMode: Boolean,
        forceAttack: Boolean,
        anchorTrusted: Boolean,
        profileBlob: ByteArray,
        debugDir: String?,
        managerPackage: String?,
        callback: IGhostlockCallback,
        statusCallback: IGhostlockStatusCallback,
    ) {
        if (!running.compareAndSet(false, true)) {
            callback.onLog("<s> error: another GhostLock process is already running")
            callback.onComplete(1)
            return
        }
        Thread({
            val exitCode = runCatching {
                require(Process.myUid() == Process.SHELL_UID) {
                    "Shizuku UserService uid=${Process.myUid()}, expected ${Process.SHELL_UID}"
                }
                val status = File("/proc/self/status").readText()
                require(Regex("(?m)^Seccomp:\\s*0$").containsMatchIn(status)) {
                    "Shizuku UserService is still seccomp-filtered"
                }
                val release = System.getProperty("os.version", "").orEmpty()
                require(profileBlob.size >= 16) { "profile blob is too short" }

                val binary = File(context.applicationInfo.nativeLibraryDir, "libghostlock.so")
                require(binary.isFile) { "missing GhostLock binary: ${binary.absolutePath}" }

                val workDir = File("/data/local/tmp/ghostlock-app").apply {
                    require(isDirectory || mkdirs()) { "cannot create $absolutePath" }
                }
                val candidates = ManagerScanner.scan(context)
                val suitable = candidates.count { it.suitable }
                val chosen = ManagerScanner.resolve(candidates, managerPackage)
                    ?: error("no suitable ksud found; refusing to start the exploit")
                val ksud = File(workDir, "ksud")
                ksud.delete()
                File(chosen.path).inputStream().use { input ->
                    ksud.outputStream().use { input.copyTo(it) }
                }
                runCatching { Os.chmod(ksud.absolutePath, 448) }
                callback.onLog("<s> ksud scan: ${candidates.size} candidate(s), $suitable suitable")
                callback.onLog("<s> ksud selected: ${chosen.packageName} ${chosen.path} [${chosen.verdict}]")
                callback.onLog("<s> Shizuku ready: uid=${Process.myUid()} Seccomp=0")
                callback.onLog("<s> kernel: $release")
                val nativeLog = File(workDir, ".ghostlock_native.log")
                // U01-S14: per-run KernelSU log so a previous run's markers can
                // never satisfy the handoff probe; the native process receives
                // the resolved path via GHOSTLOCK_KSU_LOG.
                val ksuLog = File(workDir, "ghostlock-ksu-${System.currentTimeMillis()}.log")
                /* The document already states the run's safe_mode; the
                 * service forwards the bytes unchanged. */
                val effectiveBlob = profileBlob
                val argv = mutableListOf(
                    binary.absolutePath,
                    "--ghostlock-app-call",
                    "--enable-status-record",
                )
                if (forceAttack) {
                    argv += "--force-attack"
                }
                if (!debugDir.isNullOrEmpty()) {
                    argv += listOf("--dump-kernel-log", debugDir)
                }
                callback.onLog("<b> starting native: ${binary.absolutePath}")
                ProcessBuilder(argv)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .redirectOutput(nativeLog)
                    .apply {
                        environment()["GHOSTLOCK_HOME"] = workDir.absolutePath
                        environment()["TMPDIR"] = workDir.absolutePath
                        environment()["HOME"] = workDir.absolutePath
                        environment()["GHOSTLOCK_KSU_LOG"] = ksuLog.absolutePath
                        /* Computed by the app (this service cannot read the
                         * package's install time); see InstallClock. */
                        if (anchorTrusted) {
                            environment()["GHOSTLOCK_ANCHOR_TRUSTED"] = "1"
                        }
                    }
                    .start()
                    .let { process ->
                        val stdinOut = process.outputStream
                        /* Length-prefixed document; stdin stays open for the ACK. */
                        runCatching {
                            val length = effectiveBlob.size
                            stdinOut.write(
                                byteArrayOf(
                                    (length ushr 24).toByte(),
                                    (length ushr 16).toByte(),
                                    (length ushr 8).toByte(),
                                    length.toByte(),
                                ),
                            )
                            stdinOut.write(effectiveBlob)
                            stdinOut.flush()
                        }
                        // The native process writes its log to a file and this
                        // tailer forwards lines asynchronously. Reading a pipe
                        // here applied backpressure inside the PI race window
                        // (every line also costs a binder round trip), which
                        // stalled the route and ended in a kernel panic.
                        val tailer = Thread({
                            relayLog(nativeLog, callback, statusCallback, stdinOut)
                        }, "ghostlock-shizuku-tailer").apply {
                            isDaemon = true
                            start()
                        }
                        val exitCode = process.waitFor()
                        callback.onLog("<b> native exited code=$exitCode")
                        Thread.sleep(200)
                        tailer.interrupt()
                        tailer.join(1000)
                        exitCode
                    }
            }.getOrElse { error ->
                runCatching { callback.onLog("<s> error: ${error.message}") }
                1
            }
            running.set(false)
            runCatching { callback.onComplete(exitCode) }
        }, "ghostlock-shizuku-runner").start()
    }

    /**
     * The timer route (CVE-2026-98260). The tool is a standalone static
     * executable shipped as `libtimer.so`; it is copied out, given the
     * canonical profile document, and run as the shell. The endgame is
     * reported from log markers because a hit and several misses park
     * forever: the parked parent holds the fake cred page (or the failed
     * plants' zombies), and the process must outlive this service call.
     */
    override fun runTimer(
        profileJson: String,
        mode: String,
        managerPackage: String?,
        callback: IGhostlockCallback,
    ) {
        if (!running.compareAndSet(false, true)) {
            callback.onLog("<s> error: another GhostLock process is already running")
            callback.onComplete(1)
            return
        }
        Thread({
            val outcome = runCatching { runTimerBlocking(profileJson, mode, managerPackage, callback) }
                .getOrElse { error ->
                    runCatching { callback.onLog("<s> error: ${error.message}") }
                    1
                }
            running.set(false)
            runCatching { callback.onComplete(outcome) }
        }, "ghostlock-shizuku-timer").start()
    }

    private fun runTimerBlocking(
        profileJson: String,
        mode: String,
        managerPackage: String?,
        callback: IGhostlockCallback,
    ): Int {
        require(Process.myUid() == Process.SHELL_UID) {
            "Shizuku UserService uid=${Process.myUid()}, expected ${Process.SHELL_UID}"
        }
        val status = File("/proc/self/status").readText()
        require(Regex("(?m)^Seccomp:\\s*0$").containsMatchIn(status)) {
            "Shizuku UserService is still seccomp-filtered"
        }
        require(profileJson.isNotBlank()) { "timer profile document is empty" }
        require(mode == "probe" || mode == "fake" || mode == "zero") {
            "unknown timer mode: $mode"
        }
        val binary = File(context.applicationInfo.nativeLibraryDir, "libtimer.so")
        require(binary.isFile) { "missing timer binary: ${binary.absolutePath}" }
        val workDir = File("/data/local/tmp/ghostlock-app").apply {
            require(isDirectory || mkdirs()) { "cannot create $absolutePath" }
        }
        val timer = File(workDir, "timer")
        timer.delete()
        binary.inputStream().use { input -> timer.outputStream().use { input.copyTo(it) } }
        runCatching { Os.chmod(timer.absolutePath, 448) }
        val profileFile = File(workDir, "timer-profile.json")
        profileFile.writeBytes(profileJson.toByteArray(StandardCharsets.UTF_8))
        runCatching { Os.chmod(profileFile.absolutePath, 384) }
        callback.onLog("<s> Shizuku ready: uid=${Process.myUid()} Seccomp=0")
        callback.onLog(
            "<s> timer: ${timer.absolutePath} mode=$mode document=${profileJson.length}B",
        )
        /* probe reads and validates the document; it never reaches the root
         * handoff, so a missing manager must not block the delivery gate. */
        if (mode != "probe") {
            val candidates = ManagerScanner.scan(context)
            val suitable = candidates.count { it.suitable }
            val chosen = ManagerScanner.resolve(candidates, managerPackage)
                ?: error("no suitable ksud found; refusing to start the timer route")
            val bytes = File(chosen.path).readBytes()
            /* The tool's own handoff script probes /data/local/tmp/ksud when
             * the installed manager's libksud.so has no late-load (e.g.
             * com.sukisu.ultra); stage the app's choice there and in the work
             * directory. */
            for (target in listOf(File(workDir, "ksud"), File("/data/local/tmp/ksud"))) {
                target.writeBytes(bytes)
                runCatching { Os.chmod(target.absolutePath, 448) }
            }
            callback.onLog("<s> ksud scan: ${candidates.size} candidate(s), $suitable suitable")
            callback.onLog("<s> ksud selected: ${chosen.packageName} ${chosen.path} [${chosen.verdict}]")
        }
        val logFile = File(workDir, "timer-${System.currentTimeMillis()}.log")
        val argv = listOf(timer.absolutePath, "--profile", profileFile.absolutePath, mode)
        callback.onLog("<b> starting timer: ${argv.joinToString(" ")}")
        val process = ProcessBuilder(argv)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .apply {
                environment()["HOME"] = workDir.absolutePath
                environment()["TMPDIR"] = workDir.absolutePath
            }
            .start()
        val verdict = AtomicInteger(Int.MIN_VALUE)
        val logOffset = java.util.concurrent.atomic.AtomicLong(0)
        val onLine: (String) -> Unit = { line ->
            TimerVerdict.of(line)?.let { verdict.compareAndSet(Int.MIN_VALUE, it) }
        }
        val tailer = Thread({
            tailTimerLog(logFile, callback, logOffset, onLine)
        }, "ghostlock-shizuku-timer-tailer").apply {
            isDaemon = true
            start()
        }
        val deadline = System.currentTimeMillis() + TIMER_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && verdict.get() == Int.MIN_VALUE) {
            if (!process.isAlive) {
                verdict.compareAndSet(Int.MIN_VALUE, process.exitValue())
                break
            }
            Thread.sleep(100)
        }
        tailer.interrupt()
        tailer.join(1000)
        /* The tool's last lines can land after the tailer's final poll (a
         * probe exits in ~100 ms); drain them before reading the verdict so
         * the log and the marker are never lost. */
        if (!tailer.isAlive) drainTimerLog(logFile, callback, logOffset, onLine)
        val outcome = verdict.get()
        if (outcome == Int.MIN_VALUE) {
            callback.onLog("<s> error: timer produced no verdict in ${TIMER_TIMEOUT_MS / 1000}s")
            return TimerVerdict.NoVerdict
        }
        /* A parked process is left running on purpose: it is what keeps the
         * fake cred page (or the failed-plant zombies) alive until reboot. */
        callback.onLog("<b> timer verdict=$outcome (a parked tool is left running)")
        return outcome
    }

    /** Forwards the growing timer log file; the tool is never backpressured. */
    private fun tailTimerLog(
        logFile: File,
        callback: IGhostlockCallback,
        offset: java.util.concurrent.atomic.AtomicLong,
        onLine: (String) -> Unit,
    ) {
        while (!Thread.currentThread().isInterrupted) {
            TimerLogTail.read(logFile, offset, includeTrailingPartial = false) { line ->
                onLine(line)
                runCatching { callback.onLog(line) }
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** One final read after the tailer stopped; includes a trailing partial line. */
    private fun drainTimerLog(
        logFile: File,
        callback: IGhostlockCallback,
        offset: java.util.concurrent.atomic.AtomicLong,
        onLine: (String) -> Unit,
    ) {
        TimerLogTail.read(logFile, offset, includeTrailingPartial = true) { line ->
            onLine(line)
            runCatching { callback.onLog(line) }
        }
    }

    /** Forward complete native log lines without ever blocking the native
     * process; the file is the transport, binder is only the display path. */
    /** Forwards status events to the app (persist) and ACKs the native process;
     *  returns true when the line was a status event. */
    private fun handleStatusLine(
        line: String,
        statusCallback: IGhostlockStatusCallback,
        stdinOut: java.io.OutputStream,
    ): Boolean {
        if (!line.startsWith(StatusMarker)) return false
        if (line.contains(StatusDisabled)) {
            runCatching { statusCallback.onStatus("", "disabled") }
            return true
        }
        val parts = line.removePrefix(StatusMarker).trim().split(' ')
        if (parts.size >= 2) {
            runCatching { statusCallback.onStatus(parts[0], parts[1]) }
            runCatching {
                stdinOut.write(StatusAck.toByteArray(Charsets.UTF_8))
                stdinOut.flush()
            }
        }
        return true
    }

    private fun relayLog(
        logFile: File,
        callback: IGhostlockCallback,
        statusCallback: IGhostlockStatusCallback,
        stdinOut: java.io.OutputStream,
    ) {
        var offset = 0L
        val pending = StringBuilder()
        while (!Thread.currentThread().isInterrupted) {
            try {
                if (logFile.isFile) {
                    RandomAccessFile(logFile, "r").use { handle ->
                        if (offset > handle.length()) {
                            offset = 0
                            pending.clear()
                        }
                        handle.seek(offset)
                        while (true) {
                            val byte = handle.read()
                            if (byte == -1) break
                            if (byte == '\n'.code) {
                                val line = pending.toString()
                                pending.clear()
                                offset = handle.filePointer
                                if (line.isNotEmpty()) {
                                    if (!handleStatusLine(line, statusCallback, stdinOut)) {
                                        runCatching { callback.onLog(line) }
                                    }
                                }
                            } else {
                                pending.append(byte.toChar())
                            }
                        }
                    }
                }
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (_: Exception) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /**
     * The boot facts a report needs, read as shell: the app itself is denied
     * every one of these properties and the pstore directory. The history
     * value contains newlines by design; the caller stores it verbatim.
     */
    override fun readBootFacts(): Array<String> {
        require(Process.myUid() == Process.SHELL_UID || Process.myUid() == 0) {
            "crash evidence must be read as shell or root, uid=${Process.myUid()}"
        }
        fun prop(key: String): String = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, key) as? String ?: ""
        }.getOrDefault("")
        val facts = mutableListOf<String>()
        facts += "capture_boot_id=" + runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        }.getOrDefault("")
        facts += "uptime_s=" + runCatching {
            File("/proc/uptime").readText().trim().substringBefore(' ')
        }.getOrDefault("")
        facts += "ro.boot.bootreason=" + prop("ro.boot.bootreason")
        facts += "sys.boot.reason=" + prop("sys.boot.reason")
        facts += "sys.boot.reason.last=" + prop("sys.boot.reason.last")
        facts += "history=" + prop("persist.sys.boot.reason.history")
        /* The directory is 0700 root on Android, so the app can neither list it
         * nor tell an empty pstore from a denied one. stat() as shell/root can:
         * "denied" means the node may exist but this reader may not look, and
         * "exists:<size>" is what the analyst needs to decide whether a real
         * pstore capture is even possible on this ROM. */
        for (name in CrashEvidenceStore.PstoreNames) {
            facts += "pstore.$name=" + pstoreFact(name)
        }
        return facts.toTypedArray()
    }

    private fun pstoreFact(name: String): String = try {
        "exists:" + Os.stat("/sys/fs/pstore/$name").st_size
    } catch (e: ErrnoException) {
        when (e.errno) {
            OsConstants.EACCES, OsConstants.EPERM -> "denied"
            OsConstants.ENOENT, OsConstants.ENOTDIR -> "missing"
            else -> "error:" + e.errno
        }
    } catch (_: Throwable) {
        "error"
    }

    /** Opens one of the allowlisted pstore nodes for the app to stream. */
    override fun openPstore(name: String): ParcelFileDescriptor? {
        if (name !in CrashEvidenceStore.PstoreNames) return null
        return runCatching {
            ParcelFileDescriptor.open(
                File("/sys/fs/pstore/$name"),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
        }.getOrNull()
    }

    /**
     * The previous boot's kernel console via DropBox (see [DropboxKmsg]). Shell
     * may read DropBox; it may not read pstore. Returns -1 when the tag is
     * absent, otherwise the bytes written into `out` (0 for an empty entry).
     */
    override fun writeDropboxLastKmsg(out: ParcelFileDescriptor): Long {
        require(Process.myUid() == Process.SHELL_UID || Process.myUid() == 0) {
            "crash evidence must be read as shell or root, uid=${Process.myUid()}"
        }
        return runCatching {
            if (!dropboxHasLastKmsg()) return@runCatching -1L
            val process = ProcessBuilder("/system/bin/dumpsys", "dropbox", "--print", DropboxKmsg.Tag)
                .redirectErrorStream(true)
                .start()
            var total = 0L
            ParcelFileDescriptor.AutoCloseOutputStream(out).use { sink ->
                process.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (total < MAX_DROPBOX_BYTES) {
                        val want = minOf(buffer.size.toLong(), MAX_DROPBOX_BYTES - total).toInt()
                        val read = input.read(buffer, 0, want)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        total += read
                    }
                }
                sink.flush()
            }
            process.waitFor(10, TimeUnit.SECONDS)
            process.destroy()
            total
        }.getOrDefault(0L)
    }

    private fun dropboxHasLastKmsg(): Boolean = runCatching {
        val process = ProcessBuilder("/system/bin/dumpsys", "dropbox")
            .redirectErrorStream(true)
            .start()
        val text = StringBuilder()
        process.inputStream.use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total < MAX_DROPBOX_LISTING_BYTES) {
                val read = input.read(buffer)
                if (read <= 0) break
                text.append(String(buffer, 0, read, Charsets.UTF_8))
                total += read
            }
        }
        process.waitFor(10, TimeUnit.SECONDS)
        process.destroy()
        DropboxKmsg.isPresent(text.toString())
    }.getOrDefault(false)

    /**
     * Generates a fresh bug report with `bugreportz` and streams the zip into
     * `sink`. Returns immediately; the generation runs on a thread and the sink
     * is closed at the end (EOF for the app), so a report that cannot be made
     * is an empty stream, never a hang.
     */
    override fun startBugreport(sink: ParcelFileDescriptor) {
        require(Process.myUid() == Process.SHELL_UID || Process.myUid() == 0) {
            "the bug report must be generated as shell or root, uid=${Process.myUid()}"
        }
        Thread({
            var report: File? = null
            var streamed = false
            try {
                val process = ProcessBuilder("/system/bin/bugreportz", "-p")
                    .redirectErrorStream(true)
                    .start()
                val output = StringBuilder()
                var ok = false
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        output.append(line).append('\n')
                        if (line.startsWith("FAIL:")) break
                        if (line.startsWith("OK:")) {
                            ok = true
                            break
                        }
                    }
                }
                if (!process.waitFor(REPORT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    process.destroyForcibly()
                }
                if (!ok) return@Thread
                val path = BugreportzOutput.parsePath(output.toString()) ?: return@Thread
                val file = File(path)
                /* Only the file bugreportz itself just printed is ever deleted,
                 * and only from its own output directory. */
                if (!file.isFile || !file.path.contains("/bugreports/")) return@Thread
                report = file
                file.inputStream().use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
                        input.copyTo(out, 64 * 1024)
                        out.flush()
                    }
                }
                streamed = true
            } catch (_: Throwable) {
                /* The sink close in finally is the app's end-of-stream signal. */
            } finally {
                if (!streamed) runCatching { sink.close() }
                report?.let { runCatching { it.delete() } }
            }
        }, "gl-bugreport").start()
    }

    override fun destroy() {
        if (!running.get()) kotlin.system.exitProcess(0)
    }
}
