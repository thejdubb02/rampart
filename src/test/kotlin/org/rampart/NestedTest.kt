package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NestedTest {
    private fun box(id: String, name: String, parent: String? = null, role: String? = null) =
        Mailbox(id, name, role, 0, parent)

    @Test
    fun `inbox leads, then everything else by name`() {
        val boxes = listOf(box("z", "Zebra"), box("a", "Apples"), box("i", "Inbox", role = "inbox"))
        assertEquals(listOf("Inbox", "Apples", "Zebra"), nested(boxes).map { it.mailbox.name })
    }

    @Test
    fun `children sit under their parent, in order`() {
        val boxes = listOf(
            box("c", "Clients"),
            box("c2", "Blossom", parent = "c"),
            box("c1", "American Tank", parent = "c"),
            box("w", "Work"),
        )
        assertEquals(
            listOf("Clients" to 0, "American Tank" to 1, "Blossom" to 1, "Work" to 0),
            nested(boxes).map { it.mailbox.name to it.depth },
        )
    }

    @Test
    fun `nesting goes as deep as the server does`() {
        val boxes = listOf(
            box("a", "One"),
            box("b", "Two", parent = "a"),
            box("c", "Three", parent = "b"),
        )
        assertEquals(listOf(0, 1, 2), nested(boxes).map { it.depth })
    }

    /* Losing a folder because its parent was filtered out is worse than misplacing it. */
    @Test
    fun `a folder whose parent is missing is still drawn`() {
        val boxes = listOf(box("orphan", "Orphan", parent = "gone"))
        assertEquals(listOf("Orphan" to 0), nested(boxes).map { it.mailbox.name to it.depth })
    }

    /* No server should send a loop. One might, and it must not hang the sidebar. */
    @Test
    fun `a parent loop is broken, not followed`() {
        val boxes = listOf(box("a", "A", parent = "b"), box("b", "B", parent = "a"))
        val out = nested(boxes)
        assertEquals(2, out.size)
        assertEquals(setOf("A", "B"), out.map { it.mailbox.name }.toSet())
    }

    @Test
    fun `an empty list is not a special case`() {
        assertEquals(emptyList(), nested(emptyList()))
    }

    @Test
    fun `the server's roles decide what cannot be deleted`() {
        assertTrue(isProtected(box("i", "Inbox", role = "inbox")))
        assertTrue(isProtected(box("t", "Trash", role = "trash")))
        // A folder somebody named Archive that the server does not treat as one is theirs.
        assertFalse(isProtected(box("x", "Archive")))
        assertFalse(isProtected(box("y", "Clients", role = "")))
    }
}

class ArchiveBucketTest {
    @Test
    fun `by year and by month come off the message's own date`() {
        assertEquals("2026", archiveBucket("2026-09-17T11:00:00Z", "year"))
        assertEquals("2026-09", archiveBucket("2026-09-17T11:00:00Z", "month"))
    }

    @Test
    fun `off means the Archive folder itself`() {
        assertEquals(null, archiveBucket("2026-09-17T11:00:00Z", ""))
        assertEquals(null, archiveBucket("2026-09-17T11:00:00Z", "something else"))
    }

    /* A server that sends a date we cannot read must not create a folder called "unkn". */
    @Test
    fun `an unreadable date files into Archive rather than a nonsense folder`() {
        assertEquals(null, archiveBucket("unknown", "year"))
        assertEquals(null, archiveBucket("", "month"))
        assertEquals(null, archiveBucket("2026/09/17", "month"))
    }
}
