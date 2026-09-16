package org.rampart

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The themes are a table of numbers, so the thing that can go wrong is a number, and no
 * amount of looking at the settings screen catches a palette whose body text cannot be read
 * on its own background. These are the invariants a new theme has to satisfy.
 */
class ThemesTest {
    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG relative luminance, on the opaque colours the themes are made of. */
    private fun luminance(color: androidx.compose.ui.graphics.Color): Double {
        val argb = color.value.toLong() ushr 32
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    @Test
    fun keysAreUnique() {
        assertEquals(THEMES.size, THEMES.map { it.key }.toSet().size, "two themes share a key")
        assertEquals(THEMES.size, THEMES.map { it.label }.toSet().size, "two themes share a label")
    }

    @Test
    fun bothSystemPreferencesHaveADefault() {
        assertTrue(THEMES.any { it.dark })
        assertTrue(THEMES.any { !it.dark })
        assertEquals("rampart-dark", themeFor(null, systemDark = true).key)
        assertEquals("rampart-light", themeFor(null, systemDark = false).key)
        assertEquals("nord", themeFor("nord", systemDark = false).key)
        // A theme that was removed since it was chosen must not leave the app with none.
        assertEquals("rampart-dark", themeFor("gone", systemDark = true).key)
    }

    @Test
    fun everyCharacterIsActuallyBundled() {
        THEMES.mapNotNull { it.art }.forEach { file ->
            assertNotNull(
                javaClass.classLoader.getResource("art/$file"),
                "$file is named by a theme but is not in resources/art",
            )
        }
    }

    /**
     * Body text against the surface it is set on. 4.5:1 is the readable-at-body-size line,
     * and a theme that misses it is not a taste question.
     */
    @Test
    fun textIsReadableOnItsOwnSurfaces() {
        THEMES.forEach { theme ->
            listOf("background" to theme.background, "surface" to theme.surface,
                   "row" to theme.surfaceVariant, "selection" to theme.selection).forEach { (name, behind) ->
                val ratio = contrast(theme.text, behind)
                assertTrue(ratio >= 4.5, "${theme.key}: text on $name is only ${"%.1f".format(ratio)}:1")
            }
            val onAccent = contrast(theme.onAccent, theme.accent)
            assertTrue(onAccent >= 4.5, "${theme.key}: button text on the accent is only ${"%.1f".format(onAccent)}:1")
        }
    }
}
