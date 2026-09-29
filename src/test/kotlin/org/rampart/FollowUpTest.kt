package org.rampart

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowUpTest {

    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun message(
        id: String,
        from: String,
        at: String,
        thread: String = "t1",
        keywords: Set<String> = emptySet(),
    ) = Summary(
        id = id, from = from, fromEmail = from, subject = "Quote", receivedAt = at, preview = "",
        seen = true, keywords = keywords, threadId = thread,
    )

    // ---- the keywords --------------------------------------------------------------

    @Test
    fun `the due moment survives a round trip through the keyword`() {
        val due = Instant.parse("2026-10-03T09:00:00Z")
        val keyword = followUpDueKeyword(due)
        assertEquals("\$followup-20261003t0900z", keyword)
        assertEquals(due, followUpDueOf(keyword))
        val flag = assertNotNull(followUpIn(setOf("\$seen", FOLLOW_UP, keyword)))
        assertEquals(due, flag.due)
        assertFalse(flag.noReply)
        assertEquals(listOf(keyword), flag.dateKeywords)
    }

    @Test
    fun `what is written is lowercase and inside the IMAP atom rules`() {
        val keyword = followUpDueKeyword(Instant.parse("2026-12-31T23:59:59Z"))
        assertEquals(keyword.lowercase(), keyword)
        for (k in listOf(FOLLOW_UP, FOLLOW_UP_NO_REPLY, keyword)) {
            assertTrue(k.length <= 128, k)
            // RFC 9051 atom: printable ASCII with none of ( ) { SP % * " \ ]
            assertTrue(k.all { it.code in 0x21..0x7E && it !in "(){%*\"\\]" }, k)
        }
        // Seconds are dropped, because every distinct keyword costs one of the account's slots.
        assertEquals("\$followup-20261231t2359z", keyword)
    }

    @Test
    fun `keywords are read ignoring case, as another client may have written them`() {
        val flag = assertNotNull(followUpIn(setOf("\$FollowUp", "\$FOLLOWUP-20261003T0900Z", "\$FollowUp-NoReply")))
        assertEquals(Instant.parse("2026-10-03T09:00:00Z"), flag.due)
        assertTrue(flag.noReply)
    }

    @Test
    fun `hostile or malformed date keywords are treated as absent`() {
        val broken = setOf(
            FOLLOW_UP,
            "\$followup-20261332t0900z",   // month 13
            "\$followup-20260230t0900z",   // 30 February
            "\$followup-20261003t2561z",   // 25:61
            "\$followup-2026103t0900z",    // a digit short
            "\$followup-20261003t0900",    // no zone
            "\$followup-20261003t0900z; DROP",
            "\$followup-١٢٣٤١٠٠٣t0900z", // Arabic-Indic digits
            "\$followup-",
            "\$followup-noreplyx",
        )
        val flag = assertNotNull(followUpIn(broken))
        assertNull(flag.due)
        assertTrue(flag.dateKeywords.isEmpty())
        assertFalse(flag.noReply)
        assertFalse(followUpDue(broken, Instant.parse("2100-01-01T00:00:00Z")))
    }

    @Test
    fun `a date with no flag is not a flag`() {
        assertNull(followUpIn(setOf("\$followup-20261003t0900z")))
        assertNull(followUpIn(setOf("\$followupx", "\$followed", "followup")))
        assertNull(followUpIn(emptySet()))
    }

    @Test
    fun `two dates left by two devices read as the earlier one`() {
        val flag = assertNotNull(followUpIn(setOf(FOLLOW_UP, "\$followup-20261005t0900z", "\$followup-20261003t0900z")))
        assertEquals(Instant.parse("2026-10-03T09:00:00Z"), flag.due)
        assertEquals(2, flag.dateKeywords.size)
    }

    @Test
    fun `setting a new date removes the old date in the same change`() {
        val old = setOf("\$seen", FOLLOW_UP, "\$followup-20261003t0900z")
        val change = setFollowUp(old, Instant.parse("2026-10-10T08:00:00Z"), noReply = false)
        assertEquals(setOf(FOLLOW_UP, "\$followup-20261010t0800z"), change.add)
        assertEquals(setOf("\$followup-20261003t0900z"), change.remove)
        val after = change.appliedTo(old)
        assertEquals(setOf("\$seen", FOLLOW_UP, "\$followup-20261010t0800z"), after)
    }

    @Test
    fun `choosing again without no reply takes no reply off, and mixed case copies are replaced`() {
        val old = setOf("\$FollowUp", "\$followup-20261003t0900z", FOLLOW_UP_NO_REPLY)
        val change = setFollowUp(old, Instant.parse("2026-10-03T09:00:00Z"), noReply = false)
        assertTrue(FOLLOW_UP_NO_REPLY in change.remove)
        assertTrue("\$FollowUp" in change.remove)
        assertFalse("\$followup-20261003t0900z" in change.remove)
        assertEquals(setOf(FOLLOW_UP, "\$followup-20261003t0900z"), change.appliedTo(old))
    }

    @Test
    fun `no reply is added when asked for`() {
        val change = setFollowUp(emptySet(), Instant.parse("2026-10-03T09:00:00Z"), noReply = true)
        assertEquals(setOf(FOLLOW_UP, "\$followup-20261003t0900z", FOLLOW_UP_NO_REPLY), change.add)
        assertTrue(change.remove.isEmpty())
    }

    @Test
    fun `clearing takes every follow-up keyword and nothing else`() {
        val old = setOf("\$seen", "invoices", "\$FOLLOWUP", "\$followup-20261003t0900z", "\$followup-junk", FOLLOW_UP_NO_REPLY)
        val change = clearFollowUp(old)
        assertTrue(change.add.isEmpty())
        assertEquals(setOf("\$seen", "invoices"), change.appliedTo(old))
    }

    @Test
    fun `follow-up keywords are machinery, not tags`() {
        assertTrue(tagsOf(setOf(FOLLOW_UP, "\$followup-20261003t0900z", FOLLOW_UP_NO_REPLY)).isEmpty())
    }

    // ---- when it is due ------------------------------------------------------------

    /** A Wednesday, mid-afternoon, the same moment the snooze tests use. */
    private val wednesday = ZonedDateTime.of(2026, 9, 16, 14, 30, 0, 0, london)

    @Test
    fun `the named times land at the snooze's morning`() {
        val tomorrow = FollowUpWhen.TOMORROW.dueAt(wednesday)
        assertEquals(ZonedDateTime.of(2026, 9, 17, 9, 0, 0, 0, london), tomorrow)
        assertEquals(SnoozeUntil.TOMORROW.dueAt(wednesday), tomorrow)

        val three = FollowUpWhen.IN_THREE_DAYS.dueAt(wednesday)
        assertEquals(ZonedDateTime.of(2026, 9, 19, 9, 0, 0, 0, london), three)

        val nextWeek = FollowUpWhen.NEXT_WEEK.dueAt(wednesday)
        assertEquals(DayOfWeek.MONDAY, nextWeek.dayOfWeek)
        assertEquals(ZonedDateTime.of(2026, 9, 21, 9, 0, 0, 0, london), nextWeek)
        assertEquals(SnoozeUntil.NEXT_WEEK.dueAt(wednesday), nextWeek)
    }

    @Test
    fun `nine in the morning stays nine across the clocks going back`() {
        // British Summer Time ends on Sunday 25 October 2026.
        val friday = ZonedDateTime.of(2026, 10, 23, 14, 0, 0, 0, london)
        val three = FollowUpWhen.IN_THREE_DAYS.dueAt(friday)
        assertEquals(LocalTime.of(9, 0), three.toLocalTime())
        assertEquals(Instant.parse("2026-10-26T09:00:00Z"), three.toInstant())

        val tomorrowBefore = FollowUpWhen.TOMORROW.dueAt(ZonedDateTime.of(2026, 10, 24, 20, 0, 0, 0, london))
        assertEquals(Instant.parse("2026-10-25T09:00:00Z"), tomorrowBefore.toInstant())

        val nextWeek = FollowUpWhen.NEXT_WEEK.dueAt(ZonedDateTime.of(2026, 10, 22, 10, 0, 0, 0, london))
        assertEquals(Instant.parse("2026-10-26T09:00:00Z"), nextWeek.toInstant())
    }

    @Test
    fun `and across the clocks going forward in New York`() {
        // Daylight saving starts in the United States on Sunday 8 March 2026.
        val saturday = ZonedDateTime.of(2026, 3, 7, 22, 0, 0, 0, newYork)
        val tomorrow = FollowUpWhen.TOMORROW.dueAt(saturday)
        assertEquals(LocalTime.of(9, 0), tomorrow.toLocalTime())
        assertEquals(Instant.parse("2026-03-08T13:00:00Z"), tomorrow.toInstant())
    }

    @Test
    fun `a flag set in one zone is due at the same moment read in another`() {
        val due = FollowUpWhen.TOMORROW.dueAt(wednesday).toInstant()
        val keywords = setFollowUp(emptySet(), due, noReply = false).add
        val readInTokyo = followUpIn(keywords)!!.due!!
        assertEquals(due, readInTokyo)
        assertEquals(ZonedDateTime.of(2026, 9, 17, 17, 0, 0, 0, tokyo), readInTokyo.atZone(tokyo))
        assertFalse(followUpDue(keywords, due.minusSeconds(60)))
        assertTrue(followUpDue(keywords, due))
    }

    @Test
    fun `the due list holds only dated flags that have come, earliest first`() {
        val now = Instant.parse("2026-10-05T12:00:00Z")
        val late = message("a", "x@example.org", "2026-10-01T10:00:00Z", keywords = setOf(FOLLOW_UP, "\$followup-20261004t0900z"))
        val early = message("b", "x@example.org", "2026-10-02T10:00:00Z", keywords = setOf(FOLLOW_UP, "\$followup-20261003t0900z"))
        val future = message("c", "x@example.org", "2026-10-02T10:00:00Z", keywords = setOf(FOLLOW_UP, "\$followup-20261103t0900z"))
        val undated = message("d", "x@example.org", "2026-10-02T10:00:00Z", keywords = setOf(FOLLOW_UP))
        assertEquals(listOf("b", "a"), dueFollowUps(listOf(late, early, future, undated), now).map { it.id })
    }

    // ---- only if no reply ----------------------------------------------------------

    private val me = setOf("me@example.org", "alias@example.org")
    private val sent = message("s", "me@example.org", "2026-10-01T10:00:00Z", keywords = setOf(FOLLOW_UP, FOLLOW_UP_NO_REPLY))

    @Test
    fun `a reply from someone else after the send cancels it`() {
        val reply = message("r", "them@example.net", "2026-10-02T08:00:00Z")
        assertTrue(answeredBySomeoneElse(sent, listOf(sent, reply), me))
    }

    @Test
    fun `the person's own later message does not, under any of their addresses`() {
        val nudge = message("n", "Alias@Example.org", "2026-10-03T08:00:00Z")
        assertFalse(answeredBySomeoneElse(sent, listOf(sent, nudge), me))
    }

    @Test
    fun `an earlier message from them does not`() {
        val before = message("b", "them@example.net", "2026-09-30T08:00:00Z")
        assertFalse(answeredBySomeoneElse(sent, listOf(before, sent), me))
    }

    @Test
    fun `a message in another conversation does not`() {
        val elsewhere = message("e", "them@example.net", "2026-10-02T08:00:00Z", thread = "t2")
        assertFalse(answeredBySomeoneElse(sent, listOf(sent, elsewhere), me))
    }

    @Test
    fun `a message with no thread cannot be answered`() {
        val loose = sent.copy(threadId = "")
        val reply = message("r", "them@example.net", "2026-10-02T08:00:00Z", thread = "")
        assertFalse(answeredBySomeoneElse(loose, listOf(loose, reply), me))
    }

    @Test
    fun `sent by me is decided by the person's own addresses`() {
        assertTrue(sentByMe(sent, me))
        assertFalse(sentByMe(message("x", "them@example.net", "2026-10-02T08:00:00Z"), me))
    }

    // ---- announcing once ------------------------------------------------------------

    @Test
    fun `each due flag is announced once, across restarts`() {
        val a = message("a", "x@example.org", "2026-10-01T10:00:00Z", keywords = setOf(FOLLOW_UP, "\$followup-20261003t0900z"))
        val b = message("b", "x@example.org", "2026-10-01T10:00:00Z", keywords = setOf(FOLLOW_UP, "\$followup-20261004t0900z"))

        val (first, remembered) = toAnnounce(listOf(a), listOf(a, b), emptySet())
        assertEquals(listOf("a"), first.map { it.id })

        // Rampart restarts: what was remembered comes back from the file, and nothing repeats.
        val (second, kept) = toAnnounce(listOf(a), listOf(a, b), remembered)
        assertTrue(second.isEmpty())
        assertEquals(remembered, kept)

        // b comes due too, and only b is announced.
        val (third, _) = toAnnounce(listOf(a, b), listOf(a, b), kept)
        assertEquals(listOf("b"), third.map { it.id })
    }

    @Test
    fun `a new date is a new reminder, and a cleared flag is forgotten`() {
        val a = message("a", "x@example.org", "2026-10-01T10:00:00Z", keywords = setOf(FOLLOW_UP, "\$followup-20261003t0900z"))
        val (_, remembered) = toAnnounce(listOf(a), listOf(a), emptySet())

        val moved = a.copy(keywords = setOf(FOLLOW_UP, "\$followup-20261010t0900z"))
        val (again, _) = toAnnounce(listOf(moved), listOf(moved), remembered)
        assertEquals(listOf("a"), again.map { it.id })

        val (_, afterClear) = toAnnounce(emptyList(), emptyList(), remembered)
        assertTrue(afterClear.isEmpty())
    }

    @Test
    fun `an undated flag is never announced`() {
        val undated = message("u", "x@example.org", "2026-10-01T10:00:00Z", keywords = setOf(FOLLOW_UP))
        assertTrue(toAnnounce(listOf(undated), listOf(undated), emptySet()).first.isEmpty())
    }

    @Test
    fun `the wording follows the snooze's`() {
        val now = ZonedDateTime.of(2026, 9, 16, 14, 30, 0, 0, london)
        assertEquals("Follow up tomorrow at 09:00", followUpText(FollowUpWhen.TOMORROW.dueAt(now).toInstant(), now))
        assertEquals("Follow up now", followUpText(now.minusMinutes(1).toInstant(), now))
        assertEquals("Follow up, no date", followUpText(null, now))
    }
}
