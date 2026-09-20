package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The assistant, as a panel beside the mail rather than a screen instead of it.
 *
 * Beside, because every question worth asking it is about something on screen: this
 * thread, this sender, this pile of unread. A full-window chat would mean leaving the
 * thing being asked about, which is the shape that makes people stop using one.
 *
 * What it can do, and the guard that stops a message talking it into more, is in
 * `Chat.kt`. This file draws it.
 */
@Composable
internal fun ChatPane(
    said: List<Said>,
    thinking: Boolean,
    /** Why it cannot run, when it cannot. Null when it can. */
    unavailable: String?,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    /** Where the questions go, so the agreement names it rather than describing it. */
    model: String = "",
    agreed: Boolean = true,
    onAgree: () -> Unit = {},
    onTyping: (Boolean) -> Unit = {},
) {
    var typed by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<String?>(null) }
    val scroll = rememberLazyListState()

    // The newest line, whenever one arrives. A transcript that has to be scrolled to be
    // read is one where the answer appears somewhere nobody is looking.
    LaunchedEffect(said.size, thinking) {
        if (said.isNotEmpty()) scroll.animateScrollToItem(said.size - 1)
    }

    Column(
        Modifier.width(360.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Assistant", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (said.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Clear", style = MaterialTheme.typography.bodySmall) }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(
                    RampartIcons.Close,
                    contentDescription = "Close the assistant",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (unavailable != null) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    unavailable,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSettings) { Text("Open Settings") }
            }
            return@Column
        }

        LazyColumn(
            state = scroll,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (said.none { it.role != "call" }) {
                item { Opening() }
            }
            itemsIndexed(said, key = { index, _ -> index }) { _, line ->
                // The model's own tool call is history, not something to read. What it did
                // is on the next line, in words.
                if (line.role != "call") SaidLine(line)
            }
            if (thinking) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spinner(Modifier.height(16.dp))
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Ask about your mail") },
                enabled = !thinking,
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { onTyping(it.isFocused) },
                maxLines = 4,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = {
                        val question = typed.trim()
                        // Agreed to once, on this install, before the first question. Not
                        // the packet this time: there is a new one every turn, and what
                        // somebody is actually agreeing to is what can leave and what can
                        // be done, which does not change.
                        if (agreed) {
                            typed = ""
                            onSend(question)
                        } else {
                            asking = question
                        }
                    },
                    enabled = !thinking && typed.isNotBlank(),
                ) { Text("Ask") }
            }
        }
    }

    asking?.let { question ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Before the first question") },
            text = {
                Column {
                    Text(
                        "What you type goes to ${model.ifBlank { "the model you chose" }}, " +
                            "along with the names of your folders. The text of a message " +
                            "goes too, but only one it has looked up and only when it " +
                            "needs to read it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "It can search, read, archive, delete to Trash, mark read and tag, " +
                            "and it can write a reply into the composer. It cannot send " +
                            "anything and cannot delete anything for good. Everything it " +
                            "does appears here and can be undone.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAgree()
                        asking = null
                        typed = ""
                        onSend(question)
                    },
                ) { Text("Go ahead") }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
}

/**
 * What it is for, before anybody has typed anything.
 *
 * Three examples rather than a description. Nobody reads a paragraph about a text box,
 * and the thing people cannot guess is that it can act at all.
 */
@Composable
private fun Opening() {
    Column(Modifier.padding(top = 14.dp)) {
        Text(
            "Ask about your mail, and it can act on it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            "What came in from the hotel this week?",
            "Archive everything from that newsletter.",
            "Draft a reply saying Tuesday works.",
        ).forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "It never sends anything and never deletes anything for good. A reply goes to " +
                "the composer for you to read.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** One line: yours on the right, its answers on the left, what it did in between. */
@Composable
private fun SaidLine(line: Said) {
    when (line.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                line.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            )
        }
        // Not a bubble and not the full text. What an action did is a receipt: worth
        // seeing, worth being able to check, not worth the width of the panel.
        "result" -> Text(
            line.text.lineSequence().first().take(160),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            fontWeight = FontWeight.Medium,
        )
        else -> Text(line.text, style = MaterialTheme.typography.bodyMedium)
    }
}
