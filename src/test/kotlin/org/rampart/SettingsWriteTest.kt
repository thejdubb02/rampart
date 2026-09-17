package org.rampart

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings are read, changed by one key, and written back whole. Signatures save on every
 * keystroke, so this file is now rewritten hundreds of times while somebody types, and a
 * write that loses the other keys would take the window position and theme with it.
 */
class SettingsWriteTest {
    /**
     * Somewhere of its own. Without this the suite rewrites the settings of whoever ran it,
     * which on a machine that also runs Rampart means losing their theme and window
     * position to a test.
     */
    @BeforeTest
    fun useAScratchDirectory() {
        if (System.getProperty("rampart.config.dir").isNullOrBlank()) {
            val dir = java.nio.file.Files.createTempDirectory("rampart-settings-test")
            dir.toFile().deleteOnExit()
            System.setProperty("rampart.config.dir", dir.toString())
        }
    }

    @Test
    fun oneKeyChangesWithoutLosingTheRest() {
        Settings.setTheme("nord")
        Settings.setWindow(SavedWindow(10, 20, 1440, 900, maximized = false))
        Settings.setNotifyOnArrival(false)

        assertEquals("nord", Settings.theme())
        assertEquals(SavedWindow(10, 20, 1440, 900, false), Settings.window())
        assertTrue(!Settings.notifyOnArrival())
    }


    /** No temporary file is left lying beside the settings after a write. */
    @Test
    fun theWriteLeavesNothingBehind() {
        Settings.setTheme("dracula")
        val beside = Accounts.file().resolveSibling("settings.json.new")
        assertTrue(!java.nio.file.Files.exists(beside), "a leftover $beside means the move did not happen")
    }
}
