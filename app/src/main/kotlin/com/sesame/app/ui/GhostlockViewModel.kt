package com.sesame.app.ui

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sesame.app.R
import com.sesame.app.RunStatusService
import com.sesame.app.data.BundledModule
import com.sesame.app.data.MagiskStage
import com.sesame.app.data.MeasuredProfileStore
import com.sesame.app.data.OptionDecision
import com.sesame.app.data.OptionPlan
import com.sesame.app.data.OptionSuperset
import com.sesame.app.data.RoutePlans
import com.sesame.app.data.ScratchInAnchor
import com.sesame.app.data.HoconSupport
import com.sesame.app.data.asValueMap
import com.sesame.app.data.TimerOnlyDocument
import com.sesame.app.data.decision
import com.sesame.app.domain.model.RunLogState
import com.sesame.app.domain.model.generationPhysComplete
import com.sesame.app.domain.model.physRule
import com.sesame.app.domain.model.isMediaTekSoc
import com.sesame.app.domain.model.isQualcommSoc
import com.sesame.app.domain.model.ImportedEvidence
import com.sesame.app.domain.model.KernelSnapshot
import com.sesame.app.domain.model.LogTone
import com.sesame.app.domain.model.ParseResult
import com.sesame.app.domain.model.PhysImage
import com.sesame.app.domain.model.PhysPromptState
import com.sesame.app.domain.model.PreflightFailure
import com.sesame.app.domain.model.PreflightResult
import com.sesame.app.domain.model.ProfileConfig
import com.sesame.app.domain.model.ProfileImportResult
import com.sesame.app.domain.model.RootManagerCandidate
import com.sesame.app.domain.model.nextPhysImage
import com.sesame.app.domain.model.CrashEvidenceInfo
import com.sesame.app.domain.model.RouteCandidate
import com.sesame.app.domain.model.ShizukuStatus
import com.sesame.app.data.profile.Timer98260
import com.sesame.app.shizuku.TimerVerdict
import com.sesame.app.domain.model.StrideProbe
import com.sesame.app.domain.model.StrideProbeOutcome
import com.sesame.app.domain.model.VrKoMode
import com.sesame.app.domain.repository.GhostlockRepository
import com.sesame.app.domain.repository.ProfileConfigController
import com.sesame.app.domain.usecase.FormatLogUseCase
import com.sesame.app.domain.usecase.LoadKernelSnapshotUseCase
import com.sesame.app.domain.usecase.ParseSourceUseCase
import com.sesame.app.domain.usecase.ReadDocumentUseCase
import com.sesame.app.domain.usecase.RunExploitUseCase
import com.sesame.app.ui.nav.NavController
import com.sesame.app.ui.nav.Screen
import com.sesame.app.ui.state.AdvancedActions
import com.sesame.app.ui.state.BuiltinActions
import com.sesame.app.ui.state.BuiltinState
import com.sesame.app.ui.state.GenerationActions
import com.sesame.app.ui.state.GenerationState
import com.sesame.app.ui.state.HomeActions
import com.sesame.app.ui.state.MeasurementsActions
import com.sesame.app.ui.state.MergePreviewActions
import com.sesame.app.ui.state.OverlayActions
import com.sesame.app.ui.state.OverlayState
import com.sesame.app.ui.state.ParametersActions
import com.sesame.app.ui.state.ReportActions
import com.sesame.app.ui.state.ReportState
import com.sesame.app.ui.state.RunSettingsActions
import com.sesame.app.ui.state.SessionState
import com.sesame.app.ui.state.VrKoActions
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface GhostlockEffect {
    data class PickDocument(val request: DocumentRequest) : GhostlockEffect
    data class CreateProfileDocument(val suggestedName: String) : GhostlockEffect

    /** Save dialog for the whole report: every run plus the crash evidence. */
    data class CreateReportBundleDocument(val suggestedName: String) : GhostlockEffect

    /** Platform picker for a user-supplied bugreport zip. */
    data object PickBugreport : GhostlockEffect

    data object OpenDeveloperOptions : GhostlockEffect
    data object OpenAboutPhone : GhostlockEffect

    /** Share the finished report bundle through the system sheet. */
    data class ShareFile(val uri: String) : GhostlockEffect

    data class Share(val uri: String) : GhostlockEffect
    data class Toast(val resourceId: Int) : GhostlockEffect
    data class ToastText(val text: String) : GhostlockEffect
    data class Clipboard(val text: String) : GhostlockEffect
    data class KeepScreenAwake(val enabled: Boolean) : GhostlockEffect
    data object OpenShizuku : GhostlockEffect

    /** Ask the platform for POST_NOTIFICATIONS (the run-status surface). */
    data object RequestNotificationPermission : GhostlockEffect

    /** Open the selected KernelSU manager's own UI (user-initiated). */
    data class OpenManager(val packageName: String) : GhostlockEffect
}

/** How often the report sheet re-reads the Shizuku verdict while it is open. */
private const val CrashVerdictPollMs = 1_500L

/** True when the evidence already carries a kernel log or a full imported report. */
private fun CrashEvidenceInfo?.hasKernelEvidence(): Boolean =
    this?.hasKernelLog == true || this?.source == "bugreport"

/**
 * True when a shell read the kernel side for this boot. The pstore may still
 * have been empty; that is a fact the export warning shows, not a reason to
 * ask for a system report the shell has already superseded.
 */
private fun CrashEvidenceInfo?.hasShellCapture(): Boolean =
    this?.source == "shizuku" || this?.source == "root"

/**
 * The view model's one mutable state: the five screen slices composed here, so
 * every mutation names the slice it belongs to. The screens never see this
 * type; they take the slice flows derived from it.
 */
private data class AppState(
    val session: SessionState = SessionState(),
    val report: ReportState = ReportState(),
    val overlay: OverlayState = OverlayState(),
    val generation: GenerationState = GenerationState(),
    val builtin: BuiltinState = BuiltinState(),
)

enum class DocumentRequest {
    SelectProfile, BootImage, XblImage, PayloadImage, UefiImage, VendorBootImage, DtboImage,
}

/** The parse pass in flight: the input plus the images attached so far. */
private data class ParseRequest(
    val input: String,
    val xblPath: String? = null,
    val uefiPath: String? = null,
    val vendorBootPath: String? = null,
    val dtboPath: String? = null,
)

/**
 * The parse flow's step. Every pending choice travels with the step, so a
 * cancelled or completed dialog cannot leave a partial postback behind.
 */
private sealed interface ParseStep {
    data object Idle : ParseStep
    data object ModePick : ParseStep
    data object SourcePick : ParseStep
    data class Phys(val image: PhysImage, val after: ParseResult.Parsed?) : ParseStep

    data class Plan(
        val parse: ParseResult.Parsed,
        val plans: RoutePlans,
        val decision: OptionDecision.Offer,
    ) : ParseStep

    data class Lock(val parse: ParseResult.Parsed) : ParseStep
}

/** The native tone tag (`<b> `, `<k> `, …) in a stored log line. */
private val ToneTag = Regex("<(b|k|s|-)> ")

/** A stored log can be long; the sheet replays its tail. */
private const val MaxReplayedLogLines = 4000

/** Logcat tag for the debug-only derivation check (`derive_test`). */
private const val DeriveTestTag = "GhostlockDerive"

class GhostlockViewModel(
    private val repository: GhostlockRepository,
    private val application: Application,
) : ViewModel() {
    private val effectChannel = Channel<GhostlockEffect>(Channel.BUFFERED)
    private val mutableState = MutableStateFlow(AppState())
    private var initialized = false
    private var running = false
    private val loadKernelSnapshot = LoadKernelSnapshotUseCase(repository)
    private val parseSourceUseCase = ParseSourceUseCase(repository)
    private val readDocumentUseCase = ReadDocumentUseCase(repository)
    private val runExploitUseCase = RunExploitUseCase(repository)
    private val formatLog = FormatLogUseCase()
    private val profileController get() = repository.profileController()

    private val state = mutableState.asStateFlow()

    /**
     * The session holder the screens take: the same state, sliced by concern.
     * It holds nothing of its own — every mutation still goes through this
     * view model — so the slices cannot disagree.
     */
    val session: StateFlow<SessionState> = mutableState
        .map { it.session }
        .stateIn(viewModelScope, SharingStarted.Eagerly, mutableState.value.session)

    /* The remaining screens' slices, derived exactly like [session] from the
     * one mutable state, so no consumer can observe a second truth. */
    val report: StateFlow<ReportState> = mutableState
        .map { it.report }
        .stateIn(viewModelScope, SharingStarted.Eagerly, mutableState.value.report)

    val overlay: StateFlow<OverlayState> = mutableState
        .map { it.overlay }
        .stateIn(viewModelScope, SharingStarted.Eagerly, mutableState.value.overlay)

    val generation: StateFlow<GenerationState> = mutableState
        .map { it.generation }
        .stateIn(viewModelScope, SharingStarted.Eagerly, mutableState.value.generation)

    val builtin: StateFlow<BuiltinState> = mutableState
        .map { it.builtin }
        .stateIn(viewModelScope, SharingStarted.Eagerly, mutableState.value.builtin)

    /* The four narrow action surfaces, one stable adapter each (prefer
     * adapters over renaming the view model's methods). */
    private val homeActionsImpl = object : HomeActions {
        override fun run() = onRun()
        override fun statusClick() = onStatusClick()
        override fun chooseRoute() = this@GhostlockViewModel.chooseRoute()
        override fun showDeviceInfo() = onShowDeviceInfo()
        override fun openLastRunLog() = onOpenLastRunLog()
        override fun exportReport() = onExportRunLog()
        override fun openAdvanced() = onOpenAdvanced()
        override fun openGeneration() = onOpenGeneration()
        override fun selectProfileFile() = onSelectProfileFile()
        override fun openParameters() = onOpenParameters()
        override fun selectBuiltinAuto() = onSelectBuiltinProfile(null)
        override fun clearImport() = onClearExternalProfile()
    }

    private val runSettingsActionsImpl = object : RunSettingsActions {
        override fun safeModeChanged(enabled: Boolean) = toggleSafeMode(enabled)
        override fun shizukuChanged(enabled: Boolean) = toggleShizuku(enabled)
        override fun openVrKo() = onOpenVrKo()
        override fun close() = onCloseRunSettings()
    }

    private val advancedActionsImpl = object : AdvancedActions {
        override fun close() = onCloseAdvanced()
        override fun openRunSettings() = onOpenRunSettings()
        override fun forceAttackChanged(enabled: Boolean) = toggleForceAttackTest(enabled)
        override fun openManagerPicker() = onOpenManagerPicker()
        override fun openParameters() = onOpenParameters()
        override fun openMeasurements() = onOpenMeasurements()
        override fun debugKernelLogChanged(enabled: Boolean) = onDebugKernelLogChanged(enabled)
        override fun showAbout() = onShowAbout()
    }

    private val parametersActionsImpl = object : ParametersActions {
        override fun close() = onCloseParameters()
        override fun openBuiltin() = onOpenBuiltinProfiles()
        override fun exportProfile() = onExportProfile()
        override fun clearExternalProfile() = onClearExternalProfile()
    }

    /* The verbs whose names match a view model method are qualified with the
     * view model receiver: unqualified they would resolve to the adapter's own
     * override and recurse. */
    private val reportActionsImpl = object : ReportActions {
        override fun onOpenExportRunLog() = onExportRunLog()
        override fun onAddBugreport() = this@GhostlockViewModel.onAddBugreport()
        override fun onReportGenerateBugreport() = this@GhostlockViewModel.onReportGenerateBugreport()
        override fun onCopyBugreportCommand() = this@GhostlockViewModel.onCopyBugreportCommand()
        override fun onOpenDeveloperOptions() = this@GhostlockViewModel.onOpenDeveloperOptions()
        override fun onOpenAboutPhone() = this@GhostlockViewModel.onOpenAboutPhone()
        override fun onReportNext() = this@GhostlockViewModel.onReportNext()
        override fun onReportBack() = this@GhostlockViewModel.onReportBack()
        override fun onReportGoToImport() = this@GhostlockViewModel.onReportGoToImport()
        override fun onReportAppend() = this@GhostlockViewModel.onReportAppend()
        override fun onReportExportOnly() = this@GhostlockViewModel.onReportExportOnly()
        override fun onReportRecheckDeveloper() = this@GhostlockViewModel.onReportRecheckDeveloper()
        override fun onReportShare() = this@GhostlockViewModel.onReportShare()
        override fun onRunLogPickerDismiss() = this@GhostlockViewModel.onRunLogPickerDismiss()
        override fun onClearRunLogs() = this@GhostlockViewModel.onClearRunLogs()
        override fun onExportReportBundle() = this@GhostlockViewModel.onExportReportBundle()
        override fun onBugreportPicked(uri: String?) = this@GhostlockViewModel.onBugreportPicked(uri)
        override fun onCloseExecutionSheet() = this@GhostlockViewModel.onCloseExecutionSheet()
        override fun onCopyLogs() = copyLogs()
        override fun onOpenManager() = this@GhostlockViewModel.onOpenManager()
    }

    private val overlayActionsImpl = object : OverlayActions {
        override fun onDialogItemSelected(index: Int) =
            this@GhostlockViewModel.onDialogItemSelected(index)

        override fun onDialogInputChange(value: String) =
            this@GhostlockViewModel.onDialogInputChange(value)

        override fun onDialogConfirm(value: String) =
            this@GhostlockViewModel.onDialogConfirm(value)

        override fun onDialogDismiss() = this@GhostlockViewModel.onDialogDismiss()
        override fun onDialogExportLog() = this@GhostlockViewModel.onDialogExportLog()
        override fun onRouteMatrixSave(
            backend: String,
            syscall: String?,
            carrier: String?,
            dirtyFragOn: Boolean?,
        ) = this@GhostlockViewModel.onRouteMatrixSave(backend, syscall, carrier, dirtyFragOn)
        override fun onStrideOptionSelected(value: Int) =
            this@GhostlockViewModel.onStrideOptionSelected(value)

        override fun onStrideUse() = this@GhostlockViewModel.onStrideUse()
    }

    private val generationActionsImpl = object : GenerationActions {
        override fun onOpenGeneration() = this@GhostlockViewModel.onOpenGeneration()
        override fun onCloseGeneration() = this@GhostlockViewModel.onCloseGeneration()
        override fun onSaveGeneratedProfile() = this@GhostlockViewModel.onSaveGeneratedProfile()
        override fun onRestartGeneration() = this@GhostlockViewModel.onRestartGeneration()
        override fun onParseOta() = promptParseUrl()
        override fun onParseImage() = parseOffsets()
    }

    private val builtinActionsImpl = object : BuiltinActions {
        override fun onOpenBuiltinProfiles() = this@GhostlockViewModel.onOpenBuiltinProfiles()
        override fun onCloseBuiltinProfiles() = this@GhostlockViewModel.onCloseBuiltinProfiles()
        override fun onSelectBuiltinProfile(release: String?) =
            this@GhostlockViewModel.onSelectBuiltinProfile(release)
    }

    private val vrKoActionsImpl = object : VrKoActions {
        override fun tagClearChanged(mode: VrKoMode) = setVrKoTagClear(mode)
        override fun selinuxBlockChanged(mode: VrKoMode) = setVrKoSelinuxBlock(mode)
        override fun close() = onCloseVrKo()
    }

    private val measurementsActionsImpl = object : MeasurementsActions {
        override fun close() = onCloseMeasurements()
        override fun openMerge() = onOpenMergePreview()
    }

    private val mergePreviewActionsImpl = object : MergePreviewActions {
        override fun close() = onCloseMergePreview()
        override fun exportMerged() = onExportMergedProfile()
    }

    fun homeActions(): HomeActions = homeActionsImpl
    fun runSettingsActions(): RunSettingsActions = runSettingsActionsImpl
    fun advancedActions(): AdvancedActions = advancedActionsImpl
    fun parametersActions(): ParametersActions = parametersActionsImpl
    fun reportActions(): ReportActions = reportActionsImpl
    fun overlayActions(): OverlayActions = overlayActionsImpl
    fun generationActions(): GenerationActions = generationActionsImpl
    fun builtinActions(): BuiltinActions = builtinActionsImpl
    fun vrKoActions(): VrKoActions = vrKoActionsImpl
    fun measurementsActions(): MeasurementsActions = measurementsActionsImpl
    fun mergePreviewActions(): MergePreviewActions = mergePreviewActionsImpl

    /** The one navigation truth: screens are pushed and popped, never flagged. */
    internal val nav = NavController()
    val effects = effectChannel.receiveAsFlow()

    private var kernelSnapshot: KernelSnapshot? = null
    private var pendingParse: ParseRequest? = null
    /** The resolution every consumer uses: the UI facts and the run itself. */
    private var resolvedProfile: ProfileConfig? = null
    /** The generated document waiting on the generation screen's save step. */
    private var pendingGeneratedProfile: PendingGeneratedProfile? = null
    /** The platform's recommended images already offered this session. */
    private var physVendorBootOffered = false
    private var physXblOffered = false
    private var physUefiOffered = false
    private var physDtboOffered = false
    /** The phys prompt on screen and the parsed result waiting behind it. */
    private var parseStep: ParseStep = ParseStep.Idle

    /** The generated document and its stride evidence, held while the picker is up. */
    private var pendingStrideParse: ParseResult.Parsed? = null
    private var pendingStrideProbe: StrideProbe? = null
    /** In-memory generated document; never stored until the user exports it. */
    private var pendingStrideDocument: String? = null
    private var pendingStrideName: String? = null
    private var pendingStrideRelease: String? = null

    /** Root-manager picker: open flag and the scanned candidates. */
    private var managerPickerOpen = false
    private var managerChoices: List<RootManagerCandidate> = emptyList()

    /** The route picker's open list; non-null while its dialog is up. */

    /** The document text and behaviour waiting for the user's export destination. */
    private data class PendingProfileWrite(
        val text: String,
        val suggestedName: String,
        /** Offer to select the written document for the next run. */
        val selectAfterWrite: Boolean,
    )

    private var pendingProfileWrite: PendingProfileWrite? = null
    /** The imported bugreport, reduced in memory for the current report sheet only. */
    private var pendingImport: ImportedEvidence? = null
    /** The report sheet's Shizuku-verdict poll; alive only while the sheet is open. */
    private var crashVerdictJob: Job? = null
    /** URI of a just-written profile awaiting the "use it?" confirmation. */
    private var pendingSelectUri: String? = null

    /** The notification rationale is offered once per process. */
    private var notificationRationaleShown = false

    fun initialize() {
        if (initialized) return
        initialized = true
        maybeAskNotificationPermission()
        repository.setShizukuStatusListener { refreshAccessStatus() }
        viewModelScope.launch {
            refreshSnapshot()
            markInterruptedRunLogs()
            seedLastRunStatus()
            maybeNoticeInterruptedRun()
        }
        /* Best effort and off the main path: a no-shell device still records
         * the boot it was on; a Shizuku/root device captures the pstore.
         * Imported reports are session state now; drop any left on disk by an
         * earlier build so they cannot silently ride along in a bundle. */
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.clearStoredImports() }
            runCatching { repository.collectCrashEvidence() }
            val evidence = runCatching { repository.latestCrashEvidence() }.getOrNull()
            if (evidence != null) mutableState.update { it.copy(report = it.report.copy(crashEvidence = evidence)) }
        }
    }

    private var interruptedRunLogsMarked = false

    /**
     * A run writes a terminal state when it ends, so anything still `running`
     * when the app starts was killed with the device — the case a report is
     * usually about. Naming it here costs one directory scan per start.
     */
    private suspend fun markInterruptedRunLogs() {
        if (interruptedRunLogsMarked) return
        interruptedRunLogsMarked = true
        runCatching { repository.markInterruptedRunLogs() }
    }

    private var interruptedRunNoticeChecked = false

    /**
     * A previous run that stopped mid-step left the step file `in_progress`.
     * Name it with the export and leave the cause to the run store and the
     * exported log: the step alone cannot tell a route miss from a crash, and
     * it must not turn into an automatic execution-mode change.
     */
    private suspend fun maybeNoticeInterruptedRun() {
        if (interruptedRunNoticeChecked) return
        interruptedRunNoticeChecked = true
        runCatching { repository.lastRunStuckStep() }.getOrNull() ?: return
        mutableState.update {
            it.copy(
                overlay = it.overlay.copy(
                    dialog = AppDialog.Notice(
                        titleRes = R.string.run_interrupted_title,
                        messageRes = R.string.run_interrupted_message,
                        exportLog = true,
                    ),
                ),
            )
        }
    }

    fun refreshAccessStatus() {
        if (initialized) viewModelScope.launch { refreshSnapshot() }
    }

    /* profile-ui: the controller owns loading, merging and persistence. */
    fun loadExecutionProfile() {
        val snapshot = kernelSnapshot ?: return
        viewModelScope.launch(Dispatchers.IO) {
            applyExecutionConfig(profileController.load(snapshot.kernelRelease))
            refreshMergeRows()
        }
    }

    private fun applyExecutionConfig(config: ProfileConfig) {
        /* The run consumes exactly this resolution (D7); nothing re-loads later. */
        resolvedProfile = config
        val (moduleAssetVerified, magiskManager) = moduleFacts(config)
        mutableState.update { state ->
            state.copy(
                session = state.session.copy(
                    support = state.session.support.copy(
                        release = config.release,
                        hasProfile = config.hasProfile,
                        profileInvalid = config.invalidPaths.isNotEmpty(),
                        route = config.route,
                        activeBuiltinProfile = profileController.activeBuiltinRelease(),
                        importedProfilePresent = profileController.importedProfileExists(),
                        layers = config.layers,
                        routeCandidates = config.routeCandidates,
                        selectedRoute = config.selectedRoute,
                        carrierCandidates = config.carrierCandidates,
                        selectedCarrier = config.selectedCarrier,
                        dirtyFragSelectable = config.dirtyFragSelectable,
                        dirtyFragOn = config.dirtyFragOn,
                        pipelineOrder = config.pipelineOrder,
                        module = config.module,
                        moduleAssetVerified = moduleAssetVerified,
                        magiskManager = magiskManager,
                    ),
                ),
            )
        }
    }

    /**
     * The module chain's delivery facts: whether the bundled `.ko` matches its
     * declared hash, and which installed Magisk manager the root script can
     * stage. Both are cheap enough to re-read on every resolution, and the
     * chain refuses to start when either is absent.
     */
    private fun moduleFacts(config: ProfileConfig?): Pair<Boolean, String?> {
        val module = config?.module ?: return false to null
        return BundledModule.verify(application, module) to MagiskStage.installed(application)
    }

    /** Recomputes the facts the home merge would change; cheap, runs on IO. */
    private suspend fun refreshMergeRows() {
        val rows = runCatching { repository.measurementMergeDiff() }.getOrDefault(emptyList())
        mutableState.update { it.copy(
            session = it.session.copy(measurements = it.session.measurements.copy(mergeRows = rows)),
        ) }
    }

    /* advanced-ui: the merged screen loads its editors and debug prefs. */
    fun onOpenAdvanced() {
        nav.push(Screen.Advanced)
        loadExecutionProfile()
        viewModelScope.launch(Dispatchers.IO) {
            val settings = repository.debugSettings()
            mutableState.update {
                it.copy(
                    session = it.session.copy(
                        runSettings = it.session.runSettings.copy(debugKernelLogEnabled = settings.kernelLogEnabled),
                    ),
                )
            }
        }
    }

    fun onCloseAdvanced() {
        nav.pop()
    }

    /** Opens the vr.ko anti-root countermeasure options screen from the home entry. */
    fun onOpenVrKo() {
        nav.push(Screen.VrKo)
    }

    fun onCloseVrKo() {
        nav.pop()
    }

    /** Opens the read-only measured-facts screen and refreshes its store. */
    fun onOpenMeasurements() {
        nav.push(Screen.Measurements)
        viewModelScope.launch(Dispatchers.IO) {
            val kernels = runCatching { repository.measurements() }.getOrDefault(emptyList())
            mutableState.update { it.copy(
                session = it.session.copy(measurements = it.session.measurements.copy(measuredKernels = kernels)),
            ) }
        }
    }

    fun onCloseMeasurements() {
        nav.pop()
    }

    fun onOpenParameters() {
        nav.push(Screen.Parameters)
        loadExecutionProfile()
    }

    fun onCloseParameters() {
        nav.pop()
    }

    /** Picks an SD-card profile document; selecting it wins over the bundle. */
    fun onSelectProfileFile() {
        send(GhostlockEffect.PickDocument(DocumentRequest.SelectProfile))
    }

    private fun selectExternalProfile(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = repository.selectExternalProfile(uri)) {
                is ProfileImportResult.Accepted -> {
                    applyExecutionConfig(result.config)
                    applyAdvancedConfig(result.config)
                    refreshSnapshot()
                    refreshMergeRows()
                    send(GhostlockEffect.Toast(R.string.user_profile_loaded))
                }

                is ProfileImportResult.Rejected -> {
                    /* Rejections are either a whole-document reason (schema,
                     * no entry for this kernel) or per-field paths. Localize
                     * both; the field lines keep the raw key for editing. */
                    val fieldErrors = mutableListOf<String>()
                    val documentErrors = mutableListOf<String>()
                    for (error in result.errors) {
                        val split = error.indexOf(": ")
                        val path = if (split > 0) error.substring(0, split) else ""
                        val labelRes = if (split > 0) fieldLabelRes(path) else null
                        if (labelRes != null) {
                            val rawReason = error.substring(split + 2)
                            val reason = if (rawReason == "invalid") {
                                application.getString(R.string.profile_field_invalid)
                            } else {
                                externalReasonText(rawReason, application)
                            }
                            fieldErrors += "${application.getString(labelRes)} ($path)：$reason"
                        } else {
                            documentErrors += externalReasonText(error, application)
                        }
                    }
                    val message = buildString {
                        if (documentErrors.isNotEmpty()) {
                            append(documentErrors.joinToString("\n"))
                        }
                        if (fieldErrors.isNotEmpty()) {
                            if (isNotEmpty()) append("\n\n")
                            append(application.getString(R.string.profile_import_rejected_intro))
                            append("\n")
                            append(fieldErrors.joinToString("\n"))
                        }
                    }
                    mutableState.update {
                        it.copy(
                            overlay = it.overlay.copy(dialog = AppDialog.Notice(
                                titleRes = R.string.profile_import_rejected_title,
                                message = message,
                            )),
                        )
                    }
                    /* A rejected import restores the previous selection; refresh
                     * so the Home does not keep the pre-import state. */
                    refreshSnapshot()
                }
            }
        }
    }

    fun onClearExternalProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            val config = runCatching { repository.clearExternalProfile() }.getOrNull()
            if (config == null) {
                send(GhostlockEffect.Toast(R.string.user_profile_load_failed))
                return@launch
            }
            applyExecutionConfig(config)
            applyAdvancedConfig(config)
            refreshSnapshot()
            refreshMergeRows()
            send(GhostlockEffect.Toast(R.string.user_profile_unloaded))
        }
    }

    /* ---- measured-db merge ---- */

    fun onOpenMergePreview() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshMergeRows()
            if (state.value.session.measurements.mergeRows.isEmpty()) {
                send(GhostlockEffect.Toast(R.string.merge_nothing))
                return@launch
            }
            nav.push(Screen.MergePreview)
        }
    }

    fun onCloseMergePreview() {
        nav.pop()
    }

    fun onExportMergedProfile() {
        val snapshot = kernelSnapshot ?: return
        val text = repository.mergedProfileDocument()
        if (text == null) {
            send(GhostlockEffect.Toast(R.string.export_failed))
            return
        }
        requestProfileWrite(
            PendingProfileWrite(
                text = text,
                suggestedName = exportDocumentName(snapshot.kernelRelease),
                selectAfterWrite = true,
            ),
        )
    }

    /** Queues a document and opens the system destination dialog for it. */
    private fun requestProfileWrite(write: PendingProfileWrite) {
        pendingProfileWrite = write
        send(GhostlockEffect.CreateProfileDocument(write.suggestedName))
    }

    fun onShowAbout() {
        nav.push(Screen.About)
    }

    fun onCloseAbout() {
        nav.pop()
    }

    /**
     * Opens the report flow. A shell-backed capture or a kernel log already in
     * hand — an automatic capture or an imported report — goes straight to the
     * destination dialog; otherwise the user first decides whether to attach a
     * system bug report.
     */
    fun onExportRunLog() {
        viewModelScope.launch {
            val runs = runCatching { repository.listRunLogs() }.getOrDefault(emptyList())
            val pending = pendingImport
            var evidence = runCatching { repository.latestCrashEvidence() }.getOrNull()
            /* The export sheet is the user asking for a report: the one place
             * where requesting the Shizuku permission is appropriate. A READY
             * server (shell or root) binds immediately; a permission prompt is
             * awaited briefly. A server that is not running cannot be started
             * from an app, so say so rather than exporting silently without the
             * kernel log. A pending import is already an explicit choice and
             * wins without the retry. */
            if (pending == null && !evidence.hasKernelEvidence() && !evidence.hasShellCapture()) {
                runCatching { repository.collectCrashEvidenceWithShizuku() }
                evidence = runCatching { repository.latestCrashEvidence() }.getOrNull()
                    ?: evidence
                if (!evidence.hasKernelEvidence() && !evidence.hasShellCapture() &&
                    repository.shizukuInstalled() &&
                    repository.crashShellStatus() == ShizukuStatus.NOT_RUNNING
                ) {
                    send(
                        GhostlockEffect.ToastText(
                            application.getString(R.string.report_shizuku_not_running),
                        ),
                    )
                }
            }
            /* Only a kernel log in hand skips the choice. A shell capture
             * without one is partial (boot facts only), and the choice screen
             * is where a system report — including the Shizuku-generated one
             * for ROMs without the Developer-options entry — is still offered. */
            val direct = pending != null || evidence.hasKernelEvidence()
            val shown = pending?.toInfo() ?: evidence
            mutableState.update {
                it.copy(
                    report = it.report.copy(runLogs = runs, pickerVisible = true, crashEvidence = shown, step = if (direct) {
                        ReportWizardStep.Export
                    } else {
                        ReportWizardStep.Choice
                    }, steps = if (direct) {
                        listOf(ReportWizardStep.Export)
                    } else {
                        emptyList()
                    }, exportDone = false, lastReportName = null, lastReportUri = null),
                )
            }
            if (direct) openReportDestination()
            startCrashVerdictPolling()
        }
    }

    /**
     * The Generate step's Shizuku button follows a verdict that changes while
     * the sheet is open: the user can start or stop the server, or grant the
     * permission in another app. The binder/permission listeners catch most
     * events; this poll makes the verdict live anyway (a grant made in the
     * Shizuku manager does not notify us), and it stops with the sheet.
     */
    private fun startCrashVerdictPolling() {
        if (crashVerdictJob?.isActive == true) return
        crashVerdictJob = viewModelScope.launch {
            while (isActive) {
                val status = runCatching { repository.crashShellStatus() }
                    .getOrDefault(ShizukuStatus.NOT_RUNNING)
                val available = status != ShizukuStatus.NOT_RUNNING
                if (mutableState.value.report.crashShellAvailable != available) {
                    mutableState.update { it.copy(report = it.report.copy(crashShellAvailable = available)) }
                }
                if (!mutableState.value.report.pickerVisible) break
                delay(CrashVerdictPollMs)
            }
            crashVerdictJob = null
        }
    }

    private fun stopCrashVerdictPolling() {
        crashVerdictJob?.cancel()
        crashVerdictJob = null
    }

    /**
     * The export flow proper: say what the next dialog is for and open the
     * destination picker. The sheet stays behind it, so a cancelled picker
     * leaves the same screen with the button that opens it again.
     */
    private fun openReportDestination() {
        mutableState.update {
            it.copy(report = it.report.copy(step = ReportWizardStep.Export, exportDone = false))
        }
        send(
            GhostlockEffect.ToastText(
                application.getString(R.string.report_choose_location),
            ),
        )
        send(GhostlockEffect.CreateReportBundleDocument(repository.reportBundleName()))
    }

    /** The whole-store report: every run plus every crash-evidence capture. */
    fun onExportReportBundle() {
        send(GhostlockEffect.CreateReportBundleDocument(repository.reportBundleName()))
    }

    fun onExportReportBundleDocumentPicked(documentUri: String?) {
        if (documentUri.isNullOrBlank()) return
        viewModelScope.launch {
            /* Refresh the automatic evidence first: the user may have started
             * Shizuku since the app started. A failed refresh costs the bundle
             * nothing; the stored captures still travel. The pending import is
             * in memory and is passed to the writer directly. */
            runCatching { repository.collectCrashEvidence() }
            val evidence = runCatching { repository.latestCrashEvidence() }.getOrNull()
            val pending = pendingImport
            if (pending == null && evidence != null) {
                mutableState.update { it.copy(report = it.report.copy(crashEvidence = evidence)) }
            }
            val ok = runCatching {
                repository.exportReportBundle(documentUri, pending)
            }.getOrDefault(false)
            if (ok) {
                val name = documentName(documentUri)
                mutableState.update {
                    it.copy(
                        report = it.report.copy(exportDone = true, lastReportName = name, lastReportUri = documentUri),
                    )
                }
                send(
                    GhostlockEffect.ToastText(
                        application.getString(
                            R.string.report_saved_toast,
                            name ?: documentUri,
                        ),
                    ),
                )
            } else {
                send(GhostlockEffect.Toast(R.string.export_failed))
            }
        }
    }

    private fun documentName(uri: String): String? = runCatching {
        application.contentResolver.query(
            uri.toUri(),
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    fun onAddBugreport() {
        if (mutableState.value.report.importing) return
        send(GhostlockEffect.PickBugreport)
    }

    fun onBugreportPicked(uri: String?) {
        if (uri.isNullOrBlank()) return
        if (mutableState.value.report.importing) return
        mutableState.update { it.copy(report = it.report.copy(importing = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            val imported = runCatching { repository.importBugreport(uri) }.getOrNull()
            val inFlow = mutableState.value.report.pickerVisible
            mutableState.update {
                it.copy(
                    report = it.report.copy(importing = false, crashEvidence = imported?.toInfo() ?: it.report.crashEvidence),
                )
            }
            if (imported != null) {
                pendingImport = imported
                send(GhostlockEffect.Toast(R.string.bugreport_import_done))
                /* Importing was the reason for this visit; continue to the
                 * export. A share-sheet import outside the flow keeps the
                 * evidence in memory for the next export in this session. */
                if (inFlow) openReportDestination()
            } else {
                send(GhostlockEffect.Toast(R.string.bugreport_import_failed))
            }
        }
    }

    /** Copies the adb bugreport command for the report sheet's computer route. */
    fun onCopyBugreportCommand() {
        runCatching {
            val clipboard =
                application.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(
                ClipData.newPlainText("bugreport", "adb bugreport report.zip"),
            )
        }
        send(GhostlockEffect.Toast(R.string.report_adb_copied))
    }

    /**
     * The Generate step's shell route: a ROM without the Developer-options
     * "Take bug report" entry (or a user with no computer) still gets a real
     * system report. The service generates it with `bugreportz` and streams the
     * zip into the importer; the app never stores the hundreds of megabytes.
     */
    fun onReportGenerateBugreport() {
        if (mutableState.value.report.generating || mutableState.value.report.importing) return
        mutableState.update { it.copy(report = it.report.copy(generating = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            val imported = runCatching { repository.generateBugreport() }.getOrNull()
            val inFlow = mutableState.value.report.pickerVisible
            mutableState.update {
                it.copy(
                    report = it.report.copy(generating = false, crashEvidence = imported?.toInfo() ?: it.report.crashEvidence),
                )
            }
            if (imported != null) {
                pendingImport = imported
                send(GhostlockEffect.Toast(R.string.bugreport_import_done))
                if (inFlow) openReportDestination()
            } else {
                send(GhostlockEffect.Toast(R.string.report_generate_failed))
            }
        }
    }

    fun onOpenDeveloperOptions() = send(GhostlockEffect.OpenDeveloperOptions)

    fun onOpenAboutPhone() = send(GhostlockEffect.OpenAboutPhone)

    /**
     * The user chose to attach a system bug report. Developer options is the
     * way in on every ROM; a Xiaomi feedback-app entry is deliberately not
     * used — it opens the feedback home/composer, not the system bug report.
     */
    fun onReportAppend() {
        /* While a report is being parsed the wizard holds still. */
        if (mutableState.value.report.importing) return
        /* Choosing to attach starts a fresh capture; any session import from
         * a previous pass is not used. */
        pendingImport = null
        if (developerSettingsEnabled()) {
            mutableState.update {
                it.copy(
                    report = it.report.copy(step = ReportWizardStep.Generate, steps = listOf(
                        ReportWizardStep.Generate,
                        ReportWizardStep.Import,
                        ReportWizardStep.Export,
                    )),
                )
            }
        } else {
            mutableState.update {
                it.copy(
                    report = it.report.copy(step = ReportWizardStep.Developer, steps = listOf(
                        ReportWizardStep.Developer,
                        ReportWizardStep.Generate,
                        ReportWizardStep.Import,
                        ReportWizardStep.Export,
                    )),
                )
            }
        }
    }

    /** No system report: go straight to the location dialog. */
    fun onReportExportOnly() {
        if (mutableState.value.report.importing) return
        pendingImport = null
        mutableState.update {
            it.copy(report = it.report.copy(steps = listOf(ReportWizardStep.Export)))
        }
        openReportDestination()
    }

    /** Re-reads the system switch after the user visited Settings. */
    fun onReportRecheckDeveloper() {
        if (developerSettingsEnabled()) {
            mutableState.update { it.copy(report = it.report.copy(step = ReportWizardStep.Generate)) }
        } else {
            send(GhostlockEffect.Toast(R.string.report_developer_missing))
        }
    }

    /**
     * Called from the activity's onResume. Returning from About phone is how
     * the Developer-options gate ends; the screen advances by itself.
     */
    fun onReportResumed() {
        val state = mutableState.value.report
        if (!state.pickerVisible) return
        if (state.step != ReportWizardStep.Developer) return
        if (developerSettingsEnabled()) {
            mutableState.update { it.copy(report = it.report.copy(step = ReportWizardStep.Generate)) }
        }
    }

    fun onReportNext() {
        val state = mutableState.value.report
        if (state.importing) return
        when (state.step) {
            ReportWizardStep.Developer -> {
                if (developerSettingsEnabled()) {
                    mutableState.update { it.copy(report = it.report.copy(step = ReportWizardStep.Generate)) }
                } else {
                    send(GhostlockEffect.Toast(R.string.report_developer_missing))
                }
            }

            ReportWizardStep.Generate ->
                mutableState.update { it.copy(report = it.report.copy(step = ReportWizardStep.Import)) }

            ReportWizardStep.Import -> {
                if (state.crashEvidence.hasKernelEvidence()) {
                    openReportDestination()
                } else {
                    send(GhostlockEffect.Toast(R.string.report_import_needed))
                }
            }

            ReportWizardStep.Choice,
            ReportWizardStep.Export,
            -> Unit
        }
    }

    fun onReportBack() {
        val state = mutableState.value.report
        if (state.importing) return
        val steps = state.steps
        val previous = when (state.step) {
            ReportWizardStep.Developer -> ReportWizardStep.Choice

            ReportWizardStep.Generate ->
                if (steps.contains(ReportWizardStep.Developer)) {
                    ReportWizardStep.Developer
                } else {
                    ReportWizardStep.Choice
                }

            ReportWizardStep.Import ->
                if (steps.contains(ReportWizardStep.Generate)) {
                    ReportWizardStep.Generate
                } else {
                    ReportWizardStep.Choice
                }

            ReportWizardStep.Export ->
                if (steps.contains(ReportWizardStep.Import)) {
                    ReportWizardStep.Import
                } else {
                    ReportWizardStep.Choice
                }

            ReportWizardStep.Choice -> ReportWizardStep.Choice
        }
        mutableState.update { it.copy(report = it.report.copy(step = previous)) }
    }

    /** "Already have a report" from the choice or export-warning screens. */
    fun onReportGoToImport() {
        if (mutableState.value.report.importing) return
        val steps = mutableState.value.report.steps
        val plan = when {
            steps.contains(ReportWizardStep.Developer) -> listOf(
                ReportWizardStep.Developer,
                ReportWizardStep.Generate,
                ReportWizardStep.Import,
                ReportWizardStep.Export,
            )

            steps.contains(ReportWizardStep.Generate) -> steps

            else -> listOf(
                ReportWizardStep.Import,
                ReportWizardStep.Export,
            )
        }
        mutableState.update {
            it.copy(report = it.report.copy(step = ReportWizardStep.Import, steps = plan))
        }
    }

    fun onReportShare() {
        val uri = mutableState.value.report.lastReportUri ?: return
        send(GhostlockEffect.ShareFile(uri))
    }

    /** True when the system's Developer options switch is on. */
    private fun developerSettingsEnabled(): Boolean = runCatching {
        Settings.Global.getInt(
            application.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) == 1
    }.getOrDefault(false)

    fun onRunLogPickerDismiss() {
        /* The import owns the sheet until it ends; a tap outside or Back must
         * not drop a running parse (its result would have nowhere to go). */
        if (mutableState.value.report.importing) return
        stopCrashVerdictPolling()
        pendingImport = null
        mutableState.update { it.copy(report = it.report.copy(pickerVisible = false)) }
    }

    fun onClearRunLogs() {
        if (mutableState.value.report.importing) return
        pendingImport = null
        viewModelScope.launch {
            runCatching { repository.clearRunLogs() }
            stopCrashVerdictPolling()
            mutableState.update { it.copy(report = it.report.copy(runLogs = emptyList(), pickerVisible = false)) }
            send(GhostlockEffect.Toast(R.string.run_log_cleared))
        }
    }

    fun onDebugKernelLogChanged(enabled: Boolean) {
        repository.setDebugKernelLogEnabled(enabled)
        mutableState.update { it.copy(
            session = it.session.copy(runSettings = it.session.runSettings.copy(debugKernelLogEnabled = enabled)),
        ) }
    }

    /** Exports the active profile through the system document dialog. */
    fun onExportProfile() {
        val snapshot = kernelSnapshot ?: return
        val text = repository.liveProfileDocument()
        if (text == null) {
            send(GhostlockEffect.Toast(R.string.export_failed))
            return
        }
        requestProfileWrite(
            PendingProfileWrite(
                text = text,
                suggestedName = exportDocumentName(snapshot.kernelRelease),
                selectAfterWrite = false,
            ),
        )
    }

    fun onExportProfileDocumentPicked(documentUri: String?) {
        val write = pendingProfileWrite
        pendingProfileWrite = null
        if (write == null) return
        if (documentUri.isNullOrBlank()) {
            /* A cancelled export loses the document by design: the editor says
             * so before opening the picker (measured-db-plan §4). */
            send(GhostlockEffect.Toast(R.string.export_cancelled))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repository.writeProfileDocument(documentUri, write.text)
            if (!ok) {
                send(GhostlockEffect.Toast(R.string.export_failed))
                return@launch
            }
            if (!write.selectAfterWrite) {
                send(GhostlockEffect.Toast(R.string.override_export_done))
                return@launch
            }
            pendingSelectUri = documentUri
            mutableState.update {
                it.copy(
                    overlay = it.overlay.copy(dialog = AppDialog.Confirm(
                        titleRes = R.string.profile_exported_title,
                        messageRes = R.string.profile_use_question,
                        confirmLabelRes = R.string.profile_use_confirm,
                    )),
                )
            }
        }
    }

    private fun exportDocumentName(release: String): String =
        "${release.replace(Regex("[^A-Za-z0-9._-]"), "_")}.conf"

    /* ---- builtin selection ---- */

    /** Opens the builtin picker; overrides stay keyed to the device kernel. */
    fun onOpenBuiltinProfiles() {
        val snapshot = kernelSnapshot ?: return
        nav.push(Screen.Builtin)
        viewModelScope.launch(Dispatchers.IO) {
            val releases = runCatching { profileController.builtinReleases() }
                .getOrDefault(emptyList())
            val templates = releases.filter {
                it.endsWith(ProfileConfigController.TemplateSuffix)
            }
            val kernels = releases.filterNot {
                it.endsWith(ProfileConfigController.TemplateSuffix)
            }
            if (templates.isEmpty() && kernels.isEmpty()) {
                send(GhostlockEffect.Toast(R.string.load_builtin_failed))
                return@launch
            }
            mutableState.update {
                it.copy(
                    builtin = it.builtin.copy(templates = sortByKernelSimilarity(
                        snapshot.kernelRelease, templates,
                    ), releases = sortByKernelSimilarity(
                        snapshot.kernelRelease, kernels,
                    )),
                )
            }
        }
    }

    fun onCloseBuiltinProfiles() {
        nav.pop()
    }

    fun onSelectBuiltinProfile(release: String?) {
        val snapshot = kernelSnapshot ?: return
        viewModelScope.launch(Dispatchers.IO) {
            /* The controller keeps a single active source: picking a builtin
             * also clears the external selection (last explicit choice wins). */
            val config = runCatching {
                profileController.selectBuiltin(release, snapshot.kernelRelease)
            }.getOrNull()
            if (config == null) {
                send(GhostlockEffect.Toast(R.string.load_builtin_failed))
                return@launch
            }
            applyExecutionConfig(config)
            applyAdvancedConfig(config)
            refreshSnapshot()
            refreshMergeRows()
            send(GhostlockEffect.Toast(R.string.load_builtin_done))
        }
    }

    /** Orders releases by absolute major/minor/fix/android distance to device. */
    private fun sortByKernelSimilarity(deviceRelease: String, releases: List<String>): List<String> {
        val device = kernelVersionKey(deviceRelease)
        return releases.sortedWith(Comparator { a, b ->
            compareIntLists(
                similarityKey(device, kernelVersionKey(a)),
                similarityKey(device, kernelVersionKey(b)),
            )
        })
    }

    private fun kernelVersionKey(release: String): List<Int> {
        val version = release.substringBefore('-').split('.')
            .mapNotNull { it.toIntOrNull() }
        val android = Regex("-android(\\d+)").find(release)
            ?.groupValues?.get(1)?.toIntOrNull()
        return if (android == null) version else version + android
    }

    private fun similarityKey(device: List<Int>, candidate: List<Int>): List<Int> =
        (0 until maxOf(device.size, candidate.size)).map { index ->
            kotlin.math.abs((device.getOrNull(index) ?: 0) - (candidate.getOrNull(index) ?: 0))
        }

    private fun compareIntLists(a: List<Int>, b: List<Int>): Int {
        for (index in 0 until maxOf(a.size, b.size)) {
            val result = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
            if (result != 0) return result
        }
        return 0
    }

    private fun applyAdvancedConfig(config: ProfileConfig) {
        mutableState.update { state ->
            state.copy(
                session = state.session.copy(
                    support = state.session.support.copy(
                        route = config.route,
                        profileInvalid = config.invalidPaths.isNotEmpty(),
                        activeBuiltinProfile = profileController.activeBuiltinRelease(),
                        importedProfilePresent = profileController.importedProfileExists(),
                        layers = config.layers,
                        routeCandidates = config.routeCandidates,
                        selectedRoute = config.selectedRoute,
                        carrierCandidates = config.carrierCandidates,
                        selectedCarrier = config.selectedCarrier,
                        dirtyFragSelectable = config.dirtyFragSelectable,
                        dirtyFragOn = config.dirtyFragOn,
                        pipelineOrder = config.pipelineOrder,
                    ),
                ),
            )
        }
    }

    fun toggleSafeMode(enabled: Boolean) {
        repository.setSafeModeEnabled(enabled)
        mutableState.update { it.copy(
            session = it.session.copy(runSettings = it.session.runSettings.copy(safeModeEnabled = enabled)),
        ) }
    }

    fun toggleForceAttackTest(enabled: Boolean) {
        repository.setForceAttackTest(enabled)
        mutableState.update { it.copy(
            session = it.session.copy(runSettings = it.session.runSettings.copy(forceAttackTestEnabled = enabled)),
        ) }
    }

    fun setVrKoTagClear(mode: VrKoMode) {
        repository.setVrKoTagClear(mode)
        mutableState.update { it.copy(
            session = it.session.copy(runSettings = it.session.runSettings.copy(vrKoTagClear = mode)),
        ) }
    }

    fun setVrKoSelinuxBlock(mode: VrKoMode) {
        repository.setVrKoSelinuxBlock(mode)
        mutableState.update { it.copy(
            session = it.session.copy(runSettings = it.session.runSettings.copy(vrKoSelinuxBlock = mode)),
        ) }
    }

    fun toggleShizuku(enabled: Boolean) {
        repository.setShizukuEnabled(enabled)
        mutableState.update { it.copy(session = it.session.copy(access = it.session.access.copy(shizukuEnabled = enabled))) }
        // The grant dialog lands in another app, so the status is re-read and
        // onResume() refreshes it again when the dialog closes.
        viewModelScope.launch { refreshSnapshot() }
    }

    fun onRun() {
        if (resolvedProfile?.selectedRoute == Timer98260.BLOCK_KEY) {
            onRunTimer()
        } else {
            runExploit()
        }
    }

    /**
     * The route picker: the viable routes only, in the profile's order. The
     * chosen name is persisted by the controller; a stale name is cleared at
     * the next load (self-healing, no migration).
     */
    fun chooseRoute() {
        val profile = resolvedProfile ?: return
        /* One entry on the home row; the sheet renders the backend banners and
         * applies each choice as it is tapped. */
        mutableState.update {
            it.copy(overlay = it.overlay.copy(dialog = AppDialog.RouteMatrix))
        }
    }

    /* ---- the route matrix sheet ---- */

    /**
     * Applies the sheet's draft on 保存. The GhostLock choices (syscall,
     * carrier, DirtyFrag) are remembered even when the timer is the active
     * backend, then the selected backend is written last so it wins.
     */
    fun onRouteMatrixSave(
        backend: String,
        syscall: String?,
        carrier: String?,
        dirtyFragOn: Boolean?,
    ) {
        val snapshot = kernelSnapshot ?: return
        val release = snapshot.kernelRelease
        val selected = if (backend == Timer98260.BLOCK_KEY) Timer98260.BLOCK_KEY else syscall
        if (selected == null) return
        viewModelScope.launch(Dispatchers.IO) {
            var routeStored = true
            if (syscall != null) {
                routeStored = runCatching {
                    profileController.selectRoute(release, syscall)
                }.getOrDefault(false)
                if (carrier != null) {
                    runCatching { profileController.selectCarrier(release, syscall, carrier) }
                }
                if (dirtyFragOn != null) {
                    runCatching { profileController.selectDirtyFrag(release, dirtyFragOn) }
                }
            }
            runCatching { profileController.selectRoute(release, selected) }
            withContext(Dispatchers.Main) {
                if (!routeStored && syscall != null) {
                    appendLog("route: $syscall is not viable; using the default")
                }
                appendLog("route: $selected")
                if (syscall != null && carrier != null && selected != Timer98260.BLOCK_KEY) {
                    appendLog("carrier: $syscall $carrier")
                }
                if (dirtyFragOn != null) {
                    appendLog(
                        "pipeline: dirtyfrag " +
                            if (dirtyFragOn) "on" else "off (GhostLock only)",
                    )
                }
                loadExecutionProfile()
                dismissDialog()
            }
        }
    }

    /**
     * The timer route's start path. Shizuku is mandatory and asked for here
     * (independent of the native toggle); the boot contract refuses a second
     * attempt on the same boot. There is no separate consent dialog (operator
     * decision, 2026-11-22): the picker already names the timer route, and the
     * reboot-bound risks are logged at the start of the run.
     *
     * A missing Shizuku is *not* auto-opened: switching to the Shizuku app
     * would take the foreground and hide the explanation. The refusal is a
     * notice that names the missing step (install / start / grant), with the
     * project link when the app is not installed at all.
     */
    private fun onRunTimer() {
        val snapshot = kernelSnapshot ?: return
        val config = resolvedProfile
        if (config == null || !config.hasProfile) {
            appendLog(
                if (state.value.session.support.importedProfilePresent) {
                    "result: the imported profile is unusable; remove the import first"
                } else {
                    "result: no profile is loaded; import or generate one"
                },
            )
            return
        }
        /* The timer route is Shizuku-only, independent of the native toggle:
         * read the live status rather than the snapshot's gated one, which
         * reports NOT_REQUIRED while the toggle is off. */
        val shizuku = if (snapshot.shizukuEnabled) {
            snapshot.shizukuStatus
        } else {
            repository.shizukuShellStatus()
        }
        if (shizuku != ShizukuStatus.READY) {
            appendLog("<s> Shizuku is required for the timer route")
            val installed = runCatching { repository.shizukuInstalled() }.getOrDefault(false)
            when (timerShizukuBlocker(shizuku, installed)) {
                TimerShizukuBlocker.MISSING -> showTimerBlocker(
                    titleRes = R.string.timer_shizuku_missing_title,
                    messageRes = R.string.timer_shizuku_missing_message,
                    docUrl = SHIZUKU_URL,
                )

                TimerShizukuBlocker.NOT_RUNNING -> showTimerBlocker(
                    titleRes = R.string.timer_shizuku_not_running_title,
                    messageRes = R.string.timer_shizuku_not_running_message,
                )

                TimerShizukuBlocker.PERMISSION -> showTimerBlocker(
                    titleRes = R.string.timer_shizuku_permission_title,
                    messageRes = R.string.timer_shizuku_permission_message,
                )

                null -> Unit
            }
            return
        }
        if (!repository.timerRunAllowed()) {
            appendLog("<s> a timer attempt already ran on this boot; reboot before retrying")
            mutableState.update {
                it.copy(
                    overlay = it.overlay.copy(
                        dialog = AppDialog.Notice(
                            titleRes = R.string.timer_reboot_first_title,
                            messageRes = R.string.timer_reboot_first_message,
                        ),
                    ),
                )
            }
            return
        }
        runTimerNow()
    }

    /** The timer refusal notice — the start path's only dialog. */
    private fun showTimerBlocker(titleRes: Int, messageRes: Int, docUrl: String? = null) {
        mutableState.update {
            it.copy(
                overlay = it.overlay.copy(
                    dialog = AppDialog.Notice(
                        titleRes = titleRes,
                        messageRes = messageRes,
                        docUrl = docUrl,
                    ),
                ),
            )
        }
    }

    /** The timer run, with the same operation/status plumbing as the native run. */
    private fun runTimerNow() {
        viewModelScope.launch {
            if (!beginOperation()) return@launch
            clearLog()
            send(GhostlockEffect.KeepScreenAwake(true))
            mutableState.update {
                it.copy(session = it.session.copy(run = it.session.run.copy(status = RunStatus.Running, startedAtMs = System.currentTimeMillis())))
            }
            RunStatusService.start(
                application,
                application.getString(R.string.run_notif_running_text),
            )
            appendLog("==== start timer route ====")
            /* The risks the old consent dialog carried now live in the log. */
            appendLog(
                "<s> a miss parks the tool and needs a reboot; " +
                    "SELinux stays permissive until then",
            )
            withContext(Dispatchers.IO) {
                try {
                    val code = runCatching { repository.runTimerRoute(::appendLog) }
                        .getOrElse { error ->
                            appendLog("<s> error: ${error.message}")
                            1
                        }
                    appendLog(
                        if (code == 0) {
                            "result: timer route completed"
                        } else {
                            "result: timer route failed (exit code=$code)"
                        },
                    )
                    appendLog("exit code=$code")
                    finishRunStatus(code)
                    /* A non-start failure (parked miss, watchdog, early exit)
                     * leaves the boot dirty; say so at once instead of only on
                     * the next attempt. NotStarted means the tool never ran. */
                    if (code != 0 && code != TimerVerdict.NotStarted) {
                        mutableState.update {
                            it.copy(
                                overlay = it.overlay.copy(
                                    dialog = AppDialog.Notice(
                                        titleRes = R.string.timer_reboot_first_title,
                                        messageRes = R.string.timer_reboot_first_message,
                                    ),
                                ),
                            )
                        }
                    }
                } finally {
                    if (mutableState.value.session.run.status == RunStatus.Running) {
                        finishRunStatus(-1)
                    }
                    endOperation()
                    send(GhostlockEffect.KeepScreenAwake(false))
                }
            }
        }
    }

    /** Debug-only: run the DirtyFrag viability probe without an attack. */
    fun runDirtyfragProbeCheck() {
        viewModelScope.launch {
            val report = runCatching {
                withContext(Dispatchers.IO) { repository.probeDirtyfrag() }
            }.getOrElse { "probe=NotRun error=$it" }
            appendLog("<b> $report")
        }
    }

    /** Debug-only: run the DirtyFrag ROM preflight without an attack. */
    fun runDirtyfragPreflightCheck() {
        viewModelScope.launch {
            val report = runCatching {
                withContext(Dispatchers.IO) { repository.preflightDirtyfrag() }
            }.getOrElse { "preflight=NotRun error=$it" }
            appendLog("<b> $report")
        }
    }

    /**
     * Debug-only: deliver the timer tool (CVE-2026-98260) over Shizuku and
     * run its safe `probe` mode. No root, no reboot risk; the run store holds
     * the tool's document and log.
     */
    fun runTimerProbeCheck() {
        viewModelScope.launch {
            val code = runCatching {
                withContext(Dispatchers.IO) { repository.runTimerProbe(::appendLog) }
            }.getOrElse { error ->
                appendLog("<s> error: ${error.message}")
                1
            }
            appendLog("<b> timer probe exit=$code")
        }
    }

    /**
     * Debug-only: run the timer route's way-2 tool (`fake`). It can panic and
     * reboot the device; one attempt per boot, and the parked tool must be
     * left running until the reboot.
     */
    fun runTimerRouteCheck() {
        viewModelScope.launch {
            val code = runCatching {
                withContext(Dispatchers.IO) { repository.runTimerRoute(::appendLog) }
            }.getOrElse { error ->
                appendLog("<s> error: ${error.message}")
                1
            }
            appendLog("<b> timer run exit=$code")
        }
    }

    fun onStatusClick() {
        val snapshot = kernelSnapshot ?: return
        /* shizukuEnabled already carries the profile suggestion unless the
         * user overrode it (PROFILE-SUGGEST-01). */
        if (!snapshot.shizukuEnabled) return
        when (snapshot.shizukuStatus) {
            ShizukuStatus.NOT_RUNNING -> send(GhostlockEffect.OpenShizuku)
            ShizukuStatus.PERMISSION_REQUIRED -> repository.requestShizukuPermission()
            ShizukuStatus.NOT_REQUIRED,
            ShizukuStatus.READY,
                -> Unit
        }
    }

    private fun runExploit() {
        val snapshot = kernelSnapshot ?: return
        val config = resolvedProfile
        if (config == null || !config.hasProfile || config.invalidPaths.isNotEmpty()) {
            if (beginOperation()) {
                appendLog(
                    when {
                        config == null || !config.hasProfile ->
                            if (state.value.session.support.importedProfilePresent) {
                                "result: the imported profile is unusable; remove the import first"
                            } else {
                                "result: no profile is loaded; import or generate one"
                            }

                        else ->
                            "result: the loaded profile has ${config.invalidPaths.size} invalid field(s)"
                    },
                )
                endOperation()
            }
            return
        }
        val useShizuku = snapshot.shizukuEnabled
        if (useShizuku && snapshot.shizukuStatus != ShizukuStatus.READY) {
            onStatusClick()
            return
        }
        viewModelScope.launch {
            /* Run-start pre-flight: class-1 checks stop the run before the log
             * sheet opens, so no run-store entry and no native are created. */
            when (val preflight = withContext(Dispatchers.IO) { repository.preflight(useShizuku) }) {
                is PreflightResult.Blocked -> {
                    showPreflightNotice(preflight.failure)
                    return@launch
                }
                PreflightResult.Passed -> Unit
            }
            if (!beginOperation()) return@launch
            /* A new run is a new log: the sheet must never open on the
             * previous operation's lines (the generation flow's, typically).
             * Without this the list accumulated across operations and read as
             * "nothing new during execution". */
            clearLog()
            send(GhostlockEffect.KeepScreenAwake(true))
            mutableState.update { it.copy(session = it.session.copy(run = it.session.run.copy(status = RunStatus.Running, startedAtMs = System.currentTimeMillis()))) }
            /* A foreground service keeps the app's uid out of the cached
             * (freezable) state if the KSU manager takes the foreground during
             * the handoff; the notification also carries the run status. */
            RunStatusService.start(
                application,
                application.getString(R.string.run_notif_running_text),
            )
            appendLog("==== start ${if (useShizuku) "Shizuku/V20" else "base"} ====")
            withContext(Dispatchers.IO) {
                try {
                    val code = runExploitUseCase(config, useShizuku, ::appendLog)
                    appendLog(if (code == 0) "result: exploit completed" else "result: exploit failed (exit code=$code)")
                    appendLog("exit code=$code")
                    finishRunStatus(code)
                } finally {
                    if (mutableState.value.session.run.status == RunStatus.Running) finishRunStatus(-1)
                    endOperation()
                    send(GhostlockEffect.KeepScreenAwake(false))
                }
            }
        }
    }

    /** Terminal run status for the sheet footer and the result notification. */
    private fun finishRunStatus(code: Int) {
        val text = mutableState.value.session.run.logLines.joinToString("\n") { it.text }
        val dirtyfrag = text.contains("pipeline=dirtyfrag")
        val rooted = code == 0 && (
            text.contains("KernelSU ready") ||
                text.contains("RESULT=OK") ||
                /* The timer route's own success markers: the module loaded and
                 * the tool parked holding the fake cred page. */
                text.contains("KSU_LOADED") ||
                text.contains("fake cred page held")
            )
        val message = application.getString(
            when {
                rooted && dirtyfrag -> R.string.run_notif_success_text_dirtyfrag
                rooted -> R.string.run_notif_success_text_ghostlock
                else -> R.string.run_notif_failure_text
            },
        )
        mutableState.update {
            it.copy(
                session = it.session.copy(
                    run = it.session.run.copy(status = if (rooted) RunStatus.Success else RunStatus.Failure),
                ),
            )
        }
        RunStatusService.finish(application, message, rooted)
        /* The page-free W1's value is a kernel address, so it sets the two
         * policycap bytes at selinux_state+5/+6. The post-root policy load
         * repairs them; when that load did not run or failed, only a reboot
         * does. The prompt is a safety net, not a run verdict: a completed run
         * whose fixup landed never shows it. */
        if (RunSafety.needsRebootPrompt(text, code)) {
            appendLog("warning: the page-free W1 left SELinux policy capabilities modified; reboot to restore them")
            showNotice(R.string.reboot_recommended_title, R.string.reboot_recommended_message)
            RunStatusService.rebootRecommended(
                application,
                application.getString(R.string.reboot_recommended_message),
            )
        }
    }

    /**
     * Ask for POST_NOTIFICATIONS before the first run's status matters. The
     * rationale is ours (the system dialog is terse); declining is not fatal —
     * the foreground service still protects the run, only its status is hidden.
     */
    private fun maybeAskNotificationPermission() {
        if (notificationRationaleShown) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationRationaleShown = true
        mutableState.update {
            it.copy(
                overlay = it.overlay.copy(dialog = AppDialog.NotificationPermission(
                    titleRes = R.string.run_notif_permission_title,
                    messageRes = R.string.run_notif_permission_message,
                )),
            )
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (!granted) {
            appendLog("note: notification permission not granted; run status stays in the app")
        }
    }

    /** User-initiated: open the selected (or auto-resolved) manager's UI. */
    fun onOpenManager() {
        val selected = state.value.session.access.selectedManagerPackage
        if (selected != null) {
            send(GhostlockEffect.OpenManager(selected))
            return
        }
        viewModelScope.launch {
            val packageName = runCatching {
                repository.managerCandidates().firstOrNull { it.suitable }?.packageName
            }.getOrNull()
            send(
                if (packageName != null) {
                    GhostlockEffect.OpenManager(packageName)
                } else {
                    GhostlockEffect.Toast(R.string.run_notif_manager_missing)
                },
            )
        }
    }

    /**
     * One NOTICE dialog: title + message, one dismiss, no confirm, no export.
     * Shared by the root-manager warning, the run-start pre-flight and the
     * next-launch notices so their layout and dismissal cannot drift.
     */
    private fun showNotice(titleRes: Int, messageRes: Int) {
        mutableState.update { it.copy(
            overlay = it.overlay.copy(dialog = AppDialog.Notice(titleRes = titleRes, messageRes = messageRes)),
        ) }
    }

    private fun showPreflightNotice(failure: PreflightFailure) {
        val title: Int
        val message: Int
        when (failure) {
            PreflightFailure.ANCHOR_STATE_UNCONFIRMED -> {
                title = R.string.preflight_anchor_title
                message = R.string.preflight_anchor_boot
            }
            PreflightFailure.ANCHOR_BUDGET_SPENT -> {
                title = R.string.preflight_anchor_title
                message = R.string.preflight_anchor_spent
            }
            PreflightFailure.ANCHOR_GRID_MISMATCH -> {
                title = R.string.preflight_anchor_title
                message = R.string.preflight_anchor_grid
            }
            PreflightFailure.NO_ROOT_MANAGER -> {
                /* The same warning the picker used to show; the picker keeps
                 * listing/selection, the run attempt is what blocks. */
                title = R.string.manager_picker_title
                message = R.string.manager_picker_empty
            }
        }
        showNotice(title, message)
    }

    fun onShowDeviceInfo() {
        val s = state.value.session.device
        mutableState.update {
            it.copy(
                overlay = it.overlay.copy(dialog = AppDialog.Notice(
                    titleRes = R.string.home_device_info_title,
                    message = listOf(s.deviceName, s.socName, s.kernelRelease)
                        .filter { it.isNotBlank() }
                        .joinToString("\n"),
                )),
            )
        }
    }

    /** Opens the run sheet on the last run's retained log. */
    /**
     * The last run's row is store-backed, not process-backed: the in-memory log
     * dies with the process (and with a crashed boot), and the store is the
     * authoritative record. The Home summary is seeded from the store on start;
     * opening the row replays the stored log when this process has none.
     */
    private suspend fun seedLastRunStatus() {
        val entry = runCatching { repository.listRunLogs() }.getOrNull()?.firstOrNull()
            ?: return
        val status = if (entry.state == RunLogState.COMPLETED) RunStatus.Success else RunStatus.Failure
        mutableState.update {
            if (it.session.run.status != RunStatus.Idle) it
            else it.copy(session = it.session.copy(run = it.session.run.copy(status = status)))
        }
    }

    private fun replayLine(raw: String): GhostlockLogLine {
        val tag = ToneTag.find(raw)
        val entry = if (tag == null) {
            formatLog(raw)
        } else {
            formatLog(tag.value + raw.removeRange(tag.range))
        }
        return GhostlockLogLine(entry.text.trimEnd('\n'), toneColor(entry.tone))
    }

    fun onOpenLastRunLog() {
        if (state.value.session.run.logLines.isNotEmpty()) {
            mutableState.update {
                it.copy(
                    session = it.session.copy(
                        run = it.session.run.copy(sheetVisible = true, sheetDismissible = !running),
                    ),
                )
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val entry = runCatching { repository.listRunLogs() }.getOrNull()?.firstOrNull()
            if (entry == null) {
                send(GhostlockEffect.Toast(R.string.home_run_none))
                return@launch
            }
            val text = runCatching { repository.readRunLog(entry.stamp) }.getOrNull()
            /* The stored file carries the native's tone tag *after* the elapsed
             * marker (`[A+…] <b> …`), while the live formatter expects it at
             * the start; move it there so the tag becomes a colour and is not
             * shown as text. */
            val lines = text.orEmpty().lineSequence()
                .filter { it.isNotBlank() }
                .map(::replayLine)
                .toList()
                .takeLast(MaxReplayedLogLines)
            val status = if (entry.state == RunLogState.COMPLETED) RunStatus.Success else RunStatus.Failure
            mutableState.update {
                it.copy(
                    session = it.session.copy(
                        run = it.session.run.copy(
                            logLines = lines,
                            status = status,
                            sheetVisible = true,
                            sheetDismissible = true,
                        ),
                    ),
                )
            }
        }
    }


    fun onOpenRunSettings() {
        nav.push(Screen.RunSettings)
    }

    fun onCloseRunSettings() {
        nav.pop()
    }

    fun onOpenGeneration() {
        resetParseSession()
        pendingGeneratedProfile = null
        mutableState.update { it.copy(generation = it.generation.copy(step = GenerationStep.Idle)) }
        nav.push(Screen.Generation)
    }

    fun onCloseGeneration() {
        nav.pop()
    }

    /** Step 4: the generated document is written through the system picker. */
    fun onSaveGeneratedProfile() {
        val pending = pendingGeneratedProfile ?: return
        pendingGeneratedProfile = null
        requestProfileWrite(
            PendingProfileWrite(
                text = pending.text,
                suggestedName = pending.name,
                selectAfterWrite = true,
            ),
        )
    }

    fun onRestartGeneration() {
        resetParseSession()
        pendingGeneratedProfile = null
        mutableState.update { it.copy(generation = it.generation.copy(step = GenerationStep.Idle)) }
    }

    /**
     * Debug-only derivation check (`am start --ez derive_test true --es
     * derive_image <local image path>`): runs the real `parseSource` path on the
     * image and records whether the timer analyzer's block survives into the
     * generated document and — the fix's contract — into the options superset's
     * `document`, which `OptionSuperset.cut` clones for the exported profile.
     * Writes `files/derive-test.txt` and one `derive_test:` log line per fact, so
     * a device check needs no UI and no SAF export.
     */
    fun runDeriveTest(imagePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val out = StringBuilder()
            fun record(line: String) {
                appendLog("derive_test: $line")
                Log.i(DeriveTestTag, line)
                out.appendLine(line)
            }
            try {
                val result = parseSourceUseCase(imagePath, onLog = { Log.i(DeriveTestTag, it) })
                val options = when (result) {
                    is ParseResult.Parsed -> result.options
                    is ParseResult.Ambiguous -> result.options
                    else -> null
                }
                val generated = (result as? ParseResult.Parsed)?.documentText
                val generatedMap = generated?.let { HoconSupport.parseValue(it).asValueMap() }
                record(
                    "generated present=${generatedMap != null} " +
                        "timer=${generatedMap?.get(Timer98260.BLOCK_KEY) != null} " +
                        "fakeSupported=${generatedMap?.let { Timer98260.fakeSupported(it) }}",
                )
                val optionsDoc = options?.document
                record(
                    "options.document present=${optionsDoc != null} " +
                        "timer=${optionsDoc?.get(Timer98260.BLOCK_KEY) != null} " +
                        "fakeSupported=${optionsDoc?.let { Timer98260.fakeSupported(it) }}",
                )
                record(
                    "result=${result.javaClass.simpleName} " +
                        "timerDerived=${(result as? ParseResult.Parsed)?.timerDerived ?: false}",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                record("error=${error.message}")
            } finally {
                runCatching {
                    application.filesDir.resolve("derive-test.txt").writeText(out.toString())
                }
            }
        }
    }

    fun onCloseExecutionSheet() {
        if (running && !state.value.session.run.sheetDismissible) return
        mutableState.update { it.copy(session = it.session.copy(run = it.session.run.copy(sheetVisible = false))) }
    }

    fun copyLogs() {
        val text = state.value.session.run.logLines.joinToString(separator = "") { it.text }
        send(GhostlockEffect.Clipboard(text))
        send(GhostlockEffect.Toast(R.string.copied))
    }

    fun parseOffsets() {
        resetParseSession()
        parseStep = ParseStep.ModePick
        mutableState.update {
            it.copy(
                overlay = it.overlay.copy(dialog = AppDialog.ListDialog(
                    titleRes = R.string.parse_title,
                    itemResIds = listOf(
                        R.string.parse_option_payload,
                        R.string.parse_option_boot,
                    ),
                )),
            )
        }
    }

    /**
     * The home entry: one sheet with the two profile sources. The OTA path
     * keeps its URL input; the on-device path accepts boot.img, a raw Image,
     * payload.bin or an OTA zip in one picker (the extractor detects the
     * format from the bytes).
     */
    fun promptParseSource() {
        resetParseSession()
        parseStep = ParseStep.SourcePick
        mutableState.update {
            it.copy(
                overlay = it.overlay.copy(dialog = AppDialog.ListDialog(
                    titleRes = R.string.parse_source_title,
                    itemResIds = listOf(
                        R.string.action_parse_ota,
                        R.string.action_parse,
                    ),
                )),
            )
        }
    }

    private fun resetParseSession() {
        pendingParse = null
        parseStep = ParseStep.Idle
        physVendorBootOffered = false
        physXblOffered = false
        physUefiOffered = false
        physDtboOffered = false
    }

    fun promptParseUrl() {
        mutableState.update {
            it.copy(
                overlay = it.overlay.copy(dialog = AppDialog.Input(
                    titleRes = R.string.parse_url_title,
                    labelRes = R.string.parse_url_hint,
                    confirmLabelRes = R.string.parse_start,
                )),
            )
        }
    }

    fun onDocumentResult(request: DocumentRequest, uri: String) {
        when (request) {
            DocumentRequest.SelectProfile -> selectExternalProfile(uri)
            DocumentRequest.BootImage -> stageBoot(uri)
            DocumentRequest.XblImage -> stageXbl(uri)
            DocumentRequest.UefiImage -> stageUefi(uri)
            DocumentRequest.VendorBootImage -> stageVendorBoot(uri)
            DocumentRequest.DtboImage -> stageDtbo(uri)
            DocumentRequest.PayloadImage -> stagePayload(uri)
        }
    }

    /**
     * Root-manager picker: scan now (no cache), then list the candidates with
     * their verdict plus Auto. Selecting one only records a preference; the
     * run re-scans and refuses to start if nothing suitable is found.
     */
    fun onOpenManagerPicker() {
        viewModelScope.launch {
            /* Only suitable candidates are offered; a scan that found a
             * mis-shaped or non-late-load binary is not a choice the user can
             * act on. The run log still records every scanned candidate. */
            val candidates = runCatching { repository.managerCandidates() }
                .getOrDefault(emptyList())
                .filter { it.suitable }
            val selected = repository.selectedManagerPackage()
            managerChoices = candidates
            if (candidates.isEmpty()) {
                /* The blocking warning belongs to the run attempt (runExploit's
                 * pre-flight); opening the picker with nothing to list says so
                 * briefly instead of raising the same dialog out of context. */
                send(GhostlockEffect.Toast(R.string.manager_picker_empty))
                return@launch
            }
            val selectedIndex = candidates.indexOfFirst { it.packageName == selected }
            managerPickerOpen = true
            mutableState.update {
                it.copy(
                    overlay = it.overlay.copy(dialog = AppDialog.ListDialog(
                        titleRes = R.string.manager_picker_title,
                        items = candidates.map { candidate ->
                            "${candidate.label} (${candidate.packageName})"
                        },
                        leadingResId = R.string.manager_summary_auto,
                        currentIndex = if (selectedIndex >= 0) selectedIndex else -1,
                    )),
                )
            }
        }
    }

    fun onDialogItemSelected(index: Int) {
        if (managerPickerOpen) {
            managerPickerOpen = false
            val packageName = if (index == 0) null else managerChoices.getOrNull(index - 1)?.packageName
            repository.setSelectedManagerPackage(packageName)
            mutableState.update { it.copy(
                session = it.session.copy(access = it.session.access.copy(selectedManagerPackage = packageName)),
            ) }
            dismissDialog()
            return
        }
        dismissDialog()
        when (val step = parseStep) {
            ParseStep.ModePick -> when (index) {
                0 -> pickPayload()
                1 -> pickBoot()
            }

            ParseStep.SourcePick -> {
                parseStep = ParseStep.ModePick
                when (index) {
                    0 -> promptParseUrl()
                    1 -> parseOffsets()
                }
            }

            is ParseStep.Phys -> {
                if (index > 1) {
                    // no-op; keep the dialog state intact
                } else if (index == 0) {
                    when (step.image) {
                        PhysImage.VENDOR_BOOT -> send(GhostlockEffect.PickDocument(DocumentRequest.VendorBootImage))
                        PhysImage.XBL -> send(GhostlockEffect.PickDocument(DocumentRequest.XblImage))
                        PhysImage.UEFI -> send(GhostlockEffect.PickDocument(DocumentRequest.UefiImage))
                        PhysImage.DTBO -> send(GhostlockEffect.PickDocument(DocumentRequest.DtboImage))
                    }
                } else {
                    val after = step.after
                    parseStep = ParseStep.Idle
                    if (after != null) {
                        viewModelScope.launch(Dispatchers.IO) { advancePhys(after) }
                    } else {
                        launchParse()
                    }
                }
            }

            is ParseStep.Plan -> {
                val parse = step.parse
                val options = parse.options
                val plans = step.plans
                val pageOffered = step.decision.pageOffered
                val choices = step.decision.options
                parseStep = ParseStep.Idle
                if (options == null) return
                if (pageOffered && index == 0) {
                    /* Page-based: the lock-source choice still applies when a
                     * safe anchor exists. */
                    if (plans.anchorOnly != null && showLockDialog(parse)) return
                    val cut = options.cut(plans.route, plans.page, options.device)
                        ?: return
                    appendLog("option: page (no page-free)")
                    viewModelScope.launch { continueParsed(parse, cut) }
                    return
                }
                val plan = choices.getOrNull(index - if (pageOffered) 1 else 0) ?: return
                val cut = options.cut(plans.route, plan, options.device)
                    ?: return
                appendLog(
                    "option: " + plan.label + if (plan.conditioned) " (needs review)" else "",
                )
                viewModelScope.launch { continueParsed(parse, cut) }
            }

            is ParseStep.Lock -> {
                val parse = step.parse
                parseStep = ParseStep.Idle
                val document = parse.documentText
                if (document != null) {
                    if (index == 1) {
                        appendLog("lock source: anchor")
                        viewModelScope.launch { continueParsed(parse, document) }
                    } else {
                        val page = repository.applyLockSourceToDocument(
                            document,
                            keepAnchor = false,
                        )
                        if (page == null) {
                            appendLog("result: could not remove the lock anchor; export cancelled")
                        } else {
                            appendLog("lock source: page (lock anchor removed)")
                            viewModelScope.launch { continueParsed(parse, page) }
                        }
                    }
                }
            }

            ParseStep.Idle -> Unit
        }
    }

    /** Ends the stepped flow on its failed step. */
    private fun markGenerationFailed() {
        mutableState.update { it.copy(generation = it.generation.copy(step = GenerationStep.Failed)) }
    }

    private fun finishGeneratedProfile(
        document: String,
        name: String?,
        release: String,
    ) {
        val text = document
        val suggested = name?.takeIf { it.endsWith(".conf") }
            ?: exportDocumentName(release)
        if (nav.current == Screen.Generation) {
            /* The stepped flow ends on its save step; the write starts there. */
            pendingGeneratedProfile = PendingGeneratedProfile(text, suggested)
            mutableState.update { it.copy(generation = it.generation.copy(step = GenerationStep.Save)) }
            return
        }
        requestProfileWrite(
            PendingProfileWrite(
                text = text,
                suggestedName = suggested,
                selectAfterWrite = true,
            ),
        )
    }

    fun onDialogInputChange(value: String) = mutableState.update { state ->
        val input = state.overlay.dialog as? AppDialog.Input ?: return@update state
        state.copy(overlay = state.overlay.copy(dialog = input.copy(value = value)))
    }

    fun onDialogConfirm(value: String) {
        val dialog = state.value.overlay.dialog
        val selectUri = pendingSelectUri
        pendingSelectUri = null
        dismissDialog()
        when {
            selectUri != null -> selectExternalProfile(selectUri)
            dialog is AppDialog.Input -> parseUrl(value)
            dialog is AppDialog.NotificationPermission ->
                send(GhostlockEffect.RequestNotificationPermission)

            else -> Unit
        }
    }

    fun onDialogDismiss() {
        when (parseStep) {
            is ParseStep.Plan -> {
                parseStep = ParseStep.Idle
                appendLog("result: option choice cancelled")
            }

            is ParseStep.Lock -> {
                parseStep = ParseStep.Idle
                appendLog("result: lock source choice cancelled")
            }

            ParseStep.SourcePick -> {
                parseStep = ParseStep.Idle
                appendLog("result: profile source choice cancelled")
            }

            else -> Unit
        }
        pendingSelectUri = null
        if (state.value.generation.step == GenerationStep.Confirm) {
            mutableState.update { it.copy(generation = it.generation.copy(step = GenerationStep.Idle)) }
        }
        dismissDialog()
    }

    /* ---- the slab-stride decision sheet ---- */

    fun onStrideOptionSelected(value: Int) {
        mutableState.update { state ->
            val picker = state.overlay.stridePicker ?: return@update state
            state.copy(overlay = state.overlay.copy(stridePicker = picker.copy(selectedValue = value)))
        }
    }

    fun onStrideUse() {
        val value = state.value.overlay.stridePicker?.selectedValue ?: return
        finishStride(value)
    }

    /**
     * Applies the chosen stride to the in-memory generated document and asks
     * where to export it — in the measured-db model the export is the save.
     */
    private fun finishStride(value: Int) {
        val parse = pendingStrideParse
        val document = pendingStrideDocument
        val name = pendingStrideName
        val release = pendingStrideRelease
        val probe = pendingStrideProbe
        pendingStrideParse = null
        pendingStrideProbe = null
        pendingStrideDocument = null
        pendingStrideName = null
        pendingStrideRelease = null
        mutableState.update { it.copy(overlay = it.overlay.copy(stridePicker = null)) }
        if (document == null || release == null) return
        /* The export carries the value: a generated profile must not depend on
         * this device's measured database to find its slab stride. */
        val materialized = repository.materializeStride(document, value)
        if (materialized == null) {
            appendLog("result: could not write the stride into the document; export cancelled")
            return
        }
        val evidence = probe?.let {
            "probe gcd=0x${it.gcd.toString(16)} rounds=${it.rounds} samples=${it.samples}"
        } ?: "generation probe"
        viewModelScope.launch {
            val recorded = runCatching {
                repository.recordGenerationStride(release, value, evidence)
            }.getOrDefault(false)
            appendLog(
                if (recorded) {
                    "stride: $value written into the profile (recorded as evidence)"
                } else {
                    "stride: $value written into the profile (a root measurement is already stored)"
                },
            )
            finishGeneratedProfile(materialized, name, release)
            parse?.takeIf { it.missing.isNotEmpty() }?.let(::showMissingSidecarNotice)
        }
    }

    /** The root-measured stride for this kernel, when the measured DB has one. */
    private suspend fun rootMeasuredStride(release: String): Int? = runCatching {
        repository.measurements()
            .firstOrNull { it.release == release }
            ?.facts
            ?.firstOrNull { it.key == MeasuredProfileStore.KeyMmStructSz }
            ?.value
            ?.toInt()
    }.getOrNull()

    /** "Export log & report" from the failed-run notice: dismiss, then the picker. */
    fun onDialogExportLog() {
        dismissDialog()
        onExportRunLog()
    }

    override fun onCleared() {
        repository.close()
        effectChannel.close()
        super.onCleared()
    }

    private suspend fun refreshSnapshot() {
        val snapshot = withContext(Dispatchers.IO) { loadKernelSnapshot() }
        /* Validate the resolved profile here so the run button can grey out. */
        val loaded = withContext(Dispatchers.IO) {
            runCatching { profileController.load(snapshot.kernelRelease) }.getOrNull()
        }
        val moduleState = withContext(Dispatchers.IO) { moduleFacts(loaded) }
        kernelSnapshot = snapshot
        loaded?.let { resolvedProfile = it }
        mutableState.update {
            it.copy(
                session = it.session.copy(
                    device = it.session.device.copy(
                        deviceName = snapshot.deviceName,
                        kernelRelease = snapshot.kernelRelease,
                        socName = snapshot.socName,
                    ),
                    runSettings = it.session.runSettings.copy(
                        safeModeEnabled = snapshot.safeModeEnabled,
                        forceAttackTestEnabled = snapshot.forceAttackTest,
                        vrKoTagClear = snapshot.vrKoTagClear,
                        vrKoSelinuxBlock = snapshot.vrKoSelinuxBlock,
                    ),
                    access = it.session.access.copy(
                        shizukuEnabled = snapshot.shizukuEnabled,
                        shizukuStatus = snapshot.shizukuStatus,
                        shizukuInstalled = runCatching {
                            repository.shizukuInstalled()
                        }.getOrDefault(false),
                        selectedManagerPackage = repository.selectedManagerPackage(),
                    ),
                    support = it.session.support.copy(
                        hasProfile = loaded?.hasProfile ?: false,
                        profileInvalid = loaded?.invalidPaths?.isNotEmpty() ?: false,
                        route = loaded?.route,
                        layers = loaded?.layers,
                        importedProfilePresent = profileController.importedProfileExists(),
                        routeCandidates = loaded?.routeCandidates.orEmpty(),
                        selectedRoute = loaded?.selectedRoute,
                        carrierCandidates = loaded?.carrierCandidates.orEmpty(),
                        selectedCarrier = loaded?.selectedCarrier,
                        dirtyFragSelectable = loaded?.dirtyFragSelectable ?: false,
                        dirtyFragOn = loaded?.dirtyFragOn ?: false,
                        pipelineOrder = loaded?.pipelineOrder.orEmpty(),
                        module = loaded?.module,
                        moduleAssetVerified = moduleState.first,
                        magiskManager = moduleState.second,
                    ),
                ),
                report = it.report.copy(
                    crashShellAvailable = repository.crashShellStatus() != ShizukuStatus.NOT_RUNNING,
                ),
            )
        }
        refreshMergeRows()
    }

    private fun pickPayload() {
        send(GhostlockEffect.Toast(R.string.parse_pick_payload_hint))
        send(GhostlockEffect.PickDocument(DocumentRequest.PayloadImage))
    }

    private fun pickBoot() {
        send(GhostlockEffect.PickDocument(DocumentRequest.BootImage))
    }

    /** The input image is staged; run the first pass on it alone.
     *
     * The image is all the analyzers need for the first pass: the 98260 timer
     * analyzer never reads the physical pair, and the native extractor reports
     * what the pair still needs. Prompting before any analysis asked for
     * images the timer route does not require, and hid whether the timer was
     * available (which decides whether those images are optional at all). */
    private fun beginParseInput(input: String) {
        pendingParse = ParseRequest(input)
        launchParse()
    }

    private fun launchParse() {
        val request = pendingParse ?: return
        viewModelScope.launch(Dispatchers.IO) { runParse(request) }
    }

    private fun stageBoot(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bootPath = readDocumentUseCase.cache(uri, "boot.img")
                appendLog("boot.img ready: $bootPath")
                beginParseInput(bootPath)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                appendLog("parse error: ${error.message}")
                appendLog("result: parse failed")
                showNotice(R.string.parse_result_title, R.string.parse_failed)
            }
        }
    }

    /** Caches one recommended image and re-runs the pass with it attached. */
    private fun stageSidecar(
        uri: String,
        name: String,
        update: (ParseRequest, String) -> ParseRequest,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val path = readDocumentUseCase.cache(uri, name)
                appendLog("$name ready: $path")
                pendingParse = pendingParse?.let { update(it, path) }
                launchParse()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                appendLog("parse error: ${error.message}")
                appendLog("result: parse failed")
                showNotice(R.string.parse_result_title, R.string.parse_failed)
            }
        }
    }

    private fun stageXbl(uri: String) =
        stageSidecar(uri, "xbl_config.img") { request, path -> request.copy(xblPath = path) }

    private fun stageUefi(uri: String) =
        stageSidecar(uri, "uefi.img") { request, path -> request.copy(uefiPath = path) }

    private fun stageVendorBoot(uri: String) =
        stageSidecar(uri, "vendor_boot.img") { request, path -> request.copy(vendorBootPath = path) }

    private fun stageDtbo(uri: String) =
        stageSidecar(uri, "dtbo.img") { request, path -> request.copy(dtboPath = path) }

    private fun stagePayload(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val payloadPath = readDocumentUseCase.cache(uri, "payload.bin")
                appendLog("payload.bin ready: $payloadPath")
                beginParseInput(payloadPath)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                appendLog("parse error: ${error.message}")
                appendLog("result: parse failed")
                showNotice(R.string.parse_result_title, R.string.parse_failed)
            }
        }
    }

    private fun parseUrl(value: String) {
        val url = value.trim()
        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            appendLog("error: invalid OTA URL: $url")
            appendLog("result: parse failed")
            showNotice(R.string.parse_result_title, R.string.parse_failed_url)
            markGenerationFailed()
            return
        }
        appendLog("parse OTA: $url")
        pendingParse = ParseRequest(url)
        /* A remote payload's xbl_config is auto-extracted; a MediaTek payload's
         * vendor_boot is not, so the follow-up asks for it. */
        physXblOffered = isQualcomm()
        launchParse()
    }

    /** Confirm/notice popup for an extractor outcome; unlike a Toast it waits
     * for the user and can link the matching documentation page. */
    private fun showNotice(titleRes: Int, messageRes: Int, docUrl: String? = null) {
        mutableState.update {
            it.copy(
                overlay = it.overlay.copy(dialog = AppDialog.Notice(
                    titleRes = titleRes,
                    messageRes = messageRes,
                    docUrl = docUrl,
                )),
            )
        }
    }

    /** The sidecar warning deferred while the stride picker was up. */
    private fun showMissingSidecarNotice(result: ParseResult.Parsed) {
        val mediaTek = isMediaTek()
        showNotice(
            titleRes = R.string.parse_result_title,
            messageRes = if (mediaTek) {
                R.string.parse_missing_phys_mediatek
            } else {
                R.string.parse_missing_phys_hint
            },
            docUrl = if (mediaTek) mediatekDocUrl() else null,
        )
    }

    private fun isMediaTek(): Boolean = isMediaTekSoc(kernelSnapshot?.socName.orEmpty())

    private fun isQualcomm(): Boolean = isQualcommSoc(kernelSnapshot?.socName.orEmpty())

    private fun mediatekDocUrl(): String =
        "https://github.com/YuKongA/ghostlock-app/blob/main/docs/kernel_profiles/" +
                if (Locale.getDefault().language == "zh") "MEDIATEK_ZH.md" else "MEDIATEK.md"

    private suspend fun runParse(request: ParseRequest) {
        if (!beginOperation(showLogSheet = false)) return
        clearLog()
        mutableState.update { it.copy(generation = it.generation.copy(step = GenerationStep.Parsing)) }
        try {
            when (
                val result = parseSourceUseCase(
                    request.input, request.xblPath, request.uefiPath,
                    request.vendorBootPath, request.dtboPath,
                    null, ::appendLog,
                )
            ) {
                is ParseResult.Ambiguous -> {
                    val feasible = result.routes.filter { it.verdict == "feasible" }
                    appendLog(
                        "result: ${feasible.size} feasible route(s): " +
                            feasible.joinToString(", ") { it.route },
                    )
                    /* The route picker is gone: the extractor writes the route
                     * superset and the home picker chooses at run time. An
                     * ambiguous parse now means the pipeline could not be
                     * resolved. */
                    appendLog(
                        "result: the extractor could not resolve the profile " +
                            "(no feasible pipeline)",
                    )
                    showNotice(R.string.parse_result_title, R.string.parse_failed)
                    markGenerationFailed()
                }

                is ParseResult.Parsed -> advancePhys(result)

                ParseResult.AlreadyPresent -> {
                    appendLog("result: no profile entry for this kernel")
                    showNotice(R.string.parse_result_title, R.string.offsets_already_exist)
                    markGenerationFailed()
                }

                is ParseResult.Failed -> {
                    result.reason?.let { appendLog("parse failed: $it") }
                    appendLog("result: ${parseFailureResult(result.code)}")
                    showNotice(R.string.parse_result_title, parseFailureToast(result.code))
                    markGenerationFailed()
                }
            }
        } finally {
            endOperation()
        }
    }

    /** The pipeline picker is retired: the extractor derives the order and the
     * run path resolves it before W1. Kept out of the UI deliberately. */

    /**
     * The platform's image advice: ask for the next recommended image while
     * one can still fill the physical pair, otherwise finish the pass.
     */
    private suspend fun advancePhys(result: ParseResult.Parsed) {
        val next = nextPhysImage(physState(result))
        if (next != null) {
            showPhysPrompt(next, after = result)
            return
        }
        if (!generationPhysComplete(
                kernelSnapshot?.socName.orEmpty(),
                result.physLoadPresent,
                result.physOffsetPresent,
            )
        ) {
            /* Only MediaTek can reach this: its sidecars are the sole source
             * of the pair and the runtime has no default to fall back on.
             * Elsewhere the sidecar advice is optional. The 98260 timer route
             * never reads the pair, so a derived block still yields a usable
             * timer-only profile instead of a partial GhostLock one. */
            val timerOnly = if (result.timerDerived) {
                TimerOnlyDocument.from(result.documentText)
            } else {
                null
            }
            if (timerOnly != null) {
                appendLog(
                    "warning: the physical layout is incomplete; " +
                        "deriving the 98260 timer route only",
                )
                finishTimerOnly(result, timerOnly)
                return
            }
            appendLog(
                "warning: the physical layout is incomplete; attach the missing image and re-parse",
            )
            showNotice(
                titleRes = R.string.parse_result_title,
                messageRes = R.string.parse_missing_phys_mediatek,
                docUrl = mediatekDocUrl(),
            )
            markGenerationFailed()
            return
        }
        finishParsed(result)
    }

    private fun physState(result: ParseResult.Parsed) = PhysPromptState(
        mediaTek = isMediaTek(),
        qualcomm = isQualcomm(),
        physSource = result.physSource,
        offsetPresent = result.physOffsetPresent,
        vendorBootOffered = physVendorBootOffered,
        xblOffered = physXblOffered,
        uefiOffered = physUefiOffered,
        dtboOffered = physDtboOffered,
    )

    /** One per-platform prompt: attach the recommended image or skip it. */
    private fun showPhysPrompt(image: PhysImage, after: ParseResult.Parsed?) {
        parseStep = ParseStep.Phys(image, after)
        when (image) {
            PhysImage.VENDOR_BOOT -> physVendorBootOffered = true
            PhysImage.XBL -> physXblOffered = true
            PhysImage.UEFI -> physUefiOffered = true
            PhysImage.DTBO -> physDtboOffered = true
        }
        val title = when (image) {
            PhysImage.VENDOR_BOOT -> R.string.parse_phys_vendor_boot_title
            PhysImage.XBL -> R.string.parse_phys_xbl_title
            PhysImage.UEFI -> R.string.parse_phys_uefi_title
            PhysImage.DTBO -> R.string.parse_phys_dtbo_title
        }
        val attach = when (image) {
            PhysImage.VENDOR_BOOT -> R.string.parse_phys_attach_vendor_boot
            PhysImage.XBL -> R.string.parse_phys_attach_xbl
            PhysImage.UEFI -> R.string.parse_phys_attach_uefi
            PhysImage.DTBO -> R.string.parse_phys_attach_dtbo
        }
        mutableState.update {
            it.copy(
                generation = it.generation.copy(step = GenerationStep.Confirm),
                overlay = it.overlay.copy(dialog = AppDialog.ListDialog(
                    titleRes = title,
                    /* Say what the image determines and what skipping means on
                     * this platform: without the pair MediaTek cannot derive
                     * the GhostLock route at all, while the other families use
                     * a compiled default and treat the image as calibration. */
                    messageRes = physPromptMessage(after),
                    itemResIds = listOf(attach, R.string.parse_phys_skip),
                )),
            )
        }
    }

    /** The platform-specific meaning of the physical pair, for the image prompt. */
    private fun physPromptMessage(after: ParseResult.Parsed?): Int {
        val defaults = physRule(kernelSnapshot?.socName.orEmpty()).satisfiedByDefaults
        return when {
            !defaults && after?.timerDerived == true -> R.string.parse_phys_timer_hint
            defaults -> R.string.parse_phys_default_hint
            else -> R.string.parse_phys_required_hint
        }
    }

    /** The timer-only outcome: no route, stride or lock plan applies. */
    private suspend fun finishTimerOnly(result: ParseResult.Parsed, document: String) {
        refreshSnapshot()
        result.route?.let { appendLog("route: $it") }
        appendLog("profile generated (98260 timer route only): ${result.releases.joinToString()}")
        appendLog("result: profile generated; export it to use it")
        continueParsed(result.copy(documentText = document), document, skipStride = true)
    }

    /** The Parsed branch once the image advice is satisfied. */
    private suspend fun finishParsed(result: ParseResult.Parsed) {
        val document = result.documentText
        if (document == null) {
            appendLog("result: the extractor produced no document")
            showNotice(R.string.parse_result_title, R.string.parse_failed)
            markGenerationFailed()
            return
        }
        refreshSnapshot()
        result.route?.let { appendLog("route: $it") }
        result.anchor?.let {
            appendLog(
                "anchor: " + it.removePrefix("info: ").removePrefix("warning: "),
            )
        }
        appendLog("profile generated: ${result.releases.joinToString()}")
        appendLog("result: profile generated; export it to use it")
        val options = result.options
        /* Multi-route superset: export the document as generated. The route
         * choice belongs to the home picker at run time, not to a generation
         * cut. A single-route profile keeps the page/anchor plan step below. */
        val feasibleRoutes = options?.routes?.count { it.verdict == OptionSuperset.Feasible } ?: 0
        if (feasibleRoutes > 1) {
            appendLog(
                "result: route superset ($feasibleRoutes routes); " +
                    "the home picker chooses at run time",
            )
            continueParsed(result, document)
            return
        }
        /* Page-free options first, per the operator rule: one safe option is
         * taken automatically, several are offered without a page row, and
         * page is offered only when no safe option exists. */
        val plans = options?.let { superset ->
            val route = result.route
                ?: superset.routes.firstOrNull { it.verdict == OptionSuperset.Feasible }?.route
            route?.let { superset.plansFor(it) }
        }
        if (options != null && plans != null) {
            when (val decision = plans.decision()) {
                is OptionDecision.Auto -> {
                    val cut = options.cut(
                        plans.route,
                        decision.plan,
                        options.device,
                    )
                    if (cut != null) {
                        appendLog("option: ${decision.plan.label} (auto)")
                        continueParsed(result, cut)
                        return
                    }
                }
                is OptionDecision.Offer -> {
                    showPlanDialog(result, plans, decision)
                    return
                }
                OptionDecision.NoPageFree -> Unit
            }
        }
        /* No page-free option: the lock source remains the user's choice. */
        if (showLockDialog(result)) return
        continueParsed(result, document)
    }

    /** The page/anchor lock-source dialog; false when there is no anchor. */
    private fun showLockDialog(result: ParseResult.Parsed): Boolean {
        if (!result.hasAnchor) return false
        parseStep = ParseStep.Lock(result)
        val options = result.options
        val route = result.route
            ?: options?.routes?.firstOrNull { it.verdict == OptionSuperset.Feasible }?.route
        val anchorOnly = route?.let { options?.plansFor(it)?.anchorOnly }
        val anchorSafety = anchorOnly?.let {
            RegionSafety.classify(it.anchorName, it.scratchSource, Build.MANUFACTURER)
        }
        mutableState.update {
            it.copy(
                generation = it.generation.copy(step = GenerationStep.Confirm),
                overlay = it.overlay.copy(dialog = AppDialog.ListDialog(
                    titleRes = R.string.parse_lock_title,
                    itemResIds = listOf(
                        R.string.parse_lock_page,
                        R.string.parse_lock_anchor,
                    ),
                    grades = listOf(null, anchorSafety),
                )),
            )
        }
        return true
    }

    /**
     * The plan row's user-facing text: both roles are named, because the same
     * region can supply the lock and W1's value, or only one of them. The
     * technical [OptionPlan.label] stays in the logs.
     */
    private fun planDisplayLabel(plan: OptionPlan): String {
        val anchor = plan.anchorName ?: return plan.label
        val scratch = plan.scratchSource?.takeIf { it != ScratchInAnchor }
        return if (scratch == null) {
            application.getString(R.string.parse_plan_label_same, anchor)
        } else {
            application.getString(R.string.parse_plan_label_pair, anchor, scratch)
        }
    }

    /** The page-free option list; the UI adds a page row only when offered. */
    private fun showPlanDialog(
        result: ParseResult.Parsed,
        plans: RoutePlans,
        decision: OptionDecision.Offer,
    ) {
        parseStep = ParseStep.Plan(result, plans, decision)
        mutableState.update {
            it.copy(
                generation = it.generation.copy(step = GenerationStep.Confirm),
                overlay = it.overlay.copy(dialog = AppDialog.ListDialog(
                    titleRes = if (decision.pageOffered) {
                        R.string.parse_plan_title_conditional
                    } else {
                        R.string.parse_plan_title
                    },
                    items = decision.options.map(::planDisplayLabel),
                    /* A kernel region's safety is a property of the region, not
                     * of the plan: the Samsung serial array is the one
                     * conditional region (see RegionSafety). */
                    grades = decision.options.map { plan ->
                        RegionSafety.classify(plan.anchorName, plan.scratchSource, Build.MANUFACTURER)
                    },
                    leadingResId = if (decision.pageOffered) {
                        R.string.parse_plan_page
                    } else {
                        null
                    },
                )),
            )
        }
    }

    /**
     * The stride probe and the export are shared by the no-anchor path and the
     * post-choice path: [document] is what will be exported (page mode has had
     * the `lock_anchor_*` triple removed in memory).
     */
    private suspend fun continueParsed(
        result: ParseResult.Parsed,
        document: String,
        skipStride: Boolean = false,
    ) {
        /* The live stride probe only makes sense for the running kernel; a
         * profile generated for another image skips it. */
        val runningRelease = kernelSnapshot?.kernelRelease
        /* The cut is a whole document cloned from the (timer-merged) option
         * superset, so the timer block travels with it. */
        val exportDocument = document
        val outcome: StrideProbeOutcome? = if (!skipStride && runningRelease != null &&
            runningRelease in result.releases &&
            repository.documentRequiresStride(exportDocument)
        ) {
            appendLog("stride: probing the mm_struct slab on the running kernel")
            /* The probe blocks for seconds to minutes. This is the generation
             * flow's own wait, not an attack run, so it gets a dedicated
             * non-dismissible progress dialog rather than the run log sheet
             * (which is a developer view and should stay one). */
            mutableState.update {
                it.copy(
                    overlay = it.overlay.copy(
                        dialog = AppDialog.Progress(
                            titleRes = R.string.gen_probing_title,
                            messageRes = R.string.gen_probing_detail,
                        ),
                    ),
                )
            }
            if (!beginOperation(showLogSheet = false)) {
                mutableState.update {
                    it.copy(session = it.session.copy(run = it.session.run.copy(running = true)))
                }
            }
            send(GhostlockEffect.KeepScreenAwake(true))
            try {
                runCatching {
                    repository.probeStride(runningRelease, exportDocument, ::appendLog)
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    StrideProbeOutcome.Failed(error.message ?: "probe error")
                }
            } finally {
                send(GhostlockEffect.KeepScreenAwake(false))
                endOperation()
                mutableState.update { current ->
                    if (current.overlay.dialog is AppDialog.Progress) {
                        current.copy(overlay = current.overlay.copy(dialog = null))
                    } else {
                        current
                    }
                }
            }
        } else {
            null
        }
        when (val step = strideStep(outcome)) {
            is StrideStep.Picker -> {
                val strideProbe = step.probe
                /* Probed implies the probe ran, which implies runningRelease. */
                val release = requireNotNull(runningRelease)
                pendingStrideParse = result
                pendingStrideProbe = strideProbe
                pendingStrideDocument = exportDocument
                pendingStrideName = result.documentName
                pendingStrideRelease = release
                val recommendation = recommendStride(
                    strideProbe,
                    confirmedValue = rootMeasuredStride(release),
                )
                appendLog(
                    "stride: ${strideProbe.candidates.size} candidate(s), " +
                        "gcd=0x${strideProbe.gcd.toString(16)}, " +
                        "rounds=${strideProbe.rounds}, samples=${strideProbe.samples}",
                )
                mutableState.update {
                    it.copy(
                        overlay = it.overlay.copy(stridePicker = StridePickerUiState(
                            recommendation = recommendation,
                            selectedValue = recommendation.recommendedValue,
                            footerRes = R.string.stride_footer_export,
                        )),
                        session = it.session.copy(run = it.session.run.copy(sheetVisible = false)),
                    )
                }
            }

            is StrideStep.Fail -> {
                /* The probe ran (the document targets the running kernel) and
                 * failed. A profile saved without its measured stride cannot be
                 * imported or run, so end on the failed step — never the save
                 * step, which would hand the user a dead profile. */
                appendLog("result: stride probe failed: ${step.reason}; no profile generated")
                mutableState.update {
                    it.copy(session = it.session.copy(run = it.session.run.copy(sheetVisible = false)))
                }
                markGenerationFailed()
                return
            }

            StrideStep.Save -> {
                /* No stride picker: the save prompt takes over. Close the probe
                 * sheet first so the SAF activity is not left behind a modal. */
                mutableState.update { it.copy(session = it.session.copy(run = it.session.run.copy(sheetVisible = false))) }
                finishGeneratedProfile(
                    exportDocument,
                    result.documentName,
                    runningRelease ?: result.releases.firstOrNull() ?: "profile",
                )
            }
        }
        if (result.missing.isNotEmpty()) {
            appendLog(
                "warning: missing ${result.missing.joinToString()}; " +
                    "attach the platform image or fill the fields manually",
            )
        }
    }

    private fun dismissDialog() {
        managerPickerOpen = false
        mutableState.update { it.copy(overlay = it.overlay.copy(dialog = null)) }
    }

    private fun appendLog(line: String) {
        /* The final result lines are Kotlin-side; tag them so they read as <k>. */
        val tagged = if (line.startsWith("result:")) "<k> $line" else line
        val entry = formatLog(tagged)
        val uiLine = GhostlockLogLine(entry.text, toneColor(entry.tone))
        mutableState.update { it.copy(
            session = it.session.copy(run = it.session.run.copy(logLines = it.session.run.logLines + uiLine)),
        ) }
    }

    /** Starts a fresh log for the operation that is about to run. */
    private fun clearLog() {
        mutableState.update {
            it.copy(session = it.session.copy(run = it.session.run.copy(logLines = emptyList())))
        }
    }

    private fun beginOperation(showLogSheet: Boolean = true): Boolean {
        if (running) return false
        running = true
        mutableState.update {
            if (showLogSheet) {
                it.copy(
                    session = it.session.copy(
                        run = it.session.run.copy(running = true, sheetVisible = true, sheetDismissible = false),
                    ),
                )
            } else {
                it.copy(
                    session = it.session.copy(run = it.session.run.copy(running = true)),
                )
            }
        }
        return true
    }

    private fun endOperation() {
        running = false
        mutableState.update { it.copy(
            session = it.session.copy(run = it.session.run.copy(running = false, sheetDismissible = true)),
        ) }
    }

    private fun send(effect: GhostlockEffect) {
        effectChannel.trySend(effect)
    }

    private fun toneColor(tone: LogTone): Int = when (tone) {
        LogTone.Error -> 0xFFFF6B6B.toInt()
        LogTone.Success -> 0xFF5FD68A.toInt()
        LogTone.Warning -> 0xFFFFC94D.toInt()
        LogTone.Progress -> 0xFF60A5FA.toInt()
        LogTone.Kotlin -> 0xFF5EEAD4.toInt()
        LogTone.Shizuku -> 0xFFC084FC.toInt()
        LogTone.Default -> -1
    }

    private fun parseFailureToast(code: Int): Int = when (code) {
        3, 4 -> R.string.parse_failed_route
        5 -> R.string.parse_failed_kallsyms
        6 -> R.string.parse_failed_fixed
        -1 -> R.string.parse_timeout
        else -> R.string.parse_failed
    }

    private fun parseFailureResult(code: Int): String = when (code) {
        3, 4 -> "exploit chain unsupported by this kernel"
        5 -> "kernel symbol table could not be recovered"
        6 -> "kernel has fixed the vulnerability"
        -1 -> "parse timed out"
        else -> "parse failed"
    }

    private data class PendingGeneratedProfile(val text: String, val name: String)
}
