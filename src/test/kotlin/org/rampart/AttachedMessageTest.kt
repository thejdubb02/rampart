package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A forwarded message is a file until somebody opens it, and then it is a message.
 */
class AttachedMessageTest {
    @Test
    fun `a plain message keeps its subject, sender and text`() {
        val mail = readAttachedMessage(
            """
            From: Dana Whitfield <dana@example.org>
            To: justin@example.com
            Subject: The forwarded note
            Date: Thu, 17 Sep 2026 09:00:00 +0000
            MIME-Version: 1.0
            Content-Type: text/plain; charset=UTF-8

            Hello from the attachment.
            """.trimIndent().replace("\n", "\r\n").toByteArray(),
        )
        assertEquals("The forwarded note", mail.subject)
        assertEquals("Dana Whitfield", mail.fromName)
        assertEquals("dana@example.org", mail.fromEmail)
        assertTrue(mail.sentAt?.startsWith("2026-09-17") == true)
        assertEquals("Hello from the attachment.", mail.body.text?.trim())
        assertTrue(mail.attachments.isEmpty())
    }

    @Test
    fun `html is kept and a file on the message can be saved`() {
        val mail = readAttachedMessage(
            """
            From: Dana <dana@example.org>
            Subject: Notes
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="mix"

            --mix
            Content-Type: text/plain; charset=UTF-8

            See the file.
            --mix
            Content-Type: text/html; charset=UTF-8

            <p>See the file.</p>
            --mix
            Content-Type: text/plain; name="notes.txt"
            Content-Disposition: attachment; filename="notes.txt"

            hello file
            --mix--
            """.trimIndent().replace("\n", "\r\n").toByteArray(),
        )
        assertEquals("See the file.", mail.body.text?.trim())
        assertTrue(mail.body.html?.contains("See the file.") == true)
        val file = mail.attachments.single { it.name == "notes.txt" }
        val bytes = mail.partBytes[file.blobId] ?: error("the file has no bytes")
        assertTrue(String(bytes).contains("hello file"))
    }
}
