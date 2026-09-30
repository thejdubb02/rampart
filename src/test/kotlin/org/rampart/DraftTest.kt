package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reopening a draft has to give back what was written, not an approximation of it. */
class DraftTest {
    private val summary = Summary(
        id = "d1",
        from = "Justin Willhite",
        fromEmail = "justin@willhitestrategy.com",
        subject = "Tuesday",
        receivedAt = "2026-09-17T09:00:00Z",
        preview = "",
        seen = true,
    )

    @Test
    fun aSavedDraftComesBackAsItWasWritten() {
        val draft = draftOf(
            summary,
            Body(null, "Half a sentence", to = listOf("dana@example.org"), cc = listOf("alex@example.org")),
            "justin@willhitestrategy.com",
        )
        assertEquals("dana@example.org", draft.to)
        assertEquals("alex@example.org", draft.cc)
        assertEquals("Tuesday", draft.subject)
        assertEquals("Half a sentence", draft.body)
        assertEquals("justin@willhitestrategy.com", draft.from)
    }

    /** Formatting in the HTML part has to survive reopening, or the next save flattens it. */
    @Test
    fun anHtmlDraftKeepsItsFormatting() {
        val html = "<p>Half a <b>sentence</b></p>"
        val draft = draftOf(summary, Body(html, null), "justin@willhitestrategy.com")
        assertEquals(html, draft.html)
        assertTrue(draft.body.contains("**sentence**"), draft.body)
        assertTrue(!draft.body.contains("<b>"), draft.body)
    }

    @Test
    fun htmlParagraphsAndLinksOpenAsParagraphsAndAVisibleLink() {
        val html = "<p>Hi Susan,</p><p>Visit <a href=\"https://example.org\">our site</a>.</p>"
        val draft = draftOf(summary, Body(html, null), "justin@willhitestrategy.com")
        assertEquals("Hi Susan,\n\nVisit [our site](https://example.org).", draft.body)
        assertEquals("Hi Susan,\n\nVisit our site.", displayMarkup(draft.body).text.text)
        assertTrue(markupToHtml(draft.body).contains("<a href=\"https://example.org\">our site</a>"))
        assertEquals("<p>Hi Susan,</p><p>Visit <a href=\"https://example.org\">our site</a>.</p>", markupToHtml(draft.body))
    }

    @Test
    fun divsAndDoubleBreaksOpenAsSeparateParagraphs() {
        assertEquals("One\n\nTwo", htmlToMarkup("<div>One</div><div>Two</div>"))
        assertEquals("One\n\nTwo", htmlToMarkup("<div>One<br><br>Two</div>"))
    }

    @Test
    fun aMarkedHtmlSignatureSeparatorSurvivesRestore() {
        val html = "<div>Hello</div><div>-- </div><div class=\"signature\" data-signature>Justin</div>"
        val draft = draftOf(summary, Body(html, null), "justin@willhitestrategy.com")
        assertTrue(hasSignOff(draft.body), draft.body)
    }

    @Test
    fun aReopenedDraftKeepsAttachmentsAndThreadingHeaders() {
        val file = Attachment("b1", "quote.pdf", "application/pdf", 1200)
        val logo = Attachment("b2", "logo.png", "image/png", 40, cid = "logo", inline = true)
        val draft = draftOf(
            summary,
            Body(
                html = null,
                text = "See attached.",
                references = listOf("<root@x>"),
                inReplyTo = listOf("<parent@x>"),
            ),
            "justin@willhitestrategy.com",
            listOf(file, logo),
        )
        assertEquals(listOf(file), draft.attachments)
        assertEquals("<parent@x>", draft.inReplyTo)
        assertEquals(listOf("<root@x>"), draft.references)
    }

    /**
     * Opening is not an edit. A draft that already carries its sign-off must come back
     * equal to the draft the composer remembers, or the autosave writes it straight away.
     */
    @Test
    fun anUntouchedReopenedDraftIsNotRewritten() {
        val from = "justin@willhitestrategy.com"
        val html = "<div>Hello</div><div><b>Justin</b></div>"
        val text = "Hello\n\n-- \nJustin"
        val opened = draftOf(summary.copy(fromEmail = from), Body(html, text), from)
        val identity = Identity("1", "Justin", from, "Other", "<p>Other</p>")
        val initial = draftOpening(opened, listOf(identity), aboveQuote = true)
        assertEquals(opened, initial)
        assertEquals("Justin", opened.textSignature)
        assertTrue(opened.htmlSignature.contains("<b>Justin</b>"), opened.htmlSignature)
        assertTrue(opened.body.contains("-- \nJustin"), opened.body)
    }

    /**
     * The shape of a draft the mailbox MCP tool writes: text/plain, a greeting and two
     * paragraphs, then the identity's sign-off after a "-- " line, with no HTML part at all.
     * Reopening it and editing only the top must not touch the sign-off, and the text that
     * actually goes out has to still carry it.
     */
    @Test
    fun aPlainTextDraftKeepsItsSignatureThroughOpenEditAndSend() {
        val from = "justin@willhitestrategy.com"
        val signature = "Justin Willhite\nWillhite Strategy Group"
        val text = "Hi Bonnie,\n\nPara one.\n\nPara two.\n\nBest regards,\n\n-- \n$signature"
        val opened = draftOf(summary, Body(null, text), from)
        val identity = Identity("1", "Justin", from, signature, "")
        val initial = draftOpening(opened, listOf(identity), aboveQuote = false)
        assertEquals(signature, initial.textSignature)

        val edited = initial.copy(body = initial.body.replace("Para one.", "Para one, edited."))
        val outgoing = markupToPlain(edited.body)
        assertTrue(outgoing.contains("Best regards,"), outgoing)
        assertEquals(1, Regex(Regex.escape(signature)).findAll(outgoing).count(), outgoing)
    }

    /** Reopening a signed draft is not an edit: the identity's own copy must not join it. */
    @Test
    fun aReopenedPlainDraftIsNotSignedTwiceWhenTheIdentitysSignatureMatches() {
        val from = "justin@willhitestrategy.com"
        val signature = "Justin Willhite\nWillhite Strategy Group"
        val text = "Hi Bonnie,\n\nThanks.\n\n-- \n$signature"
        val opened = draftOf(summary, Body(null, text), from)
        val identity = Identity("1", "Justin", from, signature, "")
        val initial = draftOpening(opened, listOf(identity), aboveQuote = false)
        assertEquals(text, initial.body, "opening must not rewrite what was already signed")
        assertEquals(signature, initial.textSignature)
        assertEquals(1, initial.body.lineSequence().count { it == "-- " })
    }

    /**
     * The identity's signature on the server can change after the draft was written. The
     * draft keeps the one it was actually written with, the same way a reopened HTML draft
     * does ([anUntouchedReopenedDraftIsNotRewritten]): swapping in whatever the identity says
     * now would rewrite a message the account holder already signed off on.
     */
    @Test
    fun aReopenedPlainDraftKeepsItsOwnSignatureWhenTheIdentitysHasChanged() {
        val from = "justin@willhitestrategy.com"
        val written = "Justin Willhite\nWillhite Strategy Group"
        val text = "Hi Bonnie,\n\nThanks.\n\n-- \n$written"
        val opened = draftOf(summary, Body(null, text), from)
        val identity = Identity("1", "Justin", from, "Justin Willhite\nWillhite Strategy Group\nNew tagline", "")
        val initial = draftOpening(opened, listOf(identity), aboveQuote = false)
        assertEquals(text, initial.body, "the draft's own sign-off is not swapped for the identity's current one")
        assertEquals(written, initial.textSignature)
        assertEquals(1, initial.body.lineSequence().count { it == "-- " })
    }

    @Test
    fun aDraftIsNotAReply() {
        val draft = draftOf(summary, Body(null, ""), "justin@willhitestrategy.com")
        assertNull(draft.inReplyTo, "a stored draft carries no In-Reply-To of its own")
        assertTrue(!draft.replying)
    }

    /**
     * The autosave only fires on a change, and this is the comparison it uses. A Draft that
     * did not compare by value would save on every keystroke, or never.
     */
    @Test
    fun anUntouchedDraftEqualsWhatItStartedAs() {
        val opened = replyTo(summary, Body(null, "hello"), "justin@willhitestrategy.com")
        assertEquals(opened, opened.copy())
        assertTrue(opened != opened.copy(body = opened.body + "x"))
    }
}
