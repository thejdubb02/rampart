package org.rampart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * The buttons a row shows under the pointer, drawn and chosen. The rules are in
 * HoverActions.kt.
 */

/**
 * The hover buttons in force, held once for the whole window.
 *
 * The same shape as [ListLayoutState]: the settings page and the rows are far apart, and
 * the choice is snapshot state, so a change on the settings page redraws the list at once
 * with nothing threaded between them.
 */
internal object HoverChoice {
    var actions by mutableStateOf(runCatching { hoverActionsOf(Settings.hoverActions()) }.getOrDefault(DEFAULT_HOVER_ACTIONS))
        private set

    fun toggle(action: HoverAction) {
        actions = toggledHoverAction(actions, action)
        Settings.setHoverActions(hoverActionKeys(actions))
    }

    /** Read again after something else wrote the file, such as settings sync. */
    fun reload() {
        actions = hoverActionsOf(Settings.hoverActions())
    }

    /**
     * Which row has a hover menu open (Snooze or Move), by row token and button.
     *
     * Held here rather than in the button, because the buttons only exist while the pointer
     * is over the row, and moving the pointer onto the menu takes it off the row. The row
     * stays in its hover state while its menu is open, which is what keeps the menu there.
     */
    var menuFor by mutableStateOf<String?>(null)

    /** Whether the row [token] has one of its hover menus open. */
    fun menuOpenOn(token: String): Boolean = menuFor?.substringBefore('\u0001') == token
}

/**
 * The buttons a row shows under the pointer, in the order they were chosen.
 *
 * With nothing chosen this is read, archive and delete, drawn exactly as the row drew them
 * before they were a choice. A button whose action the list does not offer is left out,
 * the same as before.
 */
@Composable
internal fun HoverButtons(message: Summary, actions: RowActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HoverChoice.actions.forEach { action ->
            when (action) {
                HoverAction.READ -> actions.markRead?.let { mark ->
                    RowButton(
                        if (message.seen) RampartIcons.Unread else RampartIcons.Read,
                        if (message.seen) "Mark unread" else "Mark read",
                    ) { mark(message, !message.seen) }
                }
                HoverAction.ARCHIVE -> actions.archive?.let { archive ->
                    RowButton(RampartIcons.Archive, "Archive") { archive(message) }
                }
                HoverAction.DELETE -> actions.trash?.let { trash ->
                    RowButton(RampartIcons.Trash, "Delete") { trash(message) }
                }
                HoverAction.STAR -> actions.star?.let { star ->
                    RowButton(RampartIcons.Star, if (message.flagged) "Remove star" else "Star") { star(message) }
                }
                HoverAction.SNOOZE -> actions.snooze?.let { put ->
                    HoverMenuButton(rowToken(message), RampartIcons.Calendar, "Snooze") { close ->
                        SnoozeUntil.entries.forEach { until ->
                            HoverMenuEntry(until.label) { close(); put(message, until) }
                        }
                    }
                }
                HoverAction.MOVE -> {
                    val move = actions.moveInto
                    val folders = actions.folders?.invoke(message).orEmpty()
                    if (move != null && folders.isNotEmpty()) {
                        HoverMenuButton(rowToken(message), RampartIcons.Move, "Move") { close ->
                            folders.forEach { folder ->
                                HoverMenuEntry(folder.name) { close(); move(message, folder.id) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A hover button that opens a short menu, for the two actions that need a second choice. */
@Composable
private fun HoverMenuButton(
    token: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    what: String,
    entries: @Composable (close: () -> Unit) -> Unit,
) {
    val key = token + "\u0001" + what
    val close = { if (HoverChoice.menuFor == key) HoverChoice.menuFor = null }
    Box {
        RowButton(icon, what) { HoverChoice.menuFor = key }
        MenuLayer(expanded = HoverChoice.menuFor == key, onDismissRequest = close) {
            entries(close)
        }
    }
}

@Composable
private fun HoverMenuEntry(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        onClick = onClick,
    )
}

/** Settings, Appearance: which buttons a row shows on hover. */
@Composable
internal fun HoverActionsSection() {
    Spacer(Modifier.height(18.dp))
    Section(
        "Buttons on a row under the pointer",
        "They take the place of the time, so up to $HOVER_ACTIONS_MAX fit. Right-click still offers everything.",
    )
    val chosen = HoverChoice.actions
    HoverAction.entries.forEach { action ->
        val on = action in chosen
        val full = !on && chosen.size >= HOVER_ACTIONS_MAX
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !full) { HoverChoice.toggle(action) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = on, enabled = !full, onCheckedChange = { HoverChoice.toggle(action) })
            Spacer(Modifier.width(12.dp))
            Text(
                action.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (full) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    if (chosen.size >= HOVER_ACTIONS_MAX) {
        Text(
            "Four are chosen, which is all the row has room for. Turn one off to choose another.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
