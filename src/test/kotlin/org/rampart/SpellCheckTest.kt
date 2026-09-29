package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What the composer refuses to mark, and how a suggestion lands.
 *
 * The checker itself is exercised once, on a sentence with one misspelling. Everything
 * else is the plain text around that call: quotes, addresses, the dictionary, the edit.
 */
class SpellCheckTest {
    @Test
    fun `a quoted reply is skipped, including the line that introduces it`() {
        val text = "See you soon.\n\nOn Tuesday, Ada wrote:\n> This is a mistke.\n> See you."
        val masked = kept(text)
        assertEquals(text.length, masked.length)
        assertTrue(masked.contains("See you soon."))
        assertFalse(masked.contains("wrote"))
        assertFalse(masked.contains("Tuesday"))
        assertFalse(masked.contains("mistke"))
        assertFalse(masked.contains("See you."))
    }

    @Test
    fun `a quoted line is skipped and the writer's own line is not`() {
        val text = "Hello there\n> quoted word\nThanks"
        val masked = kept(text)
        assertTrue(masked.contains("Hello there"))
        assertTrue(masked.contains("Thanks"))
        assertFalse(masked.contains("quoted"))
    }

    @Test
    fun `a greater-than sign inside a sentence is not a quote`() {
        val text = "Use 1 > 0 here"
        assertEquals(text, kept(text))
    }

    @Test
    fun `a forwarded message is skipped and the note above it is not`() {
        val text = "My note.\n\n---------- Forwarded message ----------\nFrom: Ada\n\nThis is a mistke."
        val masked = kept(text)
        assertTrue(masked.startsWith("My note."))
        assertFalse(masked.contains("Forwarded"))
        assertFalse(masked.contains("Ada"))
        assertFalse(masked.contains("mistke"))
    }

    @Test
    fun `a sign-off under a forward is still checked`() {
        val text = "My note.\n\n---------- Forwarded message ----------\nFrom: Ada\n\nOriginal.\n\n-- \nJustin"
        val masked = kept(text)
        assertTrue(masked.contains("My note."))
        assertTrue(masked.contains("Justin"))
        assertFalse(masked.contains("Original"))
        assertFalse(masked.contains("Ada"))
    }

    @Test
    fun `a link and an email are skipped and the words around them are not`() {
        val text = "See https://example.com/a. and me@example.com now."
        val masked = kept(text)
        val urlStart = text.indexOf("https")
        val urlEnd = text.indexOf("/a.") + 2
        val mailStart = text.indexOf("me@")
        val mailEnd = mailStart + "me@example.com".length
        assertEquals(text.length, masked.length)
        assertTrue(masked.startsWith("See "))
        assertTrue(masked.substring(urlStart, urlEnd).all { it == ' ' })
        assertEquals('.', masked[urlEnd])
        assertTrue(masked.substring(mailStart, mailEnd).all { it == ' ' })
        assertTrue(masked.contains(" and "))
        assertTrue(masked.contains(" now."))
    }

    @Test
    fun `a dictionary word is skipped without swallowing a longer one`() {
        val text = "Rampart and ramparts."
        val masked = kept(text, listOf("rampart"))
        assertEquals(text.length, masked.length)
        assertFalse(masked.contains("Rampart"))
        assertTrue(masked.contains("ramparts"))
        assertTrue(masked.contains(" and "))
        val phrase = kept("Thank you and thanks.", listOf("thank you"))
        assertFalse(phrase.contains("Thank"))
        assertTrue(phrase.contains("thanks"))
    }

    @Test
    fun `masking keeps the length and leaves the other characters where they were`() {
        val text = "Hello mistke."
        val masked = maskSkipped(text, listOf(6..11))
        assertEquals(text.length, masked.length)
        assertEquals("Hello ", masked.take(6))
        assertEquals('.', masked.last())
        assertTrue(masked.substring(6, 12).all { it == ' ' })
    }

    @Test
    fun `the dictionary is stored in one order`() {
        assertEquals(
            listOf("hello there", "Rampart"),
            canonicalDictionary(listOf("  Rampart ", "hello   there", "rampart")),
        )
        val stored = canonicalDictionary(listOf("  Rampart ", "hello   there", "rampart")).joinToString("\n")
        assertEquals("hello there\nRampart", stored)
        assertNull(dictionaryProblem(stored))
        assertNull(dictionaryProblem(""))
        assertNotNull(dictionaryProblem("Rampart\nhello there"))
        assertNotNull(dictionaryProblem("Rampart\n"))
        assertNotNull(dictionaryProblem(" Rampart"))
        assertNotNull(dictionaryProblem("hello  there"))
    }

    @Test
    fun `a suggestion replaces only its range`() {
        assertEquals("This is a mistake.", applySuggestion("This is a mistke.", 10..15, "mistake"))
    }

    @Test
    fun `a suggestion outside the text leaves it unchanged`() {
        val text = "This is a mistke."
        assertEquals(text, applySuggestion(text, 20..25, "mistake"))
        assertEquals(text, applySuggestion(text, -1..3, "x"))
    }

    @Test
    fun `a real check finds the misspelling`() = runBlocking {
        val text = "This is a mistke."
        val issue = assertNotNull(SpellCheck.check(text).firstOrNull { found ->
            found.kind == IssueKind.SPELLING && text.substring(found.range) == "mistke"
        })
        assertTrue(issue.suggestions.any { it.equals("mistake", ignoreCase = true) })
    }

    private fun kept(text: String, dictionary: Collection<String> = emptyList()): String =
        maskSkipped(text, spansToSkip(text, dictionary))
}
