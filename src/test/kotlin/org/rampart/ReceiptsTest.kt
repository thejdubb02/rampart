package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptsTest {
    @Test
    fun `an address comes out of a display name`() {
        assertEquals("dana@example.org", bareAddress("Dana Whitfield <dana@example.org>"))
        assertEquals("dana@example.org", bareAddress("  dana@example.org  "))
        assertEquals("dana@example.org", bareAddress("<dana@example.org>"))
    }

    @Test
    fun `a message that asks for one, and the header name is not case sensitive`() {
        assertEquals(
            "dana@example.org",
            receiptWanted(mapOf("Disposition-Notification-To" to "dana@example.org"), "dana@example.org"),
        )
        assertEquals(
            "dana@example.org",
            receiptWanted(mapOf("disposition-notification-to" to "Dana <dana@example.org>"), "Dana <dana@example.org>"),
        )
    }

    @Test
    fun `a message that asks for nothing`() {
        assertNull(receiptWanted(emptyMap(), "dana@example.org"))
        assertNull(receiptWanted(mapOf("Subject" to "hello"), "dana@example.org"))
        assertNull(receiptWanted(mapOf("Disposition-Notification-To" to "  "), "dana@example.org"))
    }

    /*
     * A request pointing somewhere other than the sender is how a receipt gets used to
     * confirm an address for a third party. Worth more to them than to the person reading.
     */
    @Test
    fun `a request that points somewhere else is not honoured`() {
        assertNull(
            receiptWanted(
                mapOf("Disposition-Notification-To" to "collector@somewhere.test"),
                "dana@example.org",
            ),
        )
    }

    /* The claim has to be the one the mechanism supports, and no larger. */
    @Test
    fun `the receipt says opened rather than read`() {
        val body = receiptBody("the quote", "15 September at 17:40", "dana@example.org")
        assertTrue(body.contains("was displayed"))
        assertTrue(body.contains("not that it was read"))
        assertEquals("Read: the quote", receiptSubject("the quote"))
    }
}
