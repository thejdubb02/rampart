package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * The layout in force, and the table's widths and sort, held once for the whole window.
 *
 * One object rather than a parameter threaded from Settings through the window to the
 * list: the chooser in Appearance and the list are far apart in the tree, and the list
 * redraws the moment any of these change because each is snapshot state.
 */
internal object ListLayoutState {
    var layout by mutableStateOf(runCatching { ListLayout.of(Settings.listLayout()) }.getOrDefault(ListLayout.NORMAL))
        private set
    var widths by mutableStateOf(runCatching { ColumnWidths.of(Settings.tableColumns()) }.getOrDefault(ColumnWidths()))
        private set
    var sort by mutableStateOf(runCatching { TableSort.of(Settings.tableSort()) }.getOrDefault(TableSort()))
        private set

    fun choose(next: ListLayout) {
        layout = next
        Settings.setListLayout(next.key)
    }

    fun sortBy(column: TableColumn) {
        sort = sort.clicked(column)
        Settings.setTableSort(sort.encoded())
    }

    /** Resizes as the pointer moves. Written to the file only when the drag ends. */
    fun drag(column: TableColumn, delta: Float) {
        widths = widths.dragged(column, delta)
    }

    fun saveWidths() = Settings.setTableColumns(widths.encoded())

    /** Read again after something else wrote the file, such as a settings card from Rook. */
    fun reload() {
        layout = ListLayout.of(Settings.listLayout())
        widths = ColumnWidths.of(Settings.tableColumns())
        sort = TableSort.of(Settings.tableSort())
    }
}

/** The ordinary list's width in dp, which Normal and Cards keep. */
private const val LIST_WIDTH = 368f

/** The widest in dp the table's pane grows before its columns scroll sideways instead. */
private const val TABLE_PANE_MAX = 760f

/**
 * How wide the message list pane is under [layout].
 *
 * Normal and Cards are the width the list has always been. The table is as wide as its
 * columns, up to a limit that leaves the reading pane room, and scrolls sideways past it.
 */
internal fun listPaneWidth(layout: ListLayout): Dp = when (layout) {
    ListLayout.TABLE -> (ListLayoutState.widths.total() + 2f).coerceIn(LIST_WIDTH, TABLE_PANE_MAX).dp
    else -> LIST_WIDTH.dp
}

/** The layout choice for the Appearance page, drawn the same way the density choice above it is. */
@Composable
internal fun ListLayoutChooser() {
    val current = ListLayoutState.layout
    ListLayout.entries.forEach { option ->
        Row(
            Modifier.fillMaxWidth().clickable { ListLayoutState.choose(option) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option == current, onClick = { ListLayoutState.choose(option) })
            Spacer(Modifier.width(8.dp))
            Column {
                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                Text(option.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/**
 * The list drawn as the chosen layout, for everything but Normal.
 *
 * Normal never comes here: it keeps its own path in [MessageList] untouched. This is
 * handed the same rows, selection and actions that path is, so choosing a layout changes
 * the picture and nothing about what a click or a right-click does.
 */
@Composable
internal fun LayoutList(
    layout: ListLayout,
    emails: List<Summary>,
    order: Order,
    selected: Summary?,
    picked: Set<String>,
    rowActions: RowActions,
    loadingMore: Boolean,
    scroll: LazyListState,
    onSelect: (Summary, ctrl: Boolean, shift: Boolean) -> Unit,
) {
    fun isSelected(message: Summary) =
        rowToken(message) == selected?.let(::rowToken) || rowToken(message) in picked
    when (layout) {
        ListLayout.TABLE -> MessageTable(emails, ::isSelected, rowActions, loadingMore, scroll, onSelect)
        // Remembered, as the table already is, so a redraw does not sort every row again.
        else -> MessageCards(remember(emails, order) { sorted(emails, order) }, ::isSelected, rowActions, loadingMore, scroll, onSelect)
    }
}

/**
 * Clicks on a row or a card, with the modifier keys read from the press.
 *
 * The same handling as the ordinary row, so control and shift pick several and a
 * right-click selects the message it is over before the menu opens.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.pressed(
    message: Summary,
    selected: Boolean,
    onSelect: (Summary, ctrl: Boolean, shift: Boolean) -> Unit,
    onMenu: () -> Unit,
): Modifier = onPointerEvent(PointerEventType.Press) { event ->
    when (event.button) {
        PointerButton.Primary -> {
            val keys = event.keyboardModifiers
            onSelect(message, keys.isCtrlPressed || keys.isMetaPressed, keys.isShiftPressed)
        }
        PointerButton.Secondary -> {
            if (!selected) { RowPress.menu = true; onSelect(message, false, false) }
            onMenu()
        }
        else -> Unit
    }
}

@Composable
private fun MessageTable(
    emails: List<Summary>,
    isSelected: (Summary) -> Boolean,
    rowActions: RowActions,
    loadingMore: Boolean,
    scroll: LazyListState,
    onSelect: (Summary, ctrl: Boolean, shift: Boolean) -> Unit,
) {
    val widths = ListLayoutState.widths
    val sort = ListLayoutState.sort
    // Sorted here, over the rows already loaded, the same as the ordinary list's sort menu.
    // Remembered, because a pixel of column drag is a recomposition and re-sorting the
    // folder on each one is work nobody asked for.
    val rows = remember(emails, sort) { tableSorted(emails, sort) }
    val sideways = rememberScrollState()
    Column(Modifier.fillMaxSize().horizontalScroll(sideways)) {
        Column(Modifier.width(widths.total().dp + 2.dp).fillMaxHeight()) {
            TableHeading(widths, sort)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.fillMaxSize(), state = scroll) {
                items(rows, key = { rowToken(it) }) { message ->
                    Column(Modifier.rowChange()) {
                        TableRow(message, isSelected(message), widths, rowActions, onSelect)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
                if (loadingMore) item { MoreComing() }
            }
        }
    }
}

@Composable
private fun TableHeading(widths: ColumnWidths, sort: TableSort) {
    Row(Modifier.height(28.dp).padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        TableColumn.entries.forEach { column ->
            Row(
                Modifier.width(widths.of(column).dp).fillMaxHeight()
                    .clickable { ListLayoutState.sortBy(column) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val label = if (column == TableColumn.STAR) "" else column.label
                val arrow = if (sort.column == column) (if (sort.ascending) " ↑" else " ↓") else ""
                Text(
                    label + arrow,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (sort.column == column) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sort.column == column) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                if (column == TableColumn.STAR && sort.column != column) {
                    Icon(
                        RampartIcons.Star,
                        contentDescription = "Sort by star",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                ColumnEdge(column)
            }
        }
    }
}

/** The right edge of a heading, dragged to resize its column. */
@Composable
private fun ColumnEdge(column: TableColumn) {
    Box(
        Modifier.width(6.dp).fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(column) {
                detectDragGestures(
                    onDragEnd = { ListLayoutState.saveWidths() },
                    onDragCancel = { ListLayoutState.saveWidths() },
                ) { change, drag ->
                    change.consume()
                    ListLayoutState.drag(column, drag.x.toDp().value)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).height(14.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun TableRow(
    message: Summary,
    selected: Boolean,
    widths: ColumnWidths,
    actions: RowActions,
    onSelect: (Summary, ctrl: Boolean, shift: Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val weight = if (message.seen) FontWeight.Normal else FontWeight.Bold
    Row(
        Modifier.height(30.dp)
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.surfaceVariant
                    !message.seen -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    else -> Color.Transparent
                },
            )
            .pressed(message, selected, onSelect) { menu = true }
            .rowHover(showWash = !selected),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowMenu(message, actions, menu) { menu = false }
        Box(
            Modifier.width(2.dp).fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        TableColumn.entries.forEach { column ->
            Box(Modifier.width(widths.of(column).dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                when (column) {
                    TableColumn.STAR -> {
                        val star = actions.star
                        Icon(
                            RampartIcons.Star,
                            contentDescription = if (message.flagged) "Starred" else "Not starred",
                            tint = if (message.flagged) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(13.dp)
                                .then(if (star != null) Modifier.clickable { star(message) } else Modifier),
                        )
                    }
                    TableColumn.SENDER -> Cell(displaySender(message.from, message.fromEmail).first, weight)
                    TableColumn.SUBJECT -> Cell(message.subject, weight)
                    TableColumn.DATE -> Cell(message.receivedAt.asLocalTime(), FontWeight.Normal, quiet = true)
                    TableColumn.SIZE -> Cell(tableSize(message.size), FontWeight.Normal, quiet = true)
                    TableColumn.TAGS -> TagDots(message, labelled = true)
                }
            }
        }
    }
}

@Composable
private fun Cell(text: String, weight: FontWeight, quiet: Boolean = false) {
    Text(
        text,
        style = if (quiet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        fontWeight = weight,
        color = if (quiet) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A message's tags as coloured dots, with the first one named when there is room for it. */
@Composable
private fun TagDots(message: Summary, labelled: Boolean) {
    val tags = tagsOf(message.keywords, LocalTagColours.current)
    if (tags.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.take(4).forEach { tag -> Box(Modifier.size(7.dp).clip(CircleShape).background(Color(tag.color))) }
        if (labelled) {
            Text(
                tags.first().label + if (tags.size > 1) " +${tags.size - 1}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MessageCards(
    emails: List<Summary>,
    isSelected: (Summary) -> Boolean,
    rowActions: RowActions,
    loadingMore: Boolean,
    scroll: LazyListState,
    onSelect: (Summary, ctrl: Boolean, shift: Boolean) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), state = scroll) {
        items(emails, key = { rowToken(it) }) { message ->
            Box(Modifier.rowChange()) {
                MessageCard(message, isSelected(message), rowActions, onSelect)
            }
        }
        if (loadingMore) item { MoreComing() }
    }
}

@Composable
private fun MessageCard(
    message: Summary,
    selected: Boolean,
    actions: RowActions,
    onSelect: (Summary, ctrl: Boolean, shift: Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    val (who, _) = displaySender(message.from, message.fromEmail)
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
        RowMenu(message, actions, menu) { menu = false }
        Column(
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(
                    if (selected) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                )
                .border(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape,
                )
                .pressed(message, selected, onSelect) { menu = true }
                .rowHover(showWash = !selected)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(who.ifBlank { message.fromEmail }, message.fromEmail, 28.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!message.seen) {
                            Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            who.ifBlank { message.fromEmail },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (message.flagged) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                RampartIcons.Star,
                                contentDescription = "Starred",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    Text(
                        message.receivedAt.asLocalTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                message.subject,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (message.preview.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    message.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tagsOf(message.keywords).isNotEmpty() || message.threadSize > 1) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TagDots(message, labelled = true)
                    if (message.threadSize > 1) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${message.threadSize} in conversation",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreComing() {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Spinner(Modifier.align(Alignment.Center), size = 18.dp, thickness = 2.dp)
    }
}
