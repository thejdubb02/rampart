package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncTest {
    private fun row(id: String, account: String = "") = Summary(
        id = id,
        from = "Dana",
        fromEmail = "dana@example.org",
        subject = id,
        receivedAt = "2026-09-16T00:00:00Z",
        preview = "",
        seen = false,
        account = account,
    )

    @Test
    fun `a page keeps rows outside its dates and drops missing ones inside`() {
        val cached = listOf(
            DatedId("old", "2026-01-01T00:00:00Z"),
            DatedId("missing", "2026-09-16T12:00:00Z"),
            DatedId("kept", "2026-09-16T00:00:00Z"),
            DatedId("newer", "2026-09-18T00:00:00Z"),
        )
        val page = listOf(
            DatedId("kept", "2026-09-16T00:00:00Z"),
            DatedId("edge", "2026-09-17T00:00:00Z"),
        )
        assertEquals(listOf("missing"), idsMissingFromPage(cached, page))
        // An empty page has no range. Dropping the cache here would hide mail
        // the server was never asked about.
        assertEquals(emptyList(), idsMissingFromPage(cached, emptyList()))
    }

    @Test
    fun `a merged read change goes to each account's own inbox`() {
        val writes = seenCacheWrites(
            unified = true,
            account = "one",
            folderId = "*all*",
            changed = listOf(row("m", "one"), row("m", "two")),
            inboxId = mapOf("one" to "in-1", "two" to "in-2")::get,
        )
        assertEquals(setOf("one" to "in-1", "two" to "in-2"), writes.map { it.account to it.mailbox }.toSet())
        assertTrue(writes.none { it.mailbox == "*all*" || it.account == "*all*" })
        assertEquals(listOf("m"), writes.single { it.account == "two" }.ids)
    }

    @Test
    fun `a folder view writes the change into that folder`() {
        val writes = seenCacheWrites(
            unified = false,
            account = "one",
            folderId = "archive-9",
            changed = listOf(row("m")),
            inboxId = { "in-1" },
        )
        assertEquals(listOf(MailWrite("one", "archive-9", listOf("m"))), writes)
    }

    @Test
    fun `a refused id throws and names how many`() {
        val refused = Json.parseToJsonElement(
            """{"updated":{"a":null},"notUpdated":{"b":{"type":"forbidden"}},"newState":"s2"}""",
        ).jsonObject
        val error = assertFailsWith<JmapError> {
            requireApplied(refused, listOf("a", "b"), "updated", "notUpdated", "update")
        }
        assertTrue("1" in error.message.orEmpty())

        val destroyed = Json.parseToJsonElement(
            """{"destroyed":["a"],"notDestroyed":{"b":{"type":"notFound"},"c":{"type":"notFound"}}}""",
        ).jsonObject
        val deleted = assertFailsWith<JmapError> {
            requireApplied(destroyed, listOf("a", "b", "c"), "destroyed", "notDestroyed", "delete")
        }
        assertTrue("2" in deleted.message.orEmpty())
    }

    @Test
    fun `every id that landed is not a failure`() {
        val ok = Json.parseToJsonElement(
            """{"updated":{"a":null,"b":null},"newState":"s2"}""",
        ).jsonObject
        assertEquals("s2", requireApplied(ok, listOf("a", "b"), "updated", "notUpdated", "update"))
    }

    @Test
    fun `picture eviction drops the least recently used and keeps the rest`() {
        val rows = listOf(
            PictureUse("a", "p", 10, used = 1),
            PictureUse("b", "p", 10, used = 5),
            PictureUse("c", "p", 10, used = 3),
        )
        assertEquals(listOf("a" to "p"), picturesToEvict(rows, 20))
        assertEquals(emptyList(), picturesToEvict(rows, 30))
        assertEquals(listOf("a" to "p", "c" to "p"), picturesToEvict(rows, 10))
    }

    @Test
    fun `junk and deleted are left out of search unless that folder is open`() {
        assertEquals(listOf("j", "t"), foldersToSkip(null, "j", "t"))
        assertEquals(listOf("t"), foldersToSkip("junk", "j", "t"))
        assertEquals(listOf("j"), foldersToSkip("trash", "j", "t"))
        assertEquals(emptyList(), foldersToSkip("inbox", null, null))
    }
}
