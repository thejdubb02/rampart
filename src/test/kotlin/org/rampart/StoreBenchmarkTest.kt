package org.rampart

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.copyTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.measureTime

class StoreBenchmarkTest {
    private val timings = linkedMapOf<String, Duration>()
    private val plans = linkedMapOf<String, List<String>>()

    @Test
    fun `benchmark fifty thousand message mailbox`() {
        if (System.getProperty("rampart.bench") != "true") return
        val directory = Files.createTempDirectory("rampart-store-benchmark")
        try {
            val firstPath = directory.resolve("account-1.db")
            Store.open(firstPath, null).use { seed(it) }
            val secondPath = copyStore(firstPath, directory.resolve("account-2.db"))
            val thirdPath = copyStore(firstPath, directory.resolve("account-3.db"))
            Store.open(firstPath, null).use { first ->
                Store.open(secondPath, null).use { second ->
                    Store.open(thirdPath, null).use { third ->
                        runBenchmarks(first, second, third)
                    }
                }
            }
            printTimings()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun seed(store: Store) {
        store.put(realisticMessages().chunked(2_500).mapIndexed { folder, messages -> "folder-$folder" to messages }.toMap())
    }

    private fun realisticMessages(): List<Summary> {
        val start = Instant.parse("2026-09-29T12:00:00Z")
        return List(50_000) { number ->
            val common = if (number % 4 == 0) "project update" else "weekly discussion"
            val rare = if (number == 41_337) " quokkaarchive" else ""
            val keywords = buildSet {
                if (number % 3 == 0) add("work")
                if (number % 7 == 0) add("clients/acme")
                if (number % 11 == 0) add("receipts")
                if (number % 17 == 0) add("\$answered")
            }
            Summary(
                id = "message-$number",
                from = "Sender ${number % 200}",
                fromEmail = "sender-${number % 200}@example.org",
                subject = "$common ${number % 1_000}$rare",
                receivedAt = start.minus(number.toLong() * 19, ChronoUnit.MINUTES).toString(),
                preview = "A realistic mail preview about $common and task ${number % 97}.$rare",
                seen = number % 3 != 0,
                flagged = number % 13 == 0,
                keywords = keywords,
                threadId = "thread-${number % 3_000}",
                threadSize = 1 + number % 18,
                messageId = "<message-$number@example.org>",
            )
        }
    }

    private fun copyStore(source: Path, target: Path): Path = source.copyTo(target)

    private fun runBenchmarks(first: Store, second: Store, third: Store) {
        val known = (0 until 50).mapTo(mutableSetOf()) { "sender-$it@example.org" }
        timed("Folder first page") { assertEquals(100, first.messages("folder-0").size) }
        timed("Folder page 20") { assertEquals(100, first.messages("folder-0", from = 1_900).size) }
        val filters = linkedMapOf(
            "Filter unread" to QuickFilters(unread = true),
            "Filter starred" to QuickFilters(starred = true),
            "Filter tagged" to QuickFilters(tagged = true),
            "Filter known sender" to QuickFilters(knownSender = true),
            "Filter combined" to QuickFilters(unread = true, starred = true, tagged = true, knownSender = true),
        )
        filters.forEach { (name, filter) ->
            timed(name) { first.messages("folder-0", filters = filter, knownSenders = known) }
        }
        timed("Search common word") { assertEquals(100, first.search("project").size) }
        timed("Search rare word") { assertEquals(1, first.search("quokkaarchive").size) }
        timed("Keyword counts") { assertTrue(first.keywordCounts().isNotEmpty()) }
        val saved = SavedSearch("benchmark", "Unread project", "account-1", "project", QuickFilters(unread = true))
        timed("Saved search count") {
            countUnreadSavedSearch(saved, first, "folder-0", known)
        }
        timed("Unified inbox merge") {
            val merged = merged(
                mapOf(
                    "account-1" to first.messages("folder-0"),
                    "account-2" to second.messages("folder-0"),
                    "account-3" to third.messages("folder-0"),
                ),
            )
            assertEquals(100, merged.size)
        }
        val additions = realisticMessages().take(500).mapIndexed { number, message ->
            message.copy(id = "new-$number", messageId = "<new-$number@example.org>")
        }
        timed("Upsert 500 messages") { first.put("folder-0", additions) }
        plans["Folder page"] = first.explain(
            "SELECT message.* FROM message JOIN mailbox_message mm ON mm.message_id = message.id " +
                "WHERE mm.mailbox_id = ? ORDER BY receivedAt DESC LIMIT ? OFFSET ?",
            "folder-0", 100, 0,
        )
        plans["Tagged filter"] = first.explain(
            "SELECT * FROM message WHERE id IN " +
                "(SELECT message_id FROM mailbox_message WHERE mailbox_id = ?) AND EXISTS " +
                "(SELECT 1 FROM message_keyword mk WHERE mk.message_id = message.id AND mk.visible = 1) " +
                "ORDER BY receivedAt DESC LIMIT ? OFFSET ?",
            "folder-0", 100, 0,
        )
        plans["Full text search"] = first.explain(
            "SELECT message.* FROM search JOIN message ON message.id = search.id " +
                "WHERE search MATCH ? ORDER BY receivedAt DESC LIMIT ?",
            "\"project\"", 100,
        )
    }

    private fun timed(name: String, block: () -> Unit) {
        block()
        timings[name] = measureTime(block)
    }

    private fun printTimings() {
        println("RAM-44 Store benchmark with 50,000 messages per account")
        println("Operation                         Time in ms")
        println("-------------------------------- ----------")
        timings.forEach { (name, elapsed) -> println("%-32s %10.2f".format(name, elapsed.inWholeNanoseconds / 1_000_000.0)) }
        println("Query plans")
        plans.forEach { (name, details) -> println("$name: ${details.joinToString("; ")}") }
    }
}
