package com.sesame.app.ui

import com.sesame.app.domain.model.ShizukuStatus
import com.sesame.app.ui.state.SessionState

/**
 * The support verdict: a profile exists (builtin or imported), a profile
 * exists but access is not ready, nothing exists — or a stored imported
 * profile exists but no longer resolves (2026-11-25).
 *
 * The last one is the upgrade-path state: a copy imported under an older build
 * (schema, validation rule, device identity, or the running kernel itself) is
 * kept but refused. The resolver still never falls back to the bundle on its
 * own — the selection is the user's statement — so the verdict names it and
 * the only action is removing the import; whether to generate or re-import
 * afterwards is the next, separate choice.
 */
sealed interface SupportStatus {
    data object Ready : SupportStatus
    data class AccessPending(
        val status: ShizukuStatus,
        /** False when the manager package itself is absent (NOT_RUNNING folds it). */
        val installed: Boolean = true,
    ) : SupportStatus
    data object Missing : SupportStatus
    data object SelectionUnusable : SupportStatus
}

/** The module chain's missing link, in the order the run checks it. */
internal enum class ModuleBlocker { NotBundled, AssetMissing, MagiskMissing }

/**
 * The module chain is bundle-only: its profile must be the bundled one, its
 * `.ko` must match the declared hash, and the root script must find a Magisk
 * manager to stage. Null when the chain can run, or when no module profile is
 * active.
 */
internal fun moduleBlockerOf(state: SessionState): ModuleBlocker? {
    if (state.support.module == null) return null
    return when {
        state.support.layers?.bundledAuthoritative != true -> ModuleBlocker.NotBundled
        !state.support.moduleAssetVerified -> ModuleBlocker.AssetMissing
        state.support.magiskManager == null -> ModuleBlocker.MagiskMissing
        else -> null
    }
}

internal fun supportStatusOf(state: SessionState): SupportStatus = supportStatusOf(
    hasProfile = state.support.hasProfile,
    importedUnusable = state.support.importedProfilePresent &&
        (!state.support.hasProfile || state.support.profileInvalid),
    shizukuEnabled = state.access.shizukuEnabled,
    shizukuStatus = state.access.shizukuStatus,
    shizukuInstalled = state.access.shizukuInstalled,
)

private fun supportStatusOf(
    hasProfile: Boolean,
    importedUnusable: Boolean,
    shizukuEnabled: Boolean,
    shizukuStatus: ShizukuStatus,
    shizukuInstalled: Boolean,
): SupportStatus = when {
    importedUnusable -> SupportStatus.SelectionUnusable
    !hasProfile -> SupportStatus.Missing
    shizukuEnabled && shizukuStatus != ShizukuStatus.READY ->
        SupportStatus.AccessPending(shizukuStatus, shizukuInstalled)
    else -> SupportStatus.Ready
}
