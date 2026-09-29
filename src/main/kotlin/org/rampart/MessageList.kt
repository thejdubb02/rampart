package org.rampart

import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import java.time.Instant

@Composable
internal fun SearchBar(
    query: String,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    /*
     * In the sidebar, above Write, rather than on a bar of its own.
     *
     * It had the full width of the window and nothing beside it, which is a 52dp stripe of
     * nothing above everything else on screen. Here it costs no row that was not there.
     */
    Row(
        Modifier.fillMaxWidth().height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Built rather than borrowed: Material's text field has a minimum height of its
        // own and forcing it shorter clips the text inside it, which is what a 38dp
        // OutlinedTextField did here.
        Row(
            Modifier.fillMaxWidth().height(34.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(start = 9.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                RampartIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(7.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "Search mail",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                        .copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocusChanged(it.isFocused) }
                        .onPreviewKeyEvent {
                            // Enter searches and Escape abandons it, which is what fingers
                            // do before they read any button.
                            when {
                                it.type != KeyEventType.KeyDown -> false
                                it.key == Key.Enter -> { onSearch(); true }
                                it.key == Key.Escape -> { onQueryChange(""); onSearch(); true }
                                else -> false
                            }
                        },
                )
            }
            if (query.isNotEmpty()) {
                TextButton(
                    onClick = { onQueryChange(""); onSearch() },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(26.dp),
                ) { Text("Clear", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/**
 * The toggles under the folder title.
 *
 * A row of its own, not squeezed into the title strip: five chips, a count and a
 * clear button do not fit beside the folder name. They wrap, because the list is
 * a narrow column and the labels are not optional.
 *
 * The count is how many rows have come back against how many the folder says it
 * holds. It is absent until a toggle is on, because an unfiltered folder already
 * has its name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickFilterRow(
    filters: QuickFilters,
    onFilters: (QuickFilters) -> Unit,
    onClear: () -> Unit,
    shown: Int,
    folderTotal: Int,
    attachmentReason: String?,
    filterNote: String?,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip("Unread", RampartIcons.Unread, filters.unread) {
                onFilters(filters.copy(unread = !filters.unread))
            }
            FilterChip("Starred", RampartIcons.Star, filters.starred) {
                onFilters(filters.copy(starred = !filters.starred))
            }
            FilterChip("Tagged", RampartIcons.Tag, filters.tagged) {
                onFilters(filters.copy(tagged = !filters.tagged))
            }
            FilterChip(
                "Attachment",
                RampartIcons.Attachment,
                on = filters.attachment && attachmentReason == null,
                enabled = attachmentReason == null,
            ) {
                onFilters(filters.copy(attachment = !filters.attachment))
            }
            FilterChip("Sender I Know", RampartIcons.Contacts, filters.knownSender) {
                onFilters(filters.copy(knownSender = !filters.knownSender))
            }
            if (filters.active) {
                Text(
                    if (folderTotal > 0) "$shown of $folderTotal" else "$shown matched",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                )
                Text(
                    "Clear",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onClear)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        val note = filterNote ?: attachmentReason
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    icon: ImageVector,
    on: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        on -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (on && enabled) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (on && enabled) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
    }
}

/**
 * The list title. A search replaces the folder name until the search is cleared.
 */
internal fun listHeading(
    searching: Boolean,
    query: String,
    folder: String,
    savedSearchName: String? = null,
): String {
    if (savedSearchName != null) return savedSearchName
    val typed = query.trim()
    if (searching && typed.isNotEmpty()) return "Results for $typed"
    return folder
}

@Composable
internal fun MessageList(
    emails: List<Summary>,
    selected: Summary?,
    loading: Boolean,
    title: String = "",
    /** Account key to a short name, when a row has to say which mailbox it arrived in. */
    accountLabels: Map<String, String> = emptyMap(),
    /** Everything picked out, which is [selected] alone until somebody holds a key down. */
    picked: Set<String> = emptySet(),
    onRefresh: () -> Unit = {},
    /** How the rows are ordered, and how to change it. */
    order: Order = Order.NEWEST,
    onOrder: (Order) -> Unit = {},
    /** What a right-click or a hover button on a row can do. */
    rowActions: RowActions = RowActions(),
    /** Drafts waiting for a time, by message id, as epoch millis. */
    scheduled: Map<String, Long> = emptyMap(),
    /** RFC Message-ID to the compact tracking state shown on Sent rows. */
    trackingBadges: Map<String, String> = emptyMap(),
    /** Dragging a row onto a tag. See [MessageRow]. */
    onDrag: ((Summary, Offset?) -> Unit)? = null,
    /**
     * The old unread switch, kept so a caller that still passes it gets the unread
     * toggle. The row below is the control. This is only the default for [filters].
     */
    unreadOnly: Boolean = false,
    /** Which toggles are on. Asked of the server, or of the saved copy, never of this list. */
    filters: QuickFilters = QuickFilters(unread = unreadOnly),
    onFilters: (QuickFilters) -> Unit = {},
    /** Puts every toggle back. Shown only while [filters] is doing something. */
    onClearFilters: () -> Unit = {},
    /** How many messages the folder says it holds. 0 when it did not say. */
    folderTotal: Int = 0,
    /**
     * Why the attachment toggle cannot be pressed, or null when it can. The chip stays
     * on screen either way: a missing chip looks like a feature that was never there.
     */
    attachmentReason: String? = null,
    /** Set when a toggle had to be answered from a copy that is not there. */
    filterNote: String? = null,
    /** Whether the next page is already on its way, so the foot says so. */
    loadingMore: Boolean = false,
    /** Called when the list gets near its own bottom and wants the next page. */
    onNeedMore: () -> Unit = {},
    /** A search that came back empty, rather than a folder that has no mail. */
    searching: Boolean = false,
    /** Action to save current search as a folder. */
    onSaveSearch: (() -> Unit)? = null,
    /**
     * Draws every row as though the pointer were over it.
     *
     * Only ever passed by the screenshot tests. A headless scene has no pointer, and the
     * hover state is where a row's layout is most likely to go wrong, so it has to be
     * photographable.
     */
    showHover: Boolean = false,
    density: Density = LocalListDensity.current,
    /** How rows stand for conversations. See [threadRowsFor]; the default changes nothing. */
    threads: ThreadContext = ThreadContext(),
    /** Drawn above the list's own heading, at the list's width. A person's history uses it. */
    header: (@Composable () -> Unit)? = null,
    /**
     * The list fills the width it is given.
     *
     * Off, it keeps the fixed width the split pane has always used. The focused
     * list and the list above a bottom message pass true, because that pane is
     * the whole width and a fixed strip in the middle of it would be a mistake.
     */
    fillWidth: Boolean = false,
    /** Whether the focused inbox tabs are active for this list. */
    focusEnabled: Boolean = false,
    /** Which tab is currently active. */
    focusTab: FocusTab = FocusTab.FOCUSED,
    onFocusTab: (FocusTab) -> Unit = {},
    /** Unread count on the Other tab. */
    otherUnread: Int = 0,
    /** Bundled bulk messages by sender for the Other tab. */
    otherBundles: List<FocusBundle> = emptyList(),
    /** Which sender bundles are currently expanded. */
    expandedBundles: Set<String> = emptySet(),
    onToggleBundle: (String) -> Unit = {},
    onSelect: (Summary, ctrl: Boolean, shift: Boolean) -> Unit,
) {
    // Table and Cards are drawn in ListLayoutsUi.kt. Normal is the path below, unchanged.
    val layout = ListLayoutState.layout
    // One row per conversation, saying what its newest message says, worked out once here
    // so every layout draws the same rows. Selecting, dragging and every action get the
    // folder's own message back, never the newest one the row shows. See ThreadRows.kt.
    val collapsed = remember(emails, threads, ThreadGists.version, Rethreads.version) { threadRowsFor(emails, threads) }
    val shown = remember(collapsed) { collapsed.map { it.shown } }
    val byToken = remember(collapsed) { collapsed.associateBy { rowToken(it.shown) } }
    val threadedActions = threadActions(rowActions) { byToken[rowToken(it)] }
    val toRaw: (Summary) -> Summary = { byToken[rowToken(it)]?.raw ?: it }
    val pick: (Summary, Boolean, Boolean) -> Unit = { message, ctrl, shift -> onSelect(toRaw(message), ctrl, shift) }
    val drag: ((Summary, Offset?) -> Unit)? = onDrag?.let { inner -> { message: Summary, at: Offset? -> inner(toRaw(message), at) } }
    Column(
        (if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(listPaneWidth(layout)))
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        header?.invoke()
        Row(
            Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (searching && onSaveSearch != null) {
                    TextButton(
                        onClick = onSaveSearch,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(26.dp),
                    ) {
                        Text("Save as folder", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                var sorting by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { sorting = true }, modifier = Modifier.size(26.dp)) {
                        Icon(
                            RampartIcons.Sort,
                            contentDescription = "Sort order, currently ${order.label.lowercase()}",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    MenuLayer(expanded = sorting, onDismissRequest = { sorting = false }) {
                        Order.entries.forEach { option ->
                            val chosen = option == order
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (chosen) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                leadingIcon = {
                                    // A tick on the one in use, and an empty slot of the
                                    // same width on the others so the labels line up. An
                                    // asterisk was standing in for this and read as a typo.
                                    Box(Modifier.size(14.dp)) {
                                        if (chosen) {
                                            Icon(
                                                RampartIcons.Tick,
                                                contentDescription = "In use",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                },
                                // Material's default row is built for a finger. This is a
                                // five item menu on a desktop, and at that height it reads
                                // as a list of pages rather than a set of choices.
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                onClick = {
                                    sorting = false
                                    onOrder(option)
                                },
                            )
                        }
                    }
                }
                // Rampart looks for new mail on its own, but waiting up to a minute to find
                // out whether something arrived is not the same as being able to ask.
                IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(26.dp)) {
                    Icon(
                        RampartIcons.Refresh,
                        contentDescription = "Check for new mail",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        if (focusEnabled) {
            FocusTabs(
                selected = focusTab,
                otherUnread = otherUnread,
                onSelect = onFocusTab,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        QuickFilterRow(
            filters = filters,
            onFilters = onFilters,
            onClear = onClearFilters,
            shown = emails.size,
            folderTotal = folderTotal,
            attachmentReason = attachmentReason,
            filterNote = filterNote,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val scroll = rememberLazyListState()
        /*
         * A folder nobody has scrolled opens at the top, and goes on opening at the top.
         *
         * Rows are keyed by message id, so the list keeps whichever message was at the top
         * of the viewport pinned when the contents change. That is exactly right when
         * somebody scrolled to it and exactly wrong when they never left the top, and a
         * folder is filled twice: once from the copy on disk and once from the server. The
         * two do not always agree on order or on how many there are, so the message at the
         * top of the first list can be sixty rows down the second, and the list scrolls
         * sixty rows to keep it in view. The folder then opens in the middle of itself.
         *
         * Scrolling is what makes a position worth keeping, so that is what is watched. An
         * anchor correction is done during layout and is not a scroll, which is the whole
         * distinction this needs. Reset per folder, because arriving somewhere new is the
         * one time nobody has a position in it yet.
         */
        var moved by remember(title) { mutableStateOf(false) }
        LaunchedEffect(scroll, title) {
            snapshotFlow { scroll.isScrollInProgress }.collect { if (it) moved = true }
        }
        LaunchedEffect(emails, title) { if (!moved) scroll.scrollToItem(0) }
        /*
         * Ask for the next page a screenful early, so the rows are already there by the
         * time somebody scrolls onto them. Derived rather than read every frame: without
         * that this recomposes the whole list on every pixel of scrolling.
         */
        val wantsMore by remember(emails.size) {
            derivedStateOf {
                val last = scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                emails.isNotEmpty() && last >= emails.size - 10
            }
        }
        LaunchedEffect(wantsMore) { if (wantsMore) onNeedMore() }
        // Sorted once per change of rows or order, not on every recomposition of the list:
        // a hover or a selection redraws it, and sorting by subject runs a regular expression
        // per comparison. At a few thousand loaded rows that was most of each frame.
        val rowsInOrder = remember(shown, order) { sorted(shown, order) }
        Box(Modifier.fillMaxSize()) {
            when {
                // A refresh of a list that is already here must not replace it.
                emails.isNotEmpty() -> Unit
                focusEnabled && focusTab == FocusTab.OTHER && otherBundles.isNotEmpty() -> Unit
                loading -> ListSkeleton()
                else -> EmptyFolder(
                    searching = searching,
                    filters = filters,
                    note = filterNote,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (emails.isNotEmpty() && layout != ListLayout.NORMAL) {
                LayoutList(layout, shown, order, selected, picked, threadedActions, loadingMore, scroll, pick)
            }
            if ((emails.isNotEmpty() || (focusEnabled && focusTab == FocusTab.OTHER && otherBundles.isNotEmpty())) && layout == ListLayout.NORMAL) {
                LazyColumn(Modifier.fillMaxSize(), state = scroll) {
                    if (focusEnabled && focusTab == FocusTab.OTHER) {
                        otherBundles.forEach { bundle ->
                            val expanded = bundle.senderEmail in expandedBundles
                            val bundleSelected = bundle.messages.any { m ->
                                rowToken(m) == selected?.let(::rowToken) || rowToken(m) in picked
                            }
                            item(key = "bundle-${bundle.senderEmail}") {
                                FocusBundleRow(
                                    bundle = bundle,
                                    expanded = expanded,
                                    selected = bundleSelected,
                                    density = density,
                                    actions = threadedActions,
                                    onToggle = { onToggleBundle(bundle.senderEmail) },
                                    onSelect = pick,
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            if (expanded) {
                                items(bundle.messages, key = { rowToken(it) }) { message ->
                                    MessageRow(
                                        message = message,
                                        selected = rowToken(message) == selected?.let(::rowToken) || rowToken(message) in picked,
                                        accountLabel = accountLabels[message.account],
                                        actions = threadedActions,
                                        showHover = showHover,
                                        scheduledAt = scheduled[message.id],
                                        trackingBadge = trackingBadges[message.messageId],
                                        density = density,
                                        onDrag = drag,
                                        onSelect = pick,
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    } else {
                        // LazyColumn only builds the rows on screen, so a folder with thirty
                        // thousand messages in it costs the same as one with twenty. What that
                        // folder still needs is the next page, which is what `onNeedMore` is.
                        items(rowsInOrder, key = { rowToken(it) }) { message ->
                            Column(Modifier.rowChange()) {
                                MessageRow(
                                    message = message,
                                    selected = rowToken(message) == selected?.let(::rowToken) || rowToken(message) in picked,
                                    accountLabel = accountLabels[message.account],
                                    actions = threadedActions,
                                    showHover = showHover,
                                    scheduledAt = scheduled[message.id],
                                    trackingBadge = trackingBadges[message.messageId],
                                    density = density,
                                    onDrag = drag,
                                    onSelect = pick,
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                    if (loadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                Spinner(
                                    Modifier.align(Alignment.Center),
                                    size = 18.dp,
                                    thickness = 2.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Why a folder is showing no rows.
 *
 * A filter, a search and an empty folder are three different facts, and one
 * sentence of "Nothing here." was all three of them.
 */
@Composable
private fun EmptyFolder(
    searching: Boolean,
    filters: QuickFilters,
    note: String?,
    modifier: Modifier = Modifier,
) {
    val (icon, line) = when {
        note != null -> RampartIcons.Search to note
        searching -> RampartIcons.Search to "Nothing matched that search."
        filters == QuickFilters(unread = true) -> RampartIcons.Unread to "Nothing unread here."
        filters.active -> RampartIcons.Search to "Nothing matches these filters."
        else -> RampartIcons.Folder to "This folder is empty."
    }
    Column(
        modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 3,
        )
    }
}

/**
 * A faint wash while the pointer is over a row.
 *
 * The message list was the only place that noticed. Folders, threads, accounts
 * and files sat still under the pointer, so a click was a guess about which
 * row it would land on.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.rowHover(showWash: Boolean = true, onHover: ((Boolean) -> Unit)? = null): Modifier {
    var over by remember { mutableStateOf(false) }
    val wash = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    return this
        .onPointerEvent(PointerEventType.Enter) {
            over = true
            onHover?.invoke(true)
        }
        .onPointerEvent(PointerEventType.Exit) {
            over = false
            onHover?.invoke(false)
        }
        .drawBehind {
            if (showWash && over) drawRect(wash)
        }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MessageRow(
    message: Summary,
    selected: Boolean,
    accountLabel: String? = null,
    actions: RowActions = RowActions(),
    showHover: Boolean = false,
    /** When this draft is due to be sent, or null when it is an ordinary message. */
    scheduledAt: Long? = null,
    trackingBadge: String? = null,
    density: Density = LocalListDensity.current,
    /**
     * Dragging the row onto a tag. Called with where the pointer is, in window
     * coordinates, and with null when the drag ends.
     *
     * Null means the list does not support dragging, which is every list but the mail one.
     */
    onDrag: ((Summary, Offset?) -> Unit)? = null,
    onSelect: (Summary, ctrl: Boolean, shift: Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var pointerOver by remember { mutableStateOf(false) }
    // Still hovered while one of its hover menus is open, or the menu would go with the buttons.
    val hovered = pointerOver || showHover || HoverChoice.menuOpenOn(rowToken(message))
    // Where this row sits, so the pointer offsets a drag reports (which are relative to
    // the row) can be turned into a position in the window.
    var origin by remember { mutableStateOf(Offset.Zero) }

    /*
     * Unread rows carry a tint as well as the dot and the weight.
     *
     * Three signals rather than one because the dot is 7px and the weight difference is a
     * font grade: on a full inbox, at a glance, neither of them separates what has been
     * read from what has not. The tint is deliberately faint, taken from the theme's own
     * surface rather than a colour of its own, so a mostly unread inbox does not turn into
     * a wall of highlight.
     *
     * Selection still wins, because that is the row being acted on.
     */
    /*
     * A tag's colour on the row itself, when that is switched on.
     *
     * Bulwark's `tintListRowsByTag`, which is the last thing its tag settings do that this
     * did not. Very faint on purpose: at any strength that reads as a colour it fights the
     * unread tint above it and an inbox where everything is tagged becomes unreadable,
     * which is the reason it is a setting rather than the default.
     *
     * The first tag wins where a message has several. Blending them makes a brown nobody
     * chose, and the chips beside the subject already say what the others are.
     */
    val tagTint = if (!LocalTintRowsByTag.current) null else {
        tagsOf(message.keywords, LocalTagColours.current).firstOrNull()?.color
    }
    // The account's colour where there is no tag's, and only in the merged inbox, which is
    // the only list whose rows carry an account. See [rowTint] for why the tag wins.
    val tint = rowTint(tagTint, LocalAccountTints.current[message.account])?.let { Color(it) }
    val background = when {
        selected -> MaterialTheme.colorScheme.surfaceVariant
        tint != null && !message.seen -> tint.copy(alpha = 0.22f)
        tint != null -> tint.copy(alpha = 0.11f)
        !message.seen -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    val (topPad, bottomPad) = densityVerticalPadding(density)
    Row(
        Modifier.fillMaxWidth()
            .background(background)
            .onGloballyPositioned { origin = it.boundsInWindow().topLeft }
            .then(
                if (onDrag == null) Modifier
                else Modifier.pointerInput(message.id) {
                    detectDragGestures(
                        onDragStart = { at -> onDrag(message, origin + at) },
                        onDragEnd = { onDrag(message, null) },
                        onDragCancel = { onDrag(message, null) },
                    ) { change, _ -> onDrag(message, origin + change.position) }
                },
            )
            // The modifier keys have to be read from the press itself. clickable() does not
            // carry them, and holding control to add a second message to a selection is how
            // every desktop list has worked for thirty years.
            .onPointerEvent(PointerEventType.Press) { event ->
                when (event.button) {
                    PointerButton.Primary -> {
                        val keys = event.keyboardModifiers
                        onSelect(message, keys.isCtrlPressed || keys.isMetaPressed, keys.isShiftPressed)
                    }
                    // Right-click selects as well as opening the menu. A menu acting on a
                    // message other than the one under the pointer is how the wrong thing
                    // gets deleted.
                    PointerButton.Secondary -> {
                        if (!selected) { RowPress.menu = true; onSelect(message, false, false) }
                        menu = true
                    }
                    else -> Unit
                }
            }
            .rowHover(showWash = !selected, onHover = { pointerOver = it })
            .height(IntrinsicSize.Min),
    ) {
        RowMenu(message, actions, menu, scheduledAt != null) { menu = false }
        // A 2px edge rather than a fully tinted row: it marks the selection without
        // competing with the unread dot for the same piece of attention.
        Box(
            Modifier.width(2.dp).fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        val (who, _) = displaySender(message.from, message.fromEmail)
        // Extra compact is the same one line as Compact, with less padding. A second layout
        // would only add the avatar and the preview that this density exists to remove.
        if (density == Density.COMPACT || density == Density.EXTRA_COMPACT) {
            val startPad = if (density == Density.EXTRA_COMPACT) 6.dp else 10.dp
            val endPad = if (density == Density.EXTRA_COMPACT) 8.dp else 14.dp
            Row(
                Modifier.fillMaxWidth().padding(start = startPad, end = endPad, top = topPad, bottom = bottomPad),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp)) {
                    if (!message.seen) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary, CircleShape))
                    }
                }
                if (message.flagged) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        RampartIcons.Star,
                        contentDescription = "Starred",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(11.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    who,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    message.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
                    color = if (message.seen) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.6f, fill = false),
                )
                if (message.threadSize > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        message.threadSize.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                tagsOf(message.keywords, LocalTagColours.current).take(4).forEach { tag ->
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(tag.color)))
                }
                trackingBadge?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onSelect(message, false, false) },
                    )
                }
                accountLabel?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.weight(0.001f))
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.width(HOVER_SLOT).height(18.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (hovered) {
                        HoverButtons(message, actions)
                    } else {
                        Text(
                            message.receivedAt.asLocalTime(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                    }
                }
            }
        } else {
            // Their mark, beside the row rather than above it. Top aligned rather than centred:
            // a row is three lines tall and a circle floating in the middle of it reads as
            // belonging to the preview rather than to the sender.
            Box(Modifier.padding(start = 10.dp, top = densityAvatarTopPadding(density))) {
                Avatar(
                    label = who,
                    seed = message.fromEmail,
                    size = 30.dp,
                    photo = photoFor(message.fromEmail),
                )
            }
            Column(Modifier.padding(start = 10.dp, end = 14.dp, top = topPad, bottom = bottomPad)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp)) {
                        if (!message.seen) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary, CircleShape))
                        }
                    }
                    if (message.flagged) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            RampartIcons.Star,
                            contentDescription = "Starred",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        who,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    trackingBadge?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onSelect(message, false, false) },
                        )
                    }
                    accountLabel?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.width(HOVER_SLOT).height(18.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        if (hovered) {
                            HoverButtons(message, actions)
                        } else {
                            Text(
                                message.receivedAt.asLocalTime(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(Modifier.padding(start = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // The row stands for the whole conversation, so it has to say how much of
                    // one is behind it. Without this the list silently hides the other replies.
                    if (message.threadSize > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            message.threadSize.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    // Dots rather than chips: a label is worth seeing at a glance, and four of
                    // them spelt out would push the subject off the row it belongs to.
                    tagsOf(message.keywords, LocalTagColours.current).take(4).forEach { tag ->
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(tag.color)))
                    }
                }
                /*
                 * The sign-in code, on the row, so the message never has to be opened.
                 *
                 * This is the whole point of the feature rather than a flourish on it: a code
                 * is wanted for about forty seconds and the message is never read. It is drawn
                 * instead of the preview, because the preview of one of these messages is the
                 * sentence the code was found in.
                 */
                val code = remember(message.id) { oneTimeCode(message.subject, message.preview) }
                // The preview of a scheduled draft is whatever was typed. When it will
                // go is the thing the row has to say, in the same place.
                if (scheduledAt != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Scheduled for ${Instant.ofEpochMilli(scheduledAt).toString().asLocalTime()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 13.dp),
                    )
                } else if (code != null) {
                    val clipboard = LocalClipboardManager.current
                    Spacer(Modifier.height(3.dp))
                    Row(
                        Modifier.padding(start = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            modifier = Modifier.clickable { clipboard.setText(AnnotatedString(code)) },
                        ) {
                            Text(
                                code,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "Copy",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else if (message.preview.isNotBlank()) {
                    val previewLines = densityPreviewLines(density)
                    if (previewLines > 0) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            message.preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = previewLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 13.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The buttons the open message offers. A null one is not offered at all. */
/**
 * What the right-click menu and the hover buttons on a row can do.
 *
 * Separate from [MessageActions], which belongs to the message that is open and therefore
 * needs no argument. A row has to say which message it means, and there are eight of them
 * on screen at once.
 *
 * Every member is nullable for the same reason as [MessageActions]: an account with no Junk
 * folder does not get a Junk entry rather than getting one that fails.
 */
/**
 * Draws its contents on paper rather than in the theme, when asked.
 *
 * A themed reader is the right default: most mail is text, and text should be set in
 * whatever the person chose to look at all day. It is wrong for the rest. A newsletter or
 * an invoice was drawn against white by whoever sent it, and on a dark theme its own
 * colours land on a background nobody tested them against, which is how a logo turns into
 * a dark rectangle and a pale caption disappears.
 *
 * Only the message changes. The window, the list and the sidebar stay as they were, because
 * the point is to see one message as it was meant to look, not to change the app.
 */
@Composable
internal fun Paper(on: Boolean, content: @Composable () -> Unit) {
    if (!on) {
        content()
        return
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = Color.White,
            onBackground = Color(0xFF17171B),
            surface = Color.White,
            onSurface = Color(0xFF17171B),
            surfaceVariant = Color(0xFFF4F4F6),
            onSurfaceVariant = Color(0xFF17171B),
            outline = Color(0xFF63636E),
            outlineVariant = Color(0xFFE6E6EB),
        ),
        typography = MaterialTheme.typography,
    ) {
        Surface(color = Color.White, shape = MaterialTheme.shapes.small) {
            Box(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) { content() }
        }
    }
}

/** One of the small buttons that appear on a row under the pointer. See [HoverButtons]. */
@Composable
internal fun RowButton(icon: ImageVector, what: String, onClick: () -> Unit) {
    // 18dp rather than Material's default, so three of them fit the slot the date leaves
    // and the row is the same height whether the pointer is over it or not.
    IconButton(onClick = onClick, modifier = Modifier.size(18.dp)) {
        Icon(
            icon,
            contentDescription = what,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(13.dp),
        )
    }
}

/**
 * The width kept for the date, and for the buttons that replace it.
 *
 * Wide enough for the longest date this list shows and for up to four buttons (see
 * [HOVER_ACTIONS_MAX]), so neither state is cramped and neither is what decides the width.
 */
private val HOVER_SLOT = 78.dp

/**
 * The right-click menu on a row.
 *
 * Every entry is left out rather than disabled when the account cannot do it, because a
 * greyed row invites a second click to find out why. The order is the one every mail client
 * uses, and the destructive pair is at the bottom behind a divider so a menu that opens
 * under the pointer cannot delete something on the way past.
 */
@Composable
internal fun RowMenu(
    message: Summary,
    actions: RowActions,
    open: Boolean,
    scheduled: Boolean = false,
    onClose: () -> Unit,
) {
    MenuLayer(expanded = open, onDismissRequest = onClose) {
        // Closing before acting, so the menu is gone by the time the list under it changes.
        @Composable
        fun entry(label: String, does: () -> Unit) = DropdownMenuItem(
            text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            onClick = {
                onClose()
                does()
            },
        )
        if (scheduled && (actions.sendScheduled != null || actions.cancelScheduled != null)) {
            actions.sendScheduled?.let { send -> entry("Send now") { send(message) } }
            actions.cancelScheduled?.let { drop -> entry("Cancel") { drop(message) } }
            HorizontalDivider()
        }
        actions.reply?.let {
            entry("Reply") { it(message, bareReplyAll(Settings.defaultReplyAll(), false)) }
            entry("Reply all") { it(message, true) }
        }
        actions.forward?.let { entry("Forward") { it(message) } }
        actions.forwardFile?.let { entry("Forward as attachment") { it(message) } }
        actions.markRead?.let {
            entry(if (message.seen) "Mark unread" else "Mark read") { it(message, !message.seen) }
        }
        actions.star?.let { entry(if (message.flagged) "Remove star" else "Star") { it(message) } }
        actions.archive?.let { entry("Archive") { it(message) } }
        val clipboard = LocalClipboardManager.current
        if (message.fromEmail.isNotBlank()) {
            entry("Copy address") { clipboard.setText(AnnotatedString(message.fromEmail)) }
            if (actions.alwaysFocused != null || actions.alwaysOther != null) {
                HorizontalDivider()
                actions.alwaysFocused?.let { entry("Always Focused") { it(message) } }
                actions.alwaysOther?.let { entry("Always Other") { it(message) } }
            }
        }
        actions.filter?.let { offer ->
            HorizontalDivider()
            entry("AI Filter") { offer(message) }
        }
        actions.snooze?.let { put ->
            // The four named times rather than a submenu with a calendar in it: the whole
            // value of a snooze is that it is one gesture.
            HorizontalDivider()
            SnoozeUntil.entries.forEach { until -> entry(until.label) { put(message, until) } }
        }
        actions.followUp?.let { ask -> entry(followUpMenuLabel(message.keywords)) { ask(message) } }
        if (actions.junk != null || actions.notJunk != null || actions.trash != null) {
            HorizontalDivider()
            if (actions.isJunk?.invoke(message) == true) {
                actions.notJunk?.let { entry("Not spam") { it(message) } }
            } else {
                actions.junk?.let { entry("Mark as spam") { it(message) } }
            }
            actions.trash?.let { entry("Delete") { it(message) } }
        }
    }
}

/**
 * How deep a folder sits, for indenting it in a flat list.
 *
 * Counted by walking up the parents rather than stored, because the list Move is given is
 * already filtered and a stored depth would be the depth in the full tree.
 */
internal fun depthOf(folder: Mailbox, among: List<Mailbox>): Int {
    var depth = 0
    var parent = folder.parentId
    while (parent != null && depth < 6) {
        val next = among.firstOrNull { it.id == parent } ?: break
        depth++
        parent = next.parentId
    }
    return depth
}

/**
 * A list of moves rather than one, because a batch picked out of the merged inbox can span
 * accounts, and putting half of it back is worse than offering no undo at all.
 */
/**
 * The strip that offers to take back what just happened, and drains while it does.
 *
 * **A bar that never goes away stops being read.** It sat there until dismissed, so after
 * a few archives it was furniture: present, ignored, and covering the top of the list.
 * Auto-dismissing fixes that and raises a new question, which is how long is left, and the
 * answer belongs on the button rather than beside it. The fill recedes across Undo, so the
 * thing you would press is the thing showing how long you can press it for.
 *
 * [seconds] of zero means it stays until dismissed, which is the old behaviour kept for
 * anyone who wants it.
 */
@Composable
internal fun UndoBar(
    text: String,
    seconds: Int,
    /** Changes when a new thing happens, which is what restarts the drain. */
    restartOn: Any?,
    onUndo: () -> Unit,
    /** Called when the time runs out. Null where something else takes the bar away. */
    onExpire: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val left = remember(restartOn) { Animatable(1f) }
    LaunchedEffect(restartOn, seconds) {
        if (seconds <= 0) return@LaunchedEffect
        left.snapTo(1f)
        // Linear, because this is a clock. Any easing makes the last second look longer
        // than the first, which is the one thing a countdown must not do.
        left.animateTo(0f, tween(durationMillis = seconds * 1000, easing = LinearEasing))
        onExpire?.invoke()
    }
    val card = RoundedCornerShape(10.dp)
    Row(
        Modifier.widthIn(max = 520.dp)
            .shadow(8.dp, card)
            .clip(card)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, card)
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        val drain = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        TextButton(
            onClick = onUndo,
            modifier = Modifier.clip(MaterialTheme.shapes.small).drawBehind {
                if (seconds > 0) drawRect(drain, size = Size(size.width * left.value, size.height))
            },
        ) { Text("Undo", style = MaterialTheme.typography.bodySmall) }
        onDismiss?.let {
            TextButton(onClick = it) { Text("Dismiss", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * What the reading pane shows while more than one message is picked.
 *
 * Deliberately only the actions that can be undone. Nothing here sends, replies or deletes
 * outright: the mistake somebody makes with fifty messages selected is the one they cannot
 * take back, so the pane offers nothing of that kind.
 */
@Composable
internal fun Picked(
    count: Int,
    onClear: () -> Unit,
    onFile: (role: String, what: String) -> Unit,
    onRead: () -> Unit,
    /** Whether the folder being looked at is Junk, which swaps Spam for Not spam. */
    inJunk: Boolean = false,
    /** Makes the picked conversations one, on this computer. Null where the list is not collapsed. */
    onJoin: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$count messages picked", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onFile("archive", "archived") }) { Text("Archive") }
            if (inJunk) {
                OutlinedButton(onClick = { onFile("inbox", "moved to the inbox") }) { Text("Not spam") }
            } else {
                OutlinedButton(onClick = { onFile("junk", "marked as spam") }) { Text("Spam") }
            }
            OutlinedButton(onClick = { onFile("trash", "deleted") }) { Text("Delete") }
            OutlinedButton(onClick = onRead) { Text("Mark read") }
        }
        // Undoable, so it sits with the rest. Nothing on the server changes. See [Rethreading].
        onJoin?.let { join -> OutlinedButton(onClick = join) { Text("Join into one conversation") } }
        TextButton(onClick = onClear) { Text("Clear", style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * What is picked out after a click, given what was picked and where the anchor was.
 *
 * The rules are the ones every desktop list has had for thirty years, and the reason this
 * is a function rather than four lines in a lambda is that getting shift wrong quietly
 * files the wrong fifty messages.
 *
 * A plain click picks nothing out: one message is simply the one being read, and drawing a
 * selection around it would make every single click look like the start of a batch.
 */
internal fun pickedAfter(
    ids: List<String>,
    picked: Set<String>,
    anchor: String?,
    target: String,
    ctrl: Boolean,
    shift: Boolean,
): Set<String> {
    val from = ids.indexOf(anchor)
    val to = ids.indexOf(target)
    return when {
        shift && from >= 0 && to >= 0 -> ids.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
        // Shift with nothing to measure from is a plain click, not an empty selection.
        shift -> emptySet()
        ctrl -> if (target in picked) picked - target else picked + target
        else -> emptySet()
    }
}

/**
 * The Focused and Other tabs at the top of an inbox list.
 */
@Composable
internal fun FocusTabs(
    selected: FocusTab,
    otherUnread: Int,
    onSelect: (FocusTab) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FocusTabButton(
            label = "Focused",
            selected = selected == FocusTab.FOCUSED,
            unreadCount = 0,
            onClick = { onSelect(FocusTab.FOCUSED) },
        )
        FocusTabButton(
            label = "Other",
            selected = selected == FocusTab.OTHER,
            unreadCount = otherUnread,
            onClick = { onSelect(FocusTab.OTHER) },
        )
    }
}

@Composable
private fun FocusTabButton(
    label: String,
    selected: Boolean,
    unreadCount: Int,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val fw = if (selected) FontWeight.SemiBold else FontWeight.Normal
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = fw,
            color = fg,
        )
        if (unreadCount > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    unreadCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * An expandable row grouping bulk messages from one sender in the Other tab.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FocusBundleRow(
    bundle: FocusBundle,
    expanded: Boolean,
    selected: Boolean,
    density: Density,
    actions: RowActions,
    onToggle: () -> Unit,
    onSelect: (Summary, ctrl: Boolean, shift: Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var pointerOver by remember { mutableStateOf(false) }
    val (who, _) = displaySender(bundle.sender, bundle.senderEmail)
    val hasUnread = bundle.unreadCount > 0
    val topPad = if (density == Density.COMPACT) 6.dp else if (density == Density.SPACIOUS) 14.dp else 10.dp
    val bottomPad = if (density == Density.COMPACT) 6.dp else if (density == Density.SPACIOUS) 14.dp else 10.dp

    val background = when {
        selected -> MaterialTheme.colorScheme.surfaceVariant
        hasUnread -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else -> Color.Transparent
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .onPointerEvent(PointerEventType.Enter) { pointerOver = true }
            .onPointerEvent(PointerEventType.Exit) { pointerOver = false }
            .onPointerEvent(PointerEventType.Press) { event ->
                val button = event.button
                val primary = button == null || button == PointerButton.Primary
                val secondary = button == PointerButton.Secondary
                if (secondary) {
                    menu = true
                    onSelect(bundle.newest, false, false)
                } else if (primary) {
                    onToggle()
                    onSelect(bundle.newest, event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed, event.keyboardModifiers.isShiftPressed)
                }
            }
            .padding(start = 10.dp, end = 14.dp, top = topPad, bottom = bottomPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Icon(
                RampartIcons.Expand,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(12.dp).rotate(if (expanded) 90f else 0f),
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(7.dp)) {
            if (hasUnread) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            who,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 7.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                bundle.count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            bundle.newest.subject.ifBlank { "(no subject)" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal,
            color = if (hasUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.5f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            bundle.newest.receivedAt.asLocalTime(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
        RowMenu(
            message = bundle.newest,
            actions = actions,
            open = menu,
            onClose = { menu = false },
        )
    }
}

