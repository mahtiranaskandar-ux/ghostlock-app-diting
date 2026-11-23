package com.sesame.app.ui

import com.sesame.app.domain.model.ShizukuStatus

/** The Shizuku project page the timer refusal offers when it is not installed. */
internal const val SHIZUKU_URL = "https://github.com/RikkaApps/Shizuku"

/**
 * Why the timer route (CVE-2026-98260) cannot start on this device, or null
 * when it can.
 *
 * The tool runs through the Shizuku UserService, so a device without Shizuku
 * has no delivery path for it at all. The live status alone cannot tell "not
 * installed" from "installed but stopped" ([ShizukuStatus.NOT_RUNNING] covers
 * both), which is why the install flag is part of the decision.
 */
internal enum class TimerShizukuBlocker { MISSING, NOT_RUNNING, PERMISSION }

internal fun timerShizukuBlocker(
    status: ShizukuStatus,
    installed: Boolean,
): TimerShizukuBlocker? = when {
    !installed -> TimerShizukuBlocker.MISSING
    status == ShizukuStatus.READY -> null
    status == ShizukuStatus.PERMISSION_REQUIRED -> TimerShizukuBlocker.PERMISSION
    else -> TimerShizukuBlocker.NOT_RUNNING
}
