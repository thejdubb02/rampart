package org.rampart

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateCheckTest {
    private fun serving(
        latestCode: Int,
        latestBody: String,
        manifestCode: Int = 404,
        manifestBody: String = "Not Found",
        block: (String, String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun endpoint(path: String, code: Int, body: String) {
            server.createContext(path) { exchange ->
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        endpoint("/latest", latestCode, latestBody)
        endpoint("/rampart.appinstaller", manifestCode, manifestBody)
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            block("$base/latest", "$base/rampart.appinstaller")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `newer current and every failure have fixed diagnostic categories`() {
        assertEquals(UpdateCheckCategory.NEWER_FOUND, updateCheckCategory(UpdateCheckResult.Newer("0.1.2")))
        assertEquals(UpdateCheckCategory.CURRENT, updateCheckCategory(UpdateCheckResult.Current))
        val expected = mapOf(
            UpdateCheckFailure.NO_VERSION to UpdateCheckCategory.FAILED_NO_VERSION,
            UpdateCheckFailure.HTTP_3XX to UpdateCheckCategory.FAILED_HTTP_3XX,
            UpdateCheckFailure.HTTP_403 to UpdateCheckCategory.FAILED_HTTP_403,
            UpdateCheckFailure.HTTP_404 to UpdateCheckCategory.FAILED_HTTP_404,
            UpdateCheckFailure.HTTP_429 to UpdateCheckCategory.FAILED_HTTP_429,
            UpdateCheckFailure.HTTP_4XX to UpdateCheckCategory.FAILED_HTTP_4XX,
            UpdateCheckFailure.HTTP_5XX to UpdateCheckCategory.FAILED_HTTP_5XX,
            UpdateCheckFailure.HTTP_OTHER to UpdateCheckCategory.FAILED_HTTP_OTHER,
            UpdateCheckFailure.NETWORK to UpdateCheckCategory.FAILED_NETWORK,
            UpdateCheckFailure.PARSE to UpdateCheckCategory.FAILED_PARSE,
        )
        assertEquals(UpdateCheckFailure.entries.toSet(), expected.keys)
        expected.forEach { (reason, category) ->
            assertEquals(category, updateCheckCategory(UpdateCheckResult.Failed(reason)))
        }
    }

    @Test
    fun `candidate versions map to newer or current`() {
        assertEquals(UpdateCheckResult.Newer("0.1.273"), Updates.resultFor("0.1.273", "0.1.272"))
        assertEquals(UpdateCheckResult.Current, Updates.resultFor("0.1.272", "0.1.272"))
    }

    @Test
    fun `a missing local version is a failure without making a request`() {
        assertEquals(
            UpdateCheckResult.Failed(UpdateCheckFailure.NO_VERSION),
            Updates.newerVersion(null, "not a URI", "not a URI"),
        )
    }

    @Test
    fun `a refused API request falls back to the published manifest`() {
        val manifest = """<AppInstaller><MainPackage Version="0.1.273.0" /></AppInstaller>"""
        serving(403, "rate limited", 200, manifest) { latest, appinstaller ->
            assertEquals(
                UpdateCheckResult.Newer("0.1.273"),
                Updates.newerVersion("0.1.272", latest, appinstaller),
            )
        }
    }

    @Test
    fun `bad release JSON is a parse failure`() {
        serving(200, "not JSON") { latest, appinstaller ->
            assertEquals(
                UpdateCheckResult.Failed(UpdateCheckFailure.PARSE),
                Updates.newerVersion("0.1.272", latest, appinstaller),
            )
        }
    }

    @Test
    fun `every failure has fixed plain words for the About page`() {
        UpdateCheckFailure.entries.forEach { reason ->
            val message = updateFailureMessage(reason)
            check(message.isNotBlank())
            check(message.endsWith('.'))
        }
    }
}
