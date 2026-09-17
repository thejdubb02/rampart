package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/**
 * A message on its way out, in the terms the writer used: addresses as they typed them,
 * a plain text body, and the two headers that decide whether a reply joins its thread or
 * starts a new one.
 *
 * Deliberately free of JMAP types. Turning this into an Email object is [Jmap]'s job, and
 * keeping the boundary here is what lets the composer be drawn and reviewed without a
 * server.
 */
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
    /** The sign-off, as it was appended to [body], so the HTML part can swap it out. */
    val textSignature: String = "",
    /** The same sign-off written as HTML, or empty when there is none. */
    val htmlSignature: String = "",
    /**
     * Whether this answers something. Not derived from [inReplyTo]: a message with no
     * Message-ID of its own is still being replied to, and telling the writer otherwise
     * because a header was missing would be a lie about what they are doing.
     */
    val replying: Boolean = false,
) {
    val recipients: List<String> get() = (to.split(',') + cc.split(',')).map { it.trim() }.filter { it.isNotEmpty() }
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
): Draft {
    val original = plainTextOf(body)
    val quoted = original.trim().lineSequence().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    val subject = summary.subject.trim()
    val answered = body?.messageId?.firstOrNull()
    val ours = (mine + from).map { it.trim().lowercase() }.filterTo(mutableSetOf()) { it.isNotEmpty() }
    // Answering your own message is the case where dropping your own address leaves nobody
    // to send to, so the sender goes back in rather than the reply opening addressed to no one.
    val to = if (!all) listOf(summary.fromEmail)
    else dedupe(listOf(summary.fromEmail) + body?.to.orEmpty(), ours)
        .ifEmpty { listOf(summary.fromEmail) }
    val cc = if (!all) emptyList() else dedupe(body?.cc.orEmpty(), ours + to.map { it.lowercase() })
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
internal fun hasOtherRecipients(summary: Summary, body: Body?, mine: Set<String>): Boolean {
    val ours = (mine + summary.fromEmail).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    return dedupe(body?.to.orEmpty() + body?.cc.orEmpty(), ours).isNotEmpty()
}

/** Addresses in the order they were written, without repeats and without [exclude]. */
private fun dedupe(addresses: List<String>, exclude: Set<String>): List<String> {
    val seen = exclude.toMutableSet()
    return addresses.mapNotNull { address ->
        val key = address.trim().lowercase()
        if (key.isEmpty() || !seen.add(key)) null else address.trim()
    }
}

/**
 * A forward of [summary]. The original is quoted under a header naming who sent it and
 * when, which is the convention every mail client renders the same way.
 *
 * No threading headers: a forward starts a new conversation with someone who was not in
 * the old one, and attaching it to a thread they cannot see is worse than not threading.
 */
internal fun forwardOf(summary: Summary, body: Body?, from: String): Draft {
    val subject = summary.subject.trim()
    return Draft(
        from = from,
        subject = if (subject.startsWith("Fwd:", ignoreCase = true)) subject else "Fwd: $subject",
        body = buildString {
            append("\n\n---------- Forwarded message ----------\n")
            append("From: ${summary.from} <${summary.fromEmail}>\n")
            append("Date: ${summary.receivedAt.asLocalTime()}\n")
            append("Subject: $subject\n\n")
            append(plainTextOf(body))
        },
    )
}

/** The message as text, whichever way it arrived, so a quote never carries markup. */
private fun plainTextOf(body: Body?): String =
    body?.text
        ?: body?.html?.let { renderHtml(it, Color.Unspecified, Color.Unspecified) {}.text.text }
        ?: ""

/**
 * The compose pane. Owns what the writer has typed and nothing else: sending, saving and
 * closing are the caller's, so the same pane serves a new message, a reply and a reopened
 * draft without knowing which it is.
 */
@Composable
internal fun Composer(
    identities: List<String>,
    initial: Draft,
    sending: Boolean,
    error: String?,
    onDiscard: () -> Unit,
    onSend: (Draft) -> Unit,
    /** Writes the draft to the server. Null while there is nowhere to write it. */
    onSave: (suspend (Draft) -> Unit)? = null,
    /** Puts the chosen files on the server and says what to attach. Null when it cannot. */
    onAttach: (suspend (List<Path>) -> List<Attachment>)? = null,
    /** Addresses to offer while a recipient is being typed. */
    book: List<Person> = emptyList(),
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    // The selection has to live here, not be derived from the string, or every formatting
    // button would have to guess where the caret is. Kept beside draft.body rather than
    // replacing it, because the draft is what gets saved and sent.
    var body by remember(initial) { mutableStateOf(TextFieldValue(initial.body)) }
    // Only when the change came from somewhere else, such as the sign-off being appended.
    // Typing sets both, so they already agree and the caret is left alone.
    if (body.text != draft.body) body = body.copy(text = draft.body)
    var showCc by remember(initial) { mutableStateOf(initial.cc.isNotEmpty()) }
    var pickingIdentity by remember { mutableStateOf(false) }
    var saveState by remember(initial) { mutableStateOf("") }
    var attaching by remember(initial) { mutableStateOf(false) }
    var attachError by remember(initial) { mutableStateOf<String?>(null) }
    var warning by remember(initial) { mutableStateOf<String?>(null) }
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
    LaunchedEffect(draft) {
        if (onSave == null || draft == initial) return@LaunchedEffect
        delay(1200)
        saveState = "Saving"
        saveState = try {
            onSave(draft)
            "Saved"
        } catch (e: Exception) {
            // Said plainly and left on screen. A draft that silently failed to save is the
            // one thing worse than no autosave at all.
            "Not saved: ${e.message ?: "the server refused it"}"
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
    fun send() {
        if (sending || draft.recipients.isEmpty()) return
        val question = sendWarning(draft.subject, draft.body, draft.attachments.size)
        if (question == null) onSend(draft) else warning = question
    }

    fun format(before: String, after: String): Boolean {
        if (sending) return false
        val next = wrapSelection(body, before, after)
        body = next
        draft = draft.copy(body = next.text)
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
            else -> false
        }
    }

    Surface(
        Modifier.fillMaxSize().onPreviewKeyEvent(::typed),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (draft.replying) "Reply" else "New message",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (saveState.isNotEmpty()) {
                        Text(
                            saveState,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (saveState.startsWith("Not saved")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (onAttach != null) {
                        TextButton(
                            onClick = {
                                val chosen = pickFiles()
                                if (chosen.isNotEmpty()) {
                                    scope.launch {
                                        attaching = true
                                        attachError = null
                                        try {
                                            draft = draft.copy(attachments = draft.attachments + onAttach(chosen))
                                        } catch (e: Exception) {
                                            attachError = e.message ?: "That file could not be attached."
                                        } finally {
                                            attaching = false
                                        }
                                    }
                                }
                            },
                            enabled = !sending && !attaching,
                        ) { Text(if (attaching) "Attaching" else "Attach") }
                    }
                    TextButton(onClick = onDiscard, enabled = !sending) { Text("Discard") }
                    Button(
                        onClick = ::send,
                        enabled = !sending && draft.recipients.isNotEmpty(),
                    ) { Text(if (sending) "Sending" else "Send") }
                }
            }
            HorizontalDivider()

            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
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
                            identities.forEach { address ->
                                DropdownMenuItem(
                                    text = { Text(address) },
                                    onClick = {
                                        draft = draft.copy(from = address)
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

            attachError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (draft.attachments.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    draft.attachments.forEach { file ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                safeFileName(file.name),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
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
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    fun apply(next: TextFieldValue) {
                        body = next
                        draft = draft.copy(body = next.text)
                    }
                    TextButton(onClick = { apply(wrapSelection(body, "**", "**")) }) { Text("Bold") }
                    TextButton(onClick = { apply(wrapSelection(body, "*", "*")) }) { Text("Italic") }
                    TextButton(onClick = { apply(wrapSelection(body, "[", "](https://)")) }) { Text("Link") }
                    TextButton(onClick = { apply(prefixLines(body, "- ")) }) { Text("Bullets") }
                    TextButton(onClick = { apply(prefixLines(body, "1. ")) }) { Text("Numbers") }
                    if (onAttach != null) {
                        TextButton(
                            onClick = {
                                val chosen = pickFiles().firstOrNull()
                                if (chosen != null) {
                                    scope.launch {
                                        attaching = true
                                        attachError = null
                                        try {
                                            val put = onAttach(listOf(chosen)).firstOrNull()
                                            if (put != null) {
                                                val inline = put.copy(cid = cidFor(put.blobId), inline = true)
                                                draft = draft.copy(attachments = draft.attachments + inline)
                                                apply(insertAt(body, "![${put.name}](cid:${inline.cid})"))
                                            }
                                        } catch (e: Exception) {
                                            attachError = e.message ?: "That picture could not be added."
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
                HorizontalDivider()
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (sending) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    BasicTextField(
                        value = body,
                        onValueChange = {
                            body = it
                            draft = draft.copy(body = it.text)
                        },
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
        AlertDialog(
            onDismissRequest = { warning = null },
            text = { Text(question) },
            confirmButton = {
                TextButton(
                    onClick = {
                        warning = null
                        onSend(draft)
                    },
                ) { Text("Send anyway") }
            },
            dismissButton = { TextButton(onClick = { warning = null }) { Text("Go back") } },
        )
    }

    LaunchedEffect(Unit) { firstField.requestFocus() }
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

/**
 * A draft from the Drafts folder, put back the way it was written.
 *
 * The body is taken as text, because that is what the composer writes: a draft saved by
 * another client as HTML comes back as its text, which is a fair trade against silently
 * dropping the markup on the next save.
 */
internal fun draftOf(summary: Summary, body: Body?, from: String): Draft = Draft(
    from = from,
    to = body?.to.orEmpty().joinToString(", "),
    cc = body?.cc.orEmpty().joinToString(", "),
    subject = summary.subject,
    body = plainTextOf(body),
    inReplyTo = null,
    references = body?.references.orEmpty(),
    replying = false,
)

/**
 * Appends the sign-off, after the quoted text.
 *
 * Below the quote rather than above it, which is what Justin's webmail is already set to
 * (`signaturePosition = "below_quote"`), and it is also what makes the HTML part possible:
 * a block on the end can be swapped for its HTML version by taking it off the end, where
 * one buried between the reply and the quote would need finding first.
 *
 * A reopened draft already has one. Adding another is the copy people then send by mistake.
 */
internal fun signed(draft: Draft, signature: String, html: String = ""): Draft {
    if (signature.isBlank()) return draft
    if (draft.body.lineSequence().any { it == "-- " }) return draft
    return draft.copy(
        body = draft.body.trimEnd() + signatureBlock(signature),
        textSignature = signature.trimEnd(),
        htmlSignature = html,
    )
}

/** The separator and the sign-off, exactly as [signed] appends it and [htmlBodyOf] removes it. */
internal fun signatureBlock(signature: String) = "\n\n-- \n" + signature.trimEnd()

/**
 * The HTML half of the message, or null when nothing in it needs HTML.
 *
 * Two things can need it: an HTML sign-off, and formatting the person typed. A message with
 * neither is sent as text alone, which is the right shape for a one line reply and is what
 * every mail client does.
 *
 * The text sign-off is taken back off the end and the HTML one put in its place, so the two
 * parts say the same thing rather than one carrying a formatted block and the other a copy
 * of it in plain text underneath.
 */
internal fun htmlBodyOf(body: String, textSignature: String, htmlSignature: String): String? {
    val typed = if (textSignature.isBlank()) body
    else body.removeSuffix(signatureBlock(textSignature))
    if (htmlSignature.isBlank() && markupToPlain(typed) == typed) return null
    return htmlOf(typed) + htmlSignature
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
internal fun pickFiles(): List<Path> {
    val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files.orEmpty().map { it.toPath() }
}
