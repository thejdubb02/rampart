package org.rampart

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

/**
 * Formatting in the composer, without building a rich text editor.
 *
 * The buffer holds plain text with a few markers in it, the same ones people already type
 * in chat: `**bold**`, `*italic*`, `[label](url)`, `- ` and `1. `. On send they become real
 * HTML, and the markers are stripped for the plain text part.
 *
 * Why this rather than a WYSIWYG field: Compose has no rich text editor, so one means
 * tracking styled ranges across every edit and remapping every offset, which is a large
 * thing to build and a much harder one to be sure of. Getting it subtly wrong corrupts mail
 * somebody wrote. Here the worst case is that a marker is visible, and what is sent is
 * always exactly what the text says.
 *
 * Nobody has to know any of that: the toolbar and Ctrl+B insert the markers, and
 * [MarkupStyling] draws the marked words as bold and italic while you type.
 */

/** Where a marker run was found, and what it was. */
private data class Span(val open: IntRange, val body: IntRange, val close: IntRange, val kind: Kind)

private enum class Kind { BOLD, ITALIC, LINK, IMAGE }

/** A URL we are willing to put in an attribute. Anything else stays literal text. */
private val SAFE = listOf("http://", "https://", "mailto:", "cid:", "data:image/")

internal fun safeUrl(url: String): Boolean = SAFE.any { url.startsWith(it, ignoreCase = true) }

private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Only ever applied to a URL that already passed [safeUrl]. */
private fun escapeAttr(s: String) = escape(s).replace("\"", "&quot;")

/**
 * Finds the markers on one line, left to right, never overlapping.
 *
 * `**` is tried before `*` so bold is never read as two italics, and an unclosed marker is
 * not a span at all, which is what makes a stray asterisk in ordinary prose harmless.
 */
private fun spans(line: String): List<Span> {
    val found = mutableListOf<Span>()
    var i = 0
    while (i < line.length) {
        val rest = line.substring(i)
        val span = when {
            rest.startsWith("![") -> media(line, i, image = true)
            rest.startsWith("[") -> media(line, i, image = false)
            rest.startsWith("**") -> paired(line, i, "**", Kind.BOLD)
            rest.startsWith("*") -> paired(line, i, "*", Kind.ITALIC)
            else -> null
        }
        if (span == null) {
            i++
        } else {
            found += span
            i = span.close.last + 1
        }
    }
    return found
}

private fun paired(line: String, at: Int, marker: String, kind: Kind): Span? {
    val from = at + marker.length
    // An empty pair like ** ** is not emphasis, so the body has to be at least one character.
    val close = line.indexOf(marker, from).takeIf { it > from } ?: return null
    return Span(at until from, from until close, close until close + marker.length, kind)
}

/** `[label](url)` and `![alt](url)`, which share everything but the leading bang. */
private fun media(line: String, at: Int, image: Boolean): Span? {
    val open = at + if (image) 2 else 1
    val closeBracket = line.indexOf(']', open).takeIf { it >= open } ?: return null
    if (line.getOrNull(closeBracket + 1) != '(') return null
    val closeParen = line.indexOf(')', closeBracket + 2).takeIf { it > closeBracket + 1 } ?: return null
    val url = line.substring(closeBracket + 2, closeParen)
    if (!safeUrl(url)) return null
    return Span(
        at until open,
        open until closeBracket,
        closeBracket until closeParen + 1,
        if (image) Kind.IMAGE else Kind.LINK,
    )
}

/** The url inside a link or image span, taken back out of the closing `](...)`. */
private fun urlOf(line: String, span: Span): String =
    line.substring(span.close.first + 2, span.close.last)

/** One line with its markers turned into tags, everything else escaped. */
private fun inlineHtml(line: String): String {
    val out = StringBuilder()
    var at = 0
    for (span in spans(line)) {
        out.append(escape(line.substring(at, span.open.first)))
        val body = escape(line.substring(span.body.first, span.body.last + 1))
        when (span.kind) {
            Kind.BOLD -> out.append("<b>").append(body).append("</b>")
            Kind.ITALIC -> out.append("<i>").append(body).append("</i>")
            Kind.LINK -> out.append("<a href=\"").append(escapeAttr(urlOf(line, span))).append("\">")
                .append(body).append("</a>")
            Kind.IMAGE -> out.append("<img src=\"").append(escapeAttr(urlOf(line, span)))
                .append("\" alt=\"").append(body).append("\" style=\"max-width:100%\">")
        }
        at = span.close.last + 1
    }
    out.append(escape(line.substring(at)))
    return out.toString()
}

private val BULLET = Regex("^- (.*)$")
private val NUMBER = Regex("^\\d+\\. (.*)$")

/**
 * The markup as HTML, for the `text/html` part.
 *
 * A div per line for ordinary text, which is what every other client produces and what
 * every client renders the same way. Consecutive list lines collapse into one list, because
 * a `<ul>` per bullet is legal and looks wrong everywhere.
 */
internal fun markupToHtml(text: String): String {
    val lines = text.trimEnd().ifBlank { return "" }.lines()
    val out = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val rule = if (BULLET.matches(lines[i])) BULLET else if (NUMBER.matches(lines[i])) NUMBER else null
        if (rule == null) {
            out.append(if (lines[i].isBlank()) "<div><br></div>" else "<div>" + inlineHtml(lines[i]) + "</div>")
            i++
            continue
        }
        val tag = if (rule === BULLET) "ul" else "ol"
        out.append("<").append(tag).append(">")
        while (i < lines.size && rule.matches(lines[i])) {
            out.append("<li>").append(inlineHtml(rule.find(lines[i])!!.groupValues[1])).append("</li>")
            i++
        }
        out.append("</").append(tag).append(">")
    }
    return out.toString()
}

/**
 * The markup with its markers taken off, for the `text/plain` part.
 *
 * List dashes and numbers stay, because they read correctly as text and taking them out
 * would lose the structure entirely. A link keeps its address in brackets after the label,
 * which is the convention every mail client that does this already uses.
 */
internal fun markupToPlain(text: String): String = text.lines().joinToString("\n") { line ->
    val out = StringBuilder()
    var at = 0
    for (span in spans(line)) {
        out.append(line.substring(at, span.open.first))
        val body = line.substring(span.body.first, span.body.last + 1)
        when (span.kind) {
            Kind.BOLD, Kind.ITALIC -> out.append(body)
            Kind.LINK -> out.append(body).append(" (").append(urlOf(line, span)).append(")")
            Kind.IMAGE -> out.append(body)
        }
        at = span.close.last + 1
    }
    out.append(line.substring(at))
    out.toString()
}

/**
 * Draws the marked words as they will be sent, while leaving the text exactly as typed.
 *
 * The transformation returns the same characters in the same order, so the offset mapping
 * is the identity and there is no caret arithmetic to get wrong. The markers stay on screen
 * and are dimmed rather than hidden, which is the honest version: what you see is what the
 * buffer holds, and nothing can get out of step with it.
 */
internal object MarkupStyling : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(styleMarkup(text.text), OffsetMapping.Identity)
}

private val FAINT = SpanStyle(color = Color(0x66808080))

internal fun styleMarkup(text: String): AnnotatedString = buildAnnotatedString(text)

private fun buildAnnotatedString(text: String): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    var offset = 0
    for (line in text.lines()) {
        for (span in spans(line)) {
            val style = when (span.kind) {
                Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                Kind.LINK, Kind.IMAGE -> SpanStyle(textDecoration = TextDecoration.Underline)
            }
            builder.addStyle(style, offset + span.body.first, offset + span.body.last + 1)
            builder.addStyle(FAINT, offset + span.open.first, offset + span.open.last + 1)
            builder.addStyle(FAINT, offset + span.close.first, offset + span.close.last + 1)
        }
        offset += line.length + 1
    }
    return builder.toAnnotatedString()
}

/**
 * Puts a prefix on every line the selection touches, or takes it off when they all have it.
 *
 * Touched rather than fully covered: pressing Bullets with the caret sitting in a line and
 * nothing selected should make that line a bullet, which is what every editor does.
 */
internal fun prefixLines(value: TextFieldValue, prefix: String): TextFieldValue {
    val text = value.text
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, text.length)
    val from = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0 || start == 0) 0 else it + 1 }
    val toNewline = text.indexOf('\n', end)
    val to = if (toNewline < 0) text.length else toNewline

    val lines = text.substring(from, to).split("\n")
    // A numbered prefix is renumbered rather than repeated, so three lines are 1. 2. 3.
    val numbered = prefix.first().isDigit()
    val already = lines.all { if (numbered) NUMBER.matches(it) else it.startsWith(prefix) }
    val changed = lines.mapIndexed { index, line ->
        when {
            already && numbered -> NUMBER.find(line)!!.groupValues[1]
            already -> line.removePrefix(prefix)
            numbered -> "${index + 1}. $line"
            else -> prefix + line
        }
    }.joinToString("\n")

    val next = text.substring(0, from) + changed + text.substring(to)
    return value.copy(text = next, selection = TextRange(from + changed.length))
}
