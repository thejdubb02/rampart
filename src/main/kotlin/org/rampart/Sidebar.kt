package org.rampart

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.first

/**
 * A hover label for an icon-only button, the row of five at the bottom of the sidebar
 * being the case that actually needed one: nothing there carries a word, so what each
 * one does is a guess until it is clicked.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SidebarTooltip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            CoverBody(true)
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        content = content,
    )
}

@Composable
internal fun Sidebar(
    accounts: List<AccountMailboxes>,
    here: Pair<String, Mailbox>?,
    collapsed: Boolean = false,
    inSettings: Boolean = false,
    inDashboard: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    onSettings: () -> Unit,
    onDashboard: () -> Unit = {},
    onAddAccount: () -> Unit,
    onWrite: () -> Unit,
    /** The version line at the bottom is clicked to see what changed. A no-op default so
     *  the screenshot tests, which have no changelog to open, need not pass one. */
    onViewChangelog: () -> Unit = {},
    /** What a right-click on a folder can ask for. Null hides the menu entirely. */
    folderMenu: ((String, Mailbox, FolderJob) -> Unit)? = null,
    onSelect: (String, Mailbox) -> Unit,
    savedSearches: List<SavedSearch> = emptyList(),
    selectedSavedSearchId: String? = null,
    savedSearchCounts: Map<String, Int> = emptyMap(),
    onSelectSavedSearch: (SavedSearch) -> Unit = {},
    onManageSavedSearch: ((SavedSearch, SavedSearchJob) -> Unit)? = null,
    /** Every tag on every account, merged into one list. */
    tags: List<TagRow> = emptyList(),
    /** The tag being looked at, across every account that has it. */
    hereTag: String? = null,
    onSelectTag: (String) -> Unit = {},
    /** A colour chosen for a tag, or null to put it back to the one from its name. */
    onTagColour: (String, Long?) -> Unit = { _, _ -> },
    /** The sections folded away, by the ids [foldFolders], [foldTags] and [foldTag] make. */
    folded: Set<String> = emptySet(),
    /** Folds a section away, or opens it again. */
    onFold: (String) -> Unit = {},
    /** Where the pointer is while a message is being dragged, in window coordinates. */
    dragAt: Offset? = null,
    /** Where each tag row ended up, so a drag that ends over one can find it. */
    onTagBounds: (String, Rect) -> Unit = { _, _ -> },
    /** The search field, drawn at the top. Absent while the sidebar is narrowed. */
    search: @Composable () -> Unit = {},
    /** Why a folder job cannot be done, for a folder shared with you (SharedMailboxesUi.kt). */
    folderRefusal: (String, Mailbox, FolderJob) -> String? = { _, _, _ -> null },
    /** The cross-account views under "All inboxes" (SharedMailboxesUi.kt), given whether the sidebar is narrowed. */
    unifiedViews: @Composable (Boolean) -> Unit = {},
) {
    Column(
        Modifier.width(if (collapsed) 60.dp else 232.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = if (collapsed) 8.dp else 12.dp, vertical = 8.dp),
        horizontalAlignment = if (collapsed) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        // Nothing at all when narrowed: a field 44dp wide is not a field, and the sidebar
        // is narrowed precisely to get the width back. Ctrl+K still reaches search.
        if (!collapsed) {
            search()
            Spacer(Modifier.height(10.dp))
        }
        if (collapsed) {
            FilledIconButton(onClick = onWrite, modifier = Modifier.size(40.dp)) {
                Icon(RampartIcons.Write, contentDescription = "Write", modifier = Modifier.size(17.dp))
            }
        } else {
            Button(
                onClick = onWrite,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(40.dp),
            ) {
                Icon(RampartIcons.Write, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Write", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(14.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // One account has nothing to merge, so the row would be a second name for Inbox.
            if (accounts.size > 1) {
                item(key = "all-inboxes") {
                    val unread = accounts.sumOf { a ->
                        folderFor("inbox", a.mailboxes)?.unread ?: 0
                    }
                    FolderRow(
                        mailbox = allInboxes(unread),
                        collapsed = collapsed,
                        selected = selectedSavedSearchId == null && here?.first == ALL_ACCOUNTS && here.second.id == ALL_ACCOUNTS,
                        onClick = { onSelect(ALL_ACCOUNTS, allInboxes(unread)) },
                    )
                }
                item(key = "unified-views") { unifiedViews(collapsed) }
                val allSavedSearches = savedSearchesForAccount(savedSearches, ALL_ACCOUNTS)
                if (allSavedSearches.isNotEmpty()) {
                    if (!collapsed) {
                        item(key = "saved-searches-head-all") {
                            GroupHeading(
                                text = "Saved searches",
                                open = foldSavedSearches(ALL_ACCOUNTS) !in folded,
                                onClick = { onFold(foldSavedSearches(ALL_ACCOUNTS)) },
                                top = 6.dp,
                            )
                        }
                    }
                    val shownAll = if (!collapsed && foldSavedSearches(ALL_ACCOUNTS) in folded) emptyList() else allSavedSearches
                    items(shownAll, key = { "saved-search/${it.id}" }) { searchItem ->
                        SavedSearchRow(
                            search = searchItem,
                            collapsed = collapsed,
                            selected = selectedSavedSearchId == searchItem.id,
                            unread = savedSearchCounts[searchItem.id] ?: 0,
                            onManage = onManageSavedSearch?.let { manage -> { job -> manage(searchItem, job) } },
                            onClick = { onSelectSavedSearch(searchItem) },
                        )
                    }
                }
                item(key = "all-inboxes-divider") {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            accounts.forEachIndexed { index, account ->
                // With one account the heading is noise. With two it is the only way to tell
                // one Inbox from the other. Collapsed, there is no room for it at all, so
                // the accounts are separated by a rule instead.
                if (accounts.size > 1) {
                    item(key = "head-${account.key}") {
                        if (collapsed) {
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        } else {
                            GroupHeading(
                                text = shortAccountName(account.name, account.email).uppercase(),
                                open = foldFolders(account.key) !in folded,
                                onClick = { onFold(foldFolders(account.key)) },
                                top = if (index == 0) 4.dp else 16.dp,
                            )
                        }
                    }
                }
                // One account has no heading to fold, so its folders are always shown.
                val folders = if (accounts.size > 1 && foldFolders(account.key) in folded) emptyList()
                else nested(account.mailboxes)
                items(folders, key = { "${account.key}/${it.mailbox.id}" }) { row ->
                    FolderRow(
                        mailbox = row.mailbox,
                        depth = row.depth,
                        collapsed = collapsed,
                        // A tag view is not in any folder, so nothing in the folder list is
                        // the thing being looked at while one is open.
                        selected = selectedSavedSearchId == null && hereTag == null && here?.first == account.key &&
                            here.second.id == row.mailbox.id,
                        onClick = { onSelect(account.key, row.mailbox) },
                        onManage = folderMenu?.let { manage -> { what -> manage(account.key, row.mailbox, what) } },
                        refusal = { job -> folderRefusal(account.key, row.mailbox, job) },
                    )
                }
                val accountSearches = if (accounts.size == 1) {
                    savedSearches.filter { it.account == account.key || it.account == ALL_ACCOUNTS }
                } else {
                    savedSearchesForAccount(savedSearches, account.key)
                }
                if (accountSearches.isNotEmpty()) {
                    if (!collapsed) {
                        item(key = "saved-searches-head-${account.key}") {
                            GroupHeading(
                                text = "Saved searches",
                                open = foldSavedSearches(account.key) !in folded,
                                onClick = { onFold(foldSavedSearches(account.key)) },
                                top = 6.dp,
                            )
                        }
                    }
                    val shownAccountSearches = if (!collapsed && foldSavedSearches(account.key) in folded) emptyList() else accountSearches
                    items(shownAccountSearches, key = { "saved-search/${it.id}" }) { searchItem ->
                        SavedSearchRow(
                            search = searchItem,
                            collapsed = collapsed,
                            selected = selectedSavedSearchId == searchItem.id,
                            unread = savedSearchCounts[searchItem.id] ?: 0,
                            onManage = onManageSavedSearch?.let { manage -> { job -> manage(searchItem, job) } },
                            onClick = { onSelectSavedSearch(searchItem) },
                        )
                    }
                }
            }
            /*
             * One TAGS section, under every account rather than inside each of them.
             *
             * A keyword lives in one mailbox and cannot be read from another, so per account
             * is the truthful model underneath and it is kept. It is the wrong thing to draw:
             * nobody has two Billing tags because they have two mailboxes, and a heading
             * under every account with nothing under most of them says the opposite of what
             * is true. Opening one asks every account that has it.
             *
             * Narrowed there is no room for a word, and a column of coloured dots says
             * nothing, so the tags are simply not there until the sidebar is open.
             */
            val rows = if (collapsed) emptyList() else tags
            if (rows.isNotEmpty()) {
                item(key = "tags-heading") {
                    GroupHeading(
                        text = "TAGS",
                        open = foldTags() !in folded,
                        onClick = { onFold(foldTags()) },
                        top = 16.dp,
                    )
                }
                val shown = if (foldTags() in folded) emptyList() else visibleTags(rows, "", folded)
                items(shown, key = { "tag/${it.keyword}" }) { row ->
                    TagLine(
                        row = row,
                        selected = hereTag.equals(row.keyword, ignoreCase = true),
                        // A branch with something under it folds when its own chevron is
                        // clicked, and opens the list when its name is.
                        open = if (rows.any { it.keyword.startsWith(row.keyword + NEST) }) {
                            foldTag("", row.keyword) !in folded
                        } else {
                            null
                        },
                        onFold = { onFold(foldTag("", row.keyword)) },
                        onClick = { if (row.real) onSelectTag(row.keyword) },
                        onColour = { onTagColour(row.keyword, it) },
                        dragAt = dragAt,
                        onMeasured = { onTagBounds(row.keyword, it) },
                    )
                }
            }
            /*
             * Accounts scroll with the folders. The icon row under this list is fixed,
             * so a short window scrolls the mailboxes instead of cutting those icons
             * in half.
             */
            if (collapsed) {
                items(accounts.filterNot { isSharedKey(it.key) }, key = { "face-${it.key}" }) { account ->
                    Box(Modifier.padding(vertical = 4.dp).rowHover()) {
                        Avatar(account.name, account.email, 26.dp)
                    }
                }
            } else {
                item(key = "account-stack") {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp).rowHover(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AccountStack(accounts.filterNot { isSharedKey(it.key) }, onClick = onSettings)
                        Spacer(Modifier.weight(1f))
                        AddAccountFace(onClick = onAddAccount)
                    }
                }
            }
        }
    }
}

/** The face size the account stack and its "+" use, matching the old per-row avatar. */
private val ACCOUNT_FACE_SIZE = 26.dp

/** How much of one face the next one covers. */
private val ACCOUNT_FACE_OVERLAP = 12.dp

/** The ring drawn around each face, in the sidebar's own background, so an overlap reads
 *  as one face sitting in front of another rather than two circles merging into one. */
private val ACCOUNT_FACE_RING = 2.dp

/** How many real faces the stack shows before the rest are folded into a "+N" badge. */
private const val MAX_ACCOUNT_FACES = 3

/**
 * The overlapped stack of avatars that replaced a full width row per account.
 *
 * Justin's own description of what he wanted: "show both icons overlapped showing its
 * logged into two or more emails, when you click on it it takes you the email settings
 * page." One account is not a stack: its avatar is shown alone, still clickable, because
 * reaching account settings from your own picture is a pattern nobody has to be taught
 * (macOS, Slack and Google all do exactly this even when there is only one account signed
 * in), and swapping to a different control the moment a second account arrives would make
 * this change shape under somebody's finger rather than just grow.
 *
 * Capped at [MAX_ACCOUNT_FACES] real faces. A stack that keeps widening for every account
 * stops reading as "there is more than one" and starts reading as the list this replaced;
 * past the cap the last slot becomes a "+N" badge instead, the same size as a face, so the
 * stack does not change width again as further accounts are added.
 */
@Composable
private fun AccountStack(accounts: List<AccountMailboxes>, onClick: () -> Unit) {
    val faceBox = ACCOUNT_FACE_SIZE + ACCOUNT_FACE_RING * 2
    val shown = accounts.take(MAX_ACCOUNT_FACES)
    // A plain Row would still measure each face at its full width even though later ones
    // are drawn shifted over the one before, leaving a gap of dead space between the last
    // visible face and whatever sits next to the stack. Sized explicitly instead, to the
    // width the faces actually cover once overlapped, so the "+" beside it sits where it
    // looks like it should rather than where an unshifted row would have put it.
    val width = faceBox + (faceBox - ACCOUNT_FACE_OVERLAP) * maxOf(0, shown.size - 1)
    Box(
        Modifier.size(width = width, height = faceBox)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Account settings", role = Role.Button, onClick = onClick),
    ) {
        if (accounts.size <= 1) {
            accounts.firstOrNull()?.let { account ->
                Face { Avatar(account.name, account.email, ACCOUNT_FACE_SIZE) }
            }
        } else {
            val overflow = accounts.size - shown.size
            shown.forEachIndexed { index, account ->
                Box(Modifier.offset(x = (faceBox - ACCOUNT_FACE_OVERLAP) * index)) {
                    // The badge takes the last shown slot rather than sitting beside it, so
                    // that account's own avatar is hidden too: the count it shows has to be
                    // one more than the accounts past the cap, not just the accounts past it.
                    if (overflow > 0 && index == shown.lastIndex) {
                        Face { OverflowBadge(overflow + 1) }
                    } else {
                        Face { Avatar(account.name, account.email, ACCOUNT_FACE_SIZE) }
                    }
                }
            }
        }
    }
}

/**
 * One face in the stack, ringed in the sidebar's own background so it reads as sitting in
 * front of whatever it overlaps rather than merging into it.
 */
@Composable
private fun Face(content: @Composable () -> Unit) {
    Box(
        Modifier.size(ACCOUNT_FACE_SIZE + ACCOUNT_FACE_RING * 2)
            .background(MaterialTheme.colorScheme.background, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * What the stack's last slot shows once there are more accounts than [MAX_ACCOUNT_FACES].
 * Sized and coloured like a face rather than drawn as one, so it reads as "more" rather
 * than as somebody's unlabelled initials.
 */
@Composable
private fun OverflowBadge(count: Int) {
    Box(
        Modifier.size(ACCOUNT_FACE_SIZE).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The "add another account" affordance beside the stack. Justin asked for "a plus button
 * to login to another one or something", so a plain "+" rather than a new glyph added to
 * the icon pack: [IconPack] is a large surface shared by every theme, and a button used in
 * exactly one place does not earn a new member on it. Its hit target is its own, separate
 * from [AccountStack]'s, so clicking it calls [onClick] (the existing onAddAccount)
 * directly rather than opening Settings first.
 */
@Composable
private fun AddAccountFace(onClick: () -> Unit) {
    Box(
        Modifier.size(ACCOUNT_FACE_SIZE)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClickLabel = "Add another account", role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * Accounts are remembered under the address they were signed in with, so the heading was
 * showing a truncated email over a list of folders. The part before the @ is what a person
 * would call it.
 */
internal fun shortAccountName(name: String, email: String): String {
    if (name.isNotBlank() && !name.contains('@')) return name
    val local = email.substringBefore('@')
    val host = email.substringAfter('@', "").substringBefore('.')
    return if (local.equals("admin", ignoreCase = true) && host.isNotBlank()) host else local.ifBlank { email }
}

/** A section name in the sidebar, with the chevron that folds what is under it. */
@Composable
private fun GroupHeading(text: String, open: Boolean, onClick: () -> Unit, top: Dp) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).rowHover().clickable(onClick = onClick)
            .padding(start = 10.dp, end = 10.dp, top = top, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Chevron(open)
    }
}

/**
 * Points down when the section is open and right when it is folded.
 *
 * One icon turned rather than two drawn. The pack has a chevron and a quarter turn is what
 * separates the two states anyway, so a second icon would be the same path with the numbers
 * swapped, in three packs.
 */
@Composable
private fun Chevron(open: Boolean) {
    Icon(
        RampartIcons.Expand,
        contentDescription = if (open) "Fold away" else "Open",
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(13.dp).rotate(if (open) 90f else 0f),
    )
}

/**
 * One tag in the sidebar.
 *
 * A dot in the tag's colour and its own level of the name, indented by how deep it is.
 * Only the last level is written: "Clients / Acme / Renewals" spelled out in full on every
 * line is three quarters repetition in a 232dp column, and the indent already says whose
 * it is.
 *
 * A level nobody has tagged anything with is a heading rather than a row: it can be dropped
 * onto, because that is a reasonable thing to mean, but clicking it would open a list that
 * is empty by definition.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun TagLine(
    row: TagRow,
    selected: Boolean,
    onClick: () -> Unit,
    onColour: (Long?) -> Unit,
    /** Whether this branch is open, or null on a tag with nothing under it. */
    open: Boolean? = null,
    onFold: () -> Unit = {},
    /**
     * Where the pointer is while a message is being dragged, in window coordinates, or
     * null when nothing is being dragged.
     *
     * A position rather than the usual enter and leave events, because the row being
     * dragged consumes pointer movement for the length of the drag and nothing underneath
     * hears about it. [onMeasured] hands this row's rectangle up so the drop can be worked
     * out where the drag actually ends.
     */
    dragAt: Offset? = null,
    onMeasured: (Rect) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val over = dragAt != null && bounds.contains(dragAt)
    val background = when {
        over -> MaterialTheme.colorScheme.secondaryContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp)
            .onGloballyPositioned { bounds = it.boundsInWindow(); onMeasured(bounds) }
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.button == PointerButton.Secondary) menu = true
            }
            .padding(start = 10.dp + (row.depth * 12).dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuLayer(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                "Colour",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp),
            )
            // Six to a row, so twelve colours are two rows rather than a menu twelve items
            // long that runs off the bottom of a short window.
            TAG_COLOURS.chunked(6).forEach { chunk ->
                Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
                    chunk.forEach { colour ->
                        Box(
                            Modifier.padding(3.dp).size(20.dp)
                                .background(Color(colour), CircleShape)
                                .clickable { menu = false; onColour(colour) },
                        )
                    }
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Back to the colour from its name") },
                onClick = { menu = false; onColour(null) },
            )
        }
        if (open == null) {
            Box(Modifier.size(10.dp).background(Color(row.color), CircleShape))
        } else {
            // The chevron takes the dot's place rather than sitting beside it: a branch is
            // a heading, and two markers on one row is one more than the row can carry.
            Box(
                Modifier.size(15.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onFold),
                contentAlignment = Alignment.Center,
            ) { Chevron(open) }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            row.label.substringAfterLast('/'),
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.real) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Fills, so the count is pinned to the right edge in the same column the folder
            // counts are in rather than trailing the word at whatever width it happens to be.
            modifier = Modifier.weight(1f),
        )
        // How much is behind the tag, which is what makes it worth clicking. Nothing at all
        // when it is empty, rather than a zero: a row of noughts is a list of words again.
        if (row.count > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                row.count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun FolderRow(
    mailbox: Mailbox,
    collapsed: Boolean,
    selected: Boolean,
    depth: Int = 0,
    /** What the right-click menu can do, or null where folders cannot be managed. */
    onManage: ((FolderJob) -> Unit)? = null,
    /** Why a job cannot be done here, shown in place of doing it: a shared folder's rights. */
    refusal: (FolderJob) -> String? = { null },
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .rowHover(showWash = !selected)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.button == PointerButton.Secondary && onManage != null) menu = true
            }
            // Collapsed the sidebar is icons only, so there is no room to show depth and
            // indenting would push them off their own column.
            .padding(start = if (collapsed) 0.dp else 10.dp + (depth * 12).dp)
            .padding(end = if (collapsed) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
    ) {
        onManage?.let { manage ->
            MenuLayer(expanded = menu, onDismissRequest = { menu = false }) {
                FolderMenuItem("New folder inside", refusal(FolderJob.CreateInside)) { menu = false; manage(FolderJob.CreateInside) }
                // A folder the server gave a role to is one the client should not offer to
                // rename or delete: the role is what Archive and Trash are found by, and a
                // renamed one still has it while looking like somebody's own folder.
                if (!isProtected(mailbox)) {
                    FolderMenuItem("Rename", refusal(FolderJob.Rename)) { menu = false; manage(FolderJob.Rename) }
                    if (mailbox.parentId != null) {
                        FolderMenuItem("Move to the top level", refusal(FolderJob.ToTop)) { menu = false; manage(FolderJob.ToTop) }
                    }
                    HorizontalDivider()
                    FolderMenuItem("Delete", refusal(FolderJob.Delete)) { menu = false; manage(FolderJob.Delete) }
                }
                HorizontalDivider()
                FolderMenuItem("Share this folder", refusal(FolderJob.Share)) { menu = false; manage(FolderJob.Share) }
                FolderMenuItem("Export this folder", refusal(FolderJob.Export)) { menu = false; manage(FolderJob.Export) }
                FolderMenuItem("Import into this folder", refusal(FolderJob.Import)) { menu = false; manage(FolderJob.Import) }
            }
        }
        Box(contentAlignment = Alignment.Center) {
            Icon(
                RampartIcons.forRole(mailbox.role),
                contentDescription = if (collapsed) mailbox.name else null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            // Collapsed there is no room for a count, so an unread folder carries a dot on
            // the corner of its icon instead of losing the signal altogether.
            if (collapsed && mailbox.unread > 0) {
                Box(
                    Modifier.size(7.dp).offset(x = 9.dp, y = (-8).dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        if (!collapsed) {
            Spacer(Modifier.width(10.dp))
            Text(
                mailbox.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (mailbox.unread > 0) {
                Text(
                    "${mailbox.unread}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
        }
    }
}

/**
 * One saved search row in the sidebar.
 *
 * Draws with a magnifier icon so it is never confused with a real folder.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun SavedSearchRow(
    search: SavedSearch,
    collapsed: Boolean,
    selected: Boolean,
    unread: Int = 0,
    onManage: ((SavedSearchJob) -> Unit)? = null,
    onClick: () -> Unit,
) {
    // A split search's child is indented under it and has nothing to manage: it is worked
    // out from the mail, so renaming or deleting it would be undone by the next count.
    val child = search.parentId != null
    if (child && collapsed) return
    val onManage = onManage.takeIf { !child }
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .rowHover(showWash = !selected)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.button == PointerButton.Secondary && onManage != null) menu = true
            }
            .padding(start = if (collapsed) 0.dp else if (child) 28.dp else 10.dp)
            .padding(end = if (collapsed) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
    ) {
        onManage?.let { manage ->
            MenuLayer(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = { menu = false; manage(SavedSearchJob.Rename) },
                )
                DropdownMenuItem(
                    text = { Text("Edit conditions") },
                    onClick = { menu = false; manage(SavedSearchJob.EditQuery) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menu = false; manage(SavedSearchJob.Delete) },
                )
            }
        }
        Box(contentAlignment = Alignment.Center) {
            Icon(
                RampartIcons.Search,
                contentDescription = if (collapsed) search.name else null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            if (collapsed && unread > 0) {
                Box(
                    Modifier.size(7.dp).offset(x = 9.dp, y = (-8).dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        if (!collapsed) {
            Spacer(Modifier.width(10.dp))
            Text(
                search.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (unread > 0) {
                Text(
                    "$unread",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
        }
    }
}
