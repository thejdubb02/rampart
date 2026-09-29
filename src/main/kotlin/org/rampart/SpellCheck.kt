package org.rampart

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.languagetool.JLanguageTool
import org.languagetool.language.AmericanEnglish
import org.languagetool.rules.Rule
import org.languagetool.rules.RuleMatch

/**
 * How long a draft sits still before it is checked.
 *
 * Short enough that the mark appears while the sentence is still the one being written,
 * long enough that a check is not started for every key.
 */
internal const val SPELL_PAUSE_MS = 600L

/** Spelling or grammar. Style nags are dropped before an issue is built, so they have no kind. */
internal enum class IssueKind { SPELLING, GRAMMAR }

/**
 * One marked stretch of the draft.
 *
 * [range] is inclusive and refers to the text as typed. Markdown links hide their
 * addresses only while drawing, so a suggestion replaces this stretch and nothing beside it.
 */
internal data class Issue(
    val range: IntRange,
    val message: String,
    val kind: IssueKind,
    val suggestions: List<String>,
)

/** Where a suggestion menu was opened, in the coordinates of the field's own box. */
internal data class SpellAnchor(val issue: Issue, val at: Offset)

private val URL_IN_TEXT = Regex("""(?i)\b(?:https?://|www\.)[^\s<>]+""")
private val EMAIL_IN_TEXT = Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b""")
private val URL_TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')')
private val TAG = Regex("<[^>]*>")
private val GRAMMAR_CATEGORIES = setOf("GRAMMAR", "PUNCTUATION", "CASING", "CONFUSED_WORDS")

/** The red Rampart already uses, so a spelling mark reads as the same kind of attention. */
private val SpellingUnderline = Color(0xFFDB2D54)

/** Fixed, so a theme cannot swap which colour is spelling and which is grammar. */
private val GrammarUnderline = Color(0xFF2F6FED)

/**
 * The stretches [check] should not mark.
 *
 * A quoted reply is someone else's text, and marking it asks the writer to correct a
 * sentence they are not sending as their own. The same goes for the forwarded original.
 * A sign-off under a forward is the writer's, so it stays. Addresses and links are not
 * words. The personal dictionary is matched whole, so a short entry does not hide a
 * longer word that merely starts the same way.
 */
internal fun spansToSkip(text: String, dictionary: Collection<String>): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    var i = 0
    while (i < text.length) {
        val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
        val line = text.substring(i, end).trimEnd('\r')
        // A ">" only counts as a quote at the start of the line. One inside a sentence
        // is a comparison, and skipping the line would hide the writer's own words.
        if (line.trimStart().startsWith('>') && i < end) spans += i until end
        i = if (end < text.length) end + 1 else text.length
    }
    val quote = quoteStart(text)
    if (quote >= 0) {
        val lineEnd = text.indexOf('\n', quote).let { if (it < 0) text.length else it }
        val line = text.substring(quote, lineEnd)
        if (line.contains("Forwarded message")) {
            // The writer's own sign-off, when it is the last "-- " at or after the banner.
            // Everything of the original above that line is left unmarked.
            val sig = text.lastIndexOf("\n-- \n")
            val stop = if (sig >= quote) sig else text.length
            if (quote < stop) spans += quote until stop
        } else if (quote < lineEnd) {
            spans += quote until lineEnd
        }
    }
    spans += urlSpans(text)
    spans += emailSpans(text)
    spans += dictionarySpans(text, dictionary)
    return mergeSpans(spans)
}

private fun urlSpans(text: String): List<IntRange> = URL_IN_TEXT.findAll(text).mapNotNull { match ->
    var end = match.range.last
    while (end >= match.range.first && text[end] in URL_TRAILING) end--
    val range = match.range.first..end
    range.takeUnless { it.isEmpty() }
}.toList()

private fun emailSpans(text: String): List<IntRange> = EMAIL_IN_TEXT.findAll(text).map { it.range }.toList()

private fun dictionarySpans(text: String, dictionary: Collection<String>): List<IntRange> {
    if (dictionary.isEmpty() || text.isEmpty()) return emptyList()
    val spans = mutableListOf<IntRange>()
    for (raw in dictionary) {
        val word = raw.trim().replace(Regex("""\s+"""), " ")
        if (word.isEmpty()) continue
        var from = 0
        while (from < text.length) {
            val at = text.indexOf(word, from, ignoreCase = true)
            if (at < 0) break
            val end = at + word.length
            val before = at == 0 || !text[at - 1].isLetterOrDigit()
            val after = end == text.length || !text[end].isLetterOrDigit()
            if (before && after) spans += at until end
            from = at + 1
        }
    }
    return spans
}

/** Touching or overlapping inclusive ranges become one, so a masked stretch has no gaps. */
internal fun mergeSpans(spans: List<IntRange>): List<IntRange> {
    val sorted = spans.filter { !it.isEmpty() }.sortedBy { it.first }
    if (sorted.isEmpty()) return emptyList()
    val out = mutableListOf(sorted.first())
    for (span in sorted.drop(1)) {
        val last = out.last()
        if (span.first <= last.last + 1) out[out.lastIndex] = last.first..max(last.last, span.last)
        else out += span
    }
    return out
}

/**
 * The same text with each skipped stretch replaced by spaces.
 *
 * Newlines stay, and so does the length, which is what makes a match's offsets describe
 * the original. The checker is not told to ignore the words: one shared checker serves
 * the subject and the body, and a dictionary that changed between checks would otherwise
 * have to be written into it.
 */
internal fun maskSkipped(text: String, spans: List<IntRange>): String {
    if (spans.isEmpty()) return text
    val chars = text.toCharArray()
    for (span in spans) {
        val start = span.first.coerceAtLeast(0)
        val end = span.last.coerceAtMost(chars.lastIndex)
        if (start > end) continue
        for (i in start..end) {
            if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' '
        }
    }
    return String(chars)
}

/**
 * [replacement] written over [range], or [text] when the range is not inside it.
 *
 * Refusing a stale range matters: a check that finished after another edit must not
 * replace whatever now sits at the old offsets.
 */
internal fun applySuggestion(text: String, range: IntRange, replacement: String): String {
    if (range.first < 0 || range.last >= text.length || range.first > range.last) return text
    return text.replaceRange(range, replacement)
}

/**
 * One dictionary entry as it is stored: trimmed, inner spaces collapsed, empty dropped.
 *
 * A line break is not an entry. A grammar span that covers two lines is left as a mark
 * the writer can ignore for this draft, not as a phrase saved forever.
 */
internal fun dictionaryEntry(surface: String): String? {
    if (surface.any { it == '\n' || it == '\r' }) return null
    val collapsed = surface.trim().replace(Regex("""\s+"""), " ")
    return collapsed.ifEmpty { null }
}

internal fun surfaceOf(text: String, range: IntRange): String? {
    if (range.first < 0 || range.last >= text.length || range.first > range.last) return null
    return dictionaryEntry(text.substring(range))
}

/** Issues whose text is one of [saved], without regard to capitals. */
internal fun List<Issue>.hiding(text: String, saved: Collection<String>): List<Issue> {
    if (isEmpty() || saved.isEmpty()) return this
    return filter { issue ->
        val surface = surfaceOf(text, issue.range) ?: return@filter true
        saved.none { it.equals(surface, ignoreCase = true) }
    }
}

/**
 * The form the dictionary is stored in.
 *
 * Alphabetical without regard to capitals, one casing kept, so a sync between two
 * computers does not bounce the list. The first spelling seen wins.
 */
internal fun canonicalDictionary(words: Iterable<String>): List<String> {
    val byKey = linkedMapOf<String, String>()
    for (raw in words) {
        val collapsed = dictionaryEntry(raw) ?: continue
        val key = collapsed.lowercase(Locale.ROOT)
        if (key !in byKey) byKey[key] = collapsed
    }
    return byKey.values.sortedBy { it.lowercase(Locale.ROOT) }
}

/**
 * Why [text] is not already the stored form of a dictionary, or null when it is.
 *
 * Empty is a dictionary with nothing in it. Anything else has to round-trip: the value
 * Rook writes is read straight back, and a list that came back sorted or trimmed would
 * look like a save that did not take.
 */
internal fun dictionaryProblem(text: String): String? {
    if (text.isEmpty()) return null
    val canonical = canonicalDictionary(text.split('\n')).joinToString("\n")
    return if (text == canonical) null else
        "Write one word per line, in alphabetical order, with no blank lines and no extra spaces. A phrase saved from the composer keeps a single space."
}

/**
 * Spell and grammar for the composer, on this computer.
 *
 * One [JLanguageTool] for American English, built on first use and off the window's
 * thread, because building it reads the dictionaries and is slow enough to hitch a
 * keystroke. It is not safe to share across checks, so checks take a lock. The
 * constructor of [AmericanEnglish] may run only once, and LanguageTool's own registry
 * already ran it while loading the language list, so a second `AmericanEnglish()`
 * throws. [AmericanEnglish.getInstance] is that existing language.
 *
 * Nothing here turns on remote rules or n-gram data. The ordinary constructor leaves
 * both off, and the American speller is installed even without a language model.
 */
internal object SpellCheck {
    private val gate = Mutex()

    @Volatile
    private var tool: JLanguageTool? = null

    /** Builds the checker if it has not been built yet. Safe to call when a composer opens. */
    suspend fun warm() {
        withContext(Dispatchers.Default) {
            try {
                engine()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The composer still opens. The next check tries again.
            }
        }
    }

    /**
     * Issues in [text].
     *
     * [grammar] false keeps spelling only, which is what the subject line asks for.
     * [dictionary] and [ignored] are skipped for this check and are not written into the
     * shared checker, because the next check may have a different list. A failure of the
     * checker returns nothing: a missing dictionary should not take the composer down.
     * Blank text returns nothing without building the checker.
     */
    suspend fun check(
        text: String,
        grammar: Boolean = true,
        dictionary: Collection<String> = emptyList(),
        ignored: Collection<String> = emptyList(),
    ): List<Issue> {
        if (text.isBlank()) return emptyList()
        return withContext(Dispatchers.Default) {
            val words = ArrayList<String>(dictionary.size + ignored.size)
            words.addAll(dictionary)
            words.addAll(ignored)
            val skip = spansToSkip(text, words)
            val masked = maskSkipped(text, skip)
            val matches = try {
                val lt = engine()
                gate.withLock {
                    coroutineContext.ensureActive()
                    lt.check(masked)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext emptyList()
            }
            coroutineContext.ensureActive()
            buildIssues(text, matches, skip, grammar)
        }
    }

    private suspend fun engine(): JLanguageTool {
        tool?.let { return it }
        return gate.withLock {
            tool ?: JLanguageTool(AmericanEnglish.getInstance()).also { tool = it }
        }
    }
}

private fun buildIssues(text: String, matches: List<RuleMatch>, skip: List<IntRange>, grammar: Boolean): List<Issue> {
    val found = ArrayList<Issue>(matches.size)
    for (match in matches) {
        val kind = kindOf(match.rule) ?: continue
        if (kind == IssueKind.GRAMMAR && !grammar) continue
        val start = match.fromPos
        val end = match.toPos
        if (start < 0 || end <= start || end > text.length) continue
        val range = start until end
        if (skip.any { it.first <= range.last && range.first <= it.last }) continue
        if (text.substring(range).isBlank()) continue
        found += Issue(
            range = range,
            message = cleanSpellMessage(match.shortMessage.orEmpty().ifBlank { match.message.orEmpty() }),
            kind = kind,
            suggestions = match.suggestedReplacements.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5),
        )
    }
    return found
}

/**
 * Spelling, or the grammar worth a line under a sentence.
 *
 * Style, redundancy and typography rules are left out: a composer painted with those
 * is a composer people switch off. The shared checker cannot have rules removed between
 * the subject (spelling only) and the body, so the choice is made on the result.
 */
private fun kindOf(rule: Rule): IssueKind? {
    val id = rule.id.uppercase(Locale.ROOT)
    val category = rule.category?.id?.toString()?.uppercase(Locale.ROOT).orEmpty()
    if (rule.isDictionaryBasedSpellingRule || "MORFOLOGIK" in id || "HUNSPELL" in id || "SPELLING" in id || category == "TYPOS") {
        return IssueKind.SPELLING
    }
    if (category in GRAMMAR_CATEGORIES) return IssueKind.GRAMMAR
    return null
}

/** LanguageTool wraps suggestions in tags and uses a soft hyphen. Neither belongs in a menu. */
private fun cleanSpellMessage(raw: String): String {
    val cleaned = raw
        .replace(TAG, "")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace('\u00AD', ' ')
        .replace('\u2014', '-')
        .replace('\u2013', '-')
        .replace("\u2026", "...")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return if (cleaned.length <= 180) cleaned else cleaned.take(177) + "..."
}

/**
 * Draws a wave under each issue in layout coordinates.
 *
 * An overlay, rather than another text transformation: the body already transforms
 * markdown links, and a second one would have to share the caret's offset arithmetic.
 * The wave does not change the text or the caret. [mapping] turns an issue's range in
 * the typed text into the range the layout actually drew.
 */
private fun DrawScope.drawIssueWaves(layout: TextLayoutResult, issues: List<Issue>, mapping: OffsetMapping) {
    val shownLen = layout.layoutInput.text.length
    if (shownLen == 0) return
    for (issue in issues) {
        val start = mapping.originalToTransformed(issue.range.first.coerceAtLeast(0)).coerceIn(0, shownLen)
        val end = mapping.originalToTransformed((issue.range.last + 1).coerceAtLeast(0)).coerceIn(0, shownLen)
        if (end <= start) continue
        val color = if (issue.kind == IssueKind.SPELLING) SpellingUnderline else GrammarUnderline
        var at = start
        var guard = 0
        while (at < end && guard++ < shownLen + 2) {
            val line = layout.getLineForOffset(at.coerceAtMost(shownLen))
            val lineEnd = layout.getLineEnd(line, visibleEnd = true).coerceIn(0, shownLen)
            val segEnd = if (lineEnd <= at) at + 1 else min(end, lineEnd)
            val left = layout.getHorizontalPosition(at.coerceIn(0, shownLen), true)
            val right = layout.getHorizontalPosition(segEnd.coerceIn(0, shownLen), true)
            val x1 = min(left, right)
            val x2 = max(left, right)
            if (x2 - x1 >= 1f) wave(x1, x2, layout.getLineBaseline(line), color)
            at = max(segEnd, at + 1)
        }
    }
}

private fun DrawScope.wave(x1: Float, x2: Float, baseline: Float, color: Color) {
    val amp = 1.dp.toPx()
    val length = 4.dp.toPx()
    val y = baseline + amp
    val step = 1.dp.toPx().coerceAtLeast(1f)
    val path = Path()
    path.moveTo(x1, y + sin(0.0).toFloat() * amp)
    var x = x1
    while (x < x2) {
        val next = min(x + step, x2)
        val t = (next - x1) / length * (2.0 * PI)
        path.lineTo(next, y + sin(t).toFloat() * amp)
        x = next
    }
    drawPath(path, color, style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * The wave under [issues]. Innermost on the text field, after padding and scrolling,
 * so the coordinates are the ones [layout] reports.
 */
@Composable
internal fun Modifier.spellUnderlines(
    layout: TextLayoutResult?,
    issues: List<Issue>,
    text: String,
    markup: Boolean,
): Modifier {
    val layoutNow = rememberUpdatedState(layout)
    val issuesNow = rememberUpdatedState(issues)
    val textNow = rememberUpdatedState(text)
    val mapping = remember(text, markup) {
        if (markup) displayMarkup(text).offsetMapping else OffsetMapping.Identity
    }
    return drawBehind {
        val result = layoutNow.value ?: return@drawBehind
        val current = issuesNow.value
        if (current.isEmpty()) return@drawBehind
        val expected = mapping.originalToTransformed(textNow.value.length)
        if (result.layoutInput.text.length != expected) return@drawBehind
        drawIssueWaves(result, current, mapping)
    }
}

/**
 * Opens the suggestion menu on a right click, or on a left click that did not drag.
 *
 * The press is seen on the initial pass, before the text field takes it, and a left
 * press is never consumed, so the caret still moves. The latest text and layout are
 * read when the click happens: putting them in the pointer key would restart the
 * gesture on every keystroke.
 */
@Composable
internal fun Modifier.spellIssueClicks(
    issues: List<Issue>,
    text: String,
    layout: TextLayoutResult?,
    markup: Boolean,
    toContent: (Offset) -> Offset,
    onIssue: (Issue, Offset) -> Unit,
): Modifier {
    val issuesNow = rememberUpdatedState(issues)
    val textNow = rememberUpdatedState(text)
    val layoutNow = rememberUpdatedState(layout)
    val toContentNow = rememberUpdatedState(toContent)
    val onIssueNow = rememberUpdatedState(onIssue)
    return pointerInput(markup) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val (down, right) = awaitSpellDown() ?: return@awaitEachGesture
            val press = down.position
            if (right) {
                val issue = issueAt(layoutNow.value, toContentNow.value(press), textNow.value, issuesNow.value, markup)
                if (issue != null) {
                    down.consume()
                    onIssueNow.value(issue, press)
                }
                return@awaitEachGesture
            }
            val up = awaitSpellUp(down.id) ?: return@awaitEachGesture
            if ((up.position - press).getDistance() > slop) return@awaitEachGesture
            val issue = issueAt(layoutNow.value, toContentNow.value(up.position), textNow.value, issuesNow.value, markup)
                ?: return@awaitEachGesture
            onIssueNow.value(issue, up.position)
        }
    }
}

/** The press, and whether it was the secondary button. Pointer changes in this Compose do not carry the button. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private suspend fun AwaitPointerEventScope.awaitSpellDown(): Pair<PointerInputChange, Boolean>? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
        return down to (event.button == PointerButton.Secondary)
    }
}

/**
 * The release of [id], seen before the text field consumes it.
 *
 * Waiting on the initial pass is what keeps a drag from being reported as consumed:
 * the field takes the gesture later, on the main pass.
 */
private suspend fun AwaitPointerEventScope.awaitSpellUp(id: PointerId): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == id } ?: continue
        if (change.changedToUp()) return change
        if (change.isConsumed) return null
    }
}

private fun issueAt(
    layout: TextLayoutResult?,
    content: Offset,
    text: String,
    issues: List<Issue>,
    markup: Boolean,
): Issue? {
    if (layout == null || issues.isEmpty() || text.isEmpty()) return null
    if (content.x < 0f || content.y < 0f || content.y > layout.size.height.toFloat() + 2f) return null
    val mapping = if (markup) displayMarkup(text).offsetMapping else OffsetMapping.Identity
    val shownLen = layout.layoutInput.text.length
    if (mapping.originalToTransformed(text.length) != shownLen || shownLen == 0) return null
    val line = layout.getLineForVerticalPosition(content.y)
    val top = layout.getLineTop(line)
    val bottom = layout.getLineBottom(line)
    if (content.y < top - 2f || content.y > bottom + 3f) return null
    val transformed = layout.getOffsetForPosition(content).coerceIn(0, shownLen)
    val original = mapping.transformedToOriginal(transformed)
    val lineStart = layout.getLineStart(line)
    val lineEnd = layout.getLineEnd(line, visibleEnd = true).coerceIn(0, shownLen)
    for (issue in issues) {
        if (original !in issue.range && (original - 1) !in issue.range) continue
        val start = mapping.originalToTransformed(issue.range.first.coerceIn(0, text.length)).coerceIn(0, shownLen)
        val end = mapping.originalToTransformed((issue.range.last + 1).coerceIn(0, text.length)).coerceIn(0, shownLen)
        val segStart = max(start, lineStart)
        val segEnd = min(end, lineEnd)
        if (segEnd <= segStart) continue
        val left = layout.getHorizontalPosition(segStart, true)
        val right = layout.getHorizontalPosition(segEnd, true)
        val x1 = min(left, right) - 2f
        val x2 = max(left, right) + 2f
        if (content.x in x1..x2) return issue
    }
    return null
}

/**
 * Suggestions for one issue, plus adding it to the dictionary and ignoring it for this draft.
 *
 * Placed from the click, in the window, and kept on screen. The field underneath is left
 * as it was: choosing a suggestion is the caller's edit.
 */
@Composable
internal fun SpellSuggestionMenu(
    issue: Issue,
    anchor: Offset,
    canAdd: Boolean,
    onSuggestion: (String) -> Unit,
    onAdd: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val provider = remember(anchor) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.left + anchor.x.roundToInt()
                var y = anchorBounds.top + anchor.y.roundToInt() + 6
                if (y + popupContentSize.height > windowSize.height) {
                    y = anchorBounds.top + anchor.y.roundToInt() - popupContentSize.height - 6
                }
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                return IntOffset(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.widthIn(min = 180.dp, max = 320.dp).padding(vertical = 4.dp)) {
                if (issue.message.isNotBlank()) {
                    Text(
                        issue.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                issue.suggestions.forEach { suggestion ->
                    DropdownMenuItem(text = { Text(suggestion) }, onClick = { onSuggestion(suggestion) })
                }
                if (issue.message.isNotBlank() || issue.suggestions.isNotEmpty()) HorizontalDivider()
                if (canAdd) DropdownMenuItem(text = { Text("Add to dictionary") }, onClick = onAdd)
                DropdownMenuItem(text = { Text("Ignore") }, onClick = onIgnore)
            }
        }
    }
}
