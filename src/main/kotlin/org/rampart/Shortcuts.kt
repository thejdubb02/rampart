package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class Shortcut(
    /** What to show, eg "J", "Ctrl+Enter", "/". */
    val keys: String,
    val does: String,
    /** One of "Moving around", "Acting on a message", "Writing", "Everything else". */
    val group: String,
)

/**
 * Every shortcut Rampart actually answers to, and nothing else. A list that promises a key
 * the handler does not know about is worse than no list: the only way to find out is to
 * press it and watch nothing happen.
 */
internal val SHORTCUTS: List<Shortcut> = listOf(
    Shortcut("J or Down", "Next message", "Moving around"),
    Shortcut("K or Up", "Previous message", "Moving around"),
    Shortcut("/", "Search", "Moving around"),
    Shortcut("Esc", "Clear the search, or close what is open", "Moving around"),
    Shortcut("F5", "Check for mail now", "Moving around"),
    Shortcut("R", "Reply", "Acting on a message"),
    Shortcut("A", "Reply to everyone", "Acting on a message"),
    Shortcut("F", "Forward", "Acting on a message"),
    Shortcut("E", "Archive", "Acting on a message"),
    Shortcut("Del", "Move to trash", "Acting on a message"),
    Shortcut("S", "Star or unstar", "Acting on a message"),
    Shortcut("Shift+click", "Pick a run of messages", "Acting on a message"),
    Shortcut("Ctrl+click", "Add one message to the picked set", "Acting on a message"),
    Shortcut("Right-click", "Everything above, on the message under the pointer", "Acting on a message"),
    Shortcut("C", "Write a new message", "Writing"),
    Shortcut("Ctrl+B", "Bold", "Writing"),
    Shortcut("Ctrl+I", "Italic", "Writing"),
    // Shadows the command palette, but only while the composer has the focus, which is
    // what Ctrl+K does in every other editor somebody has used.
    Shortcut("Ctrl+K", "Link", "Writing"),
    Shortcut("Ctrl+Enter", "Send", "Writing"),
    Shortcut("Esc", "Close the composer", "Writing"),
    Shortcut("Ctrl+K", "Every command, by name", "Everything else"),
    Shortcut("?", "This list", "Everything else"),
    Shortcut("Ctrl+,", "Settings", "Everything else"),
)

/**
 * Shown over the mail, not as its own pane, because it is a reminder you dismiss
 * rather than a place you go.
 */
@Composable
internal fun ShortcutsOverlay(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
            modifier = Modifier.padding(28.dp).widthIn(max = 520.dp).fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("Keyboard shortcuts", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SHORTCUTS.groupBy { it.group }.forEach { (name, rows) ->
                    Spacer(Modifier.height(16.dp))
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    rows.forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                row.keys,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(140.dp),
                            )
                            Text(row.does, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
