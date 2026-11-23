package com.sesame.app.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The timer tool parks on a hit and on several failures; the verdict must come
 * from these markers, not from the process exit (docs/analysis/timer-w3-app-
 * delivery-plan.md §3.2).
 */
class TimerVerdictTest {
    @Test
    fun `the hold line is a success`() {
        assertEquals(
            TimerVerdict.Success,
            TimerVerdict.of("[+] done; fake cred page held by this process, reboot to clean"),
        )
    }

    @Test
    fun `a parked failure is reported as such`() {
        assertEquals(
            TimerVerdict.ParkedFailure,
            TimerVerdict.of("[!] fake cred write failed -- parking without reap (zombie held)"),
        )
    }

    @Test
    fun `ordinary progress lines carry no verdict`() {
        assertNull(TimerVerdict.of("[+] child exit status=0x0"))
        assertNull(TimerVerdict.of("[!] child report missing (n=-1)"))
        assertNull(TimerVerdict.of("[ksu] late-load rc=0"))
        assertNull(TimerVerdict.of("KSU_LOADED"))
    }
}
