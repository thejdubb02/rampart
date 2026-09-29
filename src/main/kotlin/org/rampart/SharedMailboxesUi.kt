package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The window's side of shared mailboxes (RAM-45): the state the reader holds, the rows for
 * the cross-account views, the dialog that chooses what feeds them, and the dialog that
 * shares a folder. The logic is in SharedMailboxes.kt, SharedAccounts.kt, SharingRights.kt
 * and UnifiedViews.kt; Main.kt only calls into this file.
 */

/**
 * What the reader keeps about shared mailboxes for as long as the same accounts are signed
 * in: the shared sessions themselves, the view preferences, and which dialog is open.
 */
@Stable
internal class SharingState(val shared: List<Session>) {
    var prefs by mutableStateOf(Settings.sharingPrefs())
        private set

    /** The view whose chooser is open, or null. */
    var choosing by mutableStateOf<UnifiedView?>(null)

    fun session(key: String): Session? = shared.firstOrNull { it.key == key }

    private fun backend(key: String): SharedBackend? = session(key)?.jmap as? SharedBackend

    fun setIncluded(key: String, included: Boolean) {
        Settings.setSharingPref(includeChange(key, included))
        prefs = Settings.sharingPrefs()
    }

    fun setFeed(view: UnifiedView, selectors: List<String>) {
        Settings.setSharingPref(feedChange(view, selectors))
        prefs = Settings.sharingPrefs()
    }

    /**
     * The shared mailboxes worth a sidebar heading: those holding at least one folder you can
     * see. Headed with the owner's name and the word shared, so a group's Inbox is never
     * mistaken for your own.
     */
    fun sidebarAccounts(mailboxes: Map<String, List<Mailbox>>): List<AccountMailboxes> =
        shared.mapNotNull { open ->
            val boxes = mailboxes[open.key].orEmpty().ifEmpty { return@mapNotNull null }
            val owner = open.account.name
            AccountMailboxes(open.key, "${shortAccountName(owner, owner)} (shared)", owner, boxes)
        }

    fun inUnified(logins: List<Session>, mailboxes: Map<String, List<Mailbox>>): List<Session> =
        unifiedSessions(logins, shared, mailboxes, prefs)

    /** The line over a shared folder's list saying what it will not let you do, or null. */
    fun folderNote(key: String, mailboxId: String): String? =
        backend(key)?.rightsOf(mailboxId)?.let(::sharedFolderNote)

    /**
     * Why [job] cannot be done to [mailbox] under [key], or null when it can. Only a shared
     * mailbox ever says no here; your own folders are yours, and the share dialog explains
     * for itself when the server cannot share at all.
     */
    fun folderRefusal(key: String, mailbox: Mailbox, job: FolderJob): String? {
        val backend = backend(key) ?: return null
        val rights = backend.rightsOf(mailbox.id)
        return when (job) {
            FolderJob.CreateInside -> refusal(FolderAction.NEW_FOLDER, rights)
            FolderJob.Rename, FolderJob.ToTop -> refusal(FolderAction.RENAME, rights)
            FolderJob.Delete -> refusal(FolderAction.DELETE_FOLDER, rights)
            FolderJob.Import -> refusal(FolderAction.FILE_INTO, rights)
            FolderJob.Share -> refusal(FolderAction.SHARE, rights)
            FolderJob.Export -> null
        }
    }
}

@Composable
internal fun rememberSharing(logins: List<Session>): SharingState =
    remember(logins) { SharingState(sharedSessionsOf(logins)) }

@Composable
private fun iconFor(view: UnifiedView): ImageVector = when (view) {
    UnifiedView.UNREAD -> RampartIcons.Unread
    UnifiedView.STARRED -> RampartIcons.Star
    UnifiedView.ALL_MAIL -> RampartIcons.Archive
}

/**
 * The three cross-account views, drawn under "All inboxes" in the same shape as a folder
 * row. A right-click chooses which folders and which shared mailboxes feed them.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun UnifiedViewRows(
    collapsed: Boolean,
    selectedId: String?,
    onSelect: (Mailbox) -> Unit,
    onChoose: (UnifiedView) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        UnifiedView.entries.forEach { view ->
            val selected = selectedId == view.id
            val role = when (view) {
                UnifiedView.UNREAD -> "unread"
                UnifiedView.STARRED -> "starred"
                UnifiedView.ALL_MAIL -> "all"
            }
            val plain = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            // The role colour stays on a selected row. The highlight behind the row already marks it.
            val tint = sidebarRoleColour(role).takeIf { LocalSidebarIcons.current == SidebarIcons.COLOUR } ?: plain
            var menu by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .rowHover(showWash = !selected)
                    .clickable(onClick = { onSelect(view.mailbox()) })
                    .onPointerEvent(PointerEventType.Press) { event ->
                        if (event.button == PointerButton.Secondary) menu = true
                    }
                    .padding(start = if (collapsed) 0.dp else 10.dp, end = if (collapsed) 0.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
            ) {
                MenuLayer(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Choose what feeds ${view.label}") },
                        onClick = { menu = false; onChoose(view) },
                    )
                }
                Icon(
                    iconFor(view),
                    contentDescription = if (collapsed) view.label else null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
                if (!collapsed) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        view.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Which folders feed [view], and which shared mailboxes go into the cross-account views at
 * all. The folders are chosen once for every account, by role or by name, so "Projects"
 * means the Projects folder wherever there is one.
 */
@Composable
internal fun UnifiedViewsDialog(
    state: SharingState,
    view: UnifiedView,
    mailboxes: Map<String, List<Mailbox>>,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val sharedShown = state.sidebarAccounts(mailboxes)
    val choices = remember(mailboxes) { feedChoices(mailboxes.values) }
    var picked by remember(view) { mutableStateOf(feedOf(view, state.prefs).toSet()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("What feeds ${view.label}") },
        text = {
            Column(Modifier.widthIn(min = 320.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Folders, on every account that has them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                choices.forEach { (selector, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selector in picked,
                            onCheckedChange = { on -> picked = if (on) picked + selector else picked - selector },
                        )
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (picked.isEmpty()) {
                    Text(
                        "With nothing ticked, ${view.label} goes back to its usual folders.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (sharedShown.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Shared mailboxes in the cross-account views", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Your own accounts are always in them. These switches apply to All inboxes too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    sharedShown.forEach { account ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(account.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(
                                checked = includedInUnified(account.key, state.prefs),
                                onCheckedChange = { on -> state.setIncluded(account.key, on); onChanged() },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Kept in the order the chooser shows them, so the saved setting reads the same way.
                state.setFeed(view, choices.map { it.first }.filter { it in picked })
                onChanged()
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/**
 * Who a folder is shared with, and sharing it with somebody else at one of three levels.
 *
 * Every change is sent at once and the list read back from the server afterwards, so what
 * is on screen is what the server holds rather than what was asked for.
 */
@Composable
internal fun ShareFolderDialog(sharing: FolderSharing, mailbox: Mailbox, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember(mailbox.id) { mutableStateOf<List<ShareRow>?>(null) }
    var error by remember(mailbox.id) { mutableStateOf<String?>(null) }
    var busy by remember(mailbox.id) { mutableStateOf(false) }
    var address by remember(mailbox.id) { mutableStateOf("") }
    var level by remember(mailbox.id) { mutableStateOf(ShareLevel.READ) }

    /** Does [action] off the window's thread, then reads the list back. [done] runs only if both worked. */
    fun act(done: () -> Unit = {}, action: () -> Unit) {
        busy = true
        error = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    action()
                    sharing.current(mailbox.id)
                }
            }
            outcome.onSuccess { rows = it; done() }
            outcome.onFailure { e -> error = whyFailed(e).ifBlank { "The server would not do that." } }
            busy = false
        }
    }

    LaunchedEffect(mailbox.id) {
        if (sharing.unavailable == null) act { }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Share ${mailbox.name}") },
        text = {
            Column(Modifier.widthIn(min = 360.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                val unavailable = sharing.unavailable
                if (unavailable != null) {
                    Text(unavailable, style = MaterialTheme.typography.bodyMedium)
                } else {
                    ShareFolderBody(
                        rows = rows,
                        error = error,
                        busy = busy,
                        address = address,
                        onAddress = { address = it },
                        level = level,
                        onLevel = { level = it },
                        onChange = { row, option -> act { sharing.change(mailbox.id, row, option) } },
                    )
                }
            }
        },
        confirmButton = {
            if (sharing.unavailable == null) {
                TextButton(
                    enabled = !busy && address.isNotBlank(),
                    onClick = {
                        val who = address
                        val chosen = level
                        // The address stays in the box when the server says no, so it can be corrected.
                        act(done = { address = "" }) { sharing.shareWith(mailbox.id, who, chosen) }
                    },
                ) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Close") } },
    )
}

/** The inside of [ShareFolderDialog] when the server can share: who has it, and the box to add someone. */
@Composable
private fun ShareFolderBody(
    rows: List<ShareRow>?,
    error: String?,
    busy: Boolean,
    address: String,
    onAddress: (String) -> Unit,
    level: ShareLevel,
    onLevel: (ShareLevel) -> Unit,
    /** A new level for a row already listed, or null to take their access away. */
    onChange: (ShareRow, ShareLevel?) -> Unit,
) {
    Column {
        Text(
            "Read lets them see the mail. Read and write adds filing, deleting and flags. " +
                "Manage adds folders inside it, renaming and sharing it on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(10.dp))
        when {
            rows == null && error == null -> Text("Reading who it is shared with.", style = MaterialTheme.typography.bodySmall)
            rows.isNullOrEmpty() -> Text("Not shared with anyone.", style = MaterialTheme.typography.bodyMedium)
            else -> rows.orEmpty().forEach { row ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // A grant made elsewhere with a mix Rampart does not offer is left as
                        // it is until one of the three levels is picked for it.
                        if (row.level == null) {
                            Text("Custom", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        TextButton(enabled = !busy, onClick = { onChange(row, null) }) { Text("Remove") }
                    }
                    LevelChips(selected = row.level, busy = busy) { option ->
                        if (row.level != option) onChange(row, option)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        OutlinedTextField(
            value = address,
            onValueChange = onAddress,
            enabled = !busy,
            singleLine = true,
            label = { Text("Their address") },
            modifier = Modifier.fillMaxWidth(),
        )
        LevelChips(selected = level, busy = busy, onPick = onLevel)
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * One entry in a folder's right-click menu. With a [reason] it is shown greyed out with the
 * reason under it, so a folder shared with you says what it will not let you do rather than
 * offering it and failing.
 */
@Composable
internal fun FolderMenuItem(label: String, reason: String?, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            if (reason == null) {
                Text(label)
            } else {
                Column(Modifier.widthIn(max = 260.dp)) {
                    Text(label)
                    Text(reason, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        enabled = reason == null,
        onClick = onClick,
    )
}

/** The three levels as chips, one of them picked. */
@Composable
private fun LevelChips(selected: ShareLevel?, busy: Boolean, onPick: (ShareLevel) -> Unit) {
    Row(
        Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShareLevel.entries.forEach { option ->
            FilterChip(
                selected = selected == option,
                enabled = !busy,
                onClick = { onPick(option) },
                label = { Text(option.label) },
            )
        }
    }
}
