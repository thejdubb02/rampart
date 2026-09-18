package org.rampart

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner

/**
 * A message body as a short list of things to draw, rather than as one long string.
 *
 * The first renderer flattened the whole body into a single `AnnotatedString`, which meant
 * every picture had to be drawn underneath the text instead of where the sender put it, a
 * quote could not be given an edge, and a table came out as its cells run together. One
 * string cannot be laid out in more than one way, so the tree has to survive as a tree.
 *
 * Deliberately a small set. This is not a browser and is not becoming one: no positioning,
 * no floats, no CSS. What it does is keep the structure a person reads by, which is what was
 * actually missing.
 */
internal sealed interface Block {
    /** A run of text. [level] is 0 for ordinary text and 1 to 6 for a heading. */
    data class Words(val text: AnnotatedString, val level: Int = 0, val centred: Boolean = false) : Block

    /** Quoted mail, drawn with an edge down the side rather than only a colour. */
    data class Quote(val inner: List<Block>) : Block

    data class Listing(val items: List<AnnotatedString>, val ordered: Boolean) : Block

    /**
     * [src] is either `cid:something`, meaning a part the message carries, or an http URL,
     * meaning a fetch from the sender's server that nobody has agreed to yet.
     */
    data class Picture(val src: String, val alt: String, val width: Int?, val centred: Boolean = false) : Block

    /**
     * A table, drawn as one.
     *
     * Cells hold blocks rather than a flattened string, because a cell in a mail table
     * routinely holds a figure and a label under it, or a heading and a paragraph, and
     * flattening that to one run is what turned the morning report into a column of
     * orphaned numbers.
     *
     * [ruled] separates the two things a table is used for. A real data table gets lines
     * between its rows; a layout table is furniture and gets none, because a border round
     * somebody's newsletter is not in their design.
     */
    data class Layout(val rows: List<Row>, val ruled: Boolean) : Block {
        data class Row(val cells: List<Cell>, val background: Int? = null)

        /**
         * [weight] is how much of the width this cell takes against its neighbours. A table
         * that states widths is followed; one that does not shares evenly, which is what a
         * browser does with no other instruction.
         */
        data class Cell(
            val blocks: List<Block>,
            val weight: Float = 1f,
            val background: Int? = null,
            val centred: Boolean = false,
        )
    }

    data object Rule : Block
}

/** The blocks to draw, and every picture the body wants from the web. */
internal data class HtmlDoc(val blocks: List<Block>, val remoteImages: List<String>) {
    val blockedImages: Int get() = remoteImages.size
}

/** Blocks that stand on their own. Anything else is inline and joins the run around it. */
private val BACKGROUND = Regex("background(?:-color)?\\s*:\\s*([^;]+)", RegexOption.IGNORE_CASE)
private val WIDTH = Regex("(?<!max-)(?<!min-)width\\s*:\\s*([0-9.]+\\s*(?:px|%)?)", RegexOption.IGNORE_CASE)

/**
 * `#rgb`, `#rrggbb` or `rgb(r, g, b)` as an opaque packed colour, or null.
 *
 * Returned as an Int rather than a Compose Color so the block model stays free of the UI,
 * which is what lets the whole of this file be tested without a screen.
 */
internal fun hexColour(raw: String): Int? {
    val value = raw.trim().lowercase()
    if (value.startsWith("#")) {
        val hex = value.drop(1)
        val full = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            8 -> hex.take(6)
            else -> return null
        }
        return full.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
    }
    val parts = Regex("rgba?\\(([^)]*)\\)").find(value)?.groupValues?.get(1)
        ?.split(',')?.mapNotNull { it.trim().toFloatOrNull()?.toInt() } ?: return null
    if (parts.size < 3) return null
    val (r, g, b) = parts.take(3).map { it.coerceIn(0, 255) }
    return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
}

private val HEADINGS = mapOf("h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4, "h5" to 5, "h6" to 6)
private val CONTAINERS = setOf(
    "p", "div", "center", "pre", "dl", "dd", "dt", "article", "section",
    // Table parts are containers too, because a layout table is unwrapped rather than drawn
    // and its cells then have to be read the same way a div is.
    "tbody", "thead", "tfoot", "tr", "td", "th",
)

/**
 * The body, cleaned and cut into blocks.
 *
 * Cleaning is unchanged and still the security boundary: jsoup's `Cleaner` against the same
 * `Safelist`, which drops every tag and attribute not named there, so `script`, `style` and
 * every `on*` handler are gone before any of this runs.
 */
internal fun htmlBlocks(
    html: String,
    linkColor: Color,
    quoteColor: Color,
    /** What the body will be drawn on, so a colour out of the mail can be checked first. */
    background: Color = Color.Unspecified,
    onLink: (String) -> Unit,
): HtmlDoc {
    val doc = Jsoup.parse(html)
    doc.select("script, style, noscript, head, title").remove()
    val remote = doc.select("img").mapNotNull { webSrc(it) }
    val clean = Cleaner(SAFELIST).clean(doc)
    val cutter = Cutter(linkColor, quoteColor, onLink, background)
    return HtmlDoc(cutter.blocks(clean.body()), remote)
}

/**
 * The address a picture would be fetched from, or null when it is not a fetch from the web.
 *
 * A `src` beginning `//` is still a fetch. It means "whatever scheme this page came from",
 * and a message did not come from a scheme, so it is read as https rather than ignored.
 */
private fun webSrc(img: Element): String? {
    val raw = img.attr("abs:src").ifBlank { img.attr("src") }.trim()
    val src = if (raw.startsWith("//")) "https:$raw" else raw
    return src.takeIf {
        it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
    }
}

private class Cutter(
    private val linkColor: Color,
    private val quoteColor: Color,
    private val onLink: (String) -> Unit,
    /** What the body is drawn on, so a colour out of the mail can be checked against it. */
    private val background: Color = Color.Unspecified,
) {
    /**
     * The children of [parent], as blocks. Recurses, so a quote inside a div still nests.
     *
     * [centred] travels down with the walk because alignment in mail is set on a wrapper and
     * meant for everything inside it. A newsletter centres a div and puts the logo, the
     * banner and three headings in it, and reading the attribute only on the element that
     * holds the text finds nothing.
     */
    /**
     * The colour links are drawn in right now.
     *
     * The theme's own accent almost everywhere, and the readable ink inside a cell that has
     * a background. A button in mail is a table cell filled with the brand colour and a link
     * in the middle of it, and our accent drawn on somebody else's blue is a link nobody can
     * read. The same [inkFor] the cell's text already uses, so the two agree.
     */
    private var linkInk = linkColor

    fun blocks(parent: Element, centred: Boolean = false): List<Block> {
        val out = mutableListOf<Block>()
        var inline = Walker(linkInk, quoteColor, onLink, background)

        fun flush() {
            val text = inline.build()
            if (text.isNotBlank()) out += Block.Words(text, centred = centred)
            inline = Walker(linkInk, quoteColor, onLink, background)
        }

        for (node in parent.childNodes()) {
            val element = node as? Element
            if (element == null) {
                if (node is TextNode) inline.node(node)
                continue
            }
            when (val tag = element.tagName()) {
                "img" -> {
                    flush()
                    out += picture(element, centred || isCentred(element))
                }
                "hr" -> {
                    flush()
                    out += Block.Rule
                }
                "blockquote" -> {
                    flush()
                    val inner = blocks(element, centred)
                    if (inner.isNotEmpty()) out += Block.Quote(inner)
                }
                "ul", "ol" -> {
                    flush()
                    val items = element.children().filter { it.tagName() == "li" }.map { runOf(it) }
                        .filter { it.isNotBlank() }
                    if (items.isNotEmpty()) out += Block.Listing(items, ordered = tag == "ol")
                }
                "table" -> {
                    flush()
                    out += table(element)
                }

                in HEADINGS -> {
                    flush()
                    val text = runOf(element)
                    if (text.isNotBlank()) {
                        out += Block.Words(text, HEADINGS.getValue(tag), centred || isCentred(element))
                    }
                }
                in CONTAINERS -> {
                    // A container is only worth breaking on when it holds something that
                    // stands on its own. A div wrapped round three words is not a paragraph,
                    // and treating it as one is how a sentence ends up on four lines.
                    if (standsAlone(element)) {
                        flush()
                        out += blocks(element, centred || isCentred(element))
                    } else {
                        inline.node(element)
                    }
                }
                /*
                 * **The same question for every other element, which is where the pictures
                 * were going.** An `a`, a `span` or a `font` was read as text and nothing
                 * else, so an image inside a link contributed no characters and disappeared.
                 * Eight of the nine pictures in a real newsletter are wrapped in a link,
                 * because that is what a newsletter is, so the message arrived as prose with
                 * its logo, its banner and every button missing.
                 */
                else -> {
                    if (standsAlone(element)) {
                        flush()
                        out += blocks(element, centred || isCentred(element))
                    } else {
                        inline.node(element)
                    }
                }
            }
        }
        flush()
        return out
    }

    private fun picture(img: Element, centred: Boolean): Block.Picture = Block.Picture(
        src = webSrc(img) ?: img.attr("src").trim(),
        alt = img.attr("alt").trim(),
        // Ignored when it is silly. A sender is allowed to claim a picture is 9000 wide,
        // and a tracking pixel claims to be 1.
        width = img.attr("width").toIntOrNull()?.takeIf { it in 2..2000 },
        centred = centred || isCentred(img),
    )

    /**
     * A table, as a grid when it looks like data and as plain blocks when it does not.
     *
     * Mail uses tables for layout far more often than for data: a newsletter is usually one
     * cell holding the whole message. Drawing that as a one cell grid puts a border round the
     * entire email, so anything that is not at least two rows by two columns is unwrapped and
     * its contents read as ordinary blocks.
     */
    private fun table(element: Element): List<Block> {
        // Only this table's own rows. A nested table builds itself when the cell holding it
        // is walked, and selecting through would steal its rows into the outer one.
        val rows = element.select("tr").filter { it.parents().firstOrNull { p ->
            p.tagName() == "table"
        } === element }
        val cellsPerRow = rows.map { row ->
            row.children().filter { it.tagName() == "td" || it.tagName() == "th" }
        }

        /*
         * One cell holding everything is a wrapper, not a layout.
         *
         * This is the shape almost every newsletter starts with, and drawing it as a table
         * would put a border and a column round the whole message. Unwrapped, its contents
         * read as ordinary blocks, which is what they are.
         */
        val banner = colourOf(element) != null ||
            cellsPerRow.firstOrNull()?.firstOrNull()?.let { colourOf(it) != null } == true
        if (!banner && cellsPerRow.size <= 1 && cellsPerRow.sumOf { it.size } <= 1) {
            // The wrapper goes, its alignment does not. A newsletter centres its masthead by
            // centring the one cell it sits in, so unwrapping without carrying that across
            // is how a centred heading came out against the left margin.
            val only = cellsPerRow.firstOrNull()?.firstOrNull()
            return blocks(element, isCentred(element) || (only != null && isCentred(only)))
        }

        // Lines between rows only when this is a table of data. A single row of six figures
        // is a layout, and so is a logo beside an address block.
        val ruled = cellsPerRow.size >= 2 && cellsPerRow.count { it.size >= 2 } >= 2

        val built = cellsPerRow.mapIndexed { _, cells ->
            Block.Layout.Row(
                cells = cells.map { cell ->
                    val fill = colourOf(cell) ?: colourOf(element)
                    val was = linkInk
                    if (fill != null) linkInk = inkFor(fill)
                    val inner = blocks(cell, isCentred(cell))
                    linkInk = was
                    Block.Layout.Cell(
                        blocks = inner,
                        weight = widthOf(cell) * (cell.attr("colspan").toIntOrNull()?.coerceIn(1, 20) ?: 1),
                        background = fill,
                        centred = isCentred(cell),
                    )
                },
                background = colourOf(cells.firstOrNull()?.parent()) ?: colourOf(element),
            )
        }.filter { it.cells.isNotEmpty() }
        if (built.isEmpty()) return blocks(element)
        return listOf(Block.Layout(built, ruled))
    }

    /**
     * The background an element asks for, as a packed colour, or null for none.
     *
     * `bgcolor` and a `background-color` in a style string say the same thing and both are
     * in use: the attribute is what mail clients have always understood, the property is
     * what anything built this decade emits. Named colours are not resolved, because a
     * layout table naming one is vanishingly rare next to the cost of carrying the list.
     */
    private fun colourOf(element: Element?): Int? {
        element ?: return null
        val raw = element.attr("bgcolor").ifBlank {
            BACKGROUND.find(element.attr("style"))?.groupValues?.get(1).orEmpty()
        }.trim()
        return hexColour(raw)
    }

    /**
     * How wide a cell asks to be, relative to its neighbours.
     *
     * A percentage or a pixel count both work as a weight, because only the ratio between
     * the cells in a row matters here. Anything unparseable is one share, which is what a
     * browser gives a cell that says nothing.
     */
    private fun widthOf(element: Element): Float {
        val raw = element.attr("width").ifBlank {
            WIDTH.find(element.attr("style"))?.groupValues?.get(1).orEmpty()
        }
        val number = raw.trim().removeSuffix("%").removeSuffix("px").trim().toFloatOrNull()
        return number?.takeIf { it > 0f }?.coerceIn(1f, 10_000f) ?: 1f
    }

    /**
     * Everything under [element] as one run of inline text, structure flattened.
     *
     * The element's own colour is applied over the result, because only its children are
     * walked and a heading carries its colour on the heading. A span inside that sets its
     * own colour still wins for its own characters: an outer style is the default for the
     * run, not an override of it.
     */
    private fun runOf(element: Element): AnnotatedString {
        val walker = Walker(linkInk, quoteColor, onLink, background)
        element.childNodes().forEach { walker.node(it as Node) }
        val run = walker.build()
        val colour = readableColour(element.attr("style"), background) ?: return run
        return buildAnnotatedString {
            pushStyle(SpanStyle(color = colour))
            append(run)
            pop()
        }
    }
}

/**
 * Whether an element holds something that stands on its own rather than only words.
 *
 * The question that decides between reading an element as a run of text and recursing into
 * it. Getting it wrong in either direction is visible: too eager and a sentence in three
 * spans becomes three paragraphs, too shy and a picture inside a link is read for its text,
 * has none, and is never drawn.
 */
private fun standsAlone(element: Element): Boolean =
    element.select("img, hr, blockquote, ul, ol, table, h1, h2, h3, h4, h5, h6").isNotEmpty()

/**
 * Whether an element asks to be centred, by either of the two ways mail says so.
 *
 * `align` is the old attribute and `text-align` the CSS one, and a message of any age uses
 * both, often on different elements of the same layout. A `<center>` tag counts as well, and
 * it is still what a lot of newsletter builders emit.
 */
internal fun isCentred(element: Element): Boolean =
    element.tagName().equals("center", ignoreCase = true) ||
        element.attr("align").equals("center", ignoreCase = true) ||
        CENTRED.containsMatchIn(element.attr("style"))

private val CENTRED = Regex("(?i)text-align\\s*:\\s*center")
