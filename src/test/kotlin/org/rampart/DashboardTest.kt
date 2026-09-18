package org.rampart

import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arithmetic is separated from the queries so it can be checked without a database,
 * and the queries are checked against a real one because SQL is where the mistakes are.
 */
class DashboardTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `a chart has a bar for every day, including the quiet ones`() {
        // A chart built from only the days that carried something hides the quiet ones and
        // puts the busy ones side by side, which makes a fortnight of silence look busy.
        val days = byDay(
            listOf("2026-09-18T09:00:00Z", "2026-09-18T11:00:00Z", "2026-09-15T08:00:00Z"),
            days = 5,
            today = LocalDate.of(2026, 9, 18),
            zone = utc,
        )
        assertEquals(5, days.size)
        assertEquals(listOf(0, 1, 0, 0, 2), days.map { it.count })
        assertEquals(LocalDate.of(2026, 9, 18), days.last().day, "oldest first, today last")
    }

    @Test
    fun `a timestamp is read whichever way the server wrote it`() {
        assertEquals(LocalDate.of(2026, 9, 18), dayOf("2026-09-18T09:00:00Z", utc))
        // IMAP hands back an offset rather than an instant.
        assertEquals(LocalDate.of(2026, 9, 18), dayOf("2026-09-18T11:00:00+02:00", utc))
        // A header nothing can read counts for nothing rather than taking the screen down.
        assertNull(dayOf("Thursday morning", utc))
        assertNull(instantOf(""))
    }

    @Test
    fun `the day is the reader's own, not the server's`() {
        // 23:30 UTC is already tomorrow in Auckland, and a dashboard that says otherwise is
        // describing somebody else's week.
        assertEquals(
            LocalDate.of(2026, 9, 19),
            dayOf("2026-09-18T23:30:00Z", ZoneId.of("Pacific/Auckland")),
        )
    }

    @Test
    fun `the middle reply time, not the average`() {
        // One forgotten thread drags a mean anywhere. The median is the answer to how fast
        // you reply.
        assertEquals(10L, medianMinutes(listOf(5L, 10L, 20_000L)))
        assertEquals(7L, medianMinutes(listOf(4L, 10L)))
        assertNull(medianMinutes(emptyList()))
    }

    @Test
    fun `a length of time is rounded to something worth saying`() {
        assertEquals("", spokenMinutes(null))
        assertEquals("under a minute", spokenMinutes(0))
        assertEquals("1 minute", spokenMinutes(1))
        assertEquals("44 minutes", spokenMinutes(44))
        assertEquals("about 3 hours", spokenMinutes(194))
        assertEquals("about 1 day", spokenMinutes(60 * 30))
        assertEquals("about 3 weeks", spokenMinutes(60 * 24 * 23))
    }

    @Test
    fun `a share of nothing is nothing`() {
        assertEquals(0, percent(0, 0))
        assertEquals(0, percent(5, 0), "five out of nothing must not divide by zero")
        assertEquals(25, percent(1, 4))
        assertEquals(100, percent(4, 4))
    }

    @Test
    fun `unread is bucketed by age and counted once`() {
        val now = Instant.parse("2026-09-18T12:00:00Z")
        val buckets = unreadByAge(
            listOf(
                "2026-09-18T09:00:00Z",
                "2026-09-16T09:00:00Z",
                "2026-09-01T09:00:00Z",
                "2024-01-01T09:00:00Z",
            ),
            now,
        )
        assertEquals(listOf("Today" to 1, "This week" to 1, "This month" to 1, "Older" to 1),
            buckets.map { it.label to it.count })
        // An empty bucket is a row saying nothing, and there are five of them.
        assertTrue(unreadByAge(emptyList(), now).isEmpty())
    }

    // ---- the queries, against a real database ------------------------------------------

    private fun <T> withStore(block: (Store) -> T): T {
        val path = Files.createTempDirectory("rampart-dashboard").resolve("mail.db")
        return try {
            Store.open(path, null).use(block)
        } finally {
            path.deleteIfExists()
        }
    }

    private fun mail(
        id: String,
        from: String,
        at: String,
        thread: String = id,
        seen: Boolean = true,
    ) = Summary(
        id = id,
        from = from,
        fromEmail = "${from.lowercase()}@example.org",
        subject = "About $id",
        receivedAt = at,
        preview = "",
        seen = seen,
        threadId = thread,
    )

    private val now = Instant.parse("2026-09-18T12:00:00Z")

    private fun loaded(store: Store) {
        store.put(
            "inbox",
            listOf(
                mail("a", "Dana", "2026-09-17T09:00:00Z", thread = "t1"),
                mail("b", "Dana", "2026-09-16T09:00:00Z", thread = "t2", seen = false),
                mail("c", "Alex", "2026-09-15T09:00:00Z", thread = "t3"),
                // Older than the window, so it counts towards nothing but the total kept.
                mail("d", "Alex", "2026-01-01T09:00:00Z", thread = "t4"),
            ),
        )
        store.put("sent", listOf(mail("s1", "Me", "2026-09-17T10:30:00Z", thread = "t1")))
        store.put("junk", listOf(mail("j1", "Spammer", "2026-09-17T08:00:00Z", thread = "t9")))
    }

    @Test
    fun `the figures come out of the local copy`() = withStore { store ->
        loaded(store)
        val stats = store.stats("inbox", listOf("sent"), listOf("junk"), setOf("me@example.org"), now = now, zone = utc)

        // Three in the inbox inside the window, plus the one in junk.
        assertEquals(4, stats.arrived)
        assertEquals(1, stats.junk)
        assertEquals(25, percent(stats.junk, stats.arrived))
        assertEquals(1, stats.sent.sumOf { it.count })
        assertEquals(6, stats.kept, "everything kept, window or not, junk and sent included")
        assertEquals(30, stats.received.size)
    }

    @Test
    fun `who writes most is counted by address, and named by their latest name`() = withStore { store ->
        loaded(store)
        // The same person, having changed how they sign themselves.
        store.put("inbox", listOf(mail("e", "Dana", "2026-09-18T09:00:00Z", thread = "t5")
            .copy(from = "Dana Whitfield", fromEmail = "dana@example.org")))

        val top = store.stats("inbox", listOf("sent"), listOf("junk"), emptySet(), now = now, zone = utc).topSenders
        assertEquals("dana@example.org", top.first().detail)
        assertEquals(3, top.first().count)
        assertEquals("Dana Whitfield", top.first().label, "the name they used most recently")
    }

    @Test
    fun `a conversation you answered is not waiting on you`() = withStore { store ->
        loaded(store)
        val stats = store.stats("inbox", listOf("sent"), listOf("junk"), setOf("me@example.org"), now = now, zone = utc)

        // t1 has a reply in Sent, so it is done. The rest are not.
        assertEquals(listOf("d", "c", "b"), stats.waiting.map { it.id })
        assertTrue(stats.waiting.first().receivedAt < stats.waiting.last().receivedAt, "oldest first")
    }

    @Test
    fun `your own message newest also counts as answered`() = withStore { store ->
        // Replying from a client that leaves no copy in Sent still ends the conversation.
        store.put("inbox", listOf(
            mail("x", "Dana", "2026-09-15T09:00:00Z", thread = "t8"),
            mail("y", "Me", "2026-09-16T09:00:00Z", thread = "t8"),
        ))
        val stats = store.stats("inbox", listOf("sent"), emptyList(), setOf("me@example.org"), now = now, zone = utc)
        assertTrue(stats.waiting.isEmpty(), "the last word was yours")
    }

    @Test
    fun `reply time is measured from the first message in the conversation`() = withStore { store ->
        loaded(store)
        val stats = store.stats("inbox", listOf("sent"), listOf("junk"), setOf("me@example.org"), now = now, zone = utc)

        // t1 arrived at 09:00 and was answered at 10:30.
        assertEquals(listOf(90L), stats.replyMinutes)
        assertEquals("about 1 hour", spokenMinutes(medianMinutes(stats.replyMinutes)))
    }

    @Test
    fun `a reply that predates the message it answers is a clock, not a negative wait`() = withStore { store ->
        store.put("inbox", listOf(mail("m", "Dana", "2026-09-17T10:00:00Z", thread = "t1")))
        store.put("sent", listOf(mail("s", "Me", "2026-09-17T09:00:00Z", thread = "t1")))

        val stats = store.stats("inbox", listOf("sent"), emptyList(), emptySet(), now = now, zone = utc)
        assertTrue(stats.replyMinutes.isEmpty(), "two servers disagreeing is not a reply time")
    }

    @Test
    fun `an account with no Sent folder still counts everything else`() = withStore { store ->
        loaded(store)
        val stats = store.stats("inbox", emptyList(), listOf("junk"), emptySet(), now = now, zone = utc)

        assertEquals(0, stats.sent.sumOf { it.count })
        assertTrue(stats.replyMinutes.isEmpty())
        // Nothing can be known to be answered, so everything in the inbox is waiting.
        assertEquals(4, stats.waiting.size)
        assertEquals(4, stats.arrived)
    }
}
