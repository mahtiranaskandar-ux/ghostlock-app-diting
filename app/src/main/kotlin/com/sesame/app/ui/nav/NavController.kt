package com.sesame.app.ui.nav

import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

/**
 * The one navigation truth: a typed back stack.
 *
 * Invariants: [Screen.Main] is always at the bottom; `pop()` at [Screen.Main]
 * returns false and leaves the stack alone (the Activity's default back then
 * finishes the app); a screen is never pushed twice in a row.
 */
internal class NavController(initial: List<Screen> = listOf(Screen.Main)) {
    val backStack: NavBackStack = navBackStackOf(initial.first()).apply {
        addAll(initial.drop(1))
    }

    val current: Screen
        get() = backStack.lastOrNull() as? Screen ?: Screen.Main

    val canPop: Boolean
        get() = backStack.size > 1

    fun push(screen: Screen) {
        if (screen == Screen.Main) {
            reset()
            return
        }
        if (current == screen) return
        backStack.add(screen)
    }

    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** Pops everything above [screen], leaving it on top; false if absent. */
    fun popTo(screen: Screen): Boolean {
        val index = backStack.indexOf(screen)
        if (index < 0) return false
        while (backStack.size > index + 1) backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun reset() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
}
