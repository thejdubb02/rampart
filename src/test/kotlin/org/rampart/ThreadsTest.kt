package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acting on a whole conversation, and the two kinds of message that has to leave alone.
 */
class ThreadsTest {
    private val mine = setOf("justin@example.com")

    private fun note(id: String, from: String, keywords: Set<String> = emptySet()) =
        Summary(id, "Someone", from, "Re: the quote", "2026-09-20T10:00:00Z", "...", true, keywords = keywords)

    private val thread = listOf(
        note("1", "dana@example.org"),
        note("2", "justin@example.com"),
        note("3", "dana@example.org"),
    )

    /*
     * Marking a conversation read does not touch what you wrote. Those copies are in
     * Sent, and "I have read this" is about the mail that arrived.
     */
    @Test
    fun `your own replies are not marked read with the conversation`() {
        assertEquals(listOf("1", "3"), conversationIds(thread, mine))
    }

    @Test
    fun `and the address is matched whatever case it arrived in`() {
        val shouty = thread.map { if (it.id == "2") it.copy(fromEmail = "JUSTIN@EXAMPLE.COM") else it }
        assertEquals(listOf("1", "3"), conversationIds(shouty, mine))
    }

    /** A half-written reply is not mail that arrived, so marking the conversation read leaves it. */
    @Test
    fun `a draft in the conversation is left where it is`() {
        val withDraft = thread + note("4", "dana@example.org", setOf("\$draft"))
        assertEquals(listOf("1", "3"), conversationIds(withDraft, mine))
    }

    @Test
    fun `a conversation nobody has written in is all of it`() {
        assertEquals(listOf("1", "3"), conversationIds(thread.filterNot { it.id == "2" }, emptySet()))
    }

    /*
     * The server's list is the conversation the row stands for. The rows on screen are
     * only a stand-in for when the server cannot say, and an empty answer is still an
     * answer: falling back to those rows is how a click filed messages that had already
     * left the folder.
     */
    @Test
    fun `the server's list is what gets filed, including a reply of yours`() {
        assertEquals(listOf("a", "b"), filingIds("b", "t", listOf("a", "b"), listOf("b", "c")))
        assertEquals(listOf("mine", "theirs"), filingIds("theirs", "t", listOf("mine", "theirs"), emptyList()))
    }

    @Test
    fun `the message that was clicked is filed even when the server left it out`() {
        assertEquals(listOf("a", "b"), filingIds("b", "t", listOf("a"), listOf("b", "c")))
    }

    @Test
    fun `an empty answer from the server does not fall back to the rows on screen`() {
        assertEquals(listOf("b"), filingIds("b", "t", emptyList(), listOf("a", "b", "c")))
    }

    @Test
    fun `when the server cannot say, the open rows are used, or just this message`() {
        assertEquals(listOf("a", "b"), filingIds("b", "t", null, listOf("a", "b")))
        assertEquals(listOf("b"), filingIds("b", "t", null, listOf("a", "c")))
    }

    @Test
    fun `a message with no thread does not use a server list`() {
        assertEquals(listOf("b", "a"), filingIds("a", "", listOf("x"), listOf("b", "a")))
        assertEquals(listOf("a"), filingIds("a", "  ", listOf("x"), listOf("b")))
    }

    /*
     * Thread ids come from a server, and two servers have no reason to agree about them.
     * Without the account in the key, muting a conversation on one account could silence
     * an unrelated one on another.
     */
    @Test
    fun `a muted conversation is remembered per account`() {
        assertTrue(Muted.key("a@one.test", "t1") != Muted.key("a@two.test", "t1"))
    }

    /** A list nothing ever leaves is a file that grows for the life of the install. */
    @Test
    fun `the oldest muted conversations are dropped, never the newest`() {
        val many = (1..600).map { "key$it" }
        val kept = Muted.trim(many)
        assertEquals(500, kept.size)
        assertEquals("key600", kept.last())
        assertTrue("key1" !in kept)
    }
}
