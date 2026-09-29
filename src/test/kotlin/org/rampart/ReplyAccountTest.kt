package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A reply that leaves from the wrong mailbox is already gone by the time anyone
 * notices. These pin the decision the strip is allowed to make, and the cases
 * where it must stay quiet.
 */
class ReplyAccountTest {
    private val own = listOf("justin@example.org", "billing@willhitestrategy.com")

    @Test
    fun `an exact own address on To is the one it came to`() {
        val note = replyAccountWarning(
            to = listOf("justin@example.org"),
            own = own,
            from = "billing@willhitestrategy.com",
        )
        assertEquals("justin@example.org", note?.cameTo)
        assertEquals("billing@willhitestrategy.com", note?.from)
        assertEquals(
            "This came to justin@example.org. You are sending from billing@willhitestrategy.com.",
            note?.line,
        )
        assertEquals("Send from justin@example.org", note?.switchLabel)
    }

    @Test
    fun `case is not part of the address`() {
        val shouty = replyAccountWarning(
            to = listOf("JUSTIN@EXAMPLE.ORG"),
            own = own,
            from = "billing@willhitestrategy.com",
        )
        assertEquals("justin@example.org", shouty?.cameTo)

        val named = replyAccountWarning(
            to = listOf("Justin Willhite <Justin@Example.org>"),
            own = own,
            from = "billing@willhitestrategy.com",
        )
        assertEquals("justin@example.org", named?.cameTo)

        assertNull(
            replyAccountWarning(
                to = listOf("justin@example.org"),
                own = own,
                from = "Justin@Example.org",
            ),
        )
    }

    @Test
    fun `Cc counts when To names nobody we have`() {
        val note = replyAccountWarning(
            to = listOf("list@example.net"),
            cc = listOf("billing@willhitestrategy.com"),
            own = own,
            from = "justin@example.org",
        )
        assertEquals("billing@willhitestrategy.com", note?.cameTo)
    }

    @Test
    fun `mail that names none of our addresses warns about nothing`() {
        assertNull(
            replyAccountWarning(
                to = listOf("list@example.net"),
                cc = listOf("other@example.net"),
                deliveredTo = listOf("still-not-us@example.net"),
                own = own,
                from = "justin@example.org",
            ),
        )
        assertNull(
            replyAccountWarning(
                to = listOf("justin@example.org"),
                own = emptyList(),
                from = "justin@example.org",
            ),
        )
    }

    @Test
    fun `sending from the address it came to is not a warning`() {
        assertNull(
            replyAccountWarning(
                to = listOf("justin@example.org"),
                cc = listOf("billing@willhitestrategy.com"),
                own = own,
                from = "justin@example.org",
            ),
        )
    }

    @Test
    fun `To wins when Cc is also one of ours`() {
        val note = replyAccountWarning(
            to = listOf("ada@example.net", "justin@example.org"),
            cc = listOf("billing@willhitestrategy.com"),
            deliveredTo = listOf("billing@willhitestrategy.com"),
            own = listOf("billing@willhitestrategy.com", "justin@example.org"),
            from = "ada@example.net",
        )
        assertEquals("justin@example.org", note?.cameTo)
    }

    @Test
    fun `a delivery header counts when To and Cc do not`() {
        val delivered = replyAccountWarning(
            to = listOf("list@example.net"),
            cc = listOf("other@example.net"),
            deliveredTo = listOf("justin@example.org"),
            own = own,
            from = "billing@willhitestrategy.com",
        )
        assertEquals("justin@example.org", delivered?.cameTo)

        val original = replyAccountWarning(
            to = listOf("list@example.net"),
            originalTo = listOf("Billing <billing@willhitestrategy.com>"),
            own = own,
            from = "justin@example.org",
        )
        assertEquals("billing@willhitestrategy.com", original?.cameTo)
    }

    @Test
    fun `the reply keeps the headers the warning reads`() {
        val summary = Summary(
            id = "1",
            from = "Dana Whitfield",
            fromEmail = "dana@example.org",
            subject = "the quote",
            receivedAt = "2026-09-17T09:00:00Z",
            preview = "",
            seen = true,
        )
        val body = Body(
            html = null,
            text = "Numbers attached.",
            to = listOf("justin@example.org"),
            cc = listOf("sam@example.org"),
            deliveredTo = listOf("justin@example.org"),
            originalTo = listOf("billing@willhitestrategy.com"),
        )
        val draft = replyTo(summary, body, "billing@willhitestrategy.com")
        assertEquals(listOf("justin@example.org"), draft.sourceTo)
        assertEquals(listOf("sam@example.org"), draft.sourceCc)
        assertEquals(listOf("justin@example.org"), draft.sourceDeliveredTo)
        assertEquals(listOf("billing@willhitestrategy.com"), draft.sourceOriginalTo)
        assertEquals(
            "justin@example.org",
            replyAccountWarning(
                to = draft.sourceTo,
                cc = draft.sourceCc,
                deliveredTo = draft.sourceDeliveredTo,
                originalTo = draft.sourceOriginalTo,
                own = own,
                from = draft.from,
            )?.cameTo,
        )
    }

    @Test
    fun `Delivered-To and X-Original-To are read off the message`() {
        val body = bodyFromHeaders(
            listOf(
                "To" to "list@example.net",
                "Delivered-To" to "justin@example.org",
                "X-Original-To" to "Billing <billing@willhitestrategy.com>",
            ),
        )
        assertEquals(listOf("justin@example.org"), body.deliveredTo)
        assertEquals(listOf("billing@willhitestrategy.com"), body.originalTo)
    }
}
