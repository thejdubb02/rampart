package org.rampart

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A hybrid theme keeps a dark window and draws the open message on a light page.
 * These check the three built-in ones, and that the page stays light unless the
 * reader asks for a dark message.
 */
class HybridThemeTest {
    private fun theme(key: String): Theme = THEMES.first { it.key == key }

    private fun contrast(a: Color, b: Color): Float {
        val lighter = max(a.luminance(), b.luminance())
        val darker = min(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun expect(
        theme: Theme,
        background: Long,
        surface: Long,
        surfaceVariant: Long,
        selection: Long,
        text: Long,
        muted: Long,
        line: Long,
        accent: Long,
        onAccent: Long,
    ) {
        assertEquals(Color(background), theme.background, theme.key)
        assertEquals(Color(surface), theme.surface, theme.key)
        assertEquals(Color(surfaceVariant), theme.surfaceVariant, theme.key)
        assertEquals(Color(selection), theme.selection, theme.key)
        assertEquals(Color(text), theme.text, theme.key)
        assertEquals(Color(muted), theme.muted, theme.key)
        assertEquals(Color(line), theme.line, theme.key)
        assertEquals(Color(accent), theme.accent, theme.key)
        assertEquals(Color(onAccent), theme.onAccent, theme.key)
    }

    @Test
    fun threeHybridsFollowTheExistingDarkThemes() {
        assertEquals(
            listOf("ink-paper", "midnight-cloud", "forest-parchment"),
            THEMES.takeLast(3).map { it.key },
        )
        assertEquals(THEMES.size, THEMES.map { it.key }.toSet().size, "two themes share a key")
        assertEquals("rampart-dark", THEMES.first { it.dark }.key)
        assertEquals("rampart-light", THEMES.first { !it.dark }.key)
        val listed = THEMES.map { it.key }.toSet()
        listOf("ink-paper", "midnight-cloud", "forest-parchment").forEach { key ->
            val theme = theme(key)
            val page = assertNotNull(theme.page, key)
            assertTrue(theme.dark, key)
            assertEquals("$key-page", page.key)
            assertEquals(theme.label, page.label)
            assertFalse(page.dark, key)
            assertEquals(null, page.art, key)
            assertEquals(null, page.page, key)
            assertFalse(page.key in listed, page.key)
        }
    }

    @Test
    fun palettesMatchTheSpec() {
        val ink = theme("ink-paper")
        expect(
            ink,
            background = 0xFF131316, surface = 0xFF0F0F12, surfaceVariant = 0xFF1B1B20,
            selection = 0xFF2A1A21, text = 0xFFECECF1, muted = 0xFF9B9BA8,
            line = 0xFF26262D, accent = 0xFFFF7A96, onAccent = 0xFF000000,
        )
        expect(
            ink.page!!,
            background = 0xFFFFFFFF, surface = 0xFFFFFFFF, surfaceVariant = 0xFFF4F4F6,
            selection = 0xFFFDECF0, text = 0xFF17171B, muted = 0xFF63636E,
            line = 0xFFE6E6EB, accent = 0xFFDB2D54, onAccent = 0xFFFFFFFF,
        )
        val midnight = theme("midnight-cloud")
        expect(
            midnight,
            background = 0xFF10172A, surface = 0xFF0C1222, surfaceVariant = 0xFF182139,
            selection = 0xFF1F2A4D, text = 0xFFE6EAF5, muted = 0xFF97A0BA,
            line = 0xFF222C47, accent = 0xFF8B95FF, onAccent = 0xFF000000,
        )
        expect(
            midnight.page!!,
            background = 0xFFF5F8FC, surface = 0xFFF9FBFE, surfaceVariant = 0xFFEAF0F8,
            selection = 0xFFE3E7FF, text = 0xFF141A2B, muted = 0xFF5B647A,
            line = 0xFFDCE3EE, accent = 0xFF4652C8, onAccent = 0xFFFFFFFF,
        )
        val forest = theme("forest-parchment")
        expect(
            forest,
            background = 0xFF14201A, surface = 0xFF101A15, surfaceVariant = 0xFF1B2A22,
            selection = 0xFF223A2C, text = 0xFFE7EFE9, muted = 0xFF97AA9E,
            line = 0xFF24352B, accent = 0xFF7FD19B, onAccent = 0xFF000000,
        )
        expect(
            forest.page!!,
            background = 0xFFFBF8EE, surface = 0xFFFDFBF4, surfaceVariant = 0xFFF1ECDD,
            selection = 0xFFE3F0E6, text = 0xFF1D2520, muted = 0xFF5F6B63,
            line = 0xFFE4DDCB, accent = 0xFF2F7D4F, onAccent = 0xFFFFFFFF,
        )
    }

    @Test
    fun pageIsLightUnlessTheReaderAsksForDark() {
        val hybrid = theme("ink-paper")
        val dark = theme("rampart-dark")
        val light = theme("rampart-light")
        // 0.1 would be a dark window. A hybrid still owes a light page when nothing was chosen.
        assertFalse(pageIsDark("", hybrid, 0.1f))
        assertTrue(pageIsDark("dark", hybrid, 0.9f))
        assertTrue(pageIsDark("", dark, 0.1f))
        assertFalse(pageIsDark("", light, 0.9f))
        assertFalse(pageIsDark("light", hybrid, 0.1f))
        assertFalse(pageIsDark("light", dark, 0.1f))
        assertFalse(pageIsDark("light", light, 0.1f))
    }

    @Test
    fun textClearsBodyContrastOnThePageAndOnTheChrome() {
        THEMES.mapNotNull { theme -> theme.page?.let { theme to it } }.forEach { (theme, page) ->
            val onPage = contrast(page.text, page.background)
            val onChrome = contrast(theme.text, theme.background)
            assertTrue(onPage >= 4.5f, "${theme.key} page is ${"%.1f".format(onPage)}:1")
            assertTrue(onChrome >= 4.5f, "${theme.key} chrome is ${"%.1f".format(onChrome)}:1")
        }
    }

    @Test
    fun aPlainMessageUsesThePageColoursAndADesignedOneStaysWhite() {
        assertEquals("#fbf8ee", plainPageBackground(theme("forest-parchment")))
        assertEquals("#1d2520", plainPageText(theme("forest-parchment")))
        assertEquals("#ffffff", plainPageBackground(theme("rampart-dark")))
        assertEquals("#1a1a1a", plainPageText(theme("rampart-dark")))

        val tinted = prepareReading(
            "a@b.c",
            "A",
            Body("<p>Tuesday works.</p>", null),
            emptyList(),
            emptyMap(),
            false,
            false,
            emptySet(),
            "#fbf8ee",
            "#1d2520",
        ).page!!.document
        assertTrue(tinted.contains("background: #fbf8ee;"))
        assertTrue(tinted.contains("color: #1d2520;"))

        val untouched = prepareReading(
            "a@b.c",
            "A",
            Body("<p>Tuesday works.</p>", null),
            emptyList(),
            emptyMap(),
            false,
            false,
            emptySet(),
        ).page!!.document
        assertTrue(untouched.contains("background: #ffffff;"))
        assertTrue(untouched.contains("color: #1a1a1a;"))

        val designed = emailDocument(
            """<table width="100%" bgcolor="#F4F1EC"><tr><td>Hello</td></tr></table>""",
            pageBackground = "#fbf8ee",
            pageText = "#1d2520",
        ).document
        assertTrue(designed.contains("background: #ffffff"))
        assertFalse(designed.contains("#fbf8ee"))
        assertFalse(designed.contains("#1d2520"))
    }
}
