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
private val SAFELIST: Safelist = Safelist.basic()
    .addTags("h1", "h2", "h3", "h4", "h5", "h6", "div", "table", "thead", "tbody", "tr", "td", "th", "hr", "center")

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

private fun link(url: String, color: Color, onLink: (String) -> Unit) = LinkAnnotation.Clickable(
    tag = url,
    styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
) { onLink(url) }

/**
 * Walks the cleaned tree once, appending as it goes. Blank lines are owed rather than written,
 * so a stack of empty `div`s collapses instead of leaving a page of whitespace.
 */
private class Walker(
    private val linkColor: Color,
    private val quoteColor: Color,
    private val onLink: (String) -> Unit,
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
        STYLES[tag]?.let { out.pushStyle(it); pops++ }
        if (tag == "a") {
            val href = e.attr("abs:href").ifBlank { e.attr("href") }.trim()
            if (href.isNotBlank()) { out.pushLink(link(href, linkColor, onLink)); pops++ }
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
