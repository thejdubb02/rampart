package org.rampart

import java.security.Provider
import java.security.Security
import java.util.Base64
import java.util.Properties
import javax.security.auth.callback.Callback
import javax.security.auth.callback.CallbackHandler
import javax.security.auth.callback.NameCallback
import javax.security.auth.callback.PasswordCallback
import javax.security.sasl.SaslClient
import javax.security.sasl.SaslClientFactory

/**
 * Handing IMAP and SMTP an access token instead of a password.
 *
 * **XOAUTH2 is Angus Mail's, not ours.** It is what both Gmail and Microsoft document, and
 * Angus has carried it since JavaMail 1.5.5, so the SASL exchange for it is the library's
 * and the token simply goes where the password would. OAUTHBEARER (RFC 7628) is the
 * standard that replaced it and Angus has no client for it, so this file adds one, as a
 * SASL mechanism registered with Java's security providers, which is the extension point
 * Angus itself uses for XOAUTH2.
 *
 * XOAUTH2 is listed first on purpose. Both providers offer it, so the library's tested code
 * is what signs in to Gmail and Outlook, and ours only runs against a server that offers
 * OAUTHBEARER and not XOAUTH2. Angus drops every listed mechanism the server did not
 * advertise before choosing, so the order is a preference and never a failure.
 */
internal fun oauthMailProperties(protocol: String): Properties {
    OAuthBearerSasl.install()
    return Properties().apply {
        put("mail.$protocol.sasl.enable", "true")
        put("mail.$protocol.sasl.mechanisms", "XOAUTH2 OAUTHBEARER")
        // The non-SASL path Angus falls back to when SASL cannot start, pinned to XOAUTH2 so
        // it never offers a token through AUTH=PLAIN. On IMAP a server that offers neither
        // OAuth mechanism still gets a plain LOGIN as Angus's last resort, over the same TLS
        // connection and to the provider's own server, where it fails as a refused sign-in.
        put("mail.$protocol.auth.mechanisms", "XOAUTH2")
    }
}

/** The XOAUTH2 initial response, before base64. Google's and Microsoft's documented format. */
internal fun xoauth2(user: String, token: String): String = "user=$user\u0001auth=Bearer $token\u0001\u0001"

internal fun xoauth2Base64(user: String, token: String): String =
    Base64.getEncoder().encodeToString(xoauth2(user, token).toByteArray(Charsets.UTF_8))

/**
 * The OAUTHBEARER initial response of RFC 7628 section 3.1, before base64.
 *
 * A GS2 header naming the user, then key-value pairs separated by 0x01, ending in two. The
 * host and port are optional in the RFC and are sent when known, since some servers check
 * them against where they think they are.
 */
internal fun oauthBearer(user: String, token: String, host: String? = null, port: Int? = null): String = buildString {
    append("n,a=").append(saslName(user)).append(",\u0001")
    if (!host.isNullOrBlank()) append("host=").append(host).append('\u0001')
    if (port != null && port > 0) append("port=").append(port).append('\u0001')
    append("auth=Bearer ").append(token).append("\u0001\u0001")
}

internal fun oauthBearerBase64(user: String, token: String, host: String? = null, port: Int? = null): String =
    Base64.getEncoder().encodeToString(oauthBearer(user, token, host, port).toByteArray(Charsets.UTF_8))

/** RFC 5801's escaping for a name in a GS2 header, where `,` and `=` are syntax. */
private fun saslName(user: String): String = user.replace("=", "=3D").replace(",", "=2C")

/**
 * The OAUTHBEARER client. Public with a no-argument constructor, because Java's SASL looks
 * the factory up by class name through the provider registered below.
 */
class OAuthBearerSaslFactory : SaslClientFactory {
    override fun createSaslClient(
        mechanisms: Array<out String>,
        authorizationId: String?,
        protocol: String?,
        serverName: String?,
        props: Map<String, *>?,
        cbh: CallbackHandler?,
    ): SaslClient? =
        if (mechanisms.any { it == OAuthBearerSasl.MECHANISM } && cbh != null) OAuthBearerSaslClient(serverName, cbh) else null

    override fun getMechanismNames(props: Map<String, *>?): Array<String> = arrayOf(OAuthBearerSasl.MECHANISM)
}

internal object OAuthBearerSasl {
    const val MECHANISM = "OAUTHBEARER"
    private const val PROVIDER = "Rampart-OAuthBearer"

    private class Registration : Provider(PROVIDER, "1.0", "OAUTHBEARER SASL mechanism (RFC 7628)") {
        init {
            put("SaslClientFactory.$MECHANISM", OAuthBearerSaslFactory::class.java.name)
        }
    }

    /** Once per process, and quietly nothing where a security manager forbids it. */
    fun install() {
        runCatching {
            synchronized(this) {
                if (Security.getProvider(PROVIDER) == null) Security.addProvider(Registration())
            }
        }
    }
}

/**
 * One OAUTHBEARER exchange, shaped like Angus's own XOAUTH2 client.
 *
 * The name and the token come through the callback handler Angus supplies, which answers
 * with the user and the password it was given, and the password here is the access token.
 *
 * On a refused token RFC 7628 has the server send a JSON error as a challenge, and the
 * client must answer with a single 0x01 before the server will send its final NO. That is
 * the second step below; the exchange is complete after it either way.
 */
internal class OAuthBearerSaslClient(
    private val host: String?,
    private val callbacks: CallbackHandler,
) : SaslClient {
    private var sent = false
    private var complete = false

    override fun getMechanismName() = OAuthBearerSasl.MECHANISM

    override fun hasInitialResponse() = true

    override fun evaluateChallenge(challenge: ByteArray?): ByteArray {
        if (complete) return ByteArray(0)
        if (sent) {
            complete = true
            return byteArrayOf(1)
        }
        val name = NameCallback("User name:")
        val password = PasswordCallback("OAuth token:", false)
        callbacks.handle(arrayOf<Callback>(name, password))
        val token = String(password.password ?: CharArray(0))
        password.clearPassword()
        sent = true
        return oauthBearer(name.name.orEmpty(), token, host).toByteArray(Charsets.UTF_8)
    }

    override fun isComplete() = complete

    override fun unwrap(incoming: ByteArray?, offset: Int, len: Int): ByteArray =
        throw IllegalStateException("OAUTHBEARER has no security layer.")

    override fun wrap(outgoing: ByteArray?, offset: Int, len: Int): ByteArray =
        throw IllegalStateException("OAUTHBEARER has no security layer.")

    override fun getNegotiatedProperty(propName: String?): Any? = null

    override fun dispose() = Unit
}
