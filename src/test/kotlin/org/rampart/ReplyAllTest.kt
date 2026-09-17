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
}
