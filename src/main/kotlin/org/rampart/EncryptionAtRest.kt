package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * Stalwart's encryption at rest, switched on for your own account with your own public key.
 *
 * Stalwart 0.16 can encrypt every message it stores for an account to that account's
 * OpenPGP key or S/MIME certificate, so the copy on the server's disk is readable only with
 * the private key, which never leaves the person's computer. `docs/encryption.md` has what it
 * does and does not cover, taken from the server's source.
 *
 * Two self-service objects do it, both reachable by the signed-in user with their own mail
 * session (the default user role carries `sysPublicKey*` and `sysAccountSettings*`):
 *
 * - `x:PublicKey`, a collection: the key itself (`key`, armored OpenPGP or PEM), a
 *   `description`, and optionally `emailAddresses` and `expiresAt`. `accountId` and
 *   `createdAt` are the server's to set.
 * - `x:AccountSettings`, a singleton: `encryptionAtRest` is `{"@type": "Disabled"}` or one of
 *   `Aes128`, `Aes256`, `Aes256Gcm`, `ChaCha20Poly1305` with `publicKey` (the id of an
 *   `x:PublicKey`), `encryptOnAppend` and `allowSpamTraining`.
 *
 * **No admin token, ever,** as with the Security page this sits on. An administrator can take
 * either permission away, and then the section says so in one sentence rather than asking for
 * anything more.
 */

/** The algorithms Stalwart offers, as it names them on the wire. */
internal enum class RestCipher(val wire: String, val label: String, val pgpAllowed: Boolean) {
    AES256("Aes256", "AES-256", true),
    AES128("Aes128", "AES-128", true),
    AES256_GCM("Aes256Gcm", "AES-256-GCM (S/MIME only)", false),
    CHACHA20("ChaCha20Poly1305", "ChaCha20-Poly1305 (S/MIME only)", false),
    ;

    companion object {
        fun ofWire(name: String?): RestCipher? = entries.firstOrNull { it.wire == name }
    }
}

/** The account's current setting. [cipher] null means it is off. */
internal data class RestState(
    val cipher: RestCipher?,
    val publicKeyId: String?,
    val encryptOnAppend: Boolean,
    val allowSpamTraining: Boolean,
)

/** One public key the server holds for this account. */
internal data class ServerPublicKey(val id: String, val description: String, val emails: List<String>, val key: String)

private fun restMethod(name: String, accountId: String?, args: JsonObjectBuilder.() -> Unit): JsonArray =
    buildJsonArray {
        add(name)
        add(buildJsonObject {
            if (accountId != null) put("accountId", accountId)
            args()
        })
        add("0")
    }

internal fun restStateCall(accountId: String?): JsonArray =
    restMethod("x:AccountSettings/get", accountId) {
        putJsonArray("ids") { add(SINGLETON) }
        putJsonArray("properties") { add("encryptionAtRest") }
    }

internal fun publicKeysCall(accountId: String?): JsonArray =
    restMethod("x:PublicKey/get", accountId) { put("ids", JsonNull) }

/**
 * Uploads [armored] as a public key. `emailAddresses` is a set, sent the JMAP way as an
 * object of `true` values, and only when there are any: the server checks each is an address.
 */
internal fun createPublicKeyCall(accountId: String?, armored: String, description: String, emails: List<String>): JsonArray =
    restMethod("x:PublicKey/set", accountId) {
        putJsonObject("create") {
            putJsonObject("key") {
                put("key", armored.trim() + "\n")
                put("description", description.ifBlank { "Rampart" })
                if (emails.isNotEmpty()) putJsonObject("emailAddresses") { emails.forEach { put(it, true) } }
            }
        }
    }

internal fun destroyPublicKeyCall(accountId: String?, id: String): JsonArray =
    restMethod("x:PublicKey/set", accountId) { putJsonArray("destroy") { add(id) } }

/**
 * Turns it on. Spam training stays off: with it on, Stalwart keeps a readable sample of
 * each message for its classifier, which is the opposite of what somebody turning this on
 * wants. `docs/encryption.md` says so.
 */
internal fun enableRestCall(accountId: String?, cipher: RestCipher, publicKeyId: String, encryptOnAppend: Boolean): JsonArray =
    restMethod("x:AccountSettings/set", accountId) {
        putJsonObject("update") {
            putJsonObject(SINGLETON) {
                putJsonObject("encryptionAtRest") {
                    put("@type", cipher.wire)
                    put("publicKey", publicKeyId)
                    put("encryptOnAppend", encryptOnAppend)
                    put("allowSpamTraining", false)
                }
            }
        }
    }

internal fun disableRestCall(accountId: String?): JsonArray =
    restMethod("x:AccountSettings/set", accountId) {
        putJsonObject("update") {
            putJsonObject(SINGLETON) { putJsonObject("encryptionAtRest") { put("@type", "Disabled") } }
        }
    }

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.contentOrNull == "true"

private fun restArgs(response: JsonArray): JsonObject? = response.getOrNull(1) as? JsonObject

internal fun readRestState(response: JsonArray): RestState {
    val item = (restArgs(response)?.get("list") as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull()
    val rest = item?.get("encryptionAtRest") as? JsonObject
    val cipher = RestCipher.ofWire(rest?.get("@type").str())
    return RestState(
        cipher = cipher,
        publicKeyId = rest?.get("publicKey").str(),
        encryptOnAppend = rest?.get("encryptOnAppend").bool(),
        allowSpamTraining = rest?.get("allowSpamTraining").bool(),
    )
}

internal fun readPublicKeys(response: JsonArray): List<ServerPublicKey> =
    (restArgs(response)?.get("list") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
        ServerPublicKey(
            id = o["id"].str() ?: return@mapNotNull null,
            description = o["description"].str().orEmpty(),
            emails = (o["emailAddresses"] as? JsonObject)?.keys?.toList()
                ?: (o["emailAddresses"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty(),
            key = o["key"].str().orEmpty(),
        )
    }

internal fun readCreatedPublicKey(response: JsonArray): String {
    val a = restArgs(response) ?: throw JmapError("The server's reply to the new public key was empty.")
    ((a["created"] as? JsonObject)?.get("key") as? JsonObject)?.get("id").str()?.let { return it }
    val refused = (a["notCreated"] as? JsonObject)?.get("key") as? JsonObject
    throw JmapError(
        if (refused != null) refusalSentence(refused, "The server did not take your public key")
        else "The server did not take your public key and did not say why.",
    )
}

/**
 * The Stalwart-shaped key text for [entry]: armored OpenPGP as it is, or the PEM certificate.
 * Stalwart reads PEM blocks and refuses a mix of the two kinds, which one entry never is.
 */
internal fun serverKeyText(entry: KeyEntry): String = entry.publicKey.replace("\r\n", "\n")

/** The ciphers that make sense for [kind]: Stalwart refuses the AEAD ones with an OpenPGP key. */
internal fun ciphersFor(kind: KeyKind): List<RestCipher> =
    if (kind == KeyKind.OPENPGP) RestCipher.entries.filter { it.pgpAllowed } else RestCipher.entries.toList()

/**
 * Why this account cannot turn on the server's encryption, or null when it can. The same
 * shape as [securityUnavailable], asked of the protocol and the session, never a host name.
 */
internal fun restUnavailable(protocol: String?, managementAccountId: String?): String? = when {
    protocol == null -> "Sign in to an account first."
    protocol == "imap" -> "This account is signed in over IMAP, which has no way to reach the server's encryption setting."
    managementAccountId == null -> "This server is not Stalwart, so it has no encryption at rest for Rampart to turn on."
    else -> null
}

/** A method-level `forbidden` here means an administrator took the self-service permission away. */
internal fun restFailure(e: Throwable, action: String): String {
    val said = whyFailed(e)
    return if (said.contains("refused the request: forbidden", ignoreCase = true)) {
        "$action: this server does not let accounts set their own encryption. Only an administrator can change that, and Rampart will not ask for administrator rights."
    } else {
        securityFailure(e, action)
    }
}

/** The calls, on one live JMAP session, as the signed-in person. */
internal class EncryptionAtRest(private val jmap: Jmap) {
    private val accountId: String? get() = jmap.managementAccountId

    fun state(): RestState = try {
        readRestState(jmap.manage(restStateCall(accountId)).first())
    } catch (e: Exception) {
        throw JmapError(restFailure(e, "Could not read this account's encryption setting"))
    }

    fun publicKeys(): List<ServerPublicKey> = try {
        readPublicKeys(jmap.manage(publicKeysCall(accountId)).first())
    } catch (e: Exception) {
        throw JmapError(restFailure(e, "Could not list the public keys on the server"))
    }

    /** Uploads [entry]'s public key unless the server already has exactly that key, and returns its id. */
    fun ensureKey(entry: KeyEntry): String {
        val text = serverKeyText(entry).trim()
        publicKeys().firstOrNull { it.key.trim() == text }?.let { return it.id }
        val response = try {
            jmap.manage(createPublicKeyCall(accountId, text, "Rampart: ${entry.name.ifBlank { entry.fingerprint.takeLast(16) }}", entry.emails)).first()
        } catch (e: Exception) {
            throw JmapError(restFailure(e, "The server did not take your public key"))
        }
        return readCreatedPublicKey(response)
    }

    fun enable(entry: KeyEntry, cipher: RestCipher, encryptOnAppend: Boolean) {
        if (entry.kind == KeyKind.OPENPGP && !cipher.pgpAllowed) {
            throw JmapError("${cipher.label} only works with an S/MIME certificate. Choose AES-256 for an OpenPGP key.")
        }
        val id = ensureKey(entry)
        update(enableRestCall(accountId, cipher, id, encryptOnAppend), "Encryption at rest was not turned on")
    }

    fun disable() = update(disableRestCall(accountId), "Encryption at rest was not turned off")

    private fun update(call: JsonArray, action: String) {
        val response = try {
            jmap.manage(call).first()
        } catch (e: Exception) {
            throw JmapError(restFailure(e, action))
        }
        readPasswordUpdate(response, action)?.let { throw JmapError(it) }
    }
}
