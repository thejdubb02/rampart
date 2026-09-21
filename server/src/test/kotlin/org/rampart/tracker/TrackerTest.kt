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
    fun `diag takes either token, opens would only ever take the first`() {
        // The main token still works on its own, the same as before this split existed.
        assertTrue(diagAuthorised("main-token-value", "main-token-value", null))
        assertTrue(diagAuthorised("main-token-value", "main-token-value", "public-token-value"))
        // The public one works too, but only here: nothing calls diagAuthorised for /opens.
        assertTrue(diagAuthorised("public-token-value", "main-token-value", "public-token-value"))
        assertFalse(diagAuthorised("someone-elses-guess", "main-token-value", "public-token-value"))
        // No public token configured: only the main one is accepted, same as before.
        assertFalse(diagAuthorised("public-token-value", "main-token-value", null))
    }

    @Test
    fun `the pixel is a real GIF and is as small as one gets`() {
        assertEquals(42, PIXEL.size)
        assertEquals("GIF89a", PIXEL.take(6).map { it.toInt().toChar() }.joinToString(""))
        // Trailer, so a strict decoder does not treat it as truncated.
        assertEquals(0x3B, PIXEL.last().toInt() and 0xFF)
    }

    // ---- diagnostics: what /diag accepts, what it stores, and what it never does ----

    @Test
    fun `a batch survives a round trip, stamped with when the companion received it`() {
        log().use { log ->
            log.recordDiagnostics(
                listOf(
                    DiagAggregate("message.open.total", null, 4, 620.0, 40.0, 300.0, at = 1000L),
                    DiagAggregate("send.failure", "auth", 2, null, null, null, at = 2000L),
                ),
            )
            val rows = log.diagnosticsSince(0)
            assertEquals(2, rows.size)
            assertEquals("message.open.total", rows[0].metric)
            assertEquals(null, rows[0].category)
            assertEquals(4L, rows[0].count)
            assertEquals(620.0, rows[0].sum)
            assertEquals("auth", rows[1].category)
            assertEquals(null, rows[1].sum)
        }
    }

    @Test
    fun `old diagnostics rows are thrown away with the fetches, on the one knob`() {
        log().use { log ->
            val now = 400L * 24 * 60 * 60 * 1000
            log.record(Fetch("old-fetch", 1, "x", ""))
            log.record(Fetch("new-fetch", now, "x", ""))
            log.recordDiagnostics(listOf(DiagAggregate("app.startup", null, 1, 900.0, 900.0, 900.0, at = 1)))
            log.recordDiagnostics(listOf(DiagAggregate("app.startup", null, 1, 900.0, 900.0, 900.0, at = now)))
            // Two old rows, one from each table, one removed count.
            assertEquals(2, log.forgetOlderThan(30, now))
            assertEquals(listOf("new-fetch"), log.since(0).map { it.id })
            assertEquals(1, log.diagnosticsSince(0).size)
        }
    }

    @Test
    fun `a well formed batch parses into exactly the aggregates it named`() {
        val body = """
            {"items":[
                {"metric":"message.open.total","count":3,"sum":361.5,"min":40.0,"max":210.0},
                {"metric":"send.failure","category":"auth","count":1}
            ]}
        """.trimIndent()
        val items = parseDiagBatch(body)
        assertEquals(2, items.size)
        assertEquals("message.open.total", items[0].metric)
        assertEquals(null, items[0].category)
        assertEquals(3L, items[0].count)
        assertEquals(361.5, items[0].sum)
        assertEquals("auth", items[1].category)
        assertEquals(null, items[1].sum)
    }

    @Test
    fun `a metric outside the allowlist is not stored, whatever else the batch contains`() {
        val body = """{"items":[{"metric":"subject.of.the.message","count":1}]}"""
        assertEquals(emptyList(), parseDiagBatch(body))
    }

    @Test
    fun `a batch with no positive count, or no items array, or no JSON at all, parses as nothing`() {
        assertEquals(emptyList(), parseDiagBatch("""{"items":[{"metric":"app.startup","count":0}]}"""))
        assertEquals(emptyList(), parseDiagBatch("""{"items":[{"metric":"app.startup","count":-1}]}"""))
        assertEquals(emptyList(), parseDiagBatch("""{"nothing":"here"}"""))
        assertEquals(emptyList(), parseDiagBatch("not json at all"))
        assertEquals(emptyList(), parseDiagBatch(""))
        assertEquals(emptyList(), parseDiagBatch("""{"items":[{"metric":"app.startup"}]}"""))
    }

    @Test
    fun `a batch cannot carry a per message anything, because there is nowhere in the shape to put one`() {
        // Even a client that tried to smuggle a subject or an address through has nowhere
        // for it to land: fields the shape does not have are simply not read.
        val body = """
            {"items":[{
                "metric":"send.failure","category":"auth","count":1,
                "subject":"Q3 numbers","recipient":"jdubb@consumerquest.online"
            }]}
        """.trimIndent()
        val items = parseDiagBatch(body)
        assertEquals(1, items.size)
        assertEquals("send.failure", items[0].metric)
        assertEquals("auth", items[0].category)
    }

    @Test
    fun `the JSON reader handles the one escape a category token could plausibly contain`() {
        assertEquals(mapOf("a" to "x\"y"), parseJson("""{"a":"x\"y"}"""))
        // Truncated input is read as far as it goes rather than thrown on: an object with
        // nothing in it yet is still a valid, if empty, object.
        assertEquals(emptyMap<String, Any?>(), parseJson("{"))
        assertEquals(null, parseJson(""))
    }
}
