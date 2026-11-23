package com.sesame.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sesame.app.R
import com.sesame.app.domain.model.ShizukuStatus
import com.sesame.app.ui.state.RunSettingsActions
import com.sesame.app.ui.state.SessionState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** V1: the four run knobs that left Home, reachable from Advanced. */
@Composable
internal fun RunSettingsScreen(
    state: SessionState,
    actions: RunSettingsActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.run_settings_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = actions::close) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = state.runSettings.safeModeEnabled,
                    onCheckedChange = actions::safeModeChanged,
                    title = stringResource(R.string.safe_mode_label),
                    summary = stringResource(R.string.safe_mode_summary),
                )
                SwitchPreference(
                    checked = state.access.shizukuEnabled,
                    onCheckedChange = actions::shizukuChanged,
                    title = stringResource(R.string.shizuku_label),
                    summary = stringResource(
                        when {
                            !state.access.shizukuEnabled -> R.string.shizuku_summary
                            state.access.shizukuStatus == ShizukuStatus.READY ->
                                R.string.shizuku_status_ready

                            state.access.shizukuStatus == ShizukuStatus.PERMISSION_REQUIRED ->
                                R.string.shizuku_status_permission_required

                            state.access.shizukuStatus == ShizukuStatus.NOT_RUNNING ->
                                R.string.shizuku_status_not_running

                            else -> R.string.shizuku_status_checking
                        },
                    ),
                )
            }
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                val tagMode = stringResource(vrKoModeLabelRes(state.runSettings.vrKoTagClear))
                val selinuxMode =
                    stringResource(vrKoModeLabelRes(state.runSettings.vrKoSelinuxBlock))
                ArrowPreference(
                    title = stringResource(R.string.vr_ko_entry_label),
                    summary = stringResource(R.string.vr_ko_entry_summary, tagMode, selinuxMode),
                    onClick = actions::openVrKo,
                )
            }
        }
    }
}
