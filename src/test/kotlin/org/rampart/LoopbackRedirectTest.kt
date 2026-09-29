package org.rampart

import java.net.ConnectException
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The browser's way back in: the request parsing on its own, and then a real socket on an
 * ephemeral port, spoken to with a plain Java HTTP client.
 */
class LoopbackRedirectTest {

    private val state = "s3cr3t-state_value"

    @Test
    fun `a callback on the right path with the right state gives the code`() {
        val result = readRedirect("GET /?code=4%2F0Ab_xyz&state=$state&scope=email HTTP/1.1", "/", state, "Google")
        assertEquals(RedirectResult.Code("4/0Ab_xyz"), result)
    }

    @Test
    fun `a wrong or missing state is rejected`() {
        assertEquals(RedirectResult.WrongState, readRedirect("GET /?code=abc&state=other HTTP/1.1", "/", state, "Google"))
        assertEquals(RedirectResult.WrongState, readRedirect("GET /?code=abc HTTP/1.1", "/", state, "Google"))
        assertEquals(RedirectResult.WrongState, readRedirect("GET /?code=abc&state= HTTP/1.1", "/", state, "Google"))
        // One character short is still wrong.
        assertEquals(RedirectResult.WrongState, readRedirect("GET /?code=abc&state=${state.dropLast(1)} HTTP/1.1", "/", state, "Google"))
        // An error with the wrong state cannot end somebody else's sign-in either.
        assertEquals(RedirectResult.WrongState, readRedirect("GET /?error=access_denied&state=nope HTTP/1.1", "/", state, "Google"))
    }

    @Test
    fun `an error comes back as a sentence`() {
        val denied = readRedirect("GET /?error=access_denied&state=$state HTTP/1.1", "/", state, "Google")
        assertEquals(RedirectResult.Refused("The sign-in was declined at Google, so nothing was connected."), denied)
        val other = readRedirect(
            "GET /?error=invalid_request&error_description=AADSTS50011%3A+The+redirect+URI+does+not+match.%0D%0ATrace+ID%3A+1&state=$state HTTP/1.1",
            "/", state, "Microsoft",
        )
        assertIs<RedirectResult.Refused>(other)
        assertEquals("Microsoft did not finish the sign-in: AADSTS50011: The redirect URI does not match.", other.sentence)
        val bare = readRedirect("GET /?error=server_error&state=$state HTTP/1.1", "/", state, "Google")
        assertEquals(RedirectResult.Refused("Google did not finish the sign-in (server_error)."), bare)
    }

    @Test
    fun `anything that is not a callback is ignored`() {
        assertEquals(RedirectResult.NotOurs, readRedirect("GET /favicon.ico HTTP/1.1", "/", state, "Google"))
        assertEquals(RedirectResult.NotOurs, readRedirect("GET /other?code=abc&state=$state HTTP/1.1", "/", state, "Google"))
        assertEquals(RedirectResult.NotOurs, readRedirect("POST /?code=abc&state=$state HTTP/1.1", "/", state, "Google"))
        assertEquals(RedirectResult.NotOurs, readRedirect("GET / HTTP/1.1", "/", state, "Google"))
        assertEquals(RedirectResult.NotOurs, readRedirect("", "/", state, "Google"))
        assertEquals(RedirectResult.NotOurs, readRedirect("garbage", "/", state, "Google"))
    }

    @Test
    fun `the page left in the browser has no script and allows nothing`() {
        val page = String(redirectPage("200 OK", "Signed in <b>&</b>"), Charsets.UTF_8)
        assertTrue(page.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue("Content-Security-Policy: default-src 'none'\r\n" in page)
        assertTrue("Referrer-Policy: no-referrer\r\n" in page)
        assertTrue("Signed in &lt;b&gt;&amp;&lt;/b&gt;" in page)
        assertFalse("<script" in page.lowercase())
        assertFalse("http://" in page.substringAfter("\r\n\r\n"))
        assertFalse("https://" in page.substringAfter("\r\n\r\n"))
        // The house rule on punctuation holds on the page too.
        assertFalse(page.any { it == '\u2013' || it == '\u2014' || it == '\u2026' })
    }

    @Test
    fun `the redirect address names the host the provider expects`() {
        LoopbackRedirect.open().use { redirect ->
            assertTrue(redirect.address.isLoopbackAddress)
            assertEquals("127.0.0.1", redirect.address.hostAddress)
            assertEquals("http://127.0.0.1:${redirect.port}", redirect.redirectUri("127.0.0.1"))
            assertEquals("http://localhost:${redirect.port}", redirect.redirectUri("localhost"))
        }
    }

    private val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build()

    private fun get(port: Int, target: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$target")).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `a real round trip ignores strays, takes the valid callback, and closes`() {
        val redirect = LoopbackRedirect.open()
        val port = redirect.port
        val waiting = CompletableFuture.supplyAsync { redirect.await(state, "Google", Duration.ofSeconds(20)) }

        val stray = get(port, "/favicon.ico")
        assertEquals(404, stray.statusCode())
        val forged = get(port, "/?code=evil&state=wrong")
        assertEquals(400, forged.statusCode())
        assertFalse(waiting.isDone)

        val real = get(port, "/?code=the-real-code&state=$state")
        assertEquals(200, real.statusCode())
        assertTrue("close this tab" in real.body())
        assertEquals("default-src 'none'", real.headers().firstValue("Content-Security-Policy").orElse(""))
        assertEquals("the-real-code", waiting.get(5, TimeUnit.SECONDS))

        // One valid callback and the socket is gone.
        assertFailsWith<ConnectException> { Socket("127.0.0.1", port).close() }
    }

    @Test
    fun `the whole browser half, from the address sent out to tokens held`() {
        var form: Map<String, String> = emptyMap()
        val endpoint = TokenEndpoint { _, sent ->
            form = sent
            200 to """{"access_token":"at","refresh_token":"rt","expires_in":3600}"""
        }
        val signIn = BrowserSignIn(OAuthProviders.GOOGLE, OAuthClient("id.apps", "s"), "me@gmail.com", endpoint)
        val sentOut = queryParams(URI(signIn.url).rawQuery)
        val redirect = sentOut.getValue("redirect_uri")
        assertTrue(redirect.startsWith("http://127.0.0.1:"), redirect)
        val waiting = CompletableFuture.supplyAsync { signIn.await() }

        val back = http.send(
            HttpRequest.newBuilder(URI("$redirect/?code=c0de&state=${sentOut.getValue("state")}")).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, back.statusCode())
        val tokens = waiting.get(10, TimeUnit.SECONDS)
        assertEquals("at", tokens.access)
        assertEquals("rt", tokens.refresh)
        // The verifier sent to the token endpoint is the one the challenge in the address was made from.
        assertEquals("c0de", form["code"])
        assertEquals(redirect, form["redirect_uri"])
        assertEquals(sentOut["code_challenge"], Pkce.challenge(form.getValue("code_verifier")))
    }

    @Test
    fun `Microsoft is sent localhost, with the listener still on the loopback address`() {
        BrowserSignIn(OAuthProviders.MICROSOFT, OAuthClient("id"), "me@outlook.com").use { signIn ->
            assertTrue(signIn.redirectUri.startsWith("http://localhost:"), signIn.redirectUri)
        }
        BrowserSignIn(OAuthProviders.MICROSOFT, OAuthClient("id", redirectHost = "127.0.0.1"), "me@outlook.com").use { signIn ->
            assertTrue(signIn.redirectUri.startsWith("http://127.0.0.1:"), signIn.redirectUri)
        }
    }

    @Test
    fun `a provider error ends the wait as a refusal`() {
        val redirect = LoopbackRedirect.open()
        val waiting = CompletableFuture.supplyAsync {
            runCatching { redirect.await(state, "Google", Duration.ofSeconds(20)) }.exceptionOrNull()
        }
        val answer = get(redirect.port, "/?error=access_denied&state=$state")
        assertEquals(200, answer.statusCode())
        assertTrue("declined" in answer.body())
        val failure = waiting.get(5, TimeUnit.SECONDS)
        assertIs<OAuthFailure.Refused>(failure)
        assertEquals("The sign-in was declined at Google, so nothing was connected.", failure.message)
    }

    @Test
    fun `nothing arriving times out and closes the socket`() {
        val redirect = LoopbackRedirect.open()
        val port = redirect.port
        assertFailsWith<OAuthFailure.TimedOut> { redirect.await(state, "Google", Duration.ofMillis(300)) }
        assertFailsWith<ConnectException> { Socket("127.0.0.1", port).close() }
    }

    @Test
    fun `closing from another thread cancels the wait`() {
        val redirect = LoopbackRedirect.open()
        val waiting = CompletableFuture.supplyAsync {
            runCatching { redirect.await(state, "Google", Duration.ofSeconds(20)) }.exceptionOrNull()
        }
        Thread.sleep(200)
        redirect.close()
        assertIs<OAuthFailure.Cancelled>(waiting.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a connection that says nothing does not block the real callback`() {
        val redirect = LoopbackRedirect.open()
        val port = redirect.port
        val waiting = CompletableFuture.supplyAsync { redirect.await(state, "Google", Duration.ofSeconds(20)) }
        // Opens and sends nothing; the listener gives up on it after its read timeout.
        Socket("127.0.0.1", port).use { silent ->
            val real = CompletableFuture.supplyAsync { get(port, "/?code=ok&state=$state") }
            assertEquals("ok", waiting.get(15, TimeUnit.SECONDS))
            assertEquals(200, real.get(10, TimeUnit.SECONDS).statusCode())
            assertTrue(silent.isConnected)
        }
    }
}
