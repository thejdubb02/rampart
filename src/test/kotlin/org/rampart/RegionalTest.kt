package org.rampart

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a date is written, checked against a region the test builds itself.
 *
 * Reading the settings file would make the answer depend on this computer, and on whatever
 * was saved the last time someone opened the app. A sentence that only passes on one
 * machine is not a test.
 */
class RegionalTest {
    private val now = Instant.parse("2026-09-16T15:00:00Z")
    private val march = LocalDate.of(2026, 3, 5)

    @Test
    fun `today is a time, six days ago names the day, and a week ago is the date`() {
        // 16 September 2026 is a Wednesday. The clock is UTC so 15:00 stays 15:00.
        val region = region(zone = "UTC")
        assertEquals("15:00", formatList(region, now, now))
        // Earlier the same day is still today. A count of hours would call this yesterday.
        assertEquals("01:00", formatList(region, Instant.parse("2026-09-16T01:00:00Z"), Instant.parse("2026-09-16T23:00:00Z")))
        assertEquals("Thursday 15:00", formatList(region, Instant.parse("2026-09-10T15:00:00Z"), now))
        assertEquals("9 September 2026", formatList(region, Instant.parse("2026-09-09T15:00:00Z"), now))
        // A time that has not happened yet is a date, not a day name with no week around it.
        assertEquals("17 September 2026", formatList(region, Instant.parse("2026-09-17T15:00:00Z"), now))
        assertEquals("16 September 2026", formatList(region(zone = "UTC", listDate = "full"), now, now))
    }

    @Test
    fun `each date order puts the month, the day and the year in its own place`() {
        assertEquals("March 5, 2026", formatFull(region(order = "mdy"), march))
        assertEquals("Mar 5", formatShort(region(order = "mdy"), march))
        assertEquals("5 March 2026", formatFull(region(order = "dmy"), march))
        assertEquals("5 Mar", formatShort(region(order = "dmy"), march))
        assertEquals("2026-03-05", formatFull(region(order = "ymd"), march))
        assertEquals("03-05", formatShort(region(order = "ymd"), march))
    }

    @Test
    fun `the clock is 24-hour or 12-hour`() {
        val afternoon = LocalTime.of(15, 4)
        assertEquals("15:04", formatTime(region(clock = "24"), afternoon))
        // English, so the marker is "PM". A British locale would write "pm".
        val twelve = region(clock = "12", language = "en")
        assertEquals(Locale.ENGLISH, twelve.locale)
        assertEquals("3:04 PM", formatTime(twelve, afternoon))
    }

    @Test
    fun `a zone can move the same instant onto the day before`() {
        val at = Instant.parse("2026-09-16T02:00:00Z")
        val later = Instant.parse("2026-09-16T12:00:00Z")
        // 02:00 UTC is 03:00 that morning in London, and 19:00 the previous evening in Los Angeles.
        assertEquals("03:00", formatList(region(zone = "Europe/London"), at, later))
        assertEquals("Tuesday 19:00", formatList(region(zone = "America/Los_Angeles"), at, later))
        // A time that already carries its zone is left there. Moving it again names the wrong hour.
        val three = ZonedDateTime.of(2026, 9, 16, 3, 0, 0, 0, ZoneId.of("Europe/London"))
        assertEquals("03:00", formatTime(region(zone = "America/Los_Angeles"), three))
    }

    @Test
    fun `the week starts on Sunday, Monday or Saturday, and otherwise on the language's day`() {
        // Language is automatic here, so the computer's own locale is the one that can disagree.
        assertEquals(DayOfWeek.SUNDAY, region(language = "auto", week = "sunday", locale = Locale.UK).weekStart)
        assertEquals(DayOfWeek.MONDAY, region(language = "auto", week = "monday", locale = Locale.US).weekStart)
        assertEquals(DayOfWeek.SATURDAY, region(language = "auto", week = "saturday").weekStart)
        assertEquals(DayOfWeek.SUNDAY, region(language = "auto", week = "auto", locale = Locale.US).weekStart)
        assertEquals(DayOfWeek.MONDAY, region(language = "auto", week = "auto", locale = Locale.UK).weekStart)
        assertEquals(DayOfWeek.SUNDAY, weekDays(DayOfWeek.SUNDAY).first())
        assertEquals(DayOfWeek.SATURDAY, weekDays(DayOfWeek.SUNDAY).last())
        assertEquals(DayOfWeek.MONDAY, weekDays(DayOfWeek.MONDAY).first())
        assertEquals(DayOfWeek.SATURDAY, weekDays(DayOfWeek.SATURDAY).first())
        assertEquals(DayOfWeek.entries.toSet(), weekDays(DayOfWeek.SUNDAY).toSet())
    }

    @Test
    fun `an unknown choice falls back, and English stays English on a French computer`() {
        val unknown = regionFrom(
            language = "fr",
            listDate = "weird",
            dateOrder = "garbage",
            timeFormat = "25",
            timeZone = "Mars/Olympus",
            weekStart = "friday",
            systemLocale = Locale.UK,
            systemZone = ZoneId.of("Europe/London"),
        )
        assertEquals(Locale.UK, unknown.locale)
        assertEquals(false, unknown.listFull)
        assertEquals(DateOrder.DMY, unknown.order)
        assertEquals(false, unknown.hour12)
        assertEquals(ZoneId.of("Europe/London"), unknown.zone)
        assertEquals(DayOfWeek.MONDAY, unknown.weekStart)

        val english = region(
            language = "en",
            order = "auto",
            clock = "auto",
            zone = "auto",
            week = "auto",
            locale = Locale.FRANCE,
            systemZone = ZoneId.of("Europe/Paris"),
        )
        assertEquals(Locale.ENGLISH, english.locale)
        assertEquals(DateOrder.MDY, english.order)
        assertEquals(true, english.hour12)
        assertEquals(DayOfWeek.SUNDAY, english.weekStart)
        assertEquals(ZoneId.of("Europe/Paris"), english.zone)
        assertEquals("March 5, 2026", formatFull(english, march))
    }

    private fun region(
        language: String = "en",
        listDate: String = "smart",
        order: String = "dmy",
        clock: String = "24",
        zone: String = "UTC",
        week: String = "monday",
        locale: Locale = Locale.UK,
        systemZone: ZoneId = ZoneId.of("UTC"),
    ) = regionFrom(language, listDate, order, clock, zone, week, locale, systemZone)
}
