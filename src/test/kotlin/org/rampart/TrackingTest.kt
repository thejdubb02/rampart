package org.rampart

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Most of this file is about not counting things.
 *
 * An open count that includes robots is worse than no count, because it is a number that
 * lies in the direction you want to believe: it makes somebody chase a lead who never read
 * anything. So the cases that must come back AUTOMATIC matter more than the ones that come
 * back READ.
 */
class TrackingTest {

    private val sent = Instant.parse("2026-09-18T10:00:00Z")

    private fun fetch(after: Long, agent: String) =
        Fetch("abc", sent.plusSeconds(after), agent, "203.0.113.0/24")

    @Test
    fun `an open two seconds after sending is delivery, not a person`() {
        // Nobody has read anything two seconds after it left. This is the receiving server,
        // a filter, or an image proxy fetching everything on the way in.
        assertEquals(Opened.AUTOMATIC, classify(fetch(2, "Mozilla/5.0 Chrome/140"), sent))
        assertEquals(Opened.AUTOMATIC, classify(fetch(29, "Mozilla/5.0 Chrome/140"), sent))
        assertEquals(Opened.READ, classify(fetch(31, "Mozilla/5.0 Chrome/140"), sent))
    }

    @Test
    fun `Gmail's proxy is never a read, however long afterwards it happens`() {
        // The one that matters most: Gmail fetches every image through this on delivery,
        // so on a Gmail recipient the first fetch is always the proxy and never the reader.
        assertEquals(Opened.AUTOMATIC, classify(fetch(4000, "GoogleImageProxy"), sent))
        assertEquals(Opened.AUTOMATIC, classify(fetch(4000, "Mozilla/5.0 (via ggpht.com GoogleImageProxy)"), sent))
    }

    @Test
    fun `scanners and tooling are never a read`() {
        listOf(
            "Proofpoint-Scanner/1.0", "Barracuda", "Mimecast", "BingPreview/1.0b",
            "python-requests/2.31", "curl/8.4.0", "Go-http-client/2.0", "okhttp/4.12",
            "Slackbot-LinkExpanding 1.0", "HeadlessChrome/140",
        ).forEach {
            assertEquals(Opened.AUTOMATIC, classify(fetch(9000, it), sent), "$it was counted as a read")
        }
    }

    @Test
    fun `a real browser well after the send is a read`() {
        listOf("Mozilla/5.0 (Macintosh) AppleWebKit/605 Safari/605", "Mozilla/5.0 Firefox/130")
            .forEach { assertEquals(Opened.READ, classify(fetch(3600, it), sent), it) }
    }

    @Test
    fun `an agent that says nothing useful is unclear, never a read`() {
        // Said as unclear rather than guessed either way, because guessing generously here
        // is exactly how the count starts lying.
        assertEquals(Opened.UNCLEAR, classify(fetch(3600, ""), sent))
        assertEquals(Opened.UNCLEAR, classify(fetch(3600, "SomeMailer/2"), sent))
    }

    @Test
    fun `counting keeps the automatic ones apart from the reads`() {
        val opens = opensOf(
            listOf(
                fetch(3, "GoogleImageProxy"),
                fetch(7200, "Mozilla/5.0 Chrome/140"),
                fetch(9000, "Mozilla/5.0 Chrome/140"),
                fetch(9500, "Proofpoint"),
            ),
            sent,
        )
        assertEquals(2, opens.reads)
        assertEquals(2, opens.automatic)
        assertTrue(opens.wasRead)
        assertEquals(sent.plusSeconds(7200), opens.firstRead)
    }

    @Test
    fun `a message only ever fetched by machines was not read`() {
        val opens = opensOf(listOf(fetch(2, "GoogleImageProxy"), fetch(5000, "Barracuda")), sent)
        assertEquals(0, opens.reads)
        assertFalse(opens.wasRead)
        assertNull(opens.firstRead)
        // And nothing at all is not a read either.
        assertFalse(opensOf(emptyList(), sent).wasRead)
    }

    // ---- the id and the pixel ----------------------------------------------------------

    @Test
    fun `an id is random, and safe in a URL as it stands`() {
        val ids = List(200) { newTrackingId() }
        assertEquals(200, ids.toSet().size)
        ids.forEach { id ->
            assertTrue(id.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "not URL safe: $id")
            // 128 bits, base64 without padding.
            assertEquals(22, id.length)
        }
        assertNotEquals(newTrackingId(), newTrackingId())
    }

    @Test
    fun `the pixel address is built the way the server reads it`() {
        assertEquals("https://img.example.com/o/abc.gif", pixelUrl("https://img.example.com", "abc"))
        // A trailing slash on the setting must not double up.
        assertEquals("https://img.example.com/o/abc.gif", pixelUrl("https://img.example.com/", "abc"))
        assertEquals("https://img.example.com/o/abc.gif", pixelUrl("  https://img.example.com/  ", "abc"))
    }

    @Test
    fun `a plain http server is refused, and said out loud`() {
        // A pixel over http tells every network in between who is mailing whom, which is a
        // worse leak than the one the feature is for.
        assertTrue(trackingProblem("http://img.example.com")!!.contains("https"))
        assertNull(trackingProblem("https://img.example.com"))
        // Loopback is how somebody tests one, so it is allowed.
        assertNull(trackingProblem("http://localhost:8080"))
        assertNull(trackingProblem("http://127.0.0.1:8080"))
        assertTrue(trackingProblem("")!!.isNotBlank())
        assertTrue(trackingProblem("not a url")!!.isNotBlank())
    }

    // ---- what goes into the message ----------------------------------------------------

    @Test
    fun `a tracked plain message gets an HTML part it would not otherwise have had`() {
        // A pixel cannot live in a text part: it would arrive as the tag spelled out in the
        // middle of the message.
        assertNull(htmlBodyOf("Just some words.", "", ""))
        val tracked = htmlBodyOf("Just some words.", "", "", pixelHtml("https://img.example.com", "abc"))
        assertTrue(tracked != null && tracked.contains("img.example.com/o/abc.gif"))
    }

    @Test
    fun `the pixel goes last, after the sign-off`() {
        val body = "Thanks." + signatureBlock("Justin")
        val html = htmlBodyOf(body, "Justin", "<p>Justin</p>", pixelHtml("https://img.example.com", "abc"))!!
        assertTrue(html.indexOf("img.example.com") > html.indexOf("<p>Justin</p>"))
    }

    @Test
    fun `an untracked message is exactly what it was before`() {
        listOf("Just some words.", "Some **bold** words.").forEach { body ->
            assertEquals(htmlBodyOf(body, "", ""), htmlBodyOf(body, "", "", ""))
        }
    }

    @Test
    fun `the pixel says nothing about who it was sent to`() {
        // The id is the only thing in the URL, and it is random: an id that encoded the
        // recipient would leak to every mail server the message passes through.
        val pixel = pixelHtml("https://img.example.com", newTrackingId())
        listOf("dana", "example.org", "@", "subject").forEach {
            assertFalse(pixel.contains(it), "the pixel carries $it")
        }
    }

    @Test
    fun `tracking is remembered by domain rather than by address`() {
        assertEquals("example.org", trackingDomain("Dana Whitfield <dana@example.org>"))
        assertEquals("example.org", trackingDomain("someone.else@example.org"))
    }
}
