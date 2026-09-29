package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Signing in to Gmail and Microsoft through the browser, the parts with no window in them.
 *
 * The shape is the one RFC 8252 prescribes for a desktop application: the authorization
 * code flow, in the person's own browser, back to a listener on the loopback address, with
 * PKCE (RFC 7636) standing in for the client secret an installed application cannot keep.
 * Never an embedded web view. A login page drawn inside Rampart is a login page the person
 * cannot check the address of, and it is the exact thing phishing looks like, so neither
 * provider allows it and we would not want it if they did.
 *
 * What comes back is an access token that lasts about an hour and a refresh token that
 * lasts until the person revokes it. The access token is the IMAP and SMTP password, sent
 * through XOAUTH2 (see OAuthSasl.kt); the refresh token is how the next hour's access token
 * is got without the browser. Both live in the operating system's credential store and
 * never in a file.
 */

/** Refreshed this long before it runs out, so a command never goes out with a dying token. */
internal val REFRESH_MARGIN: Duration = Duration.ofMinutes(5)

/**
 * PKCE, RFC 7636.
 *
 * The verifier is made here, kept in memory, and sent only to the token endpoint. Its hash
 * goes in the browser URL. Anything that intercepts the redirect gets a code it cannot
 * exchange, because it never saw the verifier.
 */
internal object Pkce {
    /** RFC 7636 section 4.1: the unreserved characters, and only those. */
    const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /**
     * 64 characters of 66 possibilities, about 386 bits, where the RFC asks for at least 256.
     * The length is a parameter only so a test can check both ends of the allowed range.
     */
    fun verifier(random: SecureRandom = SecureRandom(), length: Int = 64): String {
        require(length in 43..128) { "A PKCE verifier is 43 to 128 characters." }
        return buildString(length) { repeat(length) { append(UNRESERVED[random.nextInt(UNRESERVED.length)]) } }
    }

    /** S256, never plain: plain puts the verifier itself in the browser's history. */
    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

/** The `state` value, which ties the redirect that comes back to the request that went out. */
internal fun newState(random: SecureRandom = SecureRandom()): String =
    base64Url(ByteArray(32).also { random.nextBytes(it) })

private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/**
 * A query string or form body, encoded the one way both providers accept.
 *
 * URLEncoder is form encoding, which writes a space as `+`. That is correct in a form body
 * and usually tolerated in a query, but a scope list is spaces and `%20` is correct in both,
 * so it is written that way everywhere.
 */
internal fun formEncode(values: Map<String, String>): String =
    values.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

/** The address the browser is sent to. Nothing in it is secret: the verifier stays here. */
internal fun authorizationUrl(
    provider: OAuthProvider,
    client: OAuthClient,
    redirectUri: String,
    challenge: String,
    state: String,
    loginHint: String?,
): String {
    val params = linkedMapOf(
        "response_type" to "code",
        "client_id" to client.clientId,
        "redirect_uri" to redirectUri,
        "scope" to provider.scopes.joinToString(" "),
        "state" to state,
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
    )
    // Fills the address in on the provider's page, so the person is not asked for it twice.
    loginHint?.trim()?.takeIf { '@' in it }?.let { params["login_hint"] = it }
    params.putAll(provider.extraParams)
    return provider.authorizeUrl(client.tenant) + "?" + formEncode(params)
}

/**
 * What a sign-in leaves behind. Printing one never prints a token.
 *
 * [email] is the address the provider says was signed in, read from the ID token, which is
 * how Rampart notices when somebody picked a different account in the browser than the one
 * they typed.
 */
internal data class OAuthTokens(
    val access: String,
    val refresh: String?,
    val expiresAt: Instant,
    val email: String? = null,
) {
    override fun toString() = "OAuthTokens(expiresAt=$expiresAt, refresh=${if (refresh.isNullOrBlank()) "none" else "kept"})"

    /** For the credential store, as one line so it survives secret-tool's newline handling. */
    fun toJson(): String = buildJsonObject {
        put("access", access)
        refresh?.let { put("refresh", it) }
        put("expiresAt", expiresAt.epochSecond)
        email?.let { put("email", it) }
    }.toString()

    companion object {
        fun fromJson(text: String): OAuthTokens? = runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            OAuthTokens(
                access = o.str("access") ?: return null,
                refresh = o.str("refresh"),
                expiresAt = Instant.ofEpochSecond((o["expiresAt"] as? JsonPrimitive)?.longOrNull ?: 0L),
                email = o.str("email"),
            )
        }.getOrNull()
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.ifBlank { null }

/**
 * Why a sign-in or a refresh did not work, sorted by what the person can do about it.
 *
 * The sorting is the point. A refresh token that was revoked needs the browser again; a
 * laptop that lost its wifi needs nothing but time; a build whose client ID was withdrawn
 * needs a new build. Showing all three as "connection failed" sends people to the wrong fix,
 * and in the first case it sends them there forever.
 */
internal sealed class OAuthFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The provider no longer honours this sign-in. Only the browser can fix it. */
    class SignInAgain(message: String) : OAuthFailure(message)

    /** Nothing is wrong with the sign-in; the provider could not be reached or had a moment. */
    class Network(message: String, cause: Throwable? = null) : OAuthFailure(message, cause)

    /** The provider said no, for a reason signing in again would not change. */
    class Refused(message: String) : OAuthFailure(message)

    class TimedOut(message: String) : OAuthFailure(message)

    class Cancelled : OAuthFailure("The sign-in was cancelled.")
}

/**
 * An error from a token endpoint, as the failure it is.
 *
 * `invalid_grant` is the one that matters: a refresh token revoked, expired, or issued
 * before a password change. Microsoft also answers `interaction_required` and its relatives
 * when a policy now wants something only the browser can give, such as a second factor.
 */
internal fun tokenFailure(provider: OAuthProvider, status: Int, error: String?, description: String?): OAuthFailure {
    val code = error?.trim()?.lowercase().orEmpty()
    val said = description?.let(::plainProviderText)?.takeIf { it.isNotBlank() }
    return when {
        code in setOf("invalid_grant", "interaction_required", "consent_required", "login_required") ->
            OAuthFailure.SignInAgain("${provider.name} no longer accepts this sign-in, so it has to be done again in the browser.")
        code in setOf("invalid_client", "unauthorized_client") ->
            OAuthFailure.Refused(
                "${provider.name} did not accept the client ID this copy of Rampart signs in with." +
                    (said?.let { " $it" } ?: ""),
            )
        code in setOf("temporarily_unavailable", "server_error") || status >= 500 ->
            OAuthFailure.Network("${provider.name}'s sign-in service is having trouble right now. Try again in a moment.")
        else -> OAuthFailure.Refused(
            "${provider.name} refused the sign-in" + (said?.let { ": $it" } ?: if (code.isNotBlank()) " ($code)." else "."),
        )
    }
}

/**
 * A description a provider wrote, made safe to show as one line of text.
 *
 * It is the provider's text and it is drawn as text, never as markup, but a line of
 * Microsoft's carries a trace ID, a correlation ID and a timestamp after the useful part,
 * so it is cut at the first line break and at a length somebody would read.
 */
internal fun plainProviderText(text: String): String {
    val first = text.lineSequence().firstOrNull().orEmpty().filter { it >= ' ' }.trim()
    return if (first.length > 200) first.take(200).trimEnd() + "." else first
}

/**
 * A token endpoint's answer, as tokens or as the failure it describes.
 *
 * [previous] is the sign-in being refreshed. A provider may hand back a new refresh token
 * on every refresh, and Microsoft does: the new one is kept whenever it is there, because
 * the old one may already be dead. When none comes back the old one is still the one that
 * works, and throwing it away would sign the account out an hour later for no reason.
 */
internal fun parseTokenResponse(
    provider: OAuthProvider,
    status: Int,
    body: String,
    previous: OAuthTokens?,
    now: Instant,
): OAuthTokens {
    val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    val error = json?.str("error")
    if (status !in 200..299 || error != null) {
        throw tokenFailure(provider, status, error, json?.str("error_description"))
    }
    if (json == null) throw OAuthFailure.Refused("${provider.name} answered the sign-in with something that was not a token.")
    val access = json.str("access_token")
        ?: throw OAuthFailure.Refused("${provider.name} answered the sign-in without an access token.")
    val type = json.str("token_type")
    if (type != null && !type.equals("Bearer", ignoreCase = true)) {
        throw OAuthFailure.Refused("${provider.name} issued a $type token, and mail servers only take Bearer tokens.")
    }
    // A number, or a number in a string, which some servers send. Absent, the hour both
    // providers actually issue is assumed, which errs towards refreshing early.
    val lifetime = (json["expires_in"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
        ?.takeIf { it > 0 } ?: 3600L
    val email = json.str("id_token")?.let(::emailFromIdToken) ?: previous?.email
    return OAuthTokens(
        access = access,
        refresh = json.str("refresh_token") ?: previous?.refresh,
        expiresAt = now.plusSeconds(lifetime),
        email = email,
    )
}

/**
 * The signed-in address, from the ID token that came back with the access token.
 *
 * The signature is not checked, and does not need to be for this use. The token came
 * straight from the provider's token endpoint over TLS, which OpenID Connect section 3.1.3.7
 * accepts in place of a signature check, and all it is used for is choosing which address
 * to hand IMAP: a wrong one gets refused by the provider's own mail server.
 */
internal fun emailFromIdToken(idToken: String): String? = runCatching {
    val payload = idToken.split('.').getOrNull(1) ?: return null
    val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)).jsonObject
    // Microsoft leaves `email` out for many work accounts and always sends the sign-in name,
    // which for a mailbox is its address.
    (claims.str("email") ?: claims.str("preferred_username"))?.takeIf { '@' in it }?.trim()
}.getOrNull()

/** True once the access token is inside [margin] of running out, or past it. */
internal fun needsRefresh(tokens: OAuthTokens, now: Instant, margin: Duration = REFRESH_MARGIN): Boolean =
    !now.isBefore(tokens.expiresAt.minus(margin))

/**
 * One POST to a token endpoint. An interface so the tests can answer without a network.
 *
 * Returns the status and body whatever the status: a 400 with `invalid_grant` in it is an
 * answer, and the caller is the one that knows what it means. Only a request that never got
 * an answer throws, and it throws [OAuthFailure.Network].
 */
internal fun interface TokenEndpoint {
    fun post(url: String, form: Map<String, String>): Pair<Int, String>
}

internal object HttpTokenEndpoint : TokenEndpoint {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            // Never followed. A token endpoint that redirects is not one to send a refresh
            // token on to.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    override fun post(url: String, form: Map<String, String>): Pair<Int, String> {
        val uri = requireHttps(url)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() to response.body()
        } catch (e: IOException) {
            throw OAuthFailure.Network(plainNetworkError(e, uri.host ?: url), e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OAuthFailure.Cancelled()
        }
    }
}

/**
 * The endpoint as a URI, or a refusal when it is not https.
 *
 * Both presets are https and nothing lets a person change them, so this is the check that
 * keeps it that way if a later change does: a code, a verifier or a refresh token sent in
 * the clear is a mailbox handed to whoever is on the same network.
 */
internal fun requireHttps(url: String): URI {
    val uri = runCatching { URI(url) }.getOrNull()
    if (uri == null || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
        throw OAuthFailure.Refused("Rampart only signs in over https, and $url is not an https address.")
    }
    return uri
}

private fun withSecret(client: OAuthClient, form: Map<String, String>): Map<String, String> =
    if (client.clientSecret.isBlank()) form else form + ("client_secret" to client.clientSecret)

/** The code from the redirect, traded for tokens. The verifier proves it is the same app asking. */
internal fun exchangeCode(
    provider: OAuthProvider,
    client: OAuthClient,
    code: String,
    verifier: String,
    redirectUri: String,
    endpoint: TokenEndpoint = HttpTokenEndpoint,
    now: Instant = Instant.now(),
): OAuthTokens {
    val form = withSecret(
        client,
        linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to client.clientId,
            "code_verifier" to verifier,
        ),
    )
    val (status, body) = endpoint.post(provider.tokenUrl(client.tenant), form)
    return parseTokenResponse(provider, status, body, null, now)
}

/** The next hour's access token, from the refresh token, with no browser. */
internal fun refreshTokens(
    provider: OAuthProvider,
    client: OAuthClient,
    current: OAuthTokens,
    endpoint: TokenEndpoint = HttpTokenEndpoint,
    now: Instant = Instant.now(),
): OAuthTokens {
    val refresh = current.refresh?.takeIf { it.isNotBlank() }
        ?: throw OAuthFailure.SignInAgain("${provider.name} did not leave a way to stay signed in, so it has to be done again in the browser.")
    val form = withSecret(
        client,
        linkedMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to client.clientId,
        ),
    )
    val (status, body) = endpoint.post(provider.tokenUrl(client.tenant), form)
    return parseTokenResponse(provider, status, body, current, now)
}

/**
 * One account's tokens, handed out fresh every time one is asked for.
 *
 * Everything that talks to the mail server asks this for the password rather than holding
 * one, which is what lets a connection opened at nine still be signing in correctly at
 * noon. It refreshes when the token is inside [margin] of running out, saves whatever came
 * back, and sorts a failure into one of two answers:
 *
 * - **The sign-in is gone** ([OAuthFailure.SignInAgain]). Remembered, reported once through
 *   [onSignInLost], and every later ask fails the same way without going back to the
 *   provider, because asking again with a revoked token only gets the same answer and, if it
 *   is done in a loop, gets the client throttled.
 * - **Anything else** ([OAuthFailure.Network] and the rest). The token in hand is handed
 *   back while it still has time on it, since it still works; only once it has actually
 *   run out does the failure reach the caller, and then as what it is.
 *
 * Synchronised, because the reading connection, the push connection and a send can all ask
 * at once, and two refreshes racing would each spend a refresh token that Microsoft rotates.
 */
internal class TokenKeeper(
    val provider: OAuthProvider,
    private var client: OAuthClient,
    initial: OAuthTokens,
    private val save: (OAuthTokens) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
    private val endpoint: TokenEndpoint = HttpTokenEndpoint,
    private val margin: Duration = REFRESH_MARGIN,
    private val onSignInLost: (OAuthFailure.SignInAgain) -> Unit = {},
) {
    private var tokens: OAuthTokens = initial
    private var lost: OAuthFailure.SignInAgain? = null

    @Synchronized
    fun accessToken(): String {
        lost?.let { throw it }
        val now = clock.instant()
        if (!needsRefresh(tokens, now, margin)) return tokens.access
        return try {
            val fresh = refreshTokens(provider, client, tokens, endpoint, now)
            tokens = fresh
            runCatching { save(fresh) }
            fresh.access
        } catch (e: OAuthFailure.SignInAgain) {
            lost = e
            runCatching { onSignInLost(e) }
            throw e
        } catch (e: OAuthFailure) {
            if (now.isBefore(tokens.expiresAt)) tokens.access else throw e
        }
    }

    /** True once the provider has refused this sign-in and only the browser can fix it. */
    @get:Synchronized
    val signedOut: Boolean get() = lost != null

    /** After the browser flow has run again: the account carries on with the new sign-in. */
    @Synchronized
    fun replace(fresh: OAuthTokens, newClient: OAuthClient = client) {
        tokens = fresh
        client = newClient
        lost = null
        runCatching { save(fresh) }
    }
}

/**
 * The browser half, from opening the listener to holding tokens.
 *
 * Built first and awaited second, so the window can show the address it sent the browser
 * to, and offer it to copy, while this waits. [close] ends the wait from any thread, which
 * is what Cancel does.
 */
internal class BrowserSignIn(
    private val provider: OAuthProvider,
    private val client: OAuthClient,
    email: String,
    private val endpoint: TokenEndpoint = HttpTokenEndpoint,
    private val clock: Clock = Clock.systemUTC(),
    private val timeout: Duration = Duration.ofMinutes(5),
    random: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private val redirect = LoopbackRedirect.open()
    private val verifier = Pkce.verifier(random)
    private val state = newState(random)
    val redirectUri: String = redirect.redirectUri(provider.redirectHost)
    val url: String = authorizationUrl(provider, client, redirectUri, Pkce.challenge(verifier), state, email)

    /** Blocks until the browser comes back, the time runs out, or [close] is called. */
    fun await(): OAuthTokens {
        try {
            val code = redirect.await(state, provider.name, timeout)
            return exchangeCode(provider, client, code, verifier, redirectUri, endpoint, clock.instant())
        } finally {
            close()
        }
    }

    override fun close() = redirect.close()
}
