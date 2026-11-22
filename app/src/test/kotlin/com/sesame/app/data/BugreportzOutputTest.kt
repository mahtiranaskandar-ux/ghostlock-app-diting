package com.sesame.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BugreportzOutputTest {
    @Test
    fun parsesTheOkLine() {
        val output = """
            PROGRESS:1/1775
            PROGRESS:900/1775
            OK:/data/user_de/0/com.android.shell/files/bugreports/bugreport-diting-2026-11-21.zip
        """.trimIndent()
        assertEquals(
            "/data/user_de/0/com.android.shell/files/bugreports/bugreport-diting-2026-11-21.zip",
            BugreportzOutput.parsePath(output),
        )
    }

    @Test
    fun aFailLineIsNotAPath() {
        val output = "PROGRESS:12/1775\nFAIL:dumpstate exited with 1\n"
        assertNull(BugreportzOutput.parsePath(output))
    }

    @Test
    fun emptyOutputIsNull() {
        assertNull(BugreportzOutput.parsePath(""))
        assertNull(BugreportzOutput.parsePath("PROGRESS:1/10\n"))
    }
}
