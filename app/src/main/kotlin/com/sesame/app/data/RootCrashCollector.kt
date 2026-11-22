package com.sesame.app.data

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The no-Shizuku half of the automatic capture: a small root script reads the
 * same boot facts and pstore nodes the shell service would. It is only tried
 * when Shizuku is unavailable, and only on a device that actually grants the
 * app root (the diting rig is the one device we have).
 *
 * The script lives in the app's cache dir and writes its output into the
 * capture dir; the app reads the files afterwards, so the only thing that
 * crosses `su` is a path.
 */
internal object RootCrashCollector {
    private const val TimeoutSeconds = 15L
    private const val ScriptName = "ghostlock-crash-capture.sh"

    private val SuCandidates = listOf(
        "su",
        "/system/bin/su",
        "/debug_ramdisk/su",
        "/sbin/su",
        "/data/adb/ksu/bin/su",
    )

    private val Script = """
        D="${'$'}1"
        mkdir -p "${'$'}D" || exit 1
        echo "source=root" > "${'$'}D/root-facts.txt"
        echo "capture_boot_id=${'$'}(cat /proc/sys/kernel/random/boot_id 2>/dev/null)" >> "${'$'}D/root-facts.txt"
        echo "uptime_s=${'$'}(cut -d' ' -f1 /proc/uptime 2>/dev/null)" >> "${'$'}D/root-facts.txt"
        echo "ro.boot.bootreason=${'$'}(getprop ro.boot.bootreason 2>/dev/null)" >> "${'$'}D/root-facts.txt"
        echo "sys.boot.reason=${'$'}(getprop sys.boot.reason 2>/dev/null)" >> "${'$'}D/root-facts.txt"
        echo "sys.boot.reason.last=${'$'}(getprop sys.boot.reason.last 2>/dev/null)" >> "${'$'}D/root-facts.txt"
        getprop persist.sys.boot.reason.history 2>/dev/null > "${'$'}D/boot-reason-history.txt"
        for f in console-ramoops console-ramoops-0 pmsg-ramoops pmsg-ramoops-0 dmesg-ramoops-0 dmesg-ramoops-1 dmesg-ramoops-2 dmesg-ramoops-3; do
          if [ -f "/sys/fs/pstore/${'$'}f" ]; then
            if cp "/sys/fs/pstore/${'$'}f" "${'$'}D/${'$'}f" 2>/dev/null; then
              echo "pstore.${'$'}f=ok" >> "${'$'}D/root-facts.txt"
            else
              echo "pstore.${'$'}f=failed" >> "${'$'}D/root-facts.txt"
            fi
          else
            echo "pstore.${'$'}f=missing" >> "${'$'}D/root-facts.txt"
          fi
        done
        ls -l /sys/fs/pstore > "${'$'}D/pstore-listing.txt" 2>&1
        # The kernel console as system_server saved it in DropBox; the same
        # channel the Shizuku service reads, and what travels when pstore
        # itself never captured the oops.
        if dumpsys dropbox 2>/dev/null | command grep -q "SYSTEM_LAST_KMSG"; then
          if timeout 10 dumpsys dropbox --print SYSTEM_LAST_KMSG > "${'$'}D/kernel-log.txt" 2>/dev/null && [ -s "${'$'}D/kernel-log.txt" ]; then
            echo "dropbox.last_kmsg=ok:${'$'}(wc -c < "${'$'}D/kernel-log.txt")" >> "${'$'}D/root-facts.txt"
          else
            rm -f "${'$'}D/kernel-log.txt"
            echo "dropbox.last_kmsg=failed" >> "${'$'}D/root-facts.txt"
          fi
        else
          echo "dropbox.last_kmsg=absent" >> "${'$'}D/root-facts.txt"
        fi
        chmod 644 "${'$'}D"/* 2>/dev/null
        exit 0
    """.trimIndent()

    fun collect(context: Context, dir: File): MutableMap<String, String>? {
        val script = File(context.cacheDir, ScriptName)
        val command = "sh ${quote(script.absolutePath)} ${quote(dir.absolutePath)}"
        val process = runCatching {
            script.writeText(Script + "\n")
            startSu(command)
        }.getOrNull() ?: return null
        try {
            runCatching { process.outputStream.close() }
            if (!process.waitFor(TimeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            val factsFile = File(dir, CrashEvidenceStore.RawRootFactsName)
            if (!factsFile.isFile) return null
            val facts = LinkedHashMap<String, String>()
            factsFile.readLines().forEach { line ->
                val at = line.indexOf('=')
                if (at > 0) facts[line.take(at)] = line.substring(at + 1)
            }
            File(dir, CrashEvidenceStore.HistoryName).takeIf { it.isFile }?.let { history ->
                val value = history.readText().trim()
                if (value.isNotEmpty()) facts["history"] = value
            }
            if (facts["capture_boot_id"].isNullOrEmpty()) return null
            return facts
        } finally {
            runCatching { process.destroy() }
        }
    }

    private fun startSu(command: String): Process? {
        for (su in SuCandidates) {
            val process = runCatching {
                ProcessBuilder(su, "-c", command)
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: continue
            return process
        }
        return null
    }

    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
