package org.rampart

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/*
 * The calendar's arithmetic that is about drawing rather than about events: which days a
 * view covers, what its title says, how overlapping events share a column. Kept apart from
 * CalendarPane.kt so it can be tested without a window.
 */

internal enum class CalendarView(val label: String) { MONTH("Month"), WEEK("Week"), DAY("Day"), AGENDA("Agenda") }

/** How many days the agenda looks ahead. A month is what a person plans against. */
internal const val AGENDA_DAYS = 30L

/**
 * The days a view draws, from the first up to but not including the second.
 *
 * Weeks start on Monday, which is the ISO week and what most of the world's calendars
 * use. A month is always six whole weeks, so the grid does not change height as you page.
 */
internal fun visibleRange(view: CalendarView, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (view) {
    CalendarView.MONTH -> {
        val first = anchor.withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        first to first.plusDays(42)
    }
    CalendarView.WEEK -> {
        val first = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        first to first.plusDays(7)
    }
    CalendarView.DAY -> anchor to anchor.plusDays(1)
    CalendarView.AGENDA -> anchor to anchor.plusDays(AGENDA_DAYS)
}

/** Where the previous and next arrows land. */
internal fun stepped(view: CalendarView, anchor: LocalDate, forward: Boolean): LocalDate {
    val sign = if (forward) 1L else -1L
    return when (view) {
        CalendarView.MONTH -> anchor.withDayOfMonth(1).plusMonths(sign)
        CalendarView.WEEK -> anchor.plusWeeks(sign)
        CalendarView.DAY -> anchor.plusDays(sign)
        CalendarView.AGENDA -> anchor.plusDays(sign * AGENDA_DAYS)
    }
}

internal val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)
internal val DAY_TITLE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)
internal val SHORT_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
internal val CALENDAR_CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
internal val DATE_FIELD = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** The header over the grid, in words. */
internal fun rangeTitle(view: CalendarView, anchor: LocalDate): String {
    val (first, until) = visibleRange(view, anchor)
    val last = until.minusDays(1)
    return when (view) {
        CalendarView.MONTH -> anchor.format(MONTH_TITLE)
        CalendarView.DAY -> anchor.format(DAY_TITLE)
        else -> if (first.year == last.year) {
            "${first.format(SHORT_DAY)} to ${last.format(SHORT_DAY)} ${last.year}"
        } else {
            "${first.format(SHORT_DAY)} ${first.year} to ${last.format(SHORT_DAY)} ${last.year}"
        }
    }
}

/** An occurrence's time as a person reads it on a list. */
internal fun timeText(o: Occurrence): String = when {
    o.allDay -> {
        val last = o.end.toLocalDate().minusDays(1)
        if (!last.isAfter(o.start.toLocalDate())) "All day" else "All day, to ${last.format(SHORT_DAY)}"
    }
    o.start.toLocalDate() == o.end.toLocalDate() || o.end == o.start.toLocalDate().plusDays(1).atStartOfDay() ->
        "${o.start.format(CALENDAR_CLOCK)} to ${o.end.format(CALENDAR_CLOCK)}"
    else -> "${o.start.format(CALENDAR_CLOCK)} to ${o.end.format(SHORT_DAY)} ${o.end.format(CALENDAR_CLOCK)}"
}

/**
 * Side by side placement for one day's timed events, as (column, columns) in input order.
 *
 * Events that overlap share the width; a cluster of overlapping events all take the same
 * number of columns so their edges line up. Greedy, which is what every calendar does and
 * is right for any day a person actually has.
 */
internal fun sideBySide(spans: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    val order = spans.indices.sortedWith(compareBy({ spans[it].first }, { -spans[it].second }))
    val column = IntArray(spans.size)
    val columns = IntArray(spans.size)
    var cluster = mutableListOf<Int>()
    var clusterEnd = Int.MIN_VALUE
    val ends = mutableListOf<Int>()
    fun close() {
        val width = cluster.maxOfOrNull { column[it] + 1 } ?: 0
        cluster.forEach { columns[it] = width }
        cluster = mutableListOf()
        ends.clear()
    }
    for (i in order) {
        val (start, end) = spans[i]
        // A zero length event still takes a sliver of the grid, so it is given a minute.
        val stop = maxOf(end, start + 1)
        if (cluster.isNotEmpty() && start >= clusterEnd) close()
        val free = ends.indexOfFirst { it <= start }
        if (free >= 0) {
            column[i] = free
            ends[free] = stop
        } else {
            column[i] = ends.size
            ends += stop
        }
        cluster += i
        clusterEnd = if (cluster.size == 1) stop else maxOf(clusterEnd, stop)
    }
    close()
    return spans.indices.map { column[it] to columns[it] }
}

/**
 * Where a series moves when one of its instances is edited as "every time".
 *
 * The editor opens on the instance that was clicked, which may be the fortieth. Saving that
 * date as the series start would drop the thirty-nine before it, so the change is taken as
 * a shift and applied to the series' own first date instead.
 *
 * The result keeps the event's own time zone rather than the viewer's, even though the
 * editor worked in the viewer's. A repeating rule is expanded in whatever zone is stored
 * with it, so writing the series back in the viewer's zone would move every future
 * occurrence onto that zone's daylight saving boundaries instead of its own.
 */
internal fun seriesDraft(occurrence: Occurrence, draft: EventDraft, viewer: ZoneId): EventDraft {
    val event = occurrence.event
    val first = if (event.allDay) event.start else {
        event.start.atZone(event.timeZone ?: viewer).withZoneSameInstant(viewer).toLocalDateTime()
    }
    val shift = Duration.between(occurrence.start, draft.start)
    val length = Duration.between(draft.start, draft.end)
    val start = first.plus(shift)
    return draft.copy(start = start, end = start.plus(length), timeZone = event.timeZone ?: viewer)
}
