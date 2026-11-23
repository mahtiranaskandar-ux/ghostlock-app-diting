package com.sesame.app.shizuku

/**
 * The timer tool's (CVE-2026-98260) terminal log markers. The tool parks on a
 * hit and on several failures, so its exit is not the outcome: a hit prints
 * the hold line and then parks, and a parked failure prints the park line and
 * then parks. Both processes must be left alive (the hit holds the fake cred
 * page, the failure holds the failed plants' zombies) until the reboot.
 *
 * See docs/analysis/timer-w3-app-delivery-plan.md §3.2.
 */
internal object TimerVerdict {
    /** A hit: the fake cred page is held by the parked process. */
    const val Success = 0

    /** A parked failure: reboot is the only cleanup. */
    const val ParkedFailure = 2

    /** No terminal marker before the watchdog (a wedged run). */
    const val NoVerdict = 3

    /** The run never reached the tool (Shizuku not ready or the UserService failed). */
    const val NotStarted = -2

    fun of(line: String): Int? = when {
        line.contains("[+] done; fake cred page held") -> Success
        line.contains("[!] ") && line.contains("parking without reap") -> ParkedFailure
        else -> null
    }
}
