package org.rampart

import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opening a folder, a saved search and each list layout against fifty thousand messages.
 *
 * Off unless `RAMPART_BENCH` is set, because a timing on a shared build machine is a
 * guess rather than a measurement. Run it with
 * `RAMPART_BENCH=1 ./gradlew test --tests org.rampart.ListBenchmarkTest` and read the
 * numbers it prints. The one assertion is the budget that matters: a folder opens from
 * the local copy in under 100 ms.
 *
 * The store is unencrypted here. The encrypted file costs a little more per page read,
 * so these are a floor, not a promise. Set `RAMPART_BENCH_DB` to a path to keep the
 * filled file and reuse it on the next run.
 */
class ListBenchmarkTest {
    private val total = 50_000

    private fun fill(store: Store) {
        val random = java.util.Random(44)
        val start = Instant.parse("2020-01-01T00:00:00Z").epochSecond
        val span = Instant.parse("2026-09-29T00:00:00Z").epochSecond - start
        val folders = listOf("inbox" to 30_000, "archive" to 11_950, "sent" to 5_000, "junk" to 3_000, "small" to 50)
        var n = 0
        for ((folder, count) in folders) {
            val page = ArrayList<Summary>(1000)
            repeat(count) {
                val sender = random.nextInt(800)
                val list = if (random.nextInt(100) < 15) "list${random.nextInt(40)}.example.org" else ""
                val tags = buildSet {
                    if (random.nextInt(100) < 10) add("tag${random.nextInt(12)}")
                    if (random.nextInt(100) < 70) add("\$seen")
                }
                page += Summary(
                    id = "m$n",
                    from = "Sender $sender",
                    fromEmail = "sender$sender@domain${sender % 50}.example",
                    subject = "Subject ${random.nextInt(5000)} about ${WORDS[random.nextInt(WORDS.size)]}",
                    receivedAt = Instant.ofEpochSecond(start + (random.nextDouble() * span).toLong()).toString(),
                    preview = (1..20).joinToString(" ") { WORDS[random.nextInt(WORDS.size)] },
                    seen = "\$seen" in tags,
                    flagged = random.nextInt(100) < 3,
                    keywords = tags,
                    threadId = "t${n / 3}",
                    size = 2_000L + random.nextInt(4_000_000),
                    listId = list,
                )
                n++
                if (page.size == 1000) {
                    store.put(folder, page)
                    page.clear()
                }
            }
            if (page.isNotEmpty()) store.put(folder, page)
        }
    }

    /** The median of [runs] timings in milliseconds, after two runs that warm the cache. */
    private fun time(runs: Int = 15, block: () -> Any?): Double {
        repeat(2) { block() }
        val samples = (1..runs).map {
            val began = System.nanoTime()
            block()
            (System.nanoTime() - began) / 1_000_000.0
        }.sorted()
        return samples[samples.size / 2]
    }

    @Test
    fun `fifty thousand messages`() {
        if (System.getenv("RAMPART_BENCH").isNullOrBlank()) return
        val kept = System.getenv("RAMPART_BENCH_DB")?.takeIf { it.isNotBlank() }?.let { java.nio.file.Path.of(it) }
        val path = kept ?: Files.createTempDirectory("rampart-bench").resolve("mail.db")
        val reuse = kept != null && Files.exists(path)
        try {
            Store.open(path, null).use { store ->
                if (!reuse) {
                    val filled = System.nanoTime()
                    fill(store)
                    println("fill: %.0f ms for $total messages".format((System.nanoTime() - filled) / 1_000_000.0))
                }
                val utc = ZoneOffset.UTC
                val except = listOf("junk")
                val results = LinkedHashMap<String, Double>()

                results["folder open, inbox, first 100"] = time { store.messages("inbox") }
                results["folder open, inbox, unread only"] = time { store.messages("inbox", filters = QuickFilters(unread = true)) }
                results["folder open, archive, page 3"] = time { store.messages("archive", from = 200) }
                results["folder open, a folder of 50"] = time { store.messages("small") }
                val refresh = store.messages("inbox")
                results["refresh write, 100 messages back into the store"] = time(runs = 5) { store.put("inbox", refresh) }

                val common = Condition.Group(
                    Joiner.ALL,
                    listOf(
                        Condition.Group(Joiner.ANY, listOf(Condition.Match(SearchField.UNREAD), Condition.Match(SearchField.STARRED))),
                        Condition.Group(Joiner.NONE, listOf(Condition.Match(SearchField.LIST, "list1"))),
                    ),
                )
                val rare = Condition.Group(
                    Joiner.ALL,
                    listOf(Condition.Match(SearchField.TAG, "tag3"), Condition.Match(SearchField.FROM, "sender17@")),
                )
                val text = Condition.Group(
                    Joiner.ALL,
                    listOf(Condition.Match(SearchField.TEXT, "invoice"), Condition.Match(SearchField.AFTER, "2025-01-01")),
                )
                val lists = Condition.Match(SearchField.LIST, ".example.org")
                fun open(condition: Condition) = store.matching(localWhere(condition, except, utc) as LocalQuery.Sql)
                results["saved search open, unread or starred and not a list"] = time { open(common) }
                results["saved search open, rare tag and sender"] = time { open(rare) }
                results["saved search open, text and date"] = time { open(text) }
                results["saved search open, every list"] = time { open(lists) }
                val child = childSearch(
                    SavedSearch(name = "Lists", account = "a", condition = lists, split = Split.LIST),
                    SplitGroup("list7.example.org", "list7.example.org", 0, 0),
                )
                results["split child open, one list"] = time { open(child.condition!!) }
                val split = SavedSearch(name = "Lists", account = "a", condition = lists, split = Split.LIST)
                results["count and split by list"] = time { countConditionSearch(split, except, store, zone = utc) }
                val bySender = SavedSearch(name = "Unread", account = "a", condition = common, split = Split.SENDER)
                results["count and split by sender"] = time { countConditionSearch(bySender, except, store, zone = utc) }

                val page = store.messages("inbox", limit = 200)
                results["layout normal, sort 200 rows"] = time { sorted(page, Order.NEWEST) }
                results["layout cards, sort 200 rows"] = time { sorted(page, Order.NEWEST) }
                TableColumn.entries.forEach { column ->
                    results["layout table, sort 200 rows by ${column.key}"] = time { tableSorted(page, TableSort(column)) }
                }
                val everything = store.messages("inbox", limit = total)
                results["layout table, sort 30,000 rows by subject"] = time(runs = 5) { tableSorted(everything, TableSort(TableColumn.SUBJECT)) }

                results.forEach { (what, ms) -> println("%-58s %8.2f ms".format(what, ms)) }
                assertTrue(results.getValue("folder open, inbox, first 100") < 100.0, "A folder must open in under 100 ms.")
            }
        } finally {
            if (kept == null) path.deleteIfExists()
        }
    }

    private companion object {
        val WORDS = listOf(
            "invoice", "meeting", "quote", "project", "report", "update", "holiday", "order", "receipt", "payment",
            "delivery", "account", "password", "newsletter", "photos", "agenda", "contract", "review", "draft", "summary",
        )
    }
}
