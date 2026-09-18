package org.rampart

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The part that carries a meeting has to survive the walk that decides what is an
 * attachment, and it very nearly does not: it has no filename and no Content-ID, which is
 * exactly the shape that walk exists to throw away.
 */
class InvitationPartTest {

    private fun parse(raw: String): MimeMessage =
        MimeMessage(Session.getInstance(Properties()), raw.replace("\n", "\r\n").byteInputStream())

    /** How Outlook and Google both send one: a third format beside the text and the HTML. */
    private val invitationInAlternative = """
        From: Dana Whitfield <dana@example.org>
        To: justin@example.com
        Subject: Invitation: Quarterly review
        MIME-Version: 1.0
        Content-Type: multipart/alternative; boundary="alt"

        --alt
        Content-Type: text/plain; charset=UTF-8

        You have been invited to Quarterly review.
        --alt
        Content-Type: text/html; charset=UTF-8

        <p>You have been invited to Quarterly review.</p>
        --alt
        Content-Type: text/calendar; charset=UTF-8; method=REQUEST

        BEGIN:VCALENDAR
        METHOD:REQUEST
        BEGIN:VEVENT
        UID:q4-review
        SUMMARY:Quarterly review
        DTSTART:20260922T130000Z
        ORGANIZER:mailto:dana@example.org
        END:VEVENT
        END:VCALENDAR
        --alt--
    """.trimIndent()

    @Test
    fun `an invitation with no filename is still found`() {
        val parts = attachmentsOf(parse(invitationInAlternative), "1")

        val ics = parts.single { it.type.equals("text/calendar", ignoreCase = true) }
        // Named here, because the part did not name itself and a file with no name is one
        // nothing downstream can talk about.
        assertEquals("invite.ics", ics.name)
    }

    @Test
    fun `the text and the HTML are still not attachments`() {
        // The whole reason the walk drops a part with no filename. Keeping the calendar one
        // must not start keeping the body as well.
        val parts = attachmentsOf(parse(invitationInAlternative), "1")
        assertEquals(1, parts.size, "the body's own formats were listed as files")
    }

    @Test
    fun `an invitation sent as a proper attachment keeps its own name`() {
        val withFile = """
            From: Dana <dana@example.org>
            Subject: Invitation
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="mix"

            --mix
            Content-Type: text/plain

            See attached.
            --mix
            Content-Type: text/calendar; charset=UTF-8; method=REQUEST
            Content-Disposition: attachment; filename="meeting.ics"

            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:x
            SUMMARY:Quarterly review
            END:VEVENT
            END:VCALENDAR
            --mix--
        """.trimIndent()

        assertEquals("meeting.ics", attachmentsOf(parse(withFile), "1").single().name)
    }

    @Test
    fun `the part reaches the parser with the meeting intact`() {
        // The two halves joined up: found by the walk, then read as a meeting. Either one
        // working alone shows nothing on screen.
        val message = parse(invitationInAlternative)
        val part = attachmentsOf(message, "1").single { it.type.equals("text/calendar", ignoreCase = true) }
        // Found by the same index the blob id carries and read the same way the backend
        // reads it: a part whose content is handed back as a stream, because Angus has no
        // handler registered for text/calendar and calling content.toString() on that
        // returns the name of the stream object.
        val found = partAt(message, part.blobId.substringAfterLast('#').toInt())!!
        val meeting = invitationIn(found.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })!!

        assertEquals("REQUEST", meeting.method)
        assertEquals("Quarterly review", meeting.summary)
        assertEquals("dana@example.org", meeting.organiser?.email)
        assertTrue(meeting.starts?.instant != null)
    }
}
