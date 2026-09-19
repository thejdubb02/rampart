package org.rampart

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint

/**
 * The tray icon with the number of unread messages on it.
 *
 * **The tray is the only part of a mail client that works while nobody is looking at it.**
 * Without a count on it the window has to be open and in front for the number to mean
 * anything, which makes a desktop mail client a browser tab that cannot be tabbed away
 * from.
 *
 * Drawn over the icon rather than shipped as images, because the number goes to three
 * digits and nobody is drawing three hundred icons. A filled circle bottom right with the
 * count in it is the shape every platform already uses, so it reads without explaining.
 */
internal class BadgedIcon(private val base: Painter, private val unread: Int) : Painter() {

    override val intrinsicSize: Size get() = base.intrinsicSize

    override fun DrawScope.onDraw() {
        with(base) { draw(size) }
        if (unread <= 0) return
        // Three digits is the ceiling. A tray icon is about sixteen points on most
        // desktops, and "1247" at that size is a smudge that says less than "99+" does.
        val label = if (unread > 99) "99+" else unread.toString()
        val wide = label.length > 2
        val radius = size.minDimension * if (wide) 0.34f else 0.30f
        val centre = size.minDimension - radius - size.minDimension * 0.02f

        val canvas = drawContext.canvas.nativeCanvas
        canvas.drawCircle(centre, centre, radius, Paint().apply { color = BADGE })
        val font = Font(BOLD, radius * if (wide) 0.95f else 1.2f)
        canvas.drawString(
            label,
            centre - font.measureTextWidth(label) / 2,
            // Skia draws from the baseline and the metrics put the visual middle above it.
            // Without this the number sits low enough to touch the edge of the circle.
            centre - (font.metrics.ascent + font.metrics.descent) / 2,
            font,
            Paint().apply { color = INK },
        )
    }

    private companion object {
        val BOLD = FontMgr.default.matchFamilyStyle(null, FontStyle.BOLD)
        val BADGE = Color(0xFFDB2D54).toArgb()
        val INK = Color.White.toArgb()
    }
}

