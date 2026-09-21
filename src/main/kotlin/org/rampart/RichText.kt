package org.rampart

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * Formatting in the composer, without building a rich text editor.
 *
 * The buffer holds plain text with a few markers in it, most of them the same ones people
 * already type in chat: `**bold**`, `*italic*`, `~~strikethrough~~`, `` `code` ``,
 * `[label](url)`, `- ` and `1. `. Underline (`__like this__`), headings (`# `, `## `), a
 * quote (`> `), a fenced code block (three backticks on their own line) and left, centre and
 * right alignment have no standard Markdown spelling, so each picks a marker that cannot be
 * confused with any of the others; the reasoning for each is on its own regex or branch
 * below. On send they become real HTML, and for the plain text part each marker is either
 * stripped, kept, or replaced, whichever reads correctly in a client with no formatting at
 * all.
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

private enum class Kind { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, CODE, LINK, IMAGE }

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
            rest.startsWith("~~") -> paired(line, i, "~~", Kind.STRIKETHROUGH)
            // Double underscore for underline. Markdown has no underline of its own to
            // collide with, and this is the same spelling Discord and Slack already use for
            // it, so it reads as "underline" on sight instead of as a new symbol nobody
            // taught themselves. A lone `_` is left alone rather than made emphasis too,
            // because a variable name like `max_retries` typed into a message must not turn
            // into styled text.
            rest.startsWith("__") -> paired(line, i, "__", Kind.UNDERLINE)
            rest.startsWith("`") -> paired(line, i, "`", Kind.CODE)
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
            Kind.UNDERLINE -> out.append("<u>").append(body).append("</u>")
            Kind.STRIKETHROUGH -> out.append("<s>").append(body).append("</s>")
            Kind.CODE -> out.append("<code>").append(body).append("</code>")
            Kind.LINK -> out.append("<a href=\"").append(escapeAttr(urlOf(line, span))).append("\">")
                .append(body).append("</a>")
            /*
             * The label goes through [escapeAttr] here and not [escape], because unlike
             * every other branch it lands inside an attribute rather than between tags.
             * `escape` leaves a double quote alone, which is harmless in element content
             * and is a way out of an attribute: `![a" onerror="...](url)` closed alt and
             * wrote its own. The src beside it was always escaped this way; the alt was not.
             */
            Kind.IMAGE -> out.append("<img src=\"").append(escapeAttr(urlOf(line, span)))
                .append("\" alt=\"").append(escapeAttr(line.substring(span.body.first, span.body.last + 1)))
                .append("\" style=\"max-width:100%\">")
        }
        at = span.close.last + 1
    }
    out.append(escape(line.substring(at)))
    return out.toString()
}

private val BULLET = Regex("^- (.*)$")
private val NUMBER = Regex("^\\d+\\. (.*)$")
private val H1 = Regex("^# (.*)$")
private val H2 = Regex("^## (.*)$")
private val QUOTE = Regex("^> (.*)$")

/*
 * `::center::` and `::right::` rather than anything built from characters Markdown already
 * gives meaning to. Align left is not a marker at all: it is the default, and a button that
 * "wrote" the default would need its own removal case anyway, so Align left already is that
 * removal case, for whichever of the other two was there. Two colons rather than one, because
 * a single colon is ordinary prose ("note: this") and a marker that can appear by accident in
 * a sentence somebody typed is not a marker, it is a landmine.
 */
private val ALIGN_CENTER = Regex("^::center:: (.*)$")
private val ALIGN_RIGHT = Regex("^::right:: (.*)$")

/**
 * A fenced code block delimiter: three backticks on their own line, optionally followed by a
 * language name that is metadata and never rendered. A prefix check rather than a full-match
 * regex, because the language name is thrown away either way and there is nothing to capture.
 */
private fun isFence(line: String): Boolean = line.startsWith("```")

/**
 * The markup as HTML, for the `text/html` part.
 *
 * A div per line for ordinary text, which is what every other client produces and what
 * every client renders the same way. Consecutive list lines collapse into one list, and
 * consecutive quoted lines collapse into one blockquote, because a `<ul>` per bullet or a
 * `<blockquote>` per line is legal HTML and looks wrong everywhere it is opened.
 */
internal fun markupToHtml(text: String): String {
    val lines = text.trimEnd().ifBlank { return "" }.lines()
    val out = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        if (isFence(line)) {
            i++
            val body = mutableListOf<String>()
            while (i < lines.size && !isFence(lines[i])) {
                body += lines[i]
                i++
            }
            if (i < lines.size) i++ // the closing fence, when the text actually had one
            out.append("<pre><code>").append(escape(body.joinToString("\n"))).append("</code></pre>")
            continue
        }

        val h2 = H2.find(line)
        if (h2 != null) {
            out.append("<h2>").append(inlineHtml(h2.groupValues[1])).append("</h2>")
            i++
            continue
        }
        val h1 = H1.find(line)
        if (h1 != null) {
            out.append("<h1>").append(inlineHtml(h1.groupValues[1])).append("</h1>")
            i++
            continue
        }

        if (QUOTE.matches(line)) {
            out.append("<blockquote>")
            while (i < lines.size && QUOTE.matches(lines[i])) {
                val body = QUOTE.find(lines[i])!!.groupValues[1]
                out.append(if (body.isBlank()) "<div><br></div>" else "<div>" + inlineHtml(body) + "</div>")
                i++
            }
            out.append("</blockquote>")
            continue
        }

        val listRule = if (BULLET.matches(line)) BULLET else if (NUMBER.matches(line)) NUMBER else null
        if (listRule != null) {
            val tag = if (listRule === BULLET) "ul" else "ol"
            out.append("<").append(tag).append(">")
            while (i < lines.size && listRule.matches(lines[i])) {
                out.append("<li>").append(inlineHtml(listRule.find(lines[i])!!.groupValues[1])).append("</li>")
                i++
            }
            out.append("</").append(tag).append(">")
            continue
        }

        val center = ALIGN_CENTER.find(line)
        if (center != null) {
            out.append("<div style=\"text-align:center\">").append(inlineHtml(center.groupValues[1])).append("</div>")
            i++
            continue
        }
        val right = ALIGN_RIGHT.find(line)
        if (right != null) {
            out.append("<div style=\"text-align:right\">").append(inlineHtml(right.groupValues[1])).append("</div>")
            i++
            continue
        }

        out.append(if (line.isBlank()) "<div><br></div>" else "<div>" + inlineHtml(line) + "</div>")
        i++
    }
    return out.toString()
}

/**
 * The markup with its markers taken off, for the `text/plain` part.
 *
 * List dashes and numbers stay, because they read correctly as text and taking them out
 * would lose the structure entirely. A quote's `> ` stays for the same reason and needs no
 * code of its own here: it is not a character [spans] treats specially, so it survives
 * untouched by falling through to the general case below. A link keeps its address in
 * brackets after the label, which is the convention every mail client that does this already
 * uses. Headings and alignment have no plain-text spelling, so the marker goes and the text
 * stays; a fenced code block, like inline code, keeps its backticks, because they are the
 * only way a plain text reader can tell a snippet from the sentence around it.
 */
internal fun markupToPlain(text: String): String {
    val out = StringBuilder()
    var inFence = false
    text.lines().forEachIndexed { index, rawLine ->
        if (index > 0) out.append("\n")

        if (isFence(rawLine)) {
            inFence = !inFence
            out.append(rawLine)
            return@forEachIndexed
        }
        if (inFence) {
            // Never read as markdown in either direction: a line inside a fence that happens
            // to contain "**" is a real double asterisk, not a request to be bold, so it is
            // copied through untouched rather than run past spans().
            out.append(rawLine)
            return@forEachIndexed
        }

        val h2 = H2.find(rawLine)
        val h1 = if (h2 == null) H1.find(rawLine) else null
        val center = if (h1 == null) ALIGN_CENTER.find(rawLine) else null
        val right = if (center == null) ALIGN_RIGHT.find(rawLine) else null
        val line = h2?.groupValues?.get(1) ?: h1?.groupValues?.get(1)
            ?: center?.groupValues?.get(1) ?: right?.groupValues?.get(1) ?: rawLine

        var at = 0
        for (span in spans(line)) {
            out.append(line.substring(at, span.open.first))
            val body = line.substring(span.body.first, span.body.last + 1)
            when (span.kind) {
                Kind.BOLD, Kind.ITALIC, Kind.UNDERLINE, Kind.STRIKETHROUGH -> out.append(body)
                Kind.CODE -> out.append(line.substring(span.open.first, span.close.last + 1))
                Kind.LINK -> out.append(body).append(" (").append(urlOf(line, span)).append(")")
                Kind.IMAGE -> out.append(body)
            }
            at = span.close.last + 1
        }
        out.append(line.substring(at))
    }
    return out.toString()
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
    var inFence = false
    /*
     * Walked with an index rather than by splitting, because the separator's length is part
     * of the answer. `lines()` treats a Windows ending as one break and hands back the two
     * lines either side, but the offset below has to advance past both characters: counting
     * one left every style after the first Windows newline drawn a character early, so bold
     * appeared over the character before the bold word. A pasted message is exactly where
     * those endings come from.
     */
    for (line in text.lines()) {
        val length = line.length
        if (isFence(line)) {
            builder.addStyle(FAINT, offset, offset + length)
            inFence = !inFence
        } else if (inFence) {
            builder.addStyle(SpanStyle(fontFamily = FontFamily.Monospace), offset, offset + length)
        } else {
            val h2 = H2.find(line)
            val h1 = if (h2 == null) H1.find(line) else null
            // The same step used to render a received heading, in HtmlBody.kt: 24 - level*2.
            if (h2 != null) {
                builder.addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp), offset, offset + length)
                builder.addStyle(FAINT, offset, offset + 3)
            } else if (h1 != null) {
                builder.addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp), offset, offset + length)
                builder.addStyle(FAINT, offset, offset + 2)
            }
            if (QUOTE.matches(line)) {
                builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0x99808080)), offset, offset + length)
                builder.addStyle(FAINT, offset, offset + 2)
            }
            val align = ALIGN_CENTER.find(line) ?: ALIGN_RIGHT.find(line)
            if (align != null) {
                // Not drawn centred or right here. AnnotatedString can carry a ParagraphStyle
                // with its own text-align, but a range has to land exactly on a paragraph
                // boundary or the builder throws, and a field whose text changes on every
                // keystroke is exactly the place that guarantee is hardest to keep. Only the
                // marker gets the same faint treatment as every other one; the alignment
                // itself is what the recipient sees, from markupToHtml.
                val markerLength = align.value.length - align.groupValues[1].length
                builder.addStyle(FAINT, offset, offset + markerLength)
            }
            for (span in spans(line)) {
                val style = when (span.kind) {
                    Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    Kind.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                    Kind.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    Kind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
                    Kind.LINK, Kind.IMAGE -> SpanStyle(textDecoration = TextDecoration.Underline)
                }
                builder.addStyle(style, offset + span.body.first, offset + span.body.last + 1)
                builder.addStyle(FAINT, offset + span.open.first, offset + span.open.last + 1)
                builder.addStyle(FAINT, offset + span.close.first, offset + span.close.last + 1)
            }
        }
        // Past the line and past whatever ended it: two characters for a Windows ending,
        // one for the others, and nothing at all after the last line.
        offset += length + when {
            text.startsWith("\r\n", offset + length) -> 2
            offset + length < text.length -> 1
            else -> 0
        }
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

private fun stripAlign(line: String): String =
    ALIGN_CENTER.find(line)?.groupValues?.get(1) ?: ALIGN_RIGHT.find(line)?.groupValues?.get(1) ?: line

/**
 * Sets or clears the alignment marker on every line the selection touches, or the line
 * holding the caret when nothing is selected. [align] is `"center"`, `"right"`, or null for
 * Align left, which has no marker of its own: pressing it removes whichever one was there.
 *
 * Only one alignment can hold a line at a time. [prefixLines] toggles a single prefix on and
 * off, which is right for Bullets and Numbers, but pressing Centre on a line already marked
 * Right cannot just prepend `::center:: ` in front of `::right:: `, or the line carries two
 * markers and renders as neither. So the existing marker, whichever it is, comes off first.
 */
internal fun alignLines(value: TextFieldValue, align: String?): TextFieldValue {
    val text = value.text
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, text.length)
    val from = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0 || start == 0) 0 else it + 1 }
    val toNewline = text.indexOf('\n', end)
    val to = if (toNewline < 0) text.length else toNewline

    val lines = text.substring(from, to).split("\n")
    val bare = lines.map(::stripAlign)
    val marker = if (align == null) null else "::$align:: "
    // Pressing the alignment that is already on every touched line clears it, the same as a
    // second press of Bullets or Numbers.
    val already = marker != null && lines == bare.map { marker + it }
    val changed = bare.map { plain -> if (marker == null || already) plain else marker + plain }
        .joinToString("\n")

    val next = text.substring(0, from) + changed + text.substring(to)
    return value.copy(text = next, selection = TextRange(from + changed.length))
}

/** One line with its line-level marker, if it has one, and every inline marker taken off. */
private fun clearLine(line: String): String {
    val bare = when {
        isFence(line) -> ""
        H2.matches(line) -> H2.find(line)!!.groupValues[1]
        H1.matches(line) -> H1.find(line)!!.groupValues[1]
        QUOTE.matches(line) -> QUOTE.find(line)!!.groupValues[1]
        BULLET.matches(line) -> BULLET.find(line)!!.groupValues[1]
        NUMBER.matches(line) -> NUMBER.find(line)!!.groupValues[1]
        ALIGN_CENTER.matches(line) -> ALIGN_CENTER.find(line)!!.groupValues[1]
        ALIGN_RIGHT.matches(line) -> ALIGN_RIGHT.find(line)!!.groupValues[1]
        else -> line
    }
    val out = StringBuilder()
    var at = 0
    for (span in spans(bare)) {
        out.append(bare.substring(at, span.open.first))
        out.append(bare.substring(span.body.first, span.body.last + 1))
        at = span.close.last + 1
    }
    out.append(bare.substring(at))
    return out.toString()
}

/**
 * Strips every marker from the selection, or from the line holding the caret when nothing is
 * selected: the line-level marker if there is one, and every inline span, kept as its plain
 * body. A link loses its address the same as bold loses its asterisks, because Clear means
 * clear, not "clear except the one kind of marker that also carries data".
 */
internal fun clearFormatting(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val start: Int
    val end: Int
    if (value.selection.start != value.selection.end) {
        /*
         * Widened to the whole of every line the selection touches, the same as [alignLines].
         *
         * A heading, a quote and an alignment are line markers, matched from the start of a
         * line, so handing [clearLine] the middle of one matches nothing and the marker
         * survives a press of Clear. Dragging from inside `# Title` into the middle of
         * `**bold**` cleared neither. Clear means clear, and a reader who selected part of a
         * line meant that line.
         */
        val from = minOf(value.selection.start, value.selection.end).coerceIn(0, text.length)
        val to = maxOf(value.selection.start, value.selection.end).coerceIn(from, text.length)
        start = text.lastIndexOf('\n', (from - 1).coerceAtLeast(0)).let { if (it < 0 || from == 0) 0 else it + 1 }
        val after = text.indexOf('\n', to)
        end = if (after < 0) text.length else after
    } else {
        val at = value.selection.start.coerceIn(0, text.length)
        start = text.lastIndexOf('\n', (at - 1).coerceAtLeast(0)).let { if (it < 0 || at == 0) 0 else it + 1 }
        val toNewline = text.indexOf('\n', at)
        end = if (toNewline < 0) text.length else toNewline
    }

    val changed = text.substring(start, end).split("\n").joinToString("\n") { clearLine(it) }
    val next = text.substring(0, start) + changed + text.substring(end)
    return value.copy(text = next, selection = TextRange(start + changed.length))
}

/**
 * What formatting the caret sits inside, so the toolbar can show a pressed button instead of
 * pretending nothing is ever already on. A selection uses its start.
 *
 * `"align-left"` is included even though it has no marker of its own: it is the state of
 * neither `"align-center"` nor `"align-right"` being set, and a caret sitting in an ordinary
 * line should still show one of the three alignment buttons pressed, the same as Word or
 * Gmail, rather than showing none of them.
 */
internal fun activeMarks(value: TextFieldValue): Set<String> {
    val text = value.text
    val at = minOf(value.selection.start, value.selection.end).coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', (at - 1).coerceAtLeast(0)).let { if (it < 0 || at == 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    val caret = at - lineStart

    val marks = mutableSetOf<String>()
    if (BULLET.matches(line)) marks += "bullets"
    if (NUMBER.matches(line)) marks += "numbers"
    if (H1.matches(line)) marks += "h1"
    if (H2.matches(line)) marks += "h2"
    if (QUOTE.matches(line)) marks += "quote"
    when {
        ALIGN_CENTER.matches(line) -> marks += "align-center"
        ALIGN_RIGHT.matches(line) -> marks += "align-right"
        else -> marks += "align-left"
    }

    // Between the two markers, inclusive of both, reads as "inside": the common case is a
    // caret resting right after typing the closing one, which is still the word being marked.
    for (span in spans(line)) {
        if (caret < span.open.first || caret > span.close.last) continue
        when (span.kind) {
            Kind.BOLD -> marks += "bold"
            Kind.ITALIC -> marks += "italic"
            Kind.UNDERLINE -> marks += "underline"
            Kind.STRIKETHROUGH -> marks += "strike"
            Kind.CODE -> marks += "code"
            Kind.LINK, Kind.IMAGE -> {}
        }
    }
    return marks
}

/**
 * Bounded undo and redo for the composer's body.
 *
 * Coalesced by time rather than by keystroke: a run of ordinary typing is one step to take
 * back, and only a pause of [coalesceMs] or more, or a discrete edit such as a toolbar
 * button, starts a new one. Without that, one Ctrl+Z would take back a single character,
 * which is not what anyone means by undo.
 *
 * Capped at [limit] steps, oldest dropped first, so a message that has been open and edited
 * for a long time does not grow the history without bound.
 */
internal class UndoHistory(private val limit: Int = 200, private val coalesceMs: Long = 800) {
    private val past = ArrayDeque<TextFieldValue>()
    private val future = ArrayDeque<TextFieldValue>()
    private var lastEdit = 0L

    /**
     * A discrete edit: a toolbar button, an emoji, a picture, anything that is not plain
     * typing. Always its own step, regardless of how close together two of them land.
     */
    fun push(before: TextFieldValue) {
        future.clear()
        past.addLast(before)
        while (past.size > limit) past.removeFirst()
        lastEdit = 0
    }

    /**
     * One character, or a small run of them, typed at [now]. Folded into the run it belongs
     * to unless [coalesceMs] has passed since the last one, which is what starts a new step.
     */
    fun type(before: TextFieldValue, after: TextFieldValue, now: Long = System.currentTimeMillis()) {
        /*
         * Time alone is not enough to say two edits belong together.
         *
         * It used to be, and a deletion that landed inside the window was folded into the
         * run of typing before it: type "hello", select all and delete within the window,
         * and the step holding "hello" was never pushed, so one press of Undo went past it
         * to the empty field it started from. Text that was typed and then removed is
         * exactly the text somebody reaches for Undo to get back.
         *
         * So only growth continues a run. Anything that shortens the text, or replaces a
         * selection, starts a step of its own however fast it arrived.
         */
        val continues = after.text.length > before.text.length && before.selection.collapsed
        if (past.isNotEmpty() && continues && now - lastEdit < coalesceMs) {
            lastEdit = now
            return
        }
        push(before)
        lastEdit = now
    }

    /** The value to go back to, or null when there is nothing left to undo. */
    fun undo(current: TextFieldValue): TextFieldValue? {
        val step = past.removeLastOrNull() ?: return null
        future.addLast(current)
        lastEdit = 0
        return step
    }

    /** The value to go forward to, or null when there is nothing left to redo. */
    fun redo(current: TextFieldValue): TextFieldValue? {
        val step = future.removeLastOrNull() ?: return null
        past.addLast(current)
        lastEdit = 0
        return step
    }

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
}
