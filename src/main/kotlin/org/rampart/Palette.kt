package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

internal data class Command(
    /** What to show, eg "Reply to everyone". Sentence case, no ending full stop. */
    val label: String,
    /** The keystroke that also does it, eg "C", or null when there is none. */
    val keys: String?,
    /** Stable id the window matches on to decide what to run. */
    val id: String,
)

/**
 * The commands the palette can run, in the order they appear when nothing is typed.
 * Ids are the contract with the window; a list that promises an id the window does not
 * handle is worse than no list.
 */
/**
 * Everything the palette can run, in the order it is offered.
 *
 * Only what the window can actually carry out. Tagging and saving a message as .eml are
 * missing on purpose: both need something already open on screen to act on, and a command
 * that silently does nothing is worse than one that is not offered.
 */
internal val COMMANDS: List<Command> = listOf(
    Command("Write a new message", "C", "compose"),
    Command("Reply", "R", "reply"),
    Command("Reply to everyone", "A", "reply-all"),
    Command("Forward", "F", "forward"),
    Command("Archive", "E", "archive"),
    Command("Move to trash", "Del", "trash"),
    // One entry for both directions. The command does whichever of the two the open message
    // actually offers, so a name that only says one half would be wrong half the time.
    Command("Mark as spam, or not spam", null, "junk"),
    Command("Star or unstar", "S", "star"),
    Command("Mark as read", null, "read"),
    Command("View source", null, "source"),
    Command("Search", "/", "search"),
    Command("Check for mail now", "F5", "refresh"),
    Command("Next message", "J", "next"),
    Command("Previous message", "K", "previous"),
    Command("Go to inbox", null, "go-inbox"),
    Command("Go to all inboxes", null, "go-unified"),
    Command("Go to archive", null, "go-archive"),
    Command("Go to sent", null, "go-sent"),
    Command("Go to drafts", null, "go-drafts"),
    Command("Show only unread", "U", "unread-only"),
    Command("Contacts", null, "contacts"),
    Command("New folder", null, "new-folder"),
    Command("Settings", "Ctrl+,", "settings"),
    Command("Keyboard shortcuts", "?", "shortcuts"),
)

/**
 * Letters in order, not a prefix of the title. That is how "gtarch" reaches "Go to archive"
 * without a fuzzy-matching library. Prefix and contiguous hits rank above a scattered match.
 */
internal fun matches(query: String, commands: List<Command> = COMMANDS): List<Command> {
    if (query.isBlank()) return commands
    return commands
        .mapIndexedNotNull { index, command ->
            val rank = rank(command.label, query) ?: return@mapIndexedNotNull null
            Triple(rank, index, command)
        }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .map { it.third }
}

// Prefix, then a contiguous run, then a scattered subsequence. Typing the start of a name
// should not lose to the same letters appearing later.
private fun rank(label: String, query: String): Int? = when {
    label.startsWith(query, ignoreCase = true) -> 0
    label.contains(query, ignoreCase = true) -> 1
    isSubsequence(label, query) -> 2
    else -> null
}

private fun isSubsequence(label: String, query: String): Boolean {
    var at = 0
    for (ch in label) {
        if (ch.equals(query[at], ignoreCase = true)) {
            at++
            if (at == query.length) return true
        }
    }
    return false
}

/**
 * Shown over the mail, not as its own pane, because it is a way to run something
 * and vanish rather than a place you go.
 */
@Composable
internal fun CommandPalette(onClose: () -> Unit, onRun: (Command) -> Unit) {
    var query by remember { mutableStateOf("") }
    var highlight by remember { mutableStateOf(0) }
    val shown = matches(query).take(8)
    // Query changes shrink the list; a stale index would light up a row the user
    // did not move to, or none at all.
    val index = if (highlight in shown.indices) highlight else 0
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                // No ripple: the scrim is a way out, not a button, and a flash across the
                // whole window on the way out looks like something went wrong.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
        Surface(
            // Empty so a click on padding cannot fall through and close the card.
            onClick = {},
            modifier = Modifier.padding(top = 72.dp).width(520.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (query.isEmpty()) {
                        Text(
                            "Type a command",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            highlight = 0
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent {
                                when {
                                    it.type != KeyEventType.KeyDown -> false
                                    it.key == Key.DirectionDown -> {
                                        if (shown.isNotEmpty()) {
                                            highlight = (index + 1).mod(shown.size)
                                        }
                                        true
                                    }
                                    it.key == Key.DirectionUp -> {
                                        if (shown.isNotEmpty()) {
                                            highlight = (index - 1).mod(shown.size)
                                        }
                                        true
                                    }
                                    it.key == Key.Enter || it.key == Key.NumPadEnter -> {
                                        shown.getOrNull(index)?.let { command ->
                                            onRun(command)
                                            onClose()
                                        }
                                        true
                                    }
                                    it.key == Key.Escape -> {
                                        onClose()
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                }
                if (shown.isEmpty()) {
                    Text(
                        "Nothing matches that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    shown.forEachIndexed { i, command ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(
                                    if (i == index) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                command.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (command.keys != null) {
                                Text(
                                    command.keys,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
