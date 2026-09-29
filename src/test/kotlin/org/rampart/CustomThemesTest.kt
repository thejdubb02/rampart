package org.rampart

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CustomThemesTest {
    private val theme = Theme(
        key = "custom:harbour",
        label = "Harbour",
        dark = true,
        art = null,
        background = Color(0xFF102030),
        surface = Color(0xFF203040),
        surfaceVariant = Color(0xFF304050),
        selection = Color(0xFF405060),
        text = Color(0xFFF0E0D0),
        muted = Color(0xFFC0B0A0),
        line = Color(0xFF506070),
        accent = Color(0xFF708090),
        onAccent = Color(0xFF010203),
    )

    @Test
    fun themeJsonRoundTrips() {
        assertEquals(theme, ThemeJson.decode(ThemeJson.encode(theme)))
    }

    @Test
    fun blackAndWhiteHaveMaximumContrast() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.0001)
    }

    @Test
    fun invalidColourIsRejectedOnImport() {
        val invalid = ThemeJson.encode(theme).replace("#102030", "navy")
        assertFailsWith<IllegalArgumentException> { ThemeJson.decode(invalid) }
    }
}
