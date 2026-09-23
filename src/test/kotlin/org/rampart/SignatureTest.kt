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

    /**
     * Below the quote, not above it. That is what Justin's webmail is already set to
     * (`signaturePosition = "below_quote"`), and it is what lets the HTML half of a message
     * swap the plain sign-off for its formatted version by taking it off the end.
     */
    @Test
    fun theSignOffGoesOnTheEnd() {
        val draft = Draft(from = "me@example.org", body = "Sounds good.\n\n> their words")
        assertEquals("Sounds good.\n\n> their words\n\n-- \nSig", signed(draft, "Sig").body)
    }

    @Test
    fun aReplyKeepsTheQuotedTextAfterTheSignature() {
        val draft = replyTo(message, body, "justin@willhitestrategy.com")
        val signed = signed(draft, "Justin")
        assertEquals(draft.body.trimEnd() + "\n\n-- \nJustin", signed.body)
        assertTrue(signed.body.contains("> Numbers attached."), signed.body)
        assertTrue(signed.body.contains("On "), "the attribution line must still be there")
        assertTrue(
            signed.body.indexOf("> Numbers attached.") < signed.body.indexOf("\n-- "),
            "the sign-off follows the quote, which is where this mailbox is set to put it",
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
        assertEquals("\n\n> -- \n> their sign-off\n\n-- \nJustin", signed.body)
    }

    @Test
    fun aSeparatorInsideAForwardDoesNotCountAsAlreadySigned() {
        val original = Body(null, "Please see this.\n\n-- \nDana")
        val forward = forwardOf(message, original, "me@example.org")
        val signed = signed(forward, "Justin", aboveQuote = true)
        assertTrue(signed.body.contains("-- \nJustin"), signed.body)
        assertTrue(
            signed.body.indexOf("-- \nJustin") < signed.body.indexOf("Forwarded message"),
            signed.body,
        )
        assertTrue(signed.body.contains("-- \nDana"), signed.body)
    }

    @Test
    fun aSeparatorInsideAReplyQuoteDoesNotCountAsAlreadySigned() {
        val original = Body(null, "Please see this.\n\n-- \nDana")
        val reply = replyTo(message, original, "me@example.org")
        val signed = signed(reply, "Justin", aboveQuote = true)
        assertTrue(signed.body.contains("-- \nJustin"), signed.body)
        assertTrue(signed.body.indexOf("-- \nJustin") < signed.body.indexOf("wrote:"), signed.body)
        assertTrue(signed.body.contains("> -- "), signed.body)
    }

    @Test
    fun changingFromSwapsTheSignOffInPlace() {
        val old = Identity("1", "Ada", "ada@example.com", "Thanks", "<p>Thanks</p>")
        val next = Identity("2", "Bea", "bea@example.com", "Cheers", "<p>Cheers</p>")
        val draft = signed(
            Draft(from = "ada@example.com", body = "Hello\n\nOn Tue, Dana wrote:\n> hi"),
            "Thanks",
            "<p>Thanks</p>",
            aboveQuote = true,
        )
        val swapped = withFrom(draft, "bea@example.com", listOf(old, next), aboveQuote = false)
        assertEquals("bea@example.com", swapped.from)
        assertTrue(swapped.body.contains("-- \nCheers"), swapped.body)
        assertTrue(!swapped.body.contains("-- \nThanks"), swapped.body)
        assertTrue(swapped.body.indexOf("-- \nCheers") < swapped.body.indexOf("wrote:"), swapped.body)
        assertEquals("<p>Cheers</p>", swapped.htmlSignature)
    }

    @Test
    fun anEditedSignOffIsLeftAloneWhenFromChanges() {
        val old = Identity("1", "Ada", "ada@example.com", "Thanks", "<p>Thanks</p>")
        val next = Identity("2", "Bea", "bea@example.com", "Cheers", "<p>Cheers</p>")
        val draft = signed(Draft(from = "ada@example.com", body = "Hello"), "Thanks", "<p>Thanks</p>")
        val edited = draft.copy(body = draft.body.replace("Thanks", "Thanks!!"))
        val swapped = withFrom(edited, "bea@example.com", listOf(old, next), aboveQuote = true)
        assertEquals("bea@example.com", swapped.from)
        assertEquals(edited.body, swapped.body)
        assertTrue(!swapped.body.contains("Cheers"), swapped.body)
    }

    private val reply = Draft(
        from = "me@example.org",
        body = "Tuesday works.\n\nOn 15 Sep 2026, Dana Whitfield wrote:\n> Is Tuesday any good?",
    )

    @Test
    fun aSignOffCanGoAboveTheQuote() {
        val signed = signed(reply, "Justin", aboveQuote = true)
        assertEquals(
            "Tuesday works.\n\n-- \nJustin\n\nOn 15 Sep 2026, Dana Whitfield wrote:\n> Is Tuesday any good?",
            signed.body,
        )
    }

    @Test
    fun aSignOffBelowTheQuoteIsStillWhatHappensByDefault() {
        assertEquals(
            "Tuesday works.\n\nOn 15 Sep 2026, Dana Whitfield wrote:\n> Is Tuesday any good?\n\n-- \nJustin",
            signed(reply, "Justin").body,
        )
    }

    @Test
    fun aForwardIsQuotedToo() {
        val forward = Draft(
            from = "me@example.org",
            body = "\n\n---------- Forwarded message ----------\nFrom: Dana <dana@example.org>\n",
        )
        // The two blank lines the draft opens with stay above it, which is where the cursor
        // goes and where the writing happens.
        assertEquals(
            "\n\n-- \nJustin\n\n---------- Forwarded message ----------\nFrom: Dana <dana@example.org>\n",
            signed(forward, "Justin", aboveQuote = true).body,
        )
    }

    @Test
    fun aNewMessageHasNoQuoteToGoAboveOf() {
        // Nothing to be above, so the setting makes no difference rather than misfiring.
        val fresh = Draft(from = "me@example.org", body = "Morning.")
        assertEquals(signed(fresh, "Justin").body, signed(fresh, "Justin", aboveQuote = true).body)
    }

    @Test
    fun theHtmlHalfPutsTheSignOffBackWhereTheTextOneWas() {
        val signed = signed(reply, "Justin", "<p><b>Justin</b></p>", aboveQuote = true)
        val html = htmlBodyOf(signed.body, signed.textSignature, signed.htmlSignature)!!

        // Above the quote in both halves, or the recipient sees the same message signed in
        // two different places depending on which part their client shows.
        assertTrue(html.indexOf("<b>Justin</b>") < html.indexOf("Dana Whitfield wrote:"))
        assertTrue(!html.contains("-- "))
    }

    @Test
    fun theHtmlHalfKeepsTheTextSignOffWhenThereIsNoHtmlOne() {
        // It used to be cut out and never put back, so the HTML part went out unsigned
        // while the text part carried a sign-off.
        val signed = signed(reply.copy(body = "*Tuesday* works."), "Justin")
        val html = htmlBodyOf(signed.body, signed.textSignature, "")!!
        assertTrue(html.contains("Justin"))
    }
}
