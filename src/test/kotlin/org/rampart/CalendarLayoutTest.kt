package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Which days each view covers, and how a crowded day is shared out. */
class CalendarLayoutTest {
    private val day = LocalDate.parse("2026-09-16")

    @Test
    fun `a month is six whole weeks from the Monday on or before the first`() {
        val (first, until) = visibleRange(CalendarView.MONTH, day)
        assertEquals(LocalDate.parse("2026-08-31"), first)
        assertEquals(DayOfWeek.MONDAY, first.dayOfWeek)
        assertEquals(42, until.toEpochDay() - first.toEpochDay())
    }

    @Test
    fun `week, day and agenda cover what they say`() {
        assertEquals(LocalDate.parse("2026-09-14") to LocalDate.parse("2026-09-21"), visibleRange(CalendarView.WEEK, day))
        assertEquals(day to day.plusDays(1), visibleRange(CalendarView.DAY, day))
        assertEquals(day to day.plusDays(30), visibleRange(CalendarView.AGENDA, day))
    }

    @Test
    fun `the arrows step by the view`() {
        assertEquals(LocalDate.parse("2026-10-01"), stepped(CalendarView.MONTH, LocalDate.parse("2026-09-30"), true))
        assertEquals(LocalDate.parse("2026-09-09"), stepped(CalendarView.WEEK, day, false))
        assertEquals(LocalDate.parse("2026-09-17"), stepped(CalendarView.DAY, day, true))
    }

    @Test
    fun `titles read as words`() {
        assertEquals("September 2026", rangeTitle(CalendarView.MONTH, day))
        // The JDK writes September short as "Sep" or "Sept" depending on its version, so only the shape is checked.
        val week = rangeTitle(CalendarView.WEEK, day)
        assertTrue(week.startsWith("14 Sep") && " to 20 Sep" in week && week.endsWith(" 2026"), week)
        assertEquals("Wednesday 16 September 2026", rangeTitle(CalendarView.DAY, day))
    }

    @Test
    fun `events that do not overlap each get the whole width`() {
        assertEquals(listOf(0 to 1, 0 to 1), sideBySide(listOf(540 to 600, 600 to 660)))
    }

    @Test
    fun `overlapping events share the width and reuse a free column`() {
        // 9 to 11, 9:30 to 10, 10 to 10:30: the third fits under the second.
        assertEquals(listOf(0 to 2, 1 to 2, 1 to 2), sideBySide(listOf(540 to 660, 570 to 600, 600 to 630)))
        // Three at once need three columns.
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3), sideBySide(listOf(540 to 600, 540 to 600, 550 to 590)))
    }

    @Test
    fun `editing every time from a later instance shifts the series rather than restarting it`() {
        val viewer = ZoneId.of("Europe/London")
        val e = assertNotNull(
            calendarEventOf(
                Json.parseToJsonElement(
                    """{"id":"e","start":"2026-09-01T09:00:00","timeZone":"Europe/London","duration":"PT1H",
                       "recurrenceRules":[{"frequency":"weekly"}]}""",
                ).jsonObject,
            ),
        )
        val fourth = occurrences(e, LocalDate.parse("2026-09-22"), LocalDate.parse("2026-09-23"), viewer).single()
        val moved = draftFrom(fourth, emptyList()).copy(
            start = LocalDateTime.parse("2026-09-22T10:00"),
            end = LocalDateTime.parse("2026-09-22T11:30"),
        )
        val series = seriesDraft(fourth, moved, viewer)
        assertEquals(LocalDateTime.parse("2026-09-01T10:00"), series.start)
        assertEquals(LocalDateTime.parse("2026-09-01T11:30"), series.end)
    }
}
