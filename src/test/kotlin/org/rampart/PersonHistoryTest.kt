package org.rampart

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A person's history: the filter, the merge across accounts, and the copy when the server is gone. */
class PersonHistoryTest {
    private val start = Instant.parse("2026-09-29T12:00:00Z")

    private fun at(minutes: Int) = start.minusSeconds(minutes * 60L).toString()

    private fun row(id: String, minutes: Int, from: String = "dana@example.org", to: List<String> = emptyList(), messageId: String = "") =
        Summary(id, "Dana", from, "Subject $id", at(minutes), "", true, messageId = messageId, recipients = to)

    private fun conditions(filter: kotlinx.serialization.json.JsonObject) =
        filter["conditions"]!!.jsonArray.map { c -> c.jsonObject.entries.single().let { it.key to it.value.jsonPrimitive.content } }

    // The filter

    @Test
    fun `one address is looked for in from, to, cc and bcc`() {
        val filter = personFilter(listOf("dana@example.org"))
        assertEquals("OR", filter["operator"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("from", "to", "cc", "bcc").map { it to "dana@example.org" },
            conditions(filter),
        )
    }

    @Test
    fun `several addresses are all looked for, in one filter`() {
        val filter = personFilter(listOf("dana@example.org", "dana@work.example"))
        assertEquals(8, conditions(filter).size)
        assertEquals(setOf("dana@example.org", "dana@work.example"), conditions(filter).map { it.second }.toSet())
        assertEquals(listOf("from" to "dana@example.org", "from" to "dana@work.example"), conditions(fromFilter(listOf("dana@example.org", "dana@work.example"))))
        assertEquals(6, conditions(toFilter(listOf("dana@example.org", "dana@work.example"))).size)
        assertTrue(conditions(toFilter(listOf("a@x"))).none { it.first == "from" })
    }

    @Test
    fun `a header that only contains the address is not them`() {
        val dana = listOf("dana@example.org")
        assertTrue(involves("Dana@Example.org", emptyList(), dana))
        assertTrue(involves("someone@else", listOf("dana@example.org"), dana))
        assertFalse(involves("adana@example.org", listOf("dana@example.org.uk"), dana))
    }

    // The merge

    /** An account that answers from a fixed list, counting how often it is asked. */
    private class Fixed(override val account: String, private val rows: List<Summary>, private val stats: PersonStats = PersonStats()) : PersonSource {
        override val answered = Answered.SERVER
        var asked = 0
        override fun page(position: Int, limit: Int, withStats: Boolean): PersonPage {
            asked++
            val page = rows.drop(position).take(limit).map { it.copy(account = account) }
            return PersonPage(page, page.size, if (withStats) stats else null)
        }
    }

    @Test
    fun `accounts merge newest first, a page at a time`() = runBlocking {
        val work = Fixed("work", List(250) { row("w$it", it * 2) }, PersonStats(received = 200, sent = 50, first = at(498), last = at(0)))
        val home = Fixed("home", List(40) { row("h$it", it * 2 + 1) }, PersonStats(received = 30, sent = 10, first = at(79), last = at(1)))
        val history = PersonHistory(listOf(work, home), pageSize = 100)
        val first = history.next()
        assertEquals(100, first.size)
        assertEquals(first.sortedByDescending { it.receivedAt }, first, "newest first across both accounts")
        assertEquals(setOf("work", "home"), first.map { it.account }.toSet())
        assertEquals(PersonStats(received = 230, sent = 60, first = at(498), last = at(0)), history.stats)
        val all = first + history.next() + history.next()
        assertTrue(history.exhausted)
        assertEquals(290, all.size)
        assertEquals(290, all.map { it.account + it.id }.toSet().size)
        assertEquals(all.sortedByDescending { it.receivedAt }, all)
        assertTrue(work.asked <= 4, "work was asked ${work.asked} times for 250 rows")
    }

    @Test
    fun `the same message in two accounts is shown once`() = runBlocking {
        val shared = "<list-7@lists.example>"
        val work = Fixed("work", listOf(row("w1", 1, messageId = shared), row("w2", 3)))
        val home = Fixed("home", listOf(row("h9", 1, messageId = shared.uppercase()), row("h2", 2)))
        val rows = PersonHistory(listOf(work, home)).next()
        assertEquals(3, rows.size)
        assertEquals(1, rows.count { it.messageId.equals(shared, ignoreCase = true) })
        // The same id in two accounts is two messages, when nothing says otherwise.
        val a = Fixed("a", listOf(row("m1", 1)))
        val b = Fixed("b", listOf(row("m1", 2)))
        assertEquals(2, PersonHistory(listOf(a, b)).next().size)
    }

    // The copy

    private fun withStore(block: (Store) -> Unit) {
        val dir = Files.createTempDirectory("rampart-person")
        try {
            Store.open(dir.resolve("mail.db"), null).use(block)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the copy finds mail from them and mail to them, and counts both`() = withStore { store ->
        store.put("inbox", listOf(row("in1", 10), row("in2", 30, from = "DANA@example.org"), row("other", 5, from = "sam@example.org")))
        store.put("sent", listOf(row("out1", 20, from = "me@example.org", to = listOf("dana@example.org", "sam@example.org"))))
        val dana = listOf("dana@example.org")
        assertEquals(listOf("in1", "out1", "in2"), store.personPage(dana, 100, 0).map { it.id })
        assertEquals(listOf("out1"), store.personPage(dana, 1, 1).map { it.id })
        assertEquals(PersonStats(received = 2, sent = 1, first = at(30), last = at(10)), store.personStats(dana))
        // Written again without recipients, as a row read back from the copy is, it keeps them.
        store.put("sent", listOf(row("out1", 20, from = "me@example.org")))
        assertEquals(1, store.personStats(dana).sent)
    }

    @Test
    fun `a near miss is not this person, in the counts or the dates`() {
        val bob = "bob@example.org"
        val hits = listOf(
            PersonHit("jimbob@example.org", emptyList(), "2020-01-01T00:00:00Z"),
            PersonHit("bob@example.org", emptyList(), "2024-05-01T00:00:00Z"),
            PersonHit("me@example.org", listOf("jimbob@example.org"), "2021-01-01T00:00:00Z"),
            PersonHit("me@example.org", listOf("bob@example.org"), "2024-06-01T00:00:00Z"),
        )
        assertEquals(
            PersonStats(received = 1, sent = 1, first = "2024-05-01T00:00:00Z", last = "2024-06-01T00:00:00Z"),
            exactPersonStats(hits, listOf(bob), onFirstPage = true),
        )
        assertNull(exactPersonStats(hits, listOf(bob), onFirstPage = false).last)
    }

    @Test
    fun `a blind copy is part of who the message went to`() {
        val onlyBcc = buildJsonObject {
            putJsonArray("bcc") {
                add(buildJsonObject { put("name", "Bob"); put("email", "Bob@Example.org") })
            }
        }
        assertEquals(listOf("bob@example.org"), recipientsIn(onlyBcc))
        val repeated = buildJsonObject {
            putJsonArray("to") { add(buildJsonObject { put("email", "bob@example.org") }) }
            putJsonArray("cc") { add(buildJsonObject { put("email", "Bob@Example.org") }) }
            putJsonArray("bcc") { add(buildJsonObject { put("email", "sam@example.org") }) }
        }
        assertEquals(listOf("bob@example.org", "sam@example.org"), recipientsIn(repeated))
        assertTrue("bcc" in emailGetProperties)
    }

    @Test
    fun `a person who was only blind copied is in the copy`() = withStore { store ->
        val recipients = storedRecipients(emptyList(), emptyList(), listOf("Bob@Example.org"))
        store.put("sent", listOf(row("bcc1", 10, from = "me@example.org", to = recipients)))
        val bob = listOf("bob@example.org")
        assertEquals(listOf("bcc1"), store.personPage(bob, 100, 0).map { it.id })
        assertEquals(PersonStats(received = 0, sent = 1, first = at(10), last = at(10)), store.personStats(bob))
    }

    @Test
    fun `an unreachable server is answered from the copy, and says so`() = withStore { store ->
        store.put("inbox", listOf(row("in1", 10)))
        // Nothing listens on port 1, so the request fails the way an offline one does.
        val offline = Jmap.forLocalServer("http://127.0.0.1:1/api", "http://127.0.0.1:1/download/{blobId}")
        val source = AccountSource("work", offline, store, listOf("dana@example.org"))
        val history = PersonHistory(listOf(source))
        val rows = runBlocking { history.next() }
        assertEquals(listOf("in1"), rows.map { it.id })
        assertEquals("work", rows.single().account)
        assertEquals(Answered.COPY, history.answered()["work"])
        assertEquals(1, history.stats.received)
        val note = answeredNote(history.answered(), mapOf("work" to "Work"))!!
        assertTrue(note.startsWith("Work answered from the copy"), note)
    }

    @Test
    fun `an account that cannot be asked, like IMAP, uses the copy from the start`() = withStore { store ->
        store.put("inbox", listOf(row("i1", 3)))
        val source = AccountSource("imap", null, store, listOf("dana@example.org"))
        assertEquals(listOf("i1"), runBlocking { PersonHistory(listOf(source)).next() }.map { it.id })
        assertEquals(Answered.COPY, source.answered)
        assertNull(answeredNote(mapOf("a" to Answered.SERVER), emptyMap()), "every server answering needs no note")
    }

    // The server

    @Test
    fun `the first page and the counts are one request, and near misses are dropped`() {
        FakeJmapServer().use { server ->
            val jmap = server.client()
            server.reset()
            val page = jmap.personPage(listOf("sender5@example.org"), 0, 100, withStats = true)
            assertEquals(1, server.apiTrips.get(), "page, counts and first date in one round trip")
            assertEquals(4, server.methods.get())
            // The fake server ignores the filter and hands back m0 to m99, so the rows kept are
            // exactly the ones really from sender5, and not sender50 to sender59.
            assertEquals(listOf("m5"), page.rows.map { it.id })
            assertEquals(100, page.consumed)
            // The same server reports a total of 50,000 for every query. The count has to
            // come from the messages themselves: sender5 is every 200th id in the capped batch.
            val stats = page.stats ?: PersonStats(received = -1)
            val expected = (0 until PERSON_STAT_LIMIT).count { it % 200 == 5 }
            assertEquals(expected, stats.received)
            assertEquals(0, stats.sent)
            server.reset()
            jmap.personPage(listOf("sender5@example.org"), 100, 100, withStats = false)
            assertEquals(2, server.methods.get(), "later pages are only the page")
        }
    }

    @Test
    fun `fifty thousand messages`() {
        if (System.getProperty("rampart.bench") != "true") return
        withStore { store ->
            val random = java.util.Random(108)
            (0 until 50).forEach { chunk ->
                store.put("inbox", List(1_000) { i ->
                    val n = chunk * 1_000 + i
                    val sender = "sender${random.nextInt(800)}@example.org"
                    row("m$n", n, from = if (n % 97 == 0) "me@example.org" else sender, to = listOf("me@example.org", "cc${n % 300}@example.org"))
                })
            }
            val person = listOf("sender17@example.org", "cc17@example.org")
            fun ms(block: () -> Unit): Double {
                repeat(2) { block() }
                val t = System.nanoTime()
                repeat(9) { block() }
                return (System.nanoTime() - t) / 9 / 1_000_000.0
            }
            println("\nPerson history from the copy, 50,000 messages")
            println("first page: %.1f ms".format(ms { store.personPage(person, 100, 0) }))
            println("counts and dates: %.1f ms".format(ms { store.personStats(person) }))
            println("matching messages: ${store.personStats(person).let { it.received + it.sent }}")
        }
    }
}
