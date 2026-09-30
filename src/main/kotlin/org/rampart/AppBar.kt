package org.rampart

import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Cursor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle

/*
 * The slim bar down the right edge of the window, and the panel it opens beside the mail.
 *
 * Beside, and never over. The message is a native view that paints on top of anything
 * Compose draws in its place, so a panel laid over the reader would be hidden behind the
 * very message it was opened to sit next to. The panel is a sibling in the same row and
 * takes its width from the mail, which is what keeps both visible at once.
 *
 * SideTools.kt holds the rules (which one is open, how wide, which key); this is what is
 * drawn. The window supplies what goes inside each panel, because Rook and Contacts close
 * over state only the window has.
 */

/**
 * Which of the bar's panels is open and how wide each one was left.
 *
 * Held here rather than in the window's state, the same way [FilesPage] is, so the window
 * needs a line to place the bar and a line for the keys rather than more state threaded
 * through it. The widths are read from settings once, the first time anything asks.
 */
internal object AppBar {
    var panels by mutableStateOf(SidePanels.restored(runCatching { Settings.sidePanelWidths() }.getOrDefault(emptyMap())))
        private set

    fun toggle(tool: SideTool) { panels = panels.toggle(tool) }

    fun show(tool: SideTool) { panels = panels.show(tool) }

    fun close(tool: SideTool) { panels = panels.close(tool) }

    fun showing(tool: SideTool): Boolean = panels.open == tool

    fun resize(tool: SideTool, width: Float, available: Float?) { panels = panels.resize(tool, width, available) }

    /**
     * Written when a drag ends rather than on every step of it, which would be a file write
     * per pixel. A failed write costs the width at the next start, nothing more, so it is
     * not worth an error on screen.
     */
    fun save() { runCatching { Settings.setSidePanelWidths(panels.savedWidths()) } }

    /** Ctrl and a digit, from the window's own key handler. True when it was one of ours. */
    fun key(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val tool = SideTool.forKey(
            digitOf(event.key),
            ctrl = event.isCtrlPressed,
            shift = event.isShiftPressed,
            alt = event.isAltPressed,
            meta = event.isMetaPressed,
        ) ?: return false
        toggle(tool)
        return true
    }
}

/** The digit on a key, top row or keypad, or null for any other key. */
private fun digitOf(key: Key): Int? = when (key) {
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    else -> null
}

/** Wide enough for a 34dp button and its highlight, and no wider: the width is the mail's. */
private val BarWidth = 44.dp

/** The same 34dp the other buttons use. A face bigger than this would force the bar wider. */
private val AccountSlot = 34.dp

/**
 * The picture inside that button. Rook's face on this bar is 20dp, and the glyphs are
 * 16dp. 20dp leaves a corner of the button for the unread count without growing the slot.
 */
private val AccountFace = 20.dp

/** How many real faces are drawn before the rest fold into a "+N" row. */
private const val MAX_ACCOUNT_FACES = 3

/** The strip on the panel's left edge that drags. Wider than the line it draws, to be findable. */
private val EdgeWidth = 6.dp

/**
 * The window's mail area with the bar on its right and, when one is open, a panel between.
 *
 * [content] is everything that was there before, laid out in a row of its own that takes
 * whatever width the panel leaves. [panel] draws the inside of whichever panel is open.
 */
@Composable
internal fun WithSideTools(
    /** Whether Rook is busy anywhere in the app, so his face moves while he works. */
    working: Boolean,
    /** Signed-in accounts. The bar draws one face each, shared mailboxes left out. */
    accounts: List<AccountMailboxes> = emptyList(),
    /** Whose mail is on screen, so that face can be marked. Null on the merged inbox. */
    currentAccount: String? = null,
    /** The "+" under the faces. Same action the old add-account button ran. */
    onAddAccount: () -> Unit = {},
    inDashboard: Boolean = false,
    onDashboard: () -> Unit = {},
    inSettings: Boolean = false,
    onSettings: () -> Unit = {},
    updateState: UpdateBarState = UpdateBarState.Hidden,
    onUpdate: () -> Unit = {},
    panel: @Composable (SideTool) -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val density = LocalDensity.current
    // Measured rather than assumed, so a narrow window still has room left for the message.
    var total by remember { mutableStateOf<Float?>(null) }
    Row(Modifier.fillMaxSize().onSizeChanged { total = with(density) { it.width.toDp().value } }) {
        Row(Modifier.weight(1f).fillMaxHeight(), content = content)
        val open = AppBar.panels.open
        if (open != null) {
            val available = total?.let { it - BarWidth.value }
            val width = clampSidePanelWidth(AppBar.panels.widthOf(open), available)
            PanelEdge(open, available)
            Box(Modifier.width(width.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
                panel(open)
            }
        }
        VerticalDivider()
        Bar(
            working, inDashboard, onDashboard, inSettings, onSettings, updateState, onUpdate,
            accounts, currentAccount, onAddAccount,
        )
    }
}

/**
 * The panel's left edge, dragged to resize it.
 *
 * The panel grows to the left, so a pointer moving left, a negative step, widens it. The
 * step is applied to the width actually drawn rather than the one remembered, so after the
 * window has been narrowed the first movement moves the edge instead of eating the gap
 * between the two.
 */
@Composable
private fun PanelEdge(tool: SideTool, available: Float?) {
    val room by rememberUpdatedState(available)
    Box(
        Modifier.width(EdgeWidth).fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(tool) {
                detectDragGestures(
                    onDragEnd = { AppBar.save() },
                    onDragCancel = { AppBar.save() },
                ) { change, drag ->
                    change.consume()
                    val drawn = clampSidePanelWidth(AppBar.panels.widthOf(tool), room)
                    AppBar.resize(tool, drawn - drag.x.toDp().value, room)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * The bar itself: one button per tool, top to bottom in [SideTool]'s order.
 *
 * Dashboard, Settings, the version and the accounts are pinned to the bottom edge.
 * They are measured first, and a short window clips the tools above them. The
 * accounts moved here so a long folder list can no longer scroll them away.
 */
@Composable
private fun Bar(
    working: Boolean,
    inDashboard: Boolean = false,
    onDashboard: () -> Unit = {},
    inSettings: Boolean = false,
    onSettings: () -> Unit = {},
    updateState: UpdateBarState = UpdateBarState.Hidden,
    onUpdate: () -> Unit = {},
    accounts: List<AccountMailboxes> = emptyList(),
    currentAccount: String? = null,
    onAddAccount: () -> Unit = {},
) {
    Column(
        Modifier.width(BarWidth).fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(
            Modifier.weight(1f).clipToBounds(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SideTool.entries.forEach { tool ->
                // Files is only for an account that keeps files, the way its old sidebar button
                // was. The key still answers, and the panel says why there is nothing in it.
                if (tool == SideTool.FILES && !FilesPage.offered && !AppBar.showing(tool)) return@forEach
                // Tasks the same way, for an account with somewhere to keep one (TasksUi.kt).
                if (tool == SideTool.TASKS && !TasksPanelState.offered && !AppBar.showing(tool)) return@forEach
                BarButton(tool, working)
            }
        }
        BarAction(
            icon = RampartIcons.Dashboard,
            label = "How your mail is going",
            active = inDashboard,
            onClick = onDashboard,
        )
        BarAction(
            icon = RampartIcons.Settings,
            label = "Settings",
            active = inSettings,
            onClick = onSettings,
        )
        VersionLabel(updateState, onUpdate)
        AccountSwitcher(accounts, currentAccount, onAccount = onSettings, onAdd = onAddAccount)
    }
}

/**
 * The running version, just above the accounts.
 *
 * Once an update is staged, the line reads "Update to <version>" and a click installs
 * it and restarts. During install, a spinner takes the place of the arrow. A development
 * build shows the plain version and nothing to press.
 */
@Composable
private fun VersionLabel(updateState: UpdateBarState, onUpdate: () -> Unit) {
    val running = Updates.current
    val canUpdate = remember { Updates.canUpdate() }
    val offered = if (canUpdate) updateState else UpdateBarState.Hidden
    if (running == null && offered is UpdateBarState.Hidden) return
    val text = if (offered is UpdateBarState.Waiting) offered.label else (running ?: "dev")
    val tooltip = when (offered) {
        is UpdateBarState.Waiting -> "Click to install and restart"
        is UpdateBarState.Staging, is UpdateBarState.Installing -> "Updating"
        is UpdateBarState.Failed -> offered.message
        UpdateBarState.Hidden -> null
    }
    val clickable = offered is UpdateBarState.Waiting || offered is UpdateBarState.Failed
    val content = @Composable {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .then(
                    if (clickable) {
                        Modifier.clickable(
                            role = androidx.compose.ui.semantics.Role.Button,
                            onClickLabel = tooltip,
                            onClick = onUpdate,
                        )
                    } else {
                        Modifier
                    }
                )
                // A waiting update is a pill in the accent colour, so it reads as something
                // to press rather than a label that happens to have an icon beside it.
                .then(
                    if (offered is UpdateBarState.Waiting) {
                        Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (offered is UpdateBarState.Waiting) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                maxLines = 1,
            )
            when (offered) {
                is UpdateBarState.Waiting -> {
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        RampartIcons.Download,
                        contentDescription = "Click to install and restart",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(11.dp).updateBob(offered.version),
                    )
                }
                is UpdateBarState.Staging, is UpdateBarState.Installing -> {
                    Spacer(Modifier.width(2.dp))
                    Spinner(size = 11.dp, thickness = 2.dp)
                }
                is UpdateBarState.Failed -> {
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        RampartIcons.Download,
                        contentDescription = offered.message,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(11.dp),
                    )
                }
                UpdateBarState.Hidden -> Unit
            }
        }
    }

    if (tooltip != null) {
        SidebarTooltip(tooltip) {
            content()
        }
    } else {
        content()
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    SidebarTooltip(label) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(34.dp).clip(MaterialTheme.shapes.small)
                .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent),
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun BarButton(tool: SideTool, working: Boolean) {
    val open = AppBar.showing(tool)
    val name = if (tool == SideTool.ROOK) "Ask Rook" else tool.label
    SidebarTooltip("$name (${tool.keys})") {
        IconButton(
            onClick = { AppBar.toggle(tool) },
            modifier = Modifier.size(34.dp).clip(MaterialTheme.shapes.small)
                .background(if (open) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent),
        ) {
            val glyph: ImageVector? = when (tool) {
                SideTool.ROOK -> null
                SideTool.CALENDAR -> RampartIcons.Calendar
                SideTool.CONTACTS -> RampartIcons.Contacts
                SideTool.FILES -> FilesGlyph
                SideTool.TASKS -> RampartIcons.Check
            }
            if (glyph == null) {
                RookAvatar(size = 20.dp, ring = open, working = working)
            } else {
                Icon(
                    glyph,
                    contentDescription = name,
                    tint = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The signed-in accounts, one face per row, then the add button.
 *
 * They used to overlap in a row under the folders, and that row scrolled away. A
 * vertical strip has nothing beside it to overlap, and this column does not scroll,
 * so the faces stay put at the bottom edge.
 *
 * Still capped at [MAX_ACCOUNT_FACES]. Past the cap the last row shows "+N", and
 * that account's picture is left out, so the number counts everyone without a
 * face. Same rule as the old stack. A shared mailbox is left out too, as it was
 * on the old row. It is a folder shared through an account, and that account
 * already has its own face.
 *
 * Clicking a face still opens account settings, which is what the old stack's one
 * click did. The account whose mail is on screen is marked the way an open tool
 * on this bar is marked: the same light wash behind the button, and, because this
 * one is a face, the same ring Rook's picture gets when his panel is open.
 */
@Composable
private fun ColumnScope.AccountSwitcher(
    accounts: List<AccountMailboxes>,
    currentKey: String?,
    onAccount: () -> Unit,
    onAdd: () -> Unit,
) {
    val own = accounts.filterNot { isSharedKey(it.key) }
    val shown = own.take(MAX_ACCOUNT_FACES)
    val extra = own.size - shown.size
    shown.forEachIndexed { index, account ->
        val fold = extra > 0 && index == shown.lastIndex
        if (fold) {
            val folded = own.drop(index)
            AccountFaceButton(
                tooltip = "${folded.size} more accounts",
                current = currentKey != null && folded.any { it.key == currentKey },
                onClick = onAccount,
            ) {
                OverflowCount(folded.size)
            }
        } else {
            // The inbox's unread conversations, the same number the All inboxes row adds.
            // Every other folder keeps its own count in the folder list.
            val unread = folderFor("inbox", account.mailboxes)?.unreadThreads ?: 0
            val address = account.email.ifBlank { shortAccountName(account.name, account.email) }
            AccountFaceButton(
                tooltip = address.ifBlank { "Account" },
                current = account.key == currentKey,
                onClick = onAccount,
            ) {
                Box(
                    Modifier.size(AccountFace).clip(CircleShape).then(
                        if (account.key == currentKey) {
                            Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        } else {
                            Modifier
                        },
                    ),
                ) {
                    Avatar(account.name, account.email, AccountFace)
                }
                if (unread > 0) {
                    // In from the corner, so the rounded button does not shave the badge.
                    UnreadBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp))
                }
            }
        }
    }
    AddAccountButton(onAdd)
}

/**
 * One row of the account strip: the bar's button size, so the unread badge in the
 * corner stays inside the width the bar already has.
 */
@Composable
private fun AccountFaceButton(
    tooltip: String,
    current: Boolean,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    SidebarTooltip(tooltip) {
        Box(
            Modifier.size(AccountSlot)
                .clip(MaterialTheme.shapes.small)
                .background(
                    if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                )
                .clickable(onClickLabel = "Account settings", role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/**
 * What the last row shows once there are more accounts than [MAX_ACCOUNT_FACES].
 * Coloured like a face, with the count written in it, so it reads as more accounts.
 */
@Composable
private fun OverflowCount(count: Int) {
    Box(
        Modifier.size(AccountFace).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+$count",
            fontSize = if (count >= 10) 8.sp else 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Unread conversations, sat on the corner of the button.
 *
 * Past 99 it says 99+. A longer number would cover the face, and the button cannot
 * grow without widening the bar.
 */
@Composable
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .defaultMinSize(minWidth = 14.dp, minHeight = 14.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            maxLines = 1,
        )
    }
}

/**
 * The add-account button under the faces.
 *
 * A plain "+". The icon pack is shared by every theme, and one button does not earn
 * a member on it. The click runs the add action on its own.
 */
@Composable
private fun AddAccountButton(onClick: () -> Unit) {
    SidebarTooltip("Add another account") {
        Box(
            Modifier.size(AccountSlot)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = "Add another account", role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(AccountFace)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // Drawn, not a "+" glyph: the font puts its plus below the middle of the line box.
                val colour = MaterialTheme.colorScheme.outline
                Canvas(Modifier.size(10.dp)) {
                    val w = 1.5.dp.toPx()
                    drawLine(colour, Offset(0f, center.y), Offset(size.width, center.y), w)
                    drawLine(colour, Offset(center.x, 0f), Offset(center.x, size.height), w)
                }
            }
        }
    }
}

/**
 * The title line every panel of ours starts with, and the way out of it.
 *
 * Rook and Contacts draw their own, since both were panes before there was a bar.
 */
@Composable
private fun PanelHeading(title: String, subtitle: String, onClose: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(
                RampartIcons.Close,
                contentDescription = "Close $title",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(15.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** A sentence in the panel's body, for why it is empty or what went wrong. */
@Composable
private fun PanelNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(16.dp),
    )
}

/** How a day is headed in the agenda: "Tomorrow" for the one after today, then the date. */
private fun dayHeading(date: LocalDate, today: LocalDate): String {
    if (date == today.plusDays(1)) return "Tomorrow"
    // The year is left off: the panel never looks further ahead than a week.
    val region = Regional.current()
    return date.dayOfWeek.getDisplayName(TextStyle.FULL, region.locale) + " " + formatMonthDay(region, date)
}

/**
 * The calendar beside the mail: what is left of today and the week after it.
 *
 * Read only, and deliberately short. Anything more than glancing at what is coming, such as
 * making or moving an event, is the full calendar's job, one click away at the top.
 */
@Composable
internal fun AgendaPanel(backend: MailBackend?, accountName: String, onOpenCalendar: () -> Unit) {
    val client = remember(backend) {
        (backend as? Jmap)?.takeIf { it.advertises(CALENDARS) }?.let(::CalendarClient)
    }
    val viewer = Regional.zone()
    var reload by remember { mutableIntStateOf(0) }
    var shown by remember(client) { mutableStateOf<Agenda<Occurrence>?>(null) }
    var loading by remember(client) { mutableStateOf(false) }
    var fault by remember(client) { mutableStateOf<Pair<String, String?>?>(null) }
    var today by remember { mutableStateOf(LocalDate.now(viewer)) }

    LaunchedEffect(client, reload, viewer) {
        val calendar = client ?: return@LaunchedEffect
        loading = true
        try {
            val now = LocalDateTime.now(viewer)
            val first = now.toLocalDate()
            val until = first.plusDays(SIDE_AGENDA_DAYS.toLong())
            val read = withContext(Dispatchers.IO) {
                // The owner's own choice of what is hidden, the same as the full calendar
                // starts from, so a hidden rota does not fill the panel it is hidden from.
                val hidden = runCatching { calendar.calendars() }.getOrDefault(emptyList())
                    .filter { !it.isVisible }.map { it.id }.toSet()
                // A day either side, because the query is in UTC and the window is local.
                // The day before also catches what started yesterday and is still on.
                val events = calendar.events(
                    utcStamp(first.minusDays(1).atStartOfDay(), viewer),
                    utcStamp(until.plusDays(1).atStartOfDay(), viewer),
                )
                expandAll(
                    events.filter { e -> e.calendarIds.isEmpty() || e.calendarIds.any { it !in hidden } },
                    first.minusDays(1),
                    until,
                    viewer,
                )
            }
            today = first
            shown = agenda(read, now) { EventSpan(it.start, it.end, it.allDay) }
            fault = null
        } catch (e: Exception) {
            fault = "Could not read your events." to whyFailed(e)
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        PanelHeading("Calendar", accountName, onClose = { AppBar.close(SideTool.CALENDAR) }) {
            if (client != null) {
                IconButton(onClick = { reload++ }, enabled = !loading, modifier = Modifier.size(28.dp)) {
                    Icon(
                        RampartIcons.Refresh,
                        contentDescription = "Read the calendar again",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
        TextButton(onClick = onOpenCalendar, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Open the full calendar")
        }
        if (client == null) {
            PanelNote(
                when {
                    backend == null -> "Sign in to an account to see its calendar."
                    backend is Jmap -> "$accountName's server keeps no calendar over JMAP, so there is nothing to show here."
                    else -> "IMAP carries mail only, so $accountName's calendar, if it has one, is on a server Rampart does not read."
                },
            )
            return@Column
        }
        fault?.let { (title, detail) ->
            FaultText(title, detail, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        val coming = shown
        when {
            coming == null && loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
            coming == null -> Unit
            coming.isEmpty -> PanelNote("Nothing on today or in the next ${SIDE_AGENDA_DAYS - 1} days.")
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                item(key = "today") { DayHeading("Today") }
                if (coming.today.isEmpty()) {
                    item(key = "today-none") {
                        Text(
                            "Nothing else today.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                items(coming.today.size, key = { "t$it" }) { index ->
                    AgendaLine(coming.today[index], onOpenCalendar)
                }
                coming.upcoming.forEach { day ->
                    item(key = "d${day.date}") { DayHeading(dayHeading(day.date, today)) }
                    items(day.items.size, key = { "d${day.date}-$it" }) { index ->
                        AgendaLine(day.items[index], onOpenCalendar)
                    }
                }
                item(key = "end") { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun DayHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

/** One event: when, then what, then where. A click goes to the full calendar, where it can be changed. */
@Composable
private fun AgendaLine(o: Occurrence, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(
            timeText(o),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            o.title.ifBlank { "(no title)" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (o.location.isNotBlank()) {
            Text(
                o.location,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The account's folders beside the mail. A click opens the Files page on that folder, which
 * is where uploading, moving and the rest already live; this is the way in, not a copy.
 */
@Composable
internal fun FilesPanel(session: Session?, onOpen: (String?) -> Unit) {
    val unavailable = filesUnavailable(session)
    val store = remember(session) { filesOf(session?.jmap) }
    var reload by remember { mutableIntStateOf(0) }
    var tree by remember(store) { mutableStateOf<FileTree?>(null) }
    var loading by remember(store) { mutableStateOf(false) }
    var fault by remember(store) { mutableStateOf<Pair<String, String?>?>(null) }

    LaunchedEffect(store, reload) {
        val files = store ?: return@LaunchedEffect
        loading = true
        try {
            tree = withContext(Dispatchers.IO) { files.tree() }
            fault = null
        } catch (e: Exception) {
            val title = "The files could not be read."
            fault = title to faultDetail(e, title)
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        PanelHeading(
            "Files",
            session?.let { shortAccountName(it.account.name, it.account.email) }.orEmpty(),
            onClose = { AppBar.close(SideTool.FILES) },
        ) {
            if (store != null) {
                IconButton(onClick = { reload++ }, enabled = !loading, modifier = Modifier.size(28.dp)) {
                    Icon(
                        RampartIcons.Refresh,
                        contentDescription = "Read the files again",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
        if (unavailable != null || store == null) {
            PanelNote(unavailable ?: "This account keeps no files.")
            return@Column
        }
        TextButton(onClick = { onOpen(null) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Open Files")
        }
        fault?.let { (title, detail) ->
            FaultText(title, detail, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        val shown = tree
        when {
            shown == null && loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
            shown == null -> Unit
            else -> {
                val outline = remember(shown) { shown.folderOutline() }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    item(key = "top") { FolderLine("All files", 0, false) { onOpen(null) } }
                    items(outline, key = { it.first.id }) { (folder, depth) ->
                        FolderLine(folder.name, depth + 1, false) { onOpen(folder.id) }
                    }
                    if (outline.isEmpty()) {
                        item(key = "none") { PanelNote("No folders yet. Everything is at the top.") }
                    }
                }
            }
        }
    }
}

/**
 * A few nods when an update first becomes ready, then the arrow stays still.
 * The coloured label is what remains visible after that. Moved in the draw
 * layer only, so nothing is laid out again, and still when animations are off.
 */
@Composable
private fun Modifier.updateBob(version: String): Modifier {
    val enabled = LocalAnimationsEnabled.current
    val bob = remember(version) { Animatable(0f) }
    LaunchedEffect(version, enabled) {
        if (!enabled) {
            bob.snapTo(0f)
            return@LaunchedEffect
        }
        repeat(3) {
            bob.animateTo(2.5f, tween(300))
            bob.animateTo(0f, tween(300))
        }
    }
    if (!enabled) return this
    val offset = bob.value
    return graphicsLayer { translationY = offset * density }
}
