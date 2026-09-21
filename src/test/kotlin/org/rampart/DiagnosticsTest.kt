package org.rampart

import com.sun.net.httpserver.HttpServer
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The non-negotiable part of this feature: nothing that could identify a person survives
 * [scrub], nothing survives [classify] and [sendFailureCategoryOf] except a fixed category,
 * and nothing that gets aggregated for upload has anywhere to put a message even if one of
 * those two had a bug in it, because [Diagnostics.flush] never reads the local buffer at all.
 * The upload test below asserts that last claim against real bytes on a real socket rather
 * than trusting the code that builds them.
 */
class DiagnosticsTest {

    /**
     * A fresh directory for every test, not merely the first one, unlike
     * [SettingsWriteTest]'s own guard. Several tests here assert on
     * [Settings.diagnosticsReporting]'s *default*, which only shows up on a settings file
     * that has never had that key written to it, so one test's explicit `true` or `false`
     * must never be what the next test's default check is actually reading.
     */
    @BeforeTest
    fun useAFreshScratchDirectory() {
        val dir = java.nio.file.Files.createTempDirectory("rampart-diagnostics-test")
        dir.toFile().deleteOnExit()
        System.setProperty("rampart.config.dir", dir.toString())
        Diagnostics.resetForTests()
    }

    @AfterTest
    fun clearGlobalState() {
        Diagnostics.resetForTests()
    }

    // ---- scrub: the function this feature is not safe to ship without ----

    @Test
    fun `an email address inside a URL is removed, the rest of the URL is not`() {
        val text = "GET https://x.example/reset?email=jdubb@consumerquest.online&token=abc failed"
        val out = scrub(text)
        assertFalse(out.contains("jdubb@consumerquest.online"), out)
        assertTrue(out.contains("https://x.example/reset"), out)
        assertTrue(out.contains("token=abc"), out)
    }

    @Test
    fun `a windows path with a real looking name loses only the name`() {
        val out = scrub("""could not open C:\Users\jdubb\Documents\report.docx""")
        assertFalse(out.contains("jdubb"), out)
        assertEquals("""could not open C:\Users\Documents\report.docx""", out)
    }

    @Test
    fun `a unix home directory loses only the name`() {
        assertEquals("no such file: /home/secret.txt", scrub("no such file: /home/jdubb/secret.txt"))
        assertEquals("no such file: /Users/secret.txt", scrub("no such file: /Users/jdubb/secret.txt"))
    }

    @Test
    fun `an exception message that quotes a header still loses the address inside it`() {
        val text = """Header "X-Original-To: jdubb@consumerquest.online" was rejected by the server"""
        val out = scrub(text)
        assertFalse(out.contains("jdubb@consumerquest.online"), out)
        assertTrue(out.contains("X-Original-To"), out)
        assertTrue(out.contains("was rejected by the server"), out)
    }

    @Test
    fun `ordinary text with no email and no home directory in it is not touched`() {
        assertEquals("Connection refused", scrub("Connection refused"))
        assertEquals("HTTP 503 from the server", scrub("HTTP 503 from the server"))
    }

    // ---- the allowlists, locked so a future edit has to mean it ----

    @Test
    fun `the metric allowlist is exactly the catalog this was built against`() {
        val expected = setOf(
            "message.open.total", "message.open.fetch", "message.open.render",
            "message.open.cache_hit", "message.open.read_ahead_hit",
            "list.load.cold", "list.load.warm", "list.page.load", "list.commands",
            "search.query", "search.fallback",
            "send.compose_to_sent", "send.failure",
            "sync.poll", "sync.push_latency", "sync.push_reconnect",
            "app.startup", "app.crash",
            "update.check", "update.stage", "update.apply",
        )
        assertEquals(expected, Metric.entries.map { it.key }.toSet())
    }

    @Test
    fun `category values are the exact words the catalog and the server agree on`() {
        assertEquals(
            listOf("timeout", "auth", "tls", "dns", "server_error", "parse_error", "unknown"),
            ErrorCategory.entries.map { it.value },
        )
        assertEquals(
            listOf("auth", "connection", "rejected", "timeout", "unknown"),
            SendFailureCategory.entries.map { it.value },
        )
        assertEquals(listOf("newer-found", "current", "check-failed"), UpdateCheckCategory.entries.map { it.value })
        assertEquals(listOf("staged", "not-ready", "failed"), UpdateStageCategory.entries.map { it.value })
        assertEquals(listOf("applied", "failed"), UpdateApplyCategory.entries.map { it.value })
    }

    // ---- classification: reduced to a bucket, never kept as text ----

    @Test
    fun `classify buckets network exceptions the same way the sign-in screen already does`() {
        assertEquals(ErrorCategory.DNS, classify(UnresolvedAddressException()))
        assertEquals(ErrorCategory.DNS, classify(UnknownHostException("mail.example.org")))
        assertEquals(ErrorCategory.TLS, classify(SSLHandshakeException("PKIX path building failed")))
        assertEquals(ErrorCategory.TIMEOUT, classify(SocketTimeoutException("timed out")))
        assertEquals(ErrorCategory.AUTH, classify(RuntimeException("answered 401")))
        assertEquals(ErrorCategory.UNKNOWN, classify(IllegalStateException("disk on fire")))
    }

    @Test
    fun `send failure tells a refusal apart from a server that could not be reached`() {
        assertEquals(
            SendFailureCategory.REJECTED,
            sendFailureCategoryOf(RuntimeException("the server refused the request: invalidProperties")),
        )
        assertEquals(SendFailureCategory.CONNECTION, sendFailureCategoryOf(ConnectException("Connection refused")))
        assertEquals(SendFailureCategory.TIMEOUT, sendFailureCategoryOf(SocketTimeoutException()))
        assertEquals(SendFailureCategory.UNKNOWN, sendFailureCategoryOf(IllegalStateException("disk on fire")))
    }

    @Test
    fun `a crash keeps only the exception's class name, never its message`() {
        Diagnostics.crash(RuntimeException("do not keep this: jdubb@consumerquest.online"))
        val recorded = Diagnostics.recent().first { it.metric == Metric.APP_CRASH.key }
        assertEquals("RuntimeException", recorded.category)
        assertFalse((recorded.detail ?: "").contains("jdubb"), recorded.detail.orEmpty())
        assertFalse((recorded.detail ?: "").contains("do not keep this"), recorded.detail.orEmpty())
    }

    // ---- the engine itself: local recording, on with no toggle and no server ----

    @Test
    fun `time returns the block's value and still records a duration when it throws`() {
        assertEquals(42, Diagnostics.time(Metric.MESSAGE_OPEN_FETCH) { 42 })
        assertTrue(Diagnostics.recent().any { it.metric == Metric.MESSAGE_OPEN_FETCH.key })

        assertFailsWith<IllegalStateException> {
            Diagnostics.time(Metric.MESSAGE_OPEN_FETCH) { throw IllegalStateException("boom") }
        }
        // The block's own exception is what a caller sees; the timing up to the throw is
        // still worth having, so it is recorded rather than lost.
        assertEquals(2, Diagnostics.recent().count { it.metric == Metric.MESSAGE_OPEN_FETCH.key })
    }

    @Test
    fun `the local buffer is capped in size`() {
        repeat(600) { Diagnostics.count(Metric.LIST_COMMANDS) }
        assertTrue(Diagnostics.recent(limit = 1000).size <= 500)
    }

    @Test
    fun `error keeps a scrubbed message locally and an event for the aggregate`() {
        Diagnostics.error(
            Metric.SEND_FAILURE,
            ErrorCategory.AUTH,
            RuntimeException("rejected for jdubb@consumerquest.online"),
        )
        val recorded = Diagnostics.recent().first { it.metric == Metric.SEND_FAILURE.key }
        assertEquals("auth", recorded.category)
        assertFalse((recorded.detail ?: "").contains("jdubb"), recorded.detail.orEmpty())
    }

    // ---- flush: what it does when there is nothing, no toggle, or no address ----

    @Test
    fun `nothing pending sends nothing`() {
        assertEquals(FlushOutcome.NOTHING_TO_SEND, Diagnostics.flush())
    }

    @Test
    fun `local recording works even when reporting is off, and nothing is sent`() {
        Settings.setDiagnosticsServer("https://diagnostics.example.invalid")
        Settings.setDiagnosticsReporting(false)
        Diagnostics.count(Metric.LIST_COMMANDS)
        assertEquals(FlushOutcome.REPORTING_OFF, Diagnostics.flush())
        // The local view is unaffected by the toggle: this is the whole point of keeping
        // the two halves separate.
        assertTrue(Diagnostics.recent().isNotEmpty())
    }

    @Test
    fun `on with nowhere to send it is told apart from off`() {
        Settings.setDiagnosticsServer("")
        // Forced true, because the default when no server is configured is false: this
        // isolates the "no server" branch of flush from the "reporting is off" one above.
        Settings.setDiagnosticsReporting(true)
        Diagnostics.count(Metric.LIST_COMMANDS)
        assertEquals(FlushOutcome.NO_SERVER, Diagnostics.flush())
    }

    @Test
    fun `reporting defaults to on once a server is configured, and off with none`() {
        Settings.setDiagnosticsServer("")
        assertFalse(Settings.diagnosticsReporting())
        Settings.setDiagnosticsServer("https://diagnostics.example.invalid")
        assertTrue(Settings.diagnosticsReporting())
    }

    // ---- what actually crosses the wire ----

    private fun withServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/diag") { exchange ->
            handler(exchange)
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun ok(exchange: com.sun.net.httpserver.HttpExchange) {
        val body = "{}".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    /**
     * The claim the whole file is built on, checked against real bytes sent to a real
     * socket rather than trusted from reading the code: an aggregate carrying a scrubbed
     * local detail still leaves nothing but numbers and a category behind when it is
     * actually POSTed on.
     */
    @Test
    fun `what is sent on the wire carries no message, only the category and numbers`() {
        var capturedBody: String? = null
        var capturedAuth: String? = null
        withServer(
            handler = { exchange ->
                capturedAuth = exchange.requestHeaders.getFirst("Authorization")
                capturedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                ok(exchange)
            },
        ) { url ->
            Settings.setDiagnosticsServer(url)
            Settings.setDiagnosticsReporting(true)
            Diagnostics.error(
                Metric.SEND_FAILURE,
                ErrorCategory.AUTH,
                RuntimeException("rejected for jdubb@consumerquest.online, see C:\\Users\\jdubb\\log.txt"),
            )
            Diagnostics.count(Metric.LIST_COMMANDS, amount = 3)
            assertEquals(FlushOutcome.SENT, Diagnostics.flush())
        }
        val body = capturedBody ?: error("the server never received anything")
        assertTrue(capturedAuth?.startsWith("Bearer") == true, capturedAuth.orEmpty())
        // The whole claim: nothing identifying, wherever it came from, made it into the body.
        assertFalse(body.contains("jdubb"), body)
        assertFalse(body.contains("@"), body)
        assertFalse(body.contains("log.txt"), body)
        // And the two things that were sent are actually in there, by name.
        assertTrue(body.contains("\"metric\":\"send.failure\""), body)
        assertTrue(body.contains("\"category\":\"auth\""), body)
        assertTrue(body.contains("\"metric\":\"list.commands\""), body)
    }

    @Test
    fun `checkServer tells a bad token apart from an address that cannot be reached at all`() {
        withServer(handler = { exchange -> exchange.sendResponseHeaders(401, -1) }) { url ->
            val problem = Diagnostics.checkServer(url, "wrong-token")
            assertNotNull(problem)
            assertTrue(problem.contains("token"), problem)
        }
        val notAUrl = Diagnostics.checkServer("not a url at all", "some-token")
        assertNotNull(notAUrl)
        val plainHttp = Diagnostics.checkServer("http://diagnostics.example.invalid", "some-token")
        assertNotNull(plainHttp)
        assertTrue(plainHttp.contains("https"), plainHttp)
    }

    @Test
    fun `checkServer is satisfied by a working server and a real token`() {
        withServer(handler = { exchange -> ok(exchange) }) { url ->
            assertEquals(null, Diagnostics.checkServer(url, "a-real-token"))
        }
    }

    @Test
    fun `a batch that fails to send is dropped rather than retried forever`() {
        // Nothing is listening on this port, so the send fails and the short retry fails too.
        Settings.setDiagnosticsServer("http://127.0.0.1:1")
        Settings.setDiagnosticsReporting(true)
        Diagnostics.count(Metric.LIST_COMMANDS)
        assertEquals(FlushOutcome.FAILED, Diagnostics.flush())
        // Drained regardless of the failure: a second flush with nothing new pending has
        // nothing to send, rather than growing the same failed batch without bound.
        assertEquals(FlushOutcome.NOTHING_TO_SEND, Diagnostics.flush())
    }
}
