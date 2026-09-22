package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for raw message parser and EML filename generator.
 * Verifies that header unfolding, blank line detection, and safe filename building
 * conform strictly to RFC standards and protect against malicious input.
 */
class EmlTest {

    @Test
    fun `headers end at first empty line for both CRLF and bare LF`() {
        val crlfMsg = "Subject: Hello\r\nDate: Today\r\n\r\nThis is body\r\nMore body"
        val crlfHeaders = headersOf(crlfMsg)
        assertEquals(2, crlfHeaders.size)
        assertEquals(Pair("Subject", "Hello"), crlfHeaders[0])
        assertEquals(Pair("Date", "Today"), crlfHeaders[1])
        assertEquals("This is body\r\nMore body", bodyOf(crlfMsg))

        val lfMsg = "Subject: Hello\nDate: Today\n\nThis is body\nMore body"
        val lfHeaders = headersOf(lfMsg)
        assertEquals(2, lfHeaders.size)
        assertEquals(Pair("Subject", "Hello"), lfHeaders[0])
        assertEquals(Pair("Date", "Today"), lfHeaders[1])
        assertEquals("This is body\nMore body", bodyOf(lfMsg))

        val mixedMsg = "Subject: Hello\r\nDate: Today\n\nThis is body"
        val mixedHeaders = headersOf(mixedMsg)
        assertEquals(2, mixedHeaders.size)
        assertEquals("This is body", bodyOf(mixedMsg))
    }

    @Test
    fun `message with no blank line has only headers and empty body`() {
        val msg = "Subject: Hello\nDate: Today"
        val headers = headersOf(msg)
        assertEquals(2, headers.size)
        assertEquals("Hello", headers[0].second)
        assertEquals("", bodyOf(msg))
    }

    @Test
    fun `folded header belongs to previous header and is joined with a single space`() {
        val msg = "Subject: Hello\n fold\r\n\tone\r\n  two\nDate: Today"
        val headers = headersOf(msg)
        assertEquals(2, headers.size)
        assertEquals("Subject", headers[0].first)
        // Leading space/tab characters are trimmed, and lines are joined with a single space.
        assertEquals("Hello fold one two", headers[0].second)
        assertEquals("Date", headers[1].first)
        assertEquals("Today", headers[1].second)
    }

    @Test
    fun `folded header at the start of raw message does not crash and is skipped`() {
        val msg = "  folded\nSubject: Hello"
        val headers = headersOf(msg)
        assertEquals(1, headers.size)
        assertEquals(Pair("Subject", "Hello"), headers[0])
    }

    @Test
    fun `lines without colon are skipped rather than throwing`() {
        val msg = "From envelope@example.com\nSubject: Hello\nNo colon line\nDate: Today"
        val headers = headersOf(msg)
        assertEquals(2, headers.size)
        assertEquals(Pair("Subject", "Hello"), headers[0])
        assertEquals(Pair("Date", "Today"), headers[1])
    }

    @Test
    fun `header names preserve their original case and multiple identical headers are kept`() {
        val msg = "suBJeCt: First\nSUBJECT: Second\nReceived: One\nReceived: Two"
        val headers = headersOf(msg)
        assertEquals(4, headers.size)
        assertEquals("suBJeCt", headers[0].first)
        assertEquals("First", headers[0].second)
        assertEquals("SUBJECT", headers[1].first)
        assertEquals("Second", headers[1].second)
        assertEquals("Received", headers[2].first)
        assertEquals("One", headers[2].second)
        assertEquals("Received", headers[3].first)
        assertEquals("Two", headers[3].second)
    }

    @Test
    fun `emlName correctly prefixes ISO dates and cuts subjects to sixty characters`() {
        // Standard ISO 8601 instant format
        assertEquals("2026-09-17 Hello.eml", emlName("Hello", "2026-09-17T18:30:00Z"))
        
        // Subject cut to sixty characters
        val longSubject = "a".repeat(100)
        val cutSubject = "a".repeat(60)
        assertEquals("2026-09-17 $cutSubject.eml", emlName(longSubject, "2026-09-17T18:30:00Z"))

        // Blank subject becomes "message"
        assertEquals("2026-09-17 message.eml", emlName("   ", "2026-09-17T18:30:00Z"))

        // Non-ISO 8601 receivedAt leaves prefix blank
        assertEquals("Hello.eml", emlName("Hello", "not-a-date"))
        assertEquals("message.eml", emlName("   ", "not-a-date"))
    }

    @Test
    fun `a forwarded message is named from the subject and the date`() {
        assertEquals(
            "Fwd - the quote - 2026-09-17.eml",
            forwardEmlName("the quote", "2026-09-17T09:00:00Z"),
        )
        assertEquals("Fwd - message - undated.eml", forwardEmlName("   ", "not-a-date"))
        val hostile = forwardEmlName("../../etc/passwd", "2026-09-17T09:00:00Z")
        assertEquals("passwd - 2026-09-17.eml", hostile)
        assertFalse(hostile.contains('/'))
        assertFalse(hostile.contains('\\'))
        val punctuated = forwardEmlName("Hello: <World>*", "2026-09-17T09:00:00Z")
        assertEquals("Fwd - Hello World - 2026-09-17.eml", punctuated)
    }

    @Test
    fun `emlName prevents path traversal and unsafe character escapes`() {
        // Path traversal must not escape the current folder.
        // Because safeFileName strips everything before the last slash, the date is stripped too.
        val traversed = emlName("../../etc/passwd", "2026-09-17T18:30:00Z")
        assertEquals("passwd.eml", traversed)
        assertTrue(!traversed.contains("/"), "Filename contains a slash: $traversed")
        assertTrue(!traversed.contains("\\"), "Filename contains a backslash: $traversed")

        // Windows reserved character removal
        val unsafeChars = emlName("Hello: <World>*", "2026-09-17T18:30:00Z")
        assertEquals("2026-09-17 Hello World.eml", unsafeChars)

        // Windows reserved device names are converted to "attachment"
        val reservedName = emlName("CON", "")
        assertEquals("attachment.eml", reservedName)
    }

    @Test
    fun `all three functions handle empty input, large lines, and invalid text without throwing`() {
        val emptyStr = ""
        val bigLine = "a".repeat(1000000)
        val junkText = "\u0000\u0001\uffff\r\n\t"

        // Empty input checks
        assertEquals(emptyList(), headersOf(emptyStr))
        assertEquals("", bodyOf(emptyStr))
        assertEquals("message.eml", emlName(emptyStr, emptyStr))

        // Big line checks
        assertEquals(emptyList(), headersOf(bigLine))
        assertEquals("", bodyOf(bigLine))
        val bigEml = emlName(bigLine, bigLine)
        assertTrue(bigEml.isNotEmpty())

        // Junk text checks
        headersOf(junkText)
        bodyOf(junkText)
        val junkEml = emlName(junkText, junkText)
        assertTrue(junkEml.isNotEmpty())
    }
}
