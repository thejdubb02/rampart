package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationTest {
    private fun at(id: String, seen: Boolean = true) =
        Summary(id, "Dana", "dana@example.org", "Re: quote", "2026-09-17T09:00:00Z", "", seen)

    @Test
    fun theOneClickedStartsExpanded() {
        val thread = listOf(at("a"), at("b"), at("c"))
        assertEquals(setOf("b"), initialExpanded(thread, at("b")))
    }

    @Test
    fun unreadMessagesJoinTheOneThatWasClicked() {
        val thread = listOf(at("a"), at("b", seen = false), at("c", seen = false))
        assertEquals(setOf("a", "b", "c"), initialExpanded(thread, at("a")))
    }

    /** Rule 9: a conversation of one message is just that message, already open. */
    @Test
    fun aMessageOnItsOwnStartsExpanded() {
        assertEquals(setOf("a"), initialExpanded(emptyList(), at("a")))
        assertEquals(setOf("a"), initialExpanded(listOf(at("a")), at("a")))
    }

    /**
     * The one that would look wrong rather than merely empty: while a new message loads,
     * the thread still in hand belongs to the last one, so it disagrees about what was
     * opened and there is nothing unread in it either. Expanding nothing at all would be a
     * pane that looks broken rather than a pane that is still catching up.
     */
    @Test
    fun aStaleThreadExpandsTheNewestRatherThanNothing() {
        val thread = listOf(at("a"), at("b"), at("c"))
        assertEquals(setOf("c"), initialExpanded(thread, at("z")))
    }
}
