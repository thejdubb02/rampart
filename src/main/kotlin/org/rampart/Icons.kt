package org.rampart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
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
private fun icon(name: String, path: String, weight: Float = 1.4f, solid: Boolean = false): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 18.dp,
        defaultHeight = 18.dp,
        viewportWidth = 18f,
        viewportHeight = 18f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            // A glyph that means "on" is filled rather than a second shape, so the on and
            // off states are the same outline and only the inside changes.
            fill = if (solid) SolidColor(Color.Black) else null,
            // Icon() recolours whatever is drawn, so the colour here is only a placeholder.
            stroke = SolidColor(Color.Black),
            strokeLineWidth = weight,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

/**
 * A set of icons, so a theme can carry its own.
 *
 * Separate from the colour theme on purpose: somebody who likes the dark palette and wants
 * heavier glyphs should not have to choose between them, and an icon pack somebody else
 * writes should not have to come with a palette attached.
 *
 * Every member is an ImageVector, so a pack is a list of paths and nothing more. Adding one
 * means implementing this and putting it in [ICON_PACKS].
 */
internal interface IconPack {
    val key: String
    val label: String
    val Inbox: ImageVector
    val Archive: ImageVector
    val Drafts: ImageVector
    val Junk: ImageVector
    val Sent: ImageVector
    val Trash: ImageVector
    val Folder: ImageVector
    val Write: ImageVector
    val Settings: ImageVector
    val Check: ImageVector
    val Star: ImageVector
    val Refresh: ImageVector
    val Back: ImageVector
    val Collapse: ImageVector
    val Expand: ImageVector
    val More: ImageVector
    val Close: ImageVector
    val Tick: ImageVector
    val Page: ImageVector
    val Sort: ImageVector
    val Unread: ImageVector
    val Read: ImageVector
    val Contacts: ImageVector
    val Search: ImageVector
    val Dashboard: ImageVector

    /** Hollow for a message left as the sender built it, solid for one turned dark. */
    val Bulb: ImageVector
    val BulbOn: ImageVector

}

/**
 * Roles are the standard JMAP ones. Anything else is somebody's own folder and gets the
 * plain folder glyph rather than a guess.
 *
 * An extension rather than a member, and that is the whole point. As a member it was
 * delegated along with everything else by a pack written as `IconPack by LineIcons`, so
 * `HeavyIcons.forRole("inbox")` handed back the line glyph and every folder in the sidebar
 * stayed the wrong set. An extension resolves against whatever it is called on, so a pack
 * cannot get this wrong by writing itself the obvious way.
 */
internal fun IconPack.forRole(role: String?): ImageVector = when (role) {
    "inbox" -> Inbox
    "archive" -> Archive
    "drafts" -> Drafts
    "junk" -> Junk
    "sent" -> Sent
    "trash" -> Trash
    else -> Folder
}

/** The set drawn for Rampart: thin strokes, sitting at the weight of the text beside them. */
internal object LineIcons : IconPack {
    override val key = "line"
    override val label = "Line"

    override val Inbox = icon(
        "Inbox",
        "M2 9.5h3.8l1.2 2.2h4L12.2 9.5H16M2 9.5 4.2 3.4A1 1 0 0 1 5.1 2.8h7.8a1 1 0 0 1 .9.6" +
            "L16 9.5v4a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 2 13.5Z",
    )
    override val Archive = icon(
        "Archive",
        "M2.5 5.5h13v8a1.5 1.5 0 0 1-1.5 1.5H4a1.5 1.5 0 0 1-1.5-1.5Z M1.6 2.8h14.8v2.7H1.6z M7 9h4",
    )
    override val Drafts = icon("Drafts", "M4 2.6h6.2L14 6.3v9a1.2 1.2 0 0 1-1.2 1.2H4A1.2 1.2 0 0 1 2.8 15.3V3.8A1.2 1.2 0 0 1 4 2.6Z M10 2.8v3.6h3.7")
    override val Junk = icon("Junk", "M9 2.4 16 14.6H2Z M9 7.2v3.1M9 12.5v.1")
    override val Sent = icon("Sent", "M16 2.6 8.4 10.2M16 2.6l-5 13-2.6-5.4L3 7.6Z")
    override val Trash = icon("Trash", "M2.8 4.8h12.4M7 4.8V3.2h4v1.6M4.4 4.8l.7 10a1.2 1.2 0 0 0 1.2 1.1h5.4a1.2 1.2 0 0 0 1.2-1.1l.7-10")
    override val Folder = icon("Folder", "M2.2 5.2A1.2 1.2 0 0 1 3.4 4h3.2l1.6 2h6.4a1.2 1.2 0 0 1 1.2 1.2v6.6A1.2 1.2 0 0 1 14.6 15H3.4a1.2 1.2 0 0 1-1.2-1.2Z")
    override val Write = icon("Write", "m11.6 3.2 3.2 3.2M3 15l.7-3.2 8.2-8.2 3.2 3.2-8.2 8.2Z")
    override val Settings = icon(
        "Settings",
        "M2.8 5.4h2.6M9.4 5.4h5.8M2.8 12.6h5.8M12.4 12.6h2.8" +
            "M7.4 3.8a1.6 1.6 0 1 1 0 3.2 1.6 1.6 0 0 1 0-3.2" +
            "M10.4 11a1.6 1.6 0 1 1 0 3.2 1.6 1.6 0 0 1 0-3.2",
    )
    override val Check = icon("Check", "m3.6 9.4 3.6 3.6L14.4 5.6")
    override val Bulb = icon("Bulb", "M9 2.6a4.6 4.6 0 0 0-2.7 8.3c.5.4.8 1 .8 1.6v.3h3.8v-.3c0-.6.3-1.2.8-1.6A4.6 4.6 0 0 0 9 2.6Z M7.1 14.4h3.8 M7.7 16.1h2.6")
    override val BulbOn = icon("BulbOn", "M9 2.6a4.6 4.6 0 0 0-2.7 8.3c.5.4.8 1 .8 1.6v.3h3.8v-.3c0-.6.3-1.2.8-1.6A4.6 4.6 0 0 0 9 2.6Z M7.1 14.4h3.8 M7.7 16.1h2.6", solid = true)
    override val Star = icon("Star", "M9 2.4 11.1 6.7 15.8 7.4 12.4 10.7 13.2 15.4 9 13.2 4.8 15.4 5.6 10.7 2.2 7.4 6.9 6.7Z")
    override val Refresh = icon(
        "Refresh",
        "M15.2 9a6.2 6.2 0 1 1-1.9-4.5 M15.4 2.6v3.6h-3.6",
    )
    override val Contacts = icon(
        "Contacts",
        "M9 3.4a2.7 2.7 0 1 1 0 5.4 2.7 2.7 0 0 1 0-5.4M3.6 15.4a5.4 5.4 0 0 1 10.8 0",
    )
    override val Search = icon("Search", "M8.1 2.6a5.5 5.5 0 1 1 0 11 5.5 5.5 0 0 1 0-11M12.2 12.2 15.6 15.6")
    // Three bars of different heights, which is what a chart looks like at 16 pixels.
    override val Dashboard = icon("Dashboard", "M3.4 15V9.6M9 15V3.4M14.6 15v-8")
    override val Back = icon("Back", "M15 9H3.6M8 3.6 2.6 9l5.4 5.4")
    override val Collapse = icon("Collapse", "m10.5 4-5 5 5 5")
    override val Expand = icon("Expand", "m7 4 5 5-5 5")
    override val More = icon("More", "M9 4.2v.1M9 9v.1M9 13.8v.1")

    override val Close = icon("Close", "M4.5 4.5l9 9M13.5 4.5l-9 9")

    /** A tick, for the one option in use. */
    override val Tick = icon("Tick", "M3.5 9.5 7 13l7.5-8")

    /** A page, for showing a message the way its sender drew it. */
    override val Page = icon("Page", "M4 2.6h7l3 3v9.8H4zM11 2.6v3h3")

    /** Three rules, longest first, which is what every list reads as "order". */
    override val Sort = icon("Sort", "M3 4.5h12M3 9h8M3 13.5h4")

    /** An open envelope, for marking something unread again. */
    override val Unread = icon("Unread", "M2.6 7.2 9 2.6l6.4 4.6v7.2H2.6zM2.6 7.2 9 11.4l6.4-4.2")

    /** A sealed envelope, for marking something read. */
    override val Read = icon("Read", "M2.6 4.8h12.8v8.4H2.6zM2.6 4.8 9 9.6l6.4-4.8")

}

/** Rebuilds a glyph at a heavier stroke, from the paths the line set already holds. */
private fun heavy(source: ImageVector): ImageVector = ImageVector.Builder(
    name = source.name,
    defaultWidth = source.defaultWidth,
    defaultHeight = source.defaultHeight,
    viewportWidth = source.viewportWidth,
    viewportHeight = source.viewportHeight,
).apply {
    source.root.forEach { node ->
        (node as? androidx.compose.ui.graphics.vector.VectorPath)?.let {
            addPath(
                pathData = it.pathData,
                // Kept, or a glyph that means "on" by being filled comes out hollow in the
                // heavy pack and the two states become indistinguishable.
                fill = it.fill,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }
}.build()

/**
 * The same shapes at a heavier stroke.
 *
 * Deliberately the cheapest possible second pack, and it is still a real choice: on a large
 * screen the line set can read as faint, and this is the fix without a second set of paths
 * to keep in step with the first. It also proves the seam works, which is the part that
 * matters for a pack somebody else writes.
 */
internal object HeavyIcons : IconPack by LineIcons {
    /*
     * Every glyph is lazy, and has to be.
     *
     * The pack list holds both sets, so loading it starts LineIcons, which starts
     * HeavyIcons, which needs LineIcons finished. Built eagerly that is a static
     * initialisation cycle and every icon comes back null; built on first use there is no
     * cycle, and by then nothing is half made.
     */
    override val key = "heavy"
    override val label = "Heavy"
    override val Inbox by lazy { heavy(LineIcons.Inbox) }
    override val Contacts by lazy { heavy(LineIcons.Contacts) }
    override val Dashboard by lazy { heavy(LineIcons.Dashboard) }
    override val Search by lazy { heavy(LineIcons.Search) }
    override val Archive by lazy { heavy(LineIcons.Archive) }
    override val Drafts by lazy { heavy(LineIcons.Drafts) }
    override val Junk by lazy { heavy(LineIcons.Junk) }
    override val Sent by lazy { heavy(LineIcons.Sent) }
    override val Trash by lazy { heavy(LineIcons.Trash) }
    override val Folder by lazy { heavy(LineIcons.Folder) }
    override val Star by lazy { heavy(LineIcons.Star) }
    override val Bulb by lazy { heavy(LineIcons.Bulb) }
    override val BulbOn by lazy { heavy(LineIcons.BulbOn) }
    override val Refresh by lazy { heavy(LineIcons.Refresh) }
    override val Back by lazy { heavy(LineIcons.Back) }
    override val Collapse by lazy { heavy(LineIcons.Collapse) }
    override val Expand by lazy { heavy(LineIcons.Expand) }
    override val More by lazy { heavy(LineIcons.More) }
    override val Close by lazy { heavy(LineIcons.Close) }
    override val Tick by lazy { heavy(LineIcons.Tick) }
    override val Page by lazy { heavy(LineIcons.Page) }
    override val Sort by lazy { heavy(LineIcons.Sort) }
    override val Unread by lazy { heavy(LineIcons.Unread) }
    override val Read by lazy { heavy(LineIcons.Read) }
}

/** Every pack on offer. A new one is added here and nowhere else. */
internal val ICON_PACKS: List<IconPack> = listOf(LineIcons, HeavyIcons)

internal fun iconPack(key: String?): IconPack =
    ICON_PACKS.firstOrNull { it.key == key } ?: LineIcons

/**
 * The pack in use, read wherever an icon is drawn.
 *
 * Static because it changes about once a year and a normal CompositionLocal would make
 * every icon a read that can invalidate.
 */
internal val LocalIconPack = staticCompositionLocalOf<IconPack> { LineIcons }

/**
 * The icons, as the call sites already ask for them.
 *
 * A property with a composable getter, so `RampartIcons.Inbox` keeps working everywhere and
 * quietly comes from whichever pack is in use. The alternative was editing a hundred call
 * sites to thread a parameter through, which is a worse trade for the same result.
 */
internal val RampartIcons: IconPack
    @Composable get() = LocalIconPack.current
