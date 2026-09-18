package org.rampart

import jakarta.activation.DataHandler
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Properties

/**
 * An active SMTP connection capable of sending drafts as MIME messages.
 *
 * Designed to send messages for IMAP accounts, which lack a server side blob store.
 * The connection is opened on creation and must be closed after use.
 */
internal class Smtp private constructor(
    private val transport: Transport,
    private val session: Session,
) : AutoCloseable {

    companion object {
        /**
         * Signs in and leaves the SMTP connection open for sending.
         *
         * Standard port 587 uses explicit STARTTLS, while port 465 uses implicit TLS.
         * In both cases, the server certificate is verified to prevent interception.
         */
        fun connect(host: String, user: String, password: String, port: Int = 587): Smtp {
            val properties = propertiesFor(host, port)
            val session = Session.getInstance(properties)
            val transport = session.getTransport("smtp")
            transport.connect(host, port, user, password)
            return Smtp(transport, session)
        }
    }

    /**
     * Sends the draft message using the SMTP transport.
     *
     * Hands back what was actually sent, so the caller can append those exact bytes to its
     * own Sent folder. IMAP has no equivalent of JMAP's EmailSubmission, which files the
     * sent copy for you, so the client has to do it and a second rendering of the same draft
     * would differ from what the recipient got in its Message-ID alone.
     *
     * The Message-ID and Date are written by [MimeMessage.saveChanges] on the way out rather
     * than by the server, which is why reading them back off this message is safe.
     */
    fun send(draft: Draft, identity: Identity, files: List<Outgoing> = emptyList()): ByteArray {
        val message = buildMessage(session, draft, identity, files)
        transport.sendMessage(message, message.allRecipients ?: emptyArray())

        val out = ByteArrayOutputStream()
        message.writeTo(out)
        return out.toByteArray()
    }

    override fun close() {
        runCatching { transport.close() }
    }
}

/** A file going out with a message, as bytes, because IMAP accounts have no blob store. */
internal data class Outgoing(
    val name: String,
    val type: String,
    val bytes: ByteArray,
    val cid: String? = null,
    val inline: Boolean = false,
)

/**
 * Decides the TLS configuration based on the target SMTP port.
 *
 * Port 587 enforces STARTTLS by setting both enable and required flags.
 * Without the required flag, a server that does not offer the upgrade would receive
 * the password in plain text with no error raised. Port 465 uses implicit TLS instead.
 */
internal fun propertiesFor(host: String, port: Int): Properties {
    val properties = Properties()
    properties.put("mail.transport.protocol", "smtp")
    properties.put("mail.smtp.host", host)
    properties.put("mail.smtp.port", port.toString())
    properties.put("mail.smtp.auth", "true")
    properties.put("mail.smtp.connectiontimeout", "15000")
    properties.put("mail.smtp.timeout", "30000")
    properties.put("mail.smtp.ssl.checkserveridentity", "true")

    if (port == 465) {
        properties.put("mail.smtp.ssl.enable", "true")
    } else {
        // Every port that is not implicit TLS, not 587 alone. A server reached on 25 or on
        // some port somebody chose themselves is still a server this is about to send a
        // password to, and the one arrangement never allowed is sending it in the clear.
        properties.put("mail.smtp.starttls.enable", "true")
        properties.put("mail.smtp.starttls.required", "true")
    }
    return properties
}

/**
 * Builds a MIME message from a draft and identity without executing network calls.
 *
 * Factored out as a pure function to allow testing the entire MIME structure,
 * header mapping, and nested multipart generation without an active socket.
 */
internal fun buildMessage(
    session: Session,
    draft: Draft,
    identity: Identity,
    files: List<Outgoing>,
): MimeMessage {
    val message = MimeMessage(session)
    val fromAddress = if (identity.name.isBlank()) {
        InternetAddress(identity.email)
    } else {
        InternetAddress(identity.email, identity.name, "UTF-8")
    }
    message.setFrom(fromAddress)

    if (draft.to.isNotBlank()) {
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(draft.to))
    }
    if (draft.cc.isNotBlank()) {
        message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(draft.cc))
    }
    message.setSubject(draft.subject, "UTF-8")

    // Without both of these, a reply arrives as a new conversation in every client that
    // threads, which is most of them.
    draft.inReplyTo?.let { message.setHeader("In-Reply-To", bracketed(it)) }
    if (draft.references.isNotEmpty()) {
        message.setHeader("References", draft.references.joinToString(" ", transform = ::bracketed))
    }

    // Addressed to the sender, because a receipt that goes anywhere else is what the
    // header is abused for and what makes clients refuse it outright.
    if (draft.receipt) {
        val senderStr = if (identity.name.isBlank()) {
            identity.email
        } else {
            InternetAddress(identity.email, identity.name, "UTF-8").toString()
        }
        message.setHeader(MDN_HEADER, senderStr)
    }

    val html = htmlBodyOf(draft.body, draft.textSignature, draft.htmlSignature, draft.trackingPixel)
    val plainText = markupToPlain(draft.body)

    val (inlineFiles, attachments) = files.partition { it.inline && it.cid != null }

    if (attachments.isNotEmpty()) {
        val mixedMultipart = MimeMultipart("mixed")
        val innerBodyPart = buildInnerBody(plainText, html, inlineFiles)
        mixedMultipart.addBodyPart(innerBodyPart)
        for (file in attachments) {
            val attachmentPart = MimeBodyPart().apply {
                val dataSource = ByteArrayDataSource(file.bytes, file.type.ifBlank { "application/octet-stream" })
                dataHandler = DataHandler(dataSource)
                fileName = file.name
                disposition = "attachment"
            }
            mixedMultipart.addBodyPart(attachmentPart)
        }
        message.setContent(mixedMultipart)
    } else {
        if (inlineFiles.isNotEmpty()) {
            val relatedMultipart = buildRelatedMultipart(plainText, html, inlineFiles)
            message.setContent(relatedMultipart)
        } else {
            if (html != null) {
                val altMultipart = buildAlternativeMultipart(plainText, html)
                message.setContent(altMultipart)
            } else {
                message.setText(plainText, "UTF-8")
            }
        }
    }

    message.sentDate = Date()
    message.saveChanges()
    return message
}

/**
 * Builds the inner body part, wrapping text or alternative content appropriately.
 *
 * Nested inside multipart/mixed alongside other normal attachments when present.
 */
private fun buildInnerBody(
    plainText: String,
    html: String?,
    inlineFiles: List<Outgoing>,
): MimeBodyPart {
    val innerBodyPart = MimeBodyPart()
    if (inlineFiles.isNotEmpty()) {
        innerBodyPart.setContent(buildRelatedMultipart(plainText, html, inlineFiles))
    } else {
        if (html != null) {
            innerBodyPart.setContent(buildAlternativeMultipart(plainText, html))
        } else {
            innerBodyPart.setText(plainText, "UTF-8")
        }
    }
    return innerBodyPart
}

/**
 * Wraps the text or alternative message body in a multipart/related container.
 *
 * Signature images or other inline assets are appended with inline disposition
 * and wrapped in angle brackets to form valid Content-ID headers.
 */
private fun buildRelatedMultipart(
    plainText: String,
    html: String?,
    inlineFiles: List<Outgoing>,
): MimeMultipart {
    val relatedMultipart = MimeMultipart("related")
    val coreBodyPart = MimeBodyPart()
    if (html != null) {
        coreBodyPart.setContent(buildAlternativeMultipart(plainText, html))
    } else {
        coreBodyPart.setText(plainText, "UTF-8")
    }
    relatedMultipart.addBodyPart(coreBodyPart)

    for (file in inlineFiles) {
        val inlinePart = MimeBodyPart().apply {
            val dataSource = ByteArrayDataSource(file.bytes, file.type.ifBlank { "application/octet-stream" })
            dataHandler = DataHandler(dataSource)
            fileName = file.name
            setHeader("Content-ID", "<${file.cid}>")
            disposition = "inline"
        }
        relatedMultipart.addBodyPart(inlinePart)
    }
    return relatedMultipart
}

/**
 * Builds a multipart/alternative container with text and HTML body parts.
 *
 * Plain text goes first, followed by HTML content, so email clients display the last
 * format they support (which favors HTML when available).
 */
private fun buildAlternativeMultipart(plainText: String, html: String): MimeMultipart {
    val altMultipart = MimeMultipart("alternative")
    val textPart = MimeBodyPart().apply {
        setText(plainText, "UTF-8")
    }
    val htmlPart = MimeBodyPart().apply {
        setContent(html, "text/html; charset=utf-8")
    }
    altMultipart.addBodyPart(textPart)
    altMultipart.addBodyPart(htmlPart)
    return altMultipart
}

/**
 * A Message-ID with the angle brackets RFC 5322 requires around it.
 *
 * Every id inside Rampart is bare, because JMAP's `asMessageIds` strips the brackets on the
 * way in and puts them back on the way out. Raw MIME does neither, and a threading header
 * written without them is the exact failure the header exists to prevent: Gmail starts a new
 * conversation and nothing reports an error.
 */
private fun bracketed(id: String): String {
    val trimmed = id.trim()
    return if (trimmed.startsWith("<") && trimmed.endsWith(">")) trimmed else "<$trimmed>"
}
