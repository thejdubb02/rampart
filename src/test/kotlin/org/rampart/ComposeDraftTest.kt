package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposeDraftTest {
    private fun turn(from: String, text: String) = Turn(from, "22 Sep", text)

    @Test
    fun `system prompt has defensive data framing`() {
        val prompt = ComposeDraft.system().lowercase()
        assertTrue(prompt.contains("data") && prompt.contains("never") && prompt.contains("instruction"))
    }

    @Test
    fun `user packet builds with only description if reply context empty`() {
        val out = ComposeDraft.user("Write a thank you email", emptyList())
        assertEquals("Draft Description: Write a thank you email", out)
    }

    @Test
    fun `user packet contains reply context if present`() {
        val out = ComposeDraft.user("Say yes", listOf(turn("John", "Are you coming?")))
        assertTrue(out.contains("Draft Description: Say yes"))
        assertTrue(out.contains("Reply Context:"))
        assertTrue(out.contains("John (22 Sep):"))
        assertTrue(out.contains("Are you coming?"))
    }

    @Test
    fun `reply context is trimmed to fit budget`() {
        val longText = "a".repeat(10_000)
        val turns = (1..6).map { turn("User$it", longText) }
        val out = ComposeDraft.user("Rewrite this", turns)
        assertTrue(out.length <= ComposeDraft.BUDGET)
        assertTrue(out.contains("User6"))
        assertTrue(!out.contains("User1"))
    }

    @Test
    fun `single reply context message over budget is cut`() {
        val giantText = "b".repeat(ComposeDraft.BUDGET * 2)
        val out = ComposeDraft.user("A short description", listOf(turn("Alice", giantText)))
        assertTrue(out.length <= ComposeDraft.BUDGET)
        assertTrue(out.contains("Alice"))
        assertTrue(out.endsWith("..."))
    }
}
