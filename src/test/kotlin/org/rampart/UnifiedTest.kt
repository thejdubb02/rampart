package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnifiedTest {
    private fun at(id: String, when_: String) = Summary(
        id = id, from = "Someone", fromEmail = "someone@example.org", subject = id,
        receivedAt = when_, preview = "", seen = true,
    )

    @Test
    fun `two inboxes come out interleaved, newest first`() {
        val out = merged(
            mapOf(
                "a" to listOf(at("a2", "2026-09-15T09:00:00Z"), at("a1", "2026-09-13T09:00:00Z")),
                "b" to listOf(at("b1", "2026-09-14T09:00:00Z")),
            ),
        )
        assertEquals(listOf("a2", "b1", "a1"), out.map { it.id })
    }

    @Test
    fun `every message remembers which account it came from`() {
        val out = merged(mapOf("a" to listOf(at("a1", "2026-09-15T09:00:00Z"))))
        assertEquals("a", out.single().account)
    }

    @Test
    fun `an account already written on a message is replaced by the one it was filed under`() {
        val stale = at("x", "2026-09-15T09:00:00Z").copy(account = "wrong")
        assertEquals("right", merged(mapOf("right" to listOf(stale))).single().account)
    }

    @Test
    fun `the merged list is capped and keeps the newest`() {
        val many = (1..40).map { at("m$it", "2026-09-%02dT09:00:00Z".format((it % 28) + 1)) }
        val out = merged(mapOf("a" to many), limit = 5)
        assertEquals(5, out.size)
        assertTrue(out.zipWithNext().all { (a, b) -> a.receivedAt >= b.receivedAt })
    }

    @Test
    fun `no accounts at all is an empty list, not a crash`() {
        assertTrue(merged(emptyMap()).isEmpty())
    }

    @Test
    fun `the pseudo folder is never mistaken for a real one`() {
        assertEquals(ALL_ACCOUNTS, allInboxes(3).id)
        assertEquals(3, allInboxes(3).unread)
    }
}
