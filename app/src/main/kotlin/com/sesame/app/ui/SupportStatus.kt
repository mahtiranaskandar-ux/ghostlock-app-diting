package com.sesame.app.ui

import com.sesame.app.domain.model.ShizukuStatus
import com.sesame.app.ui.state.SessionState

/**
 * The support verdict — three facts only (2026-11-21 operator decision):
 * a profile exists (builtin or imported), a profile exists but access is not
 * ready, or nothing exists. A profile that fails verification is rejected at
 * import/generation and never reaches this state, so there is no blocked or
 * invalid state here.
 */
sealed interface SupportStatus {
    data object Ready : SupportStatus
    data class AccessPending(val status: ShizukuStatus) : SupportStatus
    data object Missing : SupportStatus
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
    shizukuEnabled = state.access.shizukuEnabled,
    shizukuStatus = state.access.shizukuStatus,
)

private fun supportStatusOf(
    hasProfile: Boolean,
    shizukuEnabled: Boolean,
    shizukuStatus: ShizukuStatus,
): SupportStatus = when {
    !hasProfile -> SupportStatus.Missing
    shizukuEnabled && shizukuStatus != ShizukuStatus.READY ->
        SupportStatus.AccessPending(shizukuStatus)
    else -> SupportStatus.Ready
}
