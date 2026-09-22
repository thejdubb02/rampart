package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reply all is the feature with the worst failure mode in a mail client: the mistakes are
 * sent before they are noticed, and they go to everyone.
 */
class ReplyAllTest {
    @Test
    fun `a pin makes Reply address everyone`() {
        assertTrue(bareReplyAll(pinned = true, others = false))
        assertTrue(bareReplyAll(pinned = true, others = true))
    }

    @Test
    fun `without a pin Reply stays a reply to the sender`() {
        assertFalse(bareReplyAll(pinned = false, others = true))
        assertFalse(bareReplyAll(pinned = false, others = false))
    }

    private val message = Summary(
        id = "1",
        from = "Dana Whitfield",
        fromEmail = "dana@example.org",
        subject = "the quote",
        receivedAt = "2026-09-17T09:00:00Z",
        preview = "",
        seen = true,
    )
    private val body = Body(
        html = null,
        text = "Numbers attached.",
        to = listOf("justin@willhitestrategy.com", "Alex@Example.org"),
        cc = listOf("sam@example.org", "billing@willhitestrategy.com"),
    )
    private val mine = setOf("justin@willhitestrategy.com", "billing@willhitestrategy.com")

    @Test
    fun replyGoesOnlyToTheSender() {
        val draft = replyTo(message, body, "justin@willhitestrategy.com")
        assertEquals("dana@example.org", draft.to)
        assertEquals("", draft.cc, "a plain reply never copies the room")
    }

    @Test
    fun replyAllKeepsEveryoneButUs() {
        val draft = replyTo(message, body, "justin@willhitestrategy.com", all = true, mine = mine)
        assertEquals("dana@example.org, Alex@Example.org", draft.to)
        assertEquals("sam@example.org", draft.cc)
        assertTrue(mine.none { it in draft.recipients }, "our own addresses must not be written to")
    }

    /** A different spelling of the same address is the same address. */
    @Test
    fun theSameAddressIsNotWrittenTwice() {
        val shouty = body.copy(to = listOf("DANA@example.org", "alex@example.org"), cc = listOf("ALEX@example.org"))
        val draft = replyTo(message, shouty, "justin@willhitestrategy.com", all = true, mine = mine)
        assertEquals("dana@example.org, alex@example.org", draft.to)
        assertEquals("", draft.cc)
    }

    @Test
    fun answeringYourOwnMessageStillHasSomewhereToGo() {
        val ownMessage = message.copy(fromEmail = "justin@willhitestrategy.com")
        val draft = replyTo(ownMessage, Body(null, "", to = listOf("justin@willhitestrategy.com")),
                            "justin@willhitestrategy.com", all = true, mine = mine)
        assertEquals("justin@willhitestrategy.com", draft.to)
    }

    @Test
    fun theButtonIsOfferedOnlyWhenItWouldDoSomethingDifferent() {
        assertTrue(hasOtherRecipients(message, body, mine))
        val justUs = Body(null, "", to = listOf("justin@willhitestrategy.com"))
        assertFalse(hasOtherRecipients(message, justUs, mine), "one-to-one mail gets no Reply all")
        assertFalse(hasOtherRecipients(message, null, mine), "nothing loaded yet is not a reason to offer it")
        val backToSender = Body(null, "", to = listOf("dana@example.org", "justin@willhitestrategy.com"))
        assertFalse(hasOtherRecipients(message, backToSender, mine), "the sender is already on a plain reply")
    }

    @Test
    fun theThreadingHeadersSurviveReplyAll() {
        val threaded = body.copy(messageId = listOf("<a@x>"), references = listOf("<root@x>"))
        val draft = replyTo(message, threaded, "justin@willhitestrategy.com", all = true, mine = mine)
        assertEquals("<a@x>", draft.inReplyTo)
        assertEquals(listOf("<root@x>", "<a@x>"), draft.references)
    }

    @Test
    fun `a Reply-To is answered instead of the From address`() {
        // A mailing list, a ticketing system and a no-reply sender all set this header, and
        // all three are cases where answering the From address reaches nobody.
        val list = body.copy(replyTo = listOf("list@example.org"))
        assertEquals("list@example.org", replyTo(message, list, "justin@willhitestrategy.com").to)
    }

    @Test
    fun `Reply all keeps everyone, with the Reply-To first`() {
        val list = body.copy(replyTo = listOf("list@example.org"))
        val draft = replyTo(message, list, "justin@willhitestrategy.com", all = true, mine = mine)

        // The sender's own address is deliberately not carried over: they asked to be
        // answered somewhere else, and the list already reaches them.
        assertEquals("list@example.org, Alex@Example.org", draft.to)
        assertEquals("sam@example.org", draft.cc)
    }

    @Test
    fun `an empty Reply-To falls back to the sender`() {
        val blank = body.copy(replyTo = listOf("", "   "))
        assertEquals("dana@example.org", replyTo(message, blank, "justin@willhitestrategy.com").to)
    }

    @Test
    fun `a plus tag does not make an address someone else`() {
        // Mail to justin+invoices@ is mail to justin@, so it is dropped from Reply all the
        // same way, and Reply all is not offered on a message that only reached you twice.
        val tagged = body.copy(
            to = listOf("justin+invoices@willhitestrategy.com"),
            cc = listOf("justin@willhitestrategy.com"),
        )
        val draft = replyTo(message, tagged, "justin@willhitestrategy.com", all = true, mine = mine)

        assertEquals("dana@example.org", draft.to)
        assertEquals("", draft.cc)
        assertFalse(hasOtherRecipients(message, tagged, mine))
    }

    @Test
    fun `the address the sender wrote is the address that is used`() {
        // Matching strips a plus tag; sending never does. Answering a list address that
        // carries one and dropping it would deliver somewhere the sender never named.
        val tagged = body.copy(replyTo = listOf("support+ticket-4182@example.org"))
        assertEquals(
            "support+ticket-4182@example.org",
            replyTo(message, tagged, "justin@willhitestrategy.com").to,
        )
    }
}
