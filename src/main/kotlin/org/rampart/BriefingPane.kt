package org.rampart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

/**
 * Whether the Today view is showing, and the briefing it shows.
 *
 * Held by the window rather than the pane, so leaving for a message and coming back does
 * not throw away a briefing that cost money to make.
 */
internal class TodayView {
    var open by mutableStateOf(false)
    val job = RookJob<Brief>()
}

/** Every account's briefing, in the app's own data directory beside the accounts file. */
internal val briefingCache by lazy { BriefingCache(Accounts.file().parent) }

/**
 * Show today's briefing for [account]: from the cache when there is one for today and
 * [refresh] is false, otherwise made fresh.
 *
 * Called when the view opens and when Refresh is pressed, and from nowhere else. There is
 * no timer anywhere in this feature, by design: see `docs/assistant.md`.
 *
 * @param secret The key the cache is encrypted with, or null for memory only. A function
 *   because reading it can mean asking the operating system's credential store, which is
 *   not something to do on the main thread.
 */
internal fun openBriefing(
    view: TodayView,
    scope: CoroutineScope,
    account: String,
    reader: InboxReader,
    folder: String?,
    secret: () -> String?,
    refresh: Boolean,
) {
    val job = view.job
    if (job.running) return
    val day = LocalDate.now(Regional.zone()).toString()
    scope.launch {
        if (!refresh) {
            val kept = withContext(Dispatchers.IO) {
                runCatching { briefingCache.read(account, day, secret()) }.getOrNull()
            }
            if (kept != null) {
                job.forKey = account
                job.clear()
                job.result = kept
                return@launch
            }
        }
        val config = Assistant.config()
        val why = Assistant.whyNot(Assistant.BRIEFING, config, account, folder)
        job.start(scope, Assistant.BRIEFING, config, why, account) {
            val now = Instant.now()
            val unread = try {
                Briefing.recent(reader.unread(Briefing.ASK_FOR), now)
            } catch (e: Exception) {
                throw StepFailed("The server would not list your unread mail.", e)
            }
            if (unread.isEmpty()) {
                // Nothing to read means nothing to send. Not cached, so new mail is seen the
                // next time the view opens, which costs nothing either way.
                return@start Prepared(null) { Brief(day, now.toString(), 0, emptyList(), emptyList(), emptyList()) }
            }
            val sources = unread.take(Briefing.MOST).mapNotNull { message ->
                runCatching { reader.read(message.id) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { Source(message, it) }
            }
            if (sources.isEmpty()) throw StepFailed("None of your unread messages would open.", null)
            val packet = Llm.packet(config.model, Briefing.system(), Briefing.user(sources), maxTokens = 2_000)
            Prepared(packet) { reply ->
                val brief = Briefing.parse(reply.orEmpty(), sources, day, now.toString(), all = unread)
                // A cache that could not be written costs a second call tomorrow's first
                // open would have made anyway, so it is not a failure worth showing.
                runCatching { briefingCache.write(account, brief, secret()) }
                brief
            }
        }
    }
}

/**
 * The Today view: what needs an answer, what has a date on it, and the rest by topic.
 *
 * Every line is a link to its message. A date or amount the model gave that is not in the
 * message word for word carries a warning rather than being shown as fact.
 */
@Composable
internal fun BriefingPane(
    view: TodayView,
    /** The account key the briefing is for. A briefing made for another is not shown. */
    account: String,
    accountName: String,
    /** Opens (or re-reads) the briefing. Called when the pane appears, and on Refresh. */
    onLoad: (refresh: Boolean) -> Unit,
    onOpen: (Summary) -> Unit,
    onBack: () -> Unit,
) {
    val job = view.job
    RookJobConsent(job)
    LaunchedEffect(account) { onLoad(false) }
    val mine = job.forKey == account
    val brief = job.result?.takeIf { mine }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Today", style = MaterialTheme.typography.headlineSmall)
                Text(
                    briefingLine(brief, accountName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            TextButton(onClick = onBack) { Text("Dashboard") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onLoad(true) }, enabled = !job.running) {
                Text(if (job.running) "Reading" else "Refresh")
            }
        }
        Spacer(Modifier.height(18.dp))
        if (job.running && mine) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RookAvatar(size = 18.dp, working = true)
                Spacer(Modifier.width(8.dp))
                Text("Rook is reading your unread mail.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(14.dp))
        }
        job.failure?.takeIf { mine }?.let { (title, detail) ->
            FaultText(title, detail)
            Spacer(Modifier.height(14.dp))
        }
        if (brief != null) {
            if (brief.looked == 0) {
                Text("Nothing unread from the last two days.", style = MaterialTheme.typography.bodyMedium)
            } else {
                BriefSection("Needs a reply from you", brief.replies, "Nobody is waiting on you.", onOpen)
                BriefSection("Deadlines and bills", brief.deadlines, "No dates or bills.", onOpen)
                brief.topics.forEach { topic -> BriefSection(topic.name, topic.lines, "", onOpen) }
            }
        }
    }
}

/** "Made at 09:12 from 14 unread messages, for Justin", or what is happening instead. */
private fun briefingLine(brief: Brief?, accountName: String): String {
    val who = accountName.ifBlank { "this account" }
    if (brief == null) return "Unread mail from the last two days, for $who."
    val at = runCatching {
        Regional.time(Instant.parse(brief.made))
    }.getOrDefault("")
    return "Made ${if (at.isBlank()) "today" else "at $at"} from ${brief.looked} unread messages, for $who. Refresh reads them again."
}

@Composable
private fun BriefSection(title: String, lines: List<BriefLine>, empty: String, onOpen: (Summary) -> Unit) {
    if (lines.isEmpty() && empty.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    if (lines.isEmpty()) {
        Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
    lines.forEach { line -> BriefRow(line, onOpen) }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun BriefRow(line: BriefLine, onOpen: (Summary) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onOpen(line.message) }
            .padding(vertical = 6.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(line.message.from, line.message.fromEmail, 24.dp, photo = photoFor(line.message.fromEmail))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                line.note + if (line.date.isNotBlank()) ", ${line.date}" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${line.message.from.ifBlank { line.message.fromEmail }}: ${line.message.subject.ifBlank { "(no subject)" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (line.unverified.isNotEmpty()) {
                Text(
                    "Not in the message word for word, so check it: ${line.unverified.joinToString(", ")}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ---- action items, asked for from Rook's panel ------------------------------------------

/** Starts the action items call for [thread], whose turns [turns] fetches. */
internal fun startActionItems(
    job: RookJob<List<ActionItem>>,
    scope: CoroutineScope,
    why: String?,
    thread: String,
    subject: String,
    onDone: (List<ActionItem>) -> Unit = {},
    onFailure: (Pair<String, String?>) -> Unit = {},
    turns: suspend () -> List<Turn>,
) {
    val config = Assistant.config()
    job.start(scope, Assistant.ACTIONS, config, why, thread, onDone, onFailure) {
        val said = turns()
        val text = said.joinToString("\n\n") { it.text }
        Prepared(Llm.packet(config.model, ActionItems.system(), ActionItems.user(subject, said))) { reply ->
            ActionItems.parse(reply.orEmpty(), text)
        }
    }
}
