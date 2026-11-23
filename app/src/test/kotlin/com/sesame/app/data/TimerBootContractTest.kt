package com.sesame.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timer route's reboot contract: any attempt that reached the tool parks
 * (a hit holds the fake cred page, a miss holds the failed plants), so a second
 * attempt on the same boot is refused until the reboot changes the boot id.
 */
class TimerBootContractTest {
    @Test
    fun `a boot with no attempt allows the run`() {
        assertTrue(TimerBootContract.allowsRun(null, "boot-a"))
        assertTrue(TimerBootContract.allowsRun("", "boot-a"))
    }

    @Test
    fun `the same boot is refused after an attempt`() {
        assertFalse(TimerBootContract.allowsRun("boot-a", "boot-a"))
    }

    @Test
    fun `a new boot allows the next attempt`() {
        assertTrue(TimerBootContract.allowsRun("boot-a", "boot-b"))
    }

    @Test
    fun `the refusal code is stable for the UI`() {
        assertEquals(4, TimerBootContract.Refused)
    }
}
