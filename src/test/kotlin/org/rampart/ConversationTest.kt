package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationTest {
    private fun at(id: String, seen: Boolean = true) =
        Summary(id, "Dana", "dana@example.org", "Re: quote", "2026-09-17T09:00:00Z", "", seen)

    /** Gmail, Apple Mail and Outlook all open a thread to the one message that was clicked. */
    @Test
    fun onlyTheOneOpenedStartsExpanded() {
        assertEquals(setOf("b"), initialExpanded(at("b")))
    }

    /** Read state plays no part: which message was clicked is the only thing that matters. */
    @Test
    fun unreadDoesNotChangeAnything() {
        assertEquals(setOf("a"), initialExpanded(at("a", seen = false)))
    }
}
