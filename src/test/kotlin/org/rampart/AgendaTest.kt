package org.rampart

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The calendar panel beside the mail: what counts as today, and what comes after it. */
class AgendaTest {

    private data class Item(val name: String, val span: EventSpan)

    private val now = LocalDateTime.of(2026, 9, 28, 11, 0)

    private fun timed(name: String, start: LocalDateTime, end: LocalDateTime) = Item(name, EventSpan(start, end, false))

    /** All day from [first] to [last] inclusive, carried with an exclusive end the way Occurrence carries it. */
    private fun allDay(name: String, first: LocalDate, last: LocalDate = first) =
        Item(name, EventSpan(first.atStartOfDay(), last.plusDays(1).atStartOfDay(), true))

    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 9, day, hour, minute)

    private fun of(items: List<Item>, days: Int = SIDE_AGENDA_DAYS, limit: Int = SIDE_AGENDA_LIMIT) =
        agenda(items, now, days, limit) { it.span }

    @Test
    fun `nothing on is an empty agenda`() {
        assertTrue(of(emptyList()).isEmpty)
    }

    @Test
    fun `a meeting that has already ended today is gone`() {
        val result = of(listOf(timed("standup", at(28, 9), at(28, 9, 30))))
        assertTrue(result.today.isEmpty())
        assertTrue(result.upcoming.isEmpty())
    }

    @Test
    fun `today holds what is on now and what is later, all-day first then by time`() {
        val result = of(
            listOf(
                timed("lunch", at(28, 13), at(28, 14)),
                timed("running", at(28, 10, 30), at(28, 11, 30)),
                allDay("birthday", LocalDate.of(2026, 9, 28)),
            ),
        )
        assertEquals(listOf("birthday", "running", "lunch"), result.today.map { it.name })
    }

    @Test
    fun `something that started yesterday and is still on is today's`() {
        val result = of(listOf(timed("overnight", at(27, 22), at(28, 12))))
        assertEquals(listOf("overnight"), result.today.map { it.name })
        assertTrue(result.upcoming.isEmpty(), "and it is not listed a second time")
    }

    @Test
    fun `an all-day event that ended yesterday is not today's`() {
        // Ends at midnight this morning, exclusively, so it was yesterday only.
        val result = of(listOf(allDay("yesterday", LocalDate.of(2026, 9, 27))))
        assertTrue(result.isEmpty)
    }

    @Test
    fun `a reminder at this very minute is still shown`() {
        val result = of(listOf(timed("now", now, now)))
        assertEquals(listOf("now"), result.today.map { it.name })
    }

    @Test
    fun `later days are grouped by day, in order, with empty days left out`() {
        val result = of(
            listOf(
                timed("dentist", at(30, 15), at(30, 16)),
                timed("call", at(29, 9), at(29, 10)),
                allDay("holiday", LocalDate.of(2026, 9, 30)),
            ),
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30)), result.upcoming.map { it.date })
        assertEquals(listOf("holiday", "dentist"), result.upcoming[1].items.map { it.name })
    }

    @Test
    fun `a trip across several days is listed once, on the day it starts`() {
        val result = of(listOf(allDay("trip", LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2))))
        assertEquals(1, result.upcoming.size)
        assertEquals(LocalDate.of(2026, 9, 29), result.upcoming.single().date)
    }

    @Test
    fun `a trip that started before today shows under today, not again later`() {
        val result = of(listOf(allDay("trip", LocalDate.of(2026, 9, 26), LocalDate.of(2026, 10, 1))))
        assertEquals(listOf("trip"), result.today.map { it.name })
        assertTrue(result.upcoming.isEmpty())
    }

    @Test
    fun `nothing past the window is shown`() {
        // Seven days is today and the six after it, so the fifth of October is out.
        val result = of(
            listOf(
                timed("in", at(28, 12).plusDays(6), at(28, 13).plusDays(6)),
                timed("out", at(28, 12).plusDays(7), at(28, 13).plusDays(7)),
            ),
        )
        assertEquals(listOf("in"), result.upcoming.flatMap { it.items }.map { it.name })
    }

    @Test
    fun `the upcoming list is capped and cuts inside the last day`() {
        val many = (0 until 5).map { timed("tomorrow $it", at(29, 9 + it), at(29, 10 + it)) } +
            (0 until 5).map { timed("after $it", at(30, 9 + it), at(30, 10 + it)) }
        val result = of(many, limit = 7)
        assertEquals(7, result.upcoming.sumOf { it.items.size })
        assertEquals(listOf(5, 2), result.upcoming.map { it.items.size })
    }

    @Test
    fun `two identical entries are still two lines`() {
        val same = timed("sync", at(28, 15), at(28, 16))
        assertEquals(2, of(listOf(same, same)).today.size)
    }
}
