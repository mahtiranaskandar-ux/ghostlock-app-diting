package com.sesame.app.ui.state

import com.sesame.app.domain.model.VrKoMode

/**
 * The narrow action surfaces the screens take. Each one names only the verbs
 * its screen uses; the view model adapts them onto its own methods.
 */
interface HomeActions {
    fun run()
    fun statusClick()
    fun chooseRoute()
    fun showDeviceInfo()
    fun openLastRunLog()
    fun exportReport()
    fun openAdvanced()
    fun openGeneration()
    fun selectProfileFile()
    fun openParameters()
}

interface RunSettingsActions {
    fun safeModeChanged(enabled: Boolean)
    fun shizukuChanged(enabled: Boolean)
    fun openVrKo()
    fun close()
}

interface AdvancedActions {
    fun close()
    fun openRunSettings()
    fun forceAttackChanged(enabled: Boolean)
    fun openManagerPicker()
    fun openParameters()
    fun openMeasurements()
    fun debugKernelLogChanged(enabled: Boolean)
    fun showAbout()
}

interface ParametersActions {
    fun close()
    fun openBuiltin()
    fun exportProfile()
    fun clearExternalProfile()
}

/**
 * The report flow's verbs, including the run log's own execution sheet: that
 * sheet is the run-log overlay, so its close, copy and manager-open travel
 * with the report surface rather than with a screen of their own.
 */
interface ReportActions {
    /** Open the report flow (a shell capture or kernel log skips to Export). */
    fun onOpenExportRunLog()
    fun onAddBugreport()
    fun onReportGenerateBugreport()
    fun onCopyBugreportCommand()
    fun onOpenDeveloperOptions()
    fun onOpenAboutPhone()
    fun onReportNext()
    fun onReportBack()
    fun onReportGoToImport()
    fun onReportAppend()
    fun onReportExportOnly()
    fun onReportRecheckDeveloper()
    fun onReportShare()
    fun onRunLogPickerDismiss()
    fun onClearRunLogs()
    fun onExportReportBundle()
    fun onBugreportPicked(uri: String?)

    /* The execution sheet's own verbs. */
    fun onCloseExecutionSheet()
    fun onCopyLogs()
    fun onOpenManager()
}

interface OverlayActions {
    fun onDialogItemSelected(index: Int)
    fun onDialogInputChange(value: String)
    fun onDialogConfirm(value: String)
    fun onDialogDismiss()
    fun onDialogExportLog()
    /* The route matrix's draft, applied on 保存. */
    fun onRouteMatrixSave(
        backend: String,
        syscall: String?,
        carrier: String?,
        dirtyFragOn: Boolean?,
    )
    fun onStrideOptionSelected(value: Int)
    fun onStrideUse()
    fun onStrideSkip()
    fun onStridePickerDismiss()
    fun onOverwriteConfirm()
    fun onOverwriteDismiss()
}

interface GenerationActions {
    fun onOpenGeneration()
    fun onCloseGeneration()
    fun onSaveGeneratedProfile()
    fun onRestartGeneration()
    fun onParseOta()
    fun onParseImage()
}

interface BuiltinActions {
    fun onOpenBuiltinProfiles()
    fun onCloseBuiltinProfiles()
    fun onSelectBuiltinProfile(release: String?)
}

interface VrKoActions {
    fun tagClearChanged(mode: VrKoMode)
    fun selinuxBlockChanged(mode: VrKoMode)
    fun close()
}

interface MeasurementsActions {
    fun close()
}

interface MergePreviewActions {
    fun close()
    fun exportMerged()
}
