package org.rampart.tracker

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The checks that keep /icon from being pointed at the machine it runs on, and the
 * picking of a picture out of a page. None of these open a socket except the route
 * test, which stays on 127.0.0.1 and never resolves a name.
 */
class IconTest {

    private val hash = "ab".repeat(32)
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00,
    )

    @Test
    fun `a domain has to be a name`() {
        assertNull(domainSyntaxProblem("example.com"))
        assertNull(domainSyntaxProblem(" Example.COM. "))
        listOf(
            "127.0.0.1", "10.1.2.3", "8.8.8.8", "127.1",
        ).forEach { assertEquals("ip", domainSyntaxProblem(it), it) }
        assertEquals("localhost", domainSyntaxProblem("localhost"))
        assertEquals("localhost", domainSyntaxProblem("foo.localhost"))
        assertEquals("localhost", domainSyntaxProblem("localhost.localdomain"))
        assertEquals("local name", domainSyntaxProblem("printer.local"))
        assertEquals("local name", domainSyntaxProblem("meta.internal"))
        assertEquals("local name", domainSyntaxProblem("nas.lan"))
        assertEquals("local name", domainSyntaxProblem("box.home"))
        assertEquals("odd character", domainSyntaxProblem("not a domain"))
        assertEquals("odd character", domainSyntaxProblem("evil.com/foo"))
        assertEquals("odd character", domainSyntaxProblem("a_b.com"))
        assertEquals("odd character", domainSyntaxProblem("a@b.com"))
        assertEquals("bad label", domainSyntaxProblem("-bad.com"))
        assertEquals("bad label", domainSyntaxProblem("bad-.com"))
        assertEquals("not a domain", domainSyntaxProblem("com"))
        assertEquals("empty or too long", domainSyntaxProblem(""))
        assertEquals(hash, normalizeEmailHash(hash))
        assertEquals(hash, normalizeEmailHash("  ${hash.uppercase()}  "))
        assertNull(normalizeEmailHash("abc"))
        assertNull(normalizeEmailHash(hash.dropLast(1) + "g"))
    }

    @Test
    fun `a private address is refused after dns`() {
        assertFalse(isPublicAddress(v4(10, 1, 2, 3)))
        assertFalse(isPublicAddress(v4(127, 0, 0, 1)))
        assertFalse(isPublicAddress(v4(172, 16, 0, 1)))
        assertFalse(isPublicAddress(v4(192, 168, 1, 1)))
        assertFalse(isPublicAddress(v4(169, 254, 169, 254)))
        assertFalse(isPublicAddress(v4(100, 64, 0, 1)))
        assertFalse(isPublicAddress(v4(0, 1, 2, 3)))
        assertFalse(isPublicAddress(v4(192, 0, 2, 1)))
        assertFalse(isPublicAddress(v4(198, 51, 100, 1)))
        assertFalse(isPublicAddress(v4(203, 0, 113, 1)))
        assertFalse(isPublicAddress(v4(224, 0, 0, 1)))
        assertTrue(isPublicAddress(v4(172, 32, 0, 1)))
        assertTrue(isPublicAddress(v4(93, 184, 216, 34)))
        assertTrue(isPublicAddress(v4(8, 8, 8, 8)))

        val mapped = ByteArray(16)
        mapped[10] = 0xff.toByte()
        mapped[11] = 0xff.toByte()
        mapped[12] = 10
        mapped[15] = 1
        assertFalse(isPublicAddress(InetAddress.getByAddress(mapped)))

        val ula = ByteArray(16)
        ula[0] = 0xfd.toByte()
        assertFalse(isPublicAddress(InetAddress.getByAddress(ula)))
        val documentation = ByteArray(16)
        documentation[0] = 0x20
        documentation[1] = 0x01
        documentation[2] = 0x0d
        documentation[3] = 0xb8.toByte()
        assertFalse(isPublicAddress(InetAddress.getByAddress(documentation)))

        assertEquals(
            DomainGate.Private,
            domainGate("example.com") { listOf(v4(93, 184, 216, 34), v4(10, 0, 0, 1)) },
        )
        assertEquals(DomainGate.Public, domainGate("example.com") { listOf(v4(93, 184, 216, 34)) })
        assertEquals(DomainGate.Unknown, domainGate("example.com") { emptyList() })
        assertEquals(DomainGate.Unknown, domainGate("example.com") { throw java.net.UnknownHostException("no") })
    }

    @Test
    fun `the largest icon link is the one that is fetched`() {
        val html = """
            <html><head>
            <link rel="stylesheet" href="/style.css">
            <link rel="icon" href="/favicon-16.png" sizes="16x16">
            <link rel="shortcut icon" href="/favicon-32.png" sizes="32x32">
            <link rel="apple-touch-icon" href="/apple.png" sizes="180x180">
            <link rel="icon" href="http://example.com/big.png" sizes="512x512">
            </head></html>
        """.trimIndent()
        assertEquals("https://example.com/apple.png", pickIconUrl(html, "https://example.com/"))

        val flex = """
            <link rel="icon" href="/small.png" sizes="32x32">
            <link rel="icon" href="/flex.png" sizes="any">
        """.trimIndent()
        assertEquals("https://example.com/flex.png", pickIconUrl(flex, "https://example.com/"))
        assertEquals(
            "https://example.com/blog/icon.png",
            httpsUrl("https://example.com/blog/", "icon.png"),
        )
        assertEquals(
            "https://cdn.example.com/a.png",
            httpsUrl("https://example.com/", "//cdn.example.com/a.png"),
        )
        assertNull(httpsUrl("https://example.com/", "http://example.com/a.png"))
        assertNull(httpsUrl("https://example.com/", "javascript:alert(1)"))
        assertNull(httpsUrl("https://example.com/", "data:image/png,xx"))
        assertNull(httpsUrl("https://example.com/", "https://user:pass@example.com/a.png"))
        assertNull(httpsUrl("https://example.com/", "https://example.com:8443/a.png"))
    }

    @Test
    fun `a mail provider does not contribute its own logo`() {
        assertTrue(skipsDomainIcon("gmail.com"))
        assertTrue(skipsDomainIcon("Foo.Gmail.com"))
        assertTrue(skipsDomainIcon("outlook.com"))
        assertTrue(skipsDomainIcon("hotmail.com"))
        assertTrue(skipsDomainIcon("yahoo.com"))
        assertTrue(skipsDomainIcon("icloud.com"))
        assertTrue(skipsDomainIcon("proton.me"))
        assertTrue(skipsDomainIcon("aol.com"))
        assertFalse(skipsDomainIcon("notgmail.com"))
        assertFalse(skipsDomainIcon("gmail.com.evil.com"))
        assertFalse(skipsDomainIcon("example.com"))

        val seen = mutableListOf<String>()
        var resolved = false
        val outcome = lookupIcon("gmail.com", hash, IconTransport { url ->
            seen.add(url)
            IconBody(404, ByteArray(0), "text/plain", url)
        }) {
            resolved = true
            emptyList()
        }
        assertEquals(IconOutcome.Missing, outcome)
        assertFalse(resolved)
        assertTrue(seen.all { URI(it).host == "seccdn.libravatar.org" })
        assertEquals(
            "https://seccdn.libravatar.org/avatar/$hash?s=96&d=404",
            libravatarUrl(hash),
        )
    }

    @Test
    fun `a private name is not fetched and a page icon beats the favicon`() {
        val seen = mutableListOf<String>()
        val denied = lookupIcon("notgmail.com", hash, recording(seen)) { listOf(v4(10, 0, 0, 1)) }
        assertEquals(IconOutcome.BadRequest, denied)
        assertTrue(seen.none { URI(it).host == "notgmail.com" })

        seen.clear()
        val html = """<link rel="apple-touch-icon" href="apple.png" sizes="180x180">"""
        val found = lookupIcon("example.com", hash, IconTransport { url ->
            seen.add(url)
            when {
                url.contains("libravatar.org") -> IconBody(404, ByteArray(0), "text/plain", url)
                url == "https://example.com/" ->
                    IconBody(200, html.toByteArray(), "text/html", "https://example.com/moved/")
                url == "https://example.com/moved/apple.png" -> IconBody(200, png, "image/png", url)
                else -> IconBody(404, ByteArray(0), "text/plain", url)
            }
        }) { listOf(v4(93, 184, 216, 34)) }
        assertTrue(found is IconOutcome.Found)
        assertTrue((found as IconOutcome.Found).image.bytes.contentEquals(png))
        assertFalse(seen.any { it.endsWith("/favicon.ico") })

        seen.clear()
        val svgPage = """
            <link rel="apple-touch-icon" href="/mark.svg" sizes="180x180">
            <link rel="icon" href="/small.png" sizes="16x16">
        """.trimIndent()
        val fellBack = lookupIcon("example.com", hash, IconTransport { url ->
            seen.add(url)
            when {
                url.contains("libravatar.org") -> IconBody(404, ByteArray(0), "text/plain", url)
                url == "https://example.com/" -> IconBody(200, svgPage.toByteArray(), "text/html", url)
                url.endsWith(".svg") -> IconBody(200, "<svg>".toByteArray(), "image/svg+xml", url)
                url.endsWith("/favicon.ico") -> IconBody(200, png, "image/png", url)
                else -> IconBody(404, ByteArray(0), "text/plain", url)
            }
        }) { listOf(v4(93, 184, 216, 34)) }
        assertTrue(fellBack is IconOutcome.Found)
        assertTrue(seen.any { it.endsWith("/favicon.ico") })

        // A homepage we stopped reading is not "no picture": the link may be in the part
        // we never got. An icon that was already in the part we got is still used.
        val cutOff = lookupIcon("example.com", hash, IconTransport { url ->
            when {
                url.contains("libravatar.org") -> IconBody(404, ByteArray(0), "text/plain", url)
                url == "https://example.com/" ->
                    IconBody(200, "<html>no link".toByteArray(), "text/html", url, truncated = true)
                else -> IconBody(404, ByteArray(0), "text/plain", url)
            }
        }) { listOf(v4(93, 184, 216, 34)) }
        assertEquals(IconOutcome.Unavailable, cutOff)

        val fromCut = lookupIcon("example.com", hash, IconTransport { url ->
            when {
                url.contains("libravatar.org") -> IconBody(404, ByteArray(0), "text/plain", url)
                url == "https://example.com/" -> IconBody(
                    200,
                    """<link rel="icon" href="/a.png" sizes="32x32">""".toByteArray(),
                    "text/html",
                    url,
                    truncated = true,
                )
                url.endsWith("/a.png") -> IconBody(200, png, "image/png", url)
                else -> IconBody(404, ByteArray(0), "text/plain", url)
            }
        }) { listOf(v4(93, 184, 216, 34)) }
        assertTrue(fromCut is IconOutcome.Found)
    }

    @Test
    fun `a target has to be public https`() {
        val blocked = { _: String -> listOf(v4(10, 0, 0, 1)) }
        val open = { _: String -> listOf(v4(93, 184, 216, 34)) }
        assertFalse(iconTargetAllowed("https://example.com/", blocked))
        assertTrue(iconTargetAllowed("https://example.com/", open))
        assertFalse(iconTargetAllowed("http://example.com/", open))
        assertFalse(iconTargetAllowed("https://user:pass@example.com/", open))
        assertFalse(iconTargetAllowed("https://example.com:8443/", open))
        assertFalse(iconTargetAllowed("https://127.0.0.1/", open))
    }

    @Test
    fun `only a picture and a confirmed miss are kept`() {
        val dir = Files.createTempDirectory("icons")
        val cache = IconCache(dir, maxEntries = 10, ttlMillis = 1_000)
        cache.put(hash, "example.com", IconOutcome.Missing, now = 0)
        assertEquals(IconOutcome.Missing, cache.get(hash, "example.com", now = 999))
        assertNull(cache.get(hash, "example.com", now = 1_000))

        val transport = IconTransport { null }
        val service = IconService(cache, transport) { listOf(v4(93, 184, 216, 34)) }
        assertEquals(IconOutcome.Unavailable, service.lookup("example.com", hash))
        assertNull(cache.get(hash, "example.com", now = 5))

        val calls = mutableListOf<String>()
        val refused = IconService(cache, recording(calls)) { listOf(v4(10, 0, 0, 1)) }
        assertEquals(IconOutcome.BadRequest, refused.lookup("example.com", hash))
        assertTrue(calls.none { URI(it).host == "example.com" })
        assertNull(cache.get(hash, "example.com", now = 5))
        assertEquals(IconOutcome.BadRequest, refused.lookup("127.0.0.1", hash))
        assertEquals(IconOutcome.BadRequest, refused.lookup("example.com", "nope"))
    }

    @Test
    fun `the icon route wants the main token and a real name`() {
        val dir = Files.createTempDirectory("icons-route")
        val asked = mutableListOf<String>()
        val service = IconService(
            IconCache(dir),
            IconTransport { url ->
                asked.add(url)
                if (url.contains("libravatar.org")) IconBody(200, png, "image/png", url)
                else IconBody(404, ByteArray(0), "text/plain", url)
            },
        ) { listOf(v4(93, 184, 216, 34)) }
        val log = Log(Files.createTempDirectory("tracker").resolve("t.db").toString())
        log.use {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            installRoutes(server, log, "main-token-value", "diag-token-value-ok", icons = service)
            server.start()
            try {
                val base = "http://127.0.0.1:${server.address.port}"
                fun get(path: String, token: String?) = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI("$base$path")).apply {
                        token?.let { header("Authorization", "Bearer $it") }
                    }.GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
                assertEquals(401, get("/icon?domain=example.com&email=$hash", null).statusCode())
                assertEquals(401, get("/icon?domain=example.com&email=$hash", "diag-token-value-ok").statusCode())
                assertEquals(405, HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI("$base/icon?domain=example.com&email=$hash"))
                        .header("Authorization", "Bearer main-token-value")
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode())
                val bad = get("/icon?domain=127.0.0.1&email=$hash", "main-token-value")
                assertEquals(400, bad.statusCode())
                assertTrue(asked.isEmpty())
                val ok = get("/icon?domain=example.com&email=$hash", "main-token-value")
                assertEquals(200, ok.statusCode())
                assertEquals("image/png", ok.headers().firstValue("content-type").orElse(""))
                assertTrue(ok.body().contentEquals(png))
                assertEquals("private, max-age=604800", ok.headers().firstValue("cache-control").orElse(""))
            } finally {
                server.stop(0)
            }
        }
    }

    private fun recording(seen: MutableList<String>): IconTransport = IconTransport { url ->
        seen.add(url)
        IconBody(404, ByteArray(0), "text/plain", url)
    }

    private fun v4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
}
