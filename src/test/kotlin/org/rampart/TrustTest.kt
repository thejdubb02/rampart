package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Half of these check that nothing is said.
 *
 * That is the point of the file. A warning that fires on honest mail is worse than no
 * warning, because the next one is not believed either, so the cases that must stay silent
 * matter more than the ones that must speak.
 */
class TrustTest {

    // ---- naming the trackers -----------------------------------------------------------

    @Test
    fun `a tracker on a known network is named`() {
        val found = picturesIn(
            listOf(
                "https://us7.list-manage.com/track/open.php?u=abc&id=9f12",
                "https://track.hubspotemail.net/__ptq.gif?k=93ac",
                "https://cdn.example.org/newsletter/header.png",
            ),
        )
        assertEquals("Mailchimp", found[0].tracker)
        assertEquals("HubSpot", found[1].tracker)
        assertEquals("", found[2].tracker)
        assertEquals(2, trackerCount(found))
        assertEquals("Mailchimp and HubSpot", trackerLine(found))
    }

    @Test
    fun `a subdomain of a tracking network still resolves, a lookalike of one does not`() {
        assertEquals("Mailchimp", picturesIn(listOf("https://a.b.list-manage.com/x.gif")).single().tracker)
        // notlist-manage.com is somebody else, and naming a company that is not watching is
        // the one mistake this must never make.
        assertEquals("", picturesIn(listOf("https://notlist-manage.com/x.gif")).single().tracker)
        assertFalse(matchesHost("notmailchimp.com", "mailchimp.com"))
        assertTrue(matchesHost("a.mailchimp.com", "mailchimp.com"))
    }

    @Test
    fun `one pixel across is not a picture`() {
        val url = "https://mail.some-company.example/img/x.gif"
        val found = picturesIn(listOf(url), sizes = mapOf(url to (1 to 1)))
        assertTrue(found.single().isTracker)
        assertTrue(found.single().because.contains("one pixel"))
        // An ordinary picture with a size is still an ordinary picture.
        assertFalse(picturesIn(listOf(url), sizes = mapOf(url to (600 to 300))).single().isTracker)
    }

    @Test
    fun `an unknown network is caught by the shape of the address`() {
        val beacon = "https://mail.some-company.example/e/o/9f12a4c7b8e34d1a5c6f?utm_source=x"
        assertTrue(picturesIn(listOf(beacon)).single().isTracker)
        // Named by its host when there is no company name for it, which is still more than
        // a number.
        assertEquals("mail.some-company.example", trackerLine(picturesIn(listOf(beacon))))
    }

    @Test
    fun `an ordinary picture on an ordinary path says nothing`() {
        // The word alone is not enough: a directory called "open" is a directory, and an
        // identifier alone is how every CDN names a file.
        listOf(
            "https://cdn.example.org/open/house/photo.jpg",
            "https://cdn.example.org/9f12a4c7b8e34d1a5c6f.png",
            "https://example.org/images/logo.png",
        ).forEach {
            assertFalse(picturesIn(listOf(it)).single().isTracker, "$it was called a tracker")
        }
        assertEquals("", trackerLine(picturesIn(listOf("https://example.org/logo.png"))))
    }

    // ---- spotting the fakes ------------------------------------------------------------

    private fun warnings(
        fromEmail: String,
        fromName: String = "",
        html: String? = null,
        auth: String? = null,
        replyTo: List<String> = emptyList(),
        known: Set<String> = emptySet(),
    ) = warningsFor(fromEmail, fromName, html, auth, replyTo, known)

    @Test
    fun `a lookalike domain is caught by shape, not by spelling distance`() {
        // Capital i for l. Identical on screen, and no string distance sees it at all.
        val paypal = warnings("billing@paypaI.com").single()
        assertTrue(paypal.says.contains("This is not paypal.com"))
        // rn for m.
        assertTrue(warnings("admin@rnicrosoft.com").single().says.contains("microsoft.com"))
        // A zero for an o.
        assertTrue(warnings("no-reply@dr0pbox.com").single().says.contains("dropbox.com"))
    }

    @Test
    fun `the difference is said in words, because it cannot be seen`() {
        // Printing the two domains side by side is useless: the attack is that they look
        // identical. The swapped character in words is the part that cannot be misread.
        assertEquals("i in place of l", spelling("paypai.com", "paypal.com"))
        assertEquals("rn in place of m", spelling("rnicrosoft.com", "microsoft.com"))
        assertEquals("0 in place of o", spelling("dr0pbox.com", "dropbox.com"))
        assertTrue(warnings("billing@paypaI.com").single().because.contains("i in place of l"))
    }

    @Test
    fun `the real thing is never accused`() {
        assertTrue(warnings("service@paypal.com").isEmpty())
        assertTrue(warnings("noreply@github.com").isEmpty())
        // A subdomain of the real thing is the real thing.
        assertTrue(warnings("bounce@mail.paypal.com").isEmpty())
    }

    @Test
    fun `an ordinary message says nothing at all`() {
        assertTrue(
            warnings(
                "dana@example.org",
                fromName = "Dana Whitfield",
                html = "<p>Tuesday works.</p>",
                auth = "mx.example.com; dkim=pass header.d=example.org; dmarc=pass",
            ).isEmpty(),
        )
    }

    @Test
    fun `a domain that merely contains a brand name is not a lookalike`() {
        // Every agency with a client's name in its domain would trip this, and accusing one
        // of fraud is worse than missing a phish.
        assertTrue(warnings("hello@paypal-invoices.example").isEmpty())
        assertTrue(warnings("team@we-do-microsoft-support.example").isEmpty())
    }

    @Test
    fun `a lookalike of one of your own correspondents is caught too`() {
        val known = setOf("americantank.com")
        assertTrue(warnings("accounts@americantank.com", known = known).isEmpty())
        assertTrue(
            warnings("accounts@arnericantank.com", known = known).single()
                .says.contains("americantank.com"),
        )
    }

    @Test
    fun `a password field is close to never honest`() {
        val asking = warnings(
            "security@example.org",
            html = """<form action="https://elsewhere.example"><input type="password" name="p"></form>""",
        )
        assertEquals("This message asks for a password.", asking.single().says)
        // An ordinary field is not a password field.
        assertTrue(warnings("a@b.test", html = """<input type="text" name="q">""").isEmpty())
    }

    @Test
    fun `a display name that is somebody else's address is called out`() {
        val faked = warnings("attacker@bad.example", fromName = "security@paypal.com").single()
        assertTrue(faked.says.contains("not the one it came from"))
        assertTrue(faked.because.contains("security@paypal.com"))
    }

    @Test
    fun `a bulk sender displaying its own address is left alone`() {
        // Displayed as noreply@company.com, sent from the bounce subdomain. This is what
        // most commercial mail looks like and firing on it would be the end of the badge.
        assertTrue(
            warnings("bounce-9f12@mail.company.example", fromName = "\"noreply@company.example\"").isEmpty(),
        )
        // And the exact same address in both places, which the live mailbox actually has.
        assertTrue(warnings("matt@example.org", fromName = "\"matt@example.org\"").isEmpty())
    }

    @Test
    fun `Reply-To elsewhere is only a warning when nothing vouched for the message`() {
        // A mailing list. Ordinary, and the badge must stay quiet.
        assertTrue(
            warnings(
                "list@lists.example.org",
                replyTo = listOf("list@other.example"),
                auth = "mx.example.com; dkim=pass header.d=lists.example.org; dmarc=pass",
            ).isEmpty(),
        )
        // The same shape with nothing vouching for it.
        val redirected = warnings(
            "billing@supplier.example",
            replyTo = listOf("payments@attacker.example"),
            auth = "mx.example.com; dkim=fail; spf=fail; dmarc=fail",
        ).single()
        assertTrue(redirected.says.contains("attacker.example"))
    }

    @Test
    fun `a Reply-To on the sender's own sending subdomain is not a redirect`() {
        assertTrue(
            warnings(
                "news@example.org",
                replyTo = listOf("replies@mail.example.org"),
                auth = "mx.example.com; dkim=fail; dmarc=fail",
            ).isEmpty(),
        )
    }

    @Test
    fun `a domain in another alphabet says what it is`() {
        val punycode = warnings("support@xn--pypal-4ve.com").single()
        assertTrue(punycode.says.contains("alphabet"))
    }

    @Test
    fun `the characters that look alike are the ones treated alike`() {
        assertEquals(confusable("paypal.com"), confusable("paypaI.com"))
        assertEquals(confusable("microsoft.com"), confusable("rnicrosoft.com"))
        assertEquals(confusable("dropbox.com"), confusable("dr0pbox.com"))
        assertEquals(confusable("www.example.com"), confusable("vvww.example.com"))
        // Two genuinely different names stay different.
        assertTrue(confusable("github.com") != confusable("gitlab.com"))
    }
}
