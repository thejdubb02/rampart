package org.rampart

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scrolling a folder from the copy: when it may, when it must not, and what it costs.
 *
 * The first three tests always run, on a small folder. The last is the fifty thousand
 * message measurement, off unless `-Drampart.bench=true`, because a timing on a shared
 * build machine is a guess rather than a number worth failing a build over.
 */
class ListPagingTest {
    /** The folder as the server holds it, newest first, every timestamp distinct. */
    private fun folder(size: Int): List<Summary> {
        val start = Instant.parse("2026-09-29T12:00:00Z")
        return List(size) { n ->
            Summary(
                id = "m$n",
                from = "Sender ${n % 300}",
                fromEmail = "sender${n % 300}@example.org",
                subject = if (n % 5 == 0) "Re: Weekly update $n" else "Weekly update $n",
                receivedAt = start.minusSeconds(n * 61L).toString(),
                preview = "A preview of message $n about the weekly update and what is next.",
                seen = n % 3 != 0,
                threadId = "t${n / 3}",
            )
        }
    }

    /** Scrolls the whole folder the way the list does, and counts the pages the server had to answer. */
    private fun scroll(store: Store, truth: List<Summary>): Pair<Int, List<Summary>> {
        var server = 0
        val shown = ArrayList<Summary>(truth.size)
        shown += store.messages("inbox", LIST_PAGE, 0)
        while (shown.size < truth.size) {
            val offset = shown.size
            val page = pageFromCopy(store, "inbox", offset) ?: run {
                server++
                truth.subList(offset, minOf(truth.size, offset + LIST_PAGE)).also { extendCopy(store, "inbox", offset, it) }
            }
            if (page.isEmpty()) break
            shown += page
        }
        return server to shown
    }

    private fun opened(truth: List<Summary>, state: String, block: (Store) -> Unit) {
        val path = Files.createTempDirectory("rampart-paging").resolve("mail.db")
        try {
            Store.open(path, null).use { store ->
                // What reload does on first opening the folder: the first page, the cursor, the mark.
                val first = truth.take(LIST_PAGE)
                store.put("inbox", first)
                store.setCursor("inbox", state)
                markFirstPage(store, "inbox", state, first)
                block(store)
            }
        } finally {
            path.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the first scroll asks the server and the second reads the copy`() {
        val truth = folder(1_000)
        opened(truth, "s1") { store ->
            val (first, shown) = scroll(store, truth)
            assertEquals(9, first, "every page after the first came from the server once")
            assertEquals(truth.map { it.id }, shown.map { it.id })
            val (second, again) = scroll(store, truth)
            assertEquals(0, second, "an unchanged folder scrolls entirely from the copy")
            assertEquals(truth.map { it.id }, again.map { it.id }, "and in the same order the server gave")
        }
    }

    @Test
    fun `a change anywhere in the account sends scrolling back to the server`() {
        val truth = folder(500)
        opened(truth, "s1") { store ->
            scroll(store, truth)
            // Something moved: the next reload reads the first page at the new state.
            store.setCursor("inbox", "s2")
            assertNull(pageFromCopy(store, "inbox", LIST_PAGE), "the mark is for a state that has passed")
            markFirstPage(store, "inbox", "s2", truth.take(LIST_PAGE))
            assertEquals(4, scroll(store, truth).first)
        }
    }

    @Test
    fun `a page that does not carry straight on from the mark is not vouched for`() {
        val truth = folder(500)
        opened(truth, "s1") { store ->
            // A page from further down, as if the list had jumped: kept out of the mark.
            extendCopy(store, "inbox", 300, truth.subList(300, 400))
            assertEquals("s1" to LIST_PAGE, store.pagedThrough("inbox"))
            assertNull(pageFromCopy(store, "inbox", 300))
        }
    }

    @Test
    fun `a message the server dropped is never paged in from the copy`() {
        val truth = folder(400)
        opened(truth, "s1") { store ->
            // The copy still holds a message from an earlier read that the server has since
            // deleted, dated in the middle of the second page.
            val ghost = truth[150].copy(id = "gone", subject = "Deleted on the server")
            store.put("inbox", listOf(ghost))
            scroll(store, truth)
            val (_, shown) = scroll(store, truth)
            assertTrue(shown.none { it.id == "gone" })
            assertEquals(truth.map { it.id }, shown.map { it.id })
        }
    }

    @Test
    fun `fifty thousand messages`() {
        if (System.getProperty("rampart.bench") != "true") return
        val truth = folder(50_000)
        opened(truth, "s1") { store ->
            var began = System.nanoTime()
            val (fromServer, _) = scroll(store, truth)
            val firstScroll = (System.nanoTime() - began) / 1_000_000
            began = System.nanoTime()
            val (again, shown) = scroll(store, truth)
            val secondScroll = (System.nanoTime() - began) / 1_000_000
            assertEquals(0, again)
            assertEquals(50_000, shown.size)

            fun median(block: () -> Unit): Double {
                repeat(2) { block() }
                val samples = (1..9).map { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1_000_000.0 }.sorted()
                return samples[samples.size / 2]
            }
            println("\nList paging, 50,000 message folder")
            println("first scroll to the bottom: $fromServer pages from the server, $firstScroll ms of local work")
            println("second scroll to the bottom: $again pages from the server, $secondScroll ms")
            listOf(0, 10_000, 25_000, 49_900).forEach { offset ->
                println("one page from the copy at row %,d: %.1f ms".format(offset, median { pageFromCopy(store, "inbox", offset) }))
            }
            Order.entries.forEach { order ->
                listOf(100, 2_000, 50_000).forEach { rows ->
                    val slice = shown.take(rows)
                    println("sort %,d rows by %s: %.1f ms".format(rows, order.name.lowercase(), median { sorted(slice, order) }))
                }
            }
            // What the rows cost to hold, once scrolled to the bottom: the list keeps every
            // page it has loaded, though only the ones on screen are ever composed.
            val runtime = Runtime.getRuntime()
            fun used(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; return runtime.totalMemory() - runtime.freeMemory() }
            val before = used()
            val held = store.messages("inbox", 50_000, 0)
            val after = used()
            println("50,000 rows held in memory: %.1f MB, %d bytes a row".format((after - before) / 1e6, (after - before) / held.size))
            assertTrue(held.size == 50_000)
        }
    }
}
