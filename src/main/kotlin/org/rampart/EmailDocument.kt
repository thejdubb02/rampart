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
    /**
     * Draw an undesigned message dark. A designed one is never touched. See [PLAIN_DARK].
     */
    dark: Boolean = false,
): EmailPage {
    val source = Jsoup.parse(withoutTofu(html)).also(::scrubTofu)
    val clean = Cleaner(EMAIL_SAFELIST).clean(source)
    clean.outputSettings().prettyPrint(false)
    val held = resolveImages(clean, carried, remoteImages)
    val policy = if (remoteImages) REMOTE_POLICY else LOCAL_POLICY
    val css = stylesheet(source, remoteImages)
    val body = clean.body().html()
    /*
     * A message that brought a design of its own is left alone, in either window.
     *
     * The rest are given one, and which one depends on the window. This is not the old
     * inversion and must not become it: nothing is filtered, no pixel is turned inside
     * out, and a message that chose a colour still gets the colour it chose. All that
     * changes is the page a message with no opinion is drawn on.
     */
    val designed = paintsItself(clean, css)
    /*
     * Both pages are written, and an attribute picks one.
     *
     * The light and dark versions used to be two different documents, so putting a message
     * back on paper meant building it again and handing the engine a new page to load. That
     * is a second load of the whole message, pictures and all, for a change of two colours,
     * and it is what "clicking the lightbulb makes it load again" was.
     *
     * Now the dark rules ride along scoped to `html[data-dark]`, and the switch is one
     * attribute on an already-loaded page. Nothing is fetched, nothing is laid out from
     * scratch, and the message does not flicker back to the top.
     */
    val plain = if (designed) DESIGNED_CSS else PLAIN_LIGHT + PLAIN_DARK
    return EmailPage(
        """<!DOCTYPE html>
<html${if (dark) " data-dark" else ""}${if (designed) "" else " data-plain"}><head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="$policy">
<meta name="color-scheme" content="light">
<style>$css</style>
<style>$BASE_CSS$plain</style>
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
/**
 * The Content-IDs the body actually shows, which is not the same as the ones it carries.
 *
 * **Outlook gives every part a Content-ID, including the ones it attaches.** Two screenshots
 * a sender dragged into a message arrive with Content-IDs and a disposition of `attachment`,
 * and the body never refers to either. Rampart took "has a Content-ID" to mean "the body
 * draws it", so it left both out of the attachment list as already on screen, and the body
 * never drew them: they were in the message, downloaded, and nowhere to be seen or saved.
 *
 * Spelled by [cidKey], the way every other comparison here spells it, so the two lists
 * cannot disagree about what a part is called.
 */
internal fun citedCids(html: String?): Set<String> {
    if (html.isNullOrBlank()) return emptySet()
    return Jsoup.parse(html).select("img[src]").mapNotNullTo(HashSet()) { cidOf(it.attr("src")) }
}

/**
 * The Content-ID a `src` names, or null if it names something else.
 *
 * One function because two of them was the fault: the attachment list and the picture
 * resolver each decided for themselves what counted as a reference, and a part either of
 * them read differently belonged to neither list and went missing. Now they cannot
 * disagree.
 */
private fun cidOf(src: String): String? {
    val trimmed = src.trim()
    if (!trimmed.startsWith("cid:", ignoreCase = true)) return null
    return trimmed.drop(4).let(::cidKey)
}

/**
 * One Content-ID, spelled the one way everything here compares them.
 *
 * **Lower case, because a `cid:` URL is case insensitive and a Content-ID header is not
 * reliably either.** RFC 2392 says so, and Outlook does not care: a message can carry
 * `Content-ID: <image001.jpg@01DD4818.FA40AB20>` and refer to it as `cid:Image001.jpg@...`
 * in the body. Compared as written, the part is not found, [resolveImages] leaves an empty
 * gap where the picture was, and the attachment list has already decided it is drawn in
 * the body, so the picture is in neither place. That is a picture the reader will never
 * see and no message anywhere saying why.
 */
internal fun cidKey(raw: String?): String? =
    raw?.trim()?.trim('<', '>')?.trim()?.lowercase()?.ifBlank { null }

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
                val data = cidOf(src)?.let { carried[it] }
                if (data != null) img.attr("src", data) else holdTheSpace(img)
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

/**
 * A carried picture that is not here yet, kept at the size the sender gave it.
 *
 * Deleting the tag pulled the layout up, and putting the picture in afterwards pushed
 * it back down, which is the jump. An empty image of the same width and height holds
 * the gap. The inline size is what actually holds it: the page's own rule sets
 * `height: auto` on an image that does not name a max-width, and that collapses a
 * one-pixel stand-in to a line no matter what the width and height attributes say.
 */
private fun holdTheSpace(img: org.jsoup.nodes.Element) {
    val width = img.attr("width").trim()
    val height = img.attr("height").trim()
    img.attr("src", BLANK)
    if (width.isEmpty() && height.isEmpty()) return
    val sizing = buildString {
        append("max-width:100%")
        if (width.isNotEmpty()) append(";width:").append(cssSize(width))
        if (height.isNotEmpty()) append(";height:").append(cssSize(height))
    }
    val had = img.attr("style").trim().trimEnd(';')
    img.attr("style", if (had.isEmpty()) sizing else "$had;$sizing")
}

/** A bare number is pixels. Anything with a unit is already a length. */
private fun cssSize(raw: String): String =
    if (raw.any { it.isLetter() || it == '%' }) raw else raw + "px"

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
/*
 * `file:` is in `img-src` because a big picture the message carried is written beside the
 * document rather than embedded in it: a `data:` URI has a size ceiling the engine does not
 * document and does not report, and going past it draws a blank white rectangle where the
 * picture was. See `unpack` in WebBody.
 *
 * It is not the hole it looks like. A message can ask for any local file as a picture, and
 * with `script-src 'none'` there is nothing on the page that can find out whether it
 * loaded, nothing to read a pixel of it, and nowhere to send it: `default-src 'none'`
 * leaves no fetch, no frame and no form.
 */
private const val LOCAL_POLICY =
    "default-src 'none'; img-src data: file:; style-src 'unsafe-inline'; font-src data:; " +
        "base-uri 'none'; form-action 'none'; frame-src 'none'; script-src 'none'"

/** The same, with the picture fetch the reader agreed to. Still no script and no frame. */
private const val REMOTE_POLICY =
    "default-src 'none'; img-src data: file: http: https:; style-src 'unsafe-inline'; " +
        "font-src data:; base-uri 'none'; form-action 'none'; frame-src 'none'; script-src 'none'"

/**
 * The little that is imposed on the sender's own design.
 *
 * Deliberately almost nothing. The point of handing it to an engine is that the message
 * arrives as it was built, so this only stops it breaking the window: a picture or a table
 * wider than the pane is held to the pane unless the sender set a width of their own, and
 * anything still too wide scrolls sideways rather than stretching everything else.
 */
private const val BASE_CSS = """
/* A message is normally exactly as tall as the panel it was given, so there is nothing
   to scroll. One taller than the panel will fit scrolls itself, which is what auto is
   for: hidden clipped it instead, and the rest of the message was simply gone. */
html { overflow-x: auto; overflow-y: auto; }
body { margin: 0; padding: 0; overflow-x: auto; }
img:not([style*="max-width"]) { max-width: 100%; height: auto; }
table:not([style*="max-width"]) { max-width: 100%; }
"""

/**
 * A message that painted its own page, which gets nothing but a canvas to sit on.
 *
 * The white matters even though the sender is about to paint over most of it: the panel
 * behind the engine is transparent, so without it the dark window shows through the gaps
 * between a newsletter's own tables.
 */
private const val DESIGNED_CSS = """
html { background: #ffffff; }
"""

/**
 * What a message that brought no design of its own gets, which is most mail.
 *
 * A reply typed in Gmail is a bare `<div dir="ltr">`: no font, no colour, no width, no
 * margin. Handed to an engine as-is it comes out in the engine's default, which is Times
 * at the very edge of the pane with no wrapping, and that is what webmail's own stylesheet
 * exists to prevent. This is that stylesheet, and it is deliberately four declarations:
 * anything more starts overriding senders who did choose.
 *
 * On `html` rather than `body` so the sender wins every disagreement. A rule the sender
 * wrote targets `body` or something inside it, which is more specific, and an inline style
 * beats both. This only ever fills in what nobody stated.
 *
 * It is applied only where [paintsItself] says the message has no design, so a newsletter
 * keeps its own full-bleed background and gets no padding it did not ask for.
 */
private const val PLAIN_CSS = """
/* Room to breathe, because the pane's own edge is not a margin. */
body { padding: 4px 22px 20px; }
/* A pasted link or a forwarded tracking URL is one unbreakable word several thousand
   pixels long. Without this it sets the width of the page and every paragraph above it
   is laid out to that width and then clipped by the pane. */
body { overflow-wrap: break-word; word-break: break-word; }
html { font: 15px/1.55 -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
"""

/** The light version, which is what a message gets in a light window. */
private const val PLAIN_LIGHT = PLAIN_CSS + """
html { background: #ffffff; color: #1a1a1a; }
"""

/**
 * The dark version, and the thing that replaced inverting the page.
 *
 * **Inverting was wrong and is not coming back.** It turned every pixel inside out, which
 * made an unpainted reply white text on a black slab and made a hotel's dark brown header
 * come out pink. This does none of that. It is the same stylesheet with two colours
 * swapped, applied only to a message that stated no colours of its own, so nothing the
 * sender chose is altered and nothing designed is touched at all.
 *
 * The one hazard is a message that painted no background but did state a text colour, and
 * in practice that means Outlook, which writes `color:black` on nearly every span it
 * produces. Black text on a dark page is an empty message, so the handful of ways of
 * writing "black" are given the page's colour back. It is a substring match on the inline
 * style and it will miss something eventually, which is what the reader's own switch is
 * for: one click puts the message back the way the sender built it.
 */
private const val PLAIN_DARK = """
html[data-dark] { background: var(--rampart-paper, #16181d); color: var(--rampart-ink, #e6e6e6); }
html[data-dark] a, html[data-dark] a * { color: #8ab4f8; }
html[data-dark] [style*="color:black"], html[data-dark] [style*="color: black"],
html[data-dark] [style*="color:#000"], html[data-dark] [style*="color: #000"],
html[data-dark] [style*="color:#111"], html[data-dark] [style*="color:#222"],
html[data-dark] [style*="color:#333"],
html[data-dark] [style*="color:rgb(0,0,0)"], html[data-dark] [style*="color:rgb(0, 0, 0)"],
html[data-dark] font[color="black"], html[data-dark] font[color="#000000"] {
  color: var(--rampart-ink, #e6e6e6) !important;
}
/* A quoted reply's rule is drawn for a white page and disappears on a dark one. */
html[data-dark] blockquote { border-color: #4a4f57 !important; }
"""

/**
 * A message that asked for dark treatment of its own.
 *
 * Kept from when messages were inverted for a dark window, because it is still the
 * cheapest signal that a sender has a design: a `prefers-color-scheme` block is a thing
 * only somebody who thought about colour writes.
 */
private val OWN_DARK_MODE = Regex("""prefers-color-scheme\s*:\s*dark""", RegexOption.IGNORE_CASE)

/**
 * Whether the message painted its own page, and so should be left entirely alone.
 *
 * **[PLAIN_CSS] exists for a message that never chose anything**, which is most mail: a
 * reply typed in a webmail box, with no font, no colour and no width in it. Those need a
 * stylesheet or they arrive in the engine's Times at the edge of the pane. A message that
 * set its own background is a different thing entirely. It has a design, and a design
 * needs nothing from us: the padding we would add puts a white frame around a full-bleed
 * header, and the font we would set is not the one it chose.
 *
 * Three ways a sender says so, and the third is the one that matters in practice. Almost no
 * marketing email sets a background on `body`, because Outlook ignored it for twenty years;
 * they all wrap the message in a full-width table and paint that instead.
 *
 * White is not a design. A sender who writes `bgcolor="#ffffff"` is being defensive about
 * clients that default to something else, not choosing white over anything, so that one is
 * still treated as plain and still gets the stylesheet.
 */
private fun paintsItself(document: Document, css: String): Boolean {
    if (OWN_DARK_MODE.containsMatchIn(css + document.html())) return true
    if (colouredBackground(document.body())) return true
    // `body{background:...}` in the message's own stylesheet, which bodyRule() has already
    // copied across by this point but which may also have arrived in a rule of its own.
    if (BODY_BACKGROUND.containsMatchIn(css)) return true
    /*
     * And it has to be most of the message, not a corner of it.
     *
     * Width alone was not enough. A quoted signature at the bottom of a forwarded thread
     * puts a yellow highlight in a six-hundred-pixel table cell, which is wide enough to
     * pass and is a highlight rather than a page. The whole message was then treated as
     * designed and drawn on white in a dark window, where a webmail client shows it dark
     * like every other reply. Holding at least half the text is what tells the wrapper
     * that carries a newsletter apart from a cell that carries three words.
     */
    val whole = document.body().text().length
    return document.body().select("table, div, center, td").any {
        spansTheWidth(it) && colouredBackground(it) && it.text().length * 2 >= whole
    }
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
