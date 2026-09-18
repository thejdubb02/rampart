package org.rampart

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * A message body turned into text Compose can draw, and nothing else.
 *
 * There is no browser engine in this process, so there is no script engine either: a message
 * cannot run code, phone home, or read anything. The cost is that a marketing email loses its
 * layout. See docs/architecture.md, "Rendering HTML mail has no free answer on the desktop".
 */
data class Rendered(
    val text: AnnotatedString,
    /** Every picture the body wants to fetch from the web, in the order it names them. */
    val remoteImages: List<String> = emptyList(),
) {
    val blockedImages: Int get() = remoteImages.size
}

/**
 * Tags whose content we keep. Everything else is unwrapped or dropped by jsoup's Cleaner,
 * which also drops every attribute we have not listed, so `onclick` and friends cannot survive.
 * `a[href]` is restricted to ftp/http/https/mailto, which is what kills `javascript:`.
 */
internal val SAFELIST: Safelist = Safelist.basic()
    .addTags("h1", "h2", "h3", "h4", "h5", "h6", "div", "table", "thead", "tbody", "tr", "td", "th", "hr", "center")
    // The picture itself, so it can be drawn where the body puts it rather than in a heap
    // underneath. src is restricted to the three schemes that mean something here: http and
    // https are a fetch the reader has to agree to, cid is a part the message already
    // carries. Anything else, javascript: included, is dropped by the cleaner.
    // width and height are numbers that end up as a size, never anything that is executed.
    .addAttributes("img", "src", "alt", "width", "height")
    // data: is here because our own signatures use it. An embedded picture is the safest
    // kind there is: no request leaves the machine, so it cannot report that mail was read.
    .addProtocols("img", "src", "http", "https", "cid", "data")
    /*
     * What a layout table needs to still be a layout after cleaning.
     *
     * A mail table carries its shape in attributes the cleaner was dropping, so every one
     * arrived as a bare grid of cells with no widths and no colour. `style` is here for the
     * same reason and is not applied as CSS: exactly two properties are read out of it,
     * background-color and width, and everything else in it is ignored. Nothing in a style
     * string can execute, and the tags it can appear on here hold no behaviour.
     */
    .addAttributes("table", "bgcolor", "width", "align", "style", "cellpadding")
    .addAttributes("tr", "bgcolor", "style")
    .addAttributes("td", "bgcolor", "width", "align", "colspan", "style")
    .addAttributes("th", "bgcolor", "width", "align", "colspan", "style")
    /*
     * Alignment and colour, on the tags that carry them outside a table.
     *
     * Same rule as above and worth restating, because widening a safelist is how a sanitiser
     * stops sanitising: `style` is never applied as CSS. A fixed set of properties is read
     * out of it by name, text-align and color here, and every other byte of the string is
     * ignored. None of these tags holds behaviour, so there is nothing for a declaration to
     * reach even if one were read.
     *
     * Without this the attributes were dropped before anything looked at them, and a
     * newsletter that centres its masthead and colours its headings arrived left aligned
     * and monochrome.
     */
    .addAttributes("div", "align", "style")
    .addAttributes("p", "align", "style")
    .addAttributes("span", "style")
    .addAttributes("a", "style")
    .addAttributes("img", "align", "style")
    .addAttributes("center", "style")
    .addAttributes("h1", "align", "style")
    .addAttributes("h2", "align", "style")
    .addAttributes("h3", "align", "style")
    .addAttributes("h4", "align", "style")
    .addAttributes("h5", "align", "style")
    .addAttributes("h6", "align", "style")

/** A block ends the line it is on. The tight ones get one newline, the rest get a blank line. */
private val TIGHT = setOf("div", "li", "tr", "td", "th", "dd", "dt", "center")
private val LOOSE = setOf("p", "blockquote", "table", "ul", "ol", "dl", "pre", "h1", "h2", "h3", "h4", "h5", "h6")

private val STYLES = mapOf(
    "b" to SpanStyle(fontWeight = FontWeight.Bold),
    "strong" to SpanStyle(fontWeight = FontWeight.Bold),
    "th" to SpanStyle(fontWeight = FontWeight.Bold),
    "h1" to SpanStyle(fontWeight = FontWeight.Bold),
    "h2" to SpanStyle(fontWeight = FontWeight.Bold),
    "h3" to SpanStyle(fontWeight = FontWeight.Bold),
    "h4" to SpanStyle(fontWeight = FontWeight.Bold),
    "h5" to SpanStyle(fontWeight = FontWeight.Bold),
    "h6" to SpanStyle(fontWeight = FontWeight.Bold),
    "i" to SpanStyle(fontStyle = FontStyle.Italic),
    "em" to SpanStyle(fontStyle = FontStyle.Italic),
    "cite" to SpanStyle(fontStyle = FontStyle.Italic),
    "code" to SpanStyle(fontFamily = FontFamily.Monospace),
    "pre" to SpanStyle(fontFamily = FontFamily.Monospace),
    "u" to SpanStyle(textDecoration = TextDecoration.Underline),
)

private val LINKS = Regex("""(https?://|mailto:)[^\s<>"'`)\]}]+""")

fun renderHtml(html: String, linkColor: Color, quoteColor: Color, onLink: (String) -> Unit): Rendered {
    val doc = Jsoup.parse(html)
    doc.select("script, style, noscript, head, title").remove()
    // Only remote images are held back. An image the message carries with it is drawn from
    // the message's own parts and tells the sender nothing, so listing it here would put
    // "1 image was not loaded" above an image that is right there.
    //
    // Collected rather than counted, because fetching one is a decision the reader makes
    // per sender and the addresses are what that decision needs.
    val remote = doc.select("img")
        .map { it.attr("abs:src").ifBlank { it.attr("src") }.trim() }
        // A src beginning "//" is still a fetch from the web, and a common one in mail. It
        // means "whatever scheme this page came from", and a message did not come from a
        // scheme, so it is read as https rather than quietly ignored.
        .map { if (it.startsWith("//")) "https:$it" else it }
        .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    val clean = Cleaner(SAFELIST).clean(doc)
    val walker = Walker(linkColor, quoteColor, onLink)
    clean.body().childNodes().forEach { walker.node(it) }
    return Rendered(walker.build(), remote)
}

fun renderText(text: String, linkColor: Color, onLink: (String) -> Unit): Rendered {
    val out = AnnotatedString.Builder()
    var at = 0
    LINKS.findAll(text).forEach { match ->
        out.append(text.substring(at, match.range.first))
        val url = match.value.trimEnd('.', ',', ';', ':')
        out.pushLink(link(url, linkColor, onLink))
        out.append(url)
        out.pop()
        out.append(match.value.removePrefix(url))
        at = match.range.last + 1
    }
    out.append(text.substring(at))
    return Rendered(out.toAnnotatedString())
}

private fun link(url: String, color: Color, onLink: (String) -> Unit, underline: Boolean = true) =
    LinkAnnotation.Clickable(
        tag = url,
        styles = TextLinkStyles(
            SpanStyle(
                color = color,
                textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
            ),
        ),
    ) { onLink(url) }

/**
 * Walks the cleaned tree once, appending as it goes. Blank lines are owed rather than written,
 * so a stack of empty `div`s collapses instead of leaving a page of whitespace.
 */
internal class Walker(
    private val linkColor: Color,
    private val quoteColor: Color,
    private val onLink: (String) -> Unit,
    /**
     * What this text will be drawn on, so a colour out of the mail can be checked before it
     * is used. Unspecified means do not use any, which is what every caller that only wants
     * the characters passes.
     */
    private val background: Color = Color.Unspecified,
    /**
     * Whether a link in here gets an underline.
     *
     * Off inside a button, where the whole shape already says it can be pressed and the
     * sender's own style says `text-decoration: none`. A rule underneath the words of a
     * filled button is the one thing that stops it looking like one.
     */
    private val underlineLinks: Boolean = true,
) {
    private val out = AnnotatedString.Builder()
    private var started = false
    private var owedBreaks = 0
    private var owedSpace = false

    fun build(): AnnotatedString = out.toAnnotatedString()

    fun node(n: Node) {
        when (n) {
            is TextNode -> text(n.wholeText)
            is Element -> element(n)
            else -> {}
        }
    }

    private fun element(e: Element) {
        val tag = e.tagName()
        when (tag) {
            "br" -> { breaks(1); return }
            "hr" -> { breaks(1); write("--------"); breaks(1); return }
        }

        val gap = if (tag in LOOSE) 2 else if (tag in TIGHT) 1 else 0
        if (gap > 0) breaks(gap)
        if (tag == "li") write("- ")

        // A space owed from the previous text belongs outside whatever span opens here,
        // or the underline on a link starts one character early.
        flushSpace()

        var pops = 0
        if (tag == "blockquote") { out.pushStyle(SpanStyle(color = quoteColor)); pops++ }
        // The sender's own colour, but only when it can be read where it is going. A brand
        // heading is part of what the message is, and an unreadable one is worse than none.
        readableColour(e.attr("style"), background)?.let { out.pushStyle(SpanStyle(color = it)); pops++ }
        STYLES[tag]?.let { out.pushStyle(it); pops++ }
        if (tag == "a") {
            val href = e.attr("abs:href").ifBlank { e.attr("href") }.trim()
            if (href.isNotBlank()) { out.pushLink(link(href, linkColor, onLink, underlineLinks)); pops++ }
        }

        e.childNodes().forEach { node(it) }

        repeat(pops) { out.pop() }
        if (gap > 0) breaks(gap)
    }

    private fun text(raw: String) {
        // A BOM is not whitespace, so Apple Mail's stray one survives collapsing and
        // leaves a blank line in the middle of a reply.
        val collapsed = raw.replace('\u00a0', ' ').replace("\ufeff", "").replace(Regex("\\s+"), " ")
        if (collapsed.isEmpty()) return
        if (collapsed.isBlank()) { owedSpace = started && owedBreaks == 0; return }
        if (collapsed.startsWith(" ")) owedSpace = true
        write(collapsed.trim())
        if (collapsed.endsWith(" ")) owedSpace = true
    }

    private fun flushSpace() {
        if (owedSpace && owedBreaks == 0 && started) {
            out.append(' ')
            owedSpace = false
        }
    }

    private fun breaks(n: Int) {
        if (started && owedBreaks < n) owedBreaks = n
        owedSpace = false
    }

    private fun write(s: String) {
        if (s.isEmpty()) return
        repeat(owedBreaks) { out.append('\n') }
        if (owedSpace && owedBreaks == 0 && started) out.append(' ')
        owedBreaks = 0
        owedSpace = false
        started = true
        out.append(s)
    }
}

/**
 * A colour out of a style string, when it can be read against [background].
 *
 * **Colour in mail is not advice, it is a guess about somebody else's screen.** A newsletter
 * written for a white page sets its body text to near black, and drawn on a dark theme that
 * is a paragraph nobody can see. The rule here was to ignore colour entirely for exactly
 * that reason, and ignoring it loses the half that carries meaning: a brand heading, a
 * warning in red, a total in green.
 *
 * So the test is contrast rather than trust, and **the bar is invisibility, not
 * accessibility**. It is not Rampart's place to overrule a designer on whether their own
 * green is dark enough; every other mail client shows it and so should this one. What
 * Rampart will not do is draw text that cannot be seen at all.
 *
 * One threshold does both jobs, which is why there is one. A brand colour picked to sit on
 * white clears it on a light theme and is used. The same colour on a dark theme does not,
 * because a near-black body colour against a near-black background is exactly the case
 * this exists to catch, and the theme's own ink takes over. Checked against a real
 * newsletter: its #2fd2a8 heading reads 1.75 against white and 12.6 against our dark
 * surface, and its #333 body text reads 12.6 against white and 1.2 against the dark one.
 *
 * Null when there is no colour, when it cannot be parsed, or when [background] is
 * unspecified, which is how a caller says it only wants the characters.
 */
internal fun readableColour(style: String, background: Color): Color? {
    if (background == Color.Unspecified || style.isBlank()) return null
    val raw = COLOUR.find(style)?.groupValues?.get(1)?.trim() ?: return null
    val colour = parseCssColour(raw) ?: return null
    return colour.takeIf { contrast(it, background) >= VISIBLE }
}

/** The WCAG contrast ratio between two opaque colours, 1.0 (identical) to 21.0 (black on white). */
internal fun contrast(a: Color, b: Color): Double {
    val first = relativeLuminance(a)
    val second = relativeLuminance(b)
    val lighter = maxOf(first, second)
    val darker = minOf(first, second)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(colour: Color): Double {
    fun channel(value: Float): Double {
        val v = value.toDouble()
        // The sRGB transfer curve. A plain average reads mid grey as far lighter than an eye
        // does, and then every mid tone passes a contrast check it should fail.
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(colour.red) + 0.7152 * channel(colour.green) + 0.0722 * channel(colour.blue)
}

/** `#abc`, `#aabbcc` or `rgb(1, 2, 3)`, which is all mail uses. */
internal fun parseCssColour(raw: String): Color? {
    val text = raw.trim().removeSuffix(";").trim()
    if (text.startsWith("#")) {
        val hex = text.drop(1)
        val full = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            8 -> hex.take(6)
            else -> return null
        }
        return full.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
    }
    val numbers = RGB.find(text)?.groupValues?.drop(1)?.mapNotNull { it.toIntOrNull() } ?: return null
    if (numbers.size < 3) return null
    return Color(numbers[0].coerceIn(0, 255), numbers[1].coerceIn(0, 255), numbers[2].coerceIn(0, 255))
}

/**
 * Where a colour stops being readable at all.
 *
 * Well under the WCAG 3:1 for large text, deliberately. The job here is not to grade
 * somebody else's design, it is to stop a message being drawn in a colour indistinguishable
 * from what is behind it.
 */
private const val VISIBLE = 1.6

private val COLOUR = Regex("(?i)(?:^|;)\\s*color\\s*:\\s*([^;]+)")
private val RGB = Regex("(?i)rgba?\\(\\s*(\\d+)\\D+(\\d+)\\D+(\\d+)")
