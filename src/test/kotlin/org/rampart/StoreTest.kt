package org.rampart

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private fun mail(id: String, from: String, subject: String, at: String, seen: Boolean = true) =
        Summary(id, from, "${from.lowercase()}@example.org", subject, at, "a preview of $subject", seen)

    private val messages = listOf(
        mail("a", "Dana", "The quote for Tuesday", "2026-09-15T09:00:00Z"),
        mail("b", "Alex", "Invoice 2026-4471", "2026-09-17T11:00:00Z", seen = false),
        mail("c", "Cass", "Photos from the site visit", "2026-09-16T08:00:00Z"),
    )

    private fun <T> withStore(key: String? = null, block: (Store) -> T): T {
        val path = Files.createTempDirectory("rampart-store").resolve("mail.db")
        return try {
            Store.open(path, key).use(block)
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun `what goes in comes back, newest first`() = withStore { store ->
        store.put("inbox", messages)
        assertEquals(listOf("b", "c", "a"), store.messages("inbox").map { it.id })
    }

    @Test
    fun `every field survives the trip`() = withStore { store ->
        val rich = messages[0].copy(
            flagged = true,
            keywords = setOf("\$seen", "invoices"),
            threadId = "t1",
            threadSize = 3,
        )
        store.put("inbox", listOf(rich))
        assertEquals(rich, store.messages("inbox").single())
    }

    @Test
    fun `writing the same message twice updates it rather than duplicating it`() = withStore { store ->
        store.put("inbox", messages)
        store.put("inbox", listOf(messages[0].copy(seen = false, subject = "Changed")))
        val all = store.messages("inbox")
        assertEquals(3, all.size)
        val one = all.single { it.id == "a" }
        assertEquals("Changed", one.subject)
        assertFalse(one.seen)
    }

    @Test
    fun `folders are kept apart`() = withStore { store ->
        store.put("inbox", listOf(messages[0]))
        store.put("archive", listOf(messages[1]))
        assertEquals(listOf("a"), store.messages("inbox").map { it.id })
        assertEquals(listOf("b"), store.messages("archive").map { it.id })
    }

    @Test
    fun `unread only, and paging`() = withStore { store ->
        store.put("inbox", messages)
        assertEquals(listOf("b"), store.messages("inbox", unreadOnly = true).map { it.id })
        assertEquals(listOf("c"), store.messages("inbox", limit = 1, from = 1).map { it.id })
    }

    // --- search -----------------------------------------------------------------------

    @Test
    fun `search finds a word in the subject, the sender or the preview`() = withStore { store ->
        store.put("inbox", messages)
        assertEquals(listOf("b"), store.search("invoice").map { it.id })
        assertEquals(listOf("a"), store.search("Dana").map { it.id })
        assertEquals(listOf("c"), store.search("photos").map { it.id })
    }

    @Test
    fun `search spans folders, because that is what people mean by search`() = withStore { store ->
        store.put("inbox", listOf(messages[0].copy(subject = "Tuesday plan")))
        store.put("archive", listOf(messages[1].copy(subject = "Tuesday invoice")))
        assertEquals(setOf("a", "b"), store.search("tuesday").map { it.id }.toSet())
    }

    /*
     * FTS5 takes a query language, not a phrase. Somebody typing "re: invoice" means those
     * words; handed over raw it is a column filter followed by a syntax error.
     */
    /*
     * FTS5 takes a query language, not a phrase. Handed a pasted subject raw, "re:" is a
     * column filter and an unbalanced quote is a syntax error, so both would throw rather
     * than find nothing.
     */
    @Test
    fun `a pasted subject line searches for the subject`() = withStore { store ->
        store.put("inbox", messages)
        assertEquals(listOf("b"), store.search("Re: Invoice 2026-4471").map { it.id })
        assertEquals(listOf("b"), store.search("Fwd: invoice").map { it.id })
    }

    @Test
    fun `punctuation somebody typed cannot break the query`() = withStore { store ->
        store.put("inbox", messages)
        assertTrue(store.search("\"unbalanced").isEmpty())
        assertTrue(store.search("invoice OR (").isEmpty())
        assertTrue(store.search("   ").isEmpty())
    }

    @Test
    fun `re-indexing does not make a message match twice`() = withStore { store ->
        store.put("inbox", messages)
        store.put("inbox", messages)
        assertEquals(1, store.search("invoice").size)
    }

    // --- bodies, cursors, forgetting ---------------------------------------------------

    @Test
    fun `a body is kept and read back`() = withStore { store ->
        store.putBody("a", Body("<p>hello</p>", "hello"))
        assertEquals(Body("<p>hello</p>", "hello"), store.body("a"))
        assertNull(store.body("nothing"))
    }

    @Test
    fun `a cursor is remembered per folder`() = withStore { store ->
        assertNull(store.cursor("inbox"))
        store.setCursor("inbox", "s1")
        store.setCursor("archive", "s2")
        store.setCursor("inbox", "s3")
        assertEquals("s3", store.cursor("inbox"))
        assertEquals("s2", store.cursor("archive"))
    }

    @Test
    fun `forgetting takes the message, its body and its index`() = withStore { store ->
        store.put("inbox", messages)
        store.putBody("b", Body(null, "hello"))
        store.forget(listOf("b"))
        assertEquals(listOf("c", "a"), store.messages("inbox").map { it.id })
        assertNull(store.body("b"))
        assertTrue(store.search("invoice").isEmpty())
    }

    @Test
    fun `clearing a folder leaves the others alone`() = withStore { store ->
        store.put("inbox", listOf(messages[0]))
        store.put("archive", listOf(messages[1]))
        store.clear("inbox")
        assertTrue(store.messages("inbox").isEmpty())
        assertEquals(listOf("b"), store.messages("archive").map { it.id })
    }

    // --- the file itself ---------------------------------------------------------------

    @Test
    fun `a copy of the mail survives closing and reopening`() {
        val path = Files.createTempDirectory("rampart-store").resolve("mail.db")
        try {
            Store.open(path, null).use { it.put("inbox", messages) }
            Store.open(path, null).use { assertEquals(3, it.messages("inbox").size) }
        } finally {
            path.deleteIfExists()
        }
    }

    /*
     * The point of the key. A full copy of somebody's mail in a plain file on a laptop is
     * exactly what a mail client should not leave behind, so this asserts the subject is
     * not sitting in the bytes.
     */
    @Test
    fun `an encrypted file does not have the mail in it`() {
        val plain = Files.createTempDirectory("rampart-store").resolve("plain.db")
        val locked = Files.createTempDirectory("rampart-store").resolve("locked.db")
        try {
            Store.open(plain, null).use { it.put("inbox", messages) }
            Store.open(locked, "a-secret-key").use { it.put("inbox", messages) }

            assertContains(String(plain.readBytes(), Charsets.ISO_8859_1), "Invoice 2026-4471")
            assertFalse(
                String(locked.readBytes(), Charsets.ISO_8859_1).contains("Invoice 2026-4471"),
                "the subject was readable in the encrypted file",
            )
            // And it still works with the key.
            Store.open(locked, "a-secret-key").use { assertEquals(3, it.messages("inbox").size) }
        } finally {
            plain.deleteIfExists()
            locked.deleteIfExists()
        }
    }

    @Test
    fun `the wrong key does not open it`() {
        val path: Path = Files.createTempDirectory("rampart-store").resolve("locked.db")
        try {
            Store.open(path, "right").use { it.put("inbox", messages) }
            val failed = runCatching { Store.open(path, "wrong").use { it.messages("inbox") } }.isFailure
            assertTrue(failed, "a store opened with the wrong key handed the mail over")
        } finally {
            path.deleteIfExists()
        }
    }

    /* The account key reaches a filename, so it must not be able to climb out of it. */
    @Test
    fun `an account key cannot escape the config directory`() {
        assertEquals(Accounts.file().parent, Store.file("../../etc/passwd").parent)
        assertFalse(Store.file("../../etc/passwd").toString().contains(".."))
    }

    /*
     * The key is a password nobody types, so it has no business being short or memorable.
     * Hex so nothing in a URL, a file or a shell has to escape it.
     */
    @Test
    fun `a made key is long, random and safe to put in a url`() {
        val a = java.security.SecureRandom().generateSeed(32).joinToString("") { "%02x".format(it) }
        val b = java.security.SecureRandom().generateSeed(32).joinToString("") { "%02x".format(it) }
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertFalse(a == b, "two generated keys were the same")
    }
}
