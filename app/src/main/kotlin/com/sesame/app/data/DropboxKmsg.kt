package com.sesame.app.data

/**
 * The previous boot's kernel console as Android's `system_server` stores it:
 * `BootReceiver` reads `/sys/fs/pstore/console-ramoops*` (or `/proc/last_kmsg`)
 * at boot and adds a DropBox entry tagged `SYSTEM_LAST_KMSG`.
 *
 * That indirection is the only kernel-log channel a non-root device has: the
 * pstore nodes are root-only, but DropBox is readable by shell. The tag is
 * probed from the `dumpsys dropbox` listing, whose entry lines look like
 *
 *     2026-11-20 21:13:59 SYSTEM_LAST_KMSG (compressed text, 8361 bytes)
 *
 * A `dumpsys dropbox --print SYSTEM_LAST_KMSG` on a device without the entry
 * prints a search line, not an entry, so the listing is the presence test.
 */
internal object DropboxKmsg {
    const val Tag = "SYSTEM_LAST_KMSG"

    private val EntryLine =
        Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\s+SYSTEM_LAST_KMSG\b""")

    fun isPresent(listing: String): Boolean =
        listing.lineSequence().any { EntryLine.containsMatchIn(it) }
}
