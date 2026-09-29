package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class MailboxUnreadTest {
    @Test
    fun `unread conversations win over unread messages, and a missing count falls back`() {
        val both = mailboxFrom(
            Json.parseToJsonElement(
                """{"id":"in","name":"Inbox","role":"inbox","unreadEmails":1,"unreadThreads":2,"totalEmails":9}""",
            ).jsonObject,
        )
        // The message count stays the message count. The sidebar reads the other one.
        assertEquals(1, both.unread)
        assertEquals(2, both.unreadThreads)

        val messagesOnly = mailboxFrom(
            Json.parseToJsonElement(
                """{"id":"in","name":"Inbox","role":"inbox","unreadEmails":4}""",
            ).jsonObject,
        )
        assertEquals(4, messagesOnly.unread)
        assertEquals(4, messagesOnly.unreadThreads)
    }
}
