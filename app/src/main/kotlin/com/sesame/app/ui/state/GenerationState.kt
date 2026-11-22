package com.sesame.app.ui.state

import androidx.compose.runtime.Stable
import com.sesame.app.ui.GenerationStep

/** The profile-generation flow's slice: which of the screen's steps is up. */
@Stable
data class GenerationState(
    val step: GenerationStep = GenerationStep.Idle,
)
