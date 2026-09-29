package org.rampart

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailImportTest {
    @Test
    fun `mbox splitting preserves body From and removes one quoting layer`() {
        val mbox = """
            From first@example.com Tue Sep 29 10:00:00 2026
            Message-ID: <first@example.com>

            Ordinary body
            From this is a body line
            >From quoted once
            >>From quoted twice
            From second@example.com Tue Sep 29 11:00:00 2026
            Message-ID: <second@example.com>

            Second body
        """.trimIndent()

        val messages = splitMbox(mbox)

        assertEquals(2, messages.size)
        assertTrue(messages[0].contains("From this is a body line"))
        assertTrue(messages[0].contains("From quoted once"))
        assertTrue(messages[0].contains(">From quoted twice"))
        assertTrue(messages[1].contains("Second body"))
    }

    @Test
    fun `maildir info parsing maps standard flags`() {
        val flags = parseMaildirInfo("1700000000.M1.host:2,DFRS").keywords
        assertEquals(setOf("\$draft", "\$flagged", "\$answered", "\$seen"), flags)
    }

    @Test
    fun `dovecot keyword indexes map to maildir letters`() {
        val mapping = parseDovecotKeywordMapping("0 invoices\n2 project-red\n25 someday")
        assertEquals(mapOf('a' to "invoices", 'c' to "project-red", 'z' to "someday"), mapping)
        assertEquals(setOf("\$seen", "invoices", "project-red"), parseMaildirInfo("item:2,Sac", mapping).keywords)
    }

    @Test
    fun `duplicate skipping normalizes message id brackets and case`() {
        val existing = setOf("<message@EXAMPLE.com>")
        assertTrue(shouldSkipDuplicate("<Message@Example.COM>", existing))
        assertFalse(shouldSkipDuplicate("<other@example.com>", existing))
        assertFalse(shouldSkipDuplicate(null, existing))
    }

    @Test
    fun `date choice uses message date then file time`() {
        val header = Instant.parse("2026-09-29T10:00:00Z")
        val file = Instant.parse("2026-09-28T09:00:00Z")
        assertEquals(header, chooseImportDate(header, file))
        assertEquals(file, chooseImportDate(null, file))
    }
}
