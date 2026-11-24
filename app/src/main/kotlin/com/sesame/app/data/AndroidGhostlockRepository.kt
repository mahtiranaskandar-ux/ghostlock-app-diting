package com.sesame.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.IpSecAlgorithm
import android.net.IpSecManager
import android.net.IpSecTransform
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.sesame.app.BuildConfig
import com.sesame.app.BuildInfo
import com.sesame.app.domain.model.CrashEvidenceInfo
import com.sesame.app.domain.model.DebugSettings
import com.sesame.app.domain.model.ImportedEvidence
import com.sesame.app.domain.model.KernelSnapshot
import com.sesame.app.domain.model.physSourceFromLines
import com.sesame.app.domain.model.MeasuredFactInfo
import com.sesame.app.domain.model.MeasuredKernelInfo
import com.sesame.app.domain.model.MeasurementMergeRow
import com.sesame.app.data.ota.OtaPayloadExtractor
import com.sesame.app.data.profile.OverlayFact
import com.sesame.app.data.profile.OverlayRecord
import com.sesame.app.data.profile.ProfileResolver
import com.sesame.app.data.profile.PerDevicePhys
import com.sesame.app.data.profile.Timer98260
import com.sesame.app.domain.model.ParseResult
import com.sesame.app.domain.model.PreflightFailure
import com.sesame.app.domain.model.PreflightResult
import com.sesame.app.domain.model.ProfileConfig
import com.sesame.app.domain.model.ProfileImportResult
import com.sesame.app.domain.model.ProfileDiffRow
import com.sesame.app.domain.model.RunLogEntry
import com.sesame.app.domain.model.RootManagerCandidate
import com.sesame.app.domain.model.RunLogState
import com.sesame.app.domain.model.ShizukuStatus
import com.sesame.app.domain.model.StrideCandidateOption
import com.sesame.app.domain.model.StrideProbe
import com.sesame.app.domain.model.StrideProbeOutcome
import com.sesame.app.domain.model.VrKoMode
import com.sesame.app.domain.repository.GhostlockRepository
import com.sesame.app.domain.repository.ProfileConfigController
import com.sesame.app.shizuku.ShizukuExploitRunner
import com.sesame.app.shizuku.TimerVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Android implementation of the domain repository. All platform I/O lives here. */
class AndroidGhostlockRepository(context: Context) : GhostlockRepository {
    private companion object {
        const val FeasibleReportFileName = "offsets_feasible.json"
        const val OptionsReportFileName = "offsets_options.json"
        const val EditSessionPreferences = "ghostlock_edit_session"
        const val ExtractBinaryName = "libextract.so"
        const val NativeBinaryName = "libghostlock.so"
        /* The timer route's generation-side analyzer (the vendored timer-extract). */
        const val TimerExtractBinaryName = "libtimerextract.so"
        const val TimerExtractTimeoutSeconds = 180L

        /* Measured database and the root phase's /proc/slabinfo capture. */
        const val MeasuredDbFileName = "measured_profiles.conf"
        const val SlabinfoCaptureFileName = ".ghostlock_slabinfo"

        /* The probe runs up to ten leak rounds plus the spray setup. */
        const val ProbeTimeoutSeconds = 900L
        /* The export flow's Shizuku permission dialog: long enough for a human,
         * short enough that a denied/ignored dialog still falls through. */
        const val CRASH_SHIZUKU_TIMEOUT_MS = 20_000L
        val AnsiEscape = Regex("\u001B\\[[0-9;]*m")
        /** The generated document's release line, for the timer analyzer's template rule. */
        val ReleasePattern = Regex("(?m)^release\\s*=\\s*\"([^\"]+)\"")
        const val PrefForceAttackTest = "force_attack_test"
        /** Tri-state vr.ko choices, stored as auto/on/off. */
        const val PrefVrKoTagClear = "vr_ko_tag_clear"
        const val PrefVrKoSelinuxBlock = "vrko_selinux_block"

        const val PrefDebugKernelLogEnabled = "debug_kernel_log_enabled"
        /** Root-manager choice (package name); absent means Auto. */
        const val PrefManagerPackage = "manager_package"

        /** The boot a timer attempt last ran on; a same-boot retry is refused. */
        const val PrefTimerAttemptBoot = "timer_attempt_boot_id"

        /* U01-S14: per-run KernelSU log name; the resolved path travels to
         * the native process via GHOSTLOCK_KSU_LOG. */
        fun ksuLogName(runStamp: Long) = "ghostlock-ksu-$runStamp.log"

        /* Every run's stdout/stderr is redirected here (export or not), so the
         * previous run can be inspected on the next start-up. */
        const val NativeLogFileName = ".ghostlock_native.log"

        /* Attack step status persisted by the app. The native process reports
         * each step on stdout and waits for an ACK on stdin; the app owns the
         * file so a kernel panic still leaves the last in_progress step for the
         * next launch to read. HOCON/JSON. */
        const val RunStateFileName = ".ghostlock_run_state.json"
        const val StatusMarker = "\u001eGLK_STATUS"
        const val StatusAck = "\u001eGLK_STATUS_ACK\n"
        const val StatusDisabled = "\u001eGLK_STATUS_DISABLED"

        /* Native `config::anchor_state_file`: home_dir + "/.ghostlock_anchor".
         * The direct path's home is filesDir; the Shizuku service's is
         * /data/local/tmp/ghostlock-app, outside the app sandbox. */
        const val AnchorStateFileName = ".ghostlock_anchor"

        /* A pre-flight manager choice is reused only this soon, and only while
         * the chosen binary still exists. */
        const val PreflightManagerMaxAgeMs = 15_000L
    }

    private val appContext = context.applicationContext
    private val filesDir: File = appContext.filesDir
    private val preferences get() =
        appContext.getSharedPreferences("ghostlock_prefs", Context.MODE_PRIVATE)
    /** Ground-truth facts; written only from a completed run's root phase. */
    private val measuredProfiles = MeasuredProfileStore(File(filesDir, MeasuredDbFileName))
    private val externalProfileStore = AndroidExternalProfileStore(appContext)
    private val profileController = AndroidProfileConfigController(
        appContext,
        externalProfileStore,
        preferences,
    )
    private var safeModeEnabled = false
    private var forceAttackTest = false
    private var vrKoTagClear = VrKoMode.AUTO
    private var vrKoSelinuxBlock = VrKoMode.AUTO
    private var shizukuEnabled = false
    private val shizukuRunner = ShizukuExploitRunner(appContext)

    init {
        restoreShizukuPreference()
        restoreForceAttackTest()
        restoreVrKoTagClear()
        restoreVrKoSelinuxBlock()
    }

    override suspend fun snapshot(): KernelSnapshot {
        val release = System.getProperty("os.version", "unknown").orEmpty()
        /* Shizuku is opt-in only: the toggle is the user's, never seeded from a
         * profile or inferred from a kernel property. */
        val shizukuActive = shizukuEnabled
        val socName = resolveSocName()
        return KernelSnapshot(
            deviceName = resolveDeviceName(),
            kernelRelease = release,
            socName = socName,
            safeModeEnabled = safeModeEnabled,
            forceAttackTest = forceAttackTest,
            vrKoTagClear = vrKoTagClear,
            vrKoSelinuxBlock = vrKoSelinuxBlock,
            shizukuEnabled = shizukuActive,
            shizukuStatus = if (shizukuActive) shizukuRunner.status()
            else com.sesame.app.domain.model.ShizukuStatus.NOT_REQUIRED,
        )
    }

    override fun setSafeModeEnabled(enabled: Boolean) {
        safeModeEnabled = enabled
    }

    override fun setForceAttackTest(enabled: Boolean) {
        forceAttackTest = enabled
        preferences.edit { putBoolean(PrefForceAttackTest, enabled) }
    }

    override fun setVrKoTagClear(mode: VrKoMode) {
        vrKoTagClear = mode
        preferences.edit { putString(PrefVrKoTagClear, mode.wire) }
    }

    override fun setVrKoSelinuxBlock(mode: VrKoMode) {
        vrKoSelinuxBlock = mode
        preferences.edit { putString(PrefVrKoSelinuxBlock, mode.wire) }
    }

    override fun setShizukuEnabled(enabled: Boolean) {
        shizukuEnabled = enabled
        appContext.getSharedPreferences("ghostlock_prefs", Context.MODE_PRIVATE)
            .edit {
                putBoolean("shizuku_enabled", enabled)
            }
        if (enabled) shizukuRunner.requestPermission()
    }

    override suspend fun managerCandidates(): List<RootManagerCandidate> = withContext(Dispatchers.IO) {
        ManagerScanner.scan(appContext).map {
            RootManagerCandidate(
                packageName = it.packageName,
                label = it.label,
                path = it.path,
                builtin = it.builtin,
                suitable = it.suitable,
                verdict = it.verdict,
            )
        }
    }

    override fun selectedManagerPackage(): String? =
        preferences.getString(PrefManagerPackage, null)?.takeIf { it.isNotBlank() }

    override fun setSelectedManagerPackage(packageName: String?) {
        preferences.edit {
            if (packageName.isNullOrBlank()) remove(PrefManagerPackage)
            else putString(PrefManagerPackage, packageName)
        }
    }

    override fun profileController(): ProfileConfigController = profileController

    override suspend fun measurements(): List<MeasuredKernelInfo> = withContext(Dispatchers.IO) {
        measuredProfiles.kernels().map { kernel ->
            MeasuredKernelInfo(
                release = kernel.release,
                updatedAt = kernel.updatedAt,
                facts = kernel.facts.map { fact ->
                    MeasuredFactInfo(
                        key = fact.key,
                        value = fact.value,
                        source = fact.source,
                        evidence = fact.evidence,
                        measuredAt = fact.measuredAt,
                    )
                },
            )
        }
    }

    /* debug-ui: preferences for the hidden debug screen. */
    override suspend fun debugSettings(): DebugSettings = DebugSettings(
        kernelLogEnabled = preferences.getBoolean(PrefDebugKernelLogEnabled, true),
    )

    override fun setDebugKernelLogEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(PrefDebugKernelLogEnabled, enabled) }
    }

    override suspend fun listRunLogs(): List<RunLogEntry> = withContext(Dispatchers.IO) {
        RunLogStore.list(appContext)
    }

    override suspend fun readRunLog(stamp: String): String? = withContext(Dispatchers.IO) {
        RunLogStore.readLog(appContext, stamp)
    }

    override suspend fun exportRunLog(stamp: String, documentUri: String): Boolean =
        withContext(Dispatchers.IO) {
            RunLogStore.exportZip(appContext, stamp, documentUri.toUri())
        }

    override suspend fun clearRunLogs() = withContext(Dispatchers.IO) {
        RunLogStore.clear(appContext)
    }

    override suspend fun markInterruptedRunLogs(): Int = withContext(Dispatchers.IO) {
        RunLogStore.markInterrupted(appContext)
    }

    override fun reportBundleName(): String = RunLogStore.reportName()

    override suspend fun latestCrashEvidence(): CrashEvidenceInfo? = withContext(Dispatchers.IO) {
        CrashEvidenceStore.latestAutomatic(CrashEvidenceStore.root(appContext))?.toInfo()
    }

    private fun currentBootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("unknown")

    /**
     * Captures what the current boot can still show about the last crash.
     * Shell first (Shizuku, then root), one bare capture when neither exists.
     * The store keys captures by the capture boot, so a second start in the
     * same boot does not duplicate. A bare "no shell" capture is retried
     * (the user may enable Shizuku after the first attempt); a shell-backed
     * capture is final, because the previous boot's pstore cannot change
     * later in this boot.
     */
    override suspend fun collectCrashEvidence(): CrashEvidenceInfo? = withContext(Dispatchers.IO) {
        val bootId = currentBootId()
        val root = CrashEvidenceStore.root(appContext)
        val existing = CrashEvidenceStore.findForBoot(root, bootId)
        /* A shell-backed capture is final for this boot: the pstore record of
         * the previous boot cannot appear later. Only a bare "no shell"
         * capture is worth retrying (the user may enable Shizuku or root). */
        if (existing != null && existing.source != "none" && existing.source != "unknown") {
            return@withContext existing.toInfo()
        }
        val now = System.currentTimeMillis()
        val dir = existing?.dir ?: CrashEvidenceStore.newDir(root, bootId, now)
        val facts = readCrashFacts(dir)
        val history = facts?.get("history")
        val capture = when {
            facts != null -> CrashEvidenceStore.complete(root, dir, facts, history, now)
            existing == null -> CrashEvidenceStore.complete(
                root,
                dir,
                mapOf(
                    "source" to "none",
                    "capture_boot_id" to bootId,
                    "note" to "no shell available; pstore not readable",
                ),
                null,
                now,
            )
            else -> existing
        }
        capture?.toInfo()
    }

    /**
     * The export flow's capture. Opening the report sheet is a user action, so
     * asking for the Shizuku permission here is appropriate; the start-up
     * capture must not prompt and keeps calling [collectCrashEvidence].
     */
    override suspend fun collectCrashEvidenceWithShizuku(): CrashEvidenceInfo? {
        if (shizukuRunner.crashAccessStatus() == ShizukuStatus.PERMISSION_REQUIRED) {
            withContext(Dispatchers.Main) { shizukuRunner.requestPermission() }
            shizukuRunner.awaitCrashAccess(CRASH_SHIZUKU_TIMEOUT_MS)
        }
        return collectCrashEvidence()
    }

    override fun shizukuInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    override fun crashShellStatus(): ShizukuStatus = shizukuRunner.crashAccessStatus()

    override fun shizukuShellStatus(): ShizukuStatus = shizukuRunner.status()

    override suspend fun generateBugreport(): ImportedEvidence? {
        if (shizukuRunner.crashAccessStatus() == ShizukuStatus.PERMISSION_REQUIRED) {
            withContext(Dispatchers.Main) { shizukuRunner.requestPermission() }
            shizukuRunner.awaitCrashAccess(CRASH_SHIZUKU_TIMEOUT_MS)
        }
        val stream = shizukuRunner.openBugreportStream() ?: return null
        val bootId = currentBootId()
        return stream.use {
            val name = "bugreport-shizuku-${bootId.take(8)}.zip"
            val started = System.nanoTime()
            runCatching { BugreportImporter.extract(it.stream, name, bootId) }
                .also { evidence ->
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    Log.i("GhostLock", "bugreport generate: $name ${elapsed} ms, ok=${evidence != null}")
                }
                .getOrNull()
        }
    }

    private suspend fun readCrashFacts(dir: File): Map<String, String>? {
        if (shizukuRunner.crashAccessStatus() == ShizukuStatus.READY) {
            val facts = shizukuRunner.collectCrash(dir)
            if (!facts.isNullOrEmpty() && !facts["capture_boot_id"].isNullOrEmpty()) return facts
        }
        return RootCrashCollector.collect(appContext, dir)
    }

    override suspend fun importBugreport(uri: String): ImportedEvidence? =
        withContext(Dispatchers.IO) {
            val parsed = runCatching { uri.toUri() }.getOrNull() ?: return@withContext null
            val name = runCatching {
                appContext.contentResolver.query(
                    parsed,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: parsed.lastPathSegment ?: "bugreport.zip"
            val input = runCatching { appContext.contentResolver.openInputStream(parsed) }
                .onFailure { Log.w("GhostLock", "bugreport import: cannot open $parsed", it) }
                .getOrNull() ?: return@withContext null
            val started = System.nanoTime()
            input.use { stream ->
                BugreportImporter.extract(stream, name, currentBootId())
            }.also {
                val elapsed = (System.nanoTime() - started) / 1_000_000
                Log.i("GhostLock", "bugreport import: $name ${elapsed} ms, ok=${it != null}")
            }
        }

    override suspend fun exportReportBundle(
        documentUri: String,
        pendingImport: ImportedEvidence?,
    ): Boolean = withContext(Dispatchers.IO) {
        RunLogStore.exportBundle(appContext, documentUri.toUri(), pendingImport)
    }

    override suspend fun clearStoredImports() = withContext(Dispatchers.IO) {
        CrashEvidenceStore.clearImported(CrashEvidenceStore.root(appContext))
    }

    /* Profile configuration now lives in AndroidProfileConfigController: the
     * repository only wires it and forwards the native document. */

    override suspend fun parseSource(
        input: String,
        xblPath: String?,
        uefiPath: String?,
        vendorBootPath: String?,
        dtboPath: String?,
        route: String?,
        onLog: (String) -> Unit,
    ): ParseResult {
        val parsedFile = File(filesDir, "offsets_parse.tmp")
        val feasibleFile = File(filesDir, FeasibleReportFileName)
        val optionsFile = File(filesDir, OptionsReportFileName)
        var tempBootFile: File? = null
        var tempXblFile: File? = null
        return try {
            val binary = File(appContext.applicationInfo.nativeLibraryDir, ExtractBinaryName)
            if (!binary.isFile) return ParseResult.Failed(1, "missing native binary: ${binary.absolutePath}")

            /* Remote OTA URLs are resolved by the pure-Kotlin extractor so the
             * Android binary ships without the http stack; local files keep
             * going straight to the Rust extractor. */
            val isRemoteUrl = input.startsWith("http://", ignoreCase = true) ||
                input.startsWith("https://", ignoreCase = true)
            val (effectiveInput, effectiveXblPath) = if (isRemoteUrl) {
                val extracted = OtaPayloadExtractor.extractPartitions(
                    url = input,
                    workDir = filesDir,
                    onLog = onLog,
                )
                tempBootFile = extracted.bootFile
                tempXblFile = extracted.xblConfigFile
                Pair(extracted.bootFile.absolutePath, extracted.xblConfigFile?.absolutePath ?: xblPath)
            } else {
                Pair(input, xblPath)
            }

            parsedFile.delete()
            feasibleFile.delete()
            optionsFile.delete()
            /* The profile is generated for this phone. The `devices` map key is
             * the first non-generic identity (Build.DEVICE, then the vendor,
             * then the boot image). When every identity is a mainline/vendor
             * placeholder there is no unique codename: write a device-less
             * document (`devices = null`) instead of inventing a device key. */
            val deviceKey = DeviceIdentity.uniqueProfileKey()
            val deviceCandidates =
                DeviceIdentity.candidates(Build.DEVICE, DeviceIdentity.aliases())
            if (deviceKey == null) {
                onLog(
                    "identity: all candidates are generic " +
                        "(${deviceCandidates.joinToString(", ")}); " +
                        "writing a device-less document (devices = null)",
                )
            }
            val args = buildList {
                add(effectiveInput)
                if (effectiveXblPath != null) {
                    add("--xbl-config")
                    add(effectiveXblPath)
                }
                if (uefiPath != null) {
                    add("--uefi")
                    add(uefiPath)
                }
                if (vendorBootPath != null) {
                    add("--vendor-boot")
                    add(vendorBootPath)
                }
                if (dtboPath != null) {
                    add("--dtbo")
                    add(dtboPath)
                }
                if (deviceKey != null) {
                    add("--device")
                    add(deviceKey)
                }
                /* --format conf: the extractor output is already the flattened
                 * v3 document (no includes, credential template inlined), so
                 * no conversion is needed. */
                /* --anchor: the diting 5.10 line gets its lock-anchor triple
                 * derived from the image; other families warn and ignore it. */
                /* --feasible-out: written before an ambiguity exit, so the app
                 * can offer the routes the image actually supports. */
                addAll(
                    listOf(
                        "--format",
                        "conf",
                        "--anchor",
                        "--out",
                        parsedFile.absolutePath,
                        "--work-dir",
                        filesDir.absolutePath,
                        "--feasible-out",
                        feasibleFile.absolutePath,
                        "--options-out",
                        optionsFile.absolutePath,
                    ),
                )
                if (route != null) {
                    add("--route")
                    add(route)
                }
            }
            val captured = mutableListOf<String>()
            /* The optional timer block derived for this image (see
             * docs/analysis/timer-block-generation-plan.md). */
            var timerBlock: String? = null
            /* One generation log per extractor pass: the stderr decision lines,
             * the verdict JSON and a meta summary land in the app's crash-safe
             * run store and are exportable from the log screen. Exit 7 is a
             * completed pass (the choice moves to the user), not a failure. */
            val code = withDebugAttackLog("parse", onLog, completedCodes = setOf(0, 7)) {
                    archivedLog, _, _, writeSidecar ->
                val tee: (String) -> Unit = { line ->
                    captured += line
                    archivedLog(line)
                }
                tee("<k> extract: $effectiveInput")
                val exit = runProcess(
                    ProcessBuilder(listOf(binary.absolutePath) + args).directory(filesDir).redirectErrorStream(true).apply {
                            environment()["GHOSTLOCK_HOME"] = filesDir.absolutePath
                            environment()["TMPDIR"] = filesDir.absolutePath
                            environment()["HOME"] = filesDir.absolutePath
                        },
                    onLog = tee,
                    timeoutSeconds = 1800,
                )
                tee("<k> extract exit code=$exit")
                /* The timer block is an optional overlay from a separate
                 * analyzer on the same image; its absence or failure leaves a
                 * native-only profile and never fails generation. */
                if (exit == 0 && parsedFile.isFile) {
                    val release = ReleasePattern.find(parsedFile.readText())
                        ?.groupValues?.get(1)
                    if (release != null) {
                        timerBlock = deriveTimerBlock(
                            effectiveInput,
                            release,
                            tee,
                            writeSidecar,
                        )
                    }
                }
                val reportText = feasibleFile.takeIf { it.isFile }?.readText()
                val report = reportText?.let(FeasibleReport::parse)
                if (reportText != null) {
                    writeSidecar("generation.json", reportText.toByteArray(StandardCharsets.UTF_8))
                }
                val optionsText = optionsFile.takeIf { it.isFile }?.readText()
                if (optionsText != null) {
                    writeSidecar("options.json", optionsText.toByteArray(StandardCharsets.UTF_8))
                }
                writeSidecar(
                    "generation.meta",
                    generationMeta(
                        input = input,
                        effectiveInput = effectiveInput,
                        requestedRoute = route,
                        exit = exit,
                        report = report,
                        captured = captured,
                    ).toByteArray(StandardCharsets.UTF_8),
                )
                exit
            }
            val report = feasibleFile.takeIf { it.isFile }?.readText()?.let(FeasibleReport::parse)
            /* The options document is the app's picker source and the cut's
             * base: merge the timer fragment into it too, so a cut carries the
             * timer block. */
            val optionsText = optionsFile.takeIf { it.isFile }?.readText()
            val options = optionsText?.let { text ->
                val merged = timerBlock?.let { mergeTimerFragment(text, it) } ?: text
                OptionSuperset.parse(merged)
            }
            if (code == 7) {
                val routes = report?.routes.orEmpty()
                if (routes.none { it.verdict == FeasibleReport.VerdictFeasible }) {
                    return ParseResult.Failed(7, "route ambiguity without a feasible-route report")
                }
                return ParseResult.Ambiguous(routes, options)
            }
            if (code != 0 || !parsedFile.isFile) return ParseResult.Failed(code)
            val document = parsedFile.readText()
            val generated = timerBlock?.let { block ->
                mergeTimerFragment(document, block) ?: document
            } ?: document
            val fresh = parseEntries(document) ?: return ParseResult.Failed(code, "invalid extractor output")
            /* The document is returned, never stored: the app keeps no profile
             * documents (measured-db-plan §1). The caller probes it in memory,
             * applies the chosen stride, and an export is the only way it can
             * become selectable. */
            val filtered = fresh.mapNotNull { it.asValueMap() }
                .filter { (it["release"] as? String).orEmpty().isNotEmpty() }
            if (filtered.isEmpty()) return ParseResult.AlreadyPresent
            val releases = freshReleases(filtered)
            /* The document is the cleaned v3 tree: the pair lives in the
             * running device's `devices.<id>.phys` entry, and the anchor is
             * `ghostlock.anchor`. The presence checks read those, not the
             * retired top-level spellings. */
            val resolved = filtered.map { resolvedForDevice(it, deviceCandidates) }
            val missing = missingSidecarFields(resolved)
            val name = parsedDocumentName(releases)
            val anchor = parseEntries(generated)
                ?.firstNotNullOfOrNull { it.asValueMap() }
                ?.let { ProfileResolver.anchorOf(it) }
            ParseResult.Parsed(
                releases = releases,
                missing = missing,
                documentName = name,
                route = report?.chosen ?: route,
                anchor = anchorLine(captured),
                hasAnchor = anchor?.first != null,
                physSource = physSourceFromLines(captured),
                physLoadPresent = resolved.any { ProfileResolver.physOf(it).first != null },
                physOffsetPresent = resolved.any { ProfileResolver.physOf(it).second != null },
                timerDerived = timerBlock != null,
                documentText = generated,
                options = options,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ParseResult.Failed(1, error.message)
        } finally {
            parsedFile.delete()
            feasibleFile.delete()
            optionsFile.delete()
            tempBootFile?.delete()
            tempXblFile?.delete()
        }
    }

    /**
     * Derives the image's `timer` block (the timer route's per-image
     * constants) with the vendored timer analyzer. The block is optional
     * metadata: any failure logs and returns null, leaving a native-only
     * profile. It is validated here with the same parser the timer route's
     * loader uses, so a malformed block can never reach the document.
     */
    private suspend fun deriveTimerBlock(
        input: String,
        release: String,
        onLog: (String) -> Unit,
        writeSidecar: (String, ByteArray) -> Boolean,
    ): String? {
        val binary = File(appContext.applicationInfo.nativeLibraryDir, TimerExtractBinaryName)
        if (!binary.isFile) {
            onLog("timer: no analyzer binary; the profile stays native-only")
            return null
        }
        val out = File(filesDir, "timer-block.tmp")
        out.delete()
        val args = buildList {
            add(input)
            add("--emit-timer-conf")
            add(out.absolutePath)
            add("--endgame")
            add("real,fake")
            timerLayoutTemplate(release)?.let { template ->
                add("--layout-template")
                add(template)
            }
        }
        onLog("timer: deriving the block from the image")
        val exit = runCatching {
            runProcess(
                ProcessBuilder(listOf(binary.absolutePath) + args)
                    .directory(filesDir)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["TMPDIR"] = filesDir.absolutePath
                        environment()["HOME"] = filesDir.absolutePath
                    },
                onLog = onLog,
                timeoutSeconds = TimerExtractTimeoutSeconds,
            )
        }.getOrElse { error ->
            onLog("timer: analyzer error (${error.message}); the profile stays native-only")
            out.delete()
            return null
        }
        val text = out.takeIf { it.isFile }?.readText()?.trim()
        out.delete()
        if (exit != 0 || text.isNullOrEmpty()) {
            onLog("timer: analyzer exited $exit; the profile stays native-only")
            return null
        }
        val value = runCatching {
            HoconSupport.parseValue(text).asValueMap()?.get(Timer98260.BLOCK_KEY)
        }.getOrNull()
        val (config, errors) = Timer98260.parse(value)
        if (config == null || errors.isNotEmpty()) {
            onLog(
                "timer: derived block rejected " +
                    "(${errors.firstOrNull()?.fieldPath ?: "unparsable"}); native-only profile",
            )
            return null
        }
        writeSidecar("timer-block.conf", text.toByteArray(StandardCharsets.UTF_8))
        onLog("timer: block derived (endgames=${config.endgameLabel})")
        return text
    }

    override suspend fun probeStride(
        release: String,
        documentText: String,
        onLog: (String) -> Unit,
    ): StrideProbeOutcome {
        /* The probe frame is the generated document, built in memory; nothing
         * is selected or stored (measured-db-plan §1). */
        val blob = profileController.nativeDocumentFor(documentText, release)
            ?: return StrideProbeOutcome.Failed("the probe document could not be rendered")
        val binary = File(appContext.applicationInfo.nativeLibraryDir, NativeBinaryName)
        if (!binary.isFile) return StrideProbeOutcome.Failed("the native probe binary is missing")
        val lines = mutableListOf<String>()
        val exit = runProcess(
            /* --enable-status-record selects the length-prefixed stdin frame
             * (`read_profile_frame_stdin`); without it the native reads the
             * bare document and blocks on the app's length prefix. */
            ProcessBuilder(
                listOf(
                    binary.absolutePath,
                    "--ghostlock-app-call",
                    "--enable-status-record",
                    "--probe-stride",
                ),
            )
                .directory(filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["GHOSTLOCK_HOME"] = filesDir.absolutePath
                    environment()["TMPDIR"] = filesDir.absolutePath
                    environment()["HOME"] = filesDir.absolutePath
                },
            onLog = { raw ->
                val line = raw.replace(AnsiEscape, "")
                lines += line
                onLog(line)
            },
            timeoutSeconds = ProbeTimeoutSeconds,
            /* captureOutput=true is what runs the reader that calls onLog; the
             * run path uses false because it redirects stdout to a file and
             * tails it instead. */
            captureOutput = true,
            stdin = blob,
            frameStdin = true,
        )
        onLog("stride: probe exit=$exit captured=${lines.size}")
        if (exit != 0) {
            /* Any non-zero exit is a step failure, not a verdict: the probe is
             * required whenever it runs (the W1 ladder is sized by the stride),
             * so the caller must not fall through to the save step. */
            return StrideProbeOutcome.Failed("the probe exited $exit")
        }
        val probe = parseStrideProbe(lines)
        if (probe == null) {
            onLog("stride: no candidate table")
            return StrideProbeOutcome.Failed("the probe produced no candidate table")
        }
        return StrideProbeOutcome.Probed(probe)
    }

    override fun applyLockSourceToDocument(
        documentText: String,
        keepAnchor: Boolean,
    ): String? = if (keepAnchor) documentText else stripLockAnchor(documentText)

    override fun materializeStride(documentText: String, value: Int): String? =
        materializeDocumentStride(documentText, value.toLong())

    override fun documentRequiresStride(documentText: String): Boolean {
        val document = HoconSupport.parseValue(documentText).asValueMap() ?: return false
        return ProfileResolver.requiresW1(ProfileResolver.pipelineOrder(document))
    }

    override suspend fun recordGenerationStride(
        release: String,
        value: Int,
        evidence: String,
    ): Boolean = withContext(Dispatchers.IO) {
        measuredProfiles.recordProbeStride(release, value.toLong(), evidence)
    }

    private fun parseStrideProbe(lines: List<String>): StrideProbe? {
        val candidates = mutableListOf<StrideCandidateOption>()
        var rounds = 0
        var samples = 0
        var gcd = 0L
        var decided = false
        val candidateLine = Regex(
            """\[stride\] candidate value=(\d+) votes=(\d+)/(\d+) objects=(\d+)""",
        )
        val summaryLine = Regex(
            """\[stride\] summary rounds=(\d+) samples=(\d+) gcd=0x([0-9a-fA-F]+) """ +
                """candidates=\d+ decided=(\d+)""",
        )
        for (line in lines) {
            candidateLine.find(line)?.let { match ->
                candidates += StrideCandidateOption(
                    value = match.groupValues[1].toInt(),
                    votes = match.groupValues[2].toInt(),
                    samples = match.groupValues[3].toInt(),
                    objectsPerSlab = match.groupValues[4].toInt(),
                )
            }
            summaryLine.find(line)?.let { match ->
                rounds = match.groupValues[1].toInt()
                samples = match.groupValues[2].toInt()
                gcd = match.groupValues[3].toLong(16)
                decided = match.groupValues[4] == "1"
            }
        }
        if (candidates.isEmpty()) return null
        return StrideProbe(candidates, rounds, samples, gcd, decided)
    }

    /** The extractor's first anchor decision line, or null when none ran. */
    private fun anchorLine(lines: List<String>): String? = lines.firstOrNull {
        it.contains("anchor") && (it.startsWith("info:") || it.startsWith("warning:"))
    }

    /**
     * The generation record design 10 asks for: what the image is, which series
     * tier filled the shared fields, every route verdict, the route and why it
     * was chosen, the anchor verdict and how the pass ended. The raw stderr
     * (including every heuristic warning) is the log file itself; the lines
     * about heuristic values are copied here so the meta alone is enough to
     * see that a profile is not fully image-derived.
     */
    private fun generationMeta(
        input: String,
        effectiveInput: String,
        requestedRoute: String?,
        exit: Int,
        report: FeasibleReport?,
        captured: List<String>,
    ): String = buildString {
        val state = when (exit) {
            0 -> "completed"
            7 -> "ambiguous"
            3 -> "infeasible"
            -1 -> "timeout"
            else -> "failed"
        }
        appendLine("state=$state")
        appendLine("exit=$exit")
        appendLine("release=${report?.release.orEmpty()}")
        appendLine("input=$input")
        appendLine("input_sha256=${sha256Of(File(effectiveInput)) ?: "unavailable"}")
        appendLine("series=${captured.firstOrNull { it.contains("series ") }?.substringAfter("series ")?.trim() ?: "unknown"}")
        appendLine("route=${report?.chosen ?: requestedRoute.orEmpty()}")
        /* The extractor's own selection line distinguishes a derived choice
         * from the zero-feasible frame-rule fallback; the report alone cannot. */
        val selection = captured.firstOrNull {
            it.startsWith("info: route ") && it.contains(" selected (")
        }
        appendLine(
            "route_basis=" + when {
                requestedRoute != null -> "forced"
                selection?.contains("(Fallback)") == true -> "fallback"
                selection != null -> "derived"
                exit == 7 -> "ambiguous"
                else -> "none"
            },
        )
        appendLine("route_selection=${selection ?: "none"}")
        appendLine("feasible=${report?.feasible.orEmpty().joinToString(",") { it.route }}")
        report?.routes.orEmpty().forEach { verdict ->
            val numbers = if (verdict.numbers.isEmpty()) {
                ""
            } else {
                "; " + verdict.numbers.entries.joinToString(", ") { (key, value) -> "$key=$value" }
            }
            appendLine("verdict.${verdict.route}=${verdict.verdict}: ${verdict.reason}$numbers")
        }
        appendLine("anchor=${anchorLine(captured) ?: "none"}")
        val heuristics = captured.filter { it.contains("heuristic", ignoreCase = true) }
        appendLine("heuristic_count=${heuristics.size}")
        heuristics.forEachIndexed { index, line -> appendLine("heuristic.$index=$line") }
        val fieldSources = captured.filter { it.contains("field_source ") }
        appendLine("field_source_count=${fieldSources.size}")
        fieldSources.forEachIndexed { index, line -> appendLine("field_source.$index=$line") }
    }

    private fun sha256Of(file: File): String? = runCatching {
        if (!file.isFile) return@runCatching null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /* --- run-start pre-flight (class 1 of the 2026-11-07 exit audit) ------ */

    /** Candidate the pre-flight selected; consumed once by [stageKsud]. */
    @Volatile
    private var preflightManager: Pair<Long, ManagerScanner.Candidate>? = null

    override suspend fun preflight(useShizuku: Boolean): PreflightResult =
        withContext(Dispatchers.IO) {
            /* The anchor state file lives under GHOSTLOCK_HOME: for the Shizuku
             * path that home is /data/local/tmp/ghostlock-app and the process is
             * the shell's, so the judgement is not ours to make here. It is
             * deferred to the service (or to the class-2 pattern). The CPU pair
             * is the native's own verified choice, so it has no app-side gate. */
            if (!useShizuku) {
                anchorFailure()?.let { return@withContext PreflightResult.Blocked(it) }
            }
            /* A scan that throws is not proof of "no manager": fail open and
             * let stageKsud report it (the class-2 pattern covers that). */
            val candidates = runCatching { ManagerScanner.scan(appContext) }.getOrNull()
            if (candidates != null) {
                val chosen = ManagerScanner.resolve(candidates, selectedManagerPackage())
                    ?: return@withContext PreflightResult.Blocked(
                        PreflightFailure.NO_ROOT_MANAGER,
                    )
                preflightManager = SystemClock.elapsedRealtime() to chosen
            }
            PreflightResult.Passed
        }

    /**
     * Mirror of `lock_anchor.cpp`'s boot-scoped decision. Returns the blocking
     * failure, or null when a slot can be handed out (or there is no anchor to
     * check here).
     */
    private suspend fun anchorFailure(): PreflightFailure? {
        val config = runCatching { profileController.load(deviceRelease()) }.getOrNull()
            ?: return null
        val anchor = config.anchor ?: return null
        val bytes = anchor.bytes ?: AnchorSlotState.DefaultBytes
        val stride = anchor.stride ?: AnchorSlotState.DefaultStride
        /* A contradictory geometry is the validator's job (class 1 P2); do not
         * guess a grid here. */
        if (bytes <= 0L || stride < AnchorSlotState.MinStride || bytes % stride != 0L) return null
        val slots = bytes / stride
        /* read_boot_id() falls back to "unknown-boot" natively; mirror it so the
         * gate and the native cannot disagree. */
        val bootId = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        }.getOrNull().orEmpty().ifEmpty { "unknown-boot" }
        val state = runCatching { File(filesDir, AnchorStateFileName).readText() }.getOrNull()
        val trusted = InstallClock.installPredatesBoot(appContext)
        return when (AnchorSlotState.evaluate(bootId, state, slots, trusted)) {
            AnchorSlotState.Verdict.OK -> null
            AnchorSlotState.Verdict.MISSING_UNTRUSTED -> PreflightFailure.ANCHOR_STATE_UNCONFIRMED
            AnchorSlotState.Verdict.BUDGET_SPENT -> PreflightFailure.ANCHOR_BUDGET_SPENT
            AnchorSlotState.Verdict.GRID_MISMATCH -> PreflightFailure.ANCHOR_GRID_MISMATCH
        }
    }

    override suspend fun runExploit(
        config: ProfileConfig,
        onLog: (String) -> Unit,
    ): Int =
        withDebugAttackLog("direct", onLog, measure = true) { archivedLog, debugDir, runDir, writeSidecar ->
            runExploitBinary(config, "libghostlock.so", archivedLog, debugDir, runDir, writeSidecar)
        }

    override fun timerProfileDocument(): String? =
        profileController.renderTimerDocument(deviceRelease())

    override suspend fun runTimerProbe(onLog: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        withDebugAttackLog("timer-probe", onLog) { archivedLog, _, _, writeSidecar ->
            val release = deviceRelease()
            val document = profileController.renderTimerDocument(release) ?: run {
                archivedLog("<s> error: no timer profile for $release")
                return@withDebugAttackLog 1
            }
            writeSidecar("timer-profile.json", document.toByteArray(StandardCharsets.UTF_8))
            archivedLog(
                "<s> timer document ${document.length}B sha256=${sha256Of(document)}",
            )
            shizukuRunner.runTimer(document, "probe", selectedManagerPackage(), archivedLog)
        }
    }

    override fun timerRunAllowed(): Boolean = TimerBootContract.allowsRun(
        preferences.getString(PrefTimerAttemptBoot, null),
        currentBootId(),
    )

    override suspend fun runTimerRoute(onLog: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val bootId = currentBootId()
        val lastAttempt = preferences.getString(PrefTimerAttemptBoot, null)
        if (!TimerBootContract.allowsRun(lastAttempt, bootId)) {
            onLog("<s> error: a timer attempt already ran on this boot; reboot before retrying")
            return@withContext TimerBootContract.Refused
        }
        /* Arm the boot at the start, not at the verdict: if the app or the
         * UserService dies after the tool launched but before the service
         * reports, the same-boot retry must still be refused — the tool may
         * already be parked (a hit holds the fake cred page, a miss the
         * failed plants). A run that never reached the tool clears it below. */
        runCatching { preferences.edit { putString(PrefTimerAttemptBoot, bootId) } }
        val code = withDebugAttackLog("timer", onLog) { archivedLog, _, _, writeSidecar ->
            val release = deviceRelease()
            val document = profileController.renderTimerDocument(release) ?: run {
                archivedLog("<s> error: no way-2 timer profile for $release")
                return@withDebugAttackLog TimerVerdict.NotStarted
            }
            writeSidecar("timer-profile.json", document.toByteArray(StandardCharsets.UTF_8))
            /* The run log carries both: the exact runtime document the tool
             * consumed, and the resolved v3 document (every route block and the
             * provenance). */
            runCatching { profileController.renderResolvedForDebug(release) }
                .getOrNull()
                ?.let { writeSidecar("profile.conf", it.toByteArray(StandardCharsets.UTF_8)) }
            writeSidecar(
                "profile.meta",
                buildString {
                    appendLine("source=profile.conf")
                    appendLine("runtime=timer-profile.json")
                    appendLine("release=$release")
                    appendLine("selected_route=${Timer98260.BLOCK_KEY}")
                    appendLine("timer_sha256=${sha256Of(document)}")
                }.toByteArray(StandardCharsets.UTF_8),
            )
            archivedLog(
                "<s> timer document ${document.length}B sha256=${sha256Of(document)} mode=fake",
            )
            shizukuRunner.runTimer(document, "fake", selectedManagerPackage(), archivedLog)
        }
        if (code == TimerVerdict.NotStarted) {
            /* Shizuku was not ready / the tool never launched: the boot is
             * clean again (no parked process to hold a page or zombies). */
            runCatching { preferences.edit { remove(PrefTimerAttemptBoot) } }
        }
        code
    }

    override suspend fun probeDirtyfrag(): String = withContext(Dispatchers.IO) {
        val release = System.getProperty("os.version", "").orEmpty()
        val result = DirtyfragProbe.run(appContext, filesDir)
        val clear = result.outcome == DirtyfragProbe.Outcome.Pass ||
            result.outcome == DirtyfragProbe.Outcome.PrimitiveFailed
        val recorded = clear && measuredProfiles.recordDirtyfragProbe(
            release,
            result.outcome,
            Build.FINGERPRINT,
            result.evidence,
        )
        val line = "probe=${result.outcome} reason=${result.reason} " +
            "evidence=${result.evidence} db=$recorded"
        Log.i(DirtyfragProbe.Tag, line)
        line
    }

    override suspend fun preflightDirtyfrag(): String = withContext(Dispatchers.IO) {
        val preflight = DirtyfragPlan.preflight(appContext, File("/system/lib64/libc++.so"))
        val line = "preflight=${if (preflight.feasible) "Feasible" else "Rejected"} " +
            "reasons=${preflight.reasons.joinToString("; ")} details=${preflight.details}"
        Log.i(DirtyfragProbe.Tag, line)
        line
    }

    override suspend fun runExploitWithShizuku(
        config: ProfileConfig,
        onLog: (String) -> Unit,
    ): Int {
        /* The Shizuku service runs as uid 2000 and cannot write the app's run
         * folder; the module sidecar is a direct-entry diagnostic. Its AIDL also
         * carries no vr.ko mode, so that entry runs Auto. */
        return withDebugAttackLog("shizuku", onLog, measure = true) { archivedLog, debugDir, runDir, writeSidecar ->
            archivedLog("<s> resolving profile")
            val release = System.getProperty("os.version", "").orEmpty()
            /* The Shizuku path runs GhostLock only (the order check below
             * refuses a list without it); the document's `mode` carries that
             * resolution so the native's cross-check covers this invocation too. */
            val render = profileController.nativeDocumentWithOverlay(
                config,
                "ghostlock",
                safeModeEnabled,
                measuredFacts(release),
            )
            render?.let { logOverlay(it.overlay, archivedLog, runDir) }
            val profileBlob = render?.bytes
            archivedLog(
                "<s> profile resolved hasProfile=${config.hasProfile} " +
                    "blob=${profileBlob?.size ?: 0} invalid=${config.invalidPaths.size}",
            )
            logProfileLayers(archivedLog, config)
            when {
                !config.hasProfile || profileBlob == null -> {
                    archivedLog("<s> error: profile is unavailable for $release")
                    1
                }

                config.invalidPaths.isNotEmpty() -> {
                    archivedLog(
                        "<s> error: profile has ${config.invalidPaths.size} invalid field(s): " +
                            config.invalidPaths.take(6).joinToString(),
                    )
                    1
                }

                else -> {
                    val order = config.pipelineOrder
                    if ("ghostlock" !in order) {
                        archivedLog(
                            "<s> error: profile pipeline order=${order.joinToString(",")}; " +
                                "the Shizuku path only runs GhostLock (use the direct path)",
                        )
                        return@withDebugAttackLog 1
                    }
                    logProfileDigest(archivedLog, profileBlob)
                    dumpRuntimeProfile(archivedLog, writeSidecar, release, profileBlob)
                    archivedLog("<b> starting UserService")
                    resetRunState()
                    shizukuRunner.run(
                        safeModeEnabled, forceAttackTest, profileBlob, debugDir,
                        selectedManagerPackage(), archivedLog,
                    ) { step, status ->
                        if (status == "disabled") clearRunState() else applyRunStatus(step, status)
                    }
                }
            }
        }
    }

    /* --- attack step status ------------------------------------------------
     * The native process reports each step over stdout and waits for an ACK on
     * stdin. The app persists the status here; a kernel panic leaves the last
     * in_progress step on disk for the next launch to read. */

    private val runStateLock = Any()
    private var runSteps: LinkedHashMap<String, String> = LinkedHashMap()

    private fun persistRunStateLocked() {
        val json = RunStateCodec.encode(runSteps, System.currentTimeMillis())
        runCatching {
            FileOutputStream(File(filesDir, RunStateFileName)).use { out ->
                out.write(json.toByteArray(StandardCharsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }
    }

    private fun resetRunState() = synchronized(runStateLock) {
        runSteps = LinkedHashMap<String, String>().apply {
            RunStateCodec.Steps.forEach { put(it, RunStateCodec.NotStarted) }
        }
        persistRunStateLocked()
    }

    private fun applyRunStatus(step: String, status: String) = synchronized(runStateLock) {
        if (!runSteps.containsKey(step)) return@synchronized
        runSteps[step] = status
        persistRunStateLocked()
    }

    private fun clearRunState() = runCatching { File(filesDir, RunStateFileName).delete() }

    /** Handles one stdout line; true when it was a status event, not a log line. */
    private fun handleStatusLine(line: String, process: AtomicReference<Process?>): Boolean {
        if (!line.startsWith(StatusMarker)) return false
        if (line.contains(StatusDisabled)) {
            clearRunState()
            return true
        }
        val parts = line.removePrefix(StatusMarker).trim().split(' ')
        if (parts.size < 2) return true
        applyRunStatus(parts[0], parts[1])
        runCatching {
            process.get()?.outputStream?.apply {
                write(StatusAck.toByteArray(StandardCharsets.UTF_8))
                flush()
            }
        }
        return true
    }

    override suspend fun lastRunStuckStep(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(filesDir, RunStateFileName)
            if (!file.isFile) return@runCatching null
            RunStateCodec.parseStuckStep(file.readText())
        }.getOrNull()
    }

    private suspend fun withDebugAttackLog(
        entry: String,
        onLog: (String) -> Unit,
        /** Exit codes that mean the pass ended cleanly for its kind (7 is the
         * ambiguity hand-off, not a failure). */
        completedCodes: Set<Int> = setOf(0),
        /**
         * Attack runs record the measured database. Parsing does not: its
         * exit codes are not a chain completion, and it has no root phase.
         */
        measure: Boolean = false,
        run: suspend ((String) -> Unit, String?, String?, (String, ByteArray) -> Boolean) -> Int,
    ): Int {
        val settings = debugSettings()
        /* Deleted before the run, so a capture present afterwards proves this
         * run's root phase wrote it, not a previous boot's. */
        if (measure) runCatching { File(filesDir, SlabinfoCaptureFileName).delete() }
        /* Unconditional: a run with no log is a run nobody can report, and the
         * user who most needs one is the one whose device just died. */
        val archive = DebugAttackLog.open(appContext, entry)
        if (archive == null) {
            onLog("<k> warning: cannot create the run log")
            return run(onLog, null, null) { _, _ -> false }
        }
        /* Every archived line carries a run-relative timestamp. The native's
         * own `[T+ms]` clock starts at the native process; the app-owned phases
         * (ksud staging, profile load, the DirtyFrag stage, the root-script
         * tailer) need the same run-relative scale or the full chain cannot be
         * measured. The UI copy stays unprefixed. */
        val runStartMs = SystemClock.elapsedRealtime()
        val archivedLog: (String) -> Unit = { line ->
            val elapsed = SystemClock.elapsedRealtime() - runStartMs
            runCatching { archive.append("[A+${elapsed}ms] $line") }
            onLog(line)
        }
        val writeSidecar: (String, ByteArray) -> Boolean = { name, bytes ->
            runCatching { archive.writeSidecar(name, bytes) }.getOrDefault(false)
        }
        return try {
            /* First line of every archived run log: which app build produced it. */
            archivedLog(
                "<k> Sesame ${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE} " +
                    "build ${BuildInfo.BUILD_TIME_LABEL}",
            )
            archivedLog(
                "<k> run log: ${archive.displayName} (retained on the device; " +
                    "export it from the log screen)",
            )
            archivedLog("<k> debug dump dir: ${archive.folderFile.absolutePath}")
            val code = run(
                archivedLog,
                if (settings.kernelLogEnabled) archive.folderFile.absolutePath else null,
                archive.folderFile.absolutePath,
                writeSidecar,
            )
            if (measure && code in completedCodes) {
                runCatching { ingestMeasuredFacts(writeSidecar, archivedLog) }
            }
            /* Recorded before the descriptor closes: a run that ends without a
             * terminal state reads as interrupted at the next start. */
            archive.finish(if (code in completedCodes) RunLogState.COMPLETED else RunLogState.FAILED)
            code
        } finally {
            runCatching { archive.close() }
        }
    }

    /**
     * Records the root phase's `/proc/slabinfo` measurement after a completed
     * run. The capture is the only root-written measurement text, so it is also
     * copied into the run folder as evidence. A capture that does not parse is
     * logged and dropped — the database only takes facts it can name.
     */
    private fun ingestMeasuredFacts(
        writeSidecar: (String, ByteArray) -> Boolean,
        log: (String) -> Unit,
    ) {
        val capture = File(filesDir, SlabinfoCaptureFileName)
        if (!capture.isFile) {
            log("<k> measured: no root slabinfo capture")
            return
        }
        val raw = runCatching { capture.readText(StandardCharsets.UTF_8) }.getOrNull()
        if (raw.isNullOrEmpty()) return
        val release = System.getProperty("os.version", "").orEmpty()
        val fact = MeasuredProfileStore.parseSlabinfo(release, raw)
        if (fact == null) {
            log("<k> measured: slabinfo capture did not parse for $release")
            return
        }
        val recorded = measuredProfiles.record(release, fact)
        if (recorded) {
            writeSidecar("slabinfo-mm_struct.txt", raw.toByteArray(StandardCharsets.UTF_8))
        }
        log(
            if (recorded) {
                "<k> measured: ${fact.key}=${fact.value} recorded (${fact.source})"
            } else {
                "<k> warning: measured database refused the write"
            },
        )
    }

    /**
     * States which profile layers this run used and which were skipped, and why
     * (PORT-PLAN Phase 8 §C.10-11). A skipped layer is the difference between
     * "the port is broken" and "what you imported is not being applied", which
     * is exactly the distinction a log has to make from someone else's phone.
     */
    private fun logProfileLayers(log: (String) -> Unit, config: ProfileConfig) {
        val layers = config.layers ?: return
        log("<k> profile layers: ${layers.describe()}")
    }

    /**
     * The document's identity: its sha256, quoted in the run log and matching
     * the `profile.json` sidecar byte for byte, so a device failure is
     * replayable host-side.
     */
    private fun logProfileDigest(log: (String) -> Unit, blob: ByteArray) {
        val text = String(blob, StandardCharsets.UTF_8)
        log("<k> profile document ${blob.size}B sha256=${sha256Of(text)}")
    }

    /**
     * Writes the exact document handed to the backend into the attempt folder
     * (`profile.json`). No-op when debug export is disabled.
     */
    private fun dumpRuntimeProfile(
        log: (String) -> Unit,
        writeSidecar: (String, ByteArray) -> Boolean,
        release: String,
        blob: ByteArray?,
    ) {
        val written = blob?.let { writeSidecar("profile.json", it) } ?: false
        if (written) log("<k> runtime profile written: profile.json ($release)")
    }

    override fun requestShizukuPermission() = shizukuRunner.requestPermission()

    override fun setShizukuStatusListener(listener: (() -> Unit)?) =
        shizukuRunner.setStatusListener(listener)

    /* Second way (DirtyFrag hybrid): the W1-only native run has left SELinux
     * permissive. Create the IpSec transport transform, compute the page-cache
     * plan (the app owns every IV), and run the native stage that patches
     * libc++, triggers init, runs the root script over the init shell and
     * restores the blocks. */
    /**
     * The module chain's staged manager: the root script installs Magisk from
     * this directory. Only the profile-driven pair supplies it — there is no
     * marker file, so a W1 run never inherits a Magisk handoff. The module
     * itself is loaded by the init stub, not by the script, so no module path
     * is exported here (the stage reports the delivered target from its plan).
     */
    private fun handoffEnv(magiskBin: File?): Map<String, String> =
        if (magiskBin == null) {
            emptyMap()
        } else {
            mapOf(
                "GHOSTLOCK_HANDOFF" to "magisk",
                "GHOSTLOCK_MAGISK_BIN" to magiskBin.absolutePath,
            )
        }

    /** One resolved module-chain run: the staged pair and the staged manager. */
    private data class ModuleRun(
        val moduleFile: File,
        val vendorFiles: List<String>,
        val magiskBin: File,
    )

    private val crashDumpPath = "/apex/com.android.runtime/bin/crash_dump64"
    private val koTargets = listOf(
        "/vendor/lib64/libbinderdebug.so",
        "/vendor/lib64/libstagefrighthw.so",
        "/vendor/lib64/libstagefright_aidl_bufferpool2.so",
    )

    /** One native stage invocation (aux-install or the full run). */
    private suspend fun runStage(
        plan: File,
        mode: String,
        onLog: (String) -> Unit,
        magiskBin: File? = null,
        /** Per-run root-script log; absent keeps the legacy shared file. */
        ksuLog: File? = null,
    ): Int {
        val binary = File(appContext.applicationInfo.nativeLibraryDir, NativeBinaryName)
        val command = ProcessBuilder(
            binary.absolutePath,
            "--dirtyfrag",
            plan.absolutePath,
            "--dirtyfrag-mode",
            mode,
        )
            .directory(filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["GHOSTLOCK_HOME"] = filesDir.absolutePath
                environment()["TMPDIR"] = filesDir.absolutePath
                environment()["HOME"] = filesDir.absolutePath
                /* The root script and its tail must be this stage's own; the
                 * shared default file let a failed run show a previous run's
                 * script lines (the same-named file is appended by every
                 * DirtyFrag stage). */
                if (ksuLog != null) {
                    environment()["GHOSTLOCK_KSU_LOG"] = ksuLog.absolutePath
                }
                environment().putAll(handoffEnv(magiskBin))
            }
        val code = runProcess(command, onLog = onLog, captureOutput = true, timeoutSeconds = 120L)
        onLog("<b> second way: dirtyfrag mode=$mode exited code=$code")
        return code
    }

    /** A bridge read of a vendor_file target, after the aux-install run put the
     * helper into crash_dump64's page cache. The exec runs in crash_dump. */
    private suspend fun readBridge(len: Int, path: String, onLog: (String) -> Unit): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val process = ProcessBuilder(crashDumpPath, "0", path, "r", len.toString())
                    .redirectErrorStream(false)
                    .start()
                val data = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = process.inputStream.read(data, read, len - read)
                    if (n <= 0) break
                    read += n
                }
                process.waitFor()
                if (read == len) data else null
            }.onFailure {
                onLog("<-> bridge read: ${it::class.simpleName}: ${it.message}")
            }.getOrNull()
        }

    private suspend fun runDirtyfragStage(
        onLog: (String) -> Unit,
        debugDir: String?,
        run: ModuleRun? = null,
    ): Int {
        /* One log per stage run: the script appends, so a shared file made a
         * failed run read as if it had a root phase (the previous run's
         * lines were still in it). */
        val stageKsuLog = File(filesDir, ksuLogName(System.currentTimeMillis()))
        val ipsec = appContext.getSystemService(Context.IPSEC_SERVICE) as? IpSecManager
            ?: run {
                onLog("<-> second way: IpSecManager unavailable")
                return 1
            }
        val loopback = InetAddress.getByName("127.0.0.1")
        val encap = ipsec.openUdpEncapsulationSocket()
        val spiObj = ipsec.allocateSecurityParameterIndex(loopback)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val senderProbe = DatagramSocket()
        val senderPort = senderProbe.localPort
        senderProbe.close()
        val transform = IpSecTransform.Builder(appContext)
            .setEncryption(IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, key))
            .setIpv4Encapsulation(encap, senderPort)
            .buildTransportModeTransform(loopback, spiObj)
        try {
            val random = SecureRandom()
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            val socketName = buildString {
                repeat(8) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val lib = File("/system/lib64/libc++.so")
            /* Module pipeline: the module goes into a vendor_file target through
             * the crash_dump bridge, and the stub insmods that target. The
             * bundled profile supplies the staged asset and the ROM vendor list;
             * there is no marker or file fallback. The W1 pipelines keep the
             * single direct plan (koTarget empty, no aux). */
            val moduleMode = run != null
            val moduleVendorCandidates =
                run?.vendorFiles?.takeIf { it.isNotEmpty() } ?: koTargets
            var koTarget: String? = null
            var modulePadded: ByteArray? = null
            var helper: ByteArray? = null
            if (run != null) {
                koTarget = moduleVendorCandidates.firstOrNull { File(it).exists() }
                if (koTarget == null) {
                    onLog("<-> second way: no vendor_file target for the module")
                    return 1
                }
                helper = appContext.assets.open(DirtyfragPlan.BRIDGE_ASSET).use { it.readBytes() }
                val raw = run.moduleFile.readBytes()
                val len = ((raw.size + 15) / 16) * 16
                modulePadded = ByteArray(len).also { raw.copyInto(it) }
                val installPlan = DirtyfragPlan.build(
                    appContext,
                    lib,
                    key,
                    spiObj.spi,
                    senderPort,
                    encap.port,
                    socketName,
                    File(filesDir, "dirtyfrag.install.plan"),
                    auxHost = File(crashDumpPath),
                    auxHelper = helper,
                )
                val installCode =
                    runStage(installPlan, "aux-install", onLog, run.magiskBin, stageKsuLog)
                onLog("<k> second way: bridge install code=$installCode")
                if (installCode != 0) return 1
            }
            var vendorEntries: List<Pair<Long, ByteArray>> = emptyList()
            var vendorLen = 0
            if (moduleMode) {
                val target = koTarget!!
                val len = modulePadded!!.size
                val old = readBridge(len, target, onLog)
                if (old == null) {
                    onLog("<-> second way: bridge read of $target failed")
                    return 1
                }
                vendorLen = len
                vendorEntries = (0 until len step 16).map { off ->
                    val current = old.copyOfRange(off, off + 16)
                    val desired = modulePadded.copyOfRange(off, off + 16)
                    off.toLong() to DirtyfragPlan.ivFor(key, current, desired)
                }
                onLog("<k> second way: module ${len} B -> $target (${vendorEntries.size} blocks)")
            }
            val plan = DirtyfragPlan.build(
                appContext,
                lib,
                key,
                spiObj.spi,
                senderPort,
                encap.port,
                socketName,
                File(filesDir, "dirtyfrag.plan"),
                koTarget = koTarget ?: "",
                auxHost = if (moduleMode) File(crashDumpPath) else null,
                auxHelper = helper,
                auxInstalled = moduleMode,
                vendorPath = koTarget,
                vendorLen = vendorLen,
                vendorEntries = vendorEntries,
            )
            onLog("<k> second way: plan ${plan.name} socket=@$socketName module=${koTarget ?: "none"}")
            /* Debug-only diagnostic selector: a `dirtyfrag.mode` file in the
             * app's files dir asks for noshell / noscript / laterestore. It is
             * read in debug builds only; release always runs the product path. */
            val modeText = if (BuildConfig.DEBUG) {
                runCatching { File(filesDir, "dirtyfrag.mode").readText() }.getOrDefault("")
            } else {
                ""
            }
            val dirtyfragMode = when {
                modeText.contains("noshell") -> "noshell"
                modeText.contains("noscript") -> "noscript"
                modeText.contains("laterestore") -> "laterestore"
                else -> "full"
            }
            onLog("<k> second way: mode=$dirtyfragMode")
            return runStage(plan, dirtyfragMode, onLog, run?.magiskBin, stageKsuLog)
        } finally {
            runCatching { transform.close() }
            runCatching { spiObj.close() }
            runCatching { encap.close() }
        }
    }

    private suspend fun runExploitBinary(
        config: ProfileConfig,
        binaryName: String,
        onLog: (String) -> Unit,
        debugDir: String?,
        runDir: String?,
        writeSidecar: (String, ByteArray) -> Boolean,
    ): Int {
        val workDir = filesDir
        return try {
            val binary = File(appContext.applicationInfo.nativeLibraryDir, binaryName)
            require(binary.isFile) { "missing native binary: ${binary.absolutePath}" }
            /* A module-only profile hands off to Magisk, not KernelSU: its
             * chain never runs the native W1 or the ksud late-load, so a
             * device without a KernelSU manager is not refused. A hybrid order
             * (module plus a W1 entry) still stages the manager. */
            val moduleOnly = config.module != null &&
                config.pipelineOrder.isNotEmpty() &&
                config.pipelineOrder.all { it == "module" }
            if (!moduleOnly && !stageKsud(workDir, onLog)) return 1
            // U01-S14: a per-run KernelSU log path so a previous run's markers
            // can never satisfy the handoff probe; passed to the native process.
            val ksuLog = File(workDir, ksuLogName(System.currentTimeMillis()))
            val nativeLog = File(workDir, NativeLogFileName)
            nativeLog.writeText("")
            val release = System.getProperty("os.version", "").orEmpty()
            onLog(
                "<k> profile: hasProfile=${config.hasProfile} " +
                    "invalid=${config.invalidPaths.size} release=$release",
            )
            logProfileLayers(onLog, config)
            if (!config.hasProfile) {
                val reason = config.layers?.externalError?.let { " ($it)" } ?: ""
                error(
                    "no profile resolved for $release$reason; select a profile file, " +
                        "or pick a built-in release",
                )
            }
            if (config.invalidPaths.isNotEmpty()) {
                error(
                    "profile has ${config.invalidPaths.size} invalid field(s): " +
                        config.invalidPaths.take(6).joinToString(),
                )
            }
            /* The document's ladder is resolved before W1. GhostLock is viable
             * on its static verdict (the validator refuses a KDP document whose
             * ladder contains it). DirtyFrag needs the ROM preflight and the
             * primitive probe (or a matching measured-DB record). No fallback
             * after W1; a failed stage is restore/reboot. */
            var moduleRun: ModuleRun? = null
            val pipeline = run {
                val order = config.pipelineOrder
                /* The DirtyFrag box: off trims the derived ladder to GhostLock
                 * for this run; the document keeps the capability. */
                val effectiveOrder = if (config.dirtyFragSelectable && !config.dirtyFragOn) {
                    onLog("<k> pipeline: dirtyfrag off (GhostLock only)")
                    order.filter { it != "dirtyfrag" }.ifEmpty { listOf("ghostlock") }
                } else {
                    order
                }
                val decision = PipelineResolver.resolve(
                    effectiveOrder,
                    ghostlock = { null },
                    dirtyfrag = {
                        val rom = DirtyfragPlan.preflight(
                            appContext,
                            File("/system/lib64/libc++.so"),
                        )
                        if (!rom.feasible) {
                            "ROM preflight: " + rom.reasons.joinToString("; ")
                        } else {
                            when (val reuse = measuredProfiles.probeReuse(release, Build.FINGERPRINT)) {
                                ProbeReuse.Pass -> {
                                    onLog(
                                        "<k> probe=db pass (protocol " +
                                            DirtyfragProbe.ProtocolVersion + ")",
                                    )
                                    null
                                }
                                ProbeReuse.PrimitiveFailed ->
                                    "measured DB says the primitive does not land"
                                ProbeReuse.None -> {
                                    val result = DirtyfragProbe.run(appContext, filesDir)
                                    val clear =
                                        result.outcome == DirtyfragProbe.Outcome.Pass ||
                                            result.outcome == DirtyfragProbe.Outcome.PrimitiveFailed
                                    if (clear) {
                                        measuredProfiles.recordDirtyfragProbe(
                                            release,
                                            result.outcome,
                                            Build.FINGERPRINT,
                                            result.evidence,
                                        )
                                    }
                                    when (result.outcome) {
                                        DirtyfragProbe.Outcome.Pass -> {
                                            onLog("<k> probe=pass ${result.evidence}")
                                            null
                                        }
                                        else -> "probe ${result.outcome}: ${result.reason}"
                                    }
                                }
                            }
                        }
                    },
                    module = {
                        val spec = config.module
                        when {
                            spec == null -> "module pipeline is bundled-only"
                            config.layers?.bundledAuthoritative != true ->
                                "module pipeline is bundled-only " +
                                    "(the bundled profile is not authoritative)"
                            else -> {
                                /* The module chain writes the larger stub (it
                                 * carries the insmod prologue), so it is
                                 * measured against that blob. */
                                val rom = DirtyfragPlan.preflight(
                                    appContext,
                                    File("/system/lib64/libc++.so"),
                                    moduleMode = true,
                                )
                                if (!rom.feasible) {
                                    "ROM preflight: " + rom.reasons.joinToString("; ")
                                } else {
                                    val staged = BundledModule.stage(
                                        appContext,
                                        spec,
                                        File(filesDir, "modules"),
                                    )
                                    if (staged == null) {
                                        "module asset missing or asset_sha256 mismatch"
                                    } else {
                                        val magisk = MagiskStage.stage(
                                            appContext,
                                            File(filesDir, "magiskbin"),
                                        )
                                        if (magisk == null) {
                                            "Magisk manager is not installed or cannot be staged"
                                        } else {
                                            onLog(
                                                "<k> module pair: ${spec.asset} " +
                                                    "(sha256 ok), magisk=${magisk.packageName}",
                                            )
                                            moduleRun = ModuleRun(
                                                moduleFile = staged,
                                                vendorFiles = spec.vendorFiles,
                                                magiskBin = magisk.dir,
                                            )
                                            null
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
                val selected = decision.pipeline
                    ?: error(
                        "no viable pipeline: " +
                            decision.rejected.joinToString("; ").ifEmpty { "empty order" },
                    )
                if (decision.rejected.isNotEmpty()) {
                    onLog("<k> pipeline fallback: ${decision.rejected.joinToString("; ")}")
                }
                onLog("<k> pipeline=$selected (profile order)")
                selected
            }
            /* The module chain has no W1: the module itself opens the permissive
             * window (the root script loads it first). Run the DirtyFrag stage
             * directly — no native W1 invocation and no wire document. */
            if (pipeline == "module") {
                onLog("<b> pipeline=module: no W1; starting the DirtyFrag stage")
                resetRunState()
                val run = moduleRun ?: error("module pipeline selected without a staged pair")
                val moduleCode = runDirtyfragStage(onLog, debugDir, run)
                onLog("<b> pipeline=module: stage exited code=$moduleCode")
                return moduleCode
            }
            val render = profileController.nativeDocumentWithOverlay(
                config,
                pipeline,
                safeModeEnabled,
                measuredFacts(release),
            ) ?: error("profile is unavailable for $release")
            logOverlay(render.overlay, onLog, runDir)
            val profileBlob = render.bytes
            logProfileDigest(onLog, profileBlob)
            dumpRuntimeProfile(onLog, writeSidecar, release, profileBlob)
            val ksuOffset = AtomicLong()
            val nativeOffset = AtomicLong()
            val processRef = AtomicReference<Process?>(null)
            // tag root-script lines so they cannot be read as the native stages'
            val ksuSink: (String) -> Unit = { onLog("[ksu] $it") }
            // status events are consumed here (persisted + ACKed); plain lines are logs
            val routeCpus = java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
            val nativeSink: (String) -> Unit = { line ->
                if (!handleStatusLine(line, processRef)) onLog(line)
                val cpus = routeCpusFromLine(line)
                if (cpus.isNotEmpty() && routeCpus.addAll(cpus)) {
                    val pick = pinTailerOutside(routeCpus)
                    if (pick != null) {
                        onLog("<k> log fsync thread pinned to cpu=$pick (avoiding $routeCpus)")
                    }
                }
            }
            val tailer = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        tailKsuLog(nativeLog, nativeOffset, nativeSink)
                        tailKsuLog(ksuLog, ksuOffset, ksuSink)
                        Thread.sleep(200)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }.apply {
                name = "ksu-log-tailer"
                isDaemon = true
                start()
            }
            val argv = mutableListOf(
                binary.absolutePath,
                "--ghostlock-app-call",
                "--enable-status-record",
            )
            if (forceAttackTest) {
                argv += "--force-attack"
            }
            if (pipeline == "dirtyfrag") {
                /* Stop after W1 (SELinux permissive) and run the DirtyFrag stage. */
                argv += "--w1-only"
            }
            if (!debugDir.isNullOrEmpty()) {
                argv += listOf("--dump-kernel-log", debugDir)
            }
            val command = ProcessBuilder(argv)
                .directory(workDir)
                .redirectErrorStream(true)
                .redirectOutput(nativeLog)
                .apply {
                    environment()["GHOSTLOCK_HOME"] = workDir.absolutePath
                    environment()["TMPDIR"] = workDir.absolutePath
                    environment()["HOME"] = workDir.absolutePath
                    environment()["GHOSTLOCK_KSU_LOG"] = ksuLog.absolutePath
                    /* The user-authoritative vr.ko choices; the native resolves Auto. */
                    environment()["GHOSTLOCK_VRKO_TAG_CLEAR"] = vrKoTagClear.wire
                    environment()["GHOSTLOCK_VRKO_SELINUX_BLOCK"] = vrKoSelinuxBlock.wire
                    /* The run folder the exporter zips whole: the native writes
                     * the vr.ko diagnostic sidecar next to the log. */
                    if (!runDir.isNullOrEmpty()) {
                        environment()["GHOSTLOCK_RUN_DIR"] = runDir
                    }
                    /* Only when it is provable: a missing anchor state file is
                     * then known to mean "no run since the reboot" instead of
                     * "cannot tell", which is what let a clean install never
                     * run at all. See InstallClock. */
                    if (InstallClock.installPredatesBoot(appContext)) {
                        environment()["GHOSTLOCK_ANCHOR_TRUSTED"] = "1"
                    }
                }
            onLog("<b> starting native: ${binary.absolutePath}")
            resetRunState()
            try {
                val nativeCode = runProcess(
                    command,
                    onLog = {},
                    captureOutput = false,
                    stdin = profileBlob,
                    frameStdin = true,
                    onProcess = { processRef.set(it) },
                )
                onLog("<b> native exited code=$nativeCode")
                if (pipeline == "dirtyfrag" && nativeCode == 0) {
                    onLog("<b> pipeline=dirtyfrag: W1 done; starting the DirtyFrag stage")
                    runDirtyfragStage(onLog, debugDir)
                } else {
                    nativeCode
                }
            } finally {
                withContext(Dispatchers.IO) {
                    tailer.interrupt()
                    tailer.join(1000)
                    tailKsuLog(nativeLog, nativeOffset, onLog)
                    tailKsuLog(ksuLog, ksuOffset, ksuSink)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onLog("<-> error: ${error::class.simpleName}: ${error.message}")
            1
        }
    }

    override suspend fun readDocument(uri: String): String =
        appContext.contentResolver.openInputStream(uri.toUri())?.bufferedReader()?.use { it.readText() } ?: throw IOException("cannot open $uri")

    override suspend fun cacheDocument(uri: String, fileName: String): String {
        val target = File(filesDir, fileName)
        appContext.contentResolver.openInputStream(uri.toUri())?.use { input ->
            target.outputStream().use(input::copyTo)
        } ?: throw IOException("cannot open $uri")
        return target.absolutePath
    }

    /* ---- profile sources (import-copy storage model) ---- */

    override fun importedProfileExists(): Boolean = profileController.importedProfileExists()

    override suspend fun selectExternalProfile(uri: String): ProfileImportResult =
        withContext(Dispatchers.IO) {
            val release = deviceRelease()
            runCatching {
                profileController.selectExternalProfile(uri, release)
            }.getOrElse {
                ProfileImportResult.Rejected(
                    listOf(it.message ?: "the profile could not be imported"),
                )
            }
        }

    override suspend fun clearExternalProfile(): ProfileConfig = withContext(Dispatchers.IO) {
        profileController.clearExternalProfile(deviceRelease())
    }

    override fun liveProfileDocument(): String? {
        val release = deviceRelease()
        return profileController.renderResolved(release)
    }

    override suspend fun writeProfileDocument(uri: String, text: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = appContext.contentResolver
                val target = uri.toUri()
                /* Some DocumentsProviders reject the "wt" mode even though the
                 * file was created; "w" truncates too, so try it first. */
                val stream = runCatching { resolver.openOutputStream(target, "w") }.getOrNull()
                    ?: resolver.openOutputStream(target, "wt")
                    ?: return@runCatching false
                stream.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
                true
            }.onFailure { Log.w("GhostLock", "profile write failed: $uri", it) }
                .getOrDefault(false)
        }

    /* ---- editing session: in-memory, export-only ---- */

    private var editSession: AndroidProfileConfigController? = null
    private var editSessionTargetName: String? = null
    private var editSessionTargetRelease: String? = null

    override suspend fun beginEditSession(): ProfileConfig? = withContext(Dispatchers.IO) {
        endEditSession()
        val release = deviceRelease()
        val live = runCatching { profileController.load(release) }.getOrNull()
        if (live?.hasProfile != true) return@withContext null
        val session = AndroidProfileConfigController(
            context = appContext,
            externalProfiles = externalProfileStore,
            preferences = preferences,
            forcedSelection = profileController.currentSelection(release),
        )
        editSession = session
        editSessionTargetName = profileController.currentSelectionLabel(release)
        editSessionTargetRelease = release
        runCatching { session.load(release) }.getOrNull()
    }

    override fun editSessionController(): ProfileConfigController? = editSession

    override fun editSessionTarget(): String? = editSessionTargetName

    override fun editSessionRelease(): String? = editSessionTargetRelease

    override fun editSessionDocument(): String? {
        val session = editSession ?: return null
        val release = editSessionTargetRelease ?: return null
        return session.renderResolved(release)
    }

    override fun editSessionDiff(): List<ProfileDiffRow> {
        val session = editSession ?: return emptyList()
        val release = editSessionTargetRelease ?: return emptyList()
        return session.diffAgainstBundle(release)
    }

    override suspend fun rebaseEditSession(): ProfileConfig? = withContext(Dispatchers.IO) {
        val session = editSession ?: return@withContext null
        val release = editSessionTargetRelease ?: return@withContext null
        val config = runCatching { session.rebaseToBundle(release) }.getOrNull()
        if (config != null) editSessionTargetName = session.currentSelectionLabel(release)
        config
    }

    override fun endEditSession() {
        editSession = null
        editSessionTargetName = null
        editSessionTargetRelease = null
    }

    /* ---- measured overlay (plan decision 13, §3.3) ---- */

    /** The measured DB's overlay facts for [release]; only the declared set. */
    private fun measuredFacts(release: String): List<OverlayFact> =
        measuredProfiles.kernel(release)?.profileFacts().orEmpty()
            .map { OverlayFact(path = it.key, value = it.value, source = it.source) }

    /**
     * Logs every fact the overlay applied (document vs effective, with the
     * source) and records the same provenance in the run-store meta. A
     * disagreement is an extractor defect and gets its own warning.
     */
    private fun logOverlay(records: List<OverlayRecord>, log: (String) -> Unit, runDir: String?) {
        if (records.isEmpty()) return
        for (record in records) {
            log(
                "<k> overlay: ${record.path} document=${record.documentValue ?: "null"} " +
                    "effective=${record.effectiveValue} (${record.source})",
            )
            if (record.disagrees) {
                log(
                    "<k> warning: ${record.path} measured ${record.effectiveValue} " +
                        "disagrees with the document's ${record.documentValue}",
                )
            }
        }
        val line = records.joinToString("; ") {
            "${it.path}:${it.documentValue ?: "null"}->${it.effectiveValue}(${it.source})"
        }
        runDir?.let { RunLogStore.appendMeta(File(it), "overlay=$line") }
    }

    /* ---- measured database merge ---- */

    override suspend fun measurementMergeDiff(): List<MeasurementMergeRow> =
        withContext(Dispatchers.IO) {
            val release = deviceRelease()
            val numbers = profileController.resolvedNumbers(release)
                ?: return@withContext emptyList()
            /* Only profile-addressable facts: the DirtyFrag probe fact is a
             * runtime decision, and merging it would poison the document. */
            measuredProfiles.kernel(release)?.profileFacts().orEmpty().map { fact ->
                MeasurementMergeRow(
                    path = fact.key,
                    profileValue = numbers[fact.key],
                    measuredValue = fact.value,
                    source = fact.source,
                    evidence = fact.evidence,
                    measuredAt = fact.measuredAt,
                )
            }.filter { it.profileValue != it.measuredValue }
        }

    override fun mergedProfileDocument(): String? {
        val release = deviceRelease()
        val facts = measuredProfiles.kernel(release)?.profileFacts().orEmpty()
        if (facts.isEmpty()) return null
        return profileController.renderMerged(
            release,
            facts.associate { it.key to it.value },
        )
    }

    private fun deviceRelease(): String = System.getProperty("os.version", "").orEmpty()

    override fun close() {
        shizukuRunner.close()
        synchronized(processes) {
            processes.forEach(Process::destroyForcibly)
            processes.clear()
        }
    }

    /**
     * Parse helper: reads HOCON or JSON text into a list of entries.
     */
    private fun parseEntries(text: String): ValueList? {
        if (text.isBlank()) return null
        return try {
            when (val value = HoconSupport.parseValue(text)) {
                is List<*> -> value.asValueList()
                is Map<*, *> -> ValueList().apply { value.asValueMap()?.let(::add) }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fields a parsed profile still lacks that only an xbl_config FDT or uefi
     * memory map can fill. Used to prompt for the optional sidecars.
     */
    /** Resolve a schema-v3 per-device pair for the running device, if present. */
    private fun resolvedForDevice(entry: ValueMap, candidates: List<String>): ValueMap =
        runCatching { PerDevicePhys.resolveFor(entry, candidates).document }.getOrDefault(entry)

    private fun missingSidecarFields(entries: List<ValueMap>): Set<String> =
        if (entries.isNotEmpty() && entries.all { ProfileResolver.physOf(it).first == null }) {
            val device = entries.firstOrNull()?.get("devices").asValueMap()?.keys?.firstOrNull()
            setOf(if (device != null) "devices.$device.phys.load" else "devices.*.phys.load")
        } else {
            emptySet()
        }

    private fun parsedDocumentName(releases: List<String>): String {
        val stem = releases.firstOrNull().orEmpty()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifEmpty { "parsed" }
        return "$stem.conf"
    }

    private fun freshReleases(entries: List<*>): List<String> =
        entries.mapNotNull { (it.asValueMap()?.get("release") as? String) }.distinct()

    private fun restoreShizukuPreference() {
        val prefs = appContext.getSharedPreferences("ghostlock_prefs", Context.MODE_PRIVATE)
        shizukuEnabled = prefs.getBoolean("shizuku_enabled", false)
    }

    private fun restoreForceAttackTest() {
        forceAttackTest = preferences.getBoolean(PrefForceAttackTest, false)
    }

    private fun restoreVrKoTagClear() {
        vrKoTagClear = readVrKoMode(PrefVrKoTagClear)
    }

    private fun restoreVrKoSelinuxBlock() {
        vrKoSelinuxBlock = readVrKoMode(PrefVrKoSelinuxBlock)
    }

    /** Reads a tri-state preference (`auto`/`on`/`off`). */
    private fun readVrKoMode(key: String): VrKoMode =
        when (val raw = preferences.all[key]) {
            is String -> VrKoMode.fromWire(raw)
            else -> VrKoMode.AUTO
        }

    private fun readSysFile(path: String): String = File(path).takeIf { it.isFile }?.useLines { it.firstOrNull()?.trim().orEmpty() } ?: ""

    @SuppressLint("PrivateApi")
    private fun systemProperty(key: String): String = try {
        val properties = Class.forName("android.os.SystemProperties")
        properties.getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    } catch (_: Throwable) {
        ""
    }

    private fun validDeviceName(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && !it.contains("unknown", true) && !it.contains("null", true) }

    private fun resolveDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val marketName = when (manufacturer.lowercase(Locale.ROOT)) {
            "xiaomi" -> firstValidProperty("ro.product.marketname")
            "oppo", "oneplus", "realme", "oplus" -> {
                val cn = Locale.getDefault().country.equals("CN", true)
                firstValidProperty(
                    *(if (cn) arrayOf(
                        "ro.vendor.oplus.market.name", "ro.vendor.oplus.market.enname"
                    ) else arrayOf("ro.vendor.oplus.market.enname", "ro.vendor.oplus.market.name"))
                )
            }

            "vivo" -> firstValidProperty("ro.vivo.market.name")
            "honor", "huawei" -> firstValidProperty("ro.config.marketing_name")
            "zte", "nubia" -> firstValidProperty("ro.vendor.product.ztename")
            else -> null
        }
        return marketName ?: listOfNotNull(
            manufacturer, Build.BRAND.orEmpty().takeIf { !it.equals(manufacturer, true) }, Build.MODEL.orEmpty()
        ).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun resolveSocName(): String = listOf(
        systemProperty("ro.soc.manufacturer"),
        systemProperty("ro.soc.model"),
    ).mapNotNull(::validDeviceName).joinToString(" ").ifBlank { "unknown" }

    private fun firstValidProperty(vararg keys: String): String? = keys.asSequence().firstNotNullOfOrNull { validDeviceName(systemProperty(it)) }

    /**
     * Find a late-load-capable ksud and stage it into [workDir]/ksud.
     *
     * Returns false when no suitable manager exists; the caller must then
     * refuse to start the exploit (and say why in the run log).
     */
    private suspend fun stageKsud(workDir: File, onLog: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val preflight = preflightManager.also { preflightManager = null }
        val chosen = if (preflight != null &&
            SystemClock.elapsedRealtime() - preflight.first <= PreflightManagerMaxAgeMs &&
            File(preflight.second.path).isFile
        ) {
            /* The pre-flight just ran the same scan; reuse its choice instead of
             * paying the package/ELF scan twice per run. The freshness and
             * existence checks above fall back to a fresh scan. */
            onLog(
                "<k> ksud: using pre-flight selection ${preflight.second.packageName} " +
                    "${preflight.second.path} [${preflight.second.verdict}]",
            )
            preflight.second
        } else {
            val candidates = runCatching { ManagerScanner.scan(appContext) }.getOrElse {
                onLog("<k> error: ksud scan failed: ${it.message}")
                return@withContext false
            }
            val suitable = candidates.filter { it.suitable }
            onLog("<k> ksud scan: ${candidates.size} candidate(s), ${suitable.size} suitable")
            candidates.forEach {
                onLog("<k>   ${it.packageName}: ${it.path} [${it.verdict}]")
            }
            val resolved = ManagerScanner.resolve(candidates, selectedManagerPackage())
            if (resolved == null) {
                onLog("<k> error: no suitable ksud found; refusing to start the exploit")
                return@withContext false
            }
            if (suitable.size > 1) {
                onLog(
                    "<k> ksud: ${suitable.size} suitable; using ${resolved.packageName} " +
                        "(Advanced -> Root manager to change)",
                )
            }
            resolved
        }
        val output = File(workDir, "ksud")
        runCatching {
            output.delete()
            File(chosen.path).inputStream().use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
            Os.chmod(output.absolutePath, 448)
        }.onFailure {
            onLog("<k> error: cannot stage ksud from ${chosen.path}: ${it.message}")
            return@withContext false
        }
        onLog("<k> ksud selected: ${chosen.packageName} ${chosen.path} [${chosen.verdict}]")
        true
    }

    private suspend fun runProcess(
        builder: ProcessBuilder,
        onLog: (String) -> Unit = {},
        timeoutSeconds: Long = 300,
        captureOutput: Boolean = true,
        stdin: ByteArray? = null,
        frameStdin: Boolean = false,
        onProcess: ((Process) -> Unit)? = null,
    ): Int = runInterruptible(Dispatchers.IO) {
        val process = builder.start()
        synchronized(processes) { processes += process }
        if (stdin != null) {
            if (frameStdin) {
                /* 4-byte big-endian length + payload, stdin kept open for the
                 * status-record ACK channel. */
                runCatching {
                    val out = process.outputStream
                    val length = stdin.size
                    out.write(
                        byteArrayOf(
                            (length ushr 24).toByte(),
                            (length ushr 16).toByte(),
                            (length ushr 8).toByte(),
                            length.toByte(),
                        ),
                    )
                    out.write(stdin)
                    out.flush()
                }
            } else {
                runCatching { process.outputStream.use { it.write(stdin) } }
            }
        }
        onProcess?.invoke(process)
        val reader = if (captureOutput) Thread {
            try {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines -> lines.forEach(onLog) }
            } catch (_: IOException) {
            }
        }.apply {
            name = "process-output-reader"
            isDaemon = true
        } else null
        try {
            reader?.start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            reader?.let(::joinReader)
            if (finished) process.exitValue() else -1
        } finally {
            if (process.isAlive) process.destroyForcibly()
            reader?.interrupt()
            runCatching { process.inputStream.close() }
            reader?.let(::joinReader)
            synchronized(processes) { processes -= process }
        }
    }

    private fun joinReader(reader: Thread) {
        try {
            reader.join(3000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** The cores the current route's stamp/trigger threads report (from the
     *  native's picker and the compat helper's effective/pre-copy lines). The
     *  log thread's fsyncs are kept off them. */
    private fun routeCpusFromLine(line: String): Set<Int> {
        Regex("cpu select: runtime pair main=(\\d+) consumer=(\\d+)").find(line)?.let {
            return setOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        Regex("compat-engine: (waiter|consumer) effective cpu (\\d+)").find(line)?.let {
            return setOf(it.groupValues[2].toInt())
        }
        Regex("compat-engine: waiter pre-copy cpu (\\d+)").find(line)?.let {
            return setOf(it.groupValues[1].toInt())
        }
        return emptySet()
    }

    /** Move the calling (tailer) thread off the route's cores. The fsync then
     *  runs on a core the stamp does not depend on; the storage IRQs and the
     *  filesystem's background writeback are not ours to move. */
    private fun pinTailerOutside(pair: Set<Int>): Int? {
        val tid = android.os.Process.myTid()
        val count = Runtime.getRuntime().availableProcessors().coerceAtLeast(8)
        for (cpu in 0 until count) {
            if (cpu in pair) continue
            if (NativeCpuPin.setThreadAffinity(tid, cpu) == 0) return cpu
        }
        return null
    }

    private fun tailKsuLog(logFile: File, offset: AtomicLong, onLog: (String) -> Unit) {
        if (!logFile.isFile) return
        synchronized(offset) {
            runCatching {
                RandomAccessFile(logFile, "r").use { file ->
                    val position = offset.get().takeIf { it <= file.length() } ?: 0L
                    file.seek(position)
                    var lastComplete = position
                    val pending = StringBuilder()
                    while (true) {
                        val byte = file.read()
                        if (byte == -1) break
                        if (byte == '\n'.code) {
                            if (pending.isNotEmpty()) onLog(pending.toString())
                            pending.clear()
                            lastComplete = file.filePointer
                        } else {
                            pending.append(byte.toChar())
                        }
                    }
                    offset.set(lastComplete)
                }
            }
        }
    }

    private val processes = mutableSetOf<Process>()

}

/**
 * Whether the release is covered by a profile source the app can trust: a
 * bundled asset naming it, or an imported copy the user selected. The copy's
 * presence is the selection; its own release decides applicability downstream,
 * and an unreadable copy surfaces as an error, never a silent fallback.
 */
internal fun releaseSupportedByProfileSources(
    release: String,
    bundledReleases: Set<String>,
    importedSelected: Boolean,
): Boolean = release in bundledReleases || importedSelected

/**
 * The timer analyzer's no-BTF layout template for a release, or null when no
 * template applies. Only the 5.10 series has one (the labelled `5.10`
 * template); 6.x images carry BTF and supply their own facts. Releases spell
 * the series `5.10.<patch>-…` — the dash follows the patch level, not the
 * minor, so `startsWith("5.10-")` matches nothing.
 */
internal fun timerLayoutTemplate(release: String): String? =
    if (release.startsWith("5.10.")) "5.10" else null
