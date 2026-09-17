package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals

class SortingTest {
    private fun mail(id: String, from: String, subject: String, at: String, seen: Boolean = true) =
        Summary(id, from, "$from@example.org", subject, at, "", seen)

    private val emails = listOf(
        mail("a", "Dana", "Re: the quote", "2026-09-15T09:00:00Z"),
        mail("b", "Alex", "Invoice 22", "2026-09-17T11:00:00Z", seen = false),
        mail("c", "Cass", "the quote", "2026-09-16T08:00:00Z"),
    )

    private fun ids(order: Order) = sorted(emails, order).map { it.id }

    @Test
    fun `newest and oldest are each other's reverse`() {
        assertEquals(listOf("b", "c", "a"), ids(Order.NEWEST))
        assertEquals(listOf("a", "c", "b"), ids(Order.OLDEST))
    }

    @Test
    fun `unread first, then newest`() {
        assertEquals(listOf("b", "c", "a"), ids(Order.UNREAD))
    }

    @Test
    fun `by sender, alphabetically`() {
        assertEquals(listOf("b", "c", "a"), ids(Order.SENDER))
    }

    /*
     * The reason to sort by subject at all: "Re: the quote" and "the quote" land next to
     * each other instead of under R and T. Inside the pair it is newest first, like
     * everywhere else.
     */
    @Test
    fun `by subject, ignoring the reply prefix`() {
        assertEquals(listOf("b", "c", "a"), ids(Order.SUBJECT))
    }

    @Test
    fun `prefixes that come off`() {
        assertEquals("the quote", bareSubject("Re: the quote"))
        assertEquals("the quote", bareSubject("RE: Fwd: the quote"))
        assertEquals("the quote", bareSubject("Re[2]: the quote"))
        assertEquals("the quote", bareSubject("  the quote  "))
    }

    @Test
    fun `a subject that merely starts with those letters is left alone`() {
        assertEquals("regarding tuesday", bareSubject("Regarding Tuesday"))
        assertEquals("review this", bareSubject("Review this"))
    }

    @Test
    fun `sorting an empty list is not a special case`() {
        Order.entries.forEach { assertEquals(emptyList(), sorted(emptyList(), it)) }
    }
}
