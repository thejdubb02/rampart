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

    /** A draft saved as HTML somewhere else still has to be editable here. */
    @Test
    fun anHtmlDraftComesBackAsText() {
        val draft = draftOf(summary, Body("<p>Half a <b>sentence</b></p>", null), "justin@willhitestrategy.com")
        assertEquals("Half a sentence", draft.body.trim())
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
