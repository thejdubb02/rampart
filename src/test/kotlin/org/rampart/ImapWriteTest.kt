package org.rampart

import jakarta.mail.Flags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decisions IMAP write and body-read make without a server: keyword to flags, and
 * the header half of a [Body].
 */
class ImapWriteTest {

    @Test
    fun `protocol keywords become system flags, not user flags of the same name`() {
        assertEquals(Flags(Flags.Flag.SEEN), flagsFor("\$seen"))
        assertEquals(Flags(Flags.Flag.FLAGGED), flagsFor("\$flagged"))
        assertEquals(Flags(Flags.Flag.DRAFT), flagsFor("\$draft"))
        assertEquals(Flags(Flags.Flag.ANSWERED), flagsFor("\$answered"))
        // A client that compared without folding case would set a user flag named $SEEN
        // and leave the message unread.
        assertEquals(Flags(Flags.Flag.SEEN), flagsFor("\$SEEN"))
        assertTrue(flagsFor("\$seen").userFlags.isEmpty())
    }

    @Test
    fun `an unknown keyword is a user flag, including one that starts with a dollar`() {
        assertEquals(Flags("receipt"), flagsFor("receipt"))
        assertEquals(Flags("\$Forwarded"), flagsFor("\$Forwarded"))
        // Case is the caller's. Lowercasing a user flag would store a different tag than
        // the one the rest of the mailbox already uses.
        assertEquals(Flags("Receipt"), flagsFor("Receipt"))
        assertTrue(flagsFor("receipt").systemFlags.isEmpty())
        assertTrue(flagsFor("\$deleted").systemFlags.isEmpty())
    }

    @Test
    fun `a folded header is one value, not a new hop per line`() {
        val body = bodyFromHeaders(
            listOf(
                "Received" to "from a.example\r\n\tby mx.example with ESMTP\n id ABC",
                "Authentication-Results" to "mx.example;\r\n dkim=pass header.d=x.test",
            ),
        )
        assertEquals(
            listOf("from a.example by mx.example with ESMTP id ABC"),
            body.received,
        )
        assertEquals(
            listOf("mx.example; dkim=pass header.d=x.test"),
            body.authenticationResults,
        )
    }

    @Test
    fun `repeated headers keep the order the server returned them`() {
        val body = bodyFromHeaders(
            listOf(
                "Received" to "from newest.example",
                "Received" to "from older.example",
                "Authentication-Results" to "mx.example; dkim=pass",
                "Authentication-Results" to "relay.example; dkim=fail",
            ),
        )
        assertEquals(listOf("from newest.example", "from older.example"), body.received)
        assertEquals(
            listOf("mx.example; dkim=pass", "relay.example; dkim=fail"),
            body.authenticationResults,
        )
    }

    @Test
    fun `a header that is absent is empty, not a blank stand-in`() {
        val body = bodyFromHeaders(emptyList())
        assertEquals(emptyList(), body.received)
        assertEquals(emptyList(), body.authenticationResults)
        assertEquals(emptyList(), body.messageId)
        assertEquals(emptyList(), body.references)
        assertEquals(emptyList(), body.to)
        assertEquals(emptyList(), body.cc)
        assertNull(body.listUnsubscribe)
        assertNull(body.listUnsubscribePost)
        assertNull(body.spamStatus)
        assertNull(body.receiptTo)
        assertNull(body.sentAt)
        assertNull(body.html)
        assertNull(body.text)
        assertEquals(0L, body.size)
    }

    @Test
    fun `header names match without regard to case`() {
        val body = bodyFromHeaders(
            listOf(
                "received" to "from a.example",
                "message-id" to "<id@x>",
                "x-spam-status" to "No, score=-0.1 required=5.0",
                MDN_HEADER.lowercase() to "dana@example.org",
            ),
        )
        assertEquals(listOf("from a.example"), body.received)
        assertEquals(listOf("id@x"), body.messageId)
        assertEquals("No, score=-0.1 required=5.0", body.spamStatus)
        assertEquals("dana@example.org", body.receiptTo)
    }

    @Test
    fun `To and Cc keep addresses and drop display names`() {
        val body = bodyFromHeaders(
            listOf(
                "To" to "Justin <justin@willhitestrategy.com>, Sam <sam@example.org>",
                "Cc" to "Billing <billing@willhitestrategy.com>",
            ),
        )
        assertEquals(
            listOf("justin@willhitestrategy.com", "sam@example.org"),
            body.to,
        )
        assertEquals(listOf("billing@willhitestrategy.com"), body.cc)
    }

    @Test
    fun `Message-ID and References split into the ids a reply has to quote`() {
        val body = bodyFromHeaders(
            listOf(
                "Message-ID" to "<new@x>",
                "References" to "<root@x> <parent@x>",
            ),
        )
        assertEquals(listOf("new@x"), body.messageId)
        assertEquals(listOf("root@x", "parent@x"), body.references)
    }

    @Test
    fun `folded References still produce every id`() {
        val body = bodyFromHeaders(
            listOf("References" to "<root@x>\r\n <parent@x>"),
        )
        assertEquals(listOf("root@x", "parent@x"), body.references)
    }

    @Test
    fun `Date becomes an instant, and a junk Date is missing rather than a crash`() {
        val dated = bodyFromHeaders(listOf("Date" to "Thu, 17 Sep 2026 09:00:00 +0000"))
        assertEquals("2026-09-17T09:00:00Z", dated.sentAt)
        assertNull(bodyFromHeaders(listOf("Date" to "not a date")).sentAt)
    }

    @Test
    fun `unsubscribe and spam headers are kept raw`() {
        val body = bodyFromHeaders(
            listOf(
                "List-Unsubscribe" to "<https://x.test/u>, <mailto:leave@x.test>",
                "List-Unsubscribe-Post" to "List-Unsubscribe=One-Click",
                "X-Spam-Status" to "Yes, score=12.0 required=5.0",
            ),
        )
        assertEquals("<https://x.test/u>, <mailto:leave@x.test>", body.listUnsubscribe)
        assertEquals("List-Unsubscribe=One-Click", body.listUnsubscribePost)
        assertEquals("Yes, score=12.0 required=5.0", body.spamStatus)
    }

    @Test
    fun `an id never keeps its angle brackets, whichever header it came from`() {
        // The JMAP backend hands these back bare, because RFC 8621 strips the brackets on
        // the way in. A backend that kept them would thread correctly on one account and
        // not on the other, with nothing reporting a fault.
        val body = bodyFromHeaders(
            listOf(
                "Message-ID" to "<new@x>",
                "References" to "<root@x> <parent@x>",
            ),
        )
        (body.messageId + body.references).forEach {
            assertTrue('<' !in it && '>' !in it, "kept its brackets: $it")
        }
    }

    @Test
    fun `an id written without brackets is taken as it stands`() {
        assertEquals(listOf("loose@x"), messageIdsIn("loose@x"))
        assertEquals(listOf("loose@x"), messageIdsIn("<loose@x>"))
    }

    @Test
    fun `an id carries the folder it lives in, because a UID alone does not`() {
        val id = imapId(4231L, "INBOX")
        assertEquals(4231L, uidOf(id))
        assertEquals("INBOX", folderOf(id))
    }

    @Test
    fun `a folder name containing the hierarchy separator survives the round trip`() {
        // The split is on the first space and a UID is always digits, so a slash in the
        // name is ordinary rather than something to escape.
        val id = imapId(7L, "Archive/2026/Clients")
        assertEquals(7L, uidOf(id))
        assertEquals("Archive/2026/Clients", folderOf(id))
    }

    @Test
    fun `a folder name containing a space keeps all of it`() {
        val id = imapId(12L, "Deleted Items")
        assertEquals(12L, uidOf(id))
        assertEquals("Deleted Items", folderOf(id))
    }

    @Test
    fun `an id from the other backend opens nothing rather than the wrong thing`() {
        // A JMAP id is an opaque string with no space in it. Read as an IMAP id it would
        // otherwise become UID 0 in the folder named "", and folder "" is the server root.
        assertEquals(-1L, uidOf("Mabcdef123"))
        assertEquals("", folderOf("Mabcdef123"))
        assertEquals(emptyMap(), byFolder(listOf("Mabcdef123")))
    }

    @Test
    fun `a selection spanning folders is grouped, not sent to one of them`() {
        val grouped = byFolder(
            listOf(imapId(1L, "INBOX"), imapId(2L, "Sent Items"), imapId(3L, "INBOX")),
        )
        assertEquals(setOf("INBOX", "Sent Items"), grouped.keys)
        assertEquals(2, grouped.getValue("INBOX").size)
    }

    @Test
    fun `the same UID in two folders is two different messages`() {
        // The reason the id is composite at all: a UID is unique inside a folder and
        // nowhere else, so 4231 in the inbox and 4231 in Sent are unrelated.
        val grouped = byFolder(listOf(imapId(4231L, "INBOX"), imapId(4231L, "Sent Items")))
        assertEquals(2, grouped.size)
    }
}
