package org.rampart

import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What someone types in To has to go out as a name and an address, not as one string. */
class AddressParseTest {
    @Test
    fun aNameAndAddressAreSplitApart() {
        val parsed = parseAddressList("Dana Whitfield <dana@example.org>")
        assertEquals(1, parsed.size)
        assertEquals("Dana Whitfield", parsed[0].name)
        assertEquals("dana@example.org", parsed[0].email)
    }

    @Test
    fun aQuotedCommaStaysInTheNameAndASemicolonSeparates() {
        val parsed = parseAddressList(""""Whitfield, Dana" <dana@example.org>; "Last; First" <sam@example.org>""")
        assertEquals(2, parsed.size)
        assertEquals("Whitfield, Dana", parsed[0].name)
        assertEquals("dana@example.org", parsed[0].email)
        assertEquals("Last; First", parsed[1].name)
        assertEquals("sam@example.org", parsed[1].email)
    }

    @Test
    fun semicolonsAndCommasBothSeparateBareAddresses() {
        assertEquals(
            listOf("a@example.org", "b@example.org", "c@example.org"),
            parseAddressList("a@example.org; b@example.org, c@example.org").map { it.email },
        )
    }

    @Test
    fun theSmtpMessageUsesTheSameSplit() {
        val draft = Draft(
            from = "alice@example.com",
            to = """"Whitfield, Dana" <dana@example.org>; Sam <sam@example.org>""",
            cc = "Pat <pat@example.org>",
        )
        val message = buildMessage(
            Session.getInstance(Properties()),
            draft,
            Identity("id", "Alice", "alice@example.com"),
            emptyList(),
        )
        val to = message.getRecipients(jakarta.mail.Message.RecipientType.TO)
        assertEquals(2, to.size)
        assertEquals("dana@example.org", (to[0] as InternetAddress).address)
        assertEquals("Whitfield, Dana", (to[0] as InternetAddress).personal)
        assertEquals("sam@example.org", (to[1] as InternetAddress).address)
        assertEquals("Sam", (to[1] as InternetAddress).personal)
        val cc = message.getRecipients(jakarta.mail.Message.RecipientType.CC)
        assertEquals("pat@example.org", (cc[0] as InternetAddress).address)
        assertTrue(draft.recipients == listOf("dana@example.org", "sam@example.org", "pat@example.org"))
    }
}
