package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/**
 * Work for Rook that has been prepared and is waiting to be sent, or has been.
 *
 * [packet] null means the answer is already known without asking anybody (a search that
 * found nothing, a day with no unread mail), and [finish] is then called with null. Otherwise
 * [finish] is handed exactly what the model said and turns it into the result, checked.
 */
internal class Prepared<T>(val packet: String?, val finish: (String?) -> T)

/**
 * One of the button-press features (ask, the briefing, action items), from press to result.
 *
 * The same shape as Summarise in `Main.kt`, pulled into one place because three features
 * share it: check whether it may run at all, prepare off the main thread, show the exact
 * packet before the first send and after that only when asked, send it through [Llm] and
 * record what it cost under the feature's own name. Every failure along the way becomes a
 * sentence in [failure], never a stack trace and never silence.
 *
 * [forKey] says what the result belongs to (a question, a thread, an account), so the view
 * can refuse to show an answer for something that is no longer on screen.
 */
internal class RookJob<T> {
    var running by mutableStateOf(false)
    var result by mutableStateOf<T?>(null)
    /** The sentence to show, and the technical line under it when there is one. */
    var failure by mutableStateOf<Pair<String, String?>?>(null)
    var forKey by mutableStateOf("")
    /** A packet shown for agreement before its first send. Null when no dialog is up. */
    var waiting by mutableStateOf<Waiting<T>?>(null)

    class Waiting<T>(val feature: String, val config: AssistantConfig, val prepared: Prepared<T>)

    private var scope: CoroutineScope? = null
    private var onDone: (T) -> Unit = {}
    private var onFailure: (Pair<String, String?>) -> Unit = {}

    fun clear() {
        result = null
        failure = null
        waiting = null
    }

    /**
     * Prepare, then send or ask first.
     *
     * [why] is [Assistant.whyNot] for this feature, worked out by the caller because only
     * it knows which account and folder are involved. It is checked here, before anything
     * is fetched, so a folder on the never list is never even read.
     */
    fun start(
        scope: CoroutineScope,
        feature: String,
        config: AssistantConfig,
        why: String?,
        key: String,
        onDone: (T) -> Unit = {},
        onFailure: (Pair<String, String?>) -> Unit = {},
        prepare: suspend () -> Prepared<T>,
    ) {
        if (running) return
        this.scope = scope
        this.onDone = onDone
        this.onFailure = onFailure
        forKey = key
        clear()
        if (why != null) {
            val f = why to null
            failure = f
            onFailure(f)
            return
        }
        running = true
        scope.launch {
            val prepared = runCatching { withContext(Dispatchers.IO) { prepare() } }
            prepared.fold(
                onSuccess = { ready ->
                    when {
                        ready.packet == null -> {
                            running = false
                            finishWith(runCatching { ready.finish(null) })
                        }
                        // The first time, the exact packet is shown and nothing leaves until
                        // Send it is pressed. See [PacketViewer].
                        !Assistant.agreed(feature) -> {
                            running = false
                            waiting = Waiting(feature, config, ready)
                        }
                        else -> send(feature, config, ready)
                    }
                },
                onFailure = { running = false; fail(it) },
            )
        }
    }

    /** Sends a packet already agreed to, and records what it cost. */
    fun send(feature: String, config: AssistantConfig, ready: Prepared<T>) {
        val scope = scope ?: return
        val packet = ready.packet ?: return
        waiting = null
        running = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val reply = Llm.ask(config, Secrets.loadNamed(Assistant.KEY), packet)
                    Assistant.record(feature, reply.tokensIn, reply.tokensOut, config)
                    ready.finish(reply.text)
                }
            }
            running = false
            finishWith(outcome)
        }
    }

    private fun finishWith(outcome: Result<T>) {
        outcome.fold(
            onSuccess = { result = it; onDone(it) },
            onFailure = { fail(it) },
        )
    }

    private fun fail(thrown: Throwable) {
        val f = when (thrown) {
            is StepFailed -> thrown.message.orEmpty() to thrown.cause?.let { whyFailed(it) }
            is LlmError -> thrown.message.orEmpty() to null
            else -> "Rook could not finish that." to whyFailed(thrown)
        }
        failure = f
        onFailure(f)
    }
}

/** The packet dialog for [job], when it has one waiting. Draw it wherever the job's view is. */
@Composable
internal fun <T> RookJobConsent(job: RookJob<T>) {
    val waiting = job.waiting
    CoverBody(waiting != null)
    if (waiting == null) return
    PacketViewer(
        packet = waiting.prepared.packet.orEmpty(),
        agreed = Assistant.agreed(waiting.feature),
        onSend = { job.send(waiting.feature, waiting.config, waiting.prepared) },
        onAgree = { Assistant.agree(waiting.feature) },
        onDismiss = { job.waiting = null },
    )
}

/**
 * What Rook may read for [account], and nothing else.
 *
 * Junk and Trash are always left out, whichever folder is on screen: junk is the mail most
 * likely to be written to talk a model round, and neither is where an answer should come
 * from. Folders on the never list are left out of the search itself, so a message in one is
 * not fetched, let alone sent. Every summary is stamped with [account] so a link opens on
 * the right server in the merged inbox too.
 */
internal fun inboxReader(
    backend: MailBackend,
    boxes: List<Mailbox>,
    account: String,
    plain: (Body) -> String,
): InboxReader {
    val denied = Assistant.deniedFolders(account)
    val except = foldersToSkip(null, folderFor("junk", boxes)?.id, folderFor("trash", boxes)?.id) +
        boxes.filter { it.name in denied }.map { it.id }
    return object : InboxReader {
        override fun search(text: String, limit: Int): List<Summary> =
            backend.search(text, null, limit, except).map { it.copy(account = account) }

        override fun read(id: String): String? = plain(backend.body(id)).ifBlank { null }

        override fun unread(limit: Int): List<Summary> {
            val inbox = folderFor("inbox", boxes) ?: return emptyList()
            return backend.emails(inbox.id, limit, filters = QuickFilters(unread = true))
                .map { it.copy(account = account) }
        }
    }
}

/**
 * Ask Rook [question] about [account]'s mail.
 *
 * Every step is the person's press of the button: nothing here runs from typing, and Enter
 * in the search box still only searches.
 */
internal fun askRook(job: RookJob<InboxAnswer>, scope: CoroutineScope, question: String, reader: InboxReader) {
    val config = Assistant.config()
    val asked = question.trim()
    job.start(scope, Assistant.ASK, config, Assistant.whyNot(Assistant.ASK, config), asked) {
        val sources = AskInbox.prepare(asked, reader)
        if (sources.isEmpty()) {
            Prepared(null) { InboxAnswer(AskInbox.NOT_FOUND) }
        } else {
            Prepared(Llm.packet(config.model, AskInbox.system(), AskInbox.user(asked, sources))) { reply ->
                AskInbox.check(reply.orEmpty(), sources)
            }
        }
    }
}

/**
 * Under the search box: the offer to ask Rook, and the answer once there is one.
 *
 * Offered two ways. When the text reads like a question the button is plain to see, with a
 * line saying why; otherwise there is a quiet "Ask Rook instead" for somebody who meant a
 * question and typed it like a search. Either way it is a button, never Enter: Enter has
 * always searched, and a key that sometimes spends money is a key nobody can trust.
 *
 * Hidden completely while the assistant is switched off, the same as the Summarise card.
 */
@Composable
internal fun AskRookOffer(
    query: String,
    job: RookJob<InboxAnswer>,
    onAsk: () -> Unit,
    onOpen: (Summary) -> Unit,
) {
    val typed = query.trim()
    val off = remember(typed) { Assistant.config().mode == AssistantMode.OFF }
    RookJobConsent(job)
    if (off) return
    val mine = job.forKey == typed && typed.isNotEmpty()
    val answer = job.result?.takeIf { mine }
    val failure = job.failure?.takeIf { mine }
    val question = remember(typed) { looksLikeQuestion(typed) }

    if (typed.isNotEmpty() && answer == null && !(job.running && mine)) {
        if (question) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "This reads like a question.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = onAsk,
                    enabled = !job.running,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(30.dp),
                ) { Text("Ask Rook", style = MaterialTheme.typography.labelMedium) }
            }
        } else {
            TextButton(
                onClick = onAsk,
                enabled = !job.running,
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.height(28.dp),
            ) { Text("Ask Rook instead", style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (job.running && mine) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RookAvatar(size = 18.dp, working = true)
            Spacer(Modifier.width(8.dp))
            Text("Rook is looking.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
    failure?.let { (title, detail) ->
        FaultText(title, detail, Modifier.padding(top = 6.dp))
    }
    answer?.let { InboxAnswerCard(it, onOpen, onClose = { job.clear() }) }
}

/**
 * The answer, and the messages it came from as links.
 *
 * Its own card with Rook's name on it, like the thread summary, so an answer nobody wrote
 * is not mistaken for mail. When the answer misquoted its source the card says so and
 * shows the source's own sentence instead, naming what did not match: the reader should
 * never have to wonder whether a figure on screen is one the sender actually wrote.
 */
@Composable
private fun InboxAnswerCard(answer: InboxAnswer, onOpen: (Summary) -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RookAvatar(size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                "Rook",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(24.dp)) {
                Text("Close", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(4.dp))
        val quoted = answer.quotedFrom
        if (quoted != null) {
            Text(
                "Rook's answer did not match the message exactly, so here is what the message says:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            Text("\"${answer.text}\"", style = MaterialTheme.typography.bodyMedium)
            Text(
                "From ${quoted.from.ifBlank { quoted.fromEmail }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            Text(answer.text, style = MaterialTheme.typography.bodyMedium)
            if (answer.unverified.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Check these against the message, they are not in it word for word: " +
                        answer.unverified.joinToString(", ") + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (answer.sources.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("From", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            answer.sources.forEach { source -> SourceLink(source, onOpen) }
        }
    }
}

/** One message as a link: subject, then who, on one line each. */
@Composable
internal fun SourceLink(message: Summary, onOpen: (Summary) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable { onOpen(message) }
            .padding(vertical = 3.dp, horizontal = 2.dp),
    ) {
        Text(
            message.subject.ifBlank { "(no subject)" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            message.from.ifBlank { message.fromEmail },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
