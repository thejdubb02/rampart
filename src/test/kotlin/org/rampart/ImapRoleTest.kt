package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which folder is Sent, on a protocol with no such concept until 2010.
 *
 * The declaration wins and the name is a last resort, and our own server is the argument
 * for why: it calls them "Deleted Items", "Junk Mail" and "Sent Items", so a client that
 * matched on "Trash" and "Spam" would have found neither.
 */
class ImapRoleTest {
    @Test
    fun `the server's own declaration is taken over the name`() {
        assertEquals("trash", roleFor("Deleted Items", listOf("\\HasNoChildren", "\\Trash")))
        assertEquals("junk", roleFor("Junk Mail", listOf("\\Junk")))
        assertEquals("sent", roleFor("Sent Items", listOf("\\Sent")))
        assertEquals("archive", roleFor("Archive", listOf("\\Archive")))
    }

    @Test
    fun `a declaration beats a name that says something else`() {
        // A folder somebody made and called Sent is not the Sent folder, and the server
        // saying \Archive is the only thing that settles it.
        assertEquals("archive", roleFor("Sent", listOf("\\Archive")))
    }

    @Test
    fun `case and spacing in an attribute do not matter`() {
        assertEquals("drafts", roleFor("whatever", listOf("\\drafts")))
        assertEquals("sent", roleFor("  Sent Mail  ", emptyList()))
    }

    @Test
    fun `a server that declares nothing falls back to the name`() {
        assertEquals("inbox", roleFor("INBOX", emptyList()))
        assertEquals("junk", roleFor("Spam", emptyList()))
        assertEquals("trash", roleFor("Bin", listOf("\\HasNoChildren")))
    }

    @Test
    fun `an ordinary folder has no role, and is not guessed into one`() {
        assertNull(roleFor("Clients", emptyList()))
        assertNull(roleFor("Sent invoices", emptyList()))
        assertNull(roleFor("", emptyList()))
    }

    @Test
    fun `a folder called Junk Mail is the junk folder`() {
        // What our own server calls it. It declares the role as well, so nothing was broken,
        // but a server that declares nothing would have left Junk with no way out of it.
        assertEquals("junk", roleFor("Junk Mail", emptyList()))
        assertEquals("junk", roleFor("junk mail", emptyList()))
    }
}
