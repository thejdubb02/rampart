package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.Hashtable
import javax.naming.directory.InitialDirContext
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The two mail providers that no longer take a password, and everything Rampart has to know
 * about each of them to sign in through the browser instead.
 *
 * Gmail and Microsoft 365 both turned off plain passwords for IMAP and SMTP, so for most of
 * the people who might install Rampart these two are the difference between a mail client
 * and a window that says the password was wrong. Everything else still signs in the old way,
 * which is every self-hosted mailbox and most other providers.
 *
 * The endpoints and hosts are fixed here rather than read from the config file on purpose.
 * The file carries client IDs, which a person can legitimately swap for their own; an
 * endpoint that can be pointed somewhere else is a place to send somebody's refresh token.
 */
internal data class OAuthProvider(
    /** Written into accounts.json, so it never changes once shipped. */
    val id: String,
    /** The company, as in "Google asks for your password in your browser". */
    val name: String,
    /** The product people know, as in "Gmail sign-in is not configured in this build". */
    val product: String,
    /** With `{tenant}` where Microsoft puts its tenant; Google has none. */
    val authorizeUrl: String,
    val tokenUrl: String,
    val scopes: List<String>,
    /** Parameters this provider needs on the authorization request beyond the standard ones. */
    val extraParams: Map<String, String>,
    /**
     * The host the redirect URI names. The listener always binds 127.0.0.1; see
     * docs/connecting.md for why Microsoft is still sent `localhost`.
     */
    val redirectHost: String,
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    /** Address domains that are certainly this provider, with no lookup needed. */
    val domains: Set<String>,
    /** Suffixes of MX hosts that mean a custom domain's mail is hosted here. */
    val mxSuffixes: List<String>,
) {
    fun authorizeUrl(tenant: String) = authorizeUrl.replace("{tenant}", tenant)
    fun tokenUrl(tenant: String) = tokenUrl.replace("{tenant}", tenant)

    /**
     * Where mail is sent from, for this address.
     *
     * Microsoft documents a different submission host for its consumer domains than for
     * Microsoft 365. Both are believed to accept either kind of account, but using the one
     * each is documented against costs nothing and removes a thing to wonder about.
     */
    fun sendHostFor(email: String): String {
        val domain = oauthDomainOf(email)
        return if (this == OAuthProviders.MICROSOFT && domain in OAuthProviders.MICROSOFT.domains) {
            "smtp-mail.outlook.com"
        } else {
            smtpHost
        }
    }
}

internal object OAuthProviders {
    val GOOGLE = OAuthProvider(
        id = "google",
        name = "Google",
        product = "Gmail",
        authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenUrl = "https://oauth2.googleapis.com/token",
        // The one scope Google offers for IMAP and SMTP. It is full access to the mailbox,
        // which is what an IMAP client is; there is no narrower scope that IMAP accepts.
        // openid and email tell us which account was actually picked in the browser.
        scopes = listOf("https://mail.google.com/", "openid", "email"),
        // offline is what makes Google issue a refresh token at all, and consent makes it
        // issue one again on a second sign-in, which it otherwise quietly does not.
        extraParams = mapOf("access_type" to "offline", "prompt" to "consent"),
        redirectHost = "127.0.0.1",
        imapHost = "imap.gmail.com",
        imapPort = 993,
        smtpHost = "smtp.gmail.com",
        smtpPort = 465,
        domains = setOf("gmail.com", "googlemail.com"),
        mxSuffixes = listOf(".google.com", ".googlemail.com"),
    )

    val MICROSOFT = OAuthProvider(
        id = "microsoft",
        name = "Microsoft",
        product = "Outlook and Microsoft 365",
        authorizeUrl = "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize",
        tokenUrl = "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token",
        // offline_access is the only way Microsoft hands out a refresh token, and without
        // one the account would need the browser again every hour.
        scopes = listOf(
            "https://outlook.office.com/IMAP.AccessAsUser.All",
            "https://outlook.office.com/SMTP.Send",
            "offline_access",
            "openid",
            "email",
        ),
        // Offered the account chooser rather than whatever account the browser last used,
        // because the person typed an address and may well have two Microsoft accounts.
        extraParams = mapOf("prompt" to "select_account"),
        redirectHost = "localhost",
        imapHost = "outlook.office365.com",
        imapPort = 993,
        smtpHost = "smtp.office365.com",
        smtpPort = 587,
        domains = setOf("outlook.com", "hotmail.com", "live.com", "msn.com"),
        mxSuffixes = listOf(".mail.protection.outlook.com", ".olc.protection.outlook.com"),
    )

    val all = listOf(GOOGLE, MICROSOFT)

    fun byId(id: String): OAuthProvider? = all.firstOrNull { it.id == id.trim().lowercase() }
}

internal fun oauthDomainOf(email: String): String = email.substringAfterLast('@', "").trim().lowercase().trim('.')

/** The provider an address certainly belongs to, from its domain alone, with no lookup. */
internal fun providerForDomain(email: String): OAuthProvider? {
    val domain = oauthDomainOf(email)
    if (domain.isBlank()) return null
    return OAuthProviders.all.firstOrNull { domain in it.domains }
}

/**
 * The provider a custom domain's mail is hosted at, from where its MX records point.
 *
 * A Google Workspace or Microsoft 365 domain has an address nobody could recognise, and its
 * owner is exactly the person who can no longer use a password. Its MX records are the one
 * public thing that says who runs its mail, and they are read the same way Discover reads
 * SRV records: a guess that is then offered, never a decision taken for the person.
 */
internal fun providerForMx(hosts: List<String>): OAuthProvider? {
    val names = hosts.map { it.trim().trimEnd('.').lowercase() }.filter { it.isNotBlank() }
    return OAuthProviders.all.firstOrNull { provider ->
        names.any { host -> provider.mxSuffixes.any { suffix -> host.endsWith(suffix) || host == suffix.trimStart('.') } }
    }
}

/** Both of the above, the certain one first. [mx] is the DNS side, separated for tests. */
internal fun detectProvider(email: String, mx: (String) -> List<String> = ::mxHosts): OAuthProvider? {
    providerForDomain(email)?.let { return it }
    val domain = oauthDomainOf(email)
    if (domain.isBlank() || '.' !in domain) return null
    return providerForMx(mx(domain))
}

/**
 * The MX hosts for a domain, best first, or nothing.
 *
 * Never throws, for the same reason [srv] does not: no records, no resolver and no network
 * all mean the same thing here, which is that there is nothing to suggest.
 */
internal fun mxHosts(domain: String): List<String> = runCatching {
    val environment = Hashtable<String, String>().apply {
        put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory")
        put("com.sun.jndi.dns.timeout.initial", "1500")
        put("com.sun.jndi.dns.timeout.retries", "1")
    }
    val attributes = InitialDirContext(environment).getAttributes(domain, arrayOf("MX"))
    val records = attributes.get("MX")?.all ?: return emptyList()
    buildList {
        while (records.hasMore()) parseMx(records.next().toString())?.let { add(it) }
    }.sortedBy { it.first }.map { it.second }
}.getOrDefault(emptyList())

/** One MX record as the resolver prints it: preference, then host. */
internal fun parseMx(record: String): Pair<Int, String>? {
    val parts = record.trim().split(Regex("\\s+"))
    if (parts.size < 2) return null
    val host = parts[1].trim().trimEnd('.')
    if (host.isBlank()) return null
    return (parts[0].toIntOrNull() ?: 0) to host
}

/**
 * The client ID this build signs in to one provider with.
 *
 * **None of this is secret, and it is not a mistake that it ships in the build.** A desktop
 * application cannot keep a secret from the person running it, which is why OAuth for
 * installed apps (RFC 8252) uses PKCE instead of a client secret. Google still issues a
 * "client secret" to Desktop clients and still asks for it at the token endpoint, and says
 * in its own documentation that for installed apps it is not treated as confidential. It
 * lives in the same file for the same reason. docs/connecting.md says this too, so nobody
 * "fixes" it by moving it somewhere that only looks safer.
 */
internal data class OAuthClient(
    val clientId: String,
    val clientSecret: String = "",
    /** Microsoft only: "common" for any account, "consumers" or "organizations" to narrow it. */
    val tenant: String = "common",
    /**
     * The host the redirect URI names, when an application was registered with the other
     * one. Only "127.0.0.1" or "localhost"; blank means the provider's usual. The listener
     * binds 127.0.0.1 either way.
     */
    val redirectHost: String = "",
) {
    /** A blank ID, or the placeholder a template might carry, is a build with no ID in it. */
    val configured: Boolean
        get() = clientId.isNotBlank() && !clientId.contains("REPLACE", ignoreCase = true) &&
            !clientId.contains("your-client-id", ignoreCase = true)

    override fun toString() = "OAuthClient(clientId=$clientId, tenant=$tenant)"
}

/** One sentence for a provider this build has no client ID for. */
internal fun notConfiguredSentence(provider: OAuthProvider): String =
    "${provider.product} sign-in is not configured in this build."

/**
 * The client IDs, from the file shipped in the build with anything the person set on top.
 *
 * The override lives beside accounts.json and holds no secret either, only IDs somebody
 * registered for themselves. Each field overrides on its own, so an override that only
 * sets a Microsoft tenant keeps the shipped Microsoft ID.
 */
internal object OAuthClients {
    const val FILE = "oauth-clients.json"

    fun overrideFile(): Path = Accounts.file().resolveSibling(FILE)

    fun shipped(): String = runCatching {
        OAuthClients::class.java.getResourceAsStream("/$FILE")?.use { String(it.readBytes(), Charsets.UTF_8) }
    }.getOrNull().orEmpty()

    fun override(): String = runCatching {
        val file = overrideFile()
        if (file.exists()) file.readText() else ""
    }.getOrDefault("")

    fun current(provider: OAuthProvider): OAuthClient = clientFrom(provider, shipped(), override())

    /** The override for one provider, rewritten. Blank fields fall back to the build's. */
    fun saveOverride(provider: OAuthProvider, client: OAuthClient, path: Path = overrideFile()) {
        val existing = runCatching {
            if (path.exists()) Json.parseToJsonElement(path.readText()).jsonObject else JsonObject(emptyMap())
        }.getOrDefault(JsonObject(emptyMap()))
        val entry = buildJsonObject {
            put("clientId", client.clientId.trim())
            if (client.clientSecret.isNotBlank()) put("clientSecret", client.clientSecret.trim())
            if (provider == OAuthProviders.MICROSOFT) put("tenant", client.tenant.trim().ifBlank { "common" })
            if (client.redirectHost.isNotBlank()) put("redirectHost", client.redirectHost)
        }
        val document = JsonObject(existing + (provider.id to entry))
        path.parent?.createDirectories()
        path.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), document))
    }
}

/** Separated from the file reads so the override rules can be tested on strings. */
internal fun clientFrom(provider: OAuthProvider, shipped: String, override: String): OAuthClient {
    fun entry(text: String): JsonObject? = runCatching {
        Json.parseToJsonElement(text).jsonObject[provider.id]?.jsonObject
    }.getOrNull()

    fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }

    val base = entry(shipped)
    val mine = entry(override)
    // An override with its own ID brings its own secret, or none: pairing somebody's own
    // Google ID with the build's secret would only ever fail, and more confusingly.
    val ownId = mine?.text("clientId")
    return OAuthClient(
        clientId = ownId ?: base?.text("clientId").orEmpty(),
        clientSecret = if (ownId != null) mine.text("clientSecret").orEmpty() else base?.text("clientSecret").orEmpty(),
        // The tenant is the one piece of the file that lands in a URL, so it is held to the
        // shape a tenant name or ID actually has and cannot add a path or a host.
        tenant = (mine?.text("tenant") ?: base?.text("tenant"))
            ?.takeIf { it.matches(Regex("[A-Za-z0-9.-]{1,64}")) } ?: "common",
        // Only the two loopback names. Anything else would send the code somewhere that is
        // not this machine.
        redirectHost = (mine?.text("redirectHost") ?: base?.text("redirectHost"))
            ?.takeIf { it == "127.0.0.1" || it == "localhost" }.orEmpty(),
    )
}
