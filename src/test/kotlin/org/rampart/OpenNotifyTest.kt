package org.rampart

import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A notification for an open that was not a person, or for the same open twice, is worse
 * than no notification. These pin the two decisions that keep that from happening: which
 * fetches are worth saying, and the one line they collapse into.
 */
class OpenNotifyTest {
    private val sent = Instant.parse("2026-09-18T10:00:00Z")

    private fun tracked(
        id: String,
        recipient: String = "someone@example.com",
        subject: String = "The quote",
    ) = Tracked(id, "m-$id", "", recipient, subject, sent)

    private fun fetch(id: String, after: Long = 3600, agent: String = "Mozilla/5.0 Chrome/140") =
        Fetch(id, sent.plusSeconds(after), agent, "203.0.113.0/24")

    @Test
    fun `a fetch that classifies as automatic is not announced`() {
        val row = tracked("a")
        val known = mapOf("a" to row)
        // Two seconds after sending is delivery, even from a real browser.
        assertTrue(opensToAnnounce(listOf(fetch("a", after = 2)), known).isEmpty())
        // A proxy is a machine however long afterwards it happens.
        assertTrue(opensToAnnounce(listOf(fetch("a", after = 4000, agent = "GoogleImageProxy")), known).isEmpty())
        // Unclear is not a person either, and must not be guessed into a popup.
        assertTrue(opensToAnnounce(listOf(fetch("a", agent = "")), known).isEmpty())
    }

    @Test
    fun `a fetch with no tracked row is not announced`() {
        val orphan = fetch("missing")
        assertTrue(opensToAnnounce(listOf(orphan), emptyMap()).isEmpty())
        assertTrue(opensToAnnounce(listOf(orphan), mapOf("other" to tracked("other"))).isEmpty())
    }

    @Test
    fun `one open names who and which message`() {
        val row = tracked("a")
        val reads = opensToAnnounce(listOf(fetch("a")), mapOf("a" to row))
        assertEquals(listOf(row), reads)
        assertEquals("Opened by someone@example.com" to "The quote", openText(reads))
        assertEquals("(no subject)", openText(listOf(row.copy(subject = " ")))!!.second)
    }

    @Test
    fun `several reads in one poll collapse into one notification`() {
        val rows = listOf(
            tracked("a", "someone@example.com", "The quote"),
            tracked("b", "other@example.com", "Invoice"),
            tracked("c", "third@example.com", "Photos"),
            tracked("d", "fourth@example.com", "The keys"),
        )
        // Handed in reverse, so the body naming the earliest three is the function's
        // order and not just the order the poll happened to return.
        val fetches = listOf(fetch("d", 9000), fetch("c", 8000), fetch("b", 5000), fetch("a", 4000))
        val reads = opensToAnnounce(fetches, rows.associateBy { it.id })
        assertEquals(listOf("a", "b", "c", "d"), reads.map { it.id })
        assertEquals(
            "4 messages opened" to
                "someone@example.com: The quote, other@example.com: Invoice, third@example.com: Photos",
            openText(reads),
        )
        assertNull(openText(emptyList()))
    }

    /** The same message fetched twice in one poll is one open, not two. */
    @Test
    fun `one message opened twice is still one notification`() {
        val row = tracked("a")
        val reads = opensToAnnounce(listOf(fetch("a", 3600), fetch("a", 7200)), mapOf("a" to row))
        assertEquals(listOf(row), reads)
        assertEquals("Opened by someone@example.com" to "The quote", openText(reads))
    }

    /**
     * The notification path is handed only what [Store.recordFetches] inserted. A fetch it
     * ignored as a duplicate is not in that list, so it cannot be announced again.
     */
    @Test
    fun `a duplicate fetch never reaches the notification`() {
        val path = Files.createTempDirectory("rampart-open-notify").resolve("mail.db")
        try {
            Store.open(path, null).use { store ->
                val row = tracked("a")
                val other = tracked("b", "other@example.com", "Invoice")
                store.track(row)
                store.track(other)
                val read = fetch("a")
                assertEquals(listOf(read), store.recordFetches(listOf(read)))

                val again = store.recordFetches(listOf(read))
                assertTrue(again.isEmpty(), "an ignored duplicate must not be handed to the notification")
                assertNull(openText(opensToAnnounce(again, store.trackedByIds(again.map { it.id }))))

                val later = fetch("a", after = 7200)
                val inserted = store.recordFetches(listOf(read, later))
                assertEquals(listOf(later), inserted, "only the row that was new comes back")
                assertEquals(
                    "Opened by someone@example.com" to "The quote",
                    openText(opensToAnnounce(inserted, store.trackedByIds(inserted.map { it.id }))),
                )
                // The lookup is the ids that were asked for, not the whole history.
                assertEquals(setOf("a"), store.trackedByIds(listOf("a")).keys)
                assertTrue(store.trackedByIds(emptyList()).isEmpty())
            }
        } finally {
            path.deleteIfExists()
        }
    }
}
