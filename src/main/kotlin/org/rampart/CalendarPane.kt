package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** When it is, for the details dialog: the day as well as the time. */
private fun whenLong(o: Occurrence): String = when {
    o.allDay -> {
        val last = o.end.toLocalDate().minusDays(1)
        if (!last.isAfter(o.start.toLocalDate())) "${o.start.format(DAY_TITLE)}, all day"
        else "${o.start.format(DAY_TITLE)} to ${last.format(DAY_TITLE)}, all day"
    }
    else -> "${o.start.format(DAY_TITLE)}, ${timeText(o)}"
}

/** What the editor is open on. */
private data class Editing(
    val draft: EventDraft,
    /** Null for a new event. */
    val occurrence: Occurrence? = null,
)

/**
 * The account's calendar, as the server holds it.
 *
 * Month, week, day and agenda, over JMAP for Calendars. Everything is read and written as
 * the signed-in user with the mail session's own credential, and every event is kept on
 * the server, so what is made here is on the phone as well.
 *
 * An account without the capability gets one sentence saying why and nothing else: an IMAP
 * account's calendar, if it has one, lives on CalDAV, which is a different protocol on a
 * different path and not something this page speaks.
 */
@Composable
internal fun CalendarPane(backend: MailBackend?, accountName: String) {
    val client = remember(backend) {
        (backend as? Jmap)?.takeIf { it.advertises(CALENDARS) }?.let(::CalendarClient)
    }
    if (client == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text("Calendar", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    backend == null -> "Sign in to an account to see its calendar."
                    backend is Jmap -> "$accountName's server keeps no calendar over JMAP, so there is nothing to show here."
                    else -> "IMAP carries mail only, so $accountName's calendar, if it has one, is on a server Rampart does not read."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        return
    }

    val scope = rememberCoroutineScope()
    val viewer = remember { ZoneId.systemDefault() }
    var view by remember { mutableStateOf(CalendarView.MONTH) }
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var calendars by remember(client) { mutableStateOf<List<CalendarInfo>>(emptyList()) }
    var hidden by remember(client) { mutableStateOf<Set<String>>(emptySet()) }
    var events by remember(client) { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    var loading by remember(client) { mutableStateOf(true) }
    var fault by remember(client) { mutableStateOf<Pair<String, String?>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var details by remember { mutableStateOf<Occurrence?>(null) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var deleting by remember { mutableStateOf<Occurrence?>(null) }

    val range = visibleRange(view, anchor)
    val writable = calendars.filter { it.mayWrite }
    val byId = calendars.associateBy { it.id }

    LaunchedEffect(client, reload) {
        try {
            val read = withContext(Dispatchers.IO) { client.calendars() }
            // The server's own choice of what is hidden is the starting point, and only on
            // the first read: after that the toggles here are what the person last clicked.
            if (calendars.isEmpty()) hidden = read.filter { !it.isVisible }.map { it.id }.toSet()
            calendars = read
        } catch (e: Exception) {
            fault = "Could not read your calendars." to whyFailed(e)
        }
    }
    LaunchedEffect(client, range, reload) {
        loading = true
        try {
            // A day either side, because the query is in UTC and the window is in local time.
            val after = utcStamp(range.first.minusDays(1).atStartOfDay(), viewer)
            val before = utcStamp(range.second.plusDays(1).atStartOfDay(), viewer)
            events = withContext(Dispatchers.IO) { client.events(after, before) }
            fault = null
        } catch (e: Exception) {
            fault = "Could not read your events." to whyFailed(e)
        } finally {
            loading = false
        }
    }

    val shown = remember(events, range, hidden, viewer) {
        expandAll(events.filter { e -> e.calendarIds.isEmpty() || e.calendarIds.any { it !in hidden } }, range.first, range.second, viewer)
    }

    /**
     * Runs a write off the window's thread, and shows its failure in words.
     *
     * Reloading only on success matters, not just tidiness: the events effect below clears
     * [fault] whenever its own read succeeds, so reloading after a failed write raced that
     * effect and the write's own error banner was wiped a moment after it appeared, which is
     * why a refused CalendarEvent/set used to look like it had done nothing and said nothing.
     * On failure nothing changed on the server, so there is nothing to reload for either.
     */
    fun write(what: String, job: (CalendarClient) -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { job(client) }
                fault = null
                reload++
            } catch (e: Exception) {
                fault = what to whyFailed(e)
            }
        }
    }

    fun newAt(at: LocalDateTime, allDay: Boolean = false) {
        val calendar = writable.firstOrNull { it.isDefault && it.id !in hidden } ?: writable.firstOrNull { it.id !in hidden } ?: writable.firstOrNull()
        editing = Editing(
            EventDraft(
                title = "",
                calendarId = calendar?.id.orEmpty(),
                start = at,
                end = if (allDay) at else at.plusHours(1),
                allDay = allDay,
                timeZone = viewer,
            ),
        )
    }

    Row(Modifier.fillMaxSize()) {
        CalendarList(
            calendars = calendars,
            hidden = hidden,
            onToggle = { calendar ->
                val nowHidden = calendar.id !in hidden
                hidden = if (nowHidden) hidden + calendar.id else hidden - calendar.id
                // Written back so the choice follows the account. A server that will not
                // take it still has the toggle here, and says so rather than failing quietly.
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.setVisible(calendar.id, !nowHidden) }
                    } catch (e: Exception) {
                        fault = "Hidden here only: the server would not remember it." to whyFailed(e)
                    }
                }
            },
        )
        VerticalDivider()
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(rangeTitle(view, anchor), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (loading) Spinner(size = 18.dp, thickness = 2.dp, modifier = Modifier.padding(end = 10.dp))
                TextButton(onClick = { anchor = LocalDate.now() }) { Text("Today") }
                IconButton(onClick = { anchor = stepped(view, anchor, forward = false) }, modifier = Modifier.size(32.dp)) {
                    Icon(RampartIcons.Back, contentDescription = "Earlier", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { anchor = stepped(view, anchor, forward = true) }, modifier = Modifier.size(32.dp)) {
                    Icon(RampartIcons.Back, contentDescription = "Later", modifier = Modifier.size(16.dp).rotate(180f))
                }
                Spacer(Modifier.width(8.dp))
                CalendarView.entries.forEach { each ->
                    FilterChip(
                        selected = view == each,
                        onClick = { view = each },
                        label = { Text(each.label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { newAt(anchor.atTime(9, 0)) },
                    enabled = writable.isNotEmpty(),
                ) { Text("New event") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (calendars.isNotEmpty() && writable.isEmpty()) {
                    "$accountName. Every calendar here is shared with you to read, so nothing can be added to it."
                } else {
                    "$accountName. Kept on the server, so the same calendar is on your phone."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            fault?.let { (title, detail) ->
                Spacer(Modifier.height(8.dp))
                FaultText(title, detail)
            }
            Spacer(Modifier.height(12.dp))
            val colourOf = { o: Occurrence -> calendarColour(byId[o.calendarId]) }
            val open = { o: Occurrence -> details = o }
            when (view) {
                CalendarView.MONTH -> MonthGrid(
                    range = range,
                    month = anchor.monthValue,
                    occurrences = shown,
                    colourOf = colourOf,
                    onOpen = open,
                    onDay = { anchor = it; view = CalendarView.DAY },
                    onEmpty = if (writable.isEmpty()) null else { day -> newAt(day.atTime(9, 0)) },
                )
                CalendarView.WEEK, CalendarView.DAY -> TimeGrid(
                    days = generateSequence(range.first) { it.plusDays(1) }.takeWhile { it.isBefore(range.second) }.toList(),
                    occurrences = shown,
                    colourOf = colourOf,
                    onOpen = open,
                    onDay = { anchor = it; view = CalendarView.DAY },
                    onEmpty = if (writable.isEmpty()) null else { at, allDay -> newAt(at, allDay) },
                )
                CalendarView.AGENDA -> Agenda(range, shown, colourOf, open)
            }
        }
    }

    details?.let { o ->
        EventDetails(
            occurrence = o,
            calendar = byId[o.calendarId],
            mayWrite = byId[o.calendarId]?.mayWrite ?: writable.isNotEmpty(),
            onClose = { details = null },
            onEdit = {
                details = null
                editing = Editing(draftFrom(o, calendars).copy(timeZone = viewer), o)
            },
            onDelete = { details = null; deleting = o },
        )
    }

    editing?.let { current ->
        EventEditor(
            editing = current,
            calendars = writable,
            onCancel = { editing = null },
            onSave = { draft, onlyThisOne ->
                editing = null
                val o = current.occurrence
                when {
                    o == null -> write("The event was not created.") { it.create(newEventObject(draft)) }
                    onlyThisOne && o.recurrenceId != null -> write("That one time was not changed.") {
                        it.update(o.event.id, overridePatch(o.event, o.recurrenceId, instanceChange(o.event, draft)))
                    }
                    o.recurrenceId != null -> write("The event was not changed.") {
                        it.update(o.event.id, eventPatch(o.event, seriesDraft(o, draft, viewer)))
                    }
                    else -> write("The event was not changed.") { it.update(o.event.id, eventPatch(o.event, draft)) }
                }
            },
        )
    }

    deleting?.let { o ->
        val repeating = o.recurrenceId != null
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${o.title.ifBlank { "this event" }}?") },
            text = {
                Text(
                    if (repeating) "It repeats. Remove only this time, or every time it happens?"
                    else "This removes it from the server, everywhere the calendar syncs.",
                )
            },
            confirmButton = {
                Row {
                    if (repeating) {
                        TextButton(onClick = {
                            deleting = null
                            write("That one time was not deleted.") { it.update(o.event.id, excludedPatch(o.event, o.recurrenceId!!)) }
                        }) { Text("Only this one") }
                    }
                    TextButton(onClick = {
                        deleting = null
                        write("The event was not deleted.") { it.destroy(o.event.id) }
                    }) { Text(if (repeating) "Every time" else "Delete") }
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun CalendarList(calendars: List<CalendarInfo>, hidden: Set<String>, onToggle: (CalendarInfo) -> Unit) {
    Column(Modifier.width(210.dp).fillMaxHeight().padding(horizontal = 12.dp, vertical = 16.dp)) {
        Text("Calendars", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (calendars.isEmpty()) {
            Text("None yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        calendars.forEach { calendar ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onToggle(calendar) }.padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = calendar.id !in hidden, onCheckedChange = { onToggle(calendar) })
                Box(Modifier.size(10.dp).clip(CircleShape).background(calendarColour(calendar)))
                Spacer(Modifier.width(8.dp))
                Text(
                    calendar.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A coloured bar with the event's name in it, the unit every view is built from. */
@Composable
private fun Chip(text: String, colour: Color, onClick: () -> Unit, modifier: Modifier = Modifier, solid: Boolean = false) {
    val ink = if (solid) (if (luminance(colour) > 0.45) Color.Black else Color.White) else MaterialTheme.colorScheme.onSurface
    Box(
        modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(4.dp))
            .background(if (solid) colour else colour.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!solid) {
                Box(Modifier.width(3.dp).height(12.dp).clip(RoundedCornerShape(2.dp)).background(colour))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun luminance(c: Color): Double = 0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue

private fun weekdayHeaders(days: List<LocalDate>) = days.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK) }

@Composable
private fun MonthGrid(
    range: Pair<LocalDate, LocalDate>,
    month: Int,
    occurrences: List<Occurrence>,
    colourOf: (Occurrence) -> Color,
    onOpen: (Occurrence) -> Unit,
    onDay: (LocalDate) -> Unit,
    onEmpty: ((LocalDate) -> Unit)?,
) {
    val days = generateSequence(range.first) { it.plusDays(1) }.takeWhile { it.isBefore(range.second) }.toList()
    val byDay = remember(occurrences) {
        buildMap<LocalDate, MutableList<Occurrence>> { occurrences.forEach { o -> o.days().forEach { getOrPut(it) { mutableListOf() } += o } } }
    }
    val today = LocalDate.now()
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            weekdayHeaders(days.take(7)).forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f).padding(start = 6.dp, bottom = 4.dp),
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                week.forEach { day ->
                    val here = byDay[day].orEmpty()
                    Column(
                        Modifier.weight(1f).fillMaxHeight().border(0.5.dp, line)
                            .let { m -> if (onEmpty != null) m.clickable { onEmpty(day) } else m }
                            .padding(4.dp),
                    ) {
                        val isToday = day == today
                        Box(
                            Modifier.size(22.dp).clip(CircleShape)
                                .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable { onDay(day) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                day.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    isToday -> MaterialTheme.colorScheme.onPrimary
                                    day.monthValue != month -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                            // As many as fit, and a count of the rest, so a busy day never
                            // spills into the next week's row.
                            val fits = ((maxHeight.value - 16f) / 19f).toInt().coerceAtLeast(1)
                            val showing = if (here.size <= fits + 1) here else here.take(fits)
                            Column {
                                showing.forEach { o ->
                                    Chip(
                                        if (o.allDay) o.title.ifBlank { "(untitled)" } else "${o.start.format(CALENDAR_CLOCK)} ${o.title.ifBlank { "(untitled)" }}",
                                        colourOf(o),
                                        onClick = { onOpen(o) },
                                        solid = o.allDay,
                                    )
                                }
                                if (showing.size < here.size) {
                                    Text(
                                        "${here.size - showing.size} more",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { onDay(day) }.padding(start = 4.dp, top = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val HOUR = 44.dp
private val GUTTER = 52.dp

@Composable
private fun TimeGrid(
    days: List<LocalDate>,
    occurrences: List<Occurrence>,
    colourOf: (Occurrence) -> Color,
    onOpen: (Occurrence) -> Unit,
    onDay: (LocalDate) -> Unit,
    onEmpty: ((LocalDateTime, Boolean) -> Unit)?,
) {
    val today = LocalDate.now()
    val line = MaterialTheme.colorScheme.outlineVariant
    val scroll = rememberScrollState()
    val hourPx = with(LocalDensity.current) { HOUR.toPx() }
    // Opened at seven in the morning, which is where most days start, rather than at midnight.
    LaunchedEffect(Unit) { scroll.scrollTo((hourPx * 7).toInt()) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(GUTTER))
            days.forEach { day ->
                Text(
                    "${day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK)} ${day.dayOfMonth}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                    color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).clickable { onDay(day) }.padding(start = 6.dp, bottom = 4.dp),
                )
            }
        }
        // Events with no time, and events that run across midnight into another day, go on a
        // strip above the hours: placed in the grid they would be a column the height of the day.
        Row(Modifier.fillMaxWidth().border(0.5.dp, line)) {
            Text(
                "All day",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(GUTTER).padding(4.dp),
            )
            days.forEach { day ->
                val here = occurrences.filter { (it.allDay || it.days().size > 1) && day in it.days() }
                Column(
                    Modifier.weight(1f).heightIn(min = 24.dp)
                        .let { m -> if (onEmpty != null) m.clickable { onEmpty(day.atStartOfDay(), true) } else m }
                        .padding(2.dp),
                ) {
                    here.forEach { o -> Chip(o.title.ifBlank { "(untitled)" }, colourOf(o), onClick = { onOpen(o) }, solid = true) }
                }
            }
        }
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth().height(HOUR * 24)) {
                Column(Modifier.width(GUTTER)) {
                    (0 until 24).forEach { hour ->
                        Text(
                            if (hour == 0) "" else LocalTime.of(hour, 0).format(CALENDAR_CLOCK),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.height(HOUR).padding(start = 4.dp),
                        )
                    }
                }
                days.forEach { day ->
                    val timed = occurrences.filter { !it.allDay && it.days().size == 1 && it.start.toLocalDate() == day }
                    val spans = timed.map { o ->
                        val from = o.start.hour * 60 + o.start.minute
                        val to = if (o.end.toLocalDate() != day) 24 * 60 else o.end.hour * 60 + o.end.minute
                        from to maxOf(to, from + 20)
                    }
                    val placed = sideBySide(spans)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, line)) {
                        val width = maxWidth
                        Column {
                            (0 until 24).forEach { hour ->
                                Box(
                                    Modifier.fillMaxWidth().height(HOUR).border(0.25.dp, line)
                                        .let { m -> if (onEmpty != null) m.clickable { onEmpty(day.atTime(hour, 0), false) } else m },
                                )
                            }
                        }
                        timed.forEachIndexed { i, o ->
                            val (from, to) = spans[i]
                            val (column, columns) = placed[i]
                            val slot = width / columns
                            Box(
                                Modifier.offset(x = slot * column, y = HOUR * (from / 60f))
                                    .width(slot).height(HOUR * ((to - from) / 60f))
                                    .padding(horizontal = 1.dp, vertical = 1.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colourOf(o).copy(alpha = 0.22f))
                                    .border(1.dp, colourOf(o).copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                    .clickable { onOpen(o) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            ) {
                                Column {
                                    Text(
                                        o.title.ifBlank { "(untitled)" },
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        listOf(timeText(o), o.location).filter { it.isNotBlank() }.joinToString("  "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (day == today) {
                            val now = LocalTime.now()
                            Box(
                                Modifier.offset(y = HOUR * ((now.hour * 60 + now.minute) / 60f))
                                    .fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Agenda(range: Pair<LocalDate, LocalDate>, occurrences: List<Occurrence>, colourOf: (Occurrence) -> Color, onOpen: (Occurrence) -> Unit) {
    // A multi-day event is listed under each day it covers inside the window, and from the
    // first day of the window rather than before it, because that is where the reader is.
    val rows = remember(occurrences, range) {
        occurrences.flatMap { o -> o.days().filter { !it.isBefore(range.first) && it.isBefore(range.second) }.map { it to o } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
    }
    if (rows.isEmpty()) {
        Text(
            "Nothing in the next $AGENDA_DAYS days.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    val today = LocalDate.now()
    LazyColumn(Modifier.fillMaxSize()) {
        rows.forEach { (day, list) ->
            item(key = "d$day") {
                Text(
                    (if (day == today) "Today, " else "") + day.format(DAY_TITLE),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                )
                HorizontalDivider()
            }
            items(list, key = { o -> "$day ${o.event.id} ${o.recurrenceId}" }) { o ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(o) }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        timeText(o),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.width(150.dp),
                    )
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colourOf(o)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(o.title.ifBlank { "(untitled)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (o.location.isNotBlank()) {
                            Text(o.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventDetails(
    occurrence: Occurrence,
    calendar: CalendarInfo?,
    mayWrite: Boolean,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(occurrence.title.ifBlank { "(untitled)" }) },
        text = {
            SelectionContainer {
                Column(Modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(whenLong(occurrence), style = MaterialTheme.typography.bodyMedium)
                    if (occurrence.location.isNotBlank()) Text(occurrence.location, style = MaterialTheme.typography.bodyMedium)
                    calendar?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(calendarColour(it)))
                            Spacer(Modifier.width(6.dp))
                            Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    val repeats = repeatSentence(occurrence.event.rules, occurrence.event.start)
                    if (repeats.isNotBlank()) {
                        Text("$repeats.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (occurrence.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(occurrence.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            if (mayWrite) TextButton(onClick = onEdit) { Text("Edit") }
        },
        dismissButton = {
            Row {
                if (mayWrite) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}

private fun dayLetter(day: DayOfWeek) = day.getDisplayName(TextStyle.SHORT, Locale.UK)

/**
 * The event, in a dialog.
 *
 * Dates and times are typed rather than picked. They are short, a keyboard is quicker than
 * a picker for anyone who knows the date, and a field that says what is wrong with what was
 * typed is easier to trust than a picker that silently rounds.
 */
@Composable
private fun EventEditor(
    editing: Editing,
    calendars: List<CalendarInfo>,
    onCancel: () -> Unit,
    onSave: (EventDraft, Boolean) -> Unit,
) {
    val initial = editing.draft
    val repeating = editing.occurrence?.recurrenceId != null
    var title by remember { mutableStateOf(initial.title) }
    var allDay by remember { mutableStateOf(initial.allDay) }
    var startDate by remember { mutableStateOf(initial.start.toLocalDate().format(DATE_FIELD)) }
    var startTime by remember { mutableStateOf(initial.start.toLocalTime().format(CALENDAR_CLOCK)) }
    var endDate by remember { mutableStateOf(initial.end.toLocalDate().format(DATE_FIELD)) }
    var endTime by remember { mutableStateOf(initial.end.toLocalTime().format(CALENDAR_CLOCK)) }
    var location by remember { mutableStateOf(initial.location) }
    var description by remember { mutableStateOf(initial.description) }
    var calendarId by remember { mutableStateOf(initial.calendarId.ifBlank { calendars.firstOrNull()?.id.orEmpty() }) }
    var onlyThisOne by remember { mutableStateOf(false) }
    // Null means the rule is one the picker cannot show, and it is then left exactly as it is.
    val keepsRule = initial.repeat == null
    var frequency by remember { mutableStateOf(initial.repeat?.frequency) }
    var interval by remember { mutableStateOf((initial.repeat?.interval ?: 1).toString()) }
    var weekdays by remember { mutableStateOf(initial.repeat?.weekdays.orEmpty().ifEmpty { setOf(initial.start.dayOfWeek) }) }
    var ends by remember { mutableStateOf(when { initial.repeat?.count != null -> "count"; initial.repeat?.until != null -> "until"; else -> "never" }) }
    var until by remember { mutableStateOf(initial.repeat?.until?.format(DATE_FIELD) ?: initial.start.toLocalDate().plusMonths(3).format(DATE_FIELD)) }
    var count by remember { mutableStateOf((initial.repeat?.count ?: 10).toString()) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun build(): EventDraft? {
        val sd = runCatching { LocalDate.parse(startDate.trim(), DATE_FIELD) }.getOrNull()
            ?: return null.also { problem = "The start date is not a date. Write it as 2026-09-28." }
        val ed = runCatching { LocalDate.parse(endDate.trim(), DATE_FIELD) }.getOrNull()
            ?: return null.also { problem = "The end date is not a date. Write it as 2026-09-28." }
        val st = if (allDay) LocalTime.MIDNIGHT else runCatching { LocalTime.parse(startTime.trim(), CALENDAR_CLOCK) }.getOrNull()
            ?: return null.also { problem = "The start time is not a time. Write it as 09:30." }
        val et = if (allDay) LocalTime.MIDNIGHT else runCatching { LocalTime.parse(endTime.trim(), CALENDAR_CLOCK) }.getOrNull()
            ?: return null.also { problem = "The end time is not a time. Write it as 10:30." }
        val repeat = when {
            onlyThisOne -> null
            keepsRule -> null
            frequency == null -> SimpleRepeat()
            else -> SimpleRepeat(
                frequency = frequency,
                interval = interval.trim().toIntOrNull()?.takeIf { it >= 1 }
                    ?: return null.also { problem = "Repeat every how many? Give a whole number from 1." },
                weekdays = if (frequency == Frequency.WEEKLY) weekdays else emptySet(),
                until = if (ends == "until") {
                    runCatching { LocalDate.parse(until.trim(), DATE_FIELD) }.getOrNull()
                        ?: return null.also { problem = "The repeat's end is not a date. Write it as 2026-12-31." }
                } else null,
                count = if (ends == "count") {
                    count.trim().toIntOrNull() ?: return null.also { problem = "Repeat how many times? Give a whole number." }
                } else null,
            )
        }
        val draft = EventDraft(
            title = title,
            calendarId = calendarId,
            start = sd.atTime(st),
            end = ed.atTime(et),
            allDay = allDay,
            location = location,
            description = description,
            repeat = repeat,
            timeZone = initial.timeZone,
        )
        problem = draftProblem(draft)
        return draft.takeIf { problem == null }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (editing.occurrence == null) "New event" else "Edit event") },
        text = {
            Column(
                Modifier.widthIn(min = 420.dp, max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (repeating) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !onlyThisOne, onClick = { onlyThisOne = false }, label = { Text("Every time") })
                        FilterChip(selected = onlyThisOne, onClick = { onlyThisOne = true }, label = { Text("Only this one") })
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { allDay = !allDay }) {
                    Checkbox(checked = allDay, onCheckedChange = { allDay = it })
                    Text("All day", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(startDate, { startDate = it }, label = { Text("Starts") }, singleLine = true, modifier = Modifier.weight(1f))
                    if (!allDay) OutlinedTextField(startTime, { startTime = it }, label = { Text("At") }, singleLine = true, modifier = Modifier.width(96.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(endDate, { endDate = it }, label = { Text(if (allDay) "Last day" else "Ends") }, singleLine = true, modifier = Modifier.weight(1f))
                    if (!allDay) OutlinedTextField(endTime, { endTime = it }, label = { Text("At") }, singleLine = true, modifier = Modifier.width(96.dp))
                }
                OutlinedTextField(location, { location = it }, label = { Text("Where") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (calendars.size > 1 && !onlyThisOne) {
                    Text("Calendar", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        calendars.forEach { each ->
                            FilterChip(
                                selected = calendarId == each.id,
                                onClick = { calendarId = each.id },
                                label = { Text(each.name, maxLines = 1) },
                                leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(calendarColour(each))) },
                            )
                        }
                    }
                }
                if (!onlyThisOne) {
                    Text("Repeats", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    if (keepsRule) {
                        Text(
                            repeatSentence(editing.occurrence?.event?.rules.orEmpty(), initial.start) +
                                ". Kept as it is, because changing it here would lose part of it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        RepeatPicker(
                            frequency = frequency,
                            onFrequency = { frequency = it },
                            interval = interval,
                            onInterval = { interval = it },
                            weekdays = weekdays,
                            onWeekdays = { weekdays = it },
                            ends = ends,
                            onEnds = { ends = it },
                            until = until,
                            onUntil = { until = it },
                            count = count,
                            onCount = { count = it },
                        )
                    }
                }
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { build()?.let { onSave(it, onlyThisOne) } }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun RepeatPicker(
    frequency: Frequency?,
    onFrequency: (Frequency?) -> Unit,
    interval: String,
    onInterval: (String) -> Unit,
    weekdays: Set<DayOfWeek>,
    onWeekdays: (Set<DayOfWeek>) -> Unit,
    ends: String,
    onEnds: (String) -> Unit,
    until: String,
    onUntil: (String) -> Unit,
    count: String,
    onCount: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = frequency == null, onClick = { onFrequency(null) }, label = { Text("Never") })
        listOf(Frequency.DAILY to "Daily", Frequency.WEEKLY to "Weekly", Frequency.MONTHLY to "Monthly", Frequency.YEARLY to "Yearly")
            .forEach { (f, label) -> FilterChip(selected = frequency == f, onClick = { onFrequency(f) }, label = { Text(label) }) }
    }
    if (frequency == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Every", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(interval, onInterval, singleLine = true, modifier = Modifier.width(72.dp))
        Text(
            when (frequency) {
                Frequency.DAILY -> "days"
                Frequency.WEEKLY -> "weeks"
                Frequency.MONTHLY -> "months"
                Frequency.YEARLY -> "years"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (frequency == Frequency.WEEKLY) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.entries.forEach { day ->
                FilterChip(
                    selected = day in weekdays,
                    // Never down to none: a weekly rule on no days is not a rule.
                    onClick = { onWeekdays(if (day in weekdays) (weekdays - day).ifEmpty { weekdays } else weekdays + day) },
                    label = { Text(dayLetter(day)) },
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = ends == "never", onClick = { onEnds("never") }, label = { Text("Forever") })
        FilterChip(selected = ends == "until", onClick = { onEnds("until") }, label = { Text("Until") })
        FilterChip(selected = ends == "count", onClick = { onEnds("count") }, label = { Text("A number of times") })
    }
    if (ends == "until") {
        OutlinedTextField(until, onUntil, label = { Text("Last date") }, singleLine = true, modifier = Modifier.width(180.dp))
    }
    if (ends == "count") {
        OutlinedTextField(count, onCount, label = { Text("Times") }, singleLine = true, modifier = Modifier.width(120.dp))
    }
}
