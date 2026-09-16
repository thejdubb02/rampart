package org.rampart

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The icon set, drawn here rather than pulled in.
 *
 * Material's extended icon pack is a large dependency for a dozen glyphs, and its house
 * style is not ours. These are the same paths as the design canvas, on an 18 unit grid,
 * stroked rather than filled so they sit at the weight of the text beside them.
 */
private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 18.dp,
        defaultHeight = 18.dp,
        viewportWidth = 18f,
        viewportHeight = 18f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = null,
            // Icon() recolours whatever is drawn, so the colour here is only a placeholder.
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

internal object RampartIcons {
    val Inbox = icon(
        "Inbox",
        "M2 9.5h3.8l1.2 2.2h4L12.2 9.5H16M2 9.5 4.2 3.4A1 1 0 0 1 5.1 2.8h7.8a1 1 0 0 1 .9.6" +
            "L16 9.5v4a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 2 13.5Z",
    )
    val Archive = icon(
        "Archive",
        "M2.5 5.5h13v8a1.5 1.5 0 0 1-1.5 1.5H4a1.5 1.5 0 0 1-1.5-1.5Z M1.6 2.8h14.8v2.7H1.6z M7 9h4",
    )
    val Drafts = icon("Drafts", "M4 2.6h6.2L14 6.3v9a1.2 1.2 0 0 1-1.2 1.2H4A1.2 1.2 0 0 1 2.8 15.3V3.8A1.2 1.2 0 0 1 4 2.6Z M10 2.8v3.6h3.7")
    val Junk = icon("Junk", "M9 2.4 16 14.6H2Z M9 7.2v3.1M9 12.5v.1")
    val Sent = icon("Sent", "M16 2.6 8.4 10.2M16 2.6l-5 13-2.6-5.4L3 7.6Z")
    val Trash = icon("Trash", "M2.8 4.8h12.4M7 4.8V3.2h4v1.6M4.4 4.8l.7 10a1.2 1.2 0 0 0 1.2 1.1h5.4a1.2 1.2 0 0 0 1.2-1.1l.7-10")
    val Folder = icon("Folder", "M2.2 5.2A1.2 1.2 0 0 1 3.4 4h3.2l1.6 2h6.4a1.2 1.2 0 0 1 1.2 1.2v6.6A1.2 1.2 0 0 1 14.6 15H3.4a1.2 1.2 0 0 1-1.2-1.2Z")
    val Write = icon("Write", "m11.6 3.2 3.2 3.2M3 15l.7-3.2 8.2-8.2 3.2 3.2-8.2 8.2Z")
    val Settings = icon(
        "Settings",
        "M2.8 5.4h2.6M9.4 5.4h5.8M2.8 12.6h5.8M12.4 12.6h2.8" +
            "M7.4 3.8a1.6 1.6 0 1 1 0 3.2 1.6 1.6 0 0 1 0-3.2" +
            "M10.4 11a1.6 1.6 0 1 1 0 3.2 1.6 1.6 0 0 1 0-3.2",
    )
    val Check = icon("Check", "m3.6 9.4 3.6 3.6L14.4 5.6")
    val Back = icon("Back", "M15 9H3.6M8 3.6 2.6 9l5.4 5.4")
    val Collapse = icon("Collapse", "m10.5 4-5 5 5 5")
    val Expand = icon("Expand", "m7 4 5 5-5 5")

    /**
     * Roles are the standard JMAP ones. Anything else is somebody's own folder and gets the
     * plain folder glyph rather than a guess.
     */
    fun forRole(role: String?): ImageVector = when (role) {
        "inbox" -> Inbox
        "archive" -> Archive
        "drafts" -> Drafts
        "junk" -> Junk
        "sent" -> Sent
        "trash" -> Trash
        else -> Folder
    }
}
