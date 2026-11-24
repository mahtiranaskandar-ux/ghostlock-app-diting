package com.sesame.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.sesame.app.R
import com.sesame.app.domain.model.CrashEvidenceInfo
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * The report flow's screens. Choice decides whether a system bug report is
 * attached; a shell-backed capture with a kernel log skips straight to Export.
 */
enum class ReportWizardStep { Choice, Developer, Generate, Import, Export }

/** The last attack run's state; the run-status notification mirrors it. */
enum class RunStatus { Idle, Running, Success, Failure }

/** The profile-generation flow's step; the generation screen renders one at a time. */
enum class GenerationStep { Idle, Parsing, Confirm, Save, Failed }

data class GhostlockLogLine(val text: String, val color: Int)

@Composable
internal fun GhostlockApp(
    session: SessionState,
    report: ReportState,
    overlay: OverlayState,
    generation: GenerationState,
    builtin: BuiltinState,
    nav: NavController,
    homeActions: HomeActions,
    runSettingsActions: RunSettingsActions,
    advancedActions: AdvancedActions,
    parameterActions: ParametersActions,
    reportActions: ReportActions,
    overlayActions: OverlayActions,
    generationActions: GenerationActions,
    builtinActions: BuiltinActions,
    vrKoActions: VrKoActions,
    measurementsActions: MeasurementsActions,
    mergePreviewActions: MergePreviewActions,
) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        val backStack = nav.backStack
        val swipeBack = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
            NavSwipeDirection.RightToLeft
        } else {
            NavSwipeDirection.LeftToRight
        }
        /* The back ladder's last rung: every screen's close verb is its own
         * bundle's; About's is a plain pop. */
        fun closeScreen(screen: Screen) {
            when (screen) {
                Screen.Main -> Unit
                Screen.VrKo -> vrKoActions.close()
                Screen.Advanced -> advancedActions.close()
                Screen.RunSettings -> runSettingsActions.close()
                Screen.Generation -> generationActions.onCloseGeneration()
                Screen.About -> nav.pop()
                Screen.Measurements -> measurementsActions.close()
                Screen.Parameters -> parameterActions.close()
                Screen.Builtin -> builtinActions.onCloseBuiltinProfiles()
                Screen.MergePreview -> mergePreviewActions.close()
            }
        }
        Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.fillMaxSize(),
                    effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
                    onBack = {
                        when {
                            session.run.sheetVisible -> {
                                if (session.run.sheetDismissible) {
                                    reportActions.onCloseExecutionSheet()
                                }
                            }

                            overlay.dialog != null -> overlayActions.onDialogDismiss()
                            else -> {
                                if (nav.canPop) {
                                    val screen = nav.current
                                    if (screen != Screen.Main) closeScreen(screen)
                                }
                            }
                        }
                    },
                ) {
                    entry<Screen.Main>(swipeDismiss = NavSwipeDirection.None) {
                        MainScreen(session = session, actions = homeActions)
                    }
                    entry<Screen.VrKo>(swipeDismiss = swipeBack) {
                        VrKoScreen(state = session, actions = vrKoActions)
                    }
                    entry<Screen.Advanced>(swipeDismiss = swipeBack) {
                        AdvancedScreen(state = session, actions = advancedActions)
                    }
                    entry<Screen.RunSettings>(swipeDismiss = swipeBack) {
                        RunSettingsScreen(state = session, actions = runSettingsActions)
                    }
                    entry<Screen.Generation>(swipeDismiss = swipeBack) {
                        GenerationScreen(
                            state = session,
                            generation = generation,
                            actions = generationActions,
                        )
                    }
                    entry<Screen.About>(swipeDismiss = swipeBack) {
                        AboutScreen(onBack = { nav.pop() })
                    }
                    entry<Screen.Measurements>(swipeDismiss = swipeBack) {
                        MeasurementsScreen(state = session, actions = measurementsActions)
                    }
                    entry<Screen.Parameters>(swipeDismiss = swipeBack) {
                        ParameterScreen(state = session, actions = parameterActions)
                    }
                    entry<Screen.Builtin>(swipeDismiss = swipeBack) {
                        BuiltinProfileScreen(
                            state = session,
                            builtin = builtin,
                            actions = builtinActions,
                        )
                    }
                    entry<Screen.MergePreview>(swipeDismiss = swipeBack) {
                        MergePreviewScreen(state = session, actions = mergePreviewActions)
                    }
                }
                GhostlockDialog(state = session, overlay = overlay, actions = overlayActions)
                RunLogPickerDialog(report = report, actions = reportActions)
                StridePickerDialog(overlay = overlay, actions = overlayActions)
                GhostlockExecutionSheet(state = session, report = report, actions = reportActions)
            }
        }
    }
}

@Composable
private fun MainScreen(
    session: SessionState,
    actions: HomeActions,
) {
    HomeScreen(state = session, actions = actions)
}

@Composable
internal fun pageContentPadding(
    scaffoldPadding: PaddingValues,
    top: Dp = 8.dp,
    bottom: Dp = 12.dp,
    horizontalMin: Dp = 12.dp,
): PaddingValues {
    val windowWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val horizontal = ((windowWidth - 800.dp) / 2).coerceAtLeast(horizontalMin)
    return PaddingValues(
        start = horizontal,
        end = horizontal,
        top = scaffoldPadding.calculateTopPadding() + top,
        bottom = scaffoldPadding.calculateBottomPadding() + bottom,
    )
}

/* The report slice rides along by the migration's uniform signature; nothing
 * on this sheet reads it yet, so the parameter is deliberately unused. */
@Suppress("UNUSED_PARAMETER")
@Composable
private fun GhostlockExecutionSheet(
    state: SessionState,
    report: ReportState,
    actions: ReportActions,
) {
    OverlayBottomSheet(
        show = state.run.sheetVisible,
        title = stringResource(R.string.log_title),
        allowDismiss = state.run.sheetDismissible,
        onDismissRequest = actions::onCloseExecutionSheet,
        startAction = {
            IconButton(onClick = actions::onCopyLogs) {
                Icon(
                    imageVector = MiuixIcons.Copy,
                    contentDescription = stringResource(R.string.action_copy),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
        endAction = {
            IconButton(
                enabled = state.run.sheetDismissible,
                onClick = actions::onCloseExecutionSheet,
            ) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.action_close),
                )
            }
        },
        content = {
            if (!state.run.sheetDismissible) {
                /* The design's "running · mm:ss": the elapsed comes from the
                 * run's start stamp, re-read once a second while it runs. */
                var now by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(state.run.startedAtMs, state.run.sheetDismissible) {
                    while (!state.run.sheetDismissible) {
                        now = System.currentTimeMillis()
                        delay(1_000)
                    }
                }
                val elapsedMs = if (state.run.startedAtMs > 0) {
                    (now - state.run.startedAtMs).coerceAtLeast(0)
                } else {
                    0
                }
                Text(
                    text = stringResource(
                        R.string.action_running_elapsed,
                        (elapsedMs / 60_000).toInt(),
                        ((elapsedMs / 1_000) % 60).toInt(),
                    ),
                    modifier = Modifier.padding(bottom = 8.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            LogPanel(
                lines = state.run.logLines,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 520.dp)
                    .navigationBarsPadding(),
            )
            /* The run's visible verdict and the two terminal actions. The
             * manager itself is never auto-launched by the app: on a late-load
             * ROM its own restart is what takes the foreground mid-handoff.
             * The verdict gets its own row: beside two long action labels it
             * wrapped mid-word on a phone. */
            if (state.run.sheetDismissible) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    if (state.run.status != RunStatus.Idle) {
                        Text(
                            text = stringResource(
                                if (state.run.status == RunStatus.Success) {
                                    R.string.run_status_root_active
                                } else {
                                    R.string.run_status_failed
                                },
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            text = stringResource(R.string.run_log_export),
                            onClick = actions::onOpenExportRunLog,
                        )
                        TextButton(
                            text = stringResource(R.string.run_notif_open_manager),
                            onClick = actions::onOpenManager,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun GhostlockDialog(
    state: SessionState,
    overlay: OverlayState,
    actions: OverlayActions,
) {
    val current = overlay.dialog
    /* Keep the last dialog rendered while the overlay plays its exit
     * animation; `show` still follows the single source of truth. */
    var lastDialog by remember { mutableStateOf<AppDialog?>(null) }
    if (current != null) lastDialog = current
    val dialog = current ?: lastDialog
    OverlayDialog(
        show = current != null,
        title = dialog?.let { stringResource(it.titleRes) },
        onDismissRequest = {
            /* The probe's progress dialog is not dismissible: leaving it would
             * strand the generation flow with the probe still running. */
            if (dialog !is AppDialog.Progress) actions.onDialogDismiss()
        },
        content = {
            when (dialog) {
                is AppDialog.RouteMatrix -> RouteMatrixContent(state, actions)

                is AppDialog.ListDialog -> {
                    if (dialog.messageRes != 0) {
                        Text(
                            text = stringResource(dialog.messageRes),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    val items = dialog.items.ifEmpty { dialog.itemResIds.map { stringResource(it) } }
                    val leading = dialog.leadingResId
                    val offset = if (leading != null) 1 else 0
                    if (leading != null) {
                        TextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            text = stringResource(leading),
                            onClick = { actions.onDialogItemSelected(0) },
                        )
                    }
                    items.forEachIndexed { index, item ->
                        val grade = dialog.grades.getOrNull(index)
                        val label = if (index == dialog.currentIndex) {
                            stringResource(R.string.export_current_marker, item)
                        } else {
                            item
                        }
                        if (grade == null) {
                            /* No chip (image sources, phys choices): keep the
                             * miuix TextButton's centred label and container. */
                            TextButton(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                text = label,
                                onClick = { actions.onDialogItemSelected(index + offset) },
                            )
                        } else {
                            val chipColors = ButtonDefaults.textButtonColors()
                            Row(
                                /* A chip row: the same TextButton container,
                                 * with the label left-aligned so the chip sits
                                 * right after it. The miuix ButtonDefaults
                                 * values (corner 16.dp, inside margin 16x13.dp)
                                 * are not exposed to app code; they are read
                                 * from the library's constants. */
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(chipColors.color)
                                    .clickable { actions.onDialogItemSelected(index + offset) }
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = label,
                                    color = chipColors.textColor,
                                    /* The text fills the row so the chip is
                                     * always right-aligned inside the box. */
                                    modifier = Modifier.weight(1f),
                                )
                                DialogBadge(grade)
                            }
                        }
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.cancel),
                        onClick = actions::onDialogDismiss,
                    )
                }

                is AppDialog.Input -> {
                    TextField(
                        value = dialog.value,
                        onValueChange = actions::onDialogInputChange,
                        label = stringResource(dialog.labelRes),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.cancel),
                            onClick = actions::onDialogDismiss,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(dialog.confirmLabelRes),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = { actions.onDialogConfirm(dialog.value) },
                        )
                    }
                }

                is AppDialog.Confirm -> {
                    Text(
                        text = if (dialog.message.isNotEmpty()) {
                            dialog.message
                        } else {
                            stringResource(dialog.messageRes)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (dialog.exportLog) {
                        TextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            text = stringResource(R.string.run_log_export),
                            onClick = actions::onDialogExportLog,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(dialog.dismissLabelRes),
                            onClick = actions::onDialogDismiss,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(dialog.confirmLabelRes),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = { actions.onDialogConfirm("") },
                        )
                    }
                }

                is AppDialog.Notice -> {
                    Text(
                        text = if (dialog.message.isNotEmpty()) {
                            dialog.message
                        } else {
                            stringResource(dialog.messageRes)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        if (dialog.exportLog) {
                            TextButton(
                                modifier = Modifier.weight(1f),
                                text = stringResource(R.string.run_log_export),
                                onClick = actions::onDialogExportLog,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        dialog.docUrl?.let { docUrl ->
                            val uriHandler = LocalUriHandler.current
                            TextButton(
                                modifier = Modifier.weight(1f),
                                text = stringResource(R.string.dialog_open_guide),
                                onClick = { uriHandler.openUri(docUrl) },
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(dialog.dismissLabelRes),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = actions::onDialogDismiss,
                        )
                    }
                }

                is AppDialog.Progress -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        InfiniteProgressIndicator()
                        Text(
                            text = stringResource(dialog.messageRes),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }

                is AppDialog.NotificationPermission -> {
                    Text(
                        text = stringResource(dialog.messageRes),
                        modifier = Modifier.fillMaxWidth(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.run_notif_permission_later),
                            onClick = actions::onDialogDismiss,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.run_notif_permission_allow),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = { actions.onDialogConfirm("") },
                        )
                    }
                }

                null -> Unit
            }
        },
    )
}

@Composable
private fun RunLogPickerDialog(
    report: ReportState,
    actions: ReportActions,
) {
    val step = report.step
    val titleRes = when (step) {
        ReportWizardStep.Choice -> R.string.report_choice_title
        ReportWizardStep.Developer -> R.string.report_developer_title
        ReportWizardStep.Generate -> R.string.report_step_generate_title
        ReportWizardStep.Import -> R.string.report_step_import_title
        ReportWizardStep.Export -> R.string.report_step_export_title
    }
    val summaryRes = when (step) {
        ReportWizardStep.Choice -> R.string.report_choice_summary
        ReportWizardStep.Developer -> R.string.report_developer_summary
        ReportWizardStep.Generate -> R.string.report_step_generate_summary
        ReportWizardStep.Import -> R.string.report_step_import_summary
        ReportWizardStep.Export ->
            if (report.exportDone) {
                R.string.report_step_export_done_summary
            } else {
                R.string.report_step_export_summary
            }
    }
    OverlayDialog(
        show = report.pickerVisible,
        title = stringResource(titleRes),
        summary = stringResource(summaryRes),
        onDismissRequest = actions::onRunLogPickerDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val plan = report.steps
                val index = plan.indexOf(step)
                if (plan.size > 1 && index >= 0) {
                    Text(
                        text = stringResource(
                            R.string.report_step_indicator,
                            index + 1,
                            plan.size,
                        ),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                when (step) {
                    ReportWizardStep.Choice -> ReportChoiceStep(report, actions)
                    ReportWizardStep.Developer -> ReportDeveloperStep(actions)
                    ReportWizardStep.Generate -> ReportGenerateStep(report, actions)
                    ReportWizardStep.Import -> ReportImportStep(report, actions)
                    ReportWizardStep.Export -> ReportExportStep(report, actions)
                }
                Spacer(modifier = Modifier.height(12.dp))
                ReportWizardNav(step, report, actions)
            }
        },
    )
}

/** The first screen when no kernel log is in hand: attach a system report or not. */
@Composable
private fun ReportChoiceStep(report: ReportState, actions: ReportActions) {
    CrashEvidenceCard(report)
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.report_append),
        colors = ButtonDefaults.textButtonColorsPrimary(),
        onClick = actions::onReportAppend,
    )
    Spacer(modifier = Modifier.height(12.dp))
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.report_export_only),
        onClick = actions::onReportExportOnly,
    )
    ReportTextLink(
        text = stringResource(R.string.report_have_bugreport),
        onClick = actions::onReportGoToImport,
    )
}

/** Developer options is off; About phone is the way in. */
@Composable
private fun ReportDeveloperStep(actions: ReportActions) {
    Text(
        text = stringResource(R.string.report_developer_body),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.report_open_about),
        colors = ButtonDefaults.textButtonColorsPrimary(),
        onClick = actions::onOpenAboutPhone,
    )
    ReportTextLink(
        text = stringResource(R.string.report_developer_recheck),
        onClick = actions::onReportRecheckDeveloper,
    )
}

/** Developer options is on: the system generates the report from its own UI. */
@Composable
private fun ReportGenerateStep(report: ReportState, actions: ReportActions) {
    /* One primary card, then the manual routes as rows: the shell generator is
     * the fast path (no computer, auto-import), and the two fallbacks carry
     * their own how-to instead of three stacked paragraphs. The shell verdict
     * is polled while the sheet is open, so the card's state is live. */
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.report_generate_card_title),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            TextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                text = stringResource(
                    if (report.generating) {
                        R.string.report_generate_progress
                    } else {
                        R.string.report_generate_action
                    },
                ),
                enabled = report.crashShellAvailable && !report.generating,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = actions::onReportGenerateBugreport,
            )
            Text(
                text = stringResource(
                    if (!report.crashShellAvailable) {
                        R.string.report_generate_unavailable
                    } else {
                        R.string.report_generate_card_summary
                    },
                ),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    Text(
        text = stringResource(R.string.report_other_ways),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.report_dev_row_title),
            summary = stringResource(R.string.report_dev_row_summary),
            onClick = actions::onOpenDeveloperOptions,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(1.dp)
                .background(MiuixTheme.colorScheme.secondaryContainer),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.report_adb_row_title),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.report_adb_row_summary),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = stringResource(R.string.report_adb_row_hint),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            TextButton(
                text = stringResource(R.string.report_adb_copy),
                onClick = actions::onCopyBugreportCommand,
            )
        }
    }
}

/** Pick the report the user just generated. */
@Composable
private fun ReportImportStep(report: ReportState, actions: ReportActions) {
    CrashEvidenceCard(report)
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.report_add_bugreport),
        enabled = !report.importing,
        colors = ButtonDefaults.textButtonColorsPrimary(),
        onClick = actions::onAddBugreport,
    )
    if (report.importing) {
        Text(
            text = stringResource(R.string.report_importing),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/** The destination is chosen for the user straight away; this screen is the fallback. */
@Composable
private fun ReportExportStep(report: ReportState, actions: ReportActions) {
    CrashEvidenceCard(report)
    val evidence = report.crashEvidence
    if (evidence?.crashEvidence != true) {
        ReportWarningCard(
            stringResource(
                if (evidence?.hasKernelLog == true) {
                    R.string.report_no_crash_warning
                } else {
                    R.string.report_no_kernel_warning
                },
            ),
        )
        ReportTextLink(
            text = stringResource(R.string.report_back_to_import),
            onClick = actions::onReportGoToImport,
        )
    }
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.report_choose_location_action),
        colors = ButtonDefaults.textButtonColorsPrimary(),
        onClick = actions::onExportReportBundle,
    )
    if (report.exportDone) {
        Text(
            text = stringResource(
                R.string.report_saved,
                report.lastReportName ?: report.lastReportUri.orEmpty(),
            ),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ReportTextLink(
            text = stringResource(R.string.report_share),
            onClick = actions::onReportShare,
        )
    }
    if (report.runLogs.isNotEmpty()) {
        ReportTextLink(
            text = stringResource(R.string.run_log_clear),
            onClick = actions::onClearRunLogs,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ReportWizardNav(
    step: ReportWizardStep,
    report: ReportState,
    actions: ReportActions,
) {
    /* Choice is the decision screen; Export ends the flow. The screens in
     * between go back or continue; Back reaches Choice for the export-only
     * branch. While a report is being parsed no transition is allowed: both
     * directions stay visible but disabled, and the dialog's dismiss is
     * ignored (see the ViewModel). */
    val busy = report.importing
    val bugreportStep = when (step) {
        ReportWizardStep.Developer,
        ReportWizardStep.Generate,
        ReportWizardStep.Import,
        -> true

        ReportWizardStep.Choice,
        ReportWizardStep.Export,
        -> false
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (step != ReportWizardStep.Choice) {
                TextButton(
                    text = stringResource(R.string.report_back),
                    enabled = !busy,
                    onClick = actions::onReportBack,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (bugreportStep) {
                TextButton(
                    text = stringResource(
                        if (step == ReportWizardStep.Generate) {
                            R.string.report_import_existing
                        } else {
                            R.string.report_next
                        },
                    ),
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = actions::onReportNext,
                )
            }
        }
    }
}

/** A text-link style action; lighter than the filled dialog buttons. */
@Composable
private fun ReportTextLink(
    text: String,
    onClick: () -> Unit,
    color: Color = MiuixTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ReportWarningCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.errorContainer,
            contentColor = MiuixTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onErrorContainer,
        )
    }
}

/** What the app already holds about the last crash, and where it came from. */
@Composable
private fun CrashEvidenceCard(report: ReportState) {
    val evidence = report.crashEvidence
    var details by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.report_evidence_title),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = evidenceStatus(evidence),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (evidence != null && evidence.source != "none") {
                ReportTextLink(
                    text = stringResource(R.string.report_details),
                    onClick = { details = !details },
                )
                if (details) {
                    Text(
                        text = stringResource(R.string.report_source, evidence.source),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = stringResource(R.string.report_boot_id, evidence.captureBootId),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (evidence.bootReason.isNotBlank()) {
                        Text(
                            text = stringResource(
                                R.string.report_boot_reason,
                                evidence.bootReason,
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (evidence.files.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.report_files,
                                evidence.files.joinToString(", "),
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun evidenceStatus(evidence: CrashEvidenceInfo?): String = when {
    evidence == null -> stringResource(R.string.report_status_none)
    evidence.source == "bugreport" ->
        if (evidence.crashEvidence) {
            stringResource(
                R.string.report_status_imported,
                evidence.bugreportName.ifBlank { "bugreport.zip" },
            )
        } else {
            stringResource(
                R.string.report_status_imported_no_crash,
                evidence.bugreportName.ifBlank { "bugreport.zip" },
            )
        }

    evidence.hasKernelLog -> stringResource(R.string.report_status_collected)
    evidence.source == "none" -> stringResource(R.string.report_status_none)
    else -> stringResource(R.string.report_status_partial)
}

/**
 * The slab-stride decision sheet: one recommendation with an evidence level in
 * plain language, the alternatives as radio rows, and the raw table behind a
 * "technical details" toggle. Dismissing it is the same as Skip.
 */
@Composable
private fun StridePickerDialog(
    overlay: OverlayState,
    actions: OverlayActions,
) {
    val picker = overlay.stridePicker ?: return
    val recommendation = picker.recommendation
    var detailsExpanded by remember { mutableStateOf(false) }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.stride_title),
        summary = stringResource(R.string.stride_subtitle, recommendation.samples),
        /* The stride is mandatory for a W1-ladder profile: outside-tap and Back
         * do nothing, so an accidental gesture cannot throw away the probe.
         * The only exit is choosing a value and pressing Use. */
        onDismissRequest = {},
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = recommendation.recommendedValue
                                    ?.let { stringResource(R.string.stride_bytes, it) }
                                    ?: stringResource(R.string.stride_none),
                                modifier = Modifier.weight(1f),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(strideLevelRes(recommendation.level)),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (recommendation.level == StrideEvidenceLevel.CONFIRMED ||
                                    recommendation.level == StrideEvidenceLevel.CONSISTENT
                                ) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                        }
                        Text(
                            text = strideReasonText(recommendation.reason),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        recommendation.confirmedValue?.let { confirmed ->
                            Text(
                                text = stringResource(R.string.stride_confirmed_line, confirmed),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    recommendation.options.forEach { option ->
                        RadioButtonPreference(
                            title = stringResource(
                                R.string.stride_option,
                                option.value,
                                option.matches,
                                option.samples,
                            ),
                            selected = picker.selectedValue == option.value,
                            onClick = { actions.onStrideOptionSelected(option.value) },
                            radioButtonLocation = RadioButtonLocation.End,
                        )
                    }
                }
                TextButton(
                    text = if (detailsExpanded) {
                        "▾ " + stringResource(R.string.stride_details)
                    } else {
                        "▸ " + stringResource(R.string.stride_details)
                    },
                    onClick = { detailsExpanded = !detailsExpanded },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (detailsExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.stride_details_measurements,
                                recommendation.samples,
                                recommendation.rounds,
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = stringResource(
                                R.string.stride_details_gcd,
                                recommendation.gcd.toString(16),
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        recommendation.options.forEach { option ->
                            Text(
                                text = stringResource(
                                    R.string.stride_details_candidate,
                                    option.value,
                                    option.matches,
                                    option.samples,
                                    option.objectsPerSlab,
                                ),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = picker.selectedValue
                        ?.let { stringResource(R.string.stride_use, it) }
                        ?: stringResource(R.string.stride_use_none),
                    enabled = picker.selectedValue != null,
                    onClick = actions::onStrideUse,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(picker.footerRes),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
    )
}

private fun strideLevelRes(level: StrideEvidenceLevel): Int = when (level) {
    StrideEvidenceLevel.CONFIRMED -> R.string.stride_level_confirmed
    StrideEvidenceLevel.CONSISTENT -> R.string.stride_level_consistent
    StrideEvidenceLevel.MIXED -> R.string.stride_level_mixed
    StrideEvidenceLevel.INSUFFICIENT -> R.string.stride_level_insufficient
}

@Composable
private fun strideReasonText(reason: StrideReason): String = when (reason) {
    is StrideReason.ConfirmedAgree -> stringResource(
        R.string.stride_reason_confirmed_agree,
        reason.samples,
    )

    is StrideReason.ConfirmedOnly -> stringResource(R.string.stride_reason_confirmed)
    is StrideReason.Agree -> stringResource(R.string.stride_reason_agree, reason.samples)
    is StrideReason.Partial -> stringResource(
        R.string.stride_reason_partial,
        reason.matches,
        reason.samples,
        reason.runnerUp,
    )

    StrideReason.Tie -> stringResource(R.string.stride_reason_tie)
    is StrideReason.Single -> stringResource(
        if (reason.samples > 0) R.string.stride_reason_single else R.string.stride_reason_none,
    )
}

internal val OverrideHighlight = Color(0xFFF5A623)

@Composable
private fun LogPanel(
    lines: List<GhostlockLogLine>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B1220))
            .padding(12.dp),
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line.text.trimEnd('\r', '\n'),
                        color = lineColor(line.color),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

/**
 * A dialog choice's chip, rendered inline after the option (same row, trailing)
 * rather than on its own line above it: a colored dot + the short label.
 */
@Composable
private fun DialogBadge(chip: DialogChip) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(chip.color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(chip.labelRes),
            fontSize = 11.sp,
        )
    }
}

private fun lineColor(color: Int): Color = when (color) {
    0xFFFF6B6B.toInt() -> Color(0xFFFF6B6B)
    0xFF5FD68A.toInt() -> Color(0xFF5FD68A)
    0xFFFFC94D.toInt() -> Color(0xFFFFC94D)
    0xFF60A5FA.toInt() -> Color(0xFF60A5FA)
    else -> Color(0xFFD1D5DB)
}
