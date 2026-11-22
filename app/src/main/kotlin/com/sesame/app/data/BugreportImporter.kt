package com.sesame.app.data

import com.sesame.app.domain.model.ImportedEvidence
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * A user-supplied bugreport, reduced in memory for one export session.
 *
 * The full report is never copied into app storage. The importer streams the
 * zip once and keeps only the evidence that answers "how did the crash boot
 * end": the boot properties, the current `KERNEL LOG`, the ROM's reboot/crash
 * block, vendor panic summaries, generic kernel-crash blocks, top-level
 * `last_kmsg*`/`*oops*` files, and the pure-text evidence inside MIUI's nested
 * stability-reboot zips. Everything else — logcat, app data, minidumps — is
 * read past and dropped.
 *
 * The main text is scanned as raw bytes. The evidence is written as bytes
 * anyway, and the line-at-a-time decode plus two lowercase copies per line
 * dominated a large report's import (a 90 MB main text has ~1.2M lines). The
 * marker set is matched in one Aho-Corasick pass per line; only a line that
 * can possibly match is decoded and handed to the exact predicates.
 *
 * The rules match the report's content, never the device or build.
 */
internal object BugreportImporter {
    private const val MaxTopKernelFileBytes = 4L * 1024 * 1024
    private const val MaxTopKernelTotalBytes = 8L * 1024 * 1024
    private const val MaxKernelLogBytes = 4L * 1024 * 1024
    private const val MaxRebootRecordsBytes = 4L * 1024 * 1024
    private const val MaxPanicRecordBytes = 256L * 1024
    private const val MaxCrashExcerptsBytes = 4L * 1024 * 1024
    private const val MaxNestedLastKmsgBytes = 4L * 1024 * 1024
    private const val MaxNestedMdHistoryBytes = 1L * 1024 * 1024
    private const val MaxNestedTextBytes = 16L * 1024 * 1024
    private const val MaxNestedTotalBytes = 32L * 1024 * 1024
    private const val MaxEvidenceBytes = 48L * 1024 * 1024
    private const val MaxNestedZips = 8
    /* A vendor stability zip is written at the crash, a moment before the
     * capture boot's reboot entry; allow that margin when comparing. */
    private const val NestedZipWindowSlackSeconds = 120L
    private const val UptimeTailBytes = 64 * 1024

    private const val GenericBeforeLines = 64
    private const val GenericAfterLines = 256
    private const val GenericMergeGap = 20
    private const val PanicBeforeLines = 10
    private const val PanicAfterLines = 10
    private const val PanicMergeGap = 10
    private const val RingLines = 520
    private const val RingBytes = 512 * 1024

    private const val RebootRecordsName = "reboot-records.txt"
    private const val PanicRecordName = "panic-record.txt"
    private const val CrashExcerptsName = "crash-excerpts.txt"
    private const val SectionsName = "report-sections.txt"
    private const val NestedLastKmsgName = "stability-last_kmsg"
    private const val MdHistoryName = "md_history.txt"
    private const val MtdoopsName = "mtdoops.md"
    private const val LastLogcatName = "last_logcat.txt"

    private const val KernelLogHeader = "------ KERNEL LOG (dmesg) ------"
    private const val RebootRecordsMarker = "Kernel reboot records"

    private val PropertyNames = listOf(
        "ro.boot.bootreason",
        "sys.boot.reason",
        "sys.boot.reason.last",
        "persist.sys.boot.reason.history",
    )

    private val Newline = byteArrayOf('\n'.code.toByte())
    private val ZipEpochPattern = Regex("_(\\d{10})_\\d+\\.zip$", RegexOption.IGNORE_CASE)
    private val UptimePattern = Regex("\\[\\s*(\\d+)\\.\\d+\\]")
    private val KernelLogHeaderBytes = KernelLogHeader.toByteArray(StandardCharsets.UTF_8)
    private val KernelLogEndHeaderBytes = "------ ".toByteArray(StandardCharsets.UTF_8)
    private val KernelLogEndBytes =
        "was the duration of 'KERNEL LOG (dmesg)'".toByteArray(StandardCharsets.UTF_8)
    private val RebootRecordsMarkerBytes = RebootRecordsMarker.toByteArray(StandardCharsets.UTF_8)
    private val RebootPoweroffBytes = "kernel poweroff".toByteArray(StandardCharsets.UTF_8)
    private val RebootRebootBytes = "kernel reboot".toByteArray(StandardCharsets.UTF_8)
    private val HeaderPrefixBytes = "------ ".toByteArray(StandardCharsets.UTF_8)
    private val HeaderSuffixBytes = " ------".toByteArray(StandardCharsets.UTF_8)
    private val DurationMarkerBytes = "was the duration of".toByteArray(StandardCharsets.UTF_8)
    private val PropertyPrefixes = PropertyNames.map {
        ("[$it]:").toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * The crash markers, each a [CrashTokens] entry. A hit in any of them is
     * the whole crash-excerpts predicate.
     */
    private val CrashTokens = listOf(
        "kernel panic",
        "panic - not syncing",
        "end kernel panic",
        "unable to handle kernel",
        "internal error: oops",
        "oops:",
        "kernel bug at",
        "kernel bug",
        "bug: unable to handle",
        "general protection fault",
        "kpanic",
        "mrdump_common_die",
        "ipanic_die",
        "sysrq triggered crash",
        "sysrq : trigger a crash",
        "soft lockup",
        "hard lockup",
        "rcu stall",
        "watchdog: bug",
    )

    /**
     * The atoms [isPanicRecord] tests. A line that hits none of them cannot
     * satisfy any branch of that predicate.
     */
    private val PanicTokens = listOf(
        "panic reboot",
        "panic_reboot",
        "record panic",
        "panic record",
        "fst_frame",
        "keyinfo=",
        "keyinfo =",
        "stackhash",
        "panic",
        "reboot",
        "criticallog",
        "critical_log",
        "critical log",
        "kernel",
        "issue = panic",
        "issue=panic",
        "boot reason",
        "reboot reason",
        "reset reason",
        "watchdog",
        "watchdog bite",
        "hardware watchdog reset",
        "wdt reset",
    )

    private val MarkerTokens = CrashTokens + PanicTokens + RebootRecordsMarker.lowercase(Locale.US)
    private val Matcher = MarkerMatcher(MarkerTokens)
    private val CrashMask = maskOf(CrashTokens)
    private val PanicMask = maskOf(PanicTokens)
    private val RebootMarkerMask = maskOf(listOf(RebootRecordsMarker.lowercase(Locale.US)))
    private val PredicateMask = CrashMask or PanicMask

    private fun maskOf(tokens: List<String>): Long =
        tokens.fold(0L) { acc, token -> acc or (1L shl MarkerTokens.indexOf(token)) }

    private data class MainScan(
        val history: String?,
        val rebootRecords: Boolean,
        val panicRecord: Boolean,
        val crashExcerpts: Boolean,
    )

    private data class NestedScan(
        val keptBytes: Long,
        val crashEvidence: Boolean,
    )

    /**
     * Streams [input] and returns the reduced evidence, or null when the zip
     * is invalid or contains nothing allowlisted. Nothing is written to disk.
     */
    fun extract(
        input: InputStream,
        name: String,
        captureBootId: String,
        capturedMs: Long = System.currentTimeMillis(),
    ): ImportedEvidence? {
        val sink = MemorySink(MaxEvidenceBytes)
        val properties = LinkedHashMap<String, String>()
        val outcomes = LinkedHashMap<String, String>()
        var history: String? = null
        var mainScanned = false
        var mainTextName = ""
        var topTotal = 0L
        var nestedTotal = 0L
        var crashEvidence = false
        var heuristicCrash = false
        val nestedSeen = mutableSetOf<String>()
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val base = entry.name.substringAfterLast('/')
                    if (!mainScanned && isMainText(entry.name)) {
                        mainScanned = true
                        mainTextName = entry.name
                        val scan = scanMainText(zip, sink, properties)
                        history = scan.history
                        if (scan.rebootRecords || scan.panicRecord) crashEvidence = true
                        if (scan.crashExcerpts) heuristicCrash = true
                        continue
                    }
                    if (isNestedStabilityZip(entry.name)) {
                        val key = "${entry.name}|${entry.size}|${entry.crc}"
                        if (nestedSeen.add(key) && nestedSeen.size <= MaxNestedZips) {
                            val nested = scanNestedStability(
                                zip,
                                entry,
                                sink,
                                outcomes,
                                nestedTotal,
                            )
                            nestedTotal += nested.keptBytes
                            // The crash-evidence claim is decided after the
                            // boot-reason history is known (see the freshness
                            // gate below); the scan only collects.
                        } else {
                            outcomes["nested.duplicate.${entry.name}"] = "skipped"
                        }
                        runCatching { zip.closeEntry() }
                        continue
                    }
                    if (!isTopLevelKernelFile(base)) continue
                    if (topTotal >= MaxTopKernelTotalBytes) {
                        outcomes["file.$base"] = "skipped"
                        continue
                    }
                    val buffer = ByteArrayOutputStream()
                    val written = runCatching {
                        copyLimited(zip, buffer, MaxTopKernelFileBytes)
                    }.getOrDefault(0)
                    if (written > 0) {
                        val actual = sink.put(base, buffer.toByteArray())
                        if (actual != null) {
                            topTotal += minOf(buffer.size().toLong(), MaxTopKernelFileBytes)
                            crashEvidence = true
                            outcomes["file.$actual"] = "ok"
                            outcomes["origin.$actual"] = "${entry.name}@${entry.time}"
                        } else {
                            outcomes["file.$base"] = "skipped"
                        }
                    } else {
                        outcomes["file.$base"] = "failed"
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        /* Freshness gate (2026-11-21): a vendor stability zip is crash evidence
         * only when its own epoch falls inside the crashed boot's window. The
         * 2026-11-21 venus report attached two byte-identical stability logs
         * from a week-old boot because the importer never compared the zip
         * time with the boot history. Stale files are dropped; the facts keep
         * their epoch and last uptime for diagnosis. */
        val bootWindow = crashBootWindow(history)
        if (bootWindow != null) {
            outcomes["nested.boot_window"] = "${bootWindow.first},${bootWindow.second}"
        }
        var nestedFreshCrash = false
        for (actual in sink.files.keys.toList()) {
            val nestedCrashFile = actual.startsWith(NestedLastKmsgName) ||
                actual.startsWith(MdHistoryName) ||
                actual.startsWith(MtdoopsName)
            if (!nestedCrashFile) continue
            val outerName = outcomes["origin.$actual"].orEmpty().substringBefore('!')
            val epoch = stabilityZipEpoch(outerName)
            if (epoch != null) outcomes["nested.zip_epoch.$actual"] = epoch.toString()
            if (actual.startsWith(NestedLastKmsgName)) {
                lastUptimeSeconds(sink.files.getValue(actual))?.let {
                    outcomes["nested.last_uptime.$actual"] = it.toString()
                }
            }
            val fresh = epoch != null && bootWindow != null &&
                epoch >= bootWindow.first && epoch <= bootWindow.second
            if (fresh) {
                nestedFreshCrash = true
            } else {
                sink.remove(actual)
                outcomes["nested.file.$actual"] = "stale"
                outcomes["nested.stale.$actual"] = when {
                    epoch == null -> "zip name has no epoch: ${outerName.substringAfterLast('/')}"
                    bootWindow == null -> "no boot-reason history"
                    else -> "zip_epoch=$epoch outside [${bootWindow.first}, ${bootWindow.second}]"
                }
            }
        }
        if (nestedFreshCrash) crashEvidence = true

        val kernelLog = sink.contains(CrashEvidenceStore.KernelLogName)
        if (properties.isEmpty() && sink.files.isEmpty() && !kernelLog) return null

        val facts = LinkedHashMap<String, String>()
        facts["source"] = "bugreport"
        facts["capture_boot_id"] = captureBootId
        facts["captured"] = capturedMs.toString()
        facts["bugreport_name"] = name
        facts["main_text"] = mainTextName
        facts["kernel-log-scope"] = "current-boot"
        facts["crash_evidence"] = when {
            crashEvidence -> "yes"
            heuristicCrash -> "heuristic"
            else -> "no"
        }
        if (!crashEvidence && !heuristicCrash) {
            facts["note"] = "no crash evidence found in the report"
        }
        if (sink.contains(SectionsName)) facts["sections_file"] = SectionsName
        if (!history.isNullOrBlank()) facts["history_file"] = CrashEvidenceStore.HistoryName
        if (sink.truncated) facts["truncated"] = "yes"
        facts.putAll(properties.filterKeys { it != "persist.sys.boot.reason.history" })
        facts.putAll(outcomes)

        sink.putExact(CrashEvidenceStore.BootFactsName, renderFacts(facts).toByteArray(StandardCharsets.UTF_8))
        if (!history.isNullOrBlank()) {
            sink.putExact(
                CrashEvidenceStore.HistoryName,
                history.toByteArray(StandardCharsets.UTF_8),
            )
        }
        return ImportedEvidence(
            bugreportName = name,
            capturedMs = capturedMs,
            captureBootId = captureBootId,
            facts = facts,
            history = history,
            files = sink.files.toMap(),
            crashEvidence = crashEvidence || heuristicCrash,
        )
    }

    /**
     * Streams the main report text once. The reader is not closed: closing it
     * would close the ZipInputStream that still has entries to serve.
     */
    private fun scanMainText(
        input: InputStream,
        sink: MemorySink,
        out: MutableMap<String, String>,
    ): MainScan {
        val lines = ByteLineReader(input)
        val ring = ByteRing(RingLines, RingBytes)
        val reboot = RebootRecordsWriter(sink, RebootRecordsName, MaxRebootRecordsBytes)
        val panic = BlockWriter(
            sink,
            PanicRecordName,
            PanicBeforeLines,
            PanicAfterLines,
            PanicMergeGap,
            MaxPanicRecordBytes,
        )
        val crash = BlockWriter(
            sink,
            CrashExcerptsName,
            GenericBeforeLines,
            GenericAfterLines,
            GenericMergeGap,
            MaxCrashExcerptsBytes,
        )
        val sections = SectionInventory(sink)
        var history: String? = null
        val historyLines = mutableListOf<String>()
        var inHistory = false
        var inKernel = false
        var kernelBytes = 0L
        var kernel = ByteArrayOutputStream()
        try {
            var index = 0
            while (lines.readLine()) {
                val bytes = lines.bytes
                val length = lines.length
                ring.add(index, bytes, length)
                sections.onLine(bytes, length)

                if (inHistory) {
                    val text = String(bytes, 0, length, StandardCharsets.UTF_8)
                    val closing = text.trimEnd().endsWith("]")
                    historyLines += if (closing) text.trimEnd().removeSuffix("]") else text
                    if (closing) {
                        history = historyLines.joinToString("\n")
                        out["persist.sys.boot.reason.history"] = history
                        inHistory = false
                    }
                } else {
                    val prop = propertyIndex(bytes, length)
                    if (prop >= 0) {
                        val text = String(bytes, 0, length, StandardCharsets.UTF_8)
                        val name = PropertyNames[prop]
                        val raw = text.substringAfter("]:").trim()
                        val value = raw.removePrefix("[")
                        if (name == "persist.sys.boot.reason.history" && !raw.endsWith("]")) {
                            inHistory = true
                            historyLines.clear()
                            historyLines += value
                        } else if (name == "persist.sys.boot.reason.history") {
                            history = value.removeSuffix("]")
                            out[name] = history
                        } else {
                            out[name] = value.removeSuffix("]")
                        }
                    }
                }

                if (!inKernel) {
                    if (startsWith(bytes, length, KernelLogHeaderBytes)) {
                        inKernel = true
                        kernelBytes = 0
                        kernel = ByteArrayOutputStream()
                    }
                } else if (startsWith(bytes, length, KernelLogEndHeaderBytes) &&
                    contains(bytes, length, KernelLogEndBytes)
                ) {
                    inKernel = false
                } else if (kernelBytes < MaxKernelLogBytes) {
                    kernel.write(bytes, 0, length)
                    kernel.write('\n'.code)
                    kernelBytes += length + 1
                }

                /* The crash-excerpt and panic-record predicates are exact, but
                 * they only need the decoded line when a marker atom is in it. */
                val hits = Matcher.scan(bytes, length)
                val predicateHits = hits and PredicateMask
                val lower = if (predicateHits != 0L) {
                    String(bytes, 0, length, StandardCharsets.UTF_8).lowercase(Locale.US)
                } else {
                    null
                }
                val panicHit = lower != null && (hits and PanicMask) != 0L && isPanicRecord(lower)
                val crashHit = lower != null && (hits and CrashMask) != 0L && isCrashMarker(lower)
                reboot.onLine(index, bytes, length, hits, ring)
                panic.onLine(index, bytes, length, panicHit, ring)
                crash.onLine(index, bytes, length, crashHit, ring)
                index++
            }
        } finally {
            if (kernelBytes > 0) {
                sink.put(CrashEvidenceStore.KernelLogName, kernel.toByteArray())
            }
            kernel = ByteArrayOutputStream()
            reboot.close()
            panic.close()
            crash.close()
            sections.finish()
        }
        return MainScan(
            history = history,
            rebootRecords = sink.contains(RebootRecordsName),
            panicRecord = sink.contains(PanicRecordName),
            crashExcerpts = sink.contains(CrashExcerptsName),
        )
    }

    /**
     * Opens a nested stability-reboot zip and keeps the pure-text evidence
     * only. The outer stream is drained afterwards so the archive can move to
     * its next entry. The nested stream is not closed: its close() would close
     * the outer ZipInputStream.
     */
    private fun scanNestedStability(
        outer: ZipInputStream,
        entry: ZipEntry,
        sink: MemorySink,
        outcomes: MutableMap<String, String>,
        alreadyKept: Long,
    ): NestedScan {
        var kept = 0L
        var crash = false
        val nested = ZipInputStream(BufferedInputStream(outer))
        try {
            while (true) {
                val nestedEntry = nested.nextEntry ?: break
                if (nestedEntry.isDirectory) continue
                val path = nestedEntry.name.replace('\\', '/').lowercase(Locale.US)
                val targetName = when {
                    path == "temp/last_kmsg" || path.endsWith("/temp/last_kmsg") ->
                        NestedLastKmsgName

                    path == "temp/md_history" || path.endsWith("/temp/md_history") ->
                        MdHistoryName

                    path == "log/mtdoops.md" || path.endsWith("/log/mtdoops.md") ->
                        MtdoopsName

                    path == "temp/last_logcat" || path.endsWith("/temp/last_logcat") ->
                        LastLogcatName

                    else -> null
                }
                if (targetName == null) continue
                if (alreadyKept + kept >= MaxNestedTotalBytes) {
                    outcomes["nested.cap.${entry.name}"] = "skipped"
                    continue
                }
                val limit = when (targetName) {
                    NestedLastKmsgName -> MaxNestedLastKmsgBytes
                    MdHistoryName -> MaxNestedMdHistoryBytes
                    else -> MaxNestedTextBytes
                }
                val buffer = ByteArrayOutputStream()
                val written = runCatching {
                    copyLimited(nested, buffer, limit)
                }.getOrDefault(0)
                if (written > 0) {
                    val actual = sink.put(targetName, buffer.toByteArray())
                    if (actual != null) {
                        kept += minOf(buffer.size().toLong(), limit)
                        if (targetName != LastLogcatName) crash = true
                        outcomes["nested.file.$actual"] = "ok"
                        outcomes["origin.$actual"] =
                            "${entry.name}!${nestedEntry.name}@${nestedEntry.time}"
                    } else {
                        outcomes["nested.file.$targetName"] = "skipped"
                    }
                } else {
                    outcomes["nested.file.$targetName"] = "failed"
                }
            }
        } catch (error: Exception) {
            outcomes["nested.failed.${entry.name}"] =
                error.message ?: error.javaClass.simpleName
        }
        return NestedScan(kept, crash)
    }

    private class MemorySink(private val maxBytes: Long) {
        val files = LinkedHashMap<String, ByteArray>()
        private var total = 0L
        var truncated = false
            private set

        fun contains(name: String): Boolean = files.containsKey(name)

        /** Adds a uniquely named evidence file; null when the total cap is full. */
        fun put(name: String, bytes: ByteArray): String? {
            if (bytes.isEmpty()) return null
            val remaining = maxBytes - total
            if (remaining <= 0) {
                truncated = true
                return null
            }
            val data = if (bytes.size > remaining) {
                truncated = true
                bytes.copyOf(remaining.toInt())
            } else {
                bytes
            }
            if (data.isEmpty()) return null
            val actual = uniqueName(name)
            files[actual] = data
            total += data.size
            return actual
        }

        /** The completion marker and history are tiny and always kept. */
        fun putExact(name: String, bytes: ByteArray) {
            files[name] = bytes
            total += bytes.size
        }

        /** Drops a file the freshness gate rejected. */
        fun remove(name: String): Boolean {
            val data = files.remove(name) ?: return false
            total -= data.size
            return true
        }

        private fun uniqueName(base: String): String {
            var candidate = base
            var suffix = 1
            while (files.containsKey(candidate)) {
                candidate = "$base-$suffix"
                suffix++
            }
            return candidate
        }
    }

    /**
     * Splits the stream into lines with `BufferedReader`'s terminators (`\n`,
     * `\r`, `\r\n`) without decoding. The bytes stay in [bytes] until the next
     * call; callers copy what they keep. The line buffer is reused, so a scan
     * of a million lines allocates nothing per line.
     */
    private class ByteLineReader(private val input: InputStream) {
        private val buffer = ByteArray(64 * 1024)
        private var pos = 0
        private var limit = 0
        private var skipLf = false
        private var line = ByteArray(256)
        var length = 0
            private set

        val bytes: ByteArray get() = line

        fun readLine(): Boolean {
            length = 0
            var sawBytes = false
            if (skipLf) {
                skipLf = false
                if (pos >= limit) fill()
                if (limit > 0 && buffer[pos].toInt() == LF) pos++
            }
            while (true) {
                if (pos >= limit) {
                    fill()
                    if (limit <= 0) return sawBytes
                }
                var i = pos
                while (i < limit) {
                    val b = buffer[i].toInt()
                    if (b == LF || b == CR) {
                        if (i > pos) {
                            append(buffer, pos, i - pos)
                            sawBytes = true
                        }
                        pos = i + 1
                        if (b == CR) {
                            if (pos >= limit) {
                                fill()
                                if (limit > 0 && buffer[pos].toInt() == LF) pos++
                            } else if (buffer[pos].toInt() == LF) {
                                pos++
                            }
                        }
                        return true
                    }
                    i++
                }
                append(buffer, pos, i - pos)
                sawBytes = true
                pos = i
            }
        }

        private fun fill() {
            val read = input.read(buffer)
            pos = 0
            limit = if (read > 0) read else 0
        }

        private fun append(src: ByteArray, start: Int, count: Int) {
            if (count <= 0) return
            val needed = length + count
            if (needed > line.size) {
                var size = line.size
                while (size < needed) size *= 2
                line = line.copyOf(size)
            }
            System.arraycopy(src, start, line, length, count)
            length = needed
        }
    }

    /**
     * Keeps the last [maxLines] lines of raw bytes in one arena. Blocks copy
     * their context from here and lines are written back verbatim, so no line
     * is ever decoded for history alone.
     */
    private class ByteRing(maxLines: Int, private val capacity: Int) {
        private val arena = ByteArray(capacity)
        private val starts = IntArray(maxLines)
        private val lens = IntArray(maxLines)
        private val indexes = IntArray(maxLines)
        private var base = 0
        private var used = 0
        private var head = 0
        var count = 0
            private set

        val data: ByteArray get() = arena

        fun indexAt(position: Int): Int = indexes[(head + position) % indexes.size]

        fun startAt(position: Int): Int = starts[(head + position) % indexes.size]

        fun lenAt(position: Int): Int = lens[(head + position) % indexes.size]

        fun add(index: Int, src: ByteArray, length: Int) {
            if (length > capacity) {
                base = 0
                used = 0
                head = 0
                count = 0
                return
            }
            while (count > 0 && (count == indexes.size || used - base + length > capacity)) {
                base += lens[head]
                head = (head + 1) % indexes.size
                count--
            }
            if (used + length > capacity) {
                val live = used - base
                if (live > 0) System.arraycopy(arena, base, arena, 0, live)
                for (i in 0 until count) {
                    val slot = (head + i) % indexes.size
                    starts[slot] -= base
                }
                base = 0
                used = live
            }
            System.arraycopy(src, 0, arena, used, length)
            val slot = (head + count) % indexes.size
            starts[slot] = used
            lens[slot] = length
            indexes[slot] = index
            used += length
            count++
        }
    }

    /**
     * Keeps a bounded block around every matching line as raw bytes. Blocks
     * that are near each other merge; the missing context before the first
     * match comes from the shared ring.
     */
    private class BlockWriter(
        private val sink: MemorySink,
        private val name: String,
        private val before: Int,
        private val after: Int,
        private val mergeGap: Int,
        private val maxBytes: Long,
    ) {
        private val out = ByteArrayOutputStream()
        private var lastWritten = -1
        private var activeEnd = -1
        private var active = false
        private var bytes = 0L
        private var truncated = false

        fun onLine(index: Int, line: ByteArray, length: Int, matched: Boolean, ring: ByteRing) {
            if (truncated) return
            if (matched) {
                val start = maxOf(0, index - before)
                val end = index + after
                if (active && start <= activeEnd + mergeGap) {
                    activeEnd = maxOf(activeEnd, end)
                } else {
                    active = true
                    activeEnd = end
                    if (lastWritten >= 0 && start > lastWritten + 1) {
                        if (!writeRaw(Newline, 0, 1)) return
                    }
                    for (position in 0 until ring.count) {
                        val lineIndex = ring.indexAt(position)
                        if (lineIndex in start..index && lineIndex > lastWritten) {
                            if (!writeLine(
                                    lineIndex,
                                    ring.data,
                                    ring.startAt(position),
                                    ring.lenAt(position),
                                )
                            ) {
                                return
                            }
                        }
                    }
                }
            }
            if (active && index > lastWritten) {
                if (index > activeEnd + mergeGap) {
                    active = false
                } else if (!writeLine(index, line, 0, length)) {
                    return
                }
            }
        }

        fun close() {
            if (out.size() > 0) sink.put(name, out.toByteArray())
        }

        private fun writeLine(index: Int, src: ByteArray, off: Int, length: Int): Boolean {
            if (!writeRaw(src, off, length)) return false
            if (!writeRaw(Newline, 0, 1)) return false
            lastWritten = index
            return true
        }

        private fun writeRaw(src: ByteArray, off: Int, length: Int): Boolean {
            if (truncated) return false
            if (bytes + length > maxBytes) {
                truncated = true
                return false
            }
            out.write(src, off, length)
            bytes += length
            return true
        }
    }

    /**
     * The MIUI reboot/crash block: start at the earliest `kernel poweroff` /
     * `kernel reboot` line before the marker, stop before the next top-level
     * dumpstate header (or the byte cap). The sub-headers with more than six
     * dashes are part of the block.
     */
    private class RebootRecordsWriter(
        private val sink: MemorySink,
        private val name: String,
        private val maxBytes: Long,
    ) {
        private val out = ByteArrayOutputStream()
        private var lastWritten = -1
        private var bytes = 0L
        private var started = false
        private var done = false

        fun onLine(index: Int, line: ByteArray, length: Int, hits: Long, ring: ByteRing) {
            if (done) return
            if (!started) {
                if (hits and RebootMarkerMask == 0L) return
                if (!contains(line, length, RebootRecordsMarkerBytes)) return
                started = true
                var start = index
                for (position in 0 until ring.count) {
                    val lineIndex = ring.indexAt(position)
                    if (lineIndex < index - RingLines + 1) continue
                    if (isRebootStart(ring.data, ring.startAt(position), ring.lenAt(position))) {
                        start = lineIndex
                        break
                    }
                }
                for (position in 0 until ring.count) {
                    val lineIndex = ring.indexAt(position)
                    if (lineIndex in start..index && lineIndex > lastWritten) {
                        if (!writeLine(
                                lineIndex,
                                ring.data,
                                ring.startAt(position),
                                ring.lenAt(position),
                            )
                        ) {
                            done = true
                            return
                        }
                    }
                }
                return
            }
            if (isTopLevelHeader(line, 0, length)) {
                done = true
                return
            }
            if (index > lastWritten) {
                if (!writeLine(index, line, 0, length)) done = true
            }
        }

        fun close() {
            if (out.size() > 0) sink.put(name, out.toByteArray())
        }

        private fun writeLine(index: Int, src: ByteArray, off: Int, length: Int): Boolean {
            if (bytes + length + 1 > maxBytes) return false
            out.write(src, off, length)
            out.write('\n'.code)
            bytes += length + 1
            lastWritten = index
            return true
        }
    }

    private class SectionInventory(private val sink: MemorySink) {
        private val names = LinkedHashSet<String>()

        fun onLine(line: ByteArray, length: Int) {
            if (names.size >= 200) return
            if (!isTopLevelHeader(line, 0, length)) return
            names.add(String(line, 0, length, StandardCharsets.UTF_8).trim())
        }

        fun finish() {
            if (names.isEmpty()) return
            sink.put(
                SectionsName,
                names.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8),
            )
        }
    }

    /**
     * Aho-Corasick over the fixed marker set: one table-driven pass per line
     * replaces the per-marker `contains` scans and the lowercase copies the
     * exact predicates still need. Transitions fold ASCII case, so a hit set
     * is a superset of what either predicate can see.
     */
    private class MarkerMatcher(tokens: List<String>) {
        private val go: IntArray
        private val term: LongArray

        init {
            var capacity = 256
            var table = IntArray(capacity * 128) { -1 }
            var terms = LongArray(capacity)
            var nodes = 1
            for ((tokenIndex, token) in tokens.withIndex()) {
                var node = 0
                for (ch in token) {
                    val c = ch.code
                    if (c !in 0..127) continue
                    var child = table[node * 128 + c]
                    if (child < 0) {
                        if (nodes == capacity) {
                            val grown = capacity * 2
                            table = table.copyOf(grown * 128)
                            Arrays.fill(table, capacity * 128, grown * 128, -1)
                            terms = terms.copyOf(grown)
                            capacity = grown
                        }
                        child = nodes
                        nodes++
                        table[node * 128 + c] = child
                    }
                    node = child
                }
                terms[node] = terms[node] or (1L shl tokenIndex)
            }
            val fail = IntArray(nodes)
            val queue = IntArray(nodes)
            var queueHead = 0
            var queueTail = 0
            for (c in 0 until 128) {
                val child = table[c]
                if (child > 0) {
                    queue[queueTail++] = child
                } else {
                    table[c] = 0
                }
            }
            while (queueHead < queueTail) {
                val node = queue[queueHead++]
                terms[node] = terms[node] or terms[fail[node]]
                val base = node * 128
                val failBase = fail[node] * 128
                for (c in 0 until 128) {
                    val child = table[base + c]
                    if (child >= 0) {
                        fail[child] = table[failBase + c]
                        queue[queueTail++] = child
                    } else {
                        table[base + c] = table[failBase + c]
                    }
                }
            }
            go = table
            term = terms
        }

        fun scan(src: ByteArray, length: Int): Long {
            var state = 0
            var hits = 0L
            for (i in 0 until length) {
                var c = src[i].toInt() and 0xFF
                if (c >= 128) {
                    state = 0
                    continue
                }
                if (c >= 65 && c <= 90) c += 32
                state = go[state * 128 + c]
                hits = hits or term[state]
            }
            return hits
        }
    }

    private const val LF = 0x0A
    private const val CR = 0x0D
    private val Dash = '-'.code.toByte()
    private val Colon = ':'.code.toByte()

    private fun isMainText(path: String): Boolean {
        val base = path.substringAfterLast('/')
        return base.startsWith("bugreport-") &&
            base.endsWith(".txt") &&
            !base.contains("-dumpstate_log-") &&
            !base.startsWith("dumpstate-stats")
    }

    private fun isTopLevelKernelFile(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.startsWith("last_kmsg") || lower.contains("oops")
    }

    private fun isNestedStabilityZip(path: String): Boolean {
        val lower = path.replace('\\', '/').lowercase(Locale.US)
        return lower.startsWith("fs/") &&
            lower.contains("/stability/reboot/") &&
            lower.endsWith(".zip")
    }

    /** Epoch seconds from a vendor stability zip name: `..._<epoch>_<rand>.zip`. */
    private fun stabilityZipEpoch(path: String): Long? {
        val base = path.substringAfterLast('/')
        val match = ZipEpochPattern.find(base) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    /**
     * The crashed boot's window in epoch seconds, from
     * `persist.sys.boot.reason.history` (newest first): the previous entry's
     * time is the boot that crashed, the newest is the capture boot's start.
     * Null when the report carries no usable history.
     */
    private fun crashBootWindow(history: String?): Pair<Long, Long>? {
        val epochs = history.orEmpty().lineSequence()
            .mapNotNull { line -> line.substringAfterLast(',').trim().toLongOrNull() }
            .filter { it > 1_000_000_000L }
            .distinct()
            .sortedDescending()
            .toList()
        if (epochs.size < 2) return null
        return epochs[1] to epochs[0] + NestedZipWindowSlackSeconds
    }

    /** The last `[<uptime>.<us>]` stamp in a kernel log, for the freshness fact. */
    private fun lastUptimeSeconds(bytes: ByteArray): Long? {
        val from = (bytes.size - UptimeTailBytes).coerceAtLeast(0)
        val text = String(bytes, from, bytes.size - from, StandardCharsets.ISO_8859_1)
        val match = UptimePattern.findAll(text).lastOrNull() ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun propertyIndex(src: ByteArray, length: Int): Int {
        for (i in PropertyPrefixes.indices) {
            if (startsWith(src, length, PropertyPrefixes[i])) return i
        }
        return -1
    }

    private fun isTopLevelHeader(src: ByteArray, off: Int, length: Int): Boolean {
        var start = off
        var end = off + length
        while (start < end && isSpaceByte(src[start])) start++
        while (end > start && isSpaceByte(src[end - 1])) end--
        if (end - start < 13) return false
        if (src[start] != Dash) return false
        if (!startsWithAt(src, start, end, HeaderPrefixBytes)) return false
        if (!endsWithAt(src, start, end, HeaderSuffixBytes)) return false
        if (containsAt(src, start, end, DurationMarkerBytes)) return false
        var leading = 0
        while (start + leading < end && src[start + leading] == Dash) leading++
        var trailing = 0
        while (end - 1 - trailing >= start && src[end - 1 - trailing] == Dash) trailing++
        return leading == 6 && trailing == 6
    }

    /** `^(kernel poweroff|kernel reboot)\s*:` on the trimmed line (case-sensitive). */
    private fun isRebootStart(src: ByteArray, off: Int, length: Int): Boolean {
        var start = off
        var end = off + length
        while (start < end && isSpaceByte(src[start])) start++
        while (end > start && isSpaceByte(src[end - 1])) end--
        val after = when {
            startsWithAt(src, start, end, RebootPoweroffBytes) ->
                start + RebootPoweroffBytes.size

            startsWithAt(src, start, end, RebootRebootBytes) ->
                start + RebootRebootBytes.size

            else -> return false
        }
        var p = after
        while (p < end && isSpaceByte(src[p])) p++
        return p < end && src[p] == Colon
    }

    /**
     * A vendor panic-reboot summary line; the block writer keeps its context.
     * Generic kernel crash markers are left to [isCrashMarker] so this file
     * stays a summary, not a duplicate of the crash-excerpt scan.
     */
    private fun isPanicRecord(s: String): Boolean {
        if (s.contains("panic reboot") || s.contains("panic_reboot") ||
            s.contains("record panic") || s.contains("panic record")
        ) {
            return true
        }
        /* These are field names, not the bare word "keyinfo": the latter is a
         * common path/action fragment (keyinfo.txt, SMART5G_KEYINFO). */
        if (s.contains("fst_frame")) return true
        if (s.contains("keyinfo=") || s.contains("keyinfo =")) return true
        if (s.contains("stackhash") &&
            (s.contains("panic") || s.contains("reboot") || s.contains("fst_frame"))
        ) {
            return true
        }
        if (s.contains("criticallog") || s.contains("critical_log") ||
            s.contains("critical log")
        ) {
            if (s.contains("kernel") || s.contains("panic")) return true
        }
        if (s.contains("issue = panic") || s.contains("issue=panic")) return true
        /* "kernel" alone is not enough here: a long dumpsys line can contain
         * both a reason phrase and the word "kernel" by coincidence. */
        if ((s.contains("boot reason") || s.contains("reboot reason") ||
                s.contains("reset reason")) &&
            (s.contains("panic") || s.contains("watchdog"))
        ) {
            return true
        }
        return s.contains("watchdog bite") ||
            s.contains("hardware watchdog reset") ||
            s.contains("wdt reset")
    }

    /** A kernel crash marker in the already-lowercased line. */
    private fun isCrashMarker(s: String): Boolean = CrashTokens.any { s.contains(it) }

    private fun isSpaceByte(b: Byte): Boolean {
        val v = b.toInt()
        return v == 0x20 || v in 0x09..0x0D
    }

    private fun startsWith(src: ByteArray, length: Int, prefix: ByteArray): Boolean {
        if (length < prefix.size) return false
        for (i in prefix.indices) {
            if (src[i] != prefix[i]) return false
        }
        return true
    }

    private fun startsWithAt(src: ByteArray, start: Int, end: Int, prefix: ByteArray): Boolean {
        if (end - start < prefix.size) return false
        for (i in prefix.indices) {
            if (src[start + i] != prefix[i]) return false
        }
        return true
    }

    private fun endsWithAt(src: ByteArray, start: Int, end: Int, suffix: ByteArray): Boolean {
        if (end - start < suffix.size) return false
        val offset = end - suffix.size
        for (i in suffix.indices) {
            if (src[offset + i] != suffix[i]) return false
        }
        return true
    }

    private fun contains(src: ByteArray, length: Int, token: ByteArray): Boolean =
        containsAt(src, 0, length, token)

    private fun containsAt(src: ByteArray, start: Int, end: Int, token: ByteArray): Boolean {
        val limit = end - token.size
        var i = start
        while (i <= limit) {
            if (src[i] == token[0]) {
                var j = 1
                while (j < token.size && src[i + j] == token[j]) j++
                if (j == token.size) return true
            }
            i++
        }
        return false
    }

    private fun renderFacts(facts: Map<String, String>): String =
        facts.entries.joinToString("\n", postfix = "\n") { (key, value) ->
            "$key=${value.replace('\n', ' ').replace('\r', ' ')}"
        }

    private fun copyLimited(input: InputStream, out: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            val allowed = minOf(read.toLong(), limit - total)
            if (allowed <= 0) break
            out.write(buffer, 0, allowed.toInt())
            total += allowed
        }
        return total
    }
}
