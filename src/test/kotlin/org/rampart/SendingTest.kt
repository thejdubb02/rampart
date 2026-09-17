package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendingTest {
    @Test
    fun `the plain promises`() {
        assertTrue(mentionsAttachment("The quote is attached."))
        assertTrue(mentionsAttachment("Attaching the signed copy."))
        assertTrue(mentionsAttachment("Please find the invoice below."))
        assertTrue(mentionsAttachment("See the attachment."))
    }

    @Test
    fun `no promise, no warning`() {
        assertFalse(mentionsAttachment("Sounds good, see you Tuesday."))
        assertFalse(mentionsAttachment(""))
    }

    /* The false alarm that trains people to click through warnings. */
    @Test
    fun `a promise in the quoted text is not ours`() {
        val reply = "Got it, thanks.\n\nOn Tuesday, dana@example.org wrote:\n> The quote is attached."
        assertFalse(mentionsAttachment(reply))
    }

    @Test
    fun `a longer word that merely contains one is not a promise`() {
        assertFalse(mentionsAttachment("The shelf came unattached."))
    }

    @Test
    fun `the attachment question beats the subject question`() {
        assertEquals(
            "This message mentions an attachment and does not have one. Send it anyway?",
            sendWarning(subject = "", body = "The quote is attached.", attachments = 0),
        )
    }

    @Test
    fun `an actual attachment answers it`() {
        assertNull(sendWarning(subject = "Quote", body = "The quote is attached.", attachments = 1))
    }

    @Test
    fun `an empty subject is asked about on its own`() {
        assertEquals(
            "This message has no subject. Send it anyway?",
            sendWarning(subject = "  ", body = "Sounds good.", attachments = 0),
        )
    }

    @Test
    fun `nothing to ask about`() {
        assertNull(sendWarning(subject = "Tuesday", body = "Sounds good.", attachments = 0))
    }
}
