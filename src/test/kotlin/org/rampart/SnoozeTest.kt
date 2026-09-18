package org.rampart

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnoozeTest {

    private val london = ZoneId.of("Europe/London")

    /** A Wednesday, mid-afternoon. */
    private val wednesday = ZonedDateTime.of(2026, 9, 16, 14, 30, 0, 0, london)

    @Test
    fun `the due time survives a round trip through the keyword`() {
        val until = Instant.parse("2026-09-17T08:00:00Z")
        val keyword = snoozeKeyword(until)
        assertEquals(until, snoozedIn(listOf("\$seen", keyword, "billing"))?.until)
        assertEquals(keyword, snoozedIn(listOf(keyword))?.keyword)
    }

    @Test
    fun `an ordinary keyword is not a snooze`() {
        assertNull(snoozedIn(listOf("\$seen", "billing", "\$snoozeish")))
        // The prefix without a number is not one either, and must not throw.
        assertNull(snoozedIn(listOf("\$snooze-", "\$snooze-soon")))
        assertNull(snoozedIn(emptyList()))
    }

    @Test
    fun `due back is a question about now, not about the keyword`() {
        val until = Instant.parse("2026-09-16T09:00:00Z")
        val keywords = listOf(snoozeKeyword(until))
        assertTrue(dueBack(keywords, until.plusSeconds(1)))
        assertFalse(dueBack(keywords, until.minusSeconds(1)))
        // Nothing snoozed is never due back, which is what stops the sweep touching everything.
        assertFalse(dueBack(listOf("\$seen"), Instant.now()))
    }

    @Test
    fun `every choice lands in the future`() {
        // Half past eleven at night. "Later today" is three hours away and that is tomorrow,
        // which is correct: what must never happen is a time already past.
        val lateAtNight = wednesday.withHour(23).withMinute(30)
        SnoozeUntil.entries.forEach { choice ->
            listOf(wednesday, lateAtNight, wednesday.withHour(8)).forEach { now ->
                assertTrue(
                    choice.dueAt(now).isAfter(now),
                    "${choice.label} from ${now.toLocalTime()} landed at ${choice.dueAt(now)}",
                )
            }
        }
    }

    @Test
    fun `tomorrow morning is nine, not this time tomorrow`() {
        val due = SnoozeUntil.TOMORROW.dueAt(wednesday)
        assertEquals(9, due.hour)
        assertEquals(0, due.minute)
        assertEquals(wednesday.toLocalDate().plusDays(1), due.toLocalDate())
    }

    @Test
    fun `the weekend is the Saturday coming`() {
        val due = SnoozeUntil.WEEKEND.dueAt(wednesday)
        assertEquals(DayOfWeek.SATURDAY, due.dayOfWeek)
        assertEquals(9, due.hour)
        assertEquals(wednesday.toLocalDate().plusDays(3), due.toLocalDate())
    }

    @Test
    fun `asking on a Saturday means the one coming, not the hour you are in`() {
        val saturday = wednesday.with(DayOfWeek.SATURDAY).withHour(10)
        val due = SnoozeUntil.WEEKEND.dueAt(saturday)
        assertEquals(DayOfWeek.SATURDAY, due.dayOfWeek)
        assertEquals(saturday.toLocalDate().plusDays(7), due.toLocalDate())
    }

    @Test
    fun `next week is Monday morning`() {
        val due = SnoozeUntil.NEXT_WEEK.dueAt(wednesday)
        assertEquals(DayOfWeek.MONDAY, due.dayOfWeek)
        assertEquals(9, due.hour)
        assertTrue(due.isAfter(wednesday))
    }

    @Test
    fun `what it says on the row changes with how far off it is`() {
        val zone = wednesday.zone
        fun text(at: ZonedDateTime) = snoozeText(at.toInstant(), wednesday)

        assertEquals("back at 17:30", text(wednesday.plusHours(3)))
        assertEquals("back tomorrow at 09:00", text(wednesday.plusDays(1).withHour(9).withMinute(0)))
        assertEquals("back Monday at 09:00", text(wednesday.with(DayOfWeek.MONDAY).plusWeeks(1).withHour(9).withMinute(0)))
        assertEquals("back on 30 September", text(wednesday.withDayOfMonth(30).withHour(9)))
        assertEquals(zone, london)
    }

    @Test
    fun `the keyword is one the tag list refuses to show`() {
        // It is machinery, not a tag somebody made, and a "$snooze-1789..." row in the
        // sidebar would be the feature leaking into the interface.
        assertTrue(tagRows(listOf(snoozeKeyword(Instant.now()), "billing"), emptyMap()).none { it.keyword.contains("snooze") })
    }
}
