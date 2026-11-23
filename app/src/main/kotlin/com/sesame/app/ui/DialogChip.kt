package com.sesame.app.ui

import androidx.compose.ui.graphics.Color

/** A colored dot + short label rendered above a dialog choice. */
interface DialogChip {
    val labelRes: Int
    val color: Color
}
