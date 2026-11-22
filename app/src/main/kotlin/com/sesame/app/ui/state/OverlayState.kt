package com.sesame.app.ui.state

import androidx.compose.runtime.Stable
import com.sesame.app.ui.AppDialog
import com.sesame.app.ui.StridePickerUiState

/**
 * The app-wide overlays: the one typed dialog that may be open, the slab-stride
 * decision sheet, and the profile-overwrite confirmation.
 */
@Stable
data class OverlayState(
    val dialog: AppDialog? = null,
    val stridePicker: StridePickerUiState? = null,
    val overwriteVisible: Boolean = false,
    val overwriteMessage: String = "",
)
