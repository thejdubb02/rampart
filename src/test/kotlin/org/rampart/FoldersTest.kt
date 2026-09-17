package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FoldersTest {
    private fun box(name: String, role: String? = null, unread: Int = 0) = Mailbox("id-$name", name, role, unread)

    @Test
    fun `the role always wins, whatever anything is called`() {
        val boxes = listOf(box("Archive"), box("Old stuff", role = "archive"))
        assertEquals("Old stuff", folderFor("archive", boxes)?.name)
    }

    @Test
    fun `a folder with no role is found by its name`() {
        // The fault this exists for: one account set the role and the other did not, so
        // Archive appeared on one mailbox and nowhere on the other.
        assertEquals("Archive", folderFor("archive", listOf(box("Inbox", "inbox"), box("Archive")))?.name)
    }

    @Test
    fun `a folder that already has a different role is never taken by name`() {
        // Somebody's own folder called Trash is theirs. Filing mail into it because the
        // server had no Trash would be losing their mail somewhere they did not put it.
        val boxes = listOf(box("Inbox", "inbox"), box("Trash", role = "archive"))
        assertNull(folderFor("trash", boxes))
    }

    @Test
    fun `the usual other names for the same folder are known`() {
        assertEquals("Deleted Items", folderFor("trash", listOf(box("Deleted Items")))?.name)
        assertEquals("Spam", folderFor("junk", listOf(box("Spam")))?.name)
        assertEquals("Sent Mail", folderFor("sent", listOf(box("Sent Mail")))?.name)
    }

    @Test
    fun `case and stray spaces do not stop a match`() {
        assertEquals("  ARCHIVE ", folderFor("archive", listOf(box("  ARCHIVE ")))?.name)
    }

    @Test
    fun `an account that really has no such folder gets null, not a guess`() {
        assertNull(folderFor("archive", listOf(box("Inbox", "inbox"), box("Notes"))))
        assertNull(folderFor("nonsense", listOf(box("Inbox", "inbox"))))
    }

    @Test
    fun `the undo notice counts in words people use`() {
        assertEquals("1 message archived.", movedNotice(1, pastTense("archive")))
        assertEquals("4 messages moved to trash.", movedNotice(4, pastTense("trash")))
        assertEquals("2 messages marked as spam.", movedNotice(2, pastTense("junk")))
    }
}
