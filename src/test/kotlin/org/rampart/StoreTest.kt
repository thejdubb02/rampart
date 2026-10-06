package org.rampart

import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertContentEquals
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
    fun `store operations serialize access to their shared connection`() {
        val operations = Store::class.java.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers)
        }
        assertTrue(operations.isNotEmpty())
        assertTrue(
            operations.all { Modifier.isSynchronized(it.modifiers) },
            "unsynchronized operations: ${operations.filterNot { Modifier.isSynchronized(it.modifiers) }.map { it.name }}",
        )
    }

    @Test
    fun `tracked link originals survive the trip`() = withStore { store ->
        val tracked = Tracked("known", "message", "", "dana@example.org", "Report", Instant.EPOCH)
        store.track(tracked, listOf("https://first.example", "https://second.example"))

        assertEquals(
            mapOf(
                ("known" to 0) to "https://first.example",
                ("known" to 1) to "https://second.example",
            ),
            store.trackedLinks(),
        )
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
    fun `one message can belong to two folders`() = withStore { store ->
        store.put("inbox", listOf(messages[0]))
        store.put("important", listOf(messages[0]))
        assertEquals(listOf("a"), store.messages("inbox").map { it.id })
        assertEquals(listOf("a"), store.messages("important").map { it.id })
        store.clear("inbox")
        assertEquals(listOf("a"), store.messages("important").map { it.id })
    }

    @Test
    fun `unread only, and paging`() = withStore { store ->
        store.put("inbox", messages)
        assertEquals(listOf("b"), store.messages("inbox", unreadOnly = true).map { it.id })
        assertEquals(listOf("c"), store.messages("inbox", limit = 1, from = 1).map { it.id })
    }

    @Test
    fun `tag filtering pages after filtering in SQL`() = withStore { store ->
        val many = List(220) { number ->
            mail("tag-$number", "Sender", "Subject $number", "2026-09-17T11:${number % 60}:00Z")
                .copy(keywords = if (number % 2 == 0) setOf("work") else emptySet())
        }
        store.put("inbox", many)

        val secondPage = store.messages("inbox", limit = 10, from = 100, filters = QuickFilters(tagged = true))

        assertEquals(10, secondPage.size)
        assertTrue(secondPage.all { "work" in it.keywords })
    }

    @Test
    fun `keyword counts use normalized rows without changing their totals`() = withStore { store ->
        store.put(
            "inbox",
            listOf(
                messages[0].copy(keywords = setOf("Work", "receipts")),
                messages[1].copy(keywords = setOf("work")),
            ),
        )

        assertEquals(mapOf("receipts" to 1, "Work" to 2), store.keywordCounts())
        store.forget(listOf("a"))
        assertEquals(mapOf("work" to 1), store.keywordCounts())
    }

    @Test
    fun `saved search counts run across every matching row`() = withStore { store ->
        val many = List(150) { number ->
            mail("count-$number", "Sender", "Project $number", "2026-09-17T11:${number % 60}:00Z", seen = false)
        }
        store.put("inbox", many)

        assertEquals(150, store.countUnreadSearch("project"))
        assertEquals(150, store.countUnread("inbox"))
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
    fun `search finds the fetched body beyond its preview`() = withStore { store ->
        store.put("inbox", listOf(messages[0]))
        store.putKept("a", Body("<p>hidden marmalade phrase</p>", null), emptyList(), emptyMap(), null, null, null)
        assertEquals(listOf("a"), store.search("marmalade").map { it.id })
    }

    @Test
    fun `writing a message again replaces its search entry, and forgetting it removes it`() = withStore { store ->
        store.put("inbox", messages)
        store.put("inbox", listOf(messages[1].copy(subject = "Receipt 2026-4471", preview = "a receipt")))
        assertTrue(store.search("invoice").isEmpty())
        assertEquals(listOf("b"), store.search("receipt").map { it.id })
        store.forget(listOf("b"))
        assertTrue(store.search("receipt").isEmpty())
        assertEquals(listOf("a"), store.search("Dana").map { it.id })
    }

    @Test
    fun `a file whose search rows predate rowid keys is rekeyed on opening`() {
        val path = Files.createTempDirectory("rampart-store").resolve("mail.db")
        try {
            Store.open(path, null).use { it.put("inbox", messages) }
            // As an older version left it: search rows at their own rowids, no version mark.
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use {
                    it.execute("CREATE TEMP TABLE old AS SELECT id, sender, subject, body FROM search")
                    it.execute("DELETE FROM search")
                    it.execute("INSERT INTO search (rowid, id, sender, subject, body) SELECT 1000 + rowid, * FROM old")
                    it.execute("INSERT INTO search (id, sender, subject, body) VALUES ('gone', 'x', 'orphan', '')")
                    it.execute("PRAGMA user_version = 0")
                }
            }
            Store.open(path, null).use { store ->
                store.put("inbox", listOf(messages[1].copy(subject = "Receipt 2026-4471", preview = "a receipt")))
                assertTrue(store.search("invoice").isEmpty())
                assertEquals(listOf("b"), store.search("receipt").map { it.id })
                assertTrue(store.search("orphan").isEmpty())
            }
        } finally {
            path.deleteIfExists()
        }
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
    fun `a cached message keeps its headers, files and pictures`() = withStore { store ->
        val rich = Body(
            html = "<p>hi</p>",
            text = "hi",
            messageId = listOf("<m@example>"),
            to = listOf("a@example.com"),
            cc = listOf("b@example.com"),
            replyTo = listOf("c@example.com"),
            size = 42,
        )
        val files = listOf(Attachment("b1", "a.png", "image/png", 3, cid = "logo@x", inline = true))
        // The huge one is first, so a cap that stops at the first miss would drop the logo too.
        val pictures = linkedMapOf(
            "huge" to ByteArray(PICTURE_CACHE_CAP + 1) { 7 },
            "b1" to byteArrayOf(1, 2, 3),
        )
        store.putKept("m", rich, files, pictures, "email-blob", "state-1", "BEGIN:VCALENDAR")
        val back = store.kept("m")
        assertEquals(rich, back?.body)
        assertEquals(rich, store.body("m"))
        assertEquals(files, back?.attachments)
        assertNull(back?.pictures?.get("huge"))
        assertContentEquals(byteArrayOf(1, 2, 3), back?.pictures?.get("b1"))
        assertEquals("email-blob", back?.emailBlobId)
        assertEquals("state-1", back?.mailState)
        assertEquals("BEGIN:VCALENDAR", back?.calendar)
        store.forget(listOf("m"))
        assertNull(store.kept("m"))
        assertNull(store.body("m"))
    }

    @Test
    fun `a failed kept replacement leaves the previous copy whole`() {
        val path = Files.createTempDirectory("rampart-store").resolve("mail.db")
        try {
            Store.open(path, null).use { store ->
                val old = Body("<p>old</p>", "old")
                store.putKept("m", old, emptyList(), mapOf("old" to byteArrayOf(1)), null, null, null)
                DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "CREATE TRIGGER reject_picture BEFORE INSERT ON kept_picture " +
                                "BEGIN SELECT RAISE(ABORT, 'full'); END",
                        )
                    }
                }
                assertTrue(
                    runCatching {
                        store.putKept(
                            "m", Body("<p>new</p>", "new"), emptyList(),
                            mapOf("new" to byteArrayOf(2)), null, null, null,
                        )
                    }.isFailure,
                )
                val kept = store.kept("m")
                assertEquals(old, kept?.body)
                assertContentEquals(byteArrayOf(1), kept?.pictures?.get("old"))
            }
        } finally {
            path.deleteIfExists()
        }
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
    fun `a fresh page drops rows inside its dates and keeps the ones outside`() = withStore { store ->
        store.put(
            "inbox",
            listOf(
                mail("old", "Old", "old", "2026-01-01T00:00:00Z"),
                mail("gone", "Gone", "gone", "2026-09-16T12:00:00Z"),
                mail("kept", "Kept", "kept", "2026-09-16T00:00:00Z"),
                mail("newer", "New", "newer", "2026-09-18T00:00:00Z"),
            ),
        )
        store.put("archive", listOf(mail("other", "Other", "other", "2026-09-16T12:00:00Z")))
        store.pruneToPage(
            "inbox",
            listOf(
                mail("kept", "Kept", "kept", "2026-09-16T00:00:00Z"),
                mail("edge", "Edge", "edge", "2026-09-17T00:00:00Z"),
            ),
        )
        assertEquals(setOf("old", "kept", "newer"), store.messages("inbox", limit = 20).map { it.id }.toSet())
        assertEquals(listOf("other"), store.messages("archive").map { it.id })
    }

    @Test
    fun `opening a picture keeps it when the account is over its cap`() = withStore { store ->
        val body = Body("<p>x</p>", "x")
        val four = byteArrayOf(1, 2, 3, 4)
        store.putKept("a", body, emptyList(), mapOf("p" to four), null, null, null, usedAt = 1_000)
        store.putKept("b", body, emptyList(), mapOf("p" to byteArrayOf(5, 6, 7, 8)), null, null, null, usedAt = 2_000)
        // Reading a marks it used just now, which is newer than b.
        assertContentEquals(four, store.kept("a")?.pictures?.get("p"))
        store.evictPictures(4)
        assertContentEquals(four, store.kept("a")?.pictures?.get("p"))
        assertNull(store.kept("b")?.pictures?.get("p"))
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

    @Test
    fun `tracking rows move from the legacy database`() {
        val directory = Files.createTempDirectory("rampart-store")
        val legacy = directory.resolve("mail-old.db")
        val current = directory.resolve("mail-new.db")
        val tracked = Tracked("token", "message", "account", "reader@example.org", "Subject", Instant.ofEpochMilli(10))
        val fetch = Fetch("token", Instant.ofEpochMilli(20), "Mail", "network")
        try {
            Store.open(legacy, null).use {
                it.track(tracked)
                it.recordFetches(listOf(fetch))
            }

            Store.open(current, null, legacy to null).use {
                val copied = it.tracking().single()
                assertEquals(tracked.copy(account = ""), copied.first)
                assertEquals(listOf(fetch), copied.second)
            }
            assertFalse(Files.exists(legacy))
        } finally {
            directory.toFile().deleteRecursively()
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

    @Test
    fun `account punctuation cannot collide in a cache filename`() {
        assertFalse(Store.file("a.b@example.com@mail.example") == Store.file("ab@example.com@mail.example"))
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

    @Test
    fun `a recipient reply marks the matching tracked message only`() = withStore { store ->
        store.track(Tracked("one", "message-one", "", "susan@example.org", "Audit", Instant.ofEpochMilli(1)))
        store.track(Tracked("two", "message-two", "", "other@example.org", "Other", Instant.ofEpochMilli(2)))
        assertEquals(
            listOf("one"),
            store.markReplied("susan@example.org", listOf("message-one"), Instant.ofEpochMilli(10)),
        )
        val rows = store.tracking().associateBy { it.first.id }
        assertEquals(Instant.ofEpochMilli(10), rows.getValue("one").first.repliedAt)
        assertNull(rows.getValue("two").first.repliedAt)
    }

    @Test
    fun `joins and splits are kept across closing and opening the store`() {
        val path = Files.createTempDirectory("rampart-store").resolve("mail.db")
        val joined = Rethreading().joined(listOf("tA", "tB")).split("m1")
        try {
            Store.open(path, null).use { store ->
                assertEquals(Rethreading(), store.rethreading())
                store.setRethreading(joined)
            }
            Store.open(path, null).use { store ->
                assertEquals(joined, store.rethreading())
                // Replaced rather than added to, so an undo that puts fewer back leaves fewer.
                store.setRethreading(Rethreading())
                assertEquals(Rethreading(), store.rethreading())
            }
        } finally {
            path.deleteIfExists()
        }
    }

    /** A file from before joins existed gains the table when it is opened, and keeps its mail. */
    @Test
    fun `a store file made before joins existed takes them on open`() {
        val path = Files.createTempDirectory("rampart-store").resolve("mail.db")
        try {
            Store.open(path, null).use { it.put("inbox", messages) }
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { it.execute("DROP TABLE rethread") }
            }
            Store.open(path, null).use { store ->
                assertEquals(3, store.messages("inbox").size)
                assertEquals(Rethreading(), store.rethreading())
                val joined = Rethreading().joined(listOf("tA", "tB"))
                store.setRethreading(joined)
                assertEquals(joined, store.rethreading())
            }
        } finally {
            path.deleteIfExists()
        }
    }
}
