package org.rampart

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The clamp behind the compose panel's corner handle. Getting a boundary backwards here
 * means either a panel that cannot shrink to something usable, or one a drag can grow past
 * the edge of the window that holds it.
 */
class ComposeResizeTest {
    @Test
    fun draggingBelowTheMinimumSnapsToIt() {
        val result = clampComposeSize(DpSize(50.dp, 40.dp), DpSize(2000.dp, 1200.dp))
        assertEquals(ComposeMinSize, result)
    }

    @Test
    fun draggingPastTheWindowStopsAtIt() {
        val result = clampComposeSize(DpSize(5000.dp, 5000.dp), DpSize(1200.dp, 900.dp))
        // 32dp is the 16dp margin the panel keeps on each side it isn't anchored to; 0.8 is
        // the same fraction of the window's height the panel has always capped itself at.
        assertEquals(DpSize(1168.dp, 720.dp), result)
    }

    @Test
    fun aWindowSmallerThanTheDefaultShrinksTheWantedSize() {
        // 620x620 is what a setting saved before there was a handle to drag looks like,
        // opened on a window too small to hold it at that size.
        val result = clampComposeSize(DpSize(620.dp, 620.dp), DpSize(500.dp, 500.dp))
        assertEquals(DpSize(468.dp, 400.dp), result)
    }

    @Test
    fun aWindowSmallerThanTheMinimumStillReturnsTheMinimum() {
        // The maximum a tiny window derives falls below ComposeMinSize; the floor has to
        // win over the ceiling, or the panel would be asked for a maximum below its own
        // minimum and coerceIn would throw.
        val result = clampComposeSize(DpSize(620.dp, 620.dp), DpSize(300.dp, 300.dp))
        assertEquals(ComposeMinSize, result)
    }

    @Test
    fun aSizeAlreadyInsideTheBoundsIsLeftAlone() {
        val result = clampComposeSize(DpSize(500.dp, 500.dp), DpSize(1200.dp, 900.dp))
        assertEquals(DpSize(500.dp, 500.dp), result)
    }
}
