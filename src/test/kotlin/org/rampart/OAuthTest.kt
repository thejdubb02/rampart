package org.rampart

import java.net.URI
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.security.auth.callback.CallbackHandler
import javax.security.auth.callback.NameCallback
import javax.security.auth.callback.PasswordCallback
import javax.security.sasl.Sasl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The OAuth sign-in with no network and no browser: PKCE, the authorization address, the
 * token endpoint's answers, refresh timing, and the strings IMAP and SMTP are sent.
 */
class OAuthTest {

    private val google = OAuthProviders.GOOGLE
    private val microsoft = OAuthProviders.MICROSOFT
    private val client = OAuthClient("test-client.apps.example", "not-a-secret")

    /** A clock that only moves when a test moves it. */
    private class HandClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    // ---- PKCE ------------------------------------------------------------------------

    @Test
    fun `the verifier is 43 to 128 unreserved characters`() {
        val random = SecureRandom()
        listOf(43, 64, 128).forEach { length ->
            val verifier = Pkce.verifier(random, length)
            assertEquals(length, verifier.length)
            assertTrue(verifier.all { it in Pkce.UNRESERVED }, verifier)
        }
        assertEquals(64, Pkce.verifier().length)
        assertFailsWith<IllegalArgumentException> { Pkce.verifier(random, 42) }
        assertFailsWith<IllegalArgumentException> { Pkce.verifier(random, 129) }
    }

    @Test
    fun `two verifiers are never the same`() {
        assertTrue(Pkce.verifier() != Pkce.verifier())
        assertTrue(newState() != newState())
    }

    @Test
    fun `the S256 challenge matches RFC 7636 appendix B`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `the state is url safe and unpadded`() {
        val state = newState()
        assertTrue(state.all { it.isLetterOrDigit() || it == '-' || it == '_' }, state)
        assertEquals(43, state.length)
    }

    // ---- the authorization address ------------------------------------------------------

    private fun query(url: String): Map<String, String> = queryParams(URI(url).rawQuery)

    @Test
    fun `the authorization address carries every parameter, encoded`() {
        val url = authorizationUrl(google, client, "http://127.0.0.1:5123", "CHALLENGE", "STATE", "someone+tag@gmail.com")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"), url)
        val q = query(url)
        assertEquals("code", q["response_type"])
        assertEquals("test-client.apps.example", q["client_id"])
        assertEquals("http://127.0.0.1:5123", q["redirect_uri"])
        assertEquals("https://mail.google.com/ openid email", q["scope"])
        assertEquals("STATE", q["state"])
        assertEquals("CHALLENGE", q["code_challenge"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("someone+tag@gmail.com", q["login_hint"])
        assertEquals("offline", q["access_type"])
        assertEquals("consent", q["prompt"])
        // Spaces as %20, and the reserved characters of the redirect and the hint escaped.
        assertTrue("scope=https%3A%2F%2Fmail.google.com%2F%20openid%20email" in url, url)
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A5123" in url, url)
        assertTrue("login_hint=someone%2Btag%40gmail.com" in url, url)
        assertFalse(" " in url)
    }

    @Test
    fun `Microsoft is asked for a refresh token and the tenant lands in the path`() {
        val url = authorizationUrl(microsoft, OAuthClient("id", tenant = "consumers"), "http://localhost:5123", "C", "S", null)
        assertTrue(url.startsWith("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?"), url)
        val scopes = query(url)["scope"].orEmpty().split(' ')
        assertTrue("offline_access" in scopes)
        assertTrue("https://outlook.office.com/IMAP.AccessAsUser.All" in scopes)
        assertTrue("https://outlook.office.com/SMTP.Send" in scopes)
        assertNull(query(url)["login_hint"])
        assertNull(query(url)["client_secret"])
    }

    @Test
    fun `nothing secret goes in the browser address`() {
        val verifier = Pkce.verifier()
        val url = authorizationUrl(google, client, "http://127.0.0.1:1", Pkce.challenge(verifier), "S", null)
        assertFalse(verifier in url)
        assertFalse("not-a-secret" in url)
    }

    // ---- the token endpoint -------------------------------------------------------------

    private val t0: Instant = Instant.parse("2026-09-29T12:00:00Z")

    private fun idToken(claims: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{\"alg\":\"RS256\"}".toByteArray()) + "." +
            enc.encodeToString(claims.toByteArray()) + ".c2lnbmF0dXJl"
    }

    @Test
    fun `a token response gives an expiry from expires_in`() {
        val tokens = parseTokenResponse(
            google, 200,
            """{"access_token":"ya29.a","expires_in":3599,"refresh_token":"1//r","token_type":"Bearer","scope":"https://mail.google.com/"}""",
            null, t0,
        )
        assertEquals("ya29.a", tokens.access)
        assertEquals("1//r", tokens.refresh)
        assertEquals(t0.plusSeconds(3599), tokens.expiresAt)
    }

    @Test
    fun `expires_in as a string is read, and a missing one is taken as an hour`() {
        val quoted = parseTokenResponse(google, 200, """{"access_token":"a","expires_in":"120"}""", null, t0)
        assertEquals(t0.plusSeconds(120), quoted.expiresAt)
        val missing = parseTokenResponse(google, 200, """{"access_token":"a"}""", null, t0)
        assertEquals(t0.plusSeconds(3600), missing.expiresAt)
    }

    @Test
    fun `a rotated refresh token replaces the old one`() {
        val old = OAuthTokens("old-access", "old-refresh", t0)
        val fresh = parseTokenResponse(microsoft, 200, """{"access_token":"new","refresh_token":"rotated","expires_in":3600}""", old, t0)
        assertEquals("rotated", fresh.refresh)
    }

    @Test
    fun `no refresh token in the answer keeps the old one`() {
        val old = OAuthTokens("old-access", "keep-me", t0, email = "someone@gmail.com")
        val fresh = parseTokenResponse(google, 200, """{"access_token":"new","expires_in":3600}""", old, t0)
        assertEquals("keep-me", fresh.refresh)
        assertEquals("new", fresh.access)
        assertEquals("someone@gmail.com", fresh.email)
    }

    @Test
    fun `the signed-in address is read from the ID token`() {
        val google = parseTokenResponse(
            this.google, 200,
            """{"access_token":"a","id_token":"${idToken("""{"email":"picked@gmail.com","sub":"1"}""")}"}""",
            null, t0,
        )
        assertEquals("picked@gmail.com", google.email)
        // Microsoft often leaves email out and sends the sign-in name instead.
        assertEquals("someone@contoso.example", emailFromIdToken(idToken("""{"preferred_username":"someone@contoso.example"}""")))
        assertNull(emailFromIdToken("not a token"))
        assertNull(emailFromIdToken(idToken("""{"preferred_username":"no-at-sign"}""")))
    }

    @Test
    fun `a token that is not Bearer, or no token at all, is refused`() {
        assertFailsWith<OAuthFailure.Refused> { parseTokenResponse(google, 200, """{"access_token":"a","token_type":"mac"}""", null, t0) }
        assertFailsWith<OAuthFailure.Refused> { parseTokenResponse(google, 200, """{"expires_in":3600}""", null, t0) }
        assertFailsWith<OAuthFailure.Refused> { parseTokenResponse(google, 200, "<html>", null, t0) }
    }

    @Test
    fun `invalid_grant means sign in again, not a connection failure`() {
        val failure = assertFailsWith<OAuthFailure.SignInAgain> {
            parseTokenResponse(google, 400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""", null, t0)
        }
        assertTrue("Google" in failure.message.orEmpty())
        assertTrue("again" in failure.message.orEmpty())
        assertFalse("connection" in failure.message.orEmpty().lowercase())
        assertFailsWith<OAuthFailure.SignInAgain> {
            parseTokenResponse(microsoft, 400, """{"error":"interaction_required","error_description":"AADSTS50076: MFA\r\nTrace ID: x"}""", null, t0)
        }
    }

    @Test
    fun `other refusals are sorted by what fixes them`() {
        val client = assertFailsWith<OAuthFailure.Refused> {
            parseTokenResponse(google, 401, """{"error":"invalid_client","error_description":"The OAuth client was deleted."}""", null, t0)
        }
        assertTrue("client ID" in client.message.orEmpty())
        assertTrue("deleted" in client.message.orEmpty())
        assertFailsWith<OAuthFailure.Network> { parseTokenResponse(google, 503, "Service Unavailable", null, t0) }
        assertFailsWith<OAuthFailure.Network> { parseTokenResponse(google, 400, """{"error":"temporarily_unavailable"}""", null, t0) }
        // An error in a 200 is still an error.
        assertFailsWith<OAuthFailure.SignInAgain> { parseTokenResponse(google, 200, """{"error":"invalid_grant"}""", null, t0) }
    }

    @Test
    fun `a provider's description is cut to its first line`() {
        val said = plainProviderText("AADSTS70000: The grant is expired.\r\nTrace ID: 1234\r\nCorrelation ID: 5678")
        assertEquals("AADSTS70000: The grant is expired.", said)
        assertTrue(plainProviderText("x".repeat(500)).length <= 201)
    }

    @Test
    fun `the code exchange and the refresh send what the RFCs ask for`() {
        val sent = mutableListOf<Pair<String, Map<String, String>>>()
        val endpoint = TokenEndpoint { url, form ->
            sent += url to form
            200 to """{"access_token":"a","refresh_token":"r","expires_in":3600}"""
        }
        exchangeCode(google, client, "the-code", "the-verifier", "http://127.0.0.1:9", endpoint, t0)
        val (url, form) = sent.single()
        assertEquals("https://oauth2.googleapis.com/token", url)
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        assertEquals("the-verifier", form["code_verifier"])
        assertEquals("http://127.0.0.1:9", form["redirect_uri"])
        assertEquals("test-client.apps.example", form["client_id"])
        assertEquals("not-a-secret", form["client_secret"])

        sent.clear()
        refreshTokens(microsoft, OAuthClient("ms-id"), OAuthTokens("a", "r1", t0), endpoint, t0)
        val (refreshUrl, refresh) = sent.single()
        assertEquals("https://login.microsoftonline.com/common/oauth2/v2.0/token", refreshUrl)
        assertEquals("refresh_token", refresh["grant_type"])
        assertEquals("r1", refresh["refresh_token"])
        // A public client sends no secret at all, rather than an empty one.
        assertFalse("client_secret" in refresh)
    }

    @Test
    fun `an http token endpoint is refused before anything is sent`() {
        assertFailsWith<OAuthFailure.Refused> { HttpTokenEndpoint.post("http://oauth2.googleapis.com/token", mapOf("a" to "b")) }
        assertFailsWith<OAuthFailure.Refused> { requireHttps("file:///etc/passwd") }
        assertEquals("oauth2.googleapis.com", requireHttps("https://oauth2.googleapis.com/token").host)
    }

    @Test
    fun `printing tokens never prints a token`() {
        val tokens = OAuthTokens("ya29.secret-access", "1//secret-refresh", t0, "someone@gmail.com")
        assertFalse("secret" in tokens.toString())
        assertFalse("secret" in client.toString())
    }

    @Test
    fun `tokens survive the credential store's one line`() {
        val tokens = OAuthTokens("a\"b", "r", t0, "someone@gmail.com")
        val json = tokens.toJson()
        assertFalse('\n' in json)
        assertEquals(tokens, OAuthTokens.fromJson(json))
        assertNull(OAuthTokens.fromJson("not json"))
        assertNull(OAuthTokens.fromJson("{}"))
        assertEquals(OAuthTokens("a", null, t0), OAuthTokens.fromJson(OAuthTokens("a", null, t0).toJson()))
    }

    // ---- refresh timing -----------------------------------------------------------------

    @Test
    fun `refresh is due five minutes before expiry`() {
        val tokens = OAuthTokens("a", "r", t0.plusSeconds(3600))
        assertFalse(needsRefresh(tokens, t0))
        assertFalse(needsRefresh(tokens, t0.plusSeconds(3600 - 301)))
        assertTrue(needsRefresh(tokens, t0.plusSeconds(3600 - 300)))
        assertTrue(needsRefresh(tokens, t0.plusSeconds(4000)))
        assertFalse(needsRefresh(tokens, t0.plusSeconds(3500), Duration.ofSeconds(60)))
    }

    private class Fake(var answer: () -> Pair<Int, String>) : TokenEndpoint {
        var calls = 0
        override fun post(url: String, form: Map<String, String>): Pair<Int, String> {
            calls++
            return answer()
        }
    }

    @Test
    fun `the keeper refreshes only when due, and keeps what comes back`() {
        val clock = HandClock(t0)
        val saved = mutableListOf<OAuthTokens>()
        val endpoint = Fake { 200 to """{"access_token":"second","refresh_token":"rotated","expires_in":3600}""" }
        val keeper = TokenKeeper(microsoft, client, OAuthTokens("first", "original", t0.plusSeconds(3600)), { saved += it }, clock, endpoint)

        assertEquals("first", keeper.accessToken())
        clock.now = t0.plusSeconds(3600 - 301)
        assertEquals("first", keeper.accessToken())
        assertEquals(0, endpoint.calls)

        clock.now = t0.plusSeconds(3600 - 299)
        assertEquals("second", keeper.accessToken())
        assertEquals(1, endpoint.calls)
        assertEquals("rotated", saved.single().refresh)
        assertEquals(clock.now.plusSeconds(3600), saved.single().expiresAt)

        // Fresh again, so no second call.
        assertEquals("second", keeper.accessToken())
        assertEquals(1, endpoint.calls)
    }

    @Test
    fun `invalid_grant during refresh asks for the browser once and stops asking the provider`() {
        val clock = HandClock(t0.plusSeconds(4000))
        val endpoint = Fake { 400 to """{"error":"invalid_grant"}""" }
        var lost = 0
        val keeper = TokenKeeper(google, client, OAuthTokens("a", "r", t0.plusSeconds(3600)), {}, clock, endpoint, onSignInLost = { lost++ })

        assertFailsWith<OAuthFailure.SignInAgain> { keeper.accessToken() }
        assertFailsWith<OAuthFailure.SignInAgain> { keeper.accessToken() }
        assertEquals(1, endpoint.calls)
        assertEquals(1, lost)
        assertTrue(keeper.signedOut)

        // Signing in again in the browser puts it back.
        keeper.replace(OAuthTokens("after", "r2", clock.now.plusSeconds(3600)))
        assertFalse(keeper.signedOut)
        assertEquals("after", keeper.accessToken())
    }

    @Test
    fun `a network failure during refresh is a network failure, not a sign-in problem`() {
        val clock = HandClock(t0.plusSeconds(3400))
        val endpoint = Fake { throw OAuthFailure.Network("Could not reach oauth2.googleapis.com. Either the address is wrong or it is not answering.") }
        var lost = 0
        val keeper = TokenKeeper(google, client, OAuthTokens("still-good", "r", t0.plusSeconds(3600)), {}, clock, endpoint, onSignInLost = { lost++ })

        // Inside the margin but not yet expired: the token in hand still works.
        assertEquals("still-good", keeper.accessToken())
        // Past expiry the failure reaches the caller as what it is.
        clock.now = t0.plusSeconds(3700)
        val failure = assertFailsWith<OAuthFailure.Network> { keeper.accessToken() }
        assertTrue("Could not reach" in failure.message.orEmpty())
        assertEquals(0, lost)
        assertFalse(keeper.signedOut)

        // And when the network is back, so is the account, with no browser.
        endpoint.answer = { 200 to """{"access_token":"back","expires_in":3600}""" }
        assertEquals("back", keeper.accessToken())
    }

    @Test
    fun `a sign-in with no refresh token asks for the browser when it runs out`() {
        val clock = HandClock(t0.plusSeconds(3600))
        val endpoint = Fake { 200 to "{}" }
        val keeper = TokenKeeper(google, client, OAuthTokens("a", null, t0.plusSeconds(3600)), {}, clock, endpoint)
        assertFailsWith<OAuthFailure.SignInAgain> { keeper.accessToken() }
        assertEquals(0, endpoint.calls)
    }

    // ---- what IMAP and SMTP are sent ------------------------------------------------------

    @Test
    fun `XOAUTH2 matches Google's documented example`() {
        assertEquals(
            "user=someuser@example.com\u0001auth=Bearer ya29.vF9dft4qmTc2Nvb3RlckBhdHRhdmlzdGEuY29tCg\u0001\u0001",
            xoauth2("someuser@example.com", "ya29.vF9dft4qmTc2Nvb3RlckBhdHRhdmlzdGEuY29tCg"),
        )
        assertEquals(
            "dXNlcj1zb21ldXNlckBleGFtcGxlLmNvbQFhdXRoPUJlYXJlciB5YTI5LnZGOWRmdDRxbVRjMk52YjNSbGNrQmhkSFJoZG1semRHRXVZMjl0Q2cBAQ==",
            xoauth2Base64("someuser@example.com", "ya29.vF9dft4qmTc2Nvb3RlckBhdHRhdmlzdGEuY29tCg"),
        )
    }

    @Test
    fun `OAUTHBEARER matches RFC 7628's example`() {
        assertEquals(
            "bixhPXVzZXJAZXhhbXBsZS5jb20sAWhvc3Q9c2VydmVyLmV4YW1wbGUuY29tAXBvcnQ9MTQzAWF1dGg9QmVhcmVyIHZGOWRmdDRxbVRjMk52YjNSbGNrQmhiSFJoZG1semRHRXVZMjl0Q2c9PQEB",
            oauthBearerBase64("user@example.com", "vF9dft4qmTc2Nvb3RlckBhbHRhdmlzdGEuY29tCg==", "server.example.com", 143),
        )
        assertEquals("n,a=user@example.com,\u0001auth=Bearer t\u0001\u0001", oauthBearer("user@example.com", "t"))
        // A comma or an equals sign in the name is escaped, since both are GS2 syntax.
        assertEquals("n,a=a=2Cb=3Dc,\u0001auth=Bearer t\u0001\u0001", oauthBearer("a,b=c", "t"))
    }

    private fun handler(user: String, token: String) = CallbackHandler { callbacks ->
        callbacks.forEach {
            when (it) {
                is NameCallback -> it.name = user
                is PasswordCallback -> it.password = token.toCharArray()
            }
        }
    }

    @Test
    fun `Java's SASL finds the OAUTHBEARER client once it is installed`() {
        OAuthBearerSasl.install()
        val sasl = Sasl.createSaslClient(arrayOf("OAUTHBEARER"), null, "imap", "imap.example.com", null, handler("me@example.com", "tok"))
        assertNotNull(sasl)
        assertEquals("OAUTHBEARER", sasl.mechanismName)
        assertTrue(sasl.hasInitialResponse())
        val first = String(sasl.evaluateChallenge(ByteArray(0)), Charsets.UTF_8)
        assertEquals("n,a=me@example.com,\u0001host=imap.example.com\u0001auth=Bearer tok\u0001\u0001", first)
        assertFalse(sasl.isComplete)
        // RFC 7628 3.2.3: a refused token comes back as a JSON challenge, answered with 0x01.
        val second = sasl.evaluateChallenge("""{"status":"invalid_token"}""".toByteArray())
        assertTrue(second.contentEquals(byteArrayOf(1)))
        assertTrue(sasl.isComplete)
    }

    @Test
    fun `IMAP and SMTP are told to use the token, XOAUTH2 first`() {
        val imap = oauthMailProperties("imap")
        assertEquals("true", imap.getProperty("mail.imap.sasl.enable"))
        assertEquals("XOAUTH2 OAUTHBEARER", imap.getProperty("mail.imap.sasl.mechanisms"))
        assertEquals("XOAUTH2", imap.getProperty("mail.imap.auth.mechanisms"))
        val smtp = oauthMailProperties("smtp")
        assertEquals("XOAUTH2 OAUTHBEARER", smtp.getProperty("mail.smtp.sasl.mechanisms"))
        assertEquals("XOAUTH2", smtp.getProperty("mail.smtp.auth.mechanisms"))
    }
}
