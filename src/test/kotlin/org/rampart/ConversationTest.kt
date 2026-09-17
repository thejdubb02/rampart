package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationTest {
    private fun at(id: String) = Summary(id, "Dana", "dana@example.org", "Re: quote", "2026-09-17T09:00:00Z", "", true)
    private val thread = listOf(at("a"), at("b"), at("c"))

    @Test
    fun olderAboveAndNewerBelow() {
        val (earlier, later) = conversationAround(thread, at("b"))
        assertEquals(listOf("a"), earlier.map { it.id })
        assertEquals(listOf("c"), later.map { it.id })
    }

    @Test
    fun theEndsOfTheThread() {
        assertTrue(conversationAround(thread, at("a")).first.isEmpty())
        assertEquals(listOf("b", "c"), conversationAround(thread, at("a")).second.map { it.id })
        assertTrue(conversationAround(thread, at("c")).second.isEmpty())
    }

    /**
     * The one that would look wrong rather than merely empty: while a new message loads,
     * the thread still in hand belongs to the last one.
     */
    @Test
    fun astaleThreadDrawsNothingRatherThanSomebodyElsesConversation() {
        val (earlier, later) = conversationAround(thread, at("z"))
        assertTrue(earlier.isEmpty() && later.isEmpty())
    }

    @Test
    fun aMessageOnItsOwnHasNoConversation() {
        val (earlier, later) = conversationAround(emptyList(), at("a"))
        assertTrue(earlier.isEmpty() && later.isEmpty())
    }
}
