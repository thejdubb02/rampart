package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The buttons for [WritingHelp]: the panel under the composer's toolbar, the proofreading
 * marks in the draft, and the Suggest replies card above an open message.
 *
 * Every call here is a button press and goes the same way as Summarise and the chat panel:
 * [Assistant.whyNot] first, the packet shown in [PacketViewer] the first time, one post
 * through [Llm.ask], and the cost into [Assistant.record] under the compose agreement. The
 * answer is never written into the draft by itself. It is a preview with Accept and
 * Discard, or a suggestion with Accept and Dismiss, and Accept goes through the composer's
 * own edit path so Undo takes it back.
 */
internal class WritingHelpState {
    /** What Help me write is asked for. */
    var instruction by mutableStateOf("")

    /** The words in the "change it how?" box. */
    var change by mutableStateOf("")

    /** The label of the call in flight, or null when nothing is running. */
    var running by mutableStateOf<String?>(null)

    /** The person's own part as the model would have it, waiting for Accept. */
    var preview by mutableStateOf<String?>(null)

    /** Which button [preview] came from, for its heading. */
    var previewOf by mutableStateOf("")

    /** Proofreading suggestions not yet accepted or dismissed. Placed afresh on every draw. */
    var proofs by mutableStateOf<List<Proof>>(emptyList())

    /** One sentence about the last proofread, such as it having found nothing. */
    var proofNote by mutableStateOf<String?>(null)

    var error by mutableStateOf<String?>(null)
    var detail by mutableStateOf<String?>(null)

    /** A packet shown before the first call goes, and what pressing Send it does. */
    var waiting by mutableStateOf<HeldPacket?>(null)

    /** A document from Files sent as context with Help me write, until it is taken off. See RookExtras.kt. */
    var file by mutableStateOf<AttachedFile?>(null)

    /** Why the last Help me write went without a sample of the person's writing, when Match my tone is on. */
    var toneNote by mutableStateOf<String?>(null)

    val busy: Boolean get() = running != null
}

/**
 * A packet held for the person to look at, and the call that goes when they agree.
 * [tone] is true when it carries a sample of their sent mail, which is agreed to on its own.
 */
internal class HeldPacket(val packet: String, val go: () -> Unit, val tone: Boolean = false)

/** What the packet viewer says above a packet that carries a sample of the person's own mail. */
internal const val TONE_NOTE =
    "Match my tone is on, so this also carries a sample of your own sent mail, between <<<STYLE and STYLE>>>."

/**
 * The composer's text drawn with the proofreading suggestions marked.
 *
 * The markup styling underneath is kept, so bold still looks bold while a suggestion is
 * showing. With no suggestions this is [MarkupStyling] itself, so a composer with the
 * panel closed draws exactly as it did before there was one.
 */
internal fun proofreadStyling(proofs: List<Proof>, signature: String, colour: Color): VisualTransformation =
    if (proofs.isEmpty()) MarkupStyling else ProofreadStyling(proofs, signature, colour)

private data class ProofreadStyling(
    val proofs: List<Proof>,
    val signature: String,
    val colour: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val base = displayMarkup(text.text)
        val placed = WritingHelp.place(text.text, proofs, signature)
        if (placed.isEmpty()) return base
        val marked = AnnotatedString.Builder(base.text)
        for (proof in placed) {
            val start = base.offsetMapping.originalToTransformed(proof.start)
            val end = base.offsetMapping.originalToTransformed(proof.end)
            if (end > start) {
                marked.addStyle(
                    SpanStyle(textDecoration = TextDecoration.Underline, background = colour.copy(alpha = 0.16f)),
                    start,
                    end,
                )
            }
        }
        return TransformedText(marked.toAnnotatedString(), base.offsetMapping)
    }
}

/**
 * Help me write, the refine buttons, the change box and Proofread, under the toolbar.
 *
 * @param body The whole draft as it stands, quote and signature included. Only the
 *   person's own part of it is ever sent; see [WritingHelp.split].
 * @param signature The identity's sign-off, for a draft that carries it without a `-- `.
 * @param onReplace Puts a new whole draft in place, through the composer's undoable edit.
 */
@Composable
internal fun WritingHelpPanel(
    state: WritingHelpState,
    body: String,
    signature: String,
    subject: String,
    replyContext: List<Turn>,
    account: String?,
    folder: String?,
    onReplace: (String) -> Unit,
    /** The account's session, for its sent mail, its Files and its saved prompts. Null leaves those out. */
    backend: MailBackend? = null,
) {
    val scope = rememberCoroutineScope()
    // Read when the panel opens and again after each call, rather than on every keystroke,
    // because the check reads the key out of the operating system's store.
    val why = remember(state.running, state.waiting, account, folder) {
        Assistant.whyNot(Assistant.COMPOSE, Assistant.config(), account, folder)
    }
    val own = WritingHelp.split(body, signature).own

    /*
     * One way out to the model for every button here. The gate is asked again at the moment
     * of the press, because the ceiling may have been reached by the call before this one.
     */
    fun call(label: String, verb: String, packetFor: (String) -> String, tone: Boolean = false, handle: (String) -> Unit) {
        val config = Assistant.config()
        val stop = Assistant.whyNot(Assistant.COMPOSE, config, account, folder)
        if (stop != null) {
            state.error = stop
            state.detail = null
            return
        }
        val packet = packetFor(config.model)
        val go: () -> Unit = {
            scope.launch {
                state.running = label
                state.error = null
                state.detail = null
                try {
                    val reply = withContext(Dispatchers.IO) {
                        Llm.ask(config, Secrets.loadNamed(Assistant.KEY), packet).also {
                            Assistant.record(Assistant.COMPOSE, it.tokensIn, it.tokensOut, config)
                        }
                    }
                    handle(reply.text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val title = "Rook could not $verb."
                    state.error = title
                    state.detail = faultDetail(e, title)
                } finally {
                    state.running = null
                }
            }
        }
        // A sample of sent mail is agreed to on its own, so the first one shows its packet
        // even to somebody who agreed to Help me write long ago.
        if (Assistant.agreed(Assistant.COMPOSE) && (!tone || Assistant.agreed(TONE_FEATURE))) {
            go()
        } else {
            state.waiting = HeldPacket(packet, go, tone)
        }
    }

    /** A rewrite of the person's own part, shown as a preview under [label]. */
    fun rewrite(label: String, verb: String, tone: Boolean = false, packetFor: (String) -> String) {
        val before = own
        call(label, verb, packetFor, tone) { answer ->
            val text = WritingHelp.cleanText(answer, before)
            if (text.isBlank()) throw LlmError("Rook sent back nothing that could go in a draft.")
            state.proofs = emptyList()
            state.proofNote = null
            state.previewOf = label
            state.preview = text
        }
    }

    fun tooLong(): Boolean {
        if (own.length <= WritingHelp.DRAFT_LIMIT) return false
        state.error = "This draft is too long to send to Rook in one go."
        state.detail = null
        return true
    }

    fun write() {
        val instruction = state.instruction.trim()
        if (instruction.isEmpty() || state.busy) return
        val file = state.file
        val mail = backend
        state.toneNote = null
        if (mail == null || !ToneSetting.on()) {
            rewrite("Help me write", "write that") { model ->
                WritingHelp.helpPacket(model, instruction, subject, replyContext, RookExtras(file = file))
            }
            return
        }
        // The sample is read from Sent first, off the window's thread, and only then is the
        // packet built, so what the viewer shows is what goes.
        state.running = "Help me write"
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching { readToneSample(mail, account?.let { Assistant.deniedFolders(it) }.orEmpty(), ::plainTextOf) }
                    .getOrElse { ToneRead.Skipped("Your sent mail could not be read, so no sample of your writing was sent.") }
            }
            state.running = null
            val style = (read as? ToneRead.Sample)?.text.orEmpty()
            state.toneNote = (read as? ToneRead.Skipped)?.reason
            rewrite("Help me write", "write that", tone = style.isNotBlank()) { model ->
                WritingHelp.helpPacket(model, instruction, subject, replyContext, RookExtras(style, file))
            }
        }
    }

    fun refine(mode: Refine?, custom: String = "") {
        if (own.isBlank() || state.busy || tooLong()) return
        val words = own
        rewrite(mode?.label ?: "Your change", "rewrite that") { model ->
            WritingHelp.refinePacket(model, words, mode, custom)
        }
    }

    fun proofread() {
        if (own.isBlank() || state.busy || tooLong()) return
        val words = own
        val checked = body
        call("Proofread", "proofread that", { model -> WritingHelp.proofPacket(model, words) }) { answer ->
            val proofs = WritingHelp.parseProofs(answer)
            val found = WritingHelp.place(checked, proofs, signature)
            state.preview = null
            state.proofs = found.map { it.proof }
            state.proofNote = when {
                proofs.isEmpty() -> "Rook found nothing to change."
                found.isEmpty() -> "None of Rook's suggestions matched the text exactly, so none are shown."
                found.size < proofs.size ->
                    "${proofs.size - found.size} of Rook's suggestions did not match the text exactly and were left out."
                else -> null
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (why != null) {
            // The sentence goes where the buttons would be, so a panel that cannot work says
            // why rather than offering controls that fail or quietly doing nothing.
            FaultText(why, titleColor = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(4.dp))
        } else {
            PromptField(
                value = state.instruction,
                onValueChange = { state.instruction = it },
                placeholder = if (own.isBlank()) "Help me write: describe the message" else "Help me write: describe a new version",
                busy = state.running == "Help me write",
                enabled = !state.busy,
                onSubmit = ::write,
            )
            if (backend != null && account != null) {
                val shelf = remember(backend, account) { promptShelfOf(backend, account) }
                // Read once when the panel opens rather than from disk on every keystroke.
                val matching = remember { ToneSetting.on() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SavedPromptsMenu(shelf, { state.instruction }, !state.busy) { state.instruction = it }
                    AttachFileForRook(backend, state.file, !state.busy) { state.file = it }
                    if (matching) {
                        Text("Matching your tone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                state.toneNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            if (own.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Refine.entries.forEach { mode ->
                        Chip(mode.label, enabled = !state.busy, running = state.running == mode.label) { refine(mode) }
                    }
                    Chip("Proofread", enabled = !state.busy, running = state.running == "Proofread") { proofread() }
                }
                Spacer(Modifier.height(6.dp))
                PromptField(
                    value = state.change,
                    onValueChange = { state.change = it },
                    placeholder = "Change it how?",
                    busy = state.running == "Your change",
                    enabled = !state.busy,
                    onSubmit = { if (state.change.isNotBlank()) refine(null, state.change) },
                )
            }
            state.preview?.let { proposed ->
                Preview(
                    heading = "${state.previewOf}, from Rook",
                    text = proposed,
                    onAccept = {
                        onReplace(WritingHelp.split(body, signature).with(proposed))
                        state.preview = null
                    },
                    onDiscard = { state.preview = null },
                )
            }
            val placed = WritingHelp.place(body, state.proofs, signature)
            if (placed.isNotEmpty() || state.proofNote != null) {
                Proofs(
                    placed = placed,
                    note = state.proofNote,
                    onAccept = { proof ->
                        onReplace(WritingHelp.accept(body, proof))
                        state.proofs = state.proofs - proof.proof
                    },
                    onDismiss = { proof -> state.proofs = state.proofs - proof.proof },
                    onClose = {
                        state.proofs = emptyList()
                        state.proofNote = null
                    },
                )
            }
            state.error?.let { title ->
                FaultText(title, state.detail, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }

    state.waiting?.let { held ->
        PacketViewer(
            packet = held.packet,
            agreed = false,
            onSend = {
                state.waiting = null
                held.go()
            },
            onAgree = {
                Assistant.agree(Assistant.COMPOSE)
                if (held.tone) Assistant.agree(TONE_FEATURE)
            },
            onDismiss = { state.waiting = null },
            note = if (held.tone) TONE_NOTE else null,
        )
    }
}

/** A one-line box with Enter to go, in the shape of the prompt bar it replaces. */
@Composable
private fun PromptField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    busy: Boolean,
    enabled: Boolean,
    onSubmit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            RampartIcons.Write,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (enter && event.type == KeyEventType.KeyDown) {
                        if (enabled && value.isNotBlank()) onSubmit()
                        true
                    } else {
                        false
                    }
                },
            )
        }
        if (busy) {
            Spacer(Modifier.width(8.dp))
            Spinner(size = 16.dp, thickness = 2.dp)
        } else {
            TextButton(onClick = onSubmit, enabled = enabled && value.isNotBlank()) { Text("Go") }
        }
    }
}

@Composable
private fun Chip(label: String, enabled: Boolean, running: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .clip(shape)
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceDim,
                shape,
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (running) Spinner(size = 12.dp, thickness = 1.5.dp)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
        )
    }
}

/** The proposed text, which is only ever a proposal until Accept is pressed. */
@Composable
private fun Preview(heading: String, text: String, onAccept: () -> Unit, onDiscard: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            heading,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Accept puts this in place of your own words. The quoted message and your signature stay as they are.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onAccept) { Text("Accept") }
            TextButton(onClick = onDiscard) { Text("Discard") }
        }
    }
}

/** The proofreading suggestions still open, each marked in the draft and taken one at a time. */
@Composable
private fun Proofs(
    placed: List<PlacedProof>,
    note: String?,
    onAccept: (PlacedProof) -> Unit,
    onDismiss: (PlacedProof) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Proofread, from Rook",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("Close") }
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            placed.forEach { proof ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val change = if (proof.proof.replacement.isEmpty()) {
                            "Remove \"${proof.proof.original}\""
                        } else {
                            "\"${proof.proof.original}\" becomes \"${proof.proof.replacement}\""
                        }
                        Text(
                            proof.proof.kind.replaceFirstChar { it.uppercase() } + ": " + change,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (proof.proof.reason.isNotBlank()) {
                            Text(
                                proof.proof.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    TextButton(onClick = { onAccept(proof) }) { Text("Accept") }
                    TextButton(onClick = { onDismiss(proof) }) { Text("Dismiss") }
                }
            }
        }
    }
}

/**
 * Up to three short replies to the open conversation, made only when the button is pressed.
 *
 * Kept by the window for as long as it is open, against the conversation it was made for,
 * so moving to another thread does not show this one's replies there and an answer that
 * lands after the move is dropped. Picking one opens it in the composer as a reply, unsent,
 * where it is only a draft like any other.
 */
internal class SuggestReplies(private val scope: CoroutineScope) {
    private var thread by mutableStateOf<String?>(null)
    private var replies by mutableStateOf<List<String>>(emptyList())
    private var running by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var detail by mutableStateOf<String?>(null)
    private var waiting by mutableStateOf<HeldPacket?>(null)

    /**
     * What the card for [threadId] shows and does, or null to show no card at all.
     *
     * Null only when the assistant is switched off, the same rule as the summary card
     * beside it: the switch that turns it on is in the sidebar and in Settings. Every other
     * reason it cannot run is a sentence on the card.
     */
    fun actions(
        threadId: String,
        account: String,
        folder: String?,
        subject: String,
        turns: suspend () -> List<Turn>,
        onPick: (String) -> Unit,
        /** The account's session, for a sample of its sent mail when Match my tone is on. */
        backend: MailBackend? = null,
    ): SuggestRepliesActions? {
        val config = Assistant.config()
        if (config.mode == AssistantMode.OFF) return null
        val ours = thread == threadId
        return SuggestRepliesActions(
            disabledBecause = Assistant.whyNot(Assistant.COMPOSE, config, account, folder),
            running = running && ours,
            replies = if (ours) replies else emptyList(),
            error = if (ours) error else null,
            detail = if (ours) detail else null,
            waiting = if (ours) waiting else null,
            onSuggest = { suggest(threadId, account, folder, subject, turns, backend) },
            onPick = onPick,
            onCloseWaiting = { waiting = null },
        )
    }

    private fun suggest(
        threadId: String,
        account: String,
        folder: String?,
        subject: String,
        turns: suspend () -> List<Turn>,
        backend: MailBackend?,
    ) {
        if (running) return
        val config = Assistant.config()
        thread = threadId
        replies = emptyList()
        detail = null
        error = Assistant.whyNot(Assistant.COMPOSE, config, account, folder)
        if (error != null) return
        running = true
        scope.launch {
            var tone = false
            val packet = try {
                withContext(Dispatchers.IO) {
                    // A sample that cannot be read is left out rather than stopping the replies.
                    val style = if (backend != null && ToneSetting.on()) {
                        (runCatching { readToneSample(backend, Assistant.deniedFolders(account), ::plainTextOf) }.getOrNull() as? ToneRead.Sample)?.text.orEmpty()
                    } else {
                        ""
                    }
                    tone = style.isNotBlank()
                    WritingHelp.repliesPacket(config.model, subject, turns(), RookExtras(style = style))
                }
            } catch (e: CancellationException) {
                running = false
                throw e
            } catch (e: Exception) {
                failed(threadId, e)
                running = false
                return@launch
            }
            running = false
            if (thread != threadId) return@launch
            if (Assistant.agreed(Assistant.COMPOSE) && (!tone || Assistant.agreed(TONE_FEATURE))) {
                send(threadId, packet, config)
            } else {
                waiting = HeldPacket(packet, {
                    waiting = null
                    scope.launch { send(threadId, packet, config) }
                }, tone)
            }
        }
    }

    private suspend fun send(threadId: String, packet: String, config: AssistantConfig) {
        running = true
        try {
            val reply = withContext(Dispatchers.IO) {
                Llm.ask(config, Secrets.loadNamed(Assistant.KEY), packet).also {
                    Assistant.record(Assistant.COMPOSE, it.tokensIn, it.tokensOut, config)
                }
            }
            val found = WritingHelp.parseReplies(reply.text)
            if (thread == threadId) {
                replies = found
                if (found.isEmpty()) error = "Rook had no replies to suggest for this message."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(threadId, e)
        } finally {
            running = false
        }
    }

    private fun failed(threadId: String, e: Exception) {
        if (thread != threadId) return
        val title = "Rook could not suggest replies."
        error = title
        detail = faultDetail(e, title)
    }
}

/** What [SuggestRepliesCard] draws and what its buttons do. */
internal data class SuggestRepliesActions(
    /** Why the button cannot be pressed, in one sentence. Null when it can be. */
    val disabledBecause: String?,
    val running: Boolean,
    val replies: List<String>,
    val error: String?,
    val detail: String?,
    /** The packet waiting on the first-time viewer, or null. */
    val waiting: HeldPacket?,
    val onSuggest: () -> Unit,
    /** Opens the reply in the composer, unsent. */
    val onPick: (String) -> Unit,
    val onCloseWaiting: () -> Unit,
)

@Composable
internal fun SuggestRepliesCard(actions: SuggestRepliesActions) {
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Suggested replies, from Rook",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            if (actions.running) Spinner(size = 15.dp, thickness = 2.dp)
        }
        actions.replies.forEach { reply ->
            Spacer(Modifier.height(6.dp))
            Text(
                reply,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .clickable { actions.onPick(reply) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        val problem = actions.error ?: actions.disabledBecause
        if (problem != null) {
            Spacer(Modifier.height(4.dp))
            FaultText(
                problem,
                if (actions.error != null) actions.detail else null,
                titleColor = if (actions.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = actions.onSuggest,
                enabled = !actions.running && actions.disabledBecause == null,
            ) {
                Text(
                    when {
                        actions.running -> "Suggesting"
                        actions.replies.isNotEmpty() -> "Suggest again"
                        else -> "Suggest replies"
                    },
                )
            }
            if (actions.replies.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "Pick one to open it as a reply. Nothing is sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))

    actions.waiting?.let { held ->
        PacketViewer(
            packet = held.packet,
            agreed = false,
            onSend = held.go,
            onAgree = {
                Assistant.agree(Assistant.COMPOSE)
                if (held.tone) Assistant.agree(TONE_FEATURE)
            },
            onDismiss = actions.onCloseWaiting,
            note = if (held.tone) TONE_NOTE else null,
        )
    }
}
