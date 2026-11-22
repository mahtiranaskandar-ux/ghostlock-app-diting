package com.sesame.app.ui.state

import androidx.compose.runtime.Stable

/**
 * The builtin-profile picker's slice: the bundled releases and the unfilled
 * reference templates. The selected release lives in
 * [SessionState.SupportState.activeBuiltinProfile] (null means Auto).
 */
@Stable
data class BuiltinState(
    val releases: List<String> = emptyList(),
    val templates: List<String> = emptyList(),
)
