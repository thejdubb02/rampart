package org.rampart

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Five hundred messages opened in a row, and the heap afterwards.
 *
 * Everything an open does outside the window, in the order loadCard in Main.kt does it,
 * against [FakeJmapServer]: the fetch, the pictures, the page built from them, the copy kept
 * on disk with its pictures, the height remembered for the next time. Every one of those
 * holds something, and each is meant to be capped: the copy by [PICTURE_CACHE_CAP] a message
 * and on disk rather than in memory, the heights by [HeightMemory]'s count, the page by the
 * card that is open, which is one conversation at a time.
 *
 * The window itself (the web view, the cards in state) needs a display and is not driven
 * here. The card map is reset per conversation in Main.kt, and its load counters with it.
 *
 * Measured after a warm up, so what is compared is steady state rather than the JVM
 * loading classes. Heap is read after a full collection, three times over, because one
 * call to System.gc is a request rather than a promise.
 */
class SoakTest {
    private fun heap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(80) }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun `five hundred opens leave the heap where it was`() {
        val dir = Files.createTempDirectory("rampart-soak")
        try {
            FakeJmapServer(latencyMs = 0).use { server ->
                val jmap = server.client()
                Store.open(dir.resolve("mail.db"), null).use { store ->
                    val heights = HeightMemory()
                    // Only the card on screen is held, as the reader holds one conversation.
                    var onScreen: Reading? = null
                    fun open(n: Int) {
                        val id = "m$n"
                        val opened = jmap.open(id)
                        val pictures = cidBytes(jmap, opened.body, opened.attachments)
                        onScreen = prepareReading("sender@example.org", "Sender", opened.body, opened.attachments, pictures, false, false, emptySet())
                        store.putKept(id, opened.body, opened.attachments, pictures, opened.emailBlobId, opened.state, opened.calendar)
                        heights.remember(CardKey("a", id), 1_200 + n % 400)
                    }
                    (0 until 60).forEach { open(it) }
                    val before = heap()
                    val samples = mutableListOf<Long>()
                    (0 until 500).forEach { n ->
                        open(1_000 + n)
                        if (n % 100 == 99) samples += heap()
                    }
                    val after = heap()
                    val growth = (after - before) / 1e6
                    println("\nSoak, 500 opens: heap before %.1f MB, after %.1f MB, growth %.1f MB".format(before / 1e6, after / 1e6, growth))
                    println("heap every 100 opens, MB: " + samples.joinToString(", ") { "%.1f".format(it / 1e6) })
                    println("heights remembered: ${heights.size()} (capped at 200)")
                    assertTrue(heights.size() <= 200)
                    assertTrue(onScreen != null)
                    // Five hundred messages of 60 KB with three pictures each is about 90 MB
                    // passing through. A leak of even one picture per message would show as
                    // tens of megabytes; the allowance is for the collector's own noise.
                    assertTrue(growth < 12.0, "the heap grew by %.1f MB over 500 opens".format(growth))
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
