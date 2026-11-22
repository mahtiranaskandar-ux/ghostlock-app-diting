package com.sesame.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropboxKmsgTest {
    @Test
    fun findsTheEntryLine() {
        val listing = """
            Drop box contents: 17 entries
            Max entries: 1000
            Low priority rate limit period: 2000 ms
            2026-11-20 21:13:59 SYSTEM_TOMBSTONE (compressed text, 8361 bytes)
            2026-11-21 03:04:05 SYSTEM_LAST_KMSG (compressed text, 65536 bytes)
        """.trimIndent()
        assertTrue(DropboxKmsg.isPresent(listing))
    }

    @Test
    fun headerAndSearchLineAreNotEntries() {
        // `dumpsys dropbox --print TAG` prints a search line even when the tag
        // is absent; the listing is the presence test, and its header names no
        // entry.
        val listing = """
            Drop box contents: 2 entries
            Max entries: 1000
            2026-11-20 21:13:59 SYSTEM_TOMBSTONE (compressed text, 8361 bytes)
            Searching for: SYSTEM_LAST_KMSG
        """.trimIndent()
        assertFalse(DropboxKmsg.isPresent(listing))
    }

    @Test
    fun otherTagsWithThePrefixDoNotMatch() {
        val listing = "2026-11-21 03:04:05 SYSTEM_LAST_KMSG_OLD (text, 10 bytes)"
        assertFalse(DropboxKmsg.isPresent(listing))
    }
}
