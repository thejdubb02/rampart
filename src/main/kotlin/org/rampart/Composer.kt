package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
 */
internal fun replyTo(summary: Summary, body: Body?, from: String): Draft {
    val original = body?.text
        ?: body?.html?.let { renderHtml(it, Color.Unspecified, Color.Unspecified) {}.text.text }
        ?: ""
    val quoted = original.trim().lineSequence().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    val subject = summary.subject.trim()
    val answered = body?.messageId?.firstOrNull()
    return Draft(
        from = from,
        to = summary.fromEmail,
        subject = if (subject.startsWith("Re:", ignoreCase = true)) subject else "Re: $subject",
        body = "\n\nOn ${summary.receivedAt.asLocalTime()}, ${summary.from} wrote:\n$quoted",
        inReplyTo = answered,
        references = body?.references.orEmpty() + listOfNotNull(answered),
        replying = true,
    )
}

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
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var showCc by remember(initial) { mutableStateOf(initial.cc.isNotEmpty()) }
    var pickingIdentity by remember { mutableStateOf(false) }
    val firstField = remember { FocusRequester() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
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
                    TextButton(onClick = onDiscard, enabled = !sending) { Text("Discard") }
                    Button(
                        onClick = { onSend(draft) },
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
                Entry(draft.to, sending, firstField) { draft = draft.copy(to = it) }
            }
            HorizontalDivider()

            if (showCc) {
                Field(label = "Cc") { Entry(draft.cc, sending) { draft = draft.copy(cc = it) } }
                HorizontalDivider()
            }

            Field(label = "Subject") {
                Entry(draft.subject, sending) { draft = draft.copy(subject = it) }
            }
            HorizontalDivider()

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (sending) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    BasicTextField(
                        value = draft.body,
                        onValueChange = { draft = draft.copy(body = it) },
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
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = !disabled,
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier).fillMaxWidth(),
    )
}
