package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A signature in the wrong place, or a second copy on a reopened draft, is sent before
 * anyone notices. The quote surviving underneath is the part that is easy to get wrong.
 */
class SignatureTest {
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
        messageId = listOf("<a@x>"),
        references = listOf("<root@x>"),
        to = listOf("justin@willhitestrategy.com"),
        cc = listOf("sam@example.org"),
    )

    @Test
    fun aNewMessageGetsTheSignatureBlock() {
        val draft = Draft(from = "me@example.org")
        assertEquals("\n\n-- \nSig", signed(draft, "Sig").body)
    }

    @Test
    fun aReplyKeepsTheQuotedTextAfterTheSignature() {
        val draft = replyTo(message, body, "justin@willhitestrategy.com")
        val quoted = draft.body
        val signed = signed(draft, "Justin")
        assertEquals("\n\n-- \nJustin$quoted", signed.body)
        assertTrue(signed.body.contains("> Numbers attached."), signed.body)
        assertTrue(signed.body.contains("On "), "the attribution line must still be there")
        assertTrue(
            signed.body.indexOf("-- ") < signed.body.indexOf("> Numbers attached."),
            "the quote has to follow the signature, not precede it",
        )
    }

    @Test
    fun aBlankOrWhitespaceSignatureChangesNothing() {
        val draft = Draft(from = "me@example.org", body = "hello")
        assertEquals(draft, signed(draft, ""))
        assertEquals(draft, signed(draft, "   \n\t"))
    }

    @Test
    fun aDraftThatAlreadyHasASeparatorIsNotSignedTwice() {
        val draft = Draft(from = "me@example.org", body = "Hi\n\n-- \nOld")
        assertEquals(draft, signed(draft, "New"))
        assertEquals(1, draft.body.lineSequence().count { it == "-- " })
        assertEquals(1, signed(draft, "New").body.lineSequence().count { it == "-- " })
    }

    @Test
    fun threadingFieldsAndRecipientsSurviveSigning() {
        val draft = Draft(
            from = "justin@willhitestrategy.com",
            to = "dana@example.org",
            cc = "sam@example.org",
            subject = "Re: the quote",
            body = "\n\nOn Tue, Dana wrote:\n> hello",
            inReplyTo = "<a@x>",
            references = listOf("<root@x>", "<a@x>"),
            replying = true,
        )
        val signed = signed(draft, "Justin")
        assertEquals("<a@x>", signed.inReplyTo)
        assertEquals(listOf("<root@x>", "<a@x>"), signed.references)
        assertEquals("dana@example.org", signed.to)
        assertEquals("sam@example.org", signed.cc)
        assertEquals(listOf("dana@example.org", "sam@example.org"), signed.recipients)
        assertEquals("justin@willhitestrategy.com", signed.from)
        assertTrue(signed.replying)
        assertEquals("Re: the quote", signed.subject)
        assertEquals(draft.attachments, signed.attachments)
    }

    @Test
    fun aSeparatorInsideTheQuoteDoesNotCountAsAlreadySigned() {
        val draft = Draft(from = "me@example.org", body = "\n\n> -- \n> their sign-off")
        val signed = signed(draft, "Justin")
        assertEquals("\n\n-- \nJustin\n\n> -- \n> their sign-off", signed.body)
    }
}
