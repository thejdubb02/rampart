package org.rampart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The reading pane's action row.
 *
 * One height, from the first frame. Reply and Forward are there while the
 * body is still loading, only disabled, so the message underneath does not
 * jump when they wake up. Anything that does not fit goes into More. A button
 * that wraps its label makes the whole row as tall as the word is long.
 */
@Composable
internal fun ReadingToolbar(
    summary: Summary,
    body: Body?,
    bodyReady: Boolean,
    replyAll: Boolean,
    darkWindow: Boolean,
    paper: Boolean,
    canPrint: Boolean,
    sourceOpen: Boolean,
    actions: MessageActions,
    inContacts: Boolean,
    onAddContact: ((Summary) -> Unit)?,
    onReply: (Boolean) -> Unit,
    onForward: () -> Unit,
    onForwardFile: () -> Unit,
    onPaper: (Boolean) -> Unit,
    onSource: () -> Unit,
    onPrint: () -> Unit,
    onUnsubscribe: (Unsubscribe) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(ToolbarHeight).padding(horizontal = 12.dp)) {
        val kept = toolbarKept(maxWidth, darkWindow, replyAll || !bodyReady)
        val show = kept.toSet()
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if ("star" in show) {
                val star = actions.star
                ToolIcon(
                    RampartIcons.Star,
                    if (summary.flagged) "Remove the star" else "Star this",
                    enabled = star != null,
                    tint = if (summary.flagged) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                ) { star?.invoke() }
            }
            if ("reply" in show) {
                ToolText("Reply", enabled = bodyReady) {
                    onReply(bareReplyAll(Settings.defaultReplyAll(), replyAll))
                }
            }
            if ("reply-all" in show && (replyAll || !bodyReady)) {
                ToolText("Reply all", enabled = bodyReady && replyAll) { onReply(true) }
            }
            if ("forward" in show) {
                ToolText("Forward", enabled = bodyReady) { onForward() }
            }
            Spacer(Modifier.weight(1f))
            if ("archive" in show) {
                val archive = actions.archive
                ToolIcon(RampartIcons.Archive, "Archive", enabled = archive != null) { archive?.invoke() }
            }
            if ("move" in show) {
                MoveButton(summary.id, actions)
            }
            if ("spam" in show) {
                val rescue = actions.notJunk
                val junk = actions.junk
                if (rescue != null) {
                    ToolIcon(RampartIcons.Inbox, "Not spam") { rescue() }
                } else {
                    ToolIcon(RampartIcons.Junk, "Spam", enabled = junk != null) { junk?.invoke() }
                }
            }
            if ("delete" in show) {
                val trash = actions.trash
                ToolIcon(RampartIcons.Trash, "Delete", enabled = trash != null) { trash?.invoke() }
            }
            if ("unread" in show) {
                val mark = actions.markUnread
                val unread = summary.seen
                ToolIcon(
                    if (unread) RampartIcons.Unread else RampartIcons.Read,
                    if (unread) "Unread" else "Read",
                    enabled = mark != null,
                ) { mark?.invoke() }
            }
            if ("paper" in show) {
                ToolIcon(
                    if (paper) RampartIcons.Bulb else RampartIcons.BulbOn,
                    if (paper) "Draw this message dark" else "Show it as the sender drew it",
                    tint = if (paper) LocalContentColor.current else MaterialTheme.colorScheme.primary,
                ) { onPaper(!paper) }
            }
            MoreMenu(
                summary = summary,
                body = body,
                bodyReady = bodyReady,
                replyAll = replyAll,
                kept = show,
                darkWindow = darkWindow,
                paper = paper,
                canPrint = canPrint,
                sourceOpen = sourceOpen,
                actions = actions,
                inContacts = inContacts,
                onAddContact = onAddContact,
                onReply = onReply,
                onForward = onForward,
                onForwardFile = onForwardFile,
                onPaper = onPaper,
                onSource = onSource,
                onPrint = onPrint,
                onUnsubscribe = onUnsubscribe,
            )
        }
    }
}

/** The buttons that fit on one line, dropping the less common ones into More first. */
internal fun toolbarKept(width: Dp, darkWindow: Boolean, replyAllSlot: Boolean = true): List<String> {
    val preferred = buildList {
        add("star")
        add("reply")
        if (replyAllSlot) add("reply-all")
        add("forward")
        add("archive")
        add("move")
        add("spam")
        add("delete")
        add("unread")
        if (darkWindow) add("paper")
        add("more")
    }
    val kept = preferred.toMutableList()
    while (kept.size > 3 && toolbarWidth(kept) > width) {
        val moreAt = kept.indexOf("more")
        if (moreAt <= 0) break
        kept.removeAt(moreAt - 1)
    }
    return kept
}

private fun toolbarWidth(ids: List<String>): Dp {
    if (ids.isEmpty()) return 0.dp
    val buttons = ids.fold(0.dp) { acc, id ->
        acc + when (id) {
            "reply" -> 78.dp
            "reply-all" -> 108.dp
            "forward" -> 96.dp
            else -> 34.dp
        }
    }
    // The gap between buttons, plus the flexible space, which can shrink to nothing.
    return buttons + 6.dp * (ids.size - 1)
}

private val ToolbarHeight = 44.dp

@Composable
private fun ToolText(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ToolIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = LocalContentColor.current,
    onClick: () -> Unit,
) {
    SidebarTooltip(label) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun MoveButton(messageId: String, actions: MessageActions) {
    var picking by remember(messageId) { mutableStateOf(false) }
    val move = actions.moveInto
    val enabled = move != null && actions.folders.isNotEmpty()
    Box {
        ToolIcon(RampartIcons.Move, "Move", enabled = enabled) { picking = true }
        DropdownMenu(picking, onDismissRequest = { picking = false }) {
            actions.folders.forEach { folder ->
                DropdownMenuItem(
                    text = {
                        Text(
                            folder.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = (depthOf(folder, actions.folders) * 12).dp),
                        )
                    },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    onClick = {
                        picking = false
                        move?.invoke(folder.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun MoreMenu(
    summary: Summary,
    body: Body?,
    bodyReady: Boolean,
    replyAll: Boolean,
    kept: Set<String>,
    darkWindow: Boolean,
    paper: Boolean,
    canPrint: Boolean,
    sourceOpen: Boolean,
    actions: MessageActions,
    inContacts: Boolean,
    onAddContact: ((Summary) -> Unit)?,
    onReply: (Boolean) -> Unit,
    onForward: () -> Unit,
    onForwardFile: () -> Unit,
    onPaper: (Boolean) -> Unit,
    onSource: () -> Unit,
    onPrint: () -> Unit,
    onUnsubscribe: (Unsubscribe) -> Unit,
) {
    var more by remember(summary.id) { mutableStateOf(false) }
    Box {
        ToolIcon(RampartIcons.More, "More") { more = true }
        DropdownMenu(more, onDismissRequest = { more = false }) {
            if ("reply" !in kept) {
                MenuItem("Reply", enabled = bodyReady) {
                    more = false
                    onReply(bareReplyAll(Settings.defaultReplyAll(), replyAll))
                }
            }
            if ("reply-all" !in kept && (replyAll || !bodyReady)) {
                MenuItem("Reply all", enabled = bodyReady && replyAll) {
                    more = false
                    onReply(true)
                }
            }
            if ("forward" !in kept) {
                MenuItem("Forward", enabled = bodyReady) {
                    more = false
                    onForward()
                }
            }
            MenuItem("Forward as attachment", enabled = bodyReady) {
                more = false
                onForwardFile()
            }
            if ("archive" !in kept) {
                actions.archive?.let { archive ->
                    MenuItem("Archive") { more = false; archive() }
                }
            }
            if ("move" !in kept && actions.moveInto != null) {
                val move = actions.moveInto
                actions.folders.forEach { folder ->
                    MenuItem("Move to ${folder.name}") {
                        more = false
                        move(folder.id)
                    }
                }
            }
            if ("spam" !in kept) {
                actions.notJunk?.let { rescue -> MenuItem("Not spam") { more = false; rescue() } }
                actions.junk?.let { junk -> MenuItem("Spam") { more = false; junk() } }
            }
            if ("delete" !in kept) {
                actions.trash?.let { trash -> MenuItem("Delete") { more = false; trash() } }
            }
            if ("unread" !in kept) {
                actions.markUnread?.let { mark ->
                    MenuItem(if (summary.seen) "Unread" else "Read") { more = false; mark() }
                }
            }
            if (darkWindow && "paper" !in kept) {
                MenuItem(if (paper) "Draw this message dark" else "Show it as the sender drew it") {
                    more = false
                    onPaper(!paper)
                }
            }
            if (canPrint) {
                MenuItem("Print") { more = false; onPrint() }
            }
            actions.snooze?.let { put ->
                HorizontalDivider()
                SnoozeUntil.entries.forEach { until ->
                    MenuItem(until.label) { more = false; put(until) }
                }
            }
            HorizontalDivider()
            MenuItem(if (sourceOpen) "Back to the message" else "View source") {
                more = false
                onSource()
            }
            if (onAddContact != null && !inContacts) {
                MenuItem("Add to contacts") {
                    more = false
                    onAddContact(summary)
                }
            }
            unsubscribeFrom(body?.listUnsubscribe, body?.listUnsubscribePost)?.let { off ->
                MenuItem(if (off.oneClick) "Unsubscribe" else "Unsubscribe...") {
                    more = false
                    onUnsubscribe(off)
                }
            }
            actions.conversation?.let { conv ->
                HorizontalDivider()
                MenuItem(
                    if (conv.unread > 0) "Mark whole conversation read" else "Mark whole conversation unread",
                ) { more = false; conv.onRead(conv.unread > 0) }
                MenuItem("Archive whole conversation") { more = false; conv.onArchive() }
                MenuItem("Delete whole conversation") { more = false; conv.onTrash() }
                MenuItem(if (conv.muted) "Stop muting it" else "Mute this conversation") {
                    more = false
                    conv.onMute(!conv.muted)
                }
                if (conv.muted) {
                    Text(
                        "New messages in this conversation are marked read and archived while Rampart is running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        enabled = enabled,
        onClick = onClick,
    )
}
