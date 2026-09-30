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
        assertEquals(Density.EXTRA_COMPACT, Density.of("extra-compact"))
        assertEquals(Density.NORMAL, Density.of("normal"))
        assertEquals(Density.SPACIOUS, Density.of("spacious"))
        assertEquals(Density.COMPACT, Density.of("COMPACT"))
        assertEquals(Density.EXTRA_COMPACT, Density.of("EXTRA_COMPACT"))
        assertEquals(Density.NORMAL, Density.of("NORMAL"))
        assertEquals(Density.SPACIOUS, Density.of("SPACIOUS"))
        assertEquals(Density.NORMAL, Density.of(null))
        assertEquals(Density.NORMAL, Density.of(""))
        assertEquals(Density.NORMAL, Density.of("unknown"))
        assertEquals(listOf(Density.COMPACT, Density.EXTRA_COMPACT), Density.entries.take(2))
    }

    @Test
    fun `vertical padding matches density specifications`() {
        assertEquals(6.dp to 6.dp, densityVerticalPadding(Density.COMPACT))
        assertEquals(2.dp to 2.dp, densityVerticalPadding(Density.EXTRA_COMPACT))
        assertEquals(11.dp to 12.dp, densityVerticalPadding(Density.NORMAL))
        assertEquals(15.dp to 16.dp, densityVerticalPadding(Density.SPACIOUS))
    }

    @Test
    fun `avatar top padding matches density specifications`() {
        assertEquals(0.dp, densityAvatarTopPadding(Density.COMPACT))
        assertEquals(0.dp, densityAvatarTopPadding(Density.EXTRA_COMPACT))
        assertEquals(12.dp, densityAvatarTopPadding(Density.NORMAL))
        assertEquals(15.dp, densityAvatarTopPadding(Density.SPACIOUS))
    }

    @Test
    fun `preview line count matches density specifications`() {
        assertEquals(0, densityPreviewLines(Density.COMPACT))
        assertEquals(0, densityPreviewLines(Density.EXTRA_COMPACT))
        assertEquals(1, densityPreviewLines(Density.NORMAL))
        assertEquals(2, densityPreviewLines(Density.SPACIOUS))
    }

    @Test
    fun `avatar visibility matches density specifications`() {
        assertFalse(densityShowAvatar(Density.COMPACT))
        assertFalse(densityShowAvatar(Density.EXTRA_COMPACT))
        assertTrue(densityShowAvatar(Density.NORMAL))
        assertTrue(densityShowAvatar(Density.SPACIOUS))
    }
}
