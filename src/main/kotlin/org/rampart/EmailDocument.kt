package org.rampart

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * A message's HTML, cleaned and wrapped as a document an engine can be handed.
 *
 * Rampart used to lay message HTML out itself: parse it, reduce it to a list of blocks,
 * draw those in Compose. That is right for a written message and cannot be right for a
 * designed one. A marketing email is nested tables carrying inline CSS, media queries,
 * background images and fixed widths, and matching it means implementing a browser badly.
 * Each version of the hand renderer was one CSS property short of the next newsletter.
 *
 * So: sanitise, then hand it to an engine, which is what webmail does and gets for free.
 *
 * **The cleaning is the security boundary, and it runs before the engine sees a byte.**
 * Nothing executable survives it. The content security policy below is the second line, not
 * the first: it stops what is left from reaching the network even if something got through.
 */
internal data class EmailPage(
    /** The whole document, ready to be handed to the engine. */
    val document: String,
    /**
     * The pictures that were held back, each already judged.
     *
     * The list rather than a count, because the useful thing to say is not how many were
     * blocked but who was asking: see [picturesIn].
     */
    val held: List<RemotePicture>,
) {
    val blocked: Int get() = held.size
    val trackers: Int get() = trackerCount(held)
}

internal fun emailDocument(
    html: String,
    /** Content-ID to `data:` URI, for the pictures the message carries itself. */
    carried: Map<String, String> = emptyMap(),
    /** Whether pictures may be fetched from the web. Off is the default and the safe one. */
    remoteImages: Boolean = false,
    /** Render for a dark window. See [DARK_CSS] for what that does and does not do. */
    dark: Boolean = false,
): EmailPage {
    val source = Jsoup.parse(html)
    val clean = Cleaner(EMAIL_SAFELIST).clean(source)
    clean.outputSettings().prettyPrint(false)
    val held = resolveImages(clean, carried, remoteImages)
    val policy = if (remoteImages) REMOTE_POLICY else LOCAL_POLICY
    val css = stylesheet(source, remoteImages)
    val body = clean.body().html()
    // A message that chose its own colours is left alone. See [paintsItself].
    val ownColours = dark && paintsItself(clean, css)
    val invert = if (dark && !ownColours) DARK_CSS else ""
    /*
     * The colour scheme has to follow the inversion, not the window.
     *
     * `color-scheme: dark` tells the engine to use dark defaults, which is white text on a
     * dark canvas. That is right when the whole page is about to be turned inside out, and
     * catastrophic when it is not: a message that set a light background and left its text
     * colour alone got the sender's light background with the engine's white text on it,
     * which is an empty grey box. Shipped in 0.1.114 and found the same evening.
     */
    val scheme = if (ownColours || !dark) "light" else "dark light"
    return EmailPage(
        """<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="$policy">
<meta name="color-scheme" content="$scheme">
<style>$css</style>
<style>$BASE_CSS$invert</style>
</head><body>$body</body></html>""",
        held,
    )
}

/**
 * The message's own stylesheet, carried over by hand.
 *
 * It has to be by hand. jsoup's cleaner keeps a `<style>` element when the safelist allows
 * it and throws away everything inside, because a safelist describes tags and attributes
 * and a stylesheet is neither. Allowing the tag alone therefore produced an empty one, and
 * a responsive message arrived with its media queries and its classes gone, which is most
 * of how a designed email knows what to look like.
 *
 * What is taken out of it:
 *
 * - `@import`, which is a fetch. The policy would refuse it anyway; this is the first line.
 * - `expression(`, `behavior:` and `-moz-binding`, the three ways a stylesheet has
 *   historically been able to run code in one browser or another.
 * - `javascript:` anywhere in it.
 * - `</style`, which is how a stylesheet stops being a stylesheet and becomes markup again.
 * - every `url()` that is not the message's own bytes, when pictures are held back, because
 *   a background picture is the same fetch as an `<img>`.
 */
private fun stylesheet(source: org.jsoup.nodes.Document, remoteImages: Boolean): String {
    var css = source.select("style").joinToString("\n") { it.data() } + bodyRule(source)
    if (css.isBlank()) return ""
    css = DANGEROUS_CSS.replace(css, "")
    if (!remoteImages) css = css.replace(URL_IN_CSS, "none")
    return css
}

/**
 * What the message asked of `<body>` itself, as a rule.
 *
 * The cleaner builds its own document, so the original `body` element and everything on it
 * is gone by the time anything looks: no page background, no font, no margins. That is not
 * a detail. The background is what puts a designed message on a tint with its own white
 * panel floating in the middle of it, and without the font every message came out in the
 * engine's default serif no matter what the sender chose.
 */
private fun bodyRule(source: org.jsoup.nodes.Document): String {
    val body = source.body()
    val declarations = listOfNotNull(
        body.attr("style").trim().trimEnd(';').ifBlank { null },
        body.attr("bgcolor").trim().ifBlank { null }?.let { "background-color:$it" },
    )
    return if (declarations.isEmpty()) "" else "\nbody{${declarations.joinToString(";")}}"
}

private val DANGEROUS_CSS = Regex(
    """@import|expression\s*\(|behavior\s*:|-moz-binding|javascript:|</\s*style""",
    RegexOption.IGNORE_CASE,
)

/**
 * Pictures resolved to something the engine can draw without asking anyone.
 *
 * `cid:` is a part the message already carries, so it becomes the bytes themselves. A web
 * address is a request to the sender's server that says the message was opened, so unless
 * the reader has agreed to it the picture becomes a blank and the address is kept on the
 * element for when they do. Bulwark does the same thing and so does every other client
 * that takes read tracking seriously.
 *
 * Returns the ones held back, with the size the sender declared, so each can be judged.
 */
private fun resolveImages(
    document: Document,
    carried: Map<String, String>,
    remoteImages: Boolean,
): List<RemotePicture> {
    val held = ArrayList<String>()
    val sizes = HashMap<String, Pair<Int?, Int?>>()
    document.select("img[src]").forEach { img ->
        val src = img.attr("src").trim()
        when {
            src.startsWith("cid:", ignoreCase = true) -> {
                val data = carried[src.removePrefix("cid:").removePrefix("CID:").trim().trim('<', '>')]
                if (data != null) img.attr("src", data) else img.remove()
            }
            src.startsWith("data:", ignoreCase = true) -> Unit
            remoteImages -> Unit
            else -> {
                held.add(src)
                // Kept because a picture the sender declared as one pixel across is not a
                // picture, and that is judged from the HTML rather than by fetching it.
                sizes[src] = img.attr("width").toIntOrNull() to img.attr("height").toIntOrNull()
                img.attr("data-blocked-src", src)
                img.attr("src", BLANK)
                // Collapsed rather than left as a gap, or a message built out of sliced
                // pictures arrives as a page of holes with the layout pushed apart.
                img.attr("style", img.attr("style") + ";display:none")
            }
        }
    }
    // A background picture is a fetch too, and the same tracking pixel with extra steps.
    if (!remoteImages) {
        document.select("[background]").forEach { it.removeAttr("background") }
        document.select("[style*=url(]").forEach { element ->
            element.attr("style", element.attr("style").replace(URL_IN_CSS, "none"))
        }
    }
    return picturesIn(held, sizes)
}

private val URL_IN_CSS = Regex("""url\(\s*['"]?(?!data:)[^)]*\)""", RegexOption.IGNORE_CASE)

/** A transparent pixel, so a blocked picture is a blank and never a broken-image icon. */
private const val BLANK =
    "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"

/**
 * What survives the clean.
 *
 * Wider than the list the block renderer used, because an engine can use what that one had
 * to throw away: `style`, `class`, a `<style>` block, the table attributes a mail layout is
 * actually built out of. Narrower where it counts, and the two rules that matter are:
 *
 * - **Nothing that executes.** `script`, `iframe`, `object`, `embed`, `form`, `input`,
 *   `base`, `link`, `meta` and `svg` are not on the list, so the cleaner drops them, and
 *   every attribute not named here goes with them, `onclick` and its forty siblings
 *   included. A safelist cannot be defeated by an event handler it was never asked about.
 * - **No scheme that runs.** `a[href]` and `img[src]` are restricted to the protocols
 *   below, which is what kills `javascript:`.
 *
 * `<style>` is not on the list, and is not dropped either: see [stylesheet] for why it has
 * to be carried over by hand and what is taken out of it on the way.
 */
internal val EMAIL_SAFELIST: Safelist = Safelist.relaxed()
    .addTags("center", "font", "span", "hr", "big", "small", "s", "strike", "wbr")
    .addAttributes(":all", "style", "class", "id", "dir", "title", "align", "valign", "bgcolor", "width", "height")
    .addAttributes("table", "border", "cellpadding", "cellspacing", "background")
    .addAttributes("td", "colspan", "rowspan", "background", "nowrap")
    .addAttributes("th", "colspan", "rowspan", "background", "nowrap")
    .addAttributes("tr", "background")
    .addAttributes("img", "src", "alt", "border")
    .addAttributes("font", "color", "face", "size")
    .addAttributes("a", "href", "target", "rel", "name")
    .addProtocols("img", "src", "http", "https", "cid", "data")
    .addProtocols("a", "href", "http", "https", "mailto", "tel")


/**
 * No network, at all, unless the reader asked for pictures.
 *
 * `default-src 'none'` is the whole of it: no script, no frame, no font, no fetch, no form
 * post, whatever the cleaner may have missed. Styles are inline because the document is
 * built here, and data: images are the message's own parts.
 */
private const val LOCAL_POLICY =
    "default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:; " +
        "base-uri 'none'; form-action 'none'; frame-src 'none'; script-src 'none'"

/** The same, with the picture fetch the reader agreed to. Still no script and no frame. */
private const val REMOTE_POLICY =
    "default-src 'none'; img-src data: http: https:; style-src 'unsafe-inline'; font-src data:; " +
        "base-uri 'none'; form-action 'none'; frame-src 'none'; script-src 'none'"

/**
 * The little that is imposed on the sender's own design.
 *
 * Deliberately almost nothing. The point of handing it to an engine is that the message
 * arrives as it was built, so this only stops it breaking the window: a picture or a table
 * wider than the pane is held to the pane unless the sender set a width of their own, and
 * anything still too wide scrolls sideways rather than stretching everything else.
 */
private const val BASE_CSS = """
html { overflow-x: auto; overflow-y: hidden; }
body { margin: 0; padding: 0; overflow-x: auto; }
img:not([style*="max-width"]) { max-width: 100%; height: auto; }
table:not([style*="max-width"]) { max-width: 100%; }
"""

/**
 * A dark window's version, by inverting.
 *
 * Every pixel is turned inside out and the hues put back, then pictures are turned inside
 * out again so they land where they started. It is the trick webmail uses and it is not
 * colour management: a message with a mid-grey background comes out mid-grey. It beats the
 * alternative, which is a sheet of white in the middle of a dark window.
 *
 * Only where the sender did not do it themselves. [OWN_DARK_MODE] is that test.
 */
private const val DARK_CSS = """
html { filter: invert(1) hue-rotate(180deg); background: #ffffff; }
img, video { filter: invert(1) hue-rotate(180deg); }
"""

/** A message that asked for dark treatment of its own, and so should not be inverted. */
private val OWN_DARK_MODE = Regex("""prefers-color-scheme\s*:\s*dark""", RegexOption.IGNORE_CASE)

/**
 * Whether the message painted its own page, and so must not be inverted.
 *
 * **[DARK_CSS] exists for a message that never chose a colour**, which is the ordinary
 * case: black text on the white a mail client supplies by default, and a sheet of white in
 * a dark window is worse than turning it inside out. A message that set its own background
 * is a different thing entirely. It has a design, and inverting a design produces something
 * nobody made: a hotel's dark brown header came out pink, which is how this was found.
 *
 * Three ways a sender says so, and the third is the one that matters in practice. Almost no
 * marketing email sets a background on `body`, because Outlook ignored it for twenty years;
 * they all wrap the message in a full-width table and paint that instead.
 *
 * White is not a design. A sender who writes `bgcolor="#ffffff"` is being defensive about
 * clients that default to something else, not choosing white over black, so that one still
 * inverts and still turns dark.
 */
private fun paintsItself(document: Document, css: String): Boolean {
    if (OWN_DARK_MODE.containsMatchIn(css + document.html())) return true
    if (colouredBackground(document.body())) return true
    // `body{background:...}` in the message's own stylesheet, which bodyRule() has already
    // copied across by this point but which may also have arrived in a rule of its own.
    if (BODY_BACKGROUND.containsMatchIn(css)) return true
    return document.body().select("table, div, center, td").any { spansTheWidth(it) && colouredBackground(it) }
}

/** A background colour that is not white and not transparent. */
private fun colouredBackground(element: org.jsoup.nodes.Element): Boolean {
    val stated = listOf(
        element.attr("bgcolor"),
        Regex("""background(-color)?\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE)
            .find(element.attr("style"))?.groupValues?.get(2).orEmpty(),
    )
    return stated.any { value ->
        val colour = value.trim().lowercase().removeSuffix(";").trim()
        colour.isNotBlank() && colour !in BLANK_COLOURS
    }
}

/**
 * Wide enough to be the page rather than a box inside it.
 *
 * A quoted block or a callout with a tint is not a design decision about the whole message,
 * and treating one as such would leave an otherwise plain message as a white sheet.
 */
private fun spansTheWidth(element: org.jsoup.nodes.Element): Boolean {
    if (element.tagName().equals("center", ignoreCase = true)) return true
    val width = element.attr("width").trim().ifBlank {
        Regex("""(?<!min-|max-)width\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE)
            .find(element.attr("style"))?.groupValues?.get(1).orEmpty()
    }
    val cleaned = width.trim().lowercase().removeSuffix(";").trim()
    // A pixel width is a fixed-width message body, which is still the whole page. 600 is
    // the width every email template in existence settled on.
    val pixels = cleaned.removeSuffix("px").toIntOrNull()
    return cleaned == "100%" || (pixels != null && pixels >= 500)
}

private val BLANK_COLOURS = setOf(
    "", "#fff", "#ffffff", "white", "transparent", "inherit", "initial", "none", "unset",
    "rgb(255,255,255)", "rgb(255, 255, 255)",
)

private val BODY_BACKGROUND = Regex(
    """(^|[,{}\s])(body|html)\s*\{[^}]*background""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/**
 * Bytes as a `data:` URI, which is how a part the message carries reaches the engine.
 *
 * Embedded rather than served from anywhere. There is no local server to serve it from and
 * a `cid:` means nothing outside a mail client, so the alternative would be inventing a
 * scheme and teaching the engine to answer it.
 */
internal fun dataUri(type: String, bytes: ByteArray): String =
    "data:" + type.substringBefore(';').trim().ifBlank { "application/octet-stream" } +
        ";base64," + java.util.Base64.getEncoder().encodeToString(bytes)
