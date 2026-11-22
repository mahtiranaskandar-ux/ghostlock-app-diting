package com.sesame.app.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavControllerTest {
    @Test
    fun `starts at Main and cannot pop below it`() {
        val nav = NavController()

        assertEquals(Screen.Main, nav.current)
        assertFalse(nav.canPop)
        assertFalse(nav.pop())
        assertEquals(listOf(Screen.Main), nav.backStack.toList())
    }

    @Test
    fun `push and pop round-trip in order`() {
        val nav = NavController()

        nav.push(Screen.Advanced)
        nav.push(Screen.Parameters)
        nav.push(Screen.Builtin)

        assertEquals(Screen.Builtin, nav.current)
        assertEquals(
            listOf(Screen.Main, Screen.Advanced, Screen.Parameters, Screen.Builtin),
            nav.backStack.toList(),
        )

        assertTrue(nav.pop())
        assertEquals(Screen.Parameters, nav.current)
        assertTrue(nav.pop())
        assertEquals(Screen.Advanced, nav.current)
        assertTrue(nav.pop())
        assertEquals(Screen.Main, nav.current)
        assertFalse(nav.pop())
    }

    @Test
    fun `pushing Main resets the stack`() {
        val nav = NavController()
        nav.push(Screen.Advanced)
        nav.push(Screen.Parameters)

        nav.push(Screen.Main)

        assertEquals(listOf(Screen.Main), nav.backStack.toList())
        assertFalse(nav.canPop)
    }

    @Test
    fun `a screen is never pushed twice in a row`() {
        val nav = NavController()

        nav.push(Screen.Advanced)
        nav.push(Screen.Advanced)

        assertEquals(listOf(Screen.Main, Screen.Advanced), nav.backStack.toList())
    }

    @Test
    fun `popTo keeps the target on top`() {
        val nav = NavController()
        nav.push(Screen.Advanced)
        nav.push(Screen.Parameters)
        nav.push(Screen.Builtin)

        assertTrue(nav.popTo(Screen.Advanced))

        assertEquals(listOf(Screen.Main, Screen.Advanced), nav.backStack.toList())
        assertFalse(nav.popTo(Screen.Measurements))
        assertEquals(listOf(Screen.Main, Screen.Advanced), nav.backStack.toList())
    }
}
