package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for pure settings search matching, ranking and page filtering.
 */
class SettingsSearchTest {

    private val testPages = listOf(
        Triple("accounts", "Accounts", "General"),
        Triple("notifications", "Notifications", "General"),
        Triple("themes", "Themes", "Appearance"),
        Triple("reading", "Reading and archiving", "Mail"),
        Triple("about", "About", "Rampart"),
    )

    private val testItems = listOf(
        SettingSearchItem(
            id = "rampart.theme",
            name = "Theme",
            pageTitle = "Themes",
            pageKey = "themes",
            keywords = "dark mode light mode night appearance colours colors palette look",
        ),
        SettingSearchItem(
            id = "rampart.icons",
            name = "Icons",
            pageTitle = "Themes",
            pageKey = "themes",
            keywords = "icon pack glyphs appearance",
        ),
        SettingSearchItem(
            id = "rampart.density",
            name = "Density",
            pageTitle = "Themes",
            pageKey = "themes",
            keywords = "density message list compact spacious normal appearance",
        ),
        SettingSearchItem(
            id = "rampart.notifyOnArrival",
            name = "Notify when mail arrives",
            pageTitle = "Notifications",
            pageKey = "notifications",
            keywords = "notification alert new mail desktop popup",
        ),
        SettingSearchItem(
            id = "rampart.confirmBeforeSend",
            name = "Always confirm before sending",
            pageTitle = "Reading and archiving",
            pageKey = "reading",
            keywords = "send confirm ask",
        ),
        SettingSearchItem(
            id = "rampart.diagnosticsReporting",
            name = "Send diagnostics",
            pageTitle = "Diagnostics",
            pageKey = "diagnostics",
            keywords = "diagnostics telemetry reporting privacy",
        ),
    )

    @Test
    fun searchIndexBuildsFromComputerPlaceWithoutDrift() {
        val defaultItems = defaultSettingSearchItems(SettingsPages)
        val entries = ComputerPlace(ComputerChoices()).entries()
        assertEquals(entries.size, defaultItems.size)
        assertEquals(entries.map { it.id }, defaultItems.map { it.id })
    }

    @Test
    fun prefixRanksFirst() {
        val matches = matchSettings("send", testItems)
        assertTrue(matches.isNotEmpty())
        assertEquals("rampart.diagnosticsReporting", matches.first().id)
        assertEquals("Send diagnostics", matches.first().name)
        assertTrue(matches.any { it.id == "rampart.confirmBeforeSend" })
    }

    @Test
    fun keywordsMatch() {
        val matches = matchSettings("dark mode", testItems)
        assertEquals(1, matches.size)
        assertEquals("rampart.theme", matches.first().id)
        assertEquals("Theme", matches.first().name)
    }

    @Test
    fun noResultsSaysNoSettingMatches() {
        val result = searchSettings("nonexistent-setting-xyz", testItems, testPages)
        assertTrue(result.settings.isEmpty())
        assertEquals("No setting matches", result.emptyMessage)
    }

    @Test
    fun emptyBoxShowsNormalNav() {
        val result = searchSettings("", testItems, testPages)
        assertTrue(result.settings.isEmpty())
        assertEquals(testPages.size, result.filteredPages.size)
        assertNull(result.emptyMessage)
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val lower = matchSettings("theme", testItems)
        val upper = matchSettings("THEME", testItems)
        val mixed = matchSettings("ThEmE", testItems)
        assertEquals(lower, upper)
        assertEquals(lower, mixed)
        assertEquals("rampart.theme", lower.first().id)
    }

    @Test
    fun filtersNavPagesBySettingOrPageTitle() {
        val result = searchSettings("dark mode", testItems, testPages)
        assertEquals(listOf("themes"), result.filteredPages.map { it.first })

        val pageTitleResult = searchSettings("Accounts", testItems, testPages)
        assertEquals(listOf("accounts"), pageTitleResult.filteredPages.map { it.first })
    }
}
