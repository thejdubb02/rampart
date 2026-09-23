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
