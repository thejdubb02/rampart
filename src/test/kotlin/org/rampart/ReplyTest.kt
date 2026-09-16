package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplyTest {
    private val message = Summary(
        id = "1",
        from = "Dana Whitfield",
        fromEmail = "dana@example.org",
        subject = "The quote for Tuesday",
        receivedAt = "2026-09-15T17:40:00Z",
        preview = "",
        seen = true,
    )

    @Test
    fun `a reply threads onto the message it answers`() {
        val body = Body(null, "Yes, Tuesday works.", messageId = listOf("<b@example.org>"), references = listOf("<a@example.org>"))
        val draft = replyTo(message, body, "me@example.org")

        assertEquals("<b@example.org>", draft.inReplyTo)
        // References is the conversation so far with the answered message appended, in order.
        assertEquals(listOf("<a@example.org>", "<b@example.org>"), draft.references)
        assertEquals("dana@example.org", draft.to)
        assertEquals("me@example.org", draft.from)
    }

    @Test
    fun `a reply with no Message-ID to quote is still a reply`() {
        // Threading is best effort; what the writer is doing is not.
        val draft = replyTo(message, Body(null, "no headers here"), "me@example.org")
        assertEquals(null, draft.inReplyTo)
        assertTrue(draft.replying)
    }

    @Test
    fun `Re is added once and not again`() {
        val first = replyTo(message, null, "me@example.org")
        assertEquals("Re: The quote for Tuesday", first.subject)

        val second = replyTo(message.copy(subject = "Re: The quote for Tuesday"), null, "me@example.org")
        assertEquals("Re: The quote for Tuesday", second.subject)
    }

    @Test
    fun `the original is quoted, and html is quoted as its text`() {
        val plain = replyTo(message, Body(null, "Line one\nLine two"), "me@example.org")
        assertTrue(plain.body.contains("> Line one"), plain.body)
        assertTrue(plain.body.contains("> Line two"), plain.body)

        val html = replyTo(message, Body("<p>Hello <b>there</b></p>", null), "me@example.org")
        assertTrue(html.body.contains("> Hello there"), html.body)
        assertTrue(!html.body.contains("<p>"), "html markup leaked into the quote")
    }

    @Test
    fun `a forward names the original sender and does not thread`() {
        val body = Body(null, "The quote is attached.", messageId = listOf("<b@example.org>"))
        val draft = forwardOf(message, body, "me@example.org")

        assertEquals("Fwd: The quote for Tuesday", draft.subject)
        assertEquals("", draft.to)
        // Threading a forward onto a conversation the new recipient cannot see is worse
        // than not threading it at all.
        assertEquals(null, draft.inReplyTo)
        assertTrue(draft.references.isEmpty())
        assertTrue(draft.body.contains("dana@example.org"), draft.body)
        assertTrue(draft.body.contains("The quote is attached."), draft.body)
        assertTrue(!draft.replying)
    }

    @Test
    fun `Fwd is added once and not again`() {
        val once = forwardOf(message.copy(subject = "Fwd: The quote for Tuesday"), null, "me@example.org")
        assertEquals("Fwd: The quote for Tuesday", once.subject)
    }

    @Test
    fun `recipients are split on commas and blanks dropped`() {
        val draft = Draft(from = "me@example.org", to = "a@example.org,  b@example.org , ", cc = " c@example.org")
        assertEquals(listOf("a@example.org", "b@example.org", "c@example.org"), draft.recipients)
    }
}

class UpdatesTest {
    @Test
    fun `a newer version is newer, and the same one is not`() {
        assertTrue(Updates.isNewer("0.1.20", "0.1.9"))
        assertTrue(Updates.isNewer("0.2.0", "0.1.99"))
        assertTrue(!Updates.isNewer("0.1.9", "0.1.9"))
        assertTrue(!Updates.isNewer("0.1.8", "0.1.9"))
    }

    @Test
    fun `a tag that is not a number does not crash the app on startup`() {
        assertTrue(!Updates.isNewer("nightly", "0.1.9"))
        assertTrue(Updates.isNewer("0.1.10-rc1", "0.1.9"))
    }
}
