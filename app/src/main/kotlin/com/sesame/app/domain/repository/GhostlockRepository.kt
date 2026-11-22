package com.sesame.app.domain.repository

import com.sesame.app.domain.model.CpuPair
import com.sesame.app.domain.model.CrashEvidenceInfo
import com.sesame.app.domain.model.DebugSettings
import com.sesame.app.domain.model.ImportedEvidence
import com.sesame.app.domain.model.KernelSnapshot
import com.sesame.app.domain.model.MeasuredKernelInfo
import com.sesame.app.domain.model.MeasurementMergeRow
import com.sesame.app.domain.model.OffsetCandidate
import com.sesame.app.domain.model.OffsetImportResult
import com.sesame.app.domain.model.ParseResult
import com.sesame.app.domain.model.PreflightResult
import com.sesame.app.domain.model.ProfileConfig
import com.sesame.app.domain.model.ProfileImportResult
import com.sesame.app.domain.model.ProfileDiffRow
import com.sesame.app.domain.model.RunLogEntry
import com.sesame.app.domain.model.RootManagerCandidate
import com.sesame.app.domain.model.ShizukuStatus
import com.sesame.app.domain.model.StrideProbe
import com.sesame.app.domain.model.VrKoMode

interface GhostlockRepository {
    suspend fun snapshot(): KernelSnapshot

    fun selectCpuPair(index: Int)

    fun setSafeModeEnabled(enabled: Boolean)

    /** Skip the pre-attack KernelSU check and run the exploit as a test. */
    fun setForceAttackTest(enabled: Boolean)

    /** Second way: a W1-only run followed by the DirtyFrag page-cache stage. */
    fun setSecondWay(enabled: Boolean)

    /**
     * Debug-only pre-W1 fallback gate: resolve the two-entry
     * `["dirtyfrag", "ghostlock"]` order and force the DirtyFrag check to fail
     * once, proving the resolver takes GhostLock without the DirtyFrag W1-only
     * step. Consumed by the next run; ignored in release builds.
     */
    fun setFallbackTest(enabled: Boolean)

    /** Debug/test: run the DirtyFrag viability probe and return a one-line report. */
    suspend fun probeDirtyfrag(): String = "probe=NotRun (unsupported)"

    /** Debug/test: run the DirtyFrag ROM preflight and return a one-line report. */
    suspend fun preflightDirtyfrag(): String = "preflight=NotRun (unsupported)"

    /** The user's vr.ko task-tag-clear choice (Auto recommends; On/Off are final). */
    fun setVrKoTagClear(mode: VrKoMode)

    /** The user's vr.ko SELinux-write-block choice (Auto recommends; On/Off are final). */
    fun setVrKoSelinuxBlock(mode: VrKoMode)

    fun setShizukuEnabled(enabled: Boolean)

    /**
     * KernelSU-family managers found by the shared ksud scanner (executable-
     * shaped ELFs, late-load tokens, and the `late-load --help` interface
     * probe). Blocking: call from IO.
     */
    suspend fun managerCandidates(): List<RootManagerCandidate>

    /** The user's root-manager choice (package name); null means Auto. */
    fun selectedManagerPackage(): String?

    fun setSelectedManagerPackage(packageName: String?)

    suspend fun parseSource(
        input: String,
        xblPath: String? = null,
        uefiPath: String? = null,
        /** vendor_boot.img (MediaTek only); derives kernel_phys_load from
         * its header and kernel_phys_offset from its embedded DTB /memory. */
        vendorBootPath: String? = null,
        /** dtbo.img (MediaTek only): the DTB fallback when vendor_boot carries
         * no usable /memory (`dtb_size = 0` or no root memory node). */
        dtboPath: String? = null,
        overwrite: Boolean = false,
        /** Selects one route explicitly; null lets the extractor decide and
         * returns [ParseResult.Ambiguous] when more than one is Feasible. */
        route: String? = null,
        onLog: (String) -> Unit = {},
    ): ParseResult

    /**
     * Run the unprivileged mm_struct leak probe for an in-memory generated
     * document and return its stride evidence table, or null when the probe
     * cannot run. Only meaningful when [release] is the running kernel.
     */
    suspend fun probeStride(
        release: String,
        documentText: String,
        pair: CpuPair,
        onLog: (String) -> Unit = {},
    ): StrideProbe?

    /**
     * Applies (or clears, with null) `kernelsnitch.mm_struct_sz` to the entry of
     * [release] in an in-memory document and returns the rendered text.
     */
    fun applyStrideToDocument(
        documentText: String,
        release: String,
        stride: Int?,
    ): String?

    /**
     * Applies the user's lock-source choice to an in-memory generated document:
     * `keepAnchor = false` removes the `lock_anchor_*` triple (page mode) and
     * returns the rendered text; `true` returns the text unchanged.
     */
    fun applyLockSourceToDocument(
        documentText: String,
        keepAnchor: Boolean,
    ): String?

    /* ---- profile sources (import-copy storage model) ---- */

    /** True when the app-private imported copy exists (the external selection). */
    fun importedProfileExists(): Boolean

    /**
     * Imports the SD-card document [uri] for the running kernel into the
     * app-private copy. Null when it cannot be read or carries no entry for
     * this release; the previous copy is then untouched.
     */
    suspend fun selectExternalProfile(uri: String): ProfileImportResult

    /** Drops the external selection; the bundle (or builtin) applies again. */
    suspend fun clearExternalProfile(): ProfileConfig

    /** Renders the active source as the HOCON an export writes, lineage included. */
    fun liveProfileDocument(): String?

    /** Writes an exported profile into the document the user picked. */
    suspend fun writeProfileDocument(uri: String, text: String): Boolean

    /* ---- editing session: in-memory, export-only ---- */

    /** Opens an isolated editing session on the active source. */
    suspend fun beginEditSession(): ProfileConfig?

    /** Controller of the open editing session, null when none is open. */
    fun editSessionController(): ProfileConfigController?

    /** Label of the source the session started from; null when none. */
    fun editSessionTarget(): String?

    /** Release the editing session targets. */
    fun editSessionRelease(): String?

    /** Renders the session's result; the caller writes it through SAF. */
    fun editSessionDocument(): String?

    /** Fields where the session result differs from the bundle. */
    fun editSessionDiff(): List<ProfileDiffRow>

    /** Moves the session's base to the bundle while keeping its edits. */
    suspend fun rebaseEditSession(): ProfileConfig?

    fun endEditSession()

    /* ---- measured database ---- */

    /** Per-kernel measured facts, read-only; never a precedence layer. */
    suspend fun measurements(): List<MeasuredKernelInfo>

    /** Facts that would change the active profile for the running kernel. */
    suspend fun measurementMergeDiff(): List<MeasurementMergeRow>

    /** The active profile with every measured fact applied, ready to export. */
    fun mergedProfileDocument(): String?

    /** Single authority for loading, editing and exporting profile config. */
    fun profileController(): ProfileConfigController

    suspend fun readDocument(uri: String): String

    suspend fun cacheDocument(uri: String, fileName: String): String

    suspend fun debugSettings(): DebugSettings

    fun setDebugKernelLogEnabled(enabled: Boolean)

    /** Runs retained on the device, newest first, an unfinished one ahead of the rest. */
    suspend fun listRunLogs(): List<RunLogEntry>

    /** Streams one retained run into a document the user picked; false if it failed. */
    suspend fun exportRunLog(stamp: String, documentUri: String): Boolean

    suspend fun clearRunLogs()

    /**
     * Marks every run still recorded as running when the app starts: it was
     * killed with the device. Returns how many. See `RunLogStore`.
     */
    suspend fun markInterruptedRunLogs(): Int

    /**
     * Best-effort last-crash evidence for the running boot: the shell-visible
     * pstore and boot facts, stored under `last-crash/`. A device with no
     * shell still gets a bare capture that says so. Null only when the store
     * itself cannot be written.
     */
    suspend fun collectCrashEvidence(): CrashEvidenceInfo?

    /**
     * User-initiated capture: requests the Shizuku permission when that is all
     * that stands between the app and a shell, then collects. The export flow
     * calls this; the start-up capture stays silent.
     */
    suspend fun collectCrashEvidenceWithShizuku(): CrashEvidenceInfo?

    /** Whether the Shizuku manager app is installed (drives the export hint). */
    fun shizukuInstalled(): Boolean

    /** The crash collector's Shizuku view (accepts a root-run server); run gating is unchanged. */
    fun crashShellStatus(): ShizukuStatus

    /**
     * Generates a fresh system bug report through the shell (Shizuku or root)
     * and returns it reduced. The on-device route for ROMs whose Developer
     * options has no “Take bug report” entry, and for users with no computer.
     * Null when no shell is available or the generation failed.
     */
    suspend fun generateBugreport(): ImportedEvidence?

    /** The newest automatic capture on disk, without touching the device. */
    suspend fun latestCrashEvidence(): CrashEvidenceInfo?

    /**
     * Reduces a user-supplied bugreport zip in memory for the current export
     * session. Nothing is written to app storage; the original zip stays in
     * the system's bugreports folder.
     */
    suspend fun importBugreport(uri: String): ImportedEvidence?

    /**
     * Saves every retained run, every automatic crash capture, and the
     * session's in-memory import (when present) as one bundle.
     */
    suspend fun exportReportBundle(
        documentUri: String,
        pendingImport: ImportedEvidence?,
    ): Boolean

    /** One-time migration: drop imports stored by earlier builds. */
    suspend fun clearStoredImports()

    /** Suggested file name for [exportReportBundle]. */
    fun reportBundleName(): String

    suspend fun runExploit(config: ProfileConfig, pair: CpuPair, onLog: (String) -> Unit): Int

    suspend fun runExploitWithShizuku(
        config: ProfileConfig,
        pair: CpuPair,
        onLog: (String) -> Unit,
    ): Int

    /**
     * Run-start pre-flight (class 1 of the 2026-11-07 exit audit): anchor slot
     * state, CPU-pair affinity, and the root-manager scan. Returns the first
     * blocking failure, or [PreflightResult.Passed]. App-side only; the native
     * keeps its own refusals as the authority.
     */
    suspend fun preflight(pair: CpuPair, useShizuku: Boolean): PreflightResult

    /**
     * The step left `in_progress` by the previous run (usually a kernel panic),
     * or null when the last run completed/failed cleanly. `w3*` means Shizuku
     * (shell uid, no seccomp) can skip that stage.
     */
    suspend fun lastRunStuckStep(): String?

    fun requestShizukuPermission()

    fun setShizukuStatusListener(listener: (() -> Unit)?)

    fun close()
}
