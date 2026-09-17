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
        // Dkim failure.
        val auth = authenticityOf(
            "mx.example.org; spf=pass; dkim=fail; dmarc=pass",
            "No, score=2.0 required=5.0"
        )
        assertEquals(Check.PASS, auth.spf)
        assertEquals(Check.FAIL, auth.dkim)
        assertEquals(Check.PASS, auth.dmarc)
        assertEquals("This message was changed on the way here, or is not from that sender.", auth.summary)
        assertTrue(auth.worthShowing)
    }

    @Test
    fun testAuthenticitySpfFail() {
        // Spf failure.
        val auth = authenticityOf(
            "mx.example.org; spf=fail; dkim=pass; dmarc=pass",
            "No, score=2.0 required=5.0"
        )
        assertEquals(Check.FAIL, auth.spf)
        assertEquals(Check.PASS, auth.dkim)
        assertEquals(Check.PASS, auth.dmarc)
        assertEquals("This reached us through a server that sender does not normally use.", auth.summary)
        assertTrue(auth.worthShowing)
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
        assertEquals("This message was changed on the way here, or is not from that sender.", auth.summary)
        assertTrue(auth.worthShowing)
    }

    @Test
    fun testAuthenticityLineFolding() {
        // Line folding.
        val auth = authenticityOf(
            "mx.example.org;\r\n\tspf=pass;\r\n dkim=fail;\r\n\tdmarc=pass",
            "No,\r\n\tscore=-3.5 required=5.0"
        )
        assertEquals(Check.PASS, auth.spf)
        assertEquals(Check.FAIL, auth.dkim)
        assertEquals(Check.PASS, auth.dmarc)
        assertEquals(-3.5, auth.spamScore)
        assertEquals("This message was changed on the way here, or is not from that sender.", auth.summary)
        assertTrue(auth.worthShowing)
    }

    @Test
    fun testAuthenticityMultipleHeaders() {
        // A forwarder appends its own results below ours. Ours are the ones that count.
        val auth = authenticityOf(
            "mx.example.org; spf=pass; dkim=fail; dmarc=pass\nmx.forwarder.org; spf=fail; dkim=pass; dmarc=fail",
            null
        )
        assertEquals(Check.PASS, auth.spf)
        assertEquals(Check.FAIL, auth.dkim)
        assertEquals(Check.PASS, auth.dmarc)
        assertEquals("This message was changed on the way here, or is not from that sender.", auth.summary)
        assertTrue(auth.worthShowing)
    }

    @Test
    fun testAuthenticityFirstDefiniteResultWithNonDefiniteFirst() {
        // neutral is not an answer, so the definite one further down is the answer.
        val auth = authenticityOf(
            "mx.example.org; spf=neutral\nmx.example.org; spf=fail",
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
}
