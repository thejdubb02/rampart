package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server's own verdicts, read from the headers it wrote.
 *
 * The X-Spam-Result values are in the shape Stalwart 0.16's spam filter writes them:
 * `TAG (score)`, lowest first, separated by a comma and a fold.
 */
class ServerVerdictTest {

    private val passed = "mx.example.org; spf=pass; dkim=pass header.d=example.com; dmarc=pass"
    private val unvouched = "mx.example.org; spf=none; dkim=none; dmarc=none"

    // ---- viruses ------------------------------------------------------------------------

    @Test
    fun `clamav naming what it found is a virus, with the name and without the host`() {
        val verdict = virusVerdictOf(
            mapOf(
                "X-Virus-Status" to "Infected (Eicar-Test-Signature)",
                "X-Virus-Scanned" to "clamav-milter 1.0.1 at mx.example.org",
            ),
        )!!
        assertTrue(verdict.infected)
        assertEquals("Eicar-Test-Signature", verdict.found)
        assertEquals("clamav-milter 1.0.1", verdict.scanner)
        assertTrue(virusWarning(verdict)!!.because.contains("Eicar-Test-Signature"))
    }

    @Test
    fun `a clean clamav result is clean and says nothing loud`() {
        val verdict = virusVerdictOf(mapOf("X-Virus-Status" to "Clean", "X-Virus-Scanned" to "clamav-milter 1.0.1 at mx"))!!
        assertFalse(verdict.infected)
        assertEquals("clamav-milter 1.0.1", verdict.says)
        assertNull(virusWarning(verdict))
    }

    @Test
    fun `amavis and rspamd are read, and a banned file type is not called a virus`() {
        val amavis = virusVerdictOf(mapOf("X-Amavis-Alert" to "INFECTED, message contains virus: Eicar-Signature"))!!
        assertTrue(amavis.infected)
        assertEquals("Eicar-Signature", amavis.found)

        val rspamd = virusVerdictOf(mapOf("X-Virus" to "Eicar-Test-Signature"))!!
        assertTrue(rspamd.infected)
        assertEquals("Eicar-Test-Signature", rspamd.found)

        val banned = virusVerdictOf(
            mapOf("X-Amavis-Alert" to "BANNED, message contains invoice.exe", "X-Virus-Scanned" to "amavisd-new at example.org"),
        )!!
        assertFalse(banned.infected)
        assertEquals("amavisd-new", banned.scanner)
    }

    @Test
    fun `a message no scanner looked at has no virus verdict at all`() {
        assertNull(virusVerdictOf(emptyMap()))
        assertNull(virusVerdictOf(mapOf("X-Spam-Result" to "DKIM_ALLOW (-0.20)")))
    }

    @Test
    fun `header names are matched whatever their case`() {
        assertTrue(virusVerdictOf(mapOf("x-virus-status" to "infected"))!!.infected)
    }

    // ---- the spam filter's findings -------------------------------------------------------

    @Test
    fun `stalwart's findings are read by name, across folds`() {
        val findings = spamFindings(
            "DKIM_ALLOW (-0.20),\r\n\tDMARC_POLICY_ALLOW (-0.50),\r\n\tSPOOF_DISPLAY_NAME (8.00),\r\n\tHOMOGRAPH_URL (5.00)",
        )!!
        assertEquals(-0.2, findings["DKIM_ALLOW"])
        assertEquals(8.0, findings[StalwartTags.SPOOF_DISPLAY_NAME])
        assertTrue(StalwartTags.HOMOGRAPH_URL in findings)
    }

    @Test
    fun `no header is no verdict, and a header with nothing flagged is a verdict of nothing`() {
        assertNull(spamFindings(null))
        assertNull(spamFindings("   "))
        assertEquals(emptyMap(), spamFindings("garbage without any finding in it"))
    }

    @Test
    fun `a spoofed display name and a homograph link are warned about on the server's word`() {
        val warnings = serverPhishing(
            spamFindings("SPOOF_DISPLAY_NAME (8.00), HOMOGRAPH_URL (5.00)")!!,
            fromEmail = "billing@attacker.example",
            fromName = "security@bank.example",
            replyTo = emptyList(),
            authenticationResults = passed,
        )
        assertEquals(2, warnings.size)
        assertTrue(warnings.any { it.because.contains("security@bank.example") })
        assertTrue(warnings.any { it.says.contains("link") })
    }

    @Test
    fun `a link mismatch or a reply-to on another domain says nothing on mail that was vouched for`() {
        // Every newsletter that routes its clicks through a tracker trips PHISHING, and every
        // ticketing system trips SPOOF_REPLYTO. Honest mail must stay silent.
        val findings = spamFindings("PHISHING (4.00), SPOOF_REPLYTO (3.00)")!!
        assertTrue(serverPhishing(findings, "news@shop.example", "Shop", listOf("help@desk.example"), passed).isEmpty())
        val unvouchedWarnings = serverPhishing(findings, "news@shop.example", "Shop", listOf("help@desk.example"), unvouched)
        assertEquals(2, unvouchedWarnings.size)
        assertTrue(unvouchedWarnings.any { it.says == "Replies would go to desk.example, not to shop.example." })
    }

    @Test
    fun `with a server verdict the local checks it covers are not run`() {
        // The local impersonation check would fire on this. The server looked and found
        // nothing, so nothing is said.
        val warnings = trustWarnings(
            fromEmail = "bounce@mailer.example",
            fromName = "ceo@company.example",
            html = null,
            authenticationResults = passed,
            spamResult = "DKIM_ALLOW (-0.20), FROM_HAS_DN (0.00)",
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `without a server verdict the local checks run as before`() {
        val warnings = trustWarnings(
            fromEmail = "bounce@mailer.example",
            fromName = "ceo@company.example",
            html = null,
            authenticationResults = passed,
            spamResult = null,
        )
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().says.startsWith("The name on this message is an address"))
    }

    @Test
    fun `the checks the server has no counterpart for still run beside its verdict`() {
        val warnings = trustWarnings(
            fromEmail = "service@paypaI.com",
            fromName = "PayPal",
            html = """<form><input type="password" name="p"></form>""",
            authenticationResults = passed,
            spamResult = "DKIM_ALLOW (-0.20)",
        )
        assertTrue(warnings.any { it.says.contains("paypal.com") })
        assertTrue(warnings.any { it.says == "This message asks for a password." })
    }
}
