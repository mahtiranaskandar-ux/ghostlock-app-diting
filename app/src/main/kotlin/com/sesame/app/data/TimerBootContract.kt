package com.sesame.app.data

/**
 * The timer route's reboot-per-attempt contract. A hit parks the tool holding
 * the fake cred page; a miss parks it holding the failed plants. Both leave
 * the boot dirty, and the reboot is the cleanup — so a second attempt on the
 * same boot is refused. Keyed on `/proc/sys/kernel/random/boot_id`, which
 * changes by itself after the reboot.
 */
internal object TimerBootContract {
    /** Returned when a same-boot retry is refused; the log says "reboot first". */
    const val Refused = 4

    fun allowsRun(lastAttemptBootId: String?, currentBootId: String): Boolean =
        lastAttemptBootId.isNullOrBlank() || lastAttemptBootId != currentBootId
}
