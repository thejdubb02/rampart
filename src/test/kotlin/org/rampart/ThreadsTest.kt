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
     * Sent copies stay in Sent. A conversation archived out of the one folder that holds
     * everything you have written is a folder that no longer does.
     */
    @Test
    fun `your own replies are not filed with the conversation`() {
        assertEquals(listOf("1", "3"), conversationIds(thread, mine))
    }

    @Test
    fun `and the address is matched whatever case it arrived in`() {
        val shouty = thread.map { if (it.id == "2") it.copy(fromEmail = "JUSTIN@EXAMPLE.COM") else it }
        assertEquals(listOf("1", "3"), conversationIds(shouty, mine))
    }

    /** A half-written reply moved to Archive is a draft nobody finds again. */
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
