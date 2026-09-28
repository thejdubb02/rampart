package org.rampart

import java.time.LocalDate
import java.time.LocalDateTime

/*
 * What the calendar panel beside the mail lists: what is left of today, then the next few
 * days. Kept apart from the panel so the edges of "today" can be checked without a window,
 * because those edges are where an agenda goes wrong: the meeting that ended an hour ago,
 * the all-day event that ends at midnight, the one that started yesterday and is still on.
 */

/**
 * When something happens, in the reader's own local time.
 *
 * [end] is exclusive. For an all-day event it is the midnight after the last day, which is
 * how [Occurrence] carries it, so a one-day event on the fourth ends at the start of the fifth.
 */
internal data class EventSpan(val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean)

/** One day of the agenda and what is on it, all-day things first and then by time. */
internal data class AgendaDay<T>(val date: LocalDate, val items: List<T>)

internal data class Agenda<T>(
    /** What is still to come or still happening today. */
    val today: List<T>,
    /** The days after today that have anything on them, in order. Empty days are left out. */
    val upcoming: List<AgendaDay<T>>,
) {
    val isEmpty: Boolean get() = today.isEmpty() && upcoming.isEmpty()
}

/** How many days the panel looks ahead, today included. A week is what fits and what is asked. */
internal const val SIDE_AGENDA_DAYS = 7

/** A cap on the upcoming list, so a shared calendar full of rota entries cannot bury today. */
internal const val SIDE_AGENDA_LIMIT = 40

/** The dates an all-day span covers. One with no length still covers its start day. */
private fun EventSpan.allDayDates(): ClosedRange<LocalDate> {
    val first = start.toLocalDate()
    val last = end.toLocalDate().minusDays(1)
    return first..(if (last.isBefore(first)) first else last)
}

/**
 * Today and the days after it, from [items] as [span] places them.
 *
 * Today holds anything that covers today and has not ended by [now]: an all-day event on
 * today, a timed one later today, and one that started earlier and is still running,
 * including one that started yesterday. A timed event that has already finished is gone,
 * because an agenda is what is left, not what was.
 *
 * Each later day holds what starts on it. Something that runs across several days is listed
 * once, on the first day the agenda shows it, rather than repeated on every day it covers,
 * because the same line five times reads as five things.
 */
internal fun <T> agenda(
    items: List<T>,
    now: LocalDateTime,
    days: Int = SIDE_AGENDA_DAYS,
    limit: Int = SIDE_AGENDA_LIMIT,
    span: (T) -> EventSpan,
): Agenda<T> {
    val today = now.toLocalDate()
    val until = today.plusDays(days.coerceAtLeast(1).toLong())
    val order = compareBy<Pair<T, EventSpan>>({ !it.second.allDay }, { it.second.start })

    val placed = items.map { it to span(it) }
    // By position rather than by equality, so two identical entries on two calendars are
    // still two lines and neither is mistaken for the other.
    val onToday = placed.indices.filter { index ->
        val s = placed[index].second
        if (s.allDay) {
            today in s.allDayDates()
        } else {
            // Still on now, or starting later today. A zero-length event at this very
            // minute counts as still on, so a reminder at 10:00 is shown at 10:00.
            val notOver = s.end.isAfter(now) || (s.end == s.start && !s.start.isBefore(now))
            !s.start.toLocalDate().isAfter(today) && notOver
        }
    }.toSet()
    val todays = onToday.map { placed[it] }.sortedWith(order)

    val later = placed.filterIndexed { index, _ -> index !in onToday }.mapNotNull { (item, s) ->
        // The first day after today the agenda would show it on.
        val first = if (s.allDay) s.allDayDates().start else s.start.toLocalDate()
        first.takeIf { it.isAfter(today) && it.isBefore(until) }?.let { Triple(item, s, it) }
    }

    val upcoming = later.groupBy { it.third }.toSortedMap().map { (date, onDay) ->
        AgendaDay(date, onDay.map { it.first to it.second }.sortedWith(order).map { it.first })
    }
    return Agenda(todays.map { it.first }, capped(upcoming, limit))
}

/** The first [limit] items across the days, keeping whole days in order and cutting the last. */
private fun <T> capped(days: List<AgendaDay<T>>, limit: Int): List<AgendaDay<T>> {
    var left = limit.coerceAtLeast(0)
    val kept = ArrayList<AgendaDay<T>>()
    for (day in days) {
        if (left == 0) break
        val take = day.items.take(left)
        kept += AgendaDay(day.date, take)
        left -= take.size
    }
    return kept
}
