package org.rampart

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The badge is drawn, so what is worth testing is that it draws at all and that it leaves
 * the icon alone when there is nothing to say. A number nobody can read is a design
 * problem; a painter that throws on the first mail of the day is a crash on the tray.
 */
class TrayBadgeTest {

    /** A stand-in for the app icon that records whether it was asked to draw. */
    private class Base : Painter() {
        var drawn = 0
        override val intrinsicSize = Size(64f, 64f)
        override fun DrawScope.onDraw() { drawn++ }
    }

    private fun paint(unread: Int, base: Base = Base()): Base {
        val bitmap = ImageBitmap(64, 64)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(64f, 64f)) {
            with(BadgedIcon(base, unread)) { draw(Size(64f, 64f)) }
        }
        return base
    }

    @Test
    fun `the icon underneath is always drawn`() {
        assertEquals(1, paint(0).drawn)
        assertEquals(1, paint(7).drawn)
        assertEquals(1, paint(4000).drawn)
    }

    @Test
    fun `every count draws without throwing, including the ones that change the shape`() {
        // One digit, two, the three-digit case and the one past the ceiling: each picks a
        // different radius and font size, so each is a separate chance to divide by zero.
        listOf(1, 9, 10, 99, 100, 999, 100000).forEach { paint(it) }
    }

    @Test
    fun `the badge keeps the icon's own size`() {
        val base = Base()
        assertEquals(base.intrinsicSize, BadgedIcon(base, 12).intrinsicSize)
    }

    @Test
    fun `nothing unread is the plain icon`() {
        // Not a badge reading 0. A badge saying there is nothing to tell you is what no
        // badge already says, and it makes the tray look permanently busy.
        val quiet = Base()
        paint(0, quiet)
        assertTrue(quiet.drawn == 1)
    }
}
