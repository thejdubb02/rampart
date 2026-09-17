package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmojiTest {
    @Test
    fun `every group has emoji and no stray blanks`() {
        assertTrue(EMOJI.isNotEmpty())
        EMOJI.forEach { group ->
            assertTrue(group.emoji.isNotEmpty(), "${group.name} was empty")
            assertTrue(group.emoji.none { it.isBlank() }, "${group.name} had a blank entry")
        }
    }

    /* A split that caught the wrong half of a concatenation would leave whole lines in one
     * entry, which renders as a wall of characters in one cell rather than a grid. */
    @Test
    fun `each entry is one emoji rather than a run of them`() {
        EMOJI.flatMap { it.emoji }.forEach {
            assertTrue(it.length <= 8, "not a single emoji: $it")
            assertTrue(!it.contains(' '), "a run rather than one: $it")
        }
    }

    @Test
    fun `the everyday ones are there`() {
        val all = EMOJI.flatMap { it.emoji }.toSet()
        listOf("✅", "👍", "🙏", "⚠️", "🎉").forEach {
            assertTrue(it in all, "$it was missing")
        }
    }

    @Test
    fun `a blank query is everything, because people browse a picker`() {
        assertEquals(EMOJI, emojiFor(""))
        assertEquals(EMOJI, emojiFor("   "))
    }

    @Test
    fun `a query narrows to a group, and a miss shows everything rather than nothing`() {
        assertEquals(listOf("Work"), emojiFor("work").map { it.name })
        assertEquals(listOf("Faces"), emojiFor("FACE").map { it.name })
        assertEquals(EMOJI, emojiFor("nothing like this"))
    }
}
