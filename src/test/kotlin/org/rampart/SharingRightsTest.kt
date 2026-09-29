package org.rampart

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three share levels against the JMAP rights and the IMAP ACL letters, in every
 * direction. A mistake here hands somebody more of a mailbox than was meant, so each level
 * is checked to come back as itself and never as a neighbour.
 */
class SharingRightsTest {
    private fun rightsOf(vararg on: MailRight) = MailboxRights(on.toSet())

    @Test
    fun `each level goes out as shareWith and reads back as the same level`() {
        ShareLevel.entries.forEach { level ->
            val sent = level.shareWithValue()
            assertEquals(MailRight.entries.size, sent.size, "Every right is named, so nothing is left to the server.")
            assertEquals(level, ShareLevel.of(MailboxRights.from(sent)))
        }
    }

    @Test
    fun `read is read and nothing else`() {
        val read = ShareLevel.READ.shareWithValue()
        assertEquals(JsonPrimitive(true), read["mayReadItems"])
        // On Stalwart maySetSeen is the same ACL as maySetKeywords, so read must not carry it.
        assertEquals(JsonPrimitive(false), read["maySetSeen"])
        assertEquals(JsonPrimitive(false), read["maySetKeywords"])
        assertEquals(JsonPrimitive(false), read["mayRemoveItems"])
    }

    @Test
    fun `no level ever grants sending as the owner`() {
        ShareLevel.entries.forEach { assertFalse(MailRight.SUBMIT in it.rights, "$it grants maySubmit") }
    }

    @Test
    fun `manage is the only level that can share it on or rename it`() {
        assertTrue(MailRight.SHARE in ShareLevel.MANAGE.rights)
        assertTrue(MailRight.RENAME in ShareLevel.MANAGE.rights)
        assertFalse(MailRight.SHARE in ShareLevel.READ_WRITE.rights)
        assertFalse(MailRight.RENAME in ShareLevel.READ_WRITE.rights)
    }

    @Test
    fun `a mix of rights that is no level reads as custom rather than the nearest level`() {
        // Read and write, plus the right to share it on, is not Manage.
        val mixed = MailboxRights(ShareLevel.READ_WRITE.rights + MailRight.SHARE)
        assertNull(ShareLevel.of(mixed))
        // Nor is read with the right to send.
        assertNull(ShareLevel.of(rightsOf(MailRight.READ_ITEMS, MailRight.SUBMIT)))
        assertNull(ShareLevel.of(MailboxRights.NONE))
    }

    @Test
    fun `myRights is read by name and a missing object means everything`() {
        val some = buildJsonObject {
            put("mayReadItems", true)
            put("mayAddItems", false)
            put("maySetKeywords", true)
            put("someFutureRight", true)
        }
        assertEquals(rightsOf(MailRight.READ_ITEMS, MailRight.SET_KEYWORDS), MailboxRights.from(some))
        assertEquals(MailboxRights.ALL, MailboxRights.from(null))
    }

    @Test
    fun `each level has IMAP letters that read back as that level`() {
        assertEquals("lr", ShareLevel.READ.imapLetters)
        ShareLevel.entries.forEach { assertEquals(it, ShareLevel.ofImap(it.imapLetters)) }
        // Order does not matter to a server, so it must not matter here.
        assertEquals(ShareLevel.READ_WRITE, ShareLevel.ofImap("etiwsrl"))
    }

    @Test
    fun `IMAP letters mean what Stalwart makes of them`() {
        // l alone is Stalwart's Read ACL only; reading messages needs r as well.
        assertFalse(MailRight.READ_ITEMS in imapRights("l"))
        assertTrue(MailRight.READ_ITEMS in imapRights("lr"))
        // s and w are both ModifyItems, which JMAP shows as both maySetSeen and maySetKeywords.
        assertEquals(setOf(MailRight.SET_SEEN, MailRight.SET_KEYWORDS), imapRights("s"))
        assertEquals(setOf(MailRight.SET_SEEN, MailRight.SET_KEYWORDS), imapRights("w"))
        // The obsolete RFC 2086 letters.
        assertTrue(MailRight.REMOVE_ITEMS in imapRights("d"))
        assertTrue(MailRight.CREATE_CHILD in imapRights("c"))
        // No letter grants renaming.
        assertFalse(MailRight.RENAME in imapRights("lrswipkxtea"))
    }

    @Test
    fun `manage over IMAP cannot include renaming, and says so by not matching the JMAP level`() {
        assertFalse(MailRight.RENAME in imapRights(ShareLevel.MANAGE.imapLetters))
        assertEquals(ShareLevel.MANAGE.rights - MailRight.RENAME, imapRights(ShareLevel.MANAGE.imapLetters))
    }

    @Test
    fun `an action the rights do not allow is refused with one sentence`() {
        val readOnly = MailboxRights(ShareLevel.READ.rights)
        assertNotNull(refusal(FolderAction.TAG, readOnly))
        assertNotNull(refusal(FolderAction.TAKE_OUT, readOnly))
        assertNull(refusal(FolderAction.TAG, MailboxRights(ShareLevel.READ_WRITE.rights)))
        FolderAction.entries.forEach { action ->
            val why = refusal(action, MailboxRights.NONE)!!
            assertTrue(why.endsWith("."), why)
            assertEquals(1, why.count { it == '.' }, "One sentence: $why")
        }
    }

    @Test
    fun `the note above a shared folder names what is off, and is absent when nothing is`() {
        assertNull(sharedFolderNote(MailboxRights.ALL))
        assertNull(sharedFolderNote(MailboxRights(ShareLevel.READ_WRITE.rights)))
        assertTrue(sharedFolderNote(MailboxRights(ShareLevel.READ.rights))!!.contains("read only"))
    }
}
