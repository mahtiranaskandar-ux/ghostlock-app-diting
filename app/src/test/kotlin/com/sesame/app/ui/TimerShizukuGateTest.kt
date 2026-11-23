package com.sesame.app.ui

import com.sesame.app.domain.model.ShizukuStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimerShizukuGateTest {
    @Test
    fun missingWhenTheManagerIsNotInstalled() {
        assertEquals(
            TimerShizukuBlocker.MISSING,
            timerShizukuBlocker(ShizukuStatus.NOT_RUNNING, installed = false),
        )
        assertEquals(
            TimerShizukuBlocker.MISSING,
            timerShizukuBlocker(ShizukuStatus.READY, installed = false),
        )
    }

    @Test
    fun notRunningWhenInstalledButStopped() {
        assertEquals(
            TimerShizukuBlocker.NOT_RUNNING,
            timerShizukuBlocker(ShizukuStatus.NOT_RUNNING, installed = true),
        )
        /* The timer path reads the live status, so NOT_REQUIRED should not
         * reach it; if it ever does, it must not be treated as usable. */
        assertEquals(
            TimerShizukuBlocker.NOT_RUNNING,
            timerShizukuBlocker(ShizukuStatus.NOT_REQUIRED, installed = true),
        )
    }

    @Test
    fun permissionWhenRunningWithoutTheGrant() {
        assertEquals(
            TimerShizukuBlocker.PERMISSION,
            timerShizukuBlocker(ShizukuStatus.PERMISSION_REQUIRED, installed = true),
        )
    }

    @Test
    fun readyIsTheOnlyStartableStatus() {
        assertNull(timerShizukuBlocker(ShizukuStatus.READY, installed = true))
    }
}
