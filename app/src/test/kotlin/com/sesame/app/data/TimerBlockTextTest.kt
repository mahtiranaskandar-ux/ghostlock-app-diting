package com.sesame.app.data

import com.sesame.app.data.profile.Timer98260
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The optional timer block travels as text beside a generated cut. */
class TimerBlockTextTest {
    private val block = """
        timer_98260 {
          version = 1
          trigger = "killable"
        }
    """.trimIndent()

    @Test
    fun `extracts the block from a generated document`() {
        val document = "release = \"x\"\n" +
            "route {\n  select_stack {\n    waiter_shift = 1\n  }\n}\n\n" + block + "\n"

        assertEquals(block, TimerBlockText.extract(document))
    }

    @Test
    fun `a document without the block has none`() {
        assertNull(TimerBlockText.extract("release = \"x\"\n"))
    }

    @Test
    fun `append keeps exactly one block and the result parses`() {
        val cut = "release = \"x\"\n" +
            "route {\n  select_stack {\n    waiter_shift = 1\n  }\n}\n"

        val once = TimerBlockText.append(cut, block)
        assertTrue(once.contains(Timer98260.BLOCK_KEY))
        assertEquals(once, TimerBlockText.append(cut, block))
        assertEquals(once, TimerBlockText.append(once, block))

        val parsed = HoconSupport.parseValue(once).asValueMap()
        assertEquals(1L, parsed?.getLongAt("timer_98260.version"))
        assertEquals(1L, parsed?.getLongAt("route.select_stack.waiter_shift"))
    }
}
