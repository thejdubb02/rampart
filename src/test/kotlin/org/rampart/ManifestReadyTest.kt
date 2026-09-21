package org.rampart

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The check that tells "not ready yet" apart from "did not install".
 *
 * Against a real server rather than a stubbed client, because the case this exists for was
 * a real 404 from a real release: on 2026-09-21 a freshly published `rampart.appinstaller`
 * answered 404 anonymously and 200 thirty seconds later, and staging ran in the gap and
 * reported a failed install.
 */
class ManifestReadyTest {
    private fun serving(code: Int, body: String, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rampart.appinstaller") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/rampart.appinstaller")
        } finally {
            server.stop(0)
        }
    }

    private val manifest =
        """<?xml version="1.0" encoding="utf-8"?><AppInstaller Version="0.1.169.0"></AppInstaller>"""

    @Test
    fun `a manifest that is being served is ready`() {
        serving(200, manifest) { assertTrue(Updates.manifestReady(it)) }
    }

    @Test
    fun `a release whose files are not up yet is not ready`() {
        serving(404, "Not Found") { assertFalse(Updates.manifestReady(it)) }
    }

    @Test
    fun `a page that answers 200 with something that is not a manifest is not ready`() {
        // A redirect to an error page is a 200 carrying HTML. Windows would fail on it, and
        // failing there reads as a broken install rather than a release that is still landing.
        serving(200, "<html><body>Not Found</body></html>") { assertFalse(Updates.manifestReady(it)) }
    }

    @Test
    fun `nothing listening is not ready`() {
        // No network is not a failed update. It is a reason to ask again later.
        assertFalse(Updates.manifestReady("http://127.0.0.1:1/rampart.appinstaller"))
    }
}
