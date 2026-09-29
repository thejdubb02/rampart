package org.rampart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round trips per action, against [FakeJmapServer], and the budget each is held to.
 *
 * Each action below is the sequence of backend calls Main.kt makes for it, call for call,
 * using the same backend functions. The server adds a fixed delay to every request, so the
 * wall time shows what running side by side buys as well as what batching does. The
 * numbers from before RAM-44, measured the same way, are in docs/performance.md.
 */
class RoundTripTest {
    private val latency = 40L

    private data class Row(val action: String, val trips: Int, val api: Int, val methods: Int, val ms: Long)

    private fun measure(server: FakeJmapServer, action: String, block: () -> Unit): Row {
        server.reset()
        val began = System.nanoTime()
        block()
        val ms = (System.nanoTime() - began) / 1_000_000
        return Row(action, server.trips.get(), server.apiTrips.get(), server.methods.get(), ms)
    }

    private fun print(rows: List<Row>) {
        println("\nRound trips at ${latency} ms per request")
        println("%-58s %6s %5s %8s %7s".format("action", "trips", "api", "methods", "ms"))
        rows.forEach { println("%-58s %6d %5d %8d %7d".format(it.action, it.trips, it.api, it.methods, it.ms)) }
    }

    private fun startUp(accounts: List<Jmap>) = runBlocking {
        accounts.map { async(Dispatchers.IO) { it.startup() } }.map { it.await() }
    }

    private fun openCold(jmap: Jmap, id: String) = runBlocking {
        val thread = async(Dispatchers.IO) { jmap.thread("t${id.removePrefix("m").toInt() / 3}") }
        val opened = jmap.open(id)
        cidBytes(jmap, opened.body, opened.attachments)
        // The state came back with the message, so there is no separate question for it.
        opened.state ?: jmap.mailState()
        thread.await()
    }

    private fun openKept(jmap: Jmap, id: String) = jmap.stateAndStamp(id)

    private fun arrival(jmap: Jmap) {
        jmap.mailState()
        jmap.pageAndFolders("box0", 30)
        jmap.mailState()
        jmap.emails("box0", limit = 100)
    }

    private fun unified(accounts: List<Jmap>) = runBlocking {
        accounts.map { async(Dispatchers.IO) { it.emails("box0", limit = 100) } }.map { it.await() }
    }

    private fun contactsPage(jmap: Jmap) = jmap.booksAndContacts()

    @Test
    fun `round trips per action`() {
        FakeJmapServer(latencyMs = latency).use { server ->
            val one = server.client()
            val three = List(3) { server.client() }
            // Once through untimed, so the first row does not carry the JVM warming up.
            startUp(three); openCold(one, "m1"); arrival(one); contactsPage(one)
            val rows = listOf(
                measure(server, "start-up, 3 accounts") { startUp(three) },
                measure(server, "open a message, cold") { openCold(one, "m301") },
                measure(server, "open a message, kept copy, account moved") { openKept(one, "m301") },
                measure(server, "new mail arrives, poll and list reload") { arrival(one) },
                measure(server, "unified inbox, 3 accounts") { unified(three) },
                measure(server, "contacts page") { contactsPage(one) },
                measure(server, "start-up, 1 account") { startUp(listOf(one)) },
                measure(server, "unified inbox, 1 account") { unified(listOf(one)) },
            )
            print(rows)
            val byName = rows.associateBy { it.action }
            fun api(name: String) = byName.getValue(name).api
            // The budget each action is held to from now on. A change that adds a round
            // trip to any of these fails here rather than being felt later.
            assertEquals(3, api("start-up, 3 accounts"), "one request per account")
            assertEquals(2, api("open a message, cold"), "the message and its thread")
            assertEquals(1, api("open a message, kept copy, account moved"))
            assertEquals(4, api("new mail arrives, poll and list reload"))
            assertEquals(1, api("contacts page"))
            // Side by side: three accounts take about as long as one, not three times as long.
            // Compared with one account on the same machine rather than with a fixed number,
            // so a slow build machine slows both sides alike.
            fun ms(name: String) = byName.getValue(name).ms
            assertTrue(ms("unified inbox, 3 accounts") < 2 * ms("unified inbox, 1 account"), "the inboxes were not asked at once")
            assertTrue(ms("start-up, 3 accounts") < 2 * ms("start-up, 1 account"), "the accounts were not started at once")
        }
    }
}
