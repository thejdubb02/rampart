package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The appearance choices that can be wrong without opening a window: a stored value this
 * version does not know, and which folders actually use the chosen order.
 */
class AppearanceTest {
    @Test
    fun `font size falls back to medium`() {
        assertEquals(UiFont.SMALL, UiFont.of("small"))
        assertEquals(UiFont.SMALL, UiFont.of("SMALL"))
        assertEquals(UiFont.MEDIUM, UiFont.of("medium"))
        assertEquals(UiFont.LARGE, UiFont.of("large"))
        assertEquals(UiFont.MEDIUM, UiFont.of(null))
        assertEquals(UiFont.MEDIUM, UiFont.of(""))
        assertEquals(UiFont.MEDIUM, UiFont.of("nope"))
        assertEquals(0.85f, UiFont.SMALL.scale)
        assertEquals(1f, UiFont.MEDIUM.scale)
        assertEquals(1.15f, UiFont.LARGE.scale)
    }

    @Test
    fun `animations and apply-to-all default on`() {
        assertTrue(animationsOn(null))
        assertTrue(animationsOn(true))
        assertFalse(animationsOn(false))
        assertTrue(orderAppliesEverywhere(null))
        assertTrue(orderAppliesEverywhere(true))
        assertFalse(orderAppliesEverywhere(false))
    }

    @Test
    fun `a missing dark preference is system unless a theme is named`() {
        assertEquals(ThemeMode.SYSTEM, themeModeOf(null))
        assertEquals(ThemeMode.DARK, themeModeOf(true))
        assertEquals(ThemeMode.LIGHT, themeModeOf(false))
        assertEquals(ThemeMode.SYSTEM, shownThemeMode(null, null, themeIsDark = true))
        assertEquals(ThemeMode.DARK, shownThemeMode(null, true, themeIsDark = false))
        assertEquals(ThemeMode.LIGHT, shownThemeMode(null, false, themeIsDark = true))
        // A named theme is that theme. System would be a lie next to a palette already chosen.
        assertEquals(ThemeMode.DARK, shownThemeMode("nord", null, themeIsDark = true))
        assertEquals(ThemeMode.LIGHT, shownThemeMode("rampart-light", null, themeIsDark = false))
        assertEquals("system", themeModeKey(null, null, themeIsDark = true))
        assertEquals("dark", themeModeKey("nord", null, themeIsDark = true))
    }

    @Test
    fun `a stored theme wins and system follows the computer`() {
        assertEquals("nord", appearanceTheme("nord", null, systemDark = false, THEMES).key)
        assertEquals("rampart-dark", appearanceTheme(null, null, systemDark = true, THEMES).key)
        assertEquals("rampart-light", appearanceTheme(null, null, systemDark = false, THEMES).key)
        assertEquals("rampart-dark", appearanceTheme(null, true, systemDark = false, THEMES).key)
        assertEquals("rampart-light", appearanceTheme("gone", null, systemDark = false, THEMES).key)
        val custom = THEMES.first { !it.dark }.copy(key = "mine")
        assertEquals("mine", appearanceTheme("mine", true, systemDark = true, THEMES + custom).key)
    }

    @Test
    fun `order applies to the inbox only when the switch is off`() {
        assertTrue(listIsInbox("inbox", searching = false, viewingTag = false, viewingPerson = false))
        assertFalse(listIsInbox("inbox", searching = true, viewingTag = false, viewingPerson = false))
        assertFalse(listIsInbox("inbox", searching = false, viewingTag = true, viewingPerson = false))
        assertFalse(listIsInbox("inbox", searching = false, viewingTag = false, viewingPerson = true))
        assertFalse(listIsInbox("sent", searching = false, viewingTag = false, viewingPerson = false))
        assertFalse(listIsInbox(null, searching = false, viewingTag = false, viewingPerson = false))

        assertEquals(Order.NEWEST, orderForList(Order.OLDEST, inbox = false, applyToAll = false))
        assertEquals(Order.OLDEST, orderForList(Order.OLDEST, inbox = true, applyToAll = false))
        assertEquals(Order.OLDEST, orderForList(Order.OLDEST, inbox = false, applyToAll = true))
        assertEquals(Order.SENDER, orderForList(Order.SENDER, inbox = false, applyToAll = true))
        assertEquals(Order.NEWEST, orderForList(Order.NEWEST, inbox = false, applyToAll = false))
    }
}
