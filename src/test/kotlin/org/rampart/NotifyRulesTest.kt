package org.rampart

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three decisions behind a new-mail notification: what counts as one burst, what is
 * still worth saying by the time it is ready, and whether now is a time to say anything.
 */
class NotifyRulesTest {
    private fun mail(id: String, from: String = "Dana", subject: String = "Hello", at: String = "2026-09-29T09:00:0${id.last()}Z", seen: Boolean = false) =
        Summary(id, from, "${from.lowercase()}@example.org", subject, at, "", seen)

    private fun arrival(id: String, from: String = "Dana", account: String = "a1", subject: String = "Hello") =
        Arrival(account, mail(id, from, subject))

    // Bursts

    @Test
    fun `arrivals close together are one burst, released once things go quiet`() {
        val bursts = Bursts(settle = 4_000, longest = 20_000)
        bursts.add(listOf(arrival("1")), now = 0)
        bursts.add(listOf(arrival("2"), arrival("3")), now = 2_000)
        assertNull(bursts.due(5_000), "still inside the settle time after the last arrival")
        assertEquals(listOf("1", "2", "3"), bursts.due(6_000)!!.map { it.summary.id })
        assertNull(bursts.due(60_000), "a released burst is forgotten")
    }

    @Test
    fun `a steady trickle is released after the longest wait rather than held forever`() {
        val bursts = Bursts(settle = 4_000, longest = 20_000)
        (0..6).forEach { bursts.add(listOf(arrival("$it")), now = it * 3_000L) }
        assertEquals(7, bursts.due(20_000)!!.size)
    }

    @Test
    fun `the same message reported by a push and a poll is counted once`() {
        val bursts = Bursts()
        bursts.add(listOf(arrival("1")), now = 0)
        bursts.add(listOf(arrival("1")), now = 1_000)
        bursts.add(listOf(arrival("1", account = "a2")), now = 1_000)
        assertEquals(2, bursts.due(10_000)!!.size, "the same id in another account is another message")
    }

    // Words

    @Test
    fun `one message says who and what, and opens that message`() {
        val notice = burstNotice(listOf(arrival("1", "Dana", subject = "Lunch?")))!!
        assertEquals("Dana", notice.title)
        assertEquals("Lunch?", notice.body)
        assertEquals("1", notice.opens!!.summary.id)
        assertEquals("(no subject)", burstNotice(listOf(arrival("1", subject = " ")))!!.body)
    }

    @Test
    fun `several messages say how many and from how many people`() {
        val notice = burstNotice(listOf(arrival("1", "Dana"), arrival("2", "Alex"), arrival("3", "dana")))!!
        assertEquals("3 new messages from 2 people", notice.title)
        assertEquals("Dana and Alex", notice.body)
        assertEquals("3", notice.opens!!.summary.id, "a click opens the newest")
    }

    @Test
    fun `several from one person name them and the latest subject`() {
        val notice = burstNotice(listOf(arrival("1", "Dana", subject = "First"), arrival("2", "Dana", subject = "Second")))!!
        assertEquals("2 new messages from Dana", notice.title)
        assertEquals("Second", notice.body)
    }

    @Test
    fun `many people are counted rather than listed`() {
        val notice = burstNotice(listOf("Dana", "Alex", "Sam", "Jo", "Kim").mapIndexed { i, who -> arrival("$i", who) })!!
        assertEquals("5 new messages from 5 people", notice.title)
        assertEquals("Dana, Alex and 3 others", notice.body)
        assertNull(burstNotice(emptyList()))
    }

    // Suppression

    @Test
    fun `mail filed away or read elsewhere before the burst is ready is not announced`() {
        val burst = listOf(arrival("1"), arrival("2"), arrival("3"))
        val inboxNow = mapOf("a1" to listOf(mail("1"), mail("3", seen = true)))
        // 2 was filed out of the inbox by a filter; 3 was read in another client.
        assertEquals(listOf("1"), stillNew(burst, inboxNow, silenced = emptySet()).map { it.summary.id })
    }

    @Test
    fun `an inbox that could not be read keeps its arrivals`() {
        val burst = listOf(arrival("1"))
        assertEquals(1, stillNew(burst, mapOf("a1" to null), emptySet()).size)
    }

    @Test
    fun `a silenced account says nothing, and the others still do`() {
        val burst = listOf(arrival("1", account = "work"), arrival("2", account = "home"))
        val inboxNow = mapOf("work" to listOf(mail("1")), "home" to listOf(mail("2")))
        assertEquals(listOf("home"), stillNew(burst, inboxNow, silenced = setOf("work")).map { it.account })
    }

    @Test
    fun `nothing left after suppression is no notification at all`() {
        val burst = listOf(arrival("1"))
        assertNull(noticeFor(burst, mapOf("a1" to emptyList()), true, emptySet(), null, LocalTime.NOON))
    }

    // Quiet hours

    @Test
    fun `quiet hours across midnight`() {
        val night = QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0))
        assertTrue(night.covers(LocalTime.of(23, 30)))
        assertTrue(night.covers(LocalTime.of(22, 0)), "the start is inside")
        assertTrue(night.covers(LocalTime.of(3, 0)))
        assertTrue(!night.covers(LocalTime.of(7, 0)), "the end is outside")
        assertTrue(!night.covers(LocalTime.NOON))
    }

    @Test
    fun `quiet hours inside one day, and equal ends mean none`() {
        val lunch = QuietHours(LocalTime.of(12, 0), LocalTime.of(13, 0))
        assertTrue(lunch.covers(LocalTime.of(12, 30)))
        assertTrue(!lunch.covers(LocalTime.of(13, 30)))
        assertTrue(!QuietHours(LocalTime.NOON, LocalTime.NOON).covers(LocalTime.NOON))
    }

    @Test
    fun `quiet hours are stored as two times and anything else reads as off`() {
        assertEquals(QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0)), QuietHours.parse("22:00-07:00"))
        assertEquals("22:00-07:30", QuietHours.parse(" 22:00 - 07:30 ")!!.encoded())
        assertNull(QuietHours.parse(""))
        assertNull(QuietHours.parse(null))
        assertNull(QuietHours.parse("10pm-7am"))
        assertNull(QuietHours.parse("22:00"))
    }

    @Test
    fun `during quiet hours or with the switch off there is no notification, otherwise there is`() {
        val burst = listOf(arrival("1"))
        val inbox = mapOf("a1" to listOf(mail("1")))
        val night = QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0))
        assertNull(noticeFor(burst, inbox, true, emptySet(), night, LocalTime.of(23, 0)))
        assertNull(noticeFor(burst, inbox, false, emptySet(), null, LocalTime.NOON))
        assertEquals("Dana", noticeFor(burst, inbox, true, emptySet(), night, LocalTime.NOON)!!.title)
    }

    // The tray route's click

    @Test
    fun `a tray click soon after a notification opens its message, and only once`() {
        var now = 0L
        val sent = mutableListOf<String>()
        val shell = TrayShell(send = { title, _ -> sent += title }, window = 30_000, clock = { now })
        var opened = 0
        shell.notify("Dana", "Hello") { opened++ }
        assertEquals(listOf("Dana"), sent)
        now = 5_000
        shell.trayActivated()
        shell.trayActivated()
        assertEquals(1, opened, "a second activation is the person opening the window, not the message again")
    }

    @Test
    fun `a tray click long after the notification only shows the window`() {
        var now = 0L
        val shell = TrayShell(send = { _, _ -> }, window = 30_000, clock = { now })
        var opened = false
        shell.notify("Dana", "Hello") { opened = true }
        now = 60_000
        shell.trayActivated()
        assertTrue(!opened)
    }

    @Test
    fun `the badge text caps at two digits`() {
        assertEquals("7", badgeText(7))
        assertEquals("99", badgeText(99))
        assertEquals("99+", badgeText(100))
    }

    /*
     * Whatever this machine has or lacks (no libnotify, no notification service, no
     * taskbar, no display), choosing the shell and using it must never throw: a missing
     * library costs the feature, not the start of the app.
     */
    @Test
    fun `the platform shell starts and runs on a bare machine`() {
        val shell = desktopShell(tray = null)
        shell.unread(3, null)
        shell.unread(0, null)
        shell.trayActivated()
    }
}
