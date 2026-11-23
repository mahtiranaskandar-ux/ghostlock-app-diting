package com.sesame.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sesame.app.BuildConfig
import com.sesame.app.R
import com.sesame.app.ui.state.AdvancedActions
import com.sesame.app.ui.state.BuiltinActions
import com.sesame.app.ui.state.BuiltinState
import com.sesame.app.ui.state.MeasurementsActions
import com.sesame.app.ui.state.MergePreviewActions
import com.sesame.app.ui.state.ParametersActions
import com.sesame.app.ui.state.SessionState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Field reference and validation rules live in the repository docs; pick the
 * page matching the system language. */
private const val ProfileDocsBase =
    "https://github.com/YuKongA/ghostlock-app/blob/main/docs/kernel_profiles/"

private fun profileDocsUrl(): String =
    ProfileDocsBase + if (Locale.getDefault().language == "zh") {
        "PROFILE_SCHEMA_ZH.md"
    } else {
        "PROFILE_SCHEMA.md"
    }

private fun Modifier.preferencePageItem(): Modifier =
    this.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)

@Composable
private fun ProfileDocsCard(onOpen: () -> Unit) {
    Card {
        ArrowPreference(
            title = stringResource(R.string.profile_docs),
            summary = stringResource(R.string.profile_docs_summary),
            onClick = onOpen,
        )
    }
}

@Composable
private fun ProfileHintBanner(
    text: String,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
    title: String? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = if (warning) {
            CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.errorContainer,
                contentColor = MiuixTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.defaultColors()
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (warning) MiuixTheme.colorScheme.onErrorContainer
                    else MiuixTheme.colorScheme.onSurface,
                )
            }
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    fontSize = 13.sp,
                    color = if (warning) MiuixTheme.colorScheme.onErrorContainer
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * Advanced screen: offsets tooling, parameter overrides and the debug-only
 * log export switches. Debug preferences are hidden in release builds.
 */
@Composable
internal fun AdvancedScreen(
    state: SessionState,
    actions: AdvancedActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.advanced_settings),
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
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "runSettings") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.run_settings_title),
                        summary = stringResource(R.string.run_settings_summary),
                        onClick = actions::openRunSettings,
                    )
                }
            }
            item(key = "forceAttack") {
                Card {
                    SwitchPreference(
                        checked = state.runSettings.forceAttackTestEnabled,
                        onCheckedChange = actions::forceAttackChanged,
                        title = stringResource(R.string.force_attack_test_label),
                        summary = stringResource(R.string.force_attack_test_summary),
                    )
                }
            }
            /* The profile's `pipeline` is the only selector; there is no UI
             * override and no debug intent that changes the order. */
            item(key = "rootManager") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.manager_label),
                        summary = state.access.selectedManagerPackage
                            ?: stringResource(R.string.manager_summary_auto),
                        onClick = actions::openManagerPicker,
                    )
                }
            }
            item(key = "override") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.parameters),
                        summary = stringResource(R.string.parameters_summary),
                        onClick = actions::openParameters,
                    )
                }
            }
            item(key = "measurements") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.measurements_title),
                        summary = stringResource(R.string.measurements_summary),
                        onClick = actions::openMeasurements,
                    )
                }
            }
            item(key = "export") {
                Card {
                    Column {
                        /* The run log itself has no switch any more: it is always
                         * kept. This one still gates the kernel side, which the
                         * root script only writes from its exit trap. */
                        SwitchPreference(
                            checked = state.runSettings.debugKernelLogEnabled,
                            onCheckedChange = actions::debugKernelLogChanged,
                            title = stringResource(R.string.debug_kernel_log),
                            summary = stringResource(R.string.debug_kernel_log_summary),
                        )
                        if (!BuildConfig.DEBUG) {
                            Text(
                                text = stringResource(R.string.debug_build_hint),
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
            item(key = "about") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.about),
                        summary = stringResource(R.string.about_summary),
                        onClick = actions::showAbout,
                    )
                }
            }
        }
    }
}

/** Parameters screen: configuration loading and the parameter-override submenu. */
@Composable
internal fun ParameterScreen(
    state: SessionState,
    actions: ParametersActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.parameters),
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
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "current") {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.profile_source_title),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                        )
                        Text(
                            text = when {
                                state.support.importedProfilePresent ->
                                    stringResource(R.string.profile_kind_external)
                                state.support.hasProfile ->
                                    stringResource(R.string.profile_kind_builtin)
                                else -> stringResource(R.string.profile_not_loaded)
                            },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        val detail = listOfNotNull(
                            state.support.release.takeIf { it.isNotBlank() },
                            state.support.module?.let { stringResource(R.string.module_chain_label) }
                                ?: state.support.route?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        if (detail.isNotEmpty()) {
                            Text(
                                text = detail,
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
            item(key = "builtin") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.load_builtin_profile),
                        summary = stringResource(R.string.load_builtin_summary),
                        onClick = actions::openBuiltin,
                    )
                }
            }
            /* The module chain is a pair manifest, not a route: the asset, its
             * hash and the manager the root script stages are the whole
             * readiness statement. It is bundle-only, so there is nothing to
             * export and nothing an imported copy could select. */
            state.support.module?.let { module ->
                item(key = "module") {
                    val blocker = moduleBlockerOf(state)
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.module_chain_label),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                            )
                            Text(
                                text = module.asset.substringAfterLast('/'),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                text = stringResource(
                                    R.string.module_asset_sha,
                                    module.assetSha256.take(16) + "…",
                                ),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            if (module.vendorFiles.isNotEmpty()) {
                                Text(
                                    text = stringResource(
                                        R.string.module_vendor_files,
                                        module.vendorFiles.size,
                                    ),
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                module.vendorFiles.forEach { path ->
                                    Text(
                                        text = path,
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                            }
                            Text(
                                text = stringResource(
                                    when (blocker) {
                                        ModuleBlocker.NotBundled ->
                                            R.string.module_blocked_not_bundled

                                        ModuleBlocker.AssetMissing ->
                                            R.string.module_blocked_asset

                                        ModuleBlocker.MagiskMissing ->
                                            R.string.module_blocked_magisk

                                        null -> R.string.module_magisk_installed
                                    },
                                ),
                                fontSize = 13.sp,
                                color = if (blocker != null) {
                                    OverrideHighlight
                                } else {
                                    MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                                },
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
            item(key = "docs") {
                ProfileDocsCard { uriHandler.openUri(profileDocsUrl()) }
            }
            if (state.support.hasProfile) {
                item(key = "actions") {
                    if (state.support.module != null) {
                        ProfileHintBanner(
                            text = stringResource(R.string.module_export_note),
                        )
                    } else {
                        TextButton(
                            text = stringResource(R.string.override_export),
                            onClick = actions::exportProfile,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (state.support.importedProfilePresent) {
                item(key = "danger") {
                    Card {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { actions.clearExternalProfile() }
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.clear_external_profile),
                                fontSize = 16.sp,
                                color = Color(0xFFF72727),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatProfileTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * Builtin picker: the device kernel stays pinned on top, every bundled
 * release follows, ordered by how close it is to the device kernel.
 */
@Composable
internal fun BuiltinProfileScreen(
    state: SessionState,
    builtin: BuiltinState,
    actions: BuiltinActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.load_builtin_profile),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = actions::onCloseBuiltinProfiles) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(
                paddingValues, top = 0.dp, bottom = 0.dp, horizontalMin = 0.dp,
            ),
        ) {
            item(key = "top-space") { Spacer(Modifier.height(12.dp)) }
            item(key = "local-kernel") {
                ProfileHintBanner(
                    text = stringResource(R.string.local_kernel, state.device.kernelRelease),
                    modifier = Modifier.preferencePageItem(),
                )
            }
            if (state.support.activeBuiltinProfile == null && !state.support.importedProfilePresent &&
                !state.support.hasProfile
            ) {
                item(key = "auto-no-match") {
                    ProfileHintBanner(
                        text = stringResource(R.string.builtin_auto_no_match),
                        warning = true,
                        modifier = Modifier.preferencePageItem(),
                    )
                }
            }
            item(key = "immutable-notice") {
                ProfileHintBanner(
                    text = stringResource(R.string.builtin_immutable_notice),
                    modifier = Modifier.preferencePageItem(),
                )
            }
            item(key = "auto") {
                BuiltinProfileRow(
                    title = stringResource(R.string.load_builtin_auto),
                    selected = !state.support.importedProfilePresent &&
                            state.support.activeBuiltinProfile == null,
                    onClick = { actions.onSelectBuiltinProfile(null) },
                    modifier = Modifier.preferencePageItem(),
                )
            }
            if (builtin.templates.isNotEmpty()) {
                item(key = "templates-title") {
                    SmallTitle(text = stringResource(R.string.templates_section))
                }
                items(builtin.templates, key = { "template:$it" }) { release ->
                    BuiltinProfileRow(
                        title = release,
                        selected = !state.support.importedProfilePresent &&
                                state.support.activeBuiltinProfile == release,
                        onClick = { actions.onSelectBuiltinProfile(release) },
                        modifier = Modifier.preferencePageItem(),
                    )
                }
            }
            if (builtin.releases.isNotEmpty()) {
                item(key = "kernels-title") {
                    SmallTitle(text = stringResource(R.string.kernels_section))
                }
                items(builtin.releases, key = { "kernel:$it" }) { release ->
                    BuiltinProfileRow(
                        title = release,
                        selected = !state.support.importedProfilePresent &&
                                state.support.activeBuiltinProfile == release,
                        onClick = { actions.onSelectBuiltinProfile(release) },
                        modifier = Modifier.preferencePageItem(),
                    )
                }
            }
            item(key = "bottom-space") {
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun BuiltinProfileRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        RadioButtonPreference(
            title = title,
            selected = selected,
            onClick = onClick,
            radioButtonLocation = RadioButtonLocation.End,
        )
    }
}

/**
 * Read-only view of the measured database: per kernel, each root-measured fact
 * with its source, time and the raw evidence line it came from. Nothing here
 * can be edited, and nothing here is applied automatically.
 */
@Composable
internal fun MeasurementsScreen(
    state: SessionState,
    actions: MeasurementsActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.measurements_title),
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
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "readonly") {
                ProfileHintBanner(
                    text = stringResource(R.string.measurements_readonly),
                    modifier = Modifier.preferencePageItem(),
                )
            }
            if (state.measurements.measuredKernels.isEmpty()) {
                item(key = "empty") {
                    ProfileHintBanner(
                        text = stringResource(R.string.measurements_empty),
                        modifier = Modifier.preferencePageItem(),
                    )
                }
            }
            items(state.measurements.measuredKernels, key = { it.release }) { kernel ->
                Card(modifier = Modifier.preferencePageItem()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = kernel.release,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(
                                R.string.measurement_updated,
                                formatProfileTime(kernel.updatedAt),
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        kernel.facts.forEach { fact ->
                            Text(
                                text = "${fact.key} = ${fact.value}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.measurement_source, fact.source),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = stringResource(
                                    R.string.measurement_time,
                                    formatProfileTime(fact.measuredAt),
                                ),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = stringResource(
                                    R.string.measurement_evidence,
                                    fact.evidence,
                                ),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
            item(key = "bottom-space") {
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}

/**
 * Home merge preview: the facts the measured database would change, their raw
 * evidence, and the one action that matters — exporting a new document. The
 * source profile is never modified.
 */
@Composable
internal fun MergePreviewScreen(
    state: SessionState,
    actions: MergePreviewActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.merge_preview_title),
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
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                ProfileHintBanner(
                    text = stringResource(R.string.merge_preview_summary),
                    modifier = Modifier.preferencePageItem(),
                )
            }
            items(state.measurements.mergeRows, key = { it.path }) { row ->
                Card(modifier = Modifier.preferencePageItem()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = row.path,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = buildString {
                                append(stringResource(R.string.merge_profile_value))
                                append(": ")
                                append(row.profileValue ?: "—")
                                append("    ")
                                append(stringResource(R.string.merge_measured_value))
                                append(": ")
                                append(row.measuredValue)
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.measurement_source, row.source),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = stringResource(R.string.measurement_evidence, row.evidence),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            item(key = "export") {
                TextButton(
                    text = stringResource(R.string.merge_export),
                    onClick = actions::exportMerged,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier
                        .preferencePageItem()
                        .fillMaxWidth(),
                )
            }
            item(key = "bottom-space") {
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}

