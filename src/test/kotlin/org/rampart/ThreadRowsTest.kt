package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What a collapsed conversation row says, and conversations joined and split by hand.
 */
class ThreadRowsTest {
    private fun mail(
        id: String,
        at: String,
        from: String = "Dana",
        thread: String = "t1",
        seen: Boolean = true,
        flagged: Boolean = false,
        keywords: Set<String> = emptySet(),
        size: Int = 1,
    ) = Summary(
        id, from, "${from.lowercase()}@example.org", "About $id", at, "Preview of $id", seen,
        flagged = flagged, keywords = keywords, threadId = thread, threadSize = size,
    )

    private val draft = setOf("\$draft")

    // ---- the newest message ---------------------------------------------------------

    @Test
    fun `the newest message is the one the row describes`() {
        val thread = listOf(mail("1", "2026-09-20T09:00:00Z"), mail("2", "2026-09-22T09:00:00Z"), mail("3", "2026-09-21T09:00:00Z"))
        assertEquals("2", latestOf(thread)?.id)
    }

    /** The server lists a thread in date order and breaks its own ties, so later in the list wins. */
    @Test
    fun `a tie on the time goes to the one later in the thread`() {
        val thread = listOf(mail("1", "2026-09-20T09:00:00Z"), mail("2", "2026-09-20T09:00:00Z"))
        assertEquals("2", latestOf(thread)?.id)
        assertEquals("1", latestOf(thread.reversed())?.id)
    }

    @Test
    fun `a draft is not the latest thing that happened`() {
        val thread = listOf(mail("1", "2026-09-20T09:00:00Z"), mail("2", "2026-09-23T09:00:00Z", keywords = draft))
        assertEquals("1", latestOf(thread)?.id)
    }

    @Test
    fun `a conversation that is only drafts falls back to its newest draft`() {
        val thread = listOf(mail("1", "2026-09-20T09:00:00Z", keywords = draft), mail("2", "2026-09-21T09:00:00Z", keywords = draft))
        assertEquals("2", latestOf(thread)?.id)
        assertNull(latestOf(emptyList()))
    }

    // ---- the collapsed row ----------------------------------------------------------

    @Test
    fun `a collapsed row shows the newest message but keeps the folder's own id`() {
        val row = mail("1", "2026-09-20T09:00:00Z", from = "Dana", size = 2).copy(account = "a")
        val reply = mail("2", "2026-09-22T09:00:00Z", from = "Justin")
        val shown = threadSummary(row, listOf(row, reply))
        assertEquals("Justin", shown.from)
        assertEquals("justin@example.org", shown.fromEmail)
        assertEquals("About 2", shown.subject)
        assertEquals("Preview of 2", shown.preview)
        assertEquals("2026-09-22T09:00:00Z", shown.receivedAt)
        // Everything an action, the reader or the selection goes by is the folder's message.
        assertEquals("1", shown.id)
        assertEquals("a", shown.account)
        assertEquals(rowToken(row), rowToken(shown))
    }

    @Test
    fun `a row is unread when any message in the conversation is`() {
        val row = mail("1", "2026-09-22T09:00:00Z", seen = true)
        val older = mail("2", "2026-09-20T09:00:00Z", seen = false)
        assertEquals(false, threadSummary(row, listOf(older, row)).seen)
        assertEquals(true, threadSummary(row, listOf(older.copy(seen = true), row)).seen)
    }

    @Test
    fun `a row is starred when any message in the conversation is`() {
        val row = mail("1", "2026-09-22T09:00:00Z")
        val older = mail("2", "2026-09-20T09:00:00Z", flagged = true)
        assertEquals(true, threadSummary(row, listOf(older, row)).flagged)
        assertEquals(false, threadSummary(row, listOf(older.copy(flagged = false), row)).flagged)
    }

    @Test
    fun `a draft counts for neither unread nor starred`() {
        val row = mail("1", "2026-09-22T09:00:00Z")
        val half = mail("2", "2026-09-23T09:00:00Z", seen = false, flagged = true, keywords = draft)
        val shown = threadSummary(row, listOf(row, half))
        assertEquals(true, shown.seen)
        assertEquals(false, shown.flagged)
        assertEquals("1", latestOf(listOf(row, half))?.id)
    }

    /** The Drafts folder lists drafts; redrawing one as the message it answers would be wrong. */
    @Test
    fun `a draft row is left exactly as it is`() {
        val row = mail("1", "2026-09-22T09:00:00Z", keywords = draft)
        assertSame(row, threadSummary(row, listOf(mail("0", "2026-09-23T09:00:00Z", seen = false), row)))
    }

    /** The list row is repainted on every click; the conversation was fetched a minute ago. */
    @Test
    fun `the row's own state is taken from the row not the fetched conversation`() {
        val row = mail("1", "2026-09-22T09:00:00Z", seen = true)
        val stale = row.copy(seen = false)
        assertEquals(true, threadSummary(row, listOf(stale)).seen)
    }

    @Test
    fun `a row with nothing to add is the same row`() {
        val row = mail("1", "2026-09-22T09:00:00Z")
        assertSame(row, threadSummary(row, listOf(row)))
        assertSame(row, threadSummary(row, emptyList()))
    }

    // ---- the list -------------------------------------------------------------------

    private fun gists(vararg pairs: Pair<String, ThreadGist>): (String, String) -> ThreadGist? {
        val map = pairs.toMap()
        return { _, thread -> map[thread] }
    }

    @Test
    fun `with nothing known the list is exactly the rows it was given`() {
        val rows = listOf(mail("1", "2026-09-22T09:00:00Z", size = 3), mail("2", "2026-09-21T09:00:00Z", thread = "t2"))
        val out = threadRows(rows, accountOf = { "a" })
        assertEquals(rows, out.map { it.shown })
        out.forEach { assertSame(it.raw, it.shown) }
    }

    @Test
    fun `a known conversation redraws its row and nothing else`() {
        val row = mail("1", "2026-09-20T09:00:00Z", size = 2)
        val other = mail("9", "2026-09-19T09:00:00Z", thread = "t9")
        val reply = mail("2", "2026-09-22T09:00:00Z", from = "Justin", seen = false)
        val out = threadRows(listOf(row, other), accountOf = { "a" }, gistOf = gists("t1" to ThreadGist(listOf(row, reply))))
        assertEquals(2, out.size)
        assertEquals("Justin", out[0].shown.from)
        assertEquals(false, out[0].shown.seen)
        assertEquals("1", out[0].raw.id)
        assertEquals(listOf("1", "2"), out[0].members.map { it.id })
        assertSame(other, out[1].shown)
    }

    /** Somebody else's thread with the same id, or an old answer, must not redraw a row. */
    @Test
    fun `a conversation that does not include the row is not used`() {
        val row = mail("1", "2026-09-20T09:00:00Z", size = 2)
        val out = threadRows(listOf(row), accountOf = { "a" }, gistOf = gists("t1" to ThreadGist(listOf(mail("7", "2026-09-25T09:00:00Z")))))
        assertSame(row, out.single().shown)
    }

    @Test
    fun `a reply sitting in Trash is not the latest`() {
        val row = mail("1", "2026-09-20T09:00:00Z", size = 2)
        val trashed = mail("2", "2026-09-22T09:00:00Z", from = "Justin")
        val gist = ThreadGist(listOf(row, trashed), mapOf("1" to setOf("inbox"), "2" to setOf("trash")))
        val out = threadRows(listOf(row), accountOf = { "a" }, gistOf = gists("t1" to gist), leaveOut = { setOf("trash", "junk") })
        assertEquals("Dana", out.single().shown.from)
        // In Trash itself, what was deleted is the point.
        val inTrash = ThreadGist(listOf(row, trashed), mapOf("1" to setOf("trash"), "2" to setOf("trash")))
        val there = threadRows(listOf(row), accountOf = { "a" }, gistOf = gists("t1" to inTrash), leaveOut = { setOf("trash") })
        assertEquals("Justin", there.single().shown.from)
    }

    @Test
    fun `a search is drawn as it matched`() {
        val row = mail("1", "2026-09-20T09:00:00Z", size = 2)
        val out = threadRowsFor(listOf(row), ThreadContext(account = "a", collapse = false))
        assertSame(row, out.single().shown)
    }

    // ---- joins and splits -----------------------------------------------------------

    private val a = mail("a1", "2026-09-20T09:00:00Z", from = "Dana", thread = "tA")
    private val b = mail("b1", "2026-09-22T09:00:00Z", from = "Alex", thread = "tB", seen = false)
    private val c = mail("c1", "2026-09-21T09:00:00Z", from = "Cass", thread = "tC")

    @Test
    fun `two joined conversations are one row, standing on the newer`() {
        val re = Rethreading().joined(listOf("tA", "tB"))
        val out = threadRows(listOf(a, b, c), accountOf = { "acct" }, rethreadingOf = { re })
        assertEquals(2, out.size)
        val joined = out.first { it.rows.size == 2 }
        assertEquals("b1", joined.raw.id)
        assertEquals(setOf("a1", "b1"), joined.rows.map { it.id }.toSet())
        assertEquals(2, joined.shown.threadSize)
        assertEquals(false, joined.shown.seen)
        assertEquals("Alex", joined.shown.from)
        assertSame(c, out.first { it.raw.id == "c1" }.shown)
    }

    @Test
    fun `joining a conversation to a joined one makes one group`() {
        val re = Rethreading().joined(listOf("tA", "tB")).joined(listOf("tC", "tB"))
        assertEquals(setOf("tA", "tB", "tC"), re.joinedWith("tA"))
        val out = threadRows(listOf(a, b, c), accountOf = { "acct" }, rethreadingOf = { re })
        assertEquals(1, out.size)
        assertEquals(3, out.single().rows.size)
    }

    @Test
    fun `join then split gives the split message its own row`() {
        val re = Rethreading().joined(listOf("tA", "tB")).split("a1")
        val out = threadRows(listOf(a, b, c), accountOf = { "acct" }, rethreadingOf = { re })
        assertEquals(3, out.size)
        assertTrue(out.all { it.rows.size == 1 })
        assertEquals(1, out.first { it.raw.id == "a1" }.shown.threadSize)
    }

    @Test
    fun `a message split out of a joined conversation leaves the rest joined`() {
        val a2 = mail("a2", "2026-09-19T09:00:00Z", thread = "tA")
        val re = Rethreading().joined(listOf("tA", "tB", "tC")).split("c1")
        val out = threadRows(listOf(a, a2, b, c), accountOf = { "acct" }, rethreadingOf = { re })
        assertEquals(2, out.size)
        assertEquals(setOf("a1", "a2", "b1"), out.first { it.rows.size > 1 }.rows.map { it.id }.toSet())
        assertEquals("c1", out.first { it.rows.size == 1 }.raw.id)
    }

    @Test
    fun `joins that name conversations no longer in the list change nothing`() {
        val re = Rethreading().joined(listOf("tA", "tGone")).split("gone-message")
        val out = threadRows(listOf(a, c), accountOf = { "acct" }, rethreadingOf = { re })
        assertEquals(listOf(a, c), out.map { it.shown })
        out.forEach { assertSame(it.raw, it.shown) }
    }

    @Test
    fun `joins are per account`() {
        val re = Rethreading().joined(listOf("tA", "tB"))
        val elsewhere = b.copy(account = "other")
        val out = threadRows(
            listOf(a.copy(account = "acct"), elsewhere),
            accountOf = { it.account },
            rethreadingOf = { if (it == "acct") re else Rethreading() },
        )
        assertEquals(2, out.size)
    }

    /** The server folded the rest of the conversation into the row, so it needs one of its own. */
    @Test
    fun `splitting the newest message out keeps a row for the rest of the conversation`() {
        val newest = mail("3", "2026-09-22T09:00:00Z", size = 3)
        val middle = mail("2", "2026-09-21T09:00:00Z", from = "Alex")
        val oldest = mail("1", "2026-09-20T09:00:00Z")
        val boxes = mapOf("1" to setOf("inbox"), "2" to setOf("inbox"), "3" to setOf("inbox"))
        val gist = ThreadGist(listOf(oldest, middle, newest), boxes)
        val re = Rethreading().split("3")
        val out = threadRows(listOf(newest), accountOf = { "acct" }, gistOf = gists("t1" to gist), rethreadingOf = { re })
        assertEquals(listOf("3", "2"), out.map { it.raw.id })
        assertEquals(1, out[0].shown.threadSize)
        assertEquals(2, out[1].shown.threadSize)
        assertEquals("Alex", out[1].shown.from)
    }

    @Test
    fun `an older message split out of a conversation gets its own row`() {
        val newest = mail("3", "2026-09-22T09:00:00Z", size = 3)
        val middle = mail("2", "2026-09-21T09:00:00Z", from = "Alex")
        val sent = mail("1", "2026-09-20T09:00:00Z", from = "Justin")
        val boxes = mapOf("1" to setOf("sent"), "2" to setOf("inbox"), "3" to setOf("inbox"))
        val gist = ThreadGist(listOf(sent, middle, newest), boxes)
        val out = threadRows(listOf(newest), accountOf = { "acct" }, gistOf = gists("t1" to gist), rethreadingOf = { Rethreading().split("2").split("1") })
        // The one in this folder gets a row; the one in Sent does not belong in the inbox.
        assertEquals(listOf("3", "2"), out.map { it.raw.id })
        assertEquals(1, out[0].shown.threadSize)
    }

    @Test
    fun `joining takes a split message back into a conversation`() {
        val re = Rethreading().split("a1").joined(listOf("tA", "tB"), listOf("a1", "b1"))
        assertTrue("a1" !in re.splits)
        assertEquals(re.joins["tA"], re.joins["tB"])
    }

    @Test
    fun `the same join made twice comes out the same`() {
        assertEquals(Rethreading().joined(listOf("tB", "tA")), Rethreading().joined(listOf("tA", "tB")))
        assertEquals("tA", Rethreading().joined(listOf("tB", "tA")).joins["tB"])
    }

    @Test
    fun `a server that does not thread can still join, by message`() {
        val one = mail("x", "2026-09-20T09:00:00Z", thread = "")
        val two = mail("y", "2026-09-21T09:00:00Z", thread = "")
        val re = Rethreading().joined(listOf(threadKey(one), threadKey(two)))
        assertEquals(1, threadRows(listOf(one, two), accountOf = { "acct" }, rethreadingOf = { re }).size)
    }

    // ---- the reader's conversation --------------------------------------------------

    /**
     * Answers the way Jmap does: [thread] is empty for a conversation of one, and
     * [members] describes every conversation it is asked about, however small.
     */
    private class Backend(val threads: Map<String, List<Summary>>) {
        val asked = ArrayList<String>()
        fun thread(threadId: String): List<Summary> {
            asked += threadId
            return threads[threadId].orEmpty().takeIf { it.size > 1 }.orEmpty()
        }

        fun members(threadIds: Collection<String>): Map<String, ThreadGist> =
            threadIds.associateWith { ThreadGist(threads[it].orEmpty()) }
    }

    private fun rethreadedThread(backend: Backend, opened: Summary, re: Rethreading, known: List<Summary> = emptyList()) =
        rethreadedThread(opened, re, known, backend::thread, backend::members)

    @Test
    fun `with nothing joined the reader asks the server exactly what it always did`() {
        val t = listOf(a, a.copy(id = "a2", receivedAt = "2026-09-21T09:00:00Z"))
        val backend = Backend(mapOf("tA" to t))
        assertEquals(t, rethreadedThread(backend, a, Rethreading()))
        assertEquals(listOf("tA"), backend.asked)
    }

    @Test
    fun `a joined conversation opens as every message of both, oldest first`() {
        val a2 = mail("a2", "2026-09-23T09:00:00Z", thread = "tA")
        val backend = Backend(mapOf("tA" to listOf(a, a2), "tB" to listOf(b)))
        val re = Rethreading().joined(listOf("tA", "tB"))
        assertEquals(listOf("a1", "b1", "a2"), rethreadedThread(backend, a, re).map { it.id })
    }

    /** A conversation of one on IMAP has no thread to ask about; the row on screen stands in. */
    @Test
    fun `a joined conversation the server cannot describe uses the rows on screen`() {
        val backend = Backend(emptyMap())
        val re = Rethreading().joined(listOf("tA", "tB"))
        assertEquals(listOf("a1", "b1"), rethreadedThread(backend, a, re, known = listOf(a, b, c)).map { it.id })
    }

    @Test
    fun `a split message opens on its own and leaves its old conversation`() {
        val a2 = mail("a2", "2026-09-23T09:00:00Z", thread = "tA")
        val a3 = mail("a3", "2026-09-24T09:00:00Z", thread = "tA")
        val backend = Backend(mapOf("tA" to listOf(a, a2, a3)))
        val re = Rethreading().split("a2")
        assertEquals(emptyList(), rethreadedThread(backend, a2, re))
        assertEquals(listOf("a1", "a3"), rethreadedThread(backend, a, re).map { it.id })
        // Two left is still a conversation; one left is not.
        assertEquals(emptyList(), rethreadedThread(backend, a, re.split("a3")))
    }

    // ---- actions on a collapsed row -------------------------------------------------

    private fun collapsedRow(): ThreadRow {
        val row = mail("1", "2026-09-22T09:00:00Z", seen = true, size = 3)
        val unread = mail("2", "2026-09-21T09:00:00Z", seen = false, flagged = true)
        val half = mail("3", "2026-09-23T09:00:00Z", seen = false, keywords = draft)
        return threadRows(listOf(row), accountOf = { "a" }, gistOf = gists("t1" to ThreadGist(listOf(unread, row, half)))).single()
    }

    @Test
    fun `marking a collapsed row read marks every message that is not, drafts left alone`() {
        val row = collapsedRow()
        assertEquals(setOf("2"), toMark(row, true))
        assertEquals(setOf("1"), toMark(row, false))
    }

    @Test
    fun `taking the star off a collapsed row takes it off every starred message`() {
        assertEquals(setOf("2"), toUnstar(collapsedRow()))
    }

    @Test
    fun `row actions reach the whole conversation and the folder's own message`() {
        val row = collapsedRow()
        val marked = ArrayList<Pair<String, Boolean>>()
        val many = ArrayList<Set<String>>()
        val replied = ArrayList<String>()
        val unstarred = ArrayList<Set<String>>()
        val base = RowActions(
            reply = { m, _ -> replied += m.id },
            markRead = { m, read -> marked += m.id to read },
            markIds = { _, ids, _ -> many += ids },
            star = { error("the star should come off every starred message at once") },
            starIds = { _, ids, on -> if (!on) unstarred += ids },
        )
        val acts = threadActions(base) { if (rowToken(it) == rowToken(row.shown)) row else null }
        acts.markRead!!(row.shown, true)
        assertEquals(listOf("1" to true), marked)
        assertEquals(listOf(setOf("2")), many)
        // Unread is the folder's own message only, which is enough to make the row unread.
        acts.markRead!!(row.shown, false)
        assertEquals(listOf("1" to true, "1" to false), marked)
        assertEquals(1, many.size)
        acts.reply!!(row.shown, false)
        // The folder's own message, never the newest one the row shows.
        assertEquals(listOf("1"), replied)
        assertTrue(row.shown.flagged)
        acts.star!!(row.shown)
        assertEquals(listOf(setOf("2")), unstarred)
    }

    @Test
    fun `an ordinary row acts exactly as it did`() {
        val plain = mail("5", "2026-09-22T09:00:00Z", thread = "t5")
        val row = threadRows(listOf(plain), accountOf = { "a" }).single()
        val archived = ArrayList<String>()
        val marked = ArrayList<String>()
        val starred = ArrayList<String>()
        val base = RowActions(
            archive = { archived += it.id },
            markRead = { m, _ -> marked += m.id },
            markIds = { _, _, _ -> error("one message is not a conversation") },
            star = { starred += it.id },
        )
        val acts = threadActions(base) { row }
        acts.archive!!(plain)
        acts.markRead!!(plain, true)
        acts.star!!(plain)
        assertEquals(listOf("5"), archived)
        assertEquals(listOf("5"), marked)
        assertEquals(listOf("5"), starred)
    }

    @Test
    fun `filing a joined row files every conversation in it`() {
        val re = Rethreading().joined(listOf("tA", "tB"))
        val rows = threadRows(listOf(a, b), accountOf = { "acct" }, rethreadingOf = { re })
        val joined = rows.single()
        val filed = ArrayList<String>()
        val acts = threadActions(RowActions(archive = { filed += it.id })) { joined }
        acts.archive!!(joined.shown)
        assertEquals(setOf("a1", "b1"), filed.toSet())
    }

    // ---- fetching and keeping ---------------------------------------------------------

    @Test
    fun `conversations are asked about a few hundred messages at a time`() {
        val batches = gistBatches(listOf("a" to 150, "b" to 40, "c" to 30, "d" to 1000, "e" to 5), cap = 200)
        assertEquals(listOf(listOf("a", "b"), listOf("c", "e")), batches)
    }

    @Test
    fun `joins and splits survive being written as rows and read back`() {
        val re = Rethreading().joined(listOf("tB", "tA")).joined(listOf("tC", "tD")).split("m1")
        assertEquals(re, rethreadingOf(rethreadRows(re)))
        assertEquals(re, rethreadingOf(rethreadRows(re) + Triple("somethingNewer", "x", "y")))
    }
}
