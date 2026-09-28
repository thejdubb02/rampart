package org.rampart

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The addresses a phone is given, and the Apple configuration profile built from them.
 *
 * Nothing here reaches a server: Stalwart's own `/dav/cal/` and `/dav/card/` are fixed paths,
 * documented live on `mail.willhitestrategy.org`, so what is worth testing is that Rampart
 * builds them from the account's own server the same way the rest of sign-in does, and that
 * the profile it hands an iPhone is well formed and carries no secret.
 */
class PhoneSetupTest {

    // ---- addresses, from the account's own server string -----------------------------

    @Test
    fun `the calendar and contacts addresses are Stalwart's fixed DAV paths under the account's host`() {
        assertEquals("https://mail.willhitestrategy.org/dav/cal/", caldavUrl("mail.willhitestrategy.org"))
        assertEquals("https://mail.willhitestrategy.org/dav/card/", carddavUrl("mail.willhitestrategy.org"))
    }

    @Test
    fun `the host comes from the account's saved server string, however it was typed at sign-in`() {
        // The same forms sign-in itself accepts: a bare host, a base URL, and a JMAP endpoint
        // URL, all saved as SavedAccount.server and all meaning the same host.
        for (server in listOf(
            "mail.willhitestrategy.org",
            "https://mail.willhitestrategy.org",
            "https://mail.willhitestrategy.org/",
            "https://mail.willhitestrategy.org/jmap",
        )) {
            val host = hostOfServer(server)
            assertEquals("mail.willhitestrategy.org", host, "for server string \"$server\"")
            assertEquals("https://mail.willhitestrategy.org/dav/cal/", caldavUrl(host))
            assertEquals("https://mail.willhitestrategy.org/dav/card/", carddavUrl(host))
        }
    }

    @Test
    fun `a saved server with its own port keeps the host only, the same as everywhere else that reads it`() {
        assertEquals("mail.example.org", hostOfServer("mail.example.org:8443"))
    }

    // ---- when the page has nothing to offer -------------------------------------------

    @Test
    fun `there is nothing here before a sign-in, on IMAP, or on a server with neither capability`() {
        assertTrue(phoneSetupUnavailable(null, null, false)!!.contains("Sign in"))
        assertTrue(phoneSetupUnavailable("imap", null, false)!!.contains("IMAP"))
        assertTrue(phoneSetupUnavailable("jmap", "a", false)!!.contains("advertise"))
        // Advertised but not Stalwart is its own reason, not folded into "does not advertise".
        assertTrue(phoneSetupUnavailable("jmap", null, true)!!.contains("Stalwart"))
    }

    @Test
    fun `the page is available on Stalwart with either calendars or contacts advertised`() {
        assertNull(phoneSetupUnavailable("jmap", "a", true))
    }

    // ---- the .mobileconfig itself -------------------------------------------------------

    @Test
    fun `the profile carries one CalDAV and one CardDAV payload, both for the account's own host`() {
        val xml = mobileConfig("mail.willhitestrategy.org", "justin@willhitestrategy.org")

        assertTrue(xml.contains("com.apple.caldav.account"))
        assertTrue(xml.contains("com.apple.carddav.account"))
        assertTrue(xml.contains("<key>CalDAVHostName</key>"))
        assertTrue(xml.contains("<key>CardDAVHostName</key>"))
        // The host string itself appears at least twice: once for each payload.
        assertEquals(2, Regex("mail\\.willhitestrategy\\.org").findAll(xml).count())
        assertTrue(xml.contains("justin@willhitestrategy.org"))
        assertTrue(xml.contains("<key>CalDAVPort</key>"))
        assertTrue(xml.contains("<integer>443</integer>"))
        assertTrue(xml.contains("<key>CalDAVUseSSL</key>"))
        assertTrue(xml.contains("<true/>"))
    }

    @Test
    fun `the profile never writes a password, so iOS has to ask for one itself`() {
        val xml = mobileConfig("mail.willhitestrategy.org", "justin@willhitestrategy.org")
        assertFalse(xml.contains("Password", ignoreCase = true))
    }

    @Test
    fun `every payload identifier is under org-rampart, and every id in the profile is unique`() {
        val xml = mobileConfig("mail.willhitestrategy.org", "justin@willhitestrategy.org")
        val doc = parsePlist(xml)
        val identifiers = stringsAfterKey(doc, "PayloadIdentifier")
        val uuids = stringsAfterKey(doc, "PayloadUUID")

        assertEquals(3, identifiers.size, "top level plus one per DAV payload")
        assertTrue(identifiers.all { it.startsWith("org.rampart.") }, identifiers.toString())
        assertEquals(identifiers.toSet().size, identifiers.size, "no two payloads share an identifier")

        assertEquals(3, uuids.size)
        assertEquals(uuids.toSet().size, uuids.size, "no two payloads share a PayloadUUID")
    }

    @Test
    fun `the profile is a plist a real parser accepts, with no network reached to do it`() {
        val xml = mobileConfig("mail.willhitestrategy.org", "justin@willhitestrategy.org")
        val doc = parsePlist(xml)
        assertEquals("plist", doc.documentElement.tagName)
    }

    /**
     * Parses [xml] as XML, the way a strict consumer of this file would, with external DTD
     * and entity loading turned off.
     *
     * Apple's own DOCTYPE line names a DTD on apple.com. A default `DocumentBuilderFactory`
     * will try to fetch it even though nothing here validates against it, which would make
     * this test depend on the network for no reason and is the well known way an XML parser
     * is tricked into fetching or reading something it was never asked to. Both are switched
     * off, matching how any file this app is handed from outside should be parsed.
     */
    private fun parsePlist(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.isExpandEntityReferences = false
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    /** Every `<string>` value that immediately follows a `<key>name</key>` element. */
    private fun stringsAfterKey(doc: Document, name: String): List<String> {
        val out = mutableListOf<String>()
        val keys = doc.getElementsByTagName("key")
        for (i in 0 until keys.length) {
            val key = keys.item(i) as Element
            if (key.textContent != name) continue
            var sibling = key.nextSibling
            while (sibling != null && sibling !is Element) sibling = sibling.nextSibling
            if (sibling is Element && sibling.tagName == "string") out += sibling.textContent
        }
        return out
    }
}
