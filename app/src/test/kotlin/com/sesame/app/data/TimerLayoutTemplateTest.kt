package com.sesame.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimerLayoutTemplateTest {
    @Test
    fun the_5_10_series_selects_the_5_10_template() {
        assertEquals(
            "5.10",
            timerLayoutTemplate("5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"),
        )
        assertEquals("5.10", timerLayoutTemplate("5.10.43-android12-9-00001-g1234"))
    }

    /*
     * The defect this pins: the release's dash follows the patch level, so a
     * `startsWith("5.10-")` predicate matched no real release and the tool ran
     * without its template on 5.10 (the "fake-path profile is incomplete"
     * refusal on the diting, 2026-11-23).
     */
    @Test
    fun the_patch_level_dash_is_not_the_series() {
        assertNull(timerLayoutTemplate("5.10-android12-9-00003"))
        assertNull(timerLayoutTemplate("5.15.194-android13-8-g1234"))
        assertNull(timerLayoutTemplate("6.1.138-android14-11-g44bda9e8f6e9-ab13792638"))
        assertNull(timerLayoutTemplate(""))
    }
}
