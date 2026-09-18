package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Headers taken from real mail, with the domains changed. The shapes matter more than the
 * values: every one of these is a place where a regex that looked right on one example
 * reads the wrong half of the line on the next.
 */
class DetailsTest {
    private val real =
        "mx.skybox7.com; dkim=pass header.d=skybox7.com header.s=s1; " +
            "spf=pass smtp.mailfrom=cfbounces+ndrdrop@skybox7.com; " +
            "dmarc=pass (p=none sp=none dis=none) header.from=skybox7.com"

    @Test
    fun `each check comes back with what it was checked against`() {
        val checks = authChecks(real).associateBy { it.label }
        assertEquals(Check.PASS, checks.getValue("SPF").verdict)
        assertEquals("cfbounces+ndrdrop@skybox7.com", checks.getValue("SPF").value)
        assertEquals("skybox7.com", checks.getValue("DKIM").value)
        assertEquals("policy: none", checks.getValue("DMARC").value)
    }

    @Test
    fun `a fail is a fail and a softfail is not a pass`() {
        val checks = authChecks("mx.test; spf=softfail smtp.mailfrom=a@b.test; dkim=fail")
            .associateBy { it.label }
        assertEquals(Check.FAIL, checks.getValue("SPF").verdict)
        assertEquals(Check.FAIL, checks.getValue("DKIM").verdict)
        assertEquals(Check.MISSING, checks.getValue("DMARC").verdict)
    }

    @Test
    fun `nothing at all is three missing checks rather than a crash`() {
        assertEquals(3, authChecks(null).size)
        assertTrue(authChecks(null).all { it.verdict == Check.MISSING })
        assertTrue(authChecks("").all { it.value.isBlank() })
    }

    @Test
    fun `only our own server's line is read`() {
        // A forwarder further down must not be able to vouch for a message ours could not
        // verify. The topmost line is the one our server wrote.
        val stacked = "mx.skybox7.com; dkim=fail\nrelay.elsewhere.test; dkim=pass"
        assertEquals(Check.FAIL, authChecks(stacked).first { it.label == "DKIM" }.verdict)
    }

    @Test
    fun `a folded header still gives up its results`() {
        val folded = "mx.skybox7.com;\r\n\tspf=pass smtp.mailfrom=a@skybox7.com"
        assertEquals(Check.PASS, authChecks(folded).first { it.label == "SPF" }.verdict)
    }

    @Test
    fun `the hop that handed it over is named and addressed`() {
        val host = senderHost(
            listOf("from a48-96.smtp-out.amazonses.com (a48-96.smtp-out.amazonses.com [54.240.48.96]) by mx.skybox7.com"),
        )
        assertEquals("Reverse DNS", host?.label)
        assertTrue(host!!.value.contains("54.240.48.96"))
        assertTrue(host.value.contains("amazonses.com"))
    }

    @Test
    fun `only the topmost Received is read, because the rest is the sender's own claim`() {
        val host = senderHost(listOf("from real.test ([10.0.0.1]) by mx", "from liar.test ([10.0.0.9]) by relay"))
        assertTrue(host!!.value.contains("real.test"))
        assertTrue(!host.value.contains("liar.test"))
    }

    @Test
    fun `no Received at all is nothing to show rather than an empty row`() {
        assertNull(senderHost(emptyList()))
        assertNull(senderHost(listOf("by mx.skybox7.com with LMTP")))
    }

    @Test
    fun `via names the sending domain only when it differs from the one claimed`() {
        assertEquals(
            "amazonses.com",
            sentVia("admin@skybox7.com", "mx; spf=pass smtp.mailfrom=bounce@amazonses.com"),
        )
        assertNull(sentVia("admin@skybox7.com", "mx; spf=pass smtp.mailfrom=a@skybox7.com"))
        // A subdomain bouncing for its own parent is one organisation, not a via.
        assertNull(sentVia("admin@skybox7.com", "mx; spf=pass smtp.mailfrom=a@bounces.skybox7.com"))
        assertNull(sentVia("admin@skybox7.com", null))
    }

    @Test
    fun `sizes read the way a mail client shows them`() {
        assertEquals("", humanBytes(0))
        assertEquals("812 B", humanBytes(812))
        assertEquals("1.00 KB", humanBytes(1024))
        assertEquals("152.13 KB", humanBytes(155_781))
        assertEquals("1.49 MB", humanBytes(1_560_281))
    }

    @Test
    fun `recipients past the first few become a count`() {
        val (shown, more) = shownRecipients(
            listOf("a@test", "b@test", "c@test", "d@test", "e@test"),
        )
        assertEquals(listOf("a@test", "b@test"), shown)
        assertEquals(3, more)
    }

    @Test
    fun `a short list has nothing left over`() {
        val (shown, more) = shownRecipients(listOf("a@test"))
        assertEquals(listOf("a@test"), shown)
        assertEquals(0, more)
    }

    @Test
    fun `sent and received are separate rows so the gap between them shows`() {
        val rows = handling("Fri, 18 Sep 2026 07:01:01 -0700", "Fri, 18 Sep 2026 07:01:15 -0700")
        assertEquals(listOf("Sent", "Received"), rows.map { it.label })
        assertEquals(0, handling(null, "").size)
    }
}
