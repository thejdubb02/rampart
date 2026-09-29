package org.rampart

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.bouncycastle.openpgp.PGPPublicKeyRing
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/*
 * Finding somebody's OpenPGP key.
 *
 * Four places, in the order they are asked, and the first two never leave the computer:
 *
 * 1. **Keys already here**: made, imported, or kept from an earlier lookup.
 * 2. **The address book**: a contact card can carry a key (JSContact `cryptoKeys`, which is
 *    what a vCard KEY becomes over JMAP). Only a key inside the card is used. A card that
 *    points at a URL is left alone, because following it is a request nobody asked for.
 * 3. **Web Key Directory** (draft-koch-openpgp-webkey-service): the recipient's own domain
 *    publishes the key at a well-known https address. The advanced method first, the direct
 *    method only when the advanced host does not exist, as the draft says.
 * 4. **keys.openpgp.org**, through its VKS API by address. That server only hands out an
 *    address it has confirmed by mail, which is what makes a lookup by address worth doing.
 *
 * **3 and 4 are network requests that tell a third party who you are writing to.** So they
 * happen only when somebody is writing to that recipient with encryption switched on, or
 * presses Look up, never in the background, and the composer says so while it happens. Both
 * are https only: a key fetched over plain http is a key anybody on the path could have
 * swapped, which is worse than no key.
 */

/** Where a key came from, which the key list shows and which decides how much it is trusted. */
internal enum class KeySource(val label: String, val trustedByDefault: Boolean) {
    MINE("Made here", true),
    IMPORTED("Imported", true),
    CONTACT("From your address book", true),
    WKD("From the recipient's own domain (Web Key Directory)", true),
    VKS("From keys.openpgp.org", true),
    ATTACHED("Attached to a message", false),
}

internal object KeyLookup {
    /** The z-base-32 alphabet (Zooko), which the Web Key Directory uses for its file names. */
    private const val ZBASE32 = "ybndrfg8ejkmcpqxot1uwisza345h769"

    /** The most a key server's answer may be before it is refused. A real key is a few kilobytes. */
    private const val MOST_BYTES = 512 * 1024

    fun zbase32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ZBASE32[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ZBASE32[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    /** The WKD hash of an address's local part: SHA-1 of the lower-cased local part, z-base-32. */
    fun wkdHash(localPart: String): String =
        zbase32(MessageDigest.getInstance("SHA-1").digest(localPart.lowercase().toByteArray(StandardCharsets.UTF_8)))

    /** The two WKD addresses for [email], advanced first, or null when it is not an address. */
    fun wkdUrls(email: String): Pair<String, String>? {
        val at = email.trim().lastIndexOf('@')
        if (at <= 0 || at == email.trim().length - 1) return null
        val local = email.trim().substring(0, at)
        val domain = email.trim().substring(at + 1).lowercase()
        if (!Regex("^[a-z0-9.-]+$").matches(domain)) return null
        val hash = wkdHash(local)
        val l = URLEncoder.encode(local, StandardCharsets.UTF_8).replace("+", "%20")
        return "https://openpgpkey.$domain/.well-known/openpgpkey/$domain/hu/$hash?l=$l" to
            "https://$domain/.well-known/openpgpkey/hu/$hash?l=$l"
    }

    /** The keys.openpgp.org address for a lookup by email. */
    fun vksUrl(email: String): String =
        "https://keys.openpgp.org/vks/v1/by-email/" + URLEncoder.encode(email.trim(), StandardCharsets.UTF_8).replace("+", "%20")

    /**
     * Only the rings that name [email] in a user id. The WKD draft requires it of a published
     * key, and it stops a directory handing out one key for everybody.
     */
    fun forAddress(rings: List<PGPPublicKeyRing>, email: String): List<PGPPublicKeyRing> =
        rings.filter { ring -> Pgp.emailsOf(ring).any { it.equals(email.trim(), ignoreCase = true) } }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            // NORMAL follows redirects except from https to http, which is the one that matters.
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }

    /** What a lookup found, or why it found nothing, as a sentence. */
    data class Found(val rings: List<PGPPublicKeyRing>, val source: KeySource?, val note: String)

    /**
     * Asks WKD, then keys.openpgp.org, for [email]. Blocking; call it off the UI thread and
     * only when the person is writing to [email] with encryption on or pressed a button.
     */
    fun lookUp(email: String): Found {
        val urls = wkdUrls(email) ?: return Found(emptyList(), null, "$email is not an address a key can be looked up for.")
        val wkd = runCatching { fetchWkd(urls) }
        wkd.getOrNull()?.let { bytes ->
            val rings = runCatching { forAddress(Pgp.publicRings(bytes), email) }.getOrDefault(emptyList())
            if (rings.isNotEmpty()) return Found(rings, KeySource.WKD, "Found $email's key on their own domain.")
        }
        val vks = runCatching { fetch(vksUrl(email)) }
        vks.getOrNull()?.let { bytes ->
            val rings = runCatching { forAddress(Pgp.publicRings(bytes), email) }.getOrDefault(emptyList())
            if (rings.isNotEmpty()) return Found(rings, KeySource.VKS, "Found $email's key on keys.openpgp.org.")
        }
        val why = listOfNotNull(
            wkd.exceptionOrNull()?.message?.let { "their domain: $it" },
            vks.exceptionOrNull()?.message?.let { "keys.openpgp.org: $it" },
        ).joinToString("; ")
        return Found(
            emptyList(),
            null,
            if (why.isBlank()) "No key for $email was found on their domain or on keys.openpgp.org."
            else "No key for $email was found ($why).",
        )
    }

    /**
     * The advanced method, then the direct one only if the advanced host does not exist.
     * A null answer is "not published", which is not an error.
     */
    private fun fetchWkd(urls: Pair<String, String>): ByteArray? {
        return try {
            fetch(urls.first)
        } catch (e: java.net.ConnectException) {
            fetch(urls.second)
        } catch (e: java.nio.channels.UnresolvedAddressException) {
            fetch(urls.second)
        } catch (e: java.io.IOException) {
            if (generateSequence(e as Throwable) { it.cause }.any { it is java.net.UnknownHostException || it is java.nio.channels.UnresolvedAddressException }) {
                fetch(urls.second)
            } else {
                throw KeyLookupFailed("the lookup failed (${e.message ?: e::class.simpleName})")
            }
        }
    }

    /** One https GET, capped. Null on 404, bytes on 200, a sentence on anything else. */
    private fun fetch(url: String): ByteArray? {
        val uri = URI.create(url)
        if (!uri.scheme.equals("https", ignoreCase = true)) throw KeyLookupFailed("only https is used for key lookups")
        val response = http.send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (!response.uri().scheme.equals("https", ignoreCase = true)) throw KeyLookupFailed("the server redirected away from https")
        response.body().use { stream ->
            return when (response.statusCode()) {
                200 -> {
                    val bytes = stream.readNBytes(MOST_BYTES + 1)
                    if (bytes.size > MOST_BYTES) throw KeyLookupFailed("the answer was far too large to be a key")
                    bytes
                }
                404, 410 -> null
                else -> throw KeyLookupFailed("the server answered HTTP ${response.statusCode()}")
            }
        }
    }

    // ---- keys that are already in hand -----------------------------------------------

    /**
     * Keys inside a JSContact card (RFC 9553 `cryptoKeys`), as bytes. Only `data:` URIs:
     * anything else is a pointer to fetch, and fetching is not what reading a card should do.
     */
    fun keysInCard(card: JsonObject): List<ByteArray> {
        val keys = card["cryptoKeys"] as? JsonObject ?: return emptyList()
        return keys.values.mapNotNull { entry ->
            val uri = ((entry as? JsonObject)?.get("uri") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            dataUri(uri)
        }
    }

    /** The bytes of a `data:` URI, base64 or percent-encoded, or null for anything else. */
    fun dataUri(uri: String): ByteArray? {
        if (!uri.startsWith("data:", ignoreCase = true)) return null
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(5, comma)
        val payload = uri.substring(comma + 1)
        return runCatching {
            if (meta.endsWith(";base64", ignoreCase = true)) Base64.getMimeDecoder().decode(payload)
            else java.net.URLDecoder.decode(payload, StandardCharsets.UTF_8).toByteArray(StandardCharsets.UTF_8)
        }.getOrNull()
    }

    /**
     * The key in an Autocrypt header (autocrypt.org level 1), when the header's address is
     * the message's From. A key announced for some other address is ignored: anybody can
     * write a header, and this one would otherwise let a stranger attach a key to your boss.
     */
    fun autocryptKey(header: String, fromEmail: String): ByteArray? {
        val fields = header.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).trim().lowercase() to part.substring(eq + 1).trim()
        }.toMap()
        val addr = fields["addr"] ?: return null
        if (!addr.equals(fromEmail.trim(), ignoreCase = true)) return null
        val data = fields["keydata"] ?: return null
        return runCatching { Base64.getMimeDecoder().decode(data.replace(Regex("\\s"), "")) }.getOrNull()
    }
}

/** A key lookup that failed, with a reason that finishes a sentence. */
internal class KeyLookupFailed(reason: String) : Exception(reason)
