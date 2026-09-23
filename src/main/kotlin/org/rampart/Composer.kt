package org.rampart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import java.awt.Desktop
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * A message on its way out, in the terms the writer used: addresses as they typed them,
 * a plain text body, and the two headers that decide whether a reply joins its thread or
 * starts a new one.
 *
 * Deliberately free of JMAP types. Turning this into an Email object is [Jmap]'s job, and
 * keeping the boundary here is what lets the composer be drawn and reviewed without a
 * server.
 */
@Serializable
data class Draft(
    val from: String,
    val to: String = "",
    val cc: String = "",
    val subject: String = "",
    val body: String = "",
    /** The Message-ID of what this answers. Absent on a new message. */
    val inReplyTo: String? = null,
    /** The thread so far, oldest first, as References is built. */
    val references: List<String> = emptyList(),
    /** Files already uploaded to the server, ready to be named on the way out. */
    val attachments: List<Attachment> = emptyList(),
    /**
     * The tracking pixel's HTML, or empty on the overwhelming majority of messages.
     *
     * Carried on the draft rather than passed alongside it because it has to survive the
     * whole way down to whichever backend builds the message, and an extra argument on
     * every send path is how one of them ends up forgetting it.
     */
    val trackingPixel: String = "",
    /**
     * The Message-ID to use, minted here rather than by the server.
     *
     * Only set when tracking is on, and then it is essential: the copy kept in Sent is a
     * different object from the one that was sent, and without the same Message-ID on both
     * the reply comes back and threads against nothing.
     */
    val messageId: String? = null,
    /** The sign-off, as it was appended to [body], so the HTML part can swap it out. */
    val textSignature: String = "",
    /** The same sign-off written as HTML, or empty when there is none. */
    val htmlSignature: String = "",
    /**
     * The HTML body as it was stored, sent unchanged until the text is edited.
     *
     * Empty on anything composed here. Set when a draft is reopened so a later save of
     * the subject or the recipients does not flatten formatting that only the HTML had.
     */
    val html: String = "",
    /**
     * Whether this answers something. Not derived from [inReplyTo]: a message with no
     * Message-ID of its own is still being replied to, and telling the writer otherwise
     * because a header was missing would be a lie about what they are doing.
     */
    val replying: Boolean = false,
    /**
     * Whether to ask the reader's client to confirm they opened it (RFC 8098).
     *
     * A request, not a tracker. Every client is free to ignore it and most people's does,
     * which is the honest version of the same question a tracking pixel asks without
     * asking. Off unless it is turned on for this message.
     */
    val receipt: Boolean = false,
    /**
     * Whether this message goes out tracked. Off unless somebody said so on this message.
     *
     * Separate from [trackingPixel]: this is the intention, that is the machinery. The id
     * is minted at send time, so the composer can carry the decision around without one.
     */
    val tracked: Boolean = false,
) {
    val recipients: List<String> get() = (parseAddressList(to) + parseAddressList(cc)).map { it.email }
}

/**
 * A reply to [summary], quoting [body] the way mail has quoted for forty years.
 *
 * The threading headers are the part that matters: In-Reply-To is the message being
 * answered and References is the conversation so far with that message appended. Get
 * either wrong and the reply shows up as a new conversation.
 *
 * With [all], everyone on the original is carried over: the sender and the original To
 * line become To, the original Cc stays Cc, and [mine] is dropped from both, because the
 * one thing nobody wants from Reply all is a copy of their own answer.
 */
internal fun replyTo(
    summary: Summary,
    body: Body?,
    from: String,
    all: Boolean = false,
    mine: Set<String> = emptySet(),
    exactOnly: Boolean = Settings.exactIdentitiesOnly(),
    delimiter: Char = Settings.subAddressDelimiter(),
): Draft {
    val original = plainTextOf(body)
    val quoted = original.trim().lineSequence().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    val subject = summary.subject.trim()
    val answered = body?.messageId?.firstOrNull()
    val ours = (mine + from).map { forMatching(it, delimiter) }.filterTo(mutableSetOf()) { it.isNotEmpty() }
    /*
     * **Reply-To wins over From, because that is what it is for.** A mailing list sets it to
     * the list, a ticketing system to the address that files the answer against the ticket,
     * a no-reply sender to the one address that is read. Answering the From address in any
     * of those sends the reply somewhere nobody looks, and the sender finds out rather than
     * you.
     */
    val replyToHeader = body?.replyTo.orEmpty().filter { it.isNotBlank() }
    // A reply to mail you sent yourself goes to the people you wrote to, not back to you.
    // Reply all keeps the Cc line as well. Reply-To still wins, because that header exists
    // to name a different place, including on your own message.
    val own = replyToHeader.isEmpty() &&
        countsAsMine(summary.fromEmail, mine + from, exactOnly, delimiter)
    val answerTo = when {
        replyToHeader.isNotEmpty() -> replyToHeader
        own -> body?.to.orEmpty().ifEmpty { listOf(summary.fromEmail) }
        else -> listOf(summary.fromEmail)
    }
    // Answering your own message is the case where dropping your own address leaves nobody
    // to send to, so the sender goes back in rather than the reply opening addressed to no one.
    val to = if (!all) {
        if (own) dedupe(answerTo, ours, mine + from, exactOnly, delimiter).ifEmpty { answerTo }
        else answerTo
    } else dedupe(answerTo + body?.to.orEmpty(), ours, mine, exactOnly, delimiter).ifEmpty { answerTo }
    val cc = if (!all) emptyList()
    else dedupe(body?.cc.orEmpty(), ours + to.map { forMatching(it, delimiter) }, mine, exactOnly, delimiter)
    return Draft(
        from = from,
        to = to.joinToString(", "),
        cc = cc.joinToString(", "),
        subject = if (subject.startsWith("Re:", ignoreCase = true)) subject else "Re: $subject",
        body = "\n\nOn ${summary.receivedAt.asLocalTime()}, ${summary.from} wrote:\n$quoted",
        inReplyTo = answered,
        references = body?.references.orEmpty() + listOfNotNull(answered),
        replying = true,
    )
}

/**
 * Whether Reply all would reach anyone Reply would not, which is the only reason to offer
 * the button. On the usual one-to-one message it would send exactly the same mail, and a
 * second button that does the same thing as the first is a button people click by mistake.
 */
internal fun hasOtherRecipients(
    summary: Summary,
    body: Body?,
    mine: Set<String>,
    exactOnly: Boolean = Settings.exactIdentitiesOnly(),
    delimiter: Char = Settings.subAddressDelimiter(),
): Boolean {
    val ours = (mine + summary.fromEmail).map { forMatching(it, delimiter) }.filter { it.isNotEmpty() }.toSet()
    return dedupe(body?.to.orEmpty() + body?.cc.orEmpty(), ours, mine, exactOnly, delimiter).isNotEmpty()
}

/**
 * Whether a bare Reply addresses everyone.
 *
 * [pinned] is the setting. Off, Reply goes to the sender, which is what it has always
 * done, and [others] only decides whether Reply all is offered beside it. Turning Reply
 * itself into Reply all whenever [others] is true would make the two buttons send the
 * same mail. On, the bare Reply is Reply all even when [others] is false: a one-to-one
 * message has nobody else to add, and [replyTo] already leaves the sender as the only
 * recipient.
 */
internal fun bareReplyAll(pinned: Boolean, others: Boolean): Boolean {
    if (others && !pinned) return false
    return pinned
}

/**
 * Addresses in the order they were written, without repeats and without [exclude].
 *
 * Compared through [forMatching], so two spellings of one mailbox count as one. The address
 * kept is the one the sender actually wrote.
 */
private fun dedupe(
    addresses: List<String>,
    exclude: Set<String>,
    identities: Collection<String> = emptyList(),
    exactOnly: Boolean = true,
    delimiter: Char = '+',
): List<String> {
    val seen = exclude.toMutableSet()
    return addresses.mapNotNull { address ->
        val key = forMatching(address, delimiter)
        if (key.isEmpty() || !seen.add(key)) return@mapNotNull null
        // Counts as you: a configured identity, or, unless exact mode is on, any address
        // on a domain you already send as. Reply all does not copy you in on either.
        if (countsAsMine(address, identities, exactOnly, delimiter)) return@mapNotNull null
        address.trim()
    }
}

/** Whether [address] sits on a domain one of [identities] already uses. */
private fun domainOwned(address: String, identities: Collection<String>): Boolean {
    val domain = domainOf(address)
    return domain.isNotBlank() && identities.any { domainOf(it) == domain }
}

/** "Fwd:" once. A message that already says it is a forward does not get a second one. */
internal fun forwardedSubject(subject: String): String {
    val trimmed = subject.trim()
    return if (trimmed.startsWith("Fwd:", ignoreCase = true)) trimmed else "Fwd: $trimmed"
}

/**
 * A forward of [summary]. The original is quoted under a header naming who sent it and
 * when, which is the convention every mail client renders the same way.
 *
 * No threading headers: a forward starts a new conversation with someone who was not in
 * the old one, and attaching it to a thread they cannot see is worse than not threading.
 */
internal fun forwardOf(summary: Summary, body: Body?, from: String): Draft {
    return Draft(
        from = from,
        subject = forwardedSubject(summary.subject),
        body = buildString {
            append("\n\n---------- Forwarded message ----------\n")
            append("From: ${summary.from} <${summary.fromEmail}>\n")
            append("Date: ${summary.receivedAt.asLocalTime()}\n")
            append("Subject: ${summary.subject.trim()}\n\n")
            append(plainTextOf(body))
        },
    )
}

/**
 * A forward that carries the original as a file rather than quoting it.
 *
 * The body stays empty on purpose: the message is the attachment, and putting the same
 * text on the page as well is the forward this is the alternative to. The subject follows
 * [forwardedSubject], so a message that is already a forward is not labelled twice.
 */
internal fun forwardAsAttachment(summary: Summary, from: String, file: Attachment): Draft = Draft(
    from = from,
    subject = forwardedSubject(summary.subject),
    body = "",
    attachments = listOf(file),
)

/** The message as text, whichever way it arrived, so a quote never carries markup. */
internal fun plainTextOf(body: Body?): String =
    body?.text
        ?: body?.html?.let { renderHtml(it, Color.Unspecified, Color.Unspecified) {}.text.text }
        ?: ""

/**
 * The panel the composer sits in, over the mail rather than instead of it.
 *
 * Its own surface, a border and a shadow, because no one of the three is enough across
 * eighteen themes. It was drawn in `surface`, which is the colour of the mail behind it,
 * and a drop shadow is a dark smudge nobody can see on a dark background, so what you got
 * was text floating over other text with no edge anywhere.
 *
 * `surfaceBright` is the lightest surface a theme has, which is what a compose window is in
 * Gmail: the panel stands off the page rather than sitting in it. The hairline does the
 * work in a dark theme and the shadow does it in a light one.
 *
 * Shared with the screenshot harness on purpose. This frame used to be written out twice,
 * once here and once in the test, which is exactly why a picture of it never showed that
 * the panel had no edge.
 */
/**
 * Where somebody is sent to set the companion server up.
 *
 * The published page rather than `docs/` or `server/` in the source tree: the reader has
 * installed an application, not cloned a repository, and a path they cannot open is the
 * same as no link at all.
 */
private const val TRACKER_SETUP = "https://github.com/thejdubb02/rampart/blob/main/server/README.md"

@Composable
internal fun ComposerFrame(full: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        shape = if (full) RectangleShape else MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceBright,
        shadowElevation = if (full) 0.dp else 16.dp,
        border = if (full) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

/**
 * The compose pane. Owns what the writer has typed and nothing else: sending, saving and
 * closing are the caller's, so the same pane serves a new message, a reply and a reopened
 * draft without knowing which it is.
 */
@Composable
internal fun Composer(
    identities: List<Identity>,
    initial: Draft,
    sending: Boolean,
    error: String?,
    /** The technical line under [error], when the failure came from the server. */
    errorDetail: String? = null,
    onDiscard: () -> Unit,
    onSend: (Draft) -> Unit,
    /** Writes the draft to the server. Null while there is nowhere to write it. */
    onSave: (suspend (Draft) -> Unit)? = null,
    /** Puts the chosen files on the server and says what to attach. Null when it cannot. */
    onAttach: (suspend (List<Path>) -> List<Attachment>)? = null,
    /**
     * Writes one attached file to a temp file so its row can be dragged out.
     * Null where there is nowhere to fetch it from, and the row then only removes.
     */
    onDragFile: ((Attachment) -> java.io.File)? = null,
    /** Addresses to offer while a recipient is being typed. */
    book: List<Person> = emptyList(),
    /** Filling the window rather than sitting in the corner of it. */
    full: Boolean = false,
    onFull: (Boolean) -> Unit = {},
    /** Whether a companion server is set up, which is the only thing that shows the toggle. */
    trackingReady: Boolean = false,
    /** Whether tracking was last on for this recipient's domain. */
    trackedBefore: (String) -> Boolean = { false },
    /** Thread messages context for AI compose replies. */
    replyContext: List<Turn> = emptyList(),
    /** Whose mailbox this is, for [Assistant.whyNot]'s denied-folder check. */
    account: String? = null,
    /** The folder [replyContext] was read from, for the same check. */
    folder: String? = null,
    /**
     * Hold the message and send it later, instead of now.
     *
     * Null hides the control, so a caller that only sends is unchanged.
     */
    onSchedule: ((Draft, sendAt: Long) -> Unit)? = null,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    /*
     * Tracking follows the recipient, once, and then stops arguing.
     *
     * Switched on as soon as the first recipient is one it was on for last time, and never
     * again after somebody has touched the toggle on this message: a decision that keeps
     * being overridden as the To line is edited is one nobody trusts. `chosen` is what
     * remembers that they touched it.
     */
    var chosen by remember(initial) { mutableStateOf(initial.tracked) }
    /** Whether the reader has asked why the tracking toggle does nothing. */
    var needsTracker by remember { mutableStateOf(false) }
    val firstRecipient = draft.recipients.firstOrNull().orEmpty()
    LaunchedEffect(firstRecipient, trackingReady) {
        if (!chosen && trackingReady && firstRecipient.isNotBlank()) {
            draft = draft.copy(tracked = trackedBefore(trackingDomain(firstRecipient)))
        }
    }
    // The selection has to live here, not be derived from the string, or every formatting
    // button would have to guess where the caret is. Kept beside draft.body rather than
    // replacing it, because the draft is what gets saved and sent.
    var body by remember(initial) { mutableStateOf(TextFieldValue(initial.body)) }
    // Only when the change came from somewhere else, such as the sign-off being appended.
    // Typing sets both, so they already agree and the caret is left alone.
    if (body.text != draft.body) body = body.copy(text = draft.body)
    /*
     * A bounded undo/redo stack for the body, kept outside Compose state because it is a
     * history of edits, not a value to redraw from. historyTick exists only so the Undo and
     * Redo buttons have something to key a recomposition off: Compose cannot see a plain
     * object mutate on its own, and without this their enabled state would go stale the
     * moment something was pushed onto or popped off the stack.
     */
    val history = remember(initial) { UndoHistory() }
    var historyTick by remember(initial) { mutableStateOf(0) }
    var showCc by remember(initial) { mutableStateOf(initial.cc.isNotEmpty()) }
    var pickingIdentity by remember { mutableStateOf(false) }
    var saveState by remember(initial) { mutableStateOf("") }
    var saveDetail by remember(initial) { mutableStateOf<String?>(null) }
    var attaching by remember(initial) { mutableStateOf(false) }
    /** Thumbnails of pictures picked from disk, by blob id. Not recomputed while typing. */
    var previews by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var attachError by remember(initial) { mutableStateOf<String?>(null) }
    var attachDetail by remember(initial) { mutableStateOf<String?>(null) }
    var warning by remember(initial) { mutableStateOf<String?>(null) }
    /** The time a warning is asking about, or null when the warning is for Send. */
    var heldSendAt by remember(initial) { mutableStateOf<Long?>(null) }
    var scheduleMenu by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var showPromptBar by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var runningRefine by remember { mutableStateOf<String?>(null) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiDetail by remember { mutableStateOf<String?>(null) }
    var composePacket by remember { mutableStateOf<String?>(null) }
    var composeAgreed by remember { mutableStateOf(Assistant.agreed(Assistant.COMPOSE)) }
    val firstField = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    /*
     * Saving as you type, with the pause built out of the effect rather than a timer: a
     * keystroke changes `draft`, which cancels the effect mid-delay and starts it again, so
     * only a real pause reaches the server. Typing a paragraph is one save, not two hundred.
     *
     * Nothing is written until something has been typed. Opening a reply and closing it
     * again should leave no trace, and an untouched draft in the folder is exactly the kind
     * of litter that makes people stop trusting a Drafts folder.
     */
    // Keyed on sending too, not just draft: without it, a save already sitting in its
    // debounce delay when Send is clicked keeps running underneath the send in flight,
    // and lands after the message is gone, autosaving a draft of a message already sent.
    // Restarting this effect the moment sending flips true cancels that delay outright.
    // A save that has already been handed to onSave is not this effect's to abandon.
    // The caller finishes it and keeps the id, or the next save orphans a copy.
    LaunchedEffect(draft, sending) {
        if (onSave == null || draft == initial || sending) return@LaunchedEffect
        delay(1200)
        saveState = "Saving"
        saveDetail = null
        saveState = try {
            onSave(draft)
            "Saved"
        } catch (e: Exception) {
            // Said plainly and left on screen. A draft that silently failed to save is the
            // one thing worse than no autosave at all. The server's own wording sits under
            // the sentence, not in it.
            saveDetail = faultDetail(e, "Could not save the draft.")
            "Could not save the draft."
        }
    }

    /*
     * Ctrl+Enter sends and Esc closes, on the same conditions as the two buttons: a shortcut
     * that can do what the button cannot is how a message goes out with no recipient, or
     * goes out twice while the first one is still being sent.
     *
     * A preview handler rather than a plain one, because the focus is inside a text field
     * and a field that takes Enter would swallow it first.
     */
    /*
     * Both ways of sending go through here, so the button and Ctrl+Enter cannot end up
     * asking different questions. A warning is a question with a Send anyway on it, never
     * a refusal: a client that will not send a message with no subject is one people learn
     * to fight, and the fight is won by turning the warnings off.
     */
    /*
     * Both Send and Schedule ask the same questions first. A missing subject is
     * still worth asking about when the message will not go for an hour.
     */
    fun askThen(sendAt: Long?, go: () -> Unit) {
        if (sending || draft.recipients.isEmpty()) return
        val question = askBeforeSend(
            draft.subject,
            draft.body,
            draft.attachments.size,
            Settings.confirmBeforeSend(),
        )
        if (question == null) {
            go()
        } else {
            heldSendAt = sendAt
            warning = question
        }
    }

    fun send() = askThen(null) { onSend(draft) }

    fun schedule(at: Long) = askThen(at) { onSchedule?.invoke(draft, at) }

    /*
     * Every change to the body goes through one of these three, which is what lets undo and
     * redo behave the same way whether the edit came from typing, a toolbar button, or a
     * keyboard shortcut, instead of three separate call sites that could each forget to
     * record one.
     */
    fun apply(next: TextFieldValue) {
        if (sending) return
        history.push(body)
        body = next
        draft = draft.withBody(next.text, initial)
        historyTick++
    }

    fun edited(next: TextFieldValue) {
        // A pure selection change, such as clicking to move the caret, is not an edit and
        // must not push a step Undo would then have to silently skip over.
        if (next.text != body.text) {
            history.type(body, next)
            historyTick++
        }
        body = next
        draft = draft.withBody(next.text, initial)
    }

    fun restore(next: TextFieldValue) {
        body = next
        draft = draft.withBody(next.text, initial)
        historyTick++
    }

    fun runComposeDraft(desc: String) {
        val config = Assistant.config()
        scope.launch {
            running = true
            aiError = null
            aiDetail = null
            try {
                val fullPacket = Llm.packet(config.model, ComposeDraft.system(), ComposeDraft.user(desc, replyContext))
                val reply = withContext(Dispatchers.IO) {
                    Llm.ask(config, Secrets.loadNamed(Assistant.KEY), fullPacket)
                }
                Assistant.record(Assistant.COMPOSE, reply.tokensIn, reply.tokensOut, config)
                apply(TextFieldValue(reply.text))
            } catch (e: Exception) {
                val title = "The assistant could not write that."
                aiError = title
                aiDetail = faultDetail(e, title)
            } finally {
                running = false
            }
        }
    }

    fun runRefine(instruction: String) {
        val config = Assistant.config()
        val currentBody = body.text
        if (currentBody.isBlank()) return
        // Same gate as the first draft: replyContext came from [folder], and a refine call
        // sends the current body (which may itself still carry that content) same as a draft
        // does, so a folder marked never-leaves has to stop this too, not just the first call.
        val why = Assistant.whyNot(Assistant.COMPOSE, config, account, folder)
        if (why != null) {
            aiError = why
            aiDetail = null
            return
        }
        scope.launch {
            runningRefine = instruction
            aiError = null
            aiDetail = null
            try {
                val fullPacket = ComposeDraft.refinePacket(config.model, currentBody, instruction)
                val reply = withContext(Dispatchers.IO) {
                    Llm.ask(config, Secrets.loadNamed(Assistant.KEY), fullPacket)
                }
                Assistant.record(Assistant.COMPOSE, reply.tokensIn, reply.tokensOut, config)
                apply(TextFieldValue(reply.text))
            } catch (e: Exception) {
                val title = "The assistant could not write that."
                aiError = title
                aiDetail = faultDetail(e, title)
            } finally {
                runningRefine = null
            }
        }
    }

    fun format(before: String, after: String): Boolean {
        if (sending) return false
        apply(wrapSelection(body, before, after))
        return true
    }

    fun typed(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when {
            event.isCtrlPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                send()
                true
            }
            event.key == Key.Escape -> {
                if (!sending) onDiscard()
                true
            }
            event.isCtrlPressed && event.key == Key.B -> format("**", "**")
            event.isCtrlPressed && event.key == Key.I -> format("*", "*")
            event.isCtrlPressed && event.key == Key.K -> format("[", "](https://)")
            event.isCtrlPressed && event.key == Key.Z && !event.isShiftPressed -> {
                history.undo(body)?.let(::restore)
                true
            }
            event.isCtrlPressed && event.key == Key.Z && event.isShiftPressed -> {
                history.redo(body)?.let(::restore)
                true
            }
            else -> false
        }
    }

    Surface(
        Modifier.fillMaxSize().onPreviewKeyEvent(::typed),
        // The lightest surface the theme has, matching the panel this sits inside. Left as
        // `surface` it painted over that panel with the colour of the mail behind it.
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column(Modifier.fillMaxSize()) {
            // A title bar, a shade off the panel it caps, the way a compose window has one
            // in every webmail. It is what tells you where the thing you are writing starts.
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (draft.replying) "Reply" else "New message",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (saveState.isNotEmpty()) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                saveState,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (saveDetail != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                            )
                            saveDetail?.let { detail ->
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                    if (onAttach != null) {
                        TextButton(
                            onClick = {
                                val chosen = pickFiles()
                                if (chosen.isNotEmpty()) {
                                    scope.launch {
                                        attaching = true
                                        attachError = null
                                        attachDetail = null
                                        try {
                                            val added = onAttach(chosen)
                                            previews = previews + withContext(Dispatchers.IO) {
                                                pickedPreviews(chosen, added)
                                            }
                                            draft = draft.copy(attachments = draft.attachments + added)
                                        } catch (e: Exception) {
                                            attachError = "That file could not be attached."
                                            attachDetail = faultDetail(e, "That file could not be attached.")
                                        } finally {
                                            attaching = false
                                        }
                                    }
                                }
                            },
                            enabled = !sending && !attaching,
                        ) { Text(if (attaching) "Attaching" else "Attach", maxLines = 1) }
                    }
                    TextButton(onClick = { draft = draft.copy(receipt = !draft.receipt) }) {
                        Text(if (draft.receipt) "Receipt on" else "Receipt", maxLines = 1)
                    }
                    /*
                     * Always here, and off where no server has been set up, rather than
                     * missing. A toggle that silently does nothing would mean every message
                     * going out believing it was tracked, and a toggle that is simply absent
                     * reads as a feature Rampart does not have rather than one that needs a
                     * server. So the unconfigured case is a button that says why when it is
                     * pressed, which is also why this is not a disabled button: a disabled
                     * button cannot be asked anything.
                     */
                    TextButton(
                        onClick = {
                            if (trackingReady) {
                                chosen = true
                                draft = draft.copy(tracked = !draft.tracked)
                            } else {
                                needsTracker = !needsTracker
                            }
                        },
                    ) {
                        Text(
                            if (draft.tracked && trackingReady) "Tracking on" else "Track",
                            maxLines = 1,
                            color = when {
                                draft.tracked && trackingReady -> MaterialTheme.colorScheme.primary
                                trackingReady -> MaterialTheme.colorScheme.onSurfaceVariant
                                // Muted further than an ordinary button, so it reads as
                                // unavailable before it is pressed rather than after.
                                else -> MaterialTheme.colorScheme.outline
                            },
                        )
                    }
                    // Next to Discard rather than in the corner, because at panel width
                    // a title bar of its own would cost a line of the message.
                    TextButton(onClick = { onFull(!full) }) {
                        Text(if (full) "Shrink" else "Full screen", maxLines = 1)
                    }
                    TextButton(onClick = onDiscard, enabled = !sending) { Text("Discard", maxLines = 1) }
                    // maxLines = 1 on every label in this row: none of them had it, so at a
                    // dragged-narrow panel width Compose was free to wrap each one, Send
                    // included, one letter per line instead of just crowding the row.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = ::send,
                            enabled = !sending && draft.recipients.isNotEmpty(),
                        ) { Text(if (sending) "Sending" else "Send", maxLines = 1) }
                        if (onSchedule != null) {
                            Box {
                                IconButton(
                                    onClick = { scheduleMenu = true },
                                    enabled = !sending && draft.recipients.isNotEmpty(),
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        RampartIcons.Expand,
                                        contentDescription = "Schedule send",
                                        modifier = Modifier.size(16.dp).rotate(90f),
                                    )
                                }
                                DropdownMenu(scheduleMenu, onDismissRequest = { scheduleMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Schedule...") },
                                        onClick = {
                                            scheduleMenu = false
                                            showSchedule = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            /*
             * Only after the toggle has been pressed, so a composer for somebody who does not
             * want open tracking is not carrying a permanent line about a server they will
             * never run. Empty is the normal case and should not look like something missing.
             */
            if (needsTracker && !trackingReady) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Knowing whether a message was opened needs the companion server, " +
                            "because a picture has to be fetched from somewhere and this " +
                            "application is not somewhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    // The public setup page rather than a path inside the repository, which is
                    // not something somebody who installed the application can open.
                    TextButton(onClick = {
                        runCatching { Desktop.getDesktop().browse(URI(TRACKER_SETUP)) }
                    }) { Text("Set it up") }
                }
            }
            HorizontalDivider()

            if (error != null) {
                FaultText(
                    error,
                    errorDetail,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    titleColor = MaterialTheme.colorScheme.onErrorContainer,
                    detailColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                )
            }

            // One identity is not a choice, so it is shown as a line of text rather than a
            // control that does nothing when clicked.
            Field(label = "From") {
                if (identities.size <= 1) {
                    Text(draft.from, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Box {
                        TextButton(onClick = { pickingIdentity = true }, enabled = !sending) { Text(draft.from) }
                        DropdownMenu(pickingIdentity, onDismissRequest = { pickingIdentity = false }) {
                            identities.forEach { identity ->
                                DropdownMenuItem(
                                    text = { Text(identity.email) },
                                    onClick = {
                                        draft = withFrom(
                                            draft,
                                            identity.email,
                                            identities,
                                            Settings.signatureAboveQuote(),
                                        )
                                        pickingIdentity = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider()

            Field(label = "To", action = if (showCc) null else ("Cc" to { showCc = true })) {
                Entry(draft.to, sending, firstField, book) { draft = draft.copy(to = it) }
            }
            HorizontalDivider()

            if (showCc) {
                Field(label = "Cc") { Entry(draft.cc, sending, book = book) { draft = draft.copy(cc = it) } }
                HorizontalDivider()
            }

            Field(label = "Subject") {
                Entry(draft.subject, sending) { draft = draft.copy(subject = it) }
            }
            HorizontalDivider()

            attachError?.let { title ->
                FaultText(
                    title,
                    attachDetail,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (draft.attachments.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    draft.attachments.forEach { file ->
                        val dragOut = onDragFile
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp).then(
                                if (dragOut == null) Modifier
                                else Modifier.dragAttachmentOut(
                                    prepare = { dragOut(file) },
                                    onFailed = { detail ->
                                        attachError = "That file could not be dragged out."
                                        attachDetail = detail.takeIf {
                                            it.isNotBlank() && !it.equals(attachError, ignoreCase = true)
                                        }
                                    },
                                ),
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FileMark(
                                name = safeFileName(file.name),
                                type = file.type,
                                picture = previews[file.blobId],
                                glyphForImage = true,
                            )
                            Text(
                                safeFileName(file.name),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                humanSize(file.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    // The blob stays on the server and expires on its own.
                                    // Nothing else references it, so there is nothing to clean up.
                                    previews = previews - file.blobId
                                    draft = draft.copy(attachments = draft.attachments - file)
                                },
                                enabled = !sending,
                            ) { Text("Remove") }
                        }
                    }
                }
                HorizontalDivider()
            }

            if (!sending) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Recomputed on every recomposition body triggers, which is every
                    // keystroke and every click that moves the caret: cheap, since it only
                    // looks at the one line the caret is on, and it is what lets a button
                    // read as pressed the moment the caret lands inside its marker.
                    val active = activeMarks(body)
                    val canUndo = remember(historyTick) { history.canUndo }
                    val canRedo = remember(historyTick) { history.canRedo }

                    ToolbarButton(RampartIcons.Bold, "Bold", active = "bold" in active) {
                        apply(wrapSelection(body, "**", "**"))
                    }
                    ToolbarButton(RampartIcons.Italic, "Italic", active = "italic" in active) {
                        apply(wrapSelection(body, "*", "*"))
                    }
                    ToolbarButton(RampartIcons.Underline, "Underline", active = "underline" in active) {
                        apply(wrapSelection(body, "__", "__"))
                    }
                    ToolbarButton(RampartIcons.Strikethrough, "Strikethrough", active = "strike" in active) {
                        apply(wrapSelection(body, "~~", "~~"))
                    }
                    ToolbarDivider()
                    ToolbarButton(RampartIcons.Heading1, "Heading 1", active = "h1" in active) {
                        apply(prefixLines(body, "# "))
                    }
                    ToolbarButton(RampartIcons.Heading2, "Heading 2", active = "h2" in active) {
                        apply(prefixLines(body, "## "))
                    }
                    ToolbarDivider()
                    ToolbarButton(RampartIcons.Bullets, "Bulleted list", active = "bullets" in active) {
                        apply(prefixLines(body, "- "))
                    }
                    ToolbarButton(RampartIcons.Numbers, "Numbered list", active = "numbers" in active) {
                        apply(prefixLines(body, "1. "))
                    }
                    ToolbarButton(RampartIcons.Quote, "Quote", active = "quote" in active) {
                        apply(prefixLines(body, "> "))
                    }
                    ToolbarButton(RampartIcons.Code, "Code", active = "code" in active) {
                        // A selection with a line break in it cannot become a single backtick
                        // pair, because markers never span a line break (spans(), in
                        // RichText.kt): it becomes a fenced block instead, the same three
                        // backticks somebody fluent in Markdown would have typed by hand.
                        val start = minOf(body.selection.start, body.selection.end).coerceIn(0, body.text.length)
                        val end = maxOf(body.selection.start, body.selection.end).coerceIn(start, body.text.length)
                        apply(
                            if (body.text.substring(start, end).contains("\n")) {
                                wrapSelection(body, "```\n", "\n```")
                            } else {
                                wrapSelection(body, "`", "`")
                            },
                        )
                    }
                    ToolbarDivider()
                    ToolbarButton(RampartIcons.AlignLeft, "Align left", active = "align-left" in active) {
                        apply(alignLines(body, null))
                    }
                    ToolbarButton(RampartIcons.AlignCenter, "Align centre", active = "align-center" in active) {
                        apply(alignLines(body, "center"))
                    }
                    ToolbarButton(RampartIcons.AlignRight, "Align right", active = "align-right" in active) {
                        apply(alignLines(body, "right"))
                    }
                    ToolbarDivider()
                    ToolbarButton(RampartIcons.Link, "Link") { apply(wrapSelection(body, "[", "](https://)")) }
                    ToolbarButton(RampartIcons.ClearFormat, "Clear formatting") { apply(clearFormatting(body)) }
                    ToolbarDivider()
                    ToolbarButton(RampartIcons.Undo, "Undo", enabled = canUndo) {
                        history.undo(body)?.let(::restore)
                    }
                    ToolbarButton(RampartIcons.Redo, "Redo", enabled = canRedo) {
                        history.redo(body)?.let(::restore)
                    }
                    ToolbarDivider()
                    ToolbarButton(RampartIcons.Write, "Help me write", active = showPromptBar) {
                        showPromptBar = !showPromptBar
                    }
                    ToolbarDivider()
                    var emoji by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { emoji = true }) { Text("Emoji") }
                        DropdownMenu(expanded = emoji, onDismissRequest = { emoji = false }) {
                            Column(
                                Modifier.width(320.dp).heightIn(max = 300.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                EMOJI.forEach { group ->
                                    Text(
                                        group.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(vertical = 6.dp),
                                    )
                                    // A plain wrapping row of characters. Compose has no
                                    // flow layout that is not experimental, and chunking a
                                    // fixed grid is both stable and eight lines shorter.
                                    group.emoji.chunked(10).forEach { line ->
                                        Row {
                                            line.forEach { one ->
                                                Text(
                                                    one,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    modifier = Modifier
                                                        .clip(MaterialTheme.shapes.small)
                                                        .clickable {
                                                            emoji = false
                                                            apply(insertAt(body, one))
                                                        }
                                                        .padding(4.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val templates = remember { Templates.read() }
                    if (templates.isNotEmpty()) {
                        var picking by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { picking = true }) { Text("Template") }
                            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                                templates.forEach { template ->
                                    DropdownMenuItem(
                                        text = { Text(template.name) },
                                        onClick = {
                                            picking = false
                                            val filled = fill(
                                                template,
                                                templateValues(draft.to, draft.from, draft.subject, book),
                                            )
                                            // Appended rather than substituted, so a
                                            // template dropped into a half-written reply
                                            // does not eat what is already there.
                                            val nextBody = if (draft.body.isBlank()) {
                                                filled.body
                                            } else {
                                                filled.body + "\n\n" + draft.body
                                            }
                                            draft = draft.copy(
                                                subject = draft.subject.ifBlank { filled.subject },
                                            ).withBody(nextBody, initial)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (onAttach != null) {
                        TextButton(
                            onClick = {
                                val chosen = pickFiles().firstOrNull()
                                if (chosen != null) {
                                    scope.launch {
                                        attaching = true
                                        attachError = null
                                        attachDetail = null
                                        try {
                                            val put = onAttach(listOf(chosen)).firstOrNull()
                                            if (put != null) {
                                                previews = previews + withContext(Dispatchers.IO) {
                                                    pickedPreviews(listOf(chosen), listOf(put))
                                                }
                                                val inline = put.copy(cid = cidFor(put.blobId), inline = true)
                                                draft = draft.copy(attachments = draft.attachments + inline)
                                                apply(insertAt(body, "![${put.name}](cid:${inline.cid})"))
                                            }
                                        } catch (e: Exception) {
                                            val title = "That picture could not be added."
                                            attachError = title
                                            attachDetail = faultDetail(e, title)
                                        } finally {
                                            attaching = false
                                        }
                                    }
                                }
                            },
                            enabled = !attaching,
                        ) { Text("Picture") }
                    }
                }
                if (showPromptBar) {
                    ComposePromptBar(
                        prompt = prompt,
                        onPromptChange = { prompt = it },
                        running = running,
                        onSubmit = {
                            val config = Assistant.config()
                            val why = Assistant.whyNot(Assistant.COMPOSE, config, account, folder)
                            if (why != null) {
                                aiError = why
                                aiDetail = null
                            } else if (!Assistant.agreed(Assistant.COMPOSE)) {
                                composePacket = Llm.packet(config.model, ComposeDraft.system(), ComposeDraft.user(prompt, replyContext))
                            } else {
                                runComposeDraft(prompt)
                            }
                        }
                    )
                    if (composeAgreed && body.text.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Refine:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            listOf("Formalize", "Elaborate", "Shorten").forEach { option ->
                                val runningThis = runningRefine == option
                                RefineChip(
                                    label = option,
                                    enabled = !running && runningRefine == null,
                                    running = runningThis,
                                    onClick = { runRefine(option) }
                                )
                            }
                        }
                    }
                    aiError?.let { title ->
                        FaultText(
                            title,
                            aiDetail,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
                HorizontalDivider()
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (sending) {
                    Spinner(Modifier.align(Alignment.Center))
                } else {
                    BasicTextField(
                        value = body,
                        onValueChange = ::edited,
                        visualTransformation = MarkupStyling,
                        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }

    warning?.let { question ->
        val scheduling = heldSendAt != null
        AlertDialog(
            onDismissRequest = {
                warning = null
                heldSendAt = null
            },
            text = { Text(question) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val at = heldSendAt
                        warning = null
                        heldSendAt = null
                        if (at != null) onSchedule?.invoke(draft, at) else onSend(draft)
                    },
                ) {
                    Text(
                        when {
                            scheduling && question == "Send this message?" -> "Schedule"
                            scheduling -> "Schedule anyway"
                            question == "Send this message?" -> "Send"
                            else -> "Send anyway"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    warning = null
                    heldSendAt = null
                }) { Text("Go back") }
            },
        )
    }

    if (showSchedule) {
        ScheduleDialog(
            onDismiss = { showSchedule = false },
            onConfirm = { at ->
                showSchedule = false
                schedule(at)
            },
        )
    }

    composePacket?.let { packet ->
        PacketViewer(
            packet = packet,
            agreed = composeAgreed,
            onSend = {
                composePacket = null
                runComposeDraft(prompt)
            },
            onAgree = {
                Assistant.agree(Assistant.COMPOSE)
                composeAgreed = true
            },
            onDismiss = { composePacket = null }
        )
    }

    LaunchedEffect(Unit) { firstField.requestFocus() }
}

/**
 * One button in the formatting row: an icon, an accessible label standing in for the word a
 * button used to carry, and a filled background when the marker it stands for is the one the
 * caret is sitting inside. A filled background rather than a filled icon, because the set in
 * `Icons.kt` is stroked throughout and a second, solid version of every glyph is a pack of
 * icons this file does not have.
 */
@Composable
private fun ToolbarButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(30.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(16.dp))
    }
}

/** Separates one group of toolbar buttons from the next, only as tall as the buttons either side of it. */
@Composable
private fun ToolbarDivider() {
    VerticalDivider(
        modifier = Modifier.height(20.dp).padding(horizontal = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** A labelled row, hairline separated, which is what a composer looks like when it is not a form. */
@Composable
private fun Field(
    label: String,
    action: Pair<String, () -> Unit>? = null,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        Box(Modifier.weight(1f)) { content() }
        if (action != null) {
            TextButton(onClick = action.second) {
                Text(action.first, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Entry(
    value: String,
    disabled: Boolean,
    focusRequester: FocusRequester? = null,
    /** Who to offer while typing. Empty for a field that is not a recipient field. */
    book: List<Person> = emptyList(),
    onChange: (String) -> Unit,
) {
    // Dismissed stays true until the field changes again, so Esc closes the list and
    // carries on typing rather than having it reappear on the next keystroke.
    var dismissed by remember { mutableStateOf(false) }
    val offers = if (book.isEmpty() || dismissed) emptyList() else suggest(typedRecipient(value), book)

    fun choose(person: Person) {
        dismissed = true
        onChange(completeRecipient(value, person.email))
    }

    Box {
        BasicTextField(
            value = value,
            onValueChange = {
                dismissed = false
                onChange(it)
            },
            enabled = !disabled,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    // Enter takes the first offer, which is what the ranking is for. Esc
                    // puts the list away. Both only while there is a list: a field that
                    // swallows Esc with nothing open is a field you cannot get out of.
                    when {
                        offers.isEmpty() || event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Enter || event.key == Key.Tab -> {
                            choose(offers.first())
                            true
                        }
                        event.key == Key.Escape -> {
                            dismissed = true
                            true
                        }
                        else -> false
                    }
                },
        )
        DropdownMenu(
            expanded = offers.isNotEmpty(),
            onDismissRequest = { dismissed = true },
            // Never takes the focus, or every keystroke would move it out of the field.
            properties = PopupProperties(focusable = false),
        ) {
            offers.forEach { person ->
                DropdownMenuItem(
                    text = {
                        Column {
                            if (person.name.isNotBlank()) {
                                Text(person.name, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                person.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    },
                    onClick = { choose(person) },
                )
            }
        }
    }
}

private val SCHEDULE_CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.UK)

/**
 * A few named times, or a date and a clock the person types.
 *
 * The sentence at the top is the limitation, said where the choice is made: Rampart
 * sends the message itself, so a time that arrives while it is closed waits until
 * the next time it is opened. The draft is already in Drafts either way.
 */
@Composable
private fun ScheduleDialog(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val now = remember { ZonedDateTime.now() }
    val hour = remember(now) { inOneHour(now) }
    val evening = remember(now) { thisEvening(now) }
    val morning = remember(now) { tomorrowMorning(now) }
    var date by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule send") },
        text = {
            Column(Modifier.widthIn(min = 300.dp, max = 440.dp)) {
                Text(
                    "Rampart sends this when it is open. If it is closed at that time, " +
                        "the message stays in Drafts until Rampart is opened again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                ScheduleChoice("In 1 hour", hour, onConfirm)
                ScheduleChoice("This evening", evening, onConfirm)
                ScheduleChoice("Tomorrow morning", morning, onConfirm)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Or a date and time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(6.dp))
                ClockField(date, "yyyy-MM-dd") {
                    date = it
                    problem = null
                }
                Spacer(Modifier.height(6.dp))
                ClockField(time, "HH:mm") {
                    time = it
                    problem = null
                }
                problem?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val parsed = parseSchedule(date, time, ZonedDateTime.now())) {
                    is ScheduleWhen.At -> onConfirm(parsed.millis)
                    is ScheduleWhen.Problem -> problem = parsed.message
                }
            }) { Text("Schedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScheduleChoice(label: String, at: ZonedDateTime, onPick: (Long) -> Unit) {
    TextButton(onClick = { onPick(at.toInstant().toEpochMilli()) }, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label)
            Text(
                at.format(SCHEDULE_CLOCK),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** A one-line box, the same shape Settings uses, so a dialog does not invent a second kind. */
@Composable
private fun ClockField(value: String, hint: String, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(36.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A draft from the Drafts folder, put back the way it was written.
 *
 * Attachments, In-Reply-To and References come with it. When the draft has an HTML body,
 * that HTML is what a later save sends until the text itself is edited, and the composer
 * is filled with the same markup it would have held if the draft had been written here.
 * Inline pictures stay out of the file list: they belong to the body, not to the files
 * the writer attached.
 */
internal fun draftOf(
    summary: Summary,
    body: Body?,
    from: String,
    attachments: List<Attachment> = emptyList(),
): Draft {
    val html = body?.html?.takeIf { it.isNotBlank() }
    val restored = if (html == null) Restored(body?.text.orEmpty(), "", "") else restoreHtmlBody(html, body?.text)
    return Draft(
        from = from,
        to = body?.to.orEmpty().joinToString(", "),
        cc = body?.cc.orEmpty().joinToString(", "),
        subject = summary.subject,
        body = restored.body,
        inReplyTo = body?.inReplyTo?.firstOrNull(),
        references = body?.references.orEmpty(),
        attachments = attachments.filterNot { it.inline },
        textSignature = restored.textSignature,
        htmlSignature = restored.htmlSignature,
        html = html.orEmpty(),
        replying = false,
    )
}

private data class Restored(val body: String, val textSignature: String, val htmlSignature: String)

/**
 * The sign-off pulled back out of a stored draft, and the message as markup.
 *
 * The plain part is where the `-- ` line is. The HTML part is where the formatting is.
 * When the two can be lined up, the sign-off goes back into [textSignature] and
 * [htmlSignature] so the next save swaps them the same way a new message does.
 * When they cannot, the plain part is what the composer shows, separator and all, so
 * opening the draft does not decide it still needs a sign-off and write a second one.
 */
private fun restoreHtmlBody(html: String, text: String?): Restored {
    val plain = text?.takeIf { it.isNotBlank() }
    val split = plain?.let(::signatureOutsideQuote)
    if (split == null || split.text.isBlank()) return Restored(htmlToMarkup(html), "", "")
    val (messageHtml, sigHtml) = peelHtmlSignature(html, split.text)
    if (sigHtml.isBlank()) return Restored(plain, split.text, "")
    val placed = signed(Draft(from = "", body = htmlToMarkup(messageHtml)), split.text, sigHtml, split.above)
    return Restored(placed.body, placed.textSignature, placed.htmlSignature)
}

private data class OwnSignOff(val text: String, val above: Boolean)

/** The sign-off that belongs to this draft, which is the one outside the quoted part. */
private fun signatureOutsideQuote(text: String): OwnSignOff? {
    val quote = quoteStart(text)
    val head = if (quote < 0) text else text.take(quote)
    val headAt = head.lines().indexOf("-- ")
    if (headAt >= 0) {
        val sig = head.lines().drop(headAt + 1).joinToString("\n").trimEnd()
        if (sig.isNotBlank()) return OwnSignOff(sig, above = quote >= 0)
    }
    if (quote < 0) return null
    val first = text.substring(quote).lineSequence().firstOrNull().orEmpty()
    if (first.contains("Forwarded message")) return null
    val after = text.substring(quote).lines().drop(1)
    val below = after.indexOf("-- ")
    if (below < 0) return null
    val sig = after.drop(below + 1).joinToString("\n").trimEnd()
    if (sig.isBlank()) return null
    return OwnSignOff(sig, above = false)
}

/**
 * The elements whose text is the sign-off, lifted out of [html].
 *
 * Returned as the message HTML with those elements gone, and the sign-off HTML itself.
 * Nothing is lifted when no run of elements reads as the sign-off, rather than guessing
 * a cut in the middle of a sentence.
 */
internal fun peelHtmlSignature(html: String, signature: String): Pair<String, String> {
    val want = normalizeSignature(signature)
    if (want.isEmpty()) return html to ""
    val doc = Jsoup.parseBodyFragment(html)
    val kids = doc.body().children()
    if (kids.isEmpty()) return html to ""
    for (start in kids.indices) {
        val acc = StringBuilder()
        for (end in start until kids.size) {
            if (acc.isNotEmpty()) acc.append(' ')
            acc.append(kids[end].text())
            val got = normalizeSignature(acc.toString())
            if (got == want) {
                val sig = (start..end).joinToString("") { kids[it].outerHtml() }
                for (i in end downTo start) kids[i].remove()
                return doc.body().html() to sig
            }
            if (got.length > want.length) break
        }
    }
    return html to ""
}

private fun normalizeSignature(value: String) = value.replace(Regex("\\s+"), " ").trim()

/** HTML from a stored draft, as the markup the composer edits. */
internal fun htmlToMarkup(html: String): String {
    val doc = Jsoup.parseBodyFragment(html)
    doc.select("script, style, noscript").remove()
    return renderChildren(doc.body()).trim()
}

private fun renderChildren(el: Element): String = el.childNodes().joinToString("") { renderNode(it) }

private fun renderNode(node: Node): String = when (node) {
    is TextNode -> node.wholeText
    is Element -> renderElement(node)
    else -> ""
}

private fun renderElement(el: Element): String {
    val tag = el.normalName()
    return when (tag) {
        "br" -> "\n"
        "b", "strong" -> wrapMarker("**", renderChildren(el))
        "i", "em" -> wrapMarker("*", renderChildren(el))
        "u" -> wrapMarker("__", renderChildren(el))
        "s", "strike", "del" -> wrapMarker("~~", renderChildren(el))
        "code" -> if (el.parent()?.normalName() == "pre") renderChildren(el) else wrapMarker("`", renderChildren(el))
        "a" -> {
            val href = el.attr("href").trim()
            val label = renderChildren(el).ifBlank { href }
            if (href.isBlank()) label else "[$label]($href)"
        }
        "img" -> {
            val src = el.attr("src").trim()
            if (src.isBlank()) "" else "![${el.attr("alt").ifBlank { "image" }}]($src)"
        }
        "h1" -> "# " + renderChildren(el).trim() + "\n"
        "h2", "h3", "h4", "h5", "h6" -> "## " + renderChildren(el).trim() + "\n"
        "blockquote" -> renderChildren(el).trim().lines().joinToString("\n") { line ->
            if (line.isBlank()) ">" else "> $line"
        } + "\n"
        "ul" -> el.children().joinToString("\n") { "- " + renderChildren(it).trim() } + "\n"
        "ol" -> el.children().mapIndexed { index, child ->
            "${index + 1}. " + renderChildren(child).trim()
        }.joinToString("\n") + "\n"
        "pre" -> "```\n" + el.wholeText().trim('\n') + "\n```\n"
        "p", "div", "tr" -> {
            val inner = renderChildren(el).trimEnd()
            if (inner.isEmpty()) "\n" else inner + "\n"
        }
        "td", "th" -> renderChildren(el).trim() + " "
        else -> renderChildren(el)
    }
}

private fun wrapMarker(marker: String, text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return text
    return marker + trimmed + marker
}

/** Keeps the stored HTML when the text is still the text it was opened with. */
private fun Draft.withBody(text: String, original: Draft): Draft =
    copy(body = text, html = if (text == original.body) original.html else "")

/**
 * Puts the sign-off on the draft, under what is being written.
 *
 * [aboveQuote] decides whether "under" means under the reply or under the whole thing. Both
 * are in use and neither is wrong: below the quote keeps a long sign-off out of the way,
 * above it keeps it with the words it belongs to, which is what most people expect on a
 * reply. Webmail calls the same setting `signaturePosition`, and Rampart reads its own
 * rather than the server's because it is a preference of the person, not of the mailbox.
 *
 * A reopened draft already has one. Adding another is the copy people then send by mistake.
 */
internal fun signed(
    draft: Draft,
    signature: String,
    html: String = "",
    aboveQuote: Boolean = false,
): Draft {
    if (signature.isBlank()) return draft
    // A `-- ` inside the quoted or forwarded original is the other person's sign-off.
    // Only one outside that part means this draft is already signed.
    if (hasSignOff(draft.body)) return draft
    val quote = if (aboveQuote) quoteStart(draft.body) else -1
    return draft.copy(
        body = if (quote < 0) draft.body.trimEnd() + signatureBlock(signature)
        else draft.body.take(quote).trimEnd() + signatureBlock(signature) +
            "\n\n" + draft.body.substring(quote),
        textSignature = signature.trimEnd(),
        htmlSignature = html,
    )
}

/**
 * Whether [body] already carries our sign-off.
 *
 * The line `-- ` counts above the quote or the forwarded block, and below a reply's
 * quote. It does not count inside either, where it belongs to the original message.
 */
internal fun hasSignOff(body: String): Boolean {
    val quote = quoteStart(body)
    if (quote < 0) return body.lineSequence().any { it == "-- " }
    if (body.take(quote).lineSequence().any { it == "-- " }) return true
    val first = body.substring(quote).lineSequence().firstOrNull().orEmpty()
    if (first.contains("Forwarded message")) return false
    return body.substring(quote).lineSequence().drop(1).any { it == "-- " }
}

/**
 * Where the quoted original starts, or -1 when the draft has none.
 *
 * Found in the text rather than recorded on the draft, because the only moment it is needed
 * is the one where the draft was just built and its body is nothing but the quote. Both
 * builders open the same way: [replyTo] with the attribution line, [forwardOf] with the
 * forwarded-message rule.
 */
internal fun quoteStart(body: String): Int = QUOTE_OPENS.find(body)?.range?.first ?: -1

private val QUOTE_OPENS = Regex("^(.*\\bwrote:|-{3,} Forwarded message -{3,})\\s*$", RegexOption.MULTILINE)

/** The separator and the sign-off, exactly as [signed] writes it and [htmlBodyOf] takes it out. */
internal fun signatureBlock(signature: String) = "\n\n-- \n" + signature.trimEnd()

/** Where [block] sits as a whole sign-off, or -1. A longer line that merely starts with it does not count. */
private fun exactBlockAt(body: String, block: String): Int {
    if (block.isEmpty()) return -1
    var at = body.indexOf(block)
    while (at >= 0) {
        val after = at + block.length
        if (after == body.length || body[after] == '\n') return at
        at = body.indexOf(block, at + 1)
    }
    return -1
}

/**
 * The HTML part to send.
 *
 * A reopened draft keeps the HTML it was stored with until the text changes. Anything
 * else is built from the markup and the sign-off.
 */
internal fun htmlPartOf(draft: Draft): String? =
    if (draft.html.isNotBlank()) {
        if (draft.trackingPixel.isEmpty()) draft.html else draft.html + draft.trackingPixel
    } else {
        htmlBodyOf(draft.body, draft.textSignature, draft.htmlSignature, draft.trackingPixel)
    }

/**
 * The HTML half of the message, or null when nothing in it needs HTML.
 *
 * Two things can need it: an HTML sign-off, and formatting the person typed. A message with
 * neither is sent as text alone, which is the right shape for a one line reply and is what
 * every mail client does.
 *
 * The text sign-off is taken back out and the HTML one put **where it was**, so the two
 * parts say the same thing rather than one carrying a formatted block and the other a copy
 * of it in plain text somewhere else. Where it was is not always the end: see [signed].
 *
 * With no HTML sign-off there is nothing to swap, and the text one stays where it is. It
 * used to be cut out and never put back, which sent an HTML part that was the message minus
 * its sign-off while the text part had one.
 */
internal fun htmlBodyOf(
    body: String,
    textSignature: String,
    htmlSignature: String,
    /**
     * The tracking pixel, appended last.
     *
     * **Its presence forces an HTML part to exist.** A message typed as plain text with no
     * HTML sign-off returns null here, meaning text only, and a pixel cannot live in a text
     * part: it would arrive as the tag spelled out in the middle of somebody's message. So
     * a tracked plain message gets an HTML part it would not otherwise have had.
     */
    pixel: String = "",
): String? {
    val block = if (textSignature.isBlank()) "" else signatureBlock(textSignature)
    val at = if (block.isEmpty()) -1 else body.indexOf(block)
    if (htmlSignature.isBlank()) {
        val typed = if (at < 0) body else body.removeRange(at, at + block.length)
        if (markupToPlain(typed) == typed && pixel.isEmpty()) return null
        return htmlOf(body) + pixel
    }
    if (at < 0) return htmlOf(body) + htmlSignature + pixel
    return htmlOf(body.take(at)) + htmlSignature + htmlOf(body.substring(at + block.length)) + pixel
}

/**
 * What was typed, as HTML.
 *
 * A div per line for ordinary text, because that is what every other client produces and
 * what every client renders the same way, plus the composer's markers turned into real
 * tags. Escaping is the part that matters: an ampersand or an angle bracket in what
 * somebody typed must arrive as itself, not as the start of a tag. `RichText.kt` does both.
 */
internal fun htmlOf(plain: String): String = markupToHtml(plain)

/**
 * A Content-ID for a picture put in the body.
 *
 * Derived from the blob id rather than random, so adding the same picture twice reuses one
 * reference instead of attaching it twice. The `@rampart.invalid` domain is the reserved
 * one from RFC 2606: a Content-ID looks like an address and this one must never resolve.
 */
internal fun cidFor(blobId: String): String =
    blobId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + "@rampart.invalid"

/**
 * The operating system's own file picker.
 *
 * AWT's rather than a Compose dialog: on Windows this is the real Explorer window, with the
 * places and recent files someone already knows, and a file picker is the last place to put
 * something that looks nearly right. It blocks until dismissed, which is what a modal
 * picker does anyway, and it is called from the click handler on the UI thread for the same
 * reason.
 */
/**
 * Thumbnails for pictures just chosen on disk, keyed by the blob the upload returned.
 *
 * Read from the file that was picked, not fetched back from the server: the bytes are
 * already on this machine, and a thumbnail is not a reason to download them again.
 * A file that is not a picture, or that will not decode, is simply absent.
 */
internal fun pickedPreviews(paths: List<Path>, added: List<Attachment>): Map<String, ImageBitmap> {
    val raw = paths.zip(added).mapNotNull { (path, part) ->
        if (fileGlyph(part.type) != FileGlyph.IMAGE) return@mapNotNull null
        val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return@mapNotNull null
        part.blobId to bytes
    }
    return raw.mapNotNull { (id, bytes) -> scaledPreview(bytes)?.let { id to it } }.toMap()
}

/**
 * The mark beside an attached file: the picture itself when we have one, otherwise a
 * glyph for the kind of file it is.
 *
 * [glyphForImage] is for the composer, where a picture that did not decode should still
 * have something beside the name. The reading view leaves a picture with no bytes blank,
 * which is what it did before there were glyphs.
 */
@Composable
internal fun FileMark(name: String, type: String, picture: ImageBitmap?, glyphForImage: Boolean) {
    val kind = fileGlyph(type)
    if (kind == FileGlyph.IMAGE && picture != null) {
        Image(
            picture,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(end = 12.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        return
    }
    if (kind == FileGlyph.IMAGE) {
        if (!glyphForImage) return
        Box(
            Modifier
                .padding(end = 12.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                RampartIcons.Page,
                contentDescription = "Image",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
        }
        return
    }
    val icon = when (kind) {
        FileGlyph.PDF -> RampartIcons.Page
        FileGlyph.ARCHIVE -> RampartIcons.Archive
        FileGlyph.DOCUMENT -> RampartIcons.Drafts
        FileGlyph.GENERIC -> RampartIcons.Attachment
        FileGlyph.IMAGE -> RampartIcons.Page
    }
    val label = when (kind) {
        FileGlyph.PDF -> "PDF"
        FileGlyph.ARCHIVE -> "Archive"
        FileGlyph.DOCUMENT -> "Document"
        FileGlyph.GENERIC -> "File"
        FileGlyph.IMAGE -> "Image"
    }
    Icon(
        icon,
        contentDescription = label,
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(end = 12.dp).size(22.dp),
    )
}

internal fun pickFiles(): List<Path> {
    val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files.orEmpty().map { it.toPath() }
}

/**
 * Which of your addresses a message was sent to, and therefore which to answer as.
 *
 * **One account can hold several identities, and picking the first of them is wrong most of
 * the time.** A reply to mail addressed to one of your other domains going out under the
 * first address on the list is the kind of mistake the recipient sees and you do not, and
 * it is the reason a client stops being trusted.
 *
 * To before Cc, because being written to directly is a better claim than being copied. An
 * address on a domain you already send as matches the identity on that domain, unless
 * [exactOnly] is set, in which case only a configured identity counts. Anything that
 * matched nothing falls back to [fallback], the account's first identity, which is the
 * right answer for a list or a forward we cannot see.
 */
internal fun identityFor(
    body: Body?,
    mine: List<String>,
    fallback: String,
    exactOnly: Boolean = Settings.exactIdentitiesOnly(),
    delimiter: Char = Settings.subAddressDelimiter(),
): String {
    if (mine.isEmpty()) return fallback
    val known = mine.associateBy { forMatching(it, delimiter) }
    fun match(addresses: List<String>): String? =
        addresses.asSequence().mapNotNull { known[forMatching(it, delimiter)] }.firstOrNull()
    match(body?.to.orEmpty())?.let { return it }
    match(body?.cc.orEmpty())?.let { return it }
    if (exactOnly) return fallback
    // Catch-all: the address is not an identity, but its domain is one you send as.
    // The identity on that domain is what the reply goes out as. Returning the alias
    // itself would show an address the send still replaces with an identity.
    fun onDomain(addresses: List<String>): String? {
        val hit = addresses.firstOrNull { countsAsMine(it, mine, exactOnly = false, delimiter) } ?: return null
        return mine.firstOrNull { domainOf(it) == domainOf(hit) }
    }
    return onDomain(body?.to.orEmpty()) ?: onDomain(body?.cc.orEmpty()) ?: fallback
}

/**
 * The identity a draft is saved and sent as.
 *
 * An address that is one of [available] uses that identity, so the message goes out as
 * the address the draft already shows. An address that is not one of them is kept as
 * written: substituting the account's first identity would send the message as somebody
 * else and never say so. That first identity is only used when the draft has not named
 * an address at all.
 *
 * A kept address still needs an id the server will accept a submission for, and the only
 * one available is the account's own. The address on the message stays the draft's. The
 * sign-off is not borrowed: it belongs to the identity whose address was replaced.
 */
internal fun identityForDraft(available: List<Identity>, from: String): Identity? {
    if (available.isEmpty()) return null
    available.firstOrNull { it.email.equals(from, ignoreCase = true) }?.let { return it }
    if (from.isBlank()) return available.first()
    return available.first().copy(name = "", email = from, textSignature = "", htmlSignature = "")
}

/**
 * Swaps the sign-off when From changes to another identity.
 *
 * The old block is removed and the new one is written back in the same place, above the
 * quote or below it, whichever the draft already used. If the writer has edited the
 * sign-off so it is no longer the identity's, the text is left alone: a partial match
 * would cut the message in the wrong place.
 */
internal fun withFrom(
    draft: Draft,
    address: String,
    identities: List<Identity>,
    aboveQuote: Boolean,
): Draft {
    val next = identities.firstOrNull { it.email.equals(address, ignoreCase = true) }
        ?: return draft.copy(from = address)
    if (next.email.equals(draft.from, ignoreCase = true)) return draft.copy(from = next.email)
    val previous = identities.firstOrNull { it.email.equals(draft.from, ignoreCase = true) }
    val old = previous?.textSignature?.trimEnd().orEmpty()
    val oldBlock = if (old.isEmpty()) "" else signatureBlock(old)
    val oldAt = exactBlockAt(draft.body, oldBlock)
    // Edited inside the sign-off, so the block is no longer the identity's. Leave the
    // text alone rather than cutting at a partial match.
    if (oldBlock.isNotEmpty() && oldAt < 0) return draft.copy(from = next.email)
    val quoteBefore = quoteStart(draft.body)
    val stripped = if (oldAt < 0) draft.body else draft.body.removeRange(oldAt, oldAt + oldBlock.length)
    val placeAbove = when {
        oldAt < 0 || quoteBefore < 0 -> aboveQuote
        else -> oldAt < quoteBefore
    }
    val swapped = signed(
        draft.copy(from = next.email, body = stripped, textSignature = "", htmlSignature = ""),
        next.textSignature,
        next.htmlSignature,
        placeAbove,
    )
    val unchanged = swapped.body == draft.body && swapped.htmlSignature == draft.htmlSignature
    return if (unchanged) swapped else swapped.copy(html = "")
}

/**
 * The draft the composer opens on, with the account's sign-off when the address is its own.
 *
 * A from address this account does not have is left exactly as it was. Applying the first
 * identity's sign-off there would be the same silent swap as sending as that identity.
 * A draft with no from at all takes the first identity, which is the only honest default.
 */
internal fun draftOpening(draft: Draft, available: List<Identity>, aboveQuote: Boolean): Draft {
    val chosen = identityForDraft(available, draft.from) ?: return draft
    val kept = draft.from.isNotBlank() && available.none { it.email.equals(draft.from, ignoreCase = true) }
    val base = if (draft.from.equals(chosen.email, ignoreCase = true)) draft else draft.copy(from = chosen.email)
    if (kept) return base
    return signed(base, chosen.textSignature, chosen.htmlSignature, aboveQuote)
}

/**
 * An address reduced to what makes it yours, for comparing two of them.
 *
 * Takes the address out of "Name <address>", which is how a header writes it as often as
 * not, lowercases it because case is not part of an address, and **drops a tag suffix**.
 *
 * Sub-addressing is the whole reason for that last part. Mail to `you+invoices@example.org`
 * is mail to you, and a client that cannot see that answers it as the wrong identity and
 * then copies you in on your own reply. [delimiter] is that character, plus unless someone
 * has chosen another. Only for matching: the address is never rewritten, so a reply still
 * goes to exactly what the sender wrote.
 */
internal fun forMatching(address: String, delimiter: Char = Settings.subAddressDelimiter()): String {
    val bare = address.substringAfterLast('<').substringBefore('>').trim().lowercase()
    val at = bare.lastIndexOf('@')
    if (at <= 0) return bare
    val local = bare.substring(0, at).substringBefore(delimiter)
    return local + bare.substring(at)
}

/**
 * Whether [address] counts as one of [identities].
 *
 * An exact match, after [forMatching], always counts. With [exactOnly] off, so does any
 * address on a domain one of those identities uses: that is the catch-all, and it is how
 * mail to an alias you never configured is still answered as you and left off Reply all.
 */
internal fun countsAsMine(
    address: String,
    identities: Collection<String>,
    exactOnly: Boolean,
    delimiter: Char = '+',
): Boolean {
    val key = forMatching(address, delimiter)
    if (key.isEmpty()) return false
    if (identities.any { forMatching(it, delimiter) == key }) return true
    if (exactOnly) return false
    return domainOwned(address, identities)
}

@Composable
private fun ComposePromptBar(
    prompt: String,
    onPromptChange: (String) -> Unit,
    running: Boolean,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(24.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = RampartIcons.Write,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (prompt.isEmpty()) {
                Text(
                    "Describe your message",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            BasicTextField(
                value = prompt,
                onValueChange = onPromptChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        if (!running && prompt.isNotBlank()) onSubmit()
                        true
                    } else {
                        false
                    }
                }
            )
        }
        Spacer(Modifier.width(8.dp))
        if (running) {
            Spinner(size = 16.dp, thickness = 2.dp)
        } else {
            TextButton(
                onClick = onSubmit,
                enabled = prompt.isNotBlank(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text("Draft", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun RefineChip(
    label: String,
    enabled: Boolean,
    running: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                color = if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceDim,
                shape = RoundedCornerShape(12.dp)
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (running) {
                Spinner(size = 12.dp, thickness = 1.5.dp)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline)
        }
    }
}
