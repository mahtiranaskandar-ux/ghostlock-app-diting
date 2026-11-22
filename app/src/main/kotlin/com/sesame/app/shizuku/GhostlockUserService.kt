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
import com.sesame.app.data.NativeProfileDocument
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    }

    override fun runExploit(
        primaryCpu: Int,
        consumerCpu: Int,
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
                // PROFILE-SUGGEST-01: recommend_shizuku is a suggestion; the
                // blob's meta section carries it, and the app already chose the
                // Shizuku path, so it is logged, never a gate.
                if (NativeProfileDocument.fromBinary(profileBlob)?.recommendShizuku != 1u) {
                    callback.onLog("<s> kernel does not require Shizuku; running on user request")
                }

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
                // v2: safe_mode lives in the meta section; there is no fixed
                // slot offset, so the blob is rescanned and rewritten.
                val effectiveBlob = if (safeMode) {
                    NativeProfileDocument.patchSafeMode(profileBlob) ?: profileBlob
                } else {
                    profileBlob
                }
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
                        /* Length-prefixed GLK1; stdin stays open for the ACK. */
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
