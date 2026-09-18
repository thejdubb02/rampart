package org.rampart

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Invitations arrive from Outlook, Google, Apple and a dozen CalDAV servers, and every one
 * of these cases is a shape one of them actually sends.
 */
class CalendarTest {

    private val london = ZoneId.of("Europe/London")

    /**
     * Built by joining rather than as an indented literal, because a line beginning with a
     * space is a folded continuation and an indented fixture folds the whole document into
     * one line. That is the parser being right, and it cost the first run of these tests.
     */
    private fun ics(body: String) = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "METHOD:REQUEST", "BEGIN:VEVENT", "UID:abc-123") +
            body.lines() +
            listOf("END:VEVENT", "END:VCALENDAR")
        ).joinToString("\r\n")

    @Test
    fun `an ordinary invitation reads`() {
        val invitation = invitationIn(
            ics(
                """
                SUMMARY:Quarterly review
                LOCATION:Room 4
                DTSTART;TZID=Europe/London:20260922T140000
                DTEND;TZID=Europe/London:20260922T150000
                ORGANIZER;CN=Dana Whitfield:mailto:Dana@Example.ORG
                ATTENDEE;CN=Justin;PARTSTAT=NEEDS-ACTION:mailto:justin@example.com
                """.trimIndent(),
            ),
        )!!

        assertEquals("REQUEST", invitation.method)
        assertEquals("abc-123", invitation.uid)
        assertEquals("Quarterly review", invitation.summary)
        assertEquals("Room 4", invitation.location)
        // Lowercased and without the scheme, because that is how an address is compared.
        assertEquals("dana@example.org", invitation.organiser?.email)
        assertEquals("Dana Whitfield", invitation.organiser?.name)
        assertEquals("NEEDS-ACTION", invitation.attendees.single().status)
        assertEquals(
            "Tuesday 22 September 2026, 14:00 to 15:00",
            whenText(invitation.starts, invitation.ends, london),
        )
    }

    @Test
    fun `a folded line is put back together`() {
        // 75 octets and the rest on the next line with a leading space. Without unfolding,
        // "example.com" parses as a property name and the guest disappears.
        val folded = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:folded-1
            SUMMARY:A meeting with a title long enough that it has to be wrapped onto
              a second line
            ATTENDEE;CN=Somebody With A Long Name;PARTSTAT=ACCEPTED:mailto:somebody@
             example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val invitation = invitationIn(folded)!!

        assertEquals("A meeting with a title long enough that it has to be wrapped onto a second line", invitation.summary)
        assertEquals("somebody@example.com", invitation.attendees.single().email)
    }

    @Test
    fun `a quoted parameter may contain a colon and a comma`() {
        val invitation = invitationIn(
            ics("""ORGANIZER;CN="Willhite, Justin: Director":mailto:justin@example.com"""),
        )!!
        assertEquals("Willhite, Justin: Director", invitation.organiser?.name)
        assertEquals("justin@example.com", invitation.organiser?.email)
    }

    @Test
    fun `a reminder inside the meeting does not become the meeting`() {
        // A VALARM has its own SUMMARY and DESCRIPTION and lives inside the VEVENT.
        val invitation = invitationIn(
            ics(
                """
                SUMMARY:Board meeting
                BEGIN:VALARM
                ACTION:DISPLAY
                SUMMARY:Reminder
                DESCRIPTION:Board meeting in 15 minutes
                TRIGGER:-PT15M
                END:VALARM
                DESCRIPTION:Agenda attached
                """.trimIndent(),
            ),
        )!!
        assertEquals("Board meeting", invitation.summary)
        assertEquals("Agenda attached", invitation.description)
    }

    @Test
    fun `only the first event is read`() {
        // A recurring meeting with one occurrence moved carries the series and then the
        // override. Showing the override would describe one Tuesday instead of the meeting.
        val two = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:series
            SUMMARY:Standup
            END:VEVENT
            BEGIN:VEVENT
            UID:series
            RECURRENCE-ID:20260922T090000Z
            SUMMARY:Standup, moved
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertEquals("Standup", invitationIn(two)?.summary)
    }

    @Test
    fun `escapes in text come out as the characters they stand for`() {
        val invitation = invitationIn(
            ics("""DESCRIPTION:Line one\nLine two\, with a comma\; and a semicolon\\done"""),
        )!!
        assertEquals("Line one\nLine two, with a comma; and a semicolon\\done", invitation.description)
    }

    @Test
    fun `the three forms of a start time`() {
        val utc = invitationIn(ics("DTSTART:20260922T130000Z"))!!
        assertEquals(
            ZonedDateTime.of(2026, 9, 22, 13, 0, 0, 0, ZoneOffset.UTC).toInstant(),
            utc.starts?.instant?.toInstant(),
        )

        val zoned = invitationIn(ics("DTSTART;TZID=Europe/London:20260922T140000"))!!
        // 14:00 in London in September is 13:00 UTC, so the two are the same moment.
        assertEquals(utc.starts?.instant?.toInstant(), zoned.starts?.instant?.toInstant())

        val allDay = invitationIn(ics("DTSTART;VALUE=DATE:20260922"))!!
        assertTrue(allDay.starts!!.allDay)
        assertEquals("20260922", allDay.starts!!.raw)
    }

    @Test
    fun `a timezone nobody has heard of does not lose the meeting`() {
        // Exchange writes names that are not in the IANA database. A meeting rendered an
        // hour out beats one that fails to render.
        val invitation = invitationIn(ics("DTSTART;TZID=GMT Standard Time:20260922T140000"))!!
        assertTrue(invitation.starts?.instant != null)
        assertEquals("20260922T140000", invitation.starts?.raw)
    }

    @Test
    fun `a duration stands in for a missing end`() {
        val half = invitationIn(ics("DTSTART:20260922T130000Z\nDURATION:PT30M"))!!
        assertEquals(
            "Tuesday 22 September 2026, 14:00 to 14:30",
            whenText(half.starts, half.ends, london),
        )
        // java.time has no idea what a week is, so PnW is expanded before it sees it.
        val week = invitationIn(ics("DTSTART:20260922T130000Z\nDURATION:P1W"))!!
        assertEquals(ZoneOffset.UTC, week.ends?.instant?.offset)
        assertEquals(29, week.ends?.instant?.dayOfMonth)
    }

    @Test
    fun `an all day event is not moved by the reader's timezone`() {
        // Converting a date is how a birthday lands on the day before for anyone west of
        // the sender.
        val invitation = invitationIn(ics("DTSTART;VALUE=DATE:20260922\nDTEND;VALUE=DATE:20260923"))!!
        assertEquals("Tuesday 22 September 2026", whenText(invitation.starts, invitation.ends, ZoneId.of("Pacific/Auckland")))
        assertEquals("Tuesday 22 September 2026", whenText(invitation.starts, invitation.ends, ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun `an all day event over several days says so`() {
        // DTEND is the day after the last one, which is right in the protocol and would
        // read as a day too many on screen.
        val invitation = invitationIn(ics("DTSTART;VALUE=DATE:20260922\nDTEND;VALUE=DATE:20260925"))!!
        assertEquals(
            "Tuesday 22 September 2026 to Thursday 24 September 2026",
            whenText(invitation.starts, invitation.ends, london),
        )
    }

    @Test
    fun `a meeting that crosses midnight names both days`() {
        val invitation = invitationIn(ics("DTSTART:20260922T220000Z\nDTEND:20260923T010000Z"))!!
        assertEquals(
            "Tuesday 22 September 2026, 23:00 to Wednesday 23 September 2026, 02:00",
            whenText(invitation.starts, invitation.ends, london),
        )
    }

    @Test
    fun `recurrence becomes a sentence`() {
        fun repeats(rule: String) = invitationIn(ics("RRULE:$rule"))!!.repeats

        assertEquals("Repeats every day", repeats("FREQ=DAILY"))
        assertEquals("Repeats every 2 weeks on Tuesday and Thursday", repeats("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH"))
        assertEquals("Repeats every month", repeats("FREQ=MONTHLY"))
        assertEquals("Repeats every year", repeats("FREQ=YEARLY"))
        assertEquals("Repeats every week on Monday, 10 times", repeats("FREQ=WEEKLY;BYDAY=MO;COUNT=10"))
        assertEquals("Repeats every week on Monday, until 1 December 2026", repeats("FREQ=WEEKLY;BYDAY=MO;UNTIL=20261201T000000Z"))
        // An ordinal on the day, as in the second Tuesday of the month.
        assertEquals("Repeats every month on Tuesday", repeats("FREQ=MONTHLY;BYDAY=2TU"))
        // Unreadable is still a repeating meeting, and saying nothing would be the worse lie.
        assertEquals("Repeats", repeats("FREQ=FORTNIGHTLY"))
    }

    @Test
    fun `no event at all is no invitation`() {
        assertNull(invitationIn("BEGIN:VCALENDAR\nMETHOD:PUBLISH\nEND:VCALENDAR"))
        assertNull(invitationIn(""))
        assertNull(invitationIn("this is not a calendar"))
    }

    @Test
    fun `a cancellation and a reply are told apart by the method`() {
        val cancelled = invitationIn(
            "BEGIN:VCALENDAR\nMETHOD:CANCEL\nBEGIN:VEVENT\nUID:x\nSUMMARY:Gone\nEND:VEVENT\nEND:VCALENDAR",
        )!!
        assertEquals("CANCEL", cancelled.method)

        val reply = invitationIn(
            "BEGIN:VCALENDAR\nMETHOD:REPLY\nBEGIN:VEVENT\nUID:x\n" +
                "ATTENDEE;PARTSTAT=DECLINED;CN=Dana:mailto:dana@example.org\nEND:VEVENT\nEND:VCALENDAR",
        )!!
        assertEquals("REPLY", reply.method)
        assertEquals("DECLINED", reply.attendees.single().status)
    }

    @Test
    fun `an answer carries the sender alone and the meeting's own identity`() {
        val invitation = invitationIn(
            ics(
                """
                SUMMARY:Quarterly review
                SEQUENCE:3
                DTSTART:20260922T130000Z
                ORGANIZER:mailto:dana@example.org
                ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:justin@example.com
                ATTENDEE;PARTSTAT=ACCEPTED:mailto:sam@example.org
                """.trimIndent(),
            ),
        )!!
        val reply = rsvpCalendar(
            invitation,
            Rsvp.ACCEPT,
            me = "justin@example.com",
            myName = "Justin Willhite",
            now = ZonedDateTime.of(2026, 9, 18, 12, 0, 0, 0, ZoneOffset.UTC),
        )

        assertTrue(reply.contains("METHOD:REPLY"))
        assertTrue(reply.contains("UID:abc-123"))
        // The sequence goes back as it arrived: the organiser matches on it, and a reply
        // to an older one is an answer to a meeting that has since been moved.
        assertTrue(reply.contains("SEQUENCE:3"))
        assertTrue(reply.contains("DTSTAMP:20260918T120000Z"))
        assertTrue(reply.contains("ATTENDEE;PARTSTAT=ACCEPTED;CN=Justin Willhite:mailto:justin@example.com"))
        // One attendee, never the guest list: nobody has standing to answer for anyone else.
        assertEquals(1, reply.lines().count { it.startsWith("ATTENDEE") })
        assertTrue(!reply.contains("sam@example.org"))
        assertTrue(reply.endsWith("END:VCALENDAR\r\n"), "iCalendar is CRLF, and a bare newline is rejected or truncated")
    }

    @Test
    fun `a long line in an answer is folded, counted in octets`() {
        val long = "SUMMARY:" + "a".repeat(200)
        val folded = foldIcs(long)
        folded.split("\r\n").forEachIndexed { at, line ->
            val octets = line.toByteArray(Charsets.UTF_8).size
            assertTrue(octets <= 75, "line $at is $octets octets")
            if (at > 0) assertTrue(line.startsWith(" "), "a continuation begins with one space")
        }
        assertEquals(long, folded.replace("\r\n ", ""))
    }

    @Test
    fun `an answer escapes a title that would otherwise break the file`() {
        val invitation = invitationIn(ics("SUMMARY:Review\\, budget\\; and plan"))!!
        assertEquals("Review, budget; and plan", invitation.summary)
        val reply = rsvpCalendar(invitation, Rsvp.DECLINE, me = "a@b.test")
        assertTrue(reply.contains("""SUMMARY:Review\, budget\; and plan"""))
    }

    @Test
    fun `the answer is addressed to the organiser and threaded onto the invitation`() {
        val invitation = invitationIn(
            ics("SUMMARY:Quarterly review\nORGANIZER:mailto:dana@example.org"),
        )!!
        val summary = Summary(
            id = "1", from = "Dana", fromEmail = "calendar-server@example.org",
            subject = "Invitation: Quarterly review", receivedAt = "2026-09-18T09:00:00Z",
            preview = "", seen = true,
        )
        val body = Body(html = null, text = null, messageId = listOf("<invite@example.org>"))
        val draft = rsvpDraft(invitation, Rsvp.TENTATIVE, summary, body, from = "justin@example.com")

        // The organiser, not whoever the calendar server sent it from.
        assertEquals("dana@example.org", draft.to)
        assertEquals("Tentatively accepted: Quarterly review", draft.subject)
        assertEquals("<invite@example.org>", draft.inReplyTo)
    }
}
