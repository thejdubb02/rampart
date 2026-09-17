package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Authenticity verification is crucial to protect users from phishing, email spoofing,
 * and malicious transit alterations.
 */
class AuthenticityTest {

    @Test
    fun testAuthenticityAllPass() {
        // All passing results.
        val auth = authenticityOf(
            "mx.example.org; spf=pass smtp.mailfrom=x.test; dkim=pass header.d=x.test; dmarc=pass",
            "No, score=-1.2 required=5.0"
        )
        assertEquals(Check.PASS, auth.spf)
        assertEquals(Check.PASS, auth.dkim)
        assertEquals(Check.PASS, auth.dmarc)
        assertEquals(-1.2, auth.spamScore)
        assertEquals("Checks passed.", auth.summary)
        assertFalse(auth.worthShowing)
    }

    @Test
    fun testAuthenticityDmarcFail() {
        // Dmarc failure.
        val auth = authenticityOf(
            "mx.example.org; spf=pass; dkim=pass; dmarc=fail",
            "No, score=2.0 required=5.0"
        )
        assertEquals(Check.PASS, auth.spf)
        assertEquals(Check.PASS, auth.dkim)
        assertEquals(Check.FAIL, auth.dmarc)
        assertEquals("This did not come from the address it says it did.", auth.summary)
        assertTrue(auth.worthShowing)
    }

    @Test
    fun testAuthenticityDkimFail() {
        // A DKIM failure is only worth saying when DMARC did not pass in spite of it.
        val auth = authenticityOf(
            "mx.example.org; spf=pass; dkim=fail; dmarc=none",
            "No, score=2.0 required=5.0"
        )
        assertEquals(Check.FAIL, auth.dkim)
        assertEquals("This message was changed on the way here, or is not from that sender.", auth.summary)
        assertTrue(auth.worthShowing)
    }

    @Test
    fun testAuthenticitySpfFail() {
        // Forwarded mail fails SPF as a matter of course, so this only speaks up where
        // DMARC did not already settle the question.
        val auth = authenticityOf(
            "mx.example.org; spf=fail; dkim=none; dmarc=none",
            "No, score=2.0 required=5.0"
        )
        assertEquals(Check.FAIL, auth.spf)
        assertEquals("This reached us through a server that sender does not normally use.", auth.summary)
        assertTrue(auth.worthShowing)

        // The same failure under a DMARC pass says nothing, because the message really is
        // from that domain and it was only forwarded.
        assertFalse(authenticityOf("mx.example.org; spf=fail; dkim=pass; dmarc=pass", null).worthShowing)
    }

    /**
     * No header at all means our own server never saw this message arrive, which is true of
     * everything in Sent and Drafts. Measured on a real mailbox, treating that as unproven
     * put a badge on one message in three, most of them Justin's own. Nothing to check is
     * not the same as something to worry about.
     */
    @Test
    fun testAuthenticityAllMissing() {
        val auth = authenticityOf(null, null)
        assertEquals(Check.MISSING, auth.spf)
        assertEquals(Check.MISSING, auth.dkim)
        assertEquals(Check.MISSING, auth.dmarc)
        assertEquals(null, auth.spamScore)
        assertEquals("Nothing here proves who sent this.", auth.summary)
        assertFalse(auth.worthShowing, "a message we never received is not one to judge")
    }

    /** A message that did arrive, with a header that vouches for nothing, is worth a word. */
    @Test
    fun testAuthenticityReceivedButUnproven() {
        val auth = authenticityOf("mx.example.org; spf=none; dkim=none; dmarc=none", null)
        assertEquals("Nothing here proves who sent this.", auth.summary)
        assertTrue(auth.worthShowing)
    }

    @Test
    fun testAuthenticityHighSpam() {
        // High spam status.
        val auth = authenticityOf(
            "mx.example.org; spf=pass; dkim=pass; dmarc=pass",
            "Yes, score=6.1 required=5.0 tests=..."
        )
        assertEquals(Check.PASS, auth.spf)
        assertEquals(Check.PASS, auth.dkim)
        assertEquals(Check.PASS, auth.dmarc)
        assertEquals(6.1, auth.spamScore)
        assertEquals("The server thinks this is spam.", auth.summary)
        assertTrue(auth.worthShowing)
    }

    @Test
    fun testAuthenticityCaseInsensitivity() {
        // Case insensitivity.
        val auth = authenticityOf(
            "MX.EXAMPLE.ORG; SPF=PaSs; dKiM=fAiL; DMARC=nOnE",
            "YES, SCORE=7.5 REQUIRED=5.0"
        )
        assertEquals(Check.PASS, auth.spf)
        assertEquals(Check.FAIL, auth.dkim)
        assertEquals(Check.MISSING, auth.dmarc)
        assertEquals(7.5, auth.spamScore)
        // Spam is said before anything else: a message can be from exactly who it claims
        // and still be spam, and that is the more useful thing to tell somebody.
        assertEquals("The server thinks this is spam.", auth.summary)
        assertTrue(auth.worthShowing)
    }

    @Test
    fun testAuthenticityLineFolding() {
        // Line folding.
        val auth = authenticityOf(
            "mx.example.org;\r\n\tspf=pass;\r\n dkim=fail;\r\n\tdmarc=none",
            "No,\r\n\tscore=-3.5 required=5.0"
        )
        assertEquals(Check.PASS, auth.spf)
        assertEquals(Check.FAIL, auth.dkim)
        assertEquals(Check.MISSING, auth.dmarc)
        assertEquals(-3.5, auth.spamScore)
        assertEquals("This message was changed on the way here, or is not from that sender.", auth.summary)
        assertTrue(auth.worthShowing)
    }

    @Test
    fun testAuthenticityMultipleHeaders() {
        // A forwarder appends its own results below ours. Ours are the ones that count,
        // in both directions: its pass cannot vouch and its failure cannot condemn.
        val auth = authenticityOf(
            "mx.example.org; spf=pass; dkim=pass; dmarc=pass\nmx.forwarder.org; spf=fail; dkim=fail; dmarc=fail",
            null
        )
        assertEquals(Check.PASS, auth.spf)
        assertEquals(Check.PASS, auth.dkim)
        assertEquals(Check.PASS, auth.dmarc)
        assertFalse(auth.worthShowing)
    }

    @Test
    fun testAuthenticityFirstDefiniteResultWithNonDefiniteFirst() {
        // neutral is not an answer, so the definite one further along the line is.
        val auth = authenticityOf(
            "mx.example.org; spf=neutral; spf=fail; dmarc=none",
            null
        )
        assertEquals(Check.FAIL, auth.spf)
        assertEquals("This reached us through a server that sender does not normally use.", auth.summary)
        assertTrue(auth.worthShowing)
    }

    @Test
    fun testAuthenticityMalformedJunkThrowsNothing() {
        // Malformed inputs.
        val auth1 = authenticityOf("!!! malformed junk @@@", "not a score")
        assertEquals(Check.MISSING, auth1.spf)
        assertEquals(Check.MISSING, auth1.dkim)
        assertEquals(Check.MISSING, auth1.dmarc)
        assertEquals(null, auth1.spamScore)
        assertEquals("Nothing here proves who sent this.", auth1.summary)
        assertTrue(auth1.worthShowing)

        val auth2 = authenticityOf("spf=; dkim=; dmarc=", "score=")
        assertEquals(Check.MISSING, auth2.spf)
        assertEquals(Check.MISSING, auth2.dkim)
        assertEquals(Check.MISSING, auth2.dmarc)
        assertEquals(null, auth2.spamScore)
        assertEquals("Nothing here proves who sent this.", auth2.summary)
        assertTrue(auth2.worthShowing)
    }

    @Test
    fun `a broken second signature does not condemn a message DMARC passed`() {
        // A real message from a client, and the false red banner that prompted all this.
        // One signature was broken by a relay, the other verified, and DMARC passed on it.
        val header = "mail.willhitestrategy.org; dkim=fail (verification failed) " +
            "header.d=netorgft4894676.onmicrosoft.com header.s=selector2; " +
            "spf=pass (domain of matt@example.com designates 205.220.189.236 as permitted sender) " +
            "smtp.mailfrom=matt@example.com; iprev=pass; dmarc=pass header.from=example.com"
        val checked = authenticityOf(header, "No")
        assertFalse(checked.worthShowing, "a DMARC pass should say nothing at all")
    }

    @Test
    fun `a pass anywhere on the line beats a failure written before it`() {
        val header = "mx.example.org; dkim=fail header.d=relay.example.net; " +
            "dkim=pass header.d=example.com; dmarc=pass header.from=example.com"
        assertEquals(Check.PASS, authenticityOf(header, null).dkim)
    }

    @Test
    fun `a forwarder further down cannot vouch for what our own server could not`() {
        // Our line first, the forwarder's second. Only ours is read.
        val header = "mail.willhitestrategy.org; dkim=none; spf=none; dmarc=fail header.from=bank.example\n" +
            "relay.example.net; dkim=pass header.d=relay.example.net; dmarc=pass"
        val checked = authenticityOf(header, null)
        assertEquals(Check.FAIL, checked.dmarc)
        assertTrue(checked.worthShowing)
        assertEquals("This did not come from the address it says it did.", checked.summary)
    }

    @Test
    fun `a DMARC failure still shows, which is the whole point of the badge`() {
        val checked = authenticityOf("mx.example.org; spf=pass; dmarc=fail header.from=bank.example", null)
        assertTrue(checked.worthShowing)
    }

    @Test
    fun `with no DMARC at all a plain SPF failure is still worth saying`() {
        val checked = authenticityOf("mx.example.org; spf=fail smtp.mailfrom=someone@example.com", null)
        assertTrue(checked.worthShowing)
    }
}
