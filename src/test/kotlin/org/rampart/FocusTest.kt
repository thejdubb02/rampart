package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the focused inbox classifier and sender bundling in the Other tab.
 */
class FocusTest {

    private fun summary(
        id: String = "1",
        from: String = "Alice Smith",
        fromEmail: String = "alice@example.com",
        subject: String = "Hello",
        receivedAt: String = "2026-09-29T10:00:00Z",
        seen: Boolean = true,
        listId: String = "",
        listUnsubscribe: String = "",
        precedence: String = "",
        autoSubmitted: String = "",
    ) = Summary(
        id = id,
        from = from,
        fromEmail = fromEmail,
        subject = subject,
        receivedAt = receivedAt,
        preview = "Preview $id",
        seen = seen,
        listId = listId,
        listUnsubscribe = listUnsubscribe,
        precedence = precedence,
        autoSubmitted = autoSubmitted,
    )

    @Test
    fun regularPersonEmailIsFocused() {
        val msg = summary(from = "Alice", fromEmail = "alice@personal.org", subject = "Lunch tomorrow?")
        assertEquals(FocusCategory.FOCUSED, classifyFocus(msg))
        assertTrue(isFocused(msg))
        assertFalse(isBulk(msg))
    }

    @Test
    fun listIdIsBulk() {
        val msg = summary(fromEmail = "news@example.org", listId = "news.example.org")
        assertEquals(FocusCategory.OTHER, classifyFocus(msg))

        val msgWithHeader = summary(fromEmail = "news@example.org")
        val headers = listOf("List-Id" to "<community.example.org>")
        assertEquals(FocusCategory.OTHER, classifyFocus(msgWithHeader, headers = headers))
    }

    @Test
    fun listUnsubscribeIsBulk() {
        val msg = summary(fromEmail = "updates@store.com")
        val headers = listOf("List-Unsubscribe" to "<https://store.com/unsub?id=123>")
        assertEquals(FocusCategory.OTHER, classifyFocus(msg, headers = headers))
    }

    @Test
    fun `a list unsubscribe header lands in Other and a message without one stays Focused`() {
        // The same call the inbox list makes: isFocused on the row, with the header
        // the list fetch keeps on that row.
        val newsletter = summary(
            id = "news",
            fromEmail = "news@store.com",
            listUnsubscribe = "<https://store.com/unsub>",
        )
        val letter = summary(id = "letter", fromEmail = "ada@example.com")
        val (focused, other) = listOf(newsletter, letter).partition { isFocused(it) }
        assertEquals(listOf("letter"), focused.map { it.id })
        assertEquals(listOf("news"), other.map { it.id })
    }

    @Test
    fun precedenceBulkOrListIsBulk() {
        val msg1 = summary(fromEmail = "alerts@service.com")
        val headers1 = listOf("Precedence" to "bulk")
        assertEquals(FocusCategory.OTHER, classifyFocus(msg1, headers = headers1))

        val msg2 = summary(fromEmail = "alerts@service.com")
        val headers2 = listOf("Precedence" to "list")
        assertEquals(FocusCategory.OTHER, classifyFocus(msg2, headers = headers2))

        val msg3 = summary(fromEmail = "friend@example.com")
        val headers3 = listOf("Precedence" to "first-class")
        assertEquals(FocusCategory.FOCUSED, classifyFocus(msg3, headers = headers3))
    }

    @Test
    fun autoSubmittedOtherThanNoIsBulk() {
        val msg1 = summary(fromEmail = "system@service.com")
        val headers1 = listOf("Auto-Submitted" to "auto-generated")
        assertEquals(FocusCategory.OTHER, classifyFocus(msg1, headers = headers1))

        val msg2 = summary(fromEmail = "system@service.com")
        val headers2 = listOf("Auto-Submitted" to "auto-replied")
        assertEquals(FocusCategory.OTHER, classifyFocus(msg2, headers = headers2))

        val msg3 = summary(fromEmail = "friend@example.com")
        val headers3 = listOf("Auto-Submitted" to "no")
        assertEquals(FocusCategory.FOCUSED, classifyFocus(msg3, headers = headers3))
    }

    @Test
    fun noReplyStyleSendersAreBulk() {
        val noReplyAddresses = listOf(
            "noreply@company.com",
            "no-reply@service.org",
            "donotreply@bank.com",
            "notifications@github.com",
            "mailer-daemon@mx.mail.com",
            "bounce-12345@marketing.com",
        )
        for (addr in noReplyAddresses) {
            val msg = summary(from = "Automated System", fromEmail = addr)
            assertEquals(FocusCategory.OTHER, classifyFocus(msg), "Sender $addr should classify as Other")
        }

        val nameNoReply = summary(from = "GitHub Notifications", fromEmail = "support@github.com")
        assertEquals(FocusCategory.OTHER, classifyFocus(nameNoReply))
    }

    @Test
    fun marketingPlatformInReturnPathOrReceivedIsBulkForUnknownSender() {
        val platforms = listOf(
            "sendgrid" to ("Return-Path" to "<bounces+123@sendgrid.net>"),
            "mailchimp" to ("Received" to "from mail12.mailchimp.com by mx.google.com"),
            "mcsv" to ("Return-Path" to "<bounce@mcsv.net>"),
            "amazonses" to ("Return-Path" to "<0100018@amazonses.com>"),
            "mailgun" to ("Received" to "by mailgun.org with HTTP"),
            "constantcontact" to ("Return-Path" to "<bounce@constantcontact.com>"),
            "hubspot" to ("Received" to "from 192.0.2.1 by hubspot.com"),
            "klaviyo" to ("Return-Path" to "<bounces@klaviyo.com>"),
        )

        for ((name, header) in platforms) {
            val msg = summary(from = "Promo", fromEmail = "promo-$name@shop.com")
            assertEquals(
                FocusCategory.OTHER,
                classifyFocus(msg, headers = listOf(header)),
                "Platform $name in header should classify as Other",
            )
        }
    }

    @Test
    fun knownSenderIsAlwaysFocused() {
        val known = setOf("newsletter-curator@example.org", "friend@work.com", "alerts-owner@home.org")

        // Even with List-Id, known sender is treated as a person
        val msg1 = summary(fromEmail = "newsletter-curator@example.org", listId = "curated.list")
        assertEquals(FocusCategory.FOCUSED, classifyFocus(msg1, knownSenders = known))

        // Even with Precedence: bulk and List-Unsubscribe
        val msg2 = summary(fromEmail = "friend@work.com")
        val headers2 = listOf(
            "Precedence" to "bulk",
            "List-Unsubscribe" to "<https://work.com/optout>",
            "Return-Path" to "<bounce@sendgrid.net>",
        )
        assertEquals(FocusCategory.FOCUSED, classifyFocus(msg2, knownSenders = known, headers = headers2))

        // Even with no-reply sender address
        val msg3 = summary(fromEmail = "alerts-owner@home.org", from = "Home Notifications")
        assertEquals(FocusCategory.FOCUSED, classifyFocus(msg3, knownSenders = known))
    }

    @Test
    fun perSenderOverrideWinsOverEverything() {
        val overrides = mapOf(
            "bulk-sender@example.com" to "focused",
            "friend@example.com" to "other",
        )
        val known = setOf("friend@example.com")

        // Sender with bulk indicators overridden to Focused
        val bulkMsg = summary(
            fromEmail = "bulk-sender@example.com",
            listId = "announcements.list",
        )
        val bulkHeaders = listOf(
            "List-Unsubscribe" to "<https://example.com/unsub>",
            "Auto-Submitted" to "auto-generated",
            "Return-Path" to "<bounce@sendgrid.net>",
        )
        assertEquals(FocusCategory.FOCUSED, classifyFocus(bulkMsg, headers = bulkHeaders, overrides = overrides))

        // Known personal sender overridden to Other
        val personalMsg = summary(fromEmail = "friend@example.com", from = "Best Friend")
        assertEquals(FocusCategory.OTHER, classifyFocus(personalMsg, knownSenders = known, overrides = overrides))

        // Case insensitivity
        val casedMsg = summary(fromEmail = "Bulk-Sender@Example.COM", listId = "some.list")
        assertEquals(FocusCategory.FOCUSED, classifyFocus(casedMsg, overrides = overrides))
    }

    @Test
    fun bundlingBySenderGroupsMessagesTogether() {
        val messages = listOf(
            summary(id = "1", from = "GitHub", fromEmail = "notifications@github.com", subject = "Issue 101", receivedAt = "2026-09-29T12:00:00Z", seen = false),
            summary(id = "2", from = "Stripe", fromEmail = "receipts@stripe.com", subject = "Invoice #42", receivedAt = "2026-09-29T11:00:00Z", seen = true),
            summary(id = "3", from = "GitHub", fromEmail = "notifications@github.com", subject = "PR 202", receivedAt = "2026-09-29T10:00:00Z", seen = true),
            summary(id = "4", from = "GitHub", fromEmail = "notifications@github.com", subject = "Discussion 303", receivedAt = "2026-09-29T09:00:00Z", seen = false),
        )

        val bundles = bundleOtherMessages(messages)
        assertEquals(2, bundles.size)

        val githubBundle = bundles[0]
        assertEquals("GitHub", githubBundle.sender)
        assertEquals("notifications@github.com", githubBundle.senderEmail)
        assertEquals(3, githubBundle.count)
        assertEquals("Issue 101", githubBundle.newest.subject)
        assertEquals(2, githubBundle.unreadCount)
        assertEquals(listOf("1", "3", "4"), githubBundle.messages.map { it.id })

        val stripeBundle = bundles[1]
        assertEquals("Stripe", stripeBundle.sender)
        assertEquals("receipts@stripe.com", stripeBundle.senderEmail)
        assertEquals(1, stripeBundle.count)
        assertEquals("Invoice #42", stripeBundle.newest.subject)
        assertEquals(0, stripeBundle.unreadCount)
    }

    @Test
    fun bundlingEmptyListReturnsEmptyList() {
        assertEquals(emptyList(), bundleOtherMessages(emptyList()))
    }
}
