package com.sesame.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sesame.app.R
import com.sesame.app.domain.model.ShizukuStatus
import com.sesame.app.ui.state.HomeActions
import com.sesame.app.ui.state.SessionState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * V1: the Home screen is decide-and-run. One status card carries the verdict,
 * one card carries the two acquisition paths, one card carries the run history
 * and Advanced, and the primary action is pinned above the navigation bar.
 * Settings live in Advanced → 运行设置.
 */
@Composable
internal fun HomeScreen(
    state: SessionState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val status = supportStatusOf(state)
    val copy = statusCopy(state, status)
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.showDeviceInfo() }
                    .padding(top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = listOf(
                        state.device.deviceName,
                        state.device.socName,
                        shortRelease(state.device.kernelRelease),
                    )
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    fontSize = 14.sp,
                    color = secondaryText(),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            StatusCard(copy)
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.home_generate_profile),
                    summary = stringResource(R.string.home_generate_profile_summary),
                    onClick = actions::openGeneration,
                )
                ArrowPreference(
                    title = stringResource(R.string.load_config_title),
                    summary = stringResource(R.string.home_load_summary),
                    onClick = actions::selectProfileFile,
                )
            }
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.home_last_run),
                    summary = lastRunSummary(state),
                    onClick = actions::openLastRunLog,
                )
                ArrowPreference(
                    title = stringResource(R.string.run_log_export),
                    summary = stringResource(R.string.home_export_report_summary),
                    onClick = actions::exportReport,
                )
                ArrowPreference(
                    title = stringResource(R.string.advanced_settings),
                    summary = stringResource(R.string.advanced_settings_summary),
                    onClick = actions::openAdvanced,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        TextButton(
            text = copy.barLabel,
            onClick = { dispatch(copy.action, actions) },
            enabled = !state.run.running,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(52.dp),
        )
    }
}

private enum class StatusKind { Success, Pending, Action }

private enum class BarAction { Run, Shizuku, Generate }

private data class StatusCopy(
    val kind: StatusKind,
    val title: String,
    val summary: String?,
    val detail: String?,
    val barLabel: String,
    val action: BarAction,
)

@Composable
private fun statusCopy(state: SessionState, status: SupportStatus): StatusCopy {
    val source = stringResource(
        if (state.support.importedProfilePresent) R.string.home_source_external
        else R.string.home_source_builtin,
    )
    val route = state.support.route?.takeIf { it.isNotBlank() }
    val detail = listOfNotNull(source, route).joinToString(" · ")
    return when (status) {
        SupportStatus.Ready -> StatusCopy(
            kind = StatusKind.Success,
            title = stringResource(R.string.kernel_supported),
            summary = stringResource(R.string.kernel_profile_ready),
            detail = detail,
            barLabel = stringResource(R.string.action_run),
            action = BarAction.Run,
        )

        is SupportStatus.AccessPending -> StatusCopy(
            kind = StatusKind.Pending,
            title = stringResource(R.string.shizuku_label),
            summary = shizukuLine(status.status),
            detail = detail,
            barLabel = stringResource(R.string.home_action_shizuku),
            action = BarAction.Shizuku,
        )

        SupportStatus.Missing -> StatusCopy(
            kind = StatusKind.Action,
            title = stringResource(R.string.kernel_profile_required),
            summary = stringResource(R.string.kernel_profile_required_summary),
            detail = null,
            barLabel = stringResource(R.string.home_action_generate),
            action = BarAction.Generate,
        )
    }
}

@Composable
private fun StatusCard(copy: StatusCopy) {
    val dark = isSystemInDarkTheme()
    val dynamic = MiuixTheme.isDynamicColor
    val container = when (copy.kind) {
        StatusKind.Success -> when {
            dynamic -> MiuixTheme.colorScheme.secondaryContainer
            dark -> Color(0xFF1A3825)
            else -> Color(0xFFDFFAE4)
        }

        StatusKind.Pending -> if (dark) Color(0xFF2C2C2C) else Color(0xFFF2F2F2)

        StatusKind.Action -> when {
            dynamic -> MiuixTheme.colorScheme.tertiaryContainer
            dark -> Color(0xFF3E2F1B)
            else -> Color(0xFFFFF0DB)
        }
    }
    val iconTint = when (copy.kind) {
        StatusKind.Success -> if (dynamic) MiuixTheme.colorScheme.primary else Color(0xFF36D167)
        StatusKind.Pending -> secondaryText()
        StatusKind.Action ->
            if (dynamic) MiuixTheme.colorScheme.onTertiaryContainer else Color(0xFFF5A623)
    }
    val icon = when (copy.kind) {
        StatusKind.Success -> Icons.Rounded.CheckCircleOutline
        StatusKind.Pending -> Icons.Rounded.RemoveCircleOutline
        StatusKind.Action -> Icons.Rounded.ErrorOutline
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = container),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = copy.title,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = copy.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                copy.summary?.let {
                    Text(text = it, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                }
                copy.detail?.let {
                    Text(
                        text = it,
                        fontSize = 14.sp,
                        color = secondaryText(),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

private fun dispatch(action: BarAction, actions: HomeActions) {
    when (action) {
        BarAction.Run -> actions.run()
        BarAction.Shizuku -> actions.statusClick()
        BarAction.Generate -> actions.openGeneration()
    }
}

@Composable
private fun lastRunSummary(state: SessionState): String = stringResource(
    when (state.run.status) {
        RunStatus.Idle -> R.string.home_run_none
        RunStatus.Running -> R.string.action_running
        RunStatus.Success -> R.string.home_run_done
        RunStatus.Failure -> R.string.home_run_failed
    },
)

@Composable
private fun shizukuLine(status: ShizukuStatus): String = stringResource(
    when (status) {
        ShizukuStatus.READY -> R.string.shizuku_status_ready
        ShizukuStatus.PERMISSION_REQUIRED -> R.string.shizuku_status_permission_required
        ShizukuStatus.NOT_RUNNING -> R.string.shizuku_status_not_running
        else -> R.string.shizuku_status_checking
    },
)

/** `5.10.236-android12-9-…` reads as `5.10.236` on the identity line. */
private fun shortRelease(release: String): String =
    release.substringBefore('-').ifBlank { release }

@Composable
internal fun secondaryText(): Color =
    MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f)
