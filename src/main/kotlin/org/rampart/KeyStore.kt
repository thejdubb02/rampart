package org.rampart

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import java.time.LocalDate
import java.util.Base64

/*
 * The keys: yours, and everybody else's you have.
 *
 * **Two places, split by what is secret.** The list of keys, their public halves, where
 * each came from and which identity uses which, is an ordinary JSON file beside the other
 * settings (`keys.json`). Nothing in it is secret: a public key is meant to be handed out.
 * The secret halves go to the operating system's credential store through [Secrets], under
 * names of the form `openpgp-secret-<fingerprint>` and `smime-secret-<fingerprint>`, and
 * are never written to a file of ours. Where there is no credential store, a secret key
 * cannot be made or imported at all and the page says why: a secret key in a settings file
 * is one a backup, a sync tool or a bug report hands to somebody else.
 *
 * **Passphrases are never stored.** A key that arrived protected by one stays protected by
 * it. The passphrase is asked for when the key is first needed and held in memory for the
 * rest of the session, in [remembered], and gone when Rampart closes.
 */

internal enum class KeyKind(val label: String) { OPENPGP("OpenPGP"), SMIME("S/MIME") }

/** One key in the list. [publicKey] is armored OpenPGP or a PEM certificate. */
@Serializable
internal data class KeyEntry(
    val fingerprint: String,
    val kind: KeyKind,
    val name: String,
    val emails: List<String>,
    val publicKey: String,
    val hasSecret: Boolean = false,
    val needsPassphrase: Boolean = false,
    val source: KeySource = KeySource.IMPORTED,
    val trusted: Boolean = false,
    /** A self-signed S/MIME certificate made for trying things out. Nobody else trusts it. */
    val testingOnly: Boolean = false,
    val added: String = "",
)

/** Everything in `keys.json`. */
@Serializable
internal data class KeyIndex(
    val keys: List<KeyEntry> = emptyList(),
    /** An identity's address, lower case, to the fingerprint of the key it uses. */
    val identityKeys: Map<String, String> = emptyMap(),
    /**
     * Whether decrypted mail may be added to the local search index. **Off by default**, and
     * only ever honoured when the local store is itself encrypted; see [EncryptedSearch].
     */
    val searchInside: Boolean = false,
    /** Send OpenPGP inline rather than as PGP/MIME, for correspondents on very old clients. */
    val inlinePgp: Boolean = false,
)

/** Where the list and the secrets live. An interface so the tests keep both in memory. */
internal interface KeyBacking {
    fun readIndex(): KeyIndex
    fun writeIndex(index: KeyIndex): Boolean

    /** Null when it was kept, otherwise the reason it was not. */
    fun storeSecret(name: String, value: String): String?
    fun loadSecret(name: String): String?
    fun forgetSecret(name: String)
}

/** The real one: `keys.json` beside the settings, secrets in the operating system's store. */
internal object SystemKeyBacking : KeyBacking {
    private val store by lazy { JsonStore("keys.json") }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun readIndex(): KeyIndex = runCatching {
        store.read()["index"]?.let { json.decodeFromJsonElement(KeyIndex.serializer(), it) }
    }.getOrNull() ?: KeyIndex()

    override fun writeIndex(index: KeyIndex): Boolean =
        store.write { put("index", json.encodeToJsonElement(KeyIndex.serializer(), index)) }

    override fun storeSecret(name: String, value: String): String? {
        Secrets.unavailableReason()?.let { return it }
        return Secrets.storeNamed(name, value)
    }

    override fun loadSecret(name: String): String? = Secrets.loadNamed(name)

    override fun forgetSecret(name: String) {
        Secrets.storeNamed(name, "")
    }
}

internal class Keyring(private val backing: KeyBacking) {
    private val lock = Any()

    /** Passphrases typed this session, by fingerprint. Memory only. */
    private val remembered = mutableMapOf<String, CharArray>()

    /** Secret keys already read from the credential store this session, so it is asked once. */
    private val pgpCache = mutableMapOf<String, PGPSecretKeyRing>()

    /** S/MIME identities already unlocked this session, for the same reason. */
    private val smimeCache = mutableMapOf<String, SmimeIdentity>()

    fun index(): KeyIndex = synchronized(lock) { backing.readIndex() }

    fun entries(): List<KeyEntry> = index().keys

    private fun change(edit: (KeyIndex) -> KeyIndex): KeyIndex = synchronized(lock) {
        val next = edit(backing.readIndex())
        if (!backing.writeIndex(next)) throw CryptoFailure("The key list could not be saved on this computer.")
        next
    }

    private fun upsert(entry: KeyEntry) {
        change { index ->
            val existing = index.keys.firstOrNull { it.fingerprint == entry.fingerprint }
            val merged = if (existing == null) entry else entry.copy(
                // A public key arriving again must not forget that the secret half is here,
                // nor undo a decision to trust it.
                hasSecret = existing.hasSecret || entry.hasSecret,
                needsPassphrase = if (entry.hasSecret) entry.needsPassphrase else existing.needsPassphrase,
                trusted = existing.trusted || entry.trusted,
                source = if (existing.hasSecret) existing.source else entry.source,
                testingOnly = existing.testingOnly || entry.testingOnly,
                added = existing.added.ifBlank { entry.added },
            )
            index.copy(keys = index.keys.filterNot { it.fingerprint == entry.fingerprint } + merged)
        }
    }

    private fun today() = LocalDate.now().toString()

    private fun secretName(kind: KeyKind, fingerprint: String) =
        (if (kind == KeyKind.OPENPGP) "openpgp-secret-" else "smime-secret-") + fingerprint

    // ---- adding keys -----------------------------------------------------------------

    /** Adds every OpenPGP public key in [bytes]. Returns what was added. */
    fun addPgpPublic(bytes: ByteArray, source: KeySource, trusted: Boolean = source.trustedByDefault): List<KeyEntry> =
        Pgp.publicRings(bytes).map { ring ->
            KeyEntry(
                fingerprint = Pgp.fingerprintOf(ring),
                kind = KeyKind.OPENPGP,
                name = Pgp.nameOf(ring),
                emails = Pgp.emailsOf(ring),
                publicKey = Pgp.armor(ring.encoded),
                source = source,
                trusted = trusted,
                added = today(),
            ).also(::upsert)
        }

    /** Adds a correspondent's certificate, PEM or DER. */
    fun addCertificate(bytes: ByteArray, source: KeySource, trusted: Boolean = source.trustedByDefault): KeyEntry {
        val cert = Smime.readCertificate(bytes)
        return KeyEntry(
            fingerprint = Smime.fingerprintOf(cert),
            kind = KeyKind.SMIME,
            name = Smime.nameOf(cert),
            emails = Smime.emailsOf(cert),
            publicKey = Smime.pem(cert),
            source = source,
            trusted = trusted,
            testingOnly = Smime.selfSigned(cert) && source == KeySource.MINE,
            added = today(),
        ).also(::upsert)
    }

    /**
     * Makes a new OpenPGP key for [email] and keeps its secret half in the credential store.
     *
     * The secret is stored before the key is listed, so a store that refuses leaves nothing
     * behind that looks like a working key.
     */
    fun generatePgp(name: String, email: String, algorithm: PgpAlgorithm, passphrase: CharArray? = null): KeyEntry {
        val made = Pgp.generate(name, email, algorithm, passphrase)
        backing.storeSecret(secretName(KeyKind.OPENPGP, made.fingerprint), Base64.getEncoder().encodeToString(made.secretBinary))
            ?.let { throw CryptoFailure("The new key was not kept, because the credential store refused it: ${it.removeSuffix(".")}.") }
        val ring = Pgp.publicRings(made.publicArmored.toByteArray()).single()
        val entry = KeyEntry(
            fingerprint = made.fingerprint,
            kind = KeyKind.OPENPGP,
            name = Pgp.nameOf(ring),
            emails = Pgp.emailsOf(ring),
            publicKey = made.publicArmored,
            hasSecret = true,
            needsPassphrase = passphrase != null && passphrase.isNotEmpty(),
            source = KeySource.MINE,
            trusted = true,
            added = today(),
        )
        upsert(entry)
        passphrase?.takeIf { it.isNotEmpty() }?.let { synchronized(lock) { remembered[made.fingerprint] = it.copyOf() } }
        if (index().identityKeys[email.lowercase()] == null) setIdentityKey(email, made.fingerprint)
        return entry
    }

    /** Imports an OpenPGP secret key, keeping whatever passphrase it came with. */
    fun importPgpSecret(bytes: ByteArray): KeyEntry {
        val ring = Pgp.secretRing(bytes)
        val fingerprint = Pgp.fingerprintOf(ring)
        backing.storeSecret(secretName(KeyKind.OPENPGP, fingerprint), Base64.getEncoder().encodeToString(ring.encoded))
            ?.let { throw CryptoFailure("The key was not imported, because the credential store refused it: ${it.removeSuffix(".")}.") }
        val public = Pgp.publicOf(ring)
        val entry = KeyEntry(
            fingerprint = fingerprint,
            kind = KeyKind.OPENPGP,
            name = Pgp.nameOf(public),
            emails = Pgp.emailsOf(public),
            publicKey = Pgp.armor(public.encoded),
            hasSecret = true,
            needsPassphrase = Pgp.needsPassphrase(ring),
            source = KeySource.IMPORTED,
            trusted = true,
            added = today(),
        )
        upsert(entry)
        return entry
    }

    /**
     * Imports a PKCS#12 file from a certificate authority. [password] is checked here and not
     * kept: the file is stored as it was given, and a non-empty password is asked for again
     * at use.
     */
    fun importPkcs12(bytes: ByteArray, password: CharArray): KeyEntry {
        val identity = Smime.readPkcs12(bytes, password)
        val fingerprint = Smime.fingerprintOf(identity.certificate)
        backing.storeSecret(secretName(KeyKind.SMIME, fingerprint), Base64.getEncoder().encodeToString(bytes))
            ?.let { throw CryptoFailure("The certificate was not imported, because the credential store refused it: ${it.removeSuffix(".")}.") }
        val entry = KeyEntry(
            fingerprint = fingerprint,
            kind = KeyKind.SMIME,
            name = Smime.nameOf(identity.certificate),
            emails = Smime.emailsOf(identity.certificate),
            publicKey = Smime.pem(identity.certificate),
            hasSecret = true,
            needsPassphrase = password.isNotEmpty(),
            source = KeySource.IMPORTED,
            trusted = true,
            testingOnly = Smime.selfSigned(identity.certificate),
            added = today(),
        )
        upsert(entry)
        if (password.isNotEmpty()) synchronized(lock) { remembered[fingerprint] = password.copyOf() }
        return entry
    }

    /** A self-signed certificate for trying S/MIME out. Marked as a test certificate for good. */
    fun makeTestCertificate(name: String, email: String): KeyEntry {
        val identity = Smime.selfSigned(name, email)
        val fingerprint = Smime.fingerprintOf(identity.certificate)
        val file = Smime.writePkcs12(identity, CharArray(0))
        backing.storeSecret(secretName(KeyKind.SMIME, fingerprint), Base64.getEncoder().encodeToString(file))
            ?.let { throw CryptoFailure("The test certificate was not kept, because the credential store refused it: ${it.removeSuffix(".")}.") }
        val entry = KeyEntry(
            fingerprint = fingerprint,
            kind = KeyKind.SMIME,
            name = Smime.nameOf(identity.certificate),
            emails = Smime.emailsOf(identity.certificate),
            publicKey = Smime.pem(identity.certificate),
            hasSecret = true,
            source = KeySource.MINE,
            trusted = true,
            testingOnly = true,
            added = today(),
        )
        upsert(entry)
        if (index().identityKeys[email.lowercase()] == null) setIdentityKey(email, fingerprint)
        return entry
    }

    // ---- choosing and changing -------------------------------------------------------

    fun setIdentityKey(email: String, fingerprint: String?) {
        change { index ->
            val keys = index.identityKeys.toMutableMap()
            if (fingerprint == null) keys.remove(email.lowercase()) else keys[email.lowercase()] = fingerprint
            index.copy(identityKeys = keys)
        }
    }

    fun setTrusted(fingerprint: String, trusted: Boolean) {
        change { index -> index.copy(keys = index.keys.map { if (it.fingerprint == fingerprint) it.copy(trusted = trusted) else it }) }
    }

    fun setSearchInside(on: Boolean) {
        change { it.copy(searchInside = on) }
    }

    fun setInlinePgp(on: Boolean) {
        change { it.copy(inlinePgp = on) }
    }

    /** Removes a key, and its secret half from the credential store when there is one. */
    fun remove(fingerprint: String) {
        val entry = entries().firstOrNull { it.fingerprint == fingerprint } ?: return
        if (entry.hasSecret) backing.forgetSecret(secretName(entry.kind, fingerprint))
        synchronized(lock) {
            pgpCache.remove(fingerprint)
            smimeCache.remove(fingerprint)
            remembered.remove(fingerprint)?.fill('\u0000')
        }
        change { index ->
            index.copy(
                keys = index.keys.filterNot { it.fingerprint == fingerprint },
                identityKeys = index.identityKeys.filterValues { it != fingerprint },
            )
        }
    }

    fun rememberPassphrase(fingerprint: String, passphrase: CharArray) = synchronized(lock) {
        remembered[fingerprint] = passphrase.copyOf()
    }

    /** Forgets one passphrase, after it turned out to be wrong, so it is asked for again. */
    fun forgetPassphrase(fingerprint: String) = synchronized(lock) {
        remembered.remove(fingerprint)?.fill('\u0000')
        smimeCache.remove(fingerprint)
    }

    /** Forgets every passphrase typed this session, and every key unlocked with one. */
    fun forgetPassphrases() = synchronized(lock) {
        remembered.values.forEach { it.fill('\u0000') }
        remembered.clear()
        smimeCache.clear()
    }

    val passphrases: Passphrases = Passphrases { fingerprint -> synchronized(lock) { remembered[fingerprint]?.copyOf() } }

    // ---- finding keys ----------------------------------------------------------------

    /** The key an identity signs with, and encrypts its own copy to. */
    fun ownKey(identityEmail: String, kind: KeyKind? = null): KeyEntry? {
        val index = index()
        val chosen = index.identityKeys[identityEmail.lowercase()]?.let { fp -> index.keys.firstOrNull { it.fingerprint == fp } }
        if (chosen != null && chosen.hasSecret && (kind == null || chosen.kind == kind)) return chosen
        return index.keys.firstOrNull { it.hasSecret && (kind == null || it.kind == kind) && identityEmail.lowercase() in it.emails }
    }

    /**
     * The key to encrypt to for [email], of [kind]. Trusted keys first; a key that only arrived
     * attached to a message is used only once somebody has said it is theirs.
     */
    fun publicFor(email: String, kind: KeyKind): KeyEntry? {
        val matching = entries().filter { it.kind == kind && email.trim().lowercase() in it.emails }
        return matching.firstOrNull { it.hasSecret } ?: matching.firstOrNull { it.trusted }
    }

    fun trusted(fingerprint: String): Boolean = entries().any { it.fingerprint == fingerprint && (it.trusted || it.hasSecret) }

    fun pgpPublicRing(entry: KeyEntry): PGPPublicKeyRing = Pgp.publicRings(entry.publicKey.toByteArray()).single()

    fun pgpSecretRing(fingerprint: String): PGPSecretKeyRing {
        synchronized(lock) { pgpCache[fingerprint]?.let { return it } }
        val stored = backing.loadSecret(secretName(KeyKind.OPENPGP, fingerprint))
            ?: throw CryptoFailure("The secret half of the key ${Pgp.short(fingerprint)} is not in this computer's credential store.")
        val ring = Pgp.secretRing(Base64.getDecoder().decode(stored))
        synchronized(lock) { pgpCache[fingerprint] = ring }
        return ring
    }

    /** An S/MIME identity, asking for its password when it has one and none was typed yet. */
    fun smimeIdentity(fingerprint: String): SmimeIdentity {
        synchronized(lock) { smimeCache[fingerprint]?.let { return it } }
        val entry = entries().firstOrNull { it.fingerprint == fingerprint && it.kind == KeyKind.SMIME && it.hasSecret }
            ?: throw CryptoFailure("There is no S/MIME certificate with a private key under that fingerprint.")
        val password = if (entry.needsPassphrase) passphrases.forKey(fingerprint) ?: throw PassphraseNeeded(fingerprint) else CharArray(0)
        val stored = backing.loadSecret(secretName(KeyKind.SMIME, fingerprint))
            ?: throw CryptoFailure("The private key for ${entry.name.ifBlank { Pgp.short(fingerprint) }} is not in this computer's credential store.")
        val identity = try {
            Smime.readPkcs12(Base64.getDecoder().decode(stored), password)
        } catch (e: CryptoFailure) {
            forgetPassphrase(fingerprint)
            throw e
        }
        synchronized(lock) { smimeCache[fingerprint] = identity }
        return identity
    }

    /** Keys whose passphrase has not been typed this session. The reader offers to take one. */
    fun locked(): List<KeyEntry> = entries().filter { it.hasSecret && it.needsPassphrase && passphrases.forKey(it.fingerprint) == null }

    /** Every key, as the reader wants them. Locked S/MIME certificates are left out until unlocked. */
    fun sealKeys(): SealKeys = object : SealKeys {
        override fun pgpSecrets(): List<PGPSecretKeyRing> =
            entries().filter { it.kind == KeyKind.OPENPGP && it.hasSecret }.mapNotNull { runCatching { pgpSecretRing(it.fingerprint) }.getOrNull() }

        override fun pgpPublics(): List<PGPPublicKeyRing> =
            entries().filter { it.kind == KeyKind.OPENPGP }.mapNotNull { runCatching { pgpPublicRing(it) }.getOrNull() }

        override fun smimeIdentities(): List<SmimeIdentity> =
            entries().filter { it.kind == KeyKind.SMIME && it.hasSecret }.mapNotNull { runCatching { smimeIdentity(it.fingerprint) }.getOrNull() }

        override val passphrases: Passphrases get() = this@Keyring.passphrases
    }

    // ---- export ----------------------------------------------------------------------

    fun exportPublic(fingerprint: String): String =
        entries().firstOrNull { it.fingerprint == fingerprint }?.publicKey
            ?: throw CryptoFailure("There is no key with that fingerprint.")

    /**
     * The secret key, for moving it to another program. Refused unless [confirmed], which the
     * page only passes after the person has read what exporting a secret key means and said yes.
     * OpenPGP comes out armored; S/MIME comes out as the PKCS#12 file it went in as.
     */
    fun exportSecret(fingerprint: String, confirmed: Boolean): ByteArray {
        if (!confirmed) throw CryptoFailure("A secret key is only exported after you confirm it, because anybody with the file can read your mail.")
        val entry = entries().firstOrNull { it.fingerprint == fingerprint && it.hasSecret }
            ?: throw CryptoFailure("There is no secret key with that fingerprint on this computer.")
        val stored = backing.loadSecret(secretName(entry.kind, fingerprint))
            ?: throw CryptoFailure("The secret key is not in this computer's credential store.")
        val bytes = Base64.getDecoder().decode(stored)
        return if (entry.kind == KeyKind.OPENPGP) {
            val armored = java.io.ByteArrayOutputStream()
            org.bouncycastle.bcpg.ArmoredOutputStream.builder().clearHeaders().build(armored).use { it.write(bytes) }
            armored.toByteArray()
        } else {
            bytes
        }
    }
}

/** The one key ring the application uses. */
internal object Keys {
    val ring: Keyring by lazy { Keyring(SystemKeyBacking) }
}

/**
 * Searching inside encrypted mail: a setting, off by default.
 *
 * When it is on, a message decrypted in the reader has its text added to the local search
 * index, and only there. That index lives in the local store, which is SQLCipher with its key
 * in the credential store, so the decrypted words are encrypted at rest again. With no
 * encrypted store (no credential store on this computer, or a store opened without a key)
 * nothing is indexed at all, whatever the setting says: decrypted mail in a plain file is
 * exactly what the person turned encryption on to avoid. Server-side search never sees any
 * of it; decrypted text is never sent anywhere.
 */
internal object EncryptedSearch {
    const val DEFAULT = false

    fun mayIndex(settingOn: Boolean, storeEncrypted: Boolean): Boolean = settingOn && storeEncrypted
}
