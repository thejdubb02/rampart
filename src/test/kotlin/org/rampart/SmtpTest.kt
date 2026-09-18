package org.rampart

import jakarta.mail.Session
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates SMTP configuration, header generation, and the exact nesting structure of
 * nested MIME parts without connecting to a socket.
 */
class SmtpTest {

    private val session = Session.getInstance(Properties())
    private val identity = Identity("id1", "Alice", "alice@example.com")

    @Test
    fun `the port decides TLS`() {
        val props587 = propertiesFor("mail.example.com", 587)
        assertEquals("true", props587.getProperty("mail.smtp.starttls.enable"))
        assertEquals("true", props587.getProperty("mail.smtp.starttls.required"))
        assertEquals("true", props587.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertEquals("smtp", props587.getProperty("mail.transport.protocol"))
        assertEquals("mail.example.com", props587.getProperty("mail.smtp.host"))
        assertEquals("587", props587.getProperty("mail.smtp.port"))
        assertEquals("true", props587.getProperty("mail.smtp.auth"))
        assertEquals("15000", props587.getProperty("mail.smtp.connectiontimeout"))
        assertEquals("30000", props587.getProperty("mail.smtp.timeout"))
        assertEquals(null, props587.getProperty("mail.smtp.ssl.enable"))

        val props465 = propertiesFor("mail.example.com", 465)
        assertEquals("true", props465.getProperty("mail.smtp.ssl.enable"))
        assertEquals("true", props465.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertEquals("smtp", props465.getProperty("mail.transport.protocol"))
        assertEquals("mail.example.com", props465.getProperty("mail.smtp.host"))
        assertEquals("465", props465.getProperty("mail.smtp.port"))
        assertEquals("true", props465.getProperty("mail.smtp.auth"))
        assertEquals("15000", props465.getProperty("mail.smtp.connectiontimeout"))
        assertEquals("30000", props465.getProperty("mail.smtp.timeout"))
        assertEquals(null, props465.getProperty("mail.smtp.starttls.enable"))
        assertEquals(null, props465.getProperty("mail.smtp.starttls.required"))
    }

    @Test
    fun `a port nobody planned for still refuses to send the password in the clear`() {
        // The failure this guards is silent: without it a server on 25, or on a port
        // somebody chose themselves, gets the password in plaintext and nothing says so.
        listOf(25, 2525, 1587).forEach { port ->
            val properties = propertiesFor("mail.example.com", port)
            assertEquals("true", properties.getProperty("mail.smtp.starttls.required"), "port $port")
        }
    }

    @Test
    fun `an id that already has its brackets does not get a second pair`() {
        val draft = Draft(from = "a@example.com", to = "b@example.com", inReplyTo = "<already@example.com>")
        val message = buildMessage(session, draft, identity, emptyList())
        assertEquals("<already@example.com>", message.getHeader("In-Reply-To")?.firstOrNull())
    }

    @Test
    fun `a draft with HTML produces alternative multipart with text and html`() {
        val draft = Draft(
            from = "alice@example.com",
            to = "bob@example.com",
            subject = "Hello",
            body = "This is **bold** text.",
        )
        val message = buildMessage(session, draft, identity, emptyList())

        val content = message.content
        assertTrue(content is MimeMultipart)
        assertEquals("alternative", content.contentType.substringBefore(';').substringAfter('/').lowercase())
        assertEquals(2, content.count)

        val textPart = content.getBodyPart(0) as MimeBodyPart
        val htmlPart = content.getBodyPart(1) as MimeBodyPart

        assertTrue(textPart.isMimeType("text/plain"))
        assertTrue(htmlPart.isMimeType("text/html"))

        assertEquals("This is bold text.", textPart.content.toString().trim())
        assertEquals("<div>This is <b>bold</b> text.</div>", htmlPart.content.toString().trim())
    }

    @Test
    fun `an inline picture produces multipart related with content id`() {
        val draft = Draft(
            from = "alice@example.com",
            to = "bob@example.com",
            subject = "With Inline",
            body = "See this inline picture: ![alt](cid:pic-123)",
        )
        val inlineFile = Outgoing(
            name = "pic.png",
            type = "image/png",
            bytes = byteArrayOf(1, 2, 3),
            cid = "pic-123",
            inline = true,
        )

        val message = buildMessage(session, draft, identity, listOf(inlineFile))

        val content = message.content
        assertTrue(content is MimeMultipart)
        assertEquals("related", content.contentType.substringBefore(';').substringAfter('/').lowercase())
        assertEquals(2, content.count)

        val imagePart = content.getBodyPart(1) as MimeBodyPart
        assertEquals("pic.png", imagePart.fileName)
        assertEquals("inline", imagePart.disposition.lowercase())
        assertEquals("<pic-123>", imagePart.getHeader("Content-ID")?.firstOrNull())
    }

    @Test
    fun `a plain attachment does not end up inside the related part`() {
        val draft = Draft(
            from = "alice@example.com",
            to = "bob@example.com",
            subject = "Mixed Nesting",
            body = "Here is an inline picture: ![alt](cid:pic-123)",
        )
        val inlineFile = Outgoing(
            name = "pic.png",
            type = "image/png",
            bytes = byteArrayOf(1, 2, 3),
            cid = "pic-123",
            inline = true,
        )
        val normalAttachment = Outgoing(
            name = "document.pdf",
            type = "application/pdf",
            bytes = byteArrayOf(4, 5, 6),
            inline = false,
        )

        val message = buildMessage(session, draft, identity, listOf(inlineFile, normalAttachment))

        val mixedContent = message.content
        assertTrue(mixedContent is MimeMultipart)
        assertEquals("mixed", mixedContent.contentType.substringBefore(';').substringAfter('/').lowercase())
        assertEquals(2, mixedContent.count)

        val relatedWrapper = mixedContent.getBodyPart(0) as MimeBodyPart
        val relatedContent = relatedWrapper.content
        assertTrue(relatedContent is MimeMultipart)
        assertEquals("related", relatedContent.contentType.substringBefore(';').substringAfter('/').lowercase())
        assertEquals(2, relatedContent.count)

        val inlineImagePart = relatedContent.getBodyPart(1) as MimeBodyPart
        assertEquals("pic.png", inlineImagePart.fileName)
        assertEquals("inline", inlineImagePart.disposition.lowercase())

        val normalAttachmentPart = mixedContent.getBodyPart(1) as MimeBodyPart
        assertEquals("document.pdf", normalAttachmentPart.fileName)
        assertEquals("attachment", normalAttachmentPart.disposition.lowercase())
    }

    @Test
    fun `a reply carries In-Reply-To and References headers`() {
        val draft = Draft(
            from = "alice@example.com",
            to = "bob@example.com",
            subject = "Re: Hello",
            body = "My reply text.",
            // Bare, with no angle brackets, because that is the shape JMAP hands back and
            // therefore the shape every Draft in Rampart carries.
            inReplyTo = "msg-parent-123@example.com",
            references = listOf("msg-grandparent@example.com", "msg-parent-123@example.com"),
        )

        val message = buildMessage(session, draft, identity, emptyList())

        assertEquals("<msg-parent-123@example.com>", message.getHeader("In-Reply-To")?.firstOrNull())
        assertEquals(
            "<msg-grandparent@example.com> <msg-parent-123@example.com>",
            message.getHeader("References")?.firstOrNull(),
        )
    }

    @Test
    fun `a receipt request adds the MDN header addressed to the sender`() {
        val draft = Draft(
            from = "alice@example.com",
            to = "bob@example.com",
            subject = "Need Receipt",
            body = "Please acknowledge.",
            receipt = true,
        )

        val message = buildMessage(session, draft, identity, emptyList())
        val fromAddr = message.from?.firstOrNull()?.toString()
        assertEquals(fromAddr, message.getHeader(MDN_HEADER)?.firstOrNull())
    }
}
