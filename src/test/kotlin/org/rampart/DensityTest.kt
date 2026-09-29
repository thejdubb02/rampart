package org.rampart

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for message list density resolution and layout metrics.
 */
class DensityTest {
    @Test
    fun `density resolves by key or name with normal fallback`() {
        assertEquals(Density.COMPACT, Density.of("compact"))
        assertEquals(Density.NORMAL, Density.of("normal"))
        assertEquals(Density.SPACIOUS, Density.of("spacious"))
        assertEquals(Density.COMPACT, Density.of("COMPACT"))
        assertEquals(Density.NORMAL, Density.of("NORMAL"))
        assertEquals(Density.SPACIOUS, Density.of("SPACIOUS"))
        assertEquals(Density.NORMAL, Density.of(null))
        assertEquals(Density.NORMAL, Density.of(""))
        assertEquals(Density.NORMAL, Density.of("unknown"))
    }

    @Test
    fun `vertical padding matches density specifications`() {
        assertEquals(6.dp to 6.dp, densityVerticalPadding(Density.COMPACT))
        assertEquals(11.dp to 12.dp, densityVerticalPadding(Density.NORMAL))
        assertEquals(15.dp to 16.dp, densityVerticalPadding(Density.SPACIOUS))
    }

    @Test
    fun `avatar top padding matches density specifications`() {
        assertEquals(0.dp, densityAvatarTopPadding(Density.COMPACT))
        assertEquals(12.dp, densityAvatarTopPadding(Density.NORMAL))
        assertEquals(15.dp, densityAvatarTopPadding(Density.SPACIOUS))
    }

    @Test
    fun `preview line count matches density specifications`() {
        assertEquals(0, densityPreviewLines(Density.COMPACT))
        assertEquals(1, densityPreviewLines(Density.NORMAL))
        assertEquals(2, densityPreviewLines(Density.SPACIOUS))
    }

    @Test
    fun `avatar visibility matches density specifications`() {
        assertFalse(densityShowAvatar(Density.COMPACT))
        assertTrue(densityShowAvatar(Density.NORMAL))
        assertTrue(densityShowAvatar(Density.SPACIOUS))
    }
}
