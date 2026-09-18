package org.rampart.tracker

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackerTest {

    private fun log(): Log = Log(Files.createTempDirectory("tracker").resolve("t.db").toString())

    @Test
    fun `an address is reduced to its network, never kept whole`() {
        // The whole privacy claim of this service rests on this one function.
        assertEquals("203.0.113.0/24", network("203.0.113.47"))
        assertEquals("2001:db8:1234::/48", network("2001:db8:1234:5678::1"))
        assertEquals("2001:db8:1234::/48", network("[2001:db8:1234:5678::1]"))
    }

    @Test
    fun `a malformed address is dropped rather than stored as given`() {
        // Otherwise a forged X-Forwarded-For is a way to write arbitrary text into the log.
        listOf(null, "", "   ", "not-an-address", "999.1.1.1", "1.2.3", "1.2.3.4.5").forEach {
            assertEquals("", network(it), "network(${it}) kept something")
        }
    }

    @Test
    fun `a fetch survives a round trip, and since means since`() {
        log().use { log ->
            log.record(Fetch("aaa", 1000, "Mozilla", "203.0.113.0/24"))
            log.record(Fetch("bbb", 2000, "Gmail-Image-Proxy", "66.102.0.0/24"))

            assertEquals(listOf("aaa", "bbb"), log.since(0).map { it.id })
            assertEquals(listOf("bbb"), log.since(1000).map { it.id })
            assertEquals(emptyList(), log.since(2000).map { it.id })
            // Oldest first, so a client that stops halfway can carry on from where it got to.
            assertTrue(log.since(0)[0].at < log.since(0)[1].at)
        }
    }

    @Test
    fun `the same pixel fetched twice is two rows, because that is the point`() {
        log().use { log ->
            log.record(Fetch("aaa", 1000, "Mozilla", "203.0.113.0/24"))
            log.record(Fetch("aaa", 9000, "Mozilla", "203.0.113.0/24"))
            assertEquals(2, log.since(0).size)
        }
    }

    @Test
    fun `old rows are thrown away, and recent ones are not`() {
        log().use { log ->
            val now = 400L * 24 * 60 * 60 * 1000
            log.record(Fetch("old", 1, "x", ""))
            log.record(Fetch("new", now, "x", ""))
            assertEquals(1, log.forgetOlderThan(30, now))
            assertEquals(listOf("new"), log.since(0).map { it.id })
            // Zero is "keep everything" rather than "delete everything", which is the
            // dangerous way round to get this wrong.
            assertEquals(0, log.forgetOlderThan(0, now))
            assertEquals(1, log.since(0).size)
        }
    }

    @Test
    fun `a user agent cannot break out of the JSON it is written into`() {
        // It arrives from whatever fetched the pixel, so it is attacker-controlled text.
        assertEquals("\"a\\\"b\"", quoted("""a"b"""))
        assertEquals("\"a\\\\b\"", quoted("""a\b"""))
        assertEquals("\"a\\nb\"", quoted("a\nb"))
        assertEquals("\"\\u0000\"", quoted("\u0000"))
        assertEquals("\"Mozilla/5.0\"", quoted("Mozilla/5.0"))
    }

    @Test
    fun `the token has to match exactly`() {
        assertTrue(sameToken("abcdefghijklmnop", "abcdefghijklmnop"))
        assertFalse(sameToken("abcdefghijklmnoq", "abcdefghijklmnop"))
        assertFalse(sameToken("abcdefghijklmno", "abcdefghijklmnop"))
        assertFalse(sameToken("abcdefghijklmnopq", "abcdefghijklmnop"))
        // An empty one is never right, including against an empty expectation, or a
        // misconfigured server would accept every caller that sent no header at all.
        assertFalse(sameToken("", "abcdefghijklmnop"))
        assertFalse(sameToken("", ""))
    }

    @Test
    fun `the pixel is a real GIF and is as small as one gets`() {
        assertEquals(42, PIXEL.size)
        assertEquals("GIF89a", PIXEL.take(6).map { it.toInt().toChar() }.joinToString(""))
        // Trailer, so a strict decoder does not treat it as truncated.
        assertEquals(0x3B, PIXEL.last().toInt() and 0xFF)
    }
}
