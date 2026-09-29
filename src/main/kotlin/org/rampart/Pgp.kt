package org.rampart

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.Features
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPMarker
import org.bouncycastle.openpgp.PGPOnePassSignature
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Date

/*
 * OpenPGP, in process, on Bouncy Castle.
 *
 * This file is the only place that touches the OpenPGP packet API. Everything above it
 * works in bytes and armored text, so the MIME layer (CryptoMime.kt) and the key store
 * (KeyStore.kt) never see a packet, and a Bouncy Castle upgrade that moves an API moves it
 * here and nowhere else.
 *
 * The lightweight "Bc" operators are used throughout rather than the JCA ones. They need no
 * security provider registered with the JVM, so nothing here changes how the rest of the
 * application's TLS or its credential store behaves.
 *
 * Every failure a person could cause or meet (a key that is not there, a wrong passphrase,
 * a message someone changed on the way) comes out as a [CryptoFailure] carrying one
 * sentence. A crash in here would read as "Rampart broke" when the truth is usually "this
 * message was not for this key", which is something a person can act on.
 */

/** Which kind of OpenPGP key to make. Both are what current OpenPGP software reads. */
internal enum class PgpAlgorithm(val label: String) {
    /** Ed25519 to sign and X25519 to encrypt, as version 4 keys, which GnuPG, Thunderbird and Proton all read. */
    CURVE25519("Ed25519 and X25519 (recommended)"),
    RSA3072("RSA 3072"),
    RSA4096("RSA 4096"),
}

/** A key pair just made, in the forms it is kept in. */
internal data class PgpGenerated(
    val publicArmored: String,
    /** The secret key ring in binary form, which is what goes to the credential store. */
    val secretBinary: ByteArray,
    val fingerprint: String,
)

/** What came out of an OpenPGP message: the content and, when it was signed, who signed it. */
internal data class PgpOpened(val content: ByteArray, val signature: SignatureVerdict?, val encrypted: Boolean)

/**
 * Hands out a secret key's passphrase when one is needed, keyed by the key's fingerprint.
 *
 * Null means none is known, and decryption then stops with a sentence asking for it. Asked
 * rather than stored because an imported key that came with a passphrase keeps needing it:
 * the passphrase is held in memory for the session by the caller and never written down.
 */
internal fun interface Passphrases {
    fun forKey(fingerprint: String): CharArray?
}

internal object Pgp {
    private val random = SecureRandom()
    private val fingerprints = BcKeyFingerprintCalculator()
    private val digests = BcPGPDigestCalculatorProvider()

    // ---- keys ------------------------------------------------------------------------

    /**
     * Makes a new key: a primary key that certifies and signs, and a subkey that encrypts.
     *
     * [passphrase] is optional on purpose. A key Rampart makes is kept in the operating
     * system's credential store, which is already locked to the signed-in desktop user, so
     * a second secret on top of it is a choice rather than a requirement.
     */
    fun generate(name: String, email: String, algorithm: PgpAlgorithm, passphrase: CharArray? = null): PgpGenerated {
        val now = Date()
        val (primary, encryption) = when (algorithm) {
            PgpAlgorithm.CURVE25519 -> {
                val sign = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(random)) }.generateKeyPair()
                val enc = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(random)) }.generateKeyPair()
                BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.EDDSA_LEGACY, sign, now) to
                    BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.ECDH, enc, now)
            }
            PgpAlgorithm.RSA3072, PgpAlgorithm.RSA4096 -> {
                val bits = if (algorithm == PgpAlgorithm.RSA3072) 3072 else 4096
                BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.RSA_GENERAL, rsa(bits), now) to
                    BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.RSA_GENERAL, rsa(bits), now)
            }
        }
        val certify = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(true, KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA)
            setPreferredSymmetricAlgorithms(false, intArrayOf(SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_128))
            setPreferredHashAlgorithms(false, intArrayOf(HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA256))
            setPreferredCompressionAlgorithms(false, intArrayOf(CompressionAlgorithmTags.ZLIB, CompressionAlgorithmTags.UNCOMPRESSED))
            setFeature(false, Features.FEATURE_MODIFICATION_DETECTION)
        }
        val encrypt = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(true, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
        }
        val protector = passphrase?.takeIf { it.isNotEmpty() }?.let {
            BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, digests.get(HashAlgorithmTags.SHA256), 0x60)
                .setSecureRandom(random)
                .build(it)
        }
        val generator = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            primary,
            userId(name, email),
            digests.get(HashAlgorithmTags.SHA1),
            certify.generate(),
            null,
            BcPGPContentSignerBuilder(primary.publicKey.algorithm, HashAlgorithmTags.SHA256),
            protector,
        )
        generator.addSubKey(encryption, encrypt.generate(), null)
        val secret = generator.generateSecretKeyRing()
        val public = generator.generatePublicKeyRing()
        return PgpGenerated(armor(public.encoded), secret.encoded, fingerprintOf(public))
    }

    private fun rsa(bits: Int): AsymmetricCipherKeyPair =
        RSAKeyPairGenerator().apply {
            init(RSAKeyGenerationParameters(BigInteger.valueOf(65537), random, bits, 80))
        }.generateKeyPair()

    /** `Name <address>`, the form every OpenPGP program writes and searches by. */
    fun userId(name: String, email: String): String =
        if (name.isBlank()) "<${email.trim()}>" else "${name.trim()} <${email.trim()}>"

    /** Armored text, which is what people paste and what a key server hands out. */
    fun armor(binary: ByteArray): String {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream.builder().clearHeaders().build(out).use { it.write(binary) }
        return out.toString(Charsets.US_ASCII)
    }

    /**
     * Every public key ring in [data], armored or binary, several or one.
     *
     * A secret key ring counts as its public half, so pasting your own exported secret key
     * into a public key field works rather than failing on a technicality.
     */
    fun publicRings(data: ByteArray): List<PGPPublicKeyRing> {
        val found = mutableListOf<PGPPublicKeyRing>()
        try {
            val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(data)))
            while (true) {
                when (val next = factory.nextObject() ?: break) {
                    is PGPPublicKeyRing -> found += next
                    is PGPSecretKeyRing -> found += PGPPublicKeyRing(next.publicKeys.asSequence().toList())
                }
            }
        } catch (e: Exception) {
            if (found.isEmpty()) throw CryptoFailure("That is not an OpenPGP key Rampart can read.")
        }
        if (found.isEmpty()) throw CryptoFailure("There is no OpenPGP public key in that.")
        return found
    }

    /** The one secret key ring in [data], armored or binary. */
    fun secretRing(data: ByteArray): PGPSecretKeyRing {
        try {
            val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(data)))
            while (true) {
                val next = factory.nextObject() ?: break
                if (next is PGPSecretKeyRing) return next
            }
        } catch (_: Exception) {
            throw CryptoFailure("That is not an OpenPGP secret key Rampart can read.")
        }
        throw CryptoFailure("There is no OpenPGP secret key in that.")
    }

    /** The primary key's fingerprint as upper case hex, which is how people compare them. */
    fun fingerprintOf(ring: PGPPublicKeyRing): String = hex(ring.publicKey.fingerprint)

    fun fingerprintOf(ring: PGPSecretKeyRing): String = hex(ring.publicKey.fingerprint)

    /** The addresses a key names in its user ids, lower case, in the order it lists them. */
    fun emailsOf(ring: PGPPublicKeyRing): List<String> =
        ring.publicKey.userIDs.asSequence().mapNotNull(::emailInUserId).map { it.lowercase() }.distinct().toList()

    /** The first user id, which is the name a key goes by in a list. */
    fun nameOf(ring: PGPPublicKeyRing): String = ring.publicKey.userIDs.asSequence().firstOrNull().orEmpty()

    /** Whether using this secret key needs a passphrase first. */
    fun needsPassphrase(ring: PGPSecretKeyRing): Boolean =
        ring.secretKeys.asSequence().any { it.keyEncryptionAlgorithm != SymmetricKeyAlgorithmTags.NULL }

    /** The public half of a secret key ring, armored, for export or for publishing. */
    fun publicOf(ring: PGPSecretKeyRing): PGPPublicKeyRing = PGPPublicKeyRing(ring.publicKeys.asSequence().toList())

    /** Whether the key has not expired and has not been revoked. */
    private fun usable(key: PGPPublicKey): Boolean {
        if (key.hasRevocation()) return false
        val seconds = key.validSeconds
        return seconds == 0L || key.creationTime.time + seconds * 1000 > System.currentTimeMillis()
    }

    /**
     * The key in [ring] to encrypt to: a subkey flagged for it, else the primary if that can.
     *
     * The flags are read from the binding signatures rather than guessed from the algorithm,
     * because an RSA key is equally able to sign and encrypt and only the flags say which it
     * was meant for.
     */
    fun encryptionKey(ring: PGPPublicKeyRing): PGPPublicKey? {
        val keys = ring.publicKeys.asSequence().toList()
        fun flagged(key: PGPPublicKey): Boolean = key.signatures.asSequence().any { sig ->
            val flags = sig.hashedSubPackets?.keyFlags ?: 0
            flags and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0
        }
        return keys.firstOrNull { !it.isMasterKey && it.isEncryptionKey && usable(it) && flagged(it) }
            ?: keys.firstOrNull { !it.isMasterKey && it.isEncryptionKey && usable(it) }
            ?: keys.firstOrNull { it.isMasterKey && it.isEncryptionKey && usable(it) && flagged(it) }
    }

    /** The secret key in [ring] to sign with: the primary, or a subkey flagged for signing. */
    private fun signingKey(ring: PGPSecretKeyRing): PGPSecretKey {
        val keys = ring.secretKeys.asSequence().toList()
        fun flagged(key: PGPSecretKey): Boolean = key.publicKey.signatures.asSequence().any { sig ->
            (sig.hashedSubPackets?.keyFlags ?: 0) and KeyFlags.SIGN_DATA != 0
        }
        return keys.firstOrNull { !it.isMasterKey && it.isSigningKey && flagged(it) }
            ?: keys.firstOrNull { it.isMasterKey && it.isSigningKey }
            ?: throw CryptoFailure("Your OpenPGP key has no part that can sign.")
    }

    /** The private key inside [secret], unlocked with its passphrase when it has one. */
    private fun unlock(secret: PGPSecretKey, ringFingerprint: String, passphrases: Passphrases): PGPPrivateKey {
        val locked = secret.keyEncryptionAlgorithm != SymmetricKeyAlgorithmTags.NULL
        val phrase = if (locked) {
            passphrases.forKey(ringFingerprint)
                ?: throw PassphraseNeeded(ringFingerprint)
        } else {
            CharArray(0)
        }
        return try {
            secret.extractPrivateKey(BcPBESecretKeyDecryptorBuilder(digests).build(phrase))
        } catch (e: PGPException) {
            throw WrongPassphrase(ringFingerprint)
        }
    }

    // ---- encrypting and signing -------------------------------------------------------

    /**
     * Encrypts [data] to every ring in [recipients], signing it inside when [signer] is set.
     *
     * AES-256 with the integrity packet always on: a message without it is exactly the
     * kind EFAIL rewrote in flight, and nothing current fails to read one with it. Signing
     * goes inside the encryption (RFC 3156 section 6.2, the combined form), which is what
     * Thunderbird and GnuPG write and so what both read back without a second layer.
     */
    fun encrypt(
        data: ByteArray,
        recipients: List<PGPPublicKeyRing>,
        signer: PGPSecretKeyRing? = null,
        passphrases: Passphrases = Passphrases { null },
        armored: Boolean = true,
    ): ByteArray {
        if (recipients.isEmpty()) throw CryptoFailure("There is nobody to encrypt this to.")
        val generator = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(random),
        )
        for (ring in recipients) {
            val key = encryptionKey(ring)
                ?: throw CryptoFailure("The key for ${emailsOf(ring).firstOrNull() ?: short(fingerprintOf(ring))} cannot be used to encrypt, or has expired.")
            generator.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(key).setSecureRandom(random))
        }
        val sink = ByteArrayOutputStream()
        val outer = if (armored) ArmoredOutputStream.builder().clearHeaders().build(sink) else sink
        generator.open(outer, ByteArray(1 shl 16)).use { encrypted ->
            val signing = signer?.let { startSignature(it, passphrases, PGPSignature.BINARY_DOCUMENT) }
            signing?.generateOnePassVersion(false)?.encode(encrypted)
            PGPLiteralDataGenerator().open(encrypted, PGPLiteralData.BINARY, "", data.size.toLong(), Date()).use {
                it.write(data)
            }
            signing?.let {
                it.update(data)
                it.generate().encode(encrypted)
            }
        }
        if (outer !== sink) outer.close()
        return sink.toByteArray()
    }

    private fun startSignature(ring: PGPSecretKeyRing, passphrases: Passphrases, type: Int): PGPSignatureGenerator {
        val key = signingKey(ring)
        val private = unlock(key, fingerprintOf(ring), passphrases)
        return PGPSignatureGenerator(
            BcPGPContentSignerBuilder(key.publicKey.algorithm, HashAlgorithmTags.SHA256),
            key.publicKey,
        ).apply {
            init(type, private)
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setIssuerFingerprint(false, key.publicKey)
                    setSignatureCreationTime(false, Date())
                    nameOf(publicOf(ring)).takeIf { it.isNotBlank() }?.let { setSignerUserID(false, it) }
                }.generate(),
            )
        }
    }

    /**
     * A detached signature over [data], armored, for multipart/signed (RFC 3156 section 5).
     *
     * The caller hands over the part exactly as it will travel, lines ending CRLF. The
     * signature is SHA-256, which is what `micalg=pgp-sha256` on the outer part promises.
     */
    fun signDetached(data: ByteArray, signer: PGPSecretKeyRing, passphrases: Passphrases = Passphrases { null }): String {
        val signing = startSignature(signer, passphrases, PGPSignature.BINARY_DOCUMENT)
        signing.update(data)
        return armor(signing.generate().encoded)
    }

    /** The `micalg` parameter for what [signDetached] writes. */
    const val MICALG = "pgp-sha256"

    /**
     * A cleartext signed message (RFC 4880 section 7), for inline PGP.
     *
     * Trailing spaces are dropped from every line before signing, because the format
     * ignores them when verifying and a line that kept them would not verify anywhere.
     */
    fun clearSign(text: String, signer: PGPSecretKeyRing, passphrases: Passphrases = Passphrases { null }): String {
        val lines = text.replace("\r\n", "\n").split('\n').map { it.trimEnd(' ', '\t') }
        val signing = startSignature(signer, passphrases, PGPSignature.CANONICAL_TEXT_DOCUMENT)
        signing.update(lines.joinToString("\r\n").toByteArray(Charsets.UTF_8))
        val signature = armor(signing.generate().encoded).replace("-----BEGIN PGP MESSAGE-----", "-----BEGIN PGP SIGNATURE-----")
            .replace("-----END PGP MESSAGE-----", "-----END PGP SIGNATURE-----")
        return buildString {
            append("-----BEGIN PGP SIGNED MESSAGE-----\r\n")
            append("Hash: SHA256\r\n\r\n")
            lines.forEach { line ->
                // Dash escaping: a line that starts with a dash would otherwise read as armor.
                append(if (line.startsWith("-")) "- $line" else line).append("\r\n")
            }
            append(signature.replace("\r\n", "\n").replace("\n", "\r\n"))
        }
    }

    // ---- reading ----------------------------------------------------------------------

    /**
     * Opens an OpenPGP message: decrypts it when it is encrypted, and checks any signature.
     *
     * [secrets] are the secret key rings this install holds, [publics] every public key it
     * knows (its own included), which is where a signer is looked for. A message encrypted
     * to none of [secrets] stops with a sentence naming the key it wanted, and one without
     * an integrity packet is refused outright rather than shown, because that is the shape
     * an attacker leaves behind after changing ciphertext in flight.
     */
    fun open(
        data: ByteArray,
        secrets: List<PGPSecretKeyRing>,
        publics: List<PGPPublicKeyRing>,
        passphrases: Passphrases = Passphrases { null },
    ): PgpOpened {
        val factory = try {
            BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(data)))
        } catch (e: Exception) {
            throw CryptoFailure("The encrypted part of this message is damaged and cannot be read.")
        }
        val first = nextObject(factory)
        if (first is PGPEncryptedDataList) {
            val candidates = first.encryptedDataObjects.asSequence().filterIsInstance<PGPPublicKeyEncryptedData>().toList()
            if (candidates.isEmpty()) throw CryptoFailure("This message was encrypted with a password rather than a key, which Rampart does not open.")
            var lastFailure: CryptoFailure? = null
            for (candidate in candidates) {
                val (ring, secret) = secrets.firstNotNullOfOrNull { ring ->
                    ring.getSecretKey(candidate.keyID)?.let { ring to it }
                } ?: continue
                val private = try {
                    unlock(secret, fingerprintOf(ring), passphrases)
                } catch (e: CryptoFailure) {
                    lastFailure = e
                    continue
                }
                val clear = try {
                    candidate.getDataStream(BcPublicKeyDataDecryptorFactory(private))
                } catch (e: PGPException) {
                    throw CryptoFailure("This message could not be decrypted with your key: ${e.message ?: "the key did not fit"}.")
                }
                if (!candidate.isIntegrityProtected) {
                    throw CryptoFailure("This message has no integrity check, so Rampart will not show it: it could have been changed on the way.")
                }
                val inner = readInner(BcPGPObjectFactory(clear), publics)
                val intact = try {
                    candidate.verify()
                } catch (e: PGPException) {
                    false
                }
                if (!intact) throw CryptoFailure("This message was changed after it was encrypted, so Rampart will not show it.")
                return inner.copy(encrypted = true)
            }
            lastFailure?.let { throw it }
            val wanted = candidates.joinToString(", ") { "%016X".format(it.keyID) }
            throw CryptoFailure("This message was encrypted to a key Rampart does not have ($wanted), so it cannot be opened here.")
        }
        return readInner(factory, publics, first)
    }

    private fun nextObject(factory: BcPGPObjectFactory): Any? {
        while (true) {
            val next = try {
                factory.nextObject()
            } catch (e: Exception) {
                throw CryptoFailure("The encrypted part of this message is damaged and cannot be read.")
            }
            if (next !is PGPMarker) return next
        }
    }

    /** Reads a literal, perhaps compressed, perhaps signed in one pass, and checks the signature. */
    private fun readInner(factory: BcPGPObjectFactory, publics: List<PGPPublicKeyRing>, already: Any? = null): PgpOpened {
        var next = already ?: nextObject(factory)
        if (next is PGPCompressedData) {
            val decompressed = BcPGPObjectFactory(next.dataStream)
            return readInner(decompressed, publics)
        }
        var onePass: PGPOnePassSignature? = null
        var signer: PGPPublicKeyRing? = null
        if (next is PGPOnePassSignatureList && !next.isEmpty) {
            val ops = next[0]
            onePass = ops
            signer = publics.firstOrNull { it.getPublicKey(ops.keyID) != null }
            signer?.let { ops.init(BcPGPContentVerifierBuilderProvider(), it.getPublicKey(ops.keyID)) }
            next = nextObject(factory)
        }
        if (next !is PGPLiteralData) throw CryptoFailure("This OpenPGP message holds no text Rampart can show.")
        val content = readAll(next.inputStream)
        if (onePass == null) return PgpOpened(content, null, encrypted = false)
        val keyId = "%016X".format(onePass.keyID)
        val signatures = nextObject(factory) as? PGPSignatureList
        val verdict = when {
            signer == null -> SignatureVerdict.UnknownSigner(keyId)
            signatures == null || signatures.isEmpty -> SignatureVerdict.Broken("The signature is missing from the end of the message.")
            else -> {
                onePass.update(content)
                val good = try {
                    onePass.verify(signatures[0])
                } catch (e: PGPException) {
                    false
                }
                if (good) SignatureVerdict.Good(nameOf(signer), fingerprintOf(signer), emailsOf(signer))
                else SignatureVerdict.Broken("The signature does not match the message: it was changed after it was signed.")
            }
        }
        return PgpOpened(content, verdict, encrypted = false)
    }

    /**
     * Checks a detached signature (multipart/signed) against the bytes of the signed part.
     *
     * [data] is canonicalised to CRLF first. A part can reach us with bare line feeds when a
     * server or a file system rewrote them, and the signature was always made over CRLF.
     */
    fun verifyDetached(data: ByteArray, signature: ByteArray, publics: List<PGPPublicKeyRing>): SignatureVerdict {
        val sig = try {
            val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(signature)))
            var found: PGPSignature? = null
            while (found == null) {
                when (val next = factory.nextObject() ?: break) {
                    is PGPSignatureList -> if (!next.isEmpty) found = next[0]
                    is PGPCompressedData -> {
                        val inner = BcPGPObjectFactory(next.dataStream).nextObject()
                        if (inner is PGPSignatureList && !inner.isEmpty) found = inner[0]
                    }
                }
            }
            found
        } catch (e: Exception) {
            null
        } ?: return SignatureVerdict.Broken("The signature attached to this message is damaged and cannot be checked.")
        return check(sig, canonical(data), publics)
    }

    private fun check(sig: PGPSignature, data: ByteArray, publics: List<PGPPublicKeyRing>): SignatureVerdict {
        val keyId = "%016X".format(sig.keyID)
        val signer = publics.firstOrNull { it.getPublicKey(sig.keyID) != null }
            ?: return SignatureVerdict.UnknownSigner(keyId)
        return try {
            sig.init(BcPGPContentVerifierBuilderProvider(), signer.getPublicKey(sig.keyID))
            sig.update(data)
            if (sig.verify()) SignatureVerdict.Good(nameOf(signer), fingerprintOf(signer), emailsOf(signer))
            else SignatureVerdict.Broken("The signature does not match the message: it was changed after it was signed.")
        } catch (e: PGPException) {
            SignatureVerdict.Broken("The signature could not be checked: ${e.message ?: "it is not one Rampart understands"}.")
        }
    }

    /**
     * Reads a cleartext signed block (inline PGP), returning the text and the verdict.
     *
     * The text given back is the text that was signed, with dash escaping undone, and never
     * anything from outside the armor: words above or below a signed block were not signed,
     * and showing them under a "signed" badge would be lending them the signature.
     */
    fun verifyCleartext(block: String, publics: List<PGPPublicKeyRing>): Pair<String, SignatureVerdict> {
        val text = block.replace("\r\n", "\n")
        val begin = text.indexOf("-----BEGIN PGP SIGNED MESSAGE-----")
        val sigStart = text.indexOf("-----BEGIN PGP SIGNATURE-----", begin)
        val sigEnd = text.indexOf("-----END PGP SIGNATURE-----", sigStart)
        if (begin < 0 || sigStart < 0 || sigEnd < 0) {
            return "" to SignatureVerdict.Broken("The signed block in this message is incomplete.")
        }
        val afterHeader = text.indexOf("\n\n", begin)
        if (afterHeader < 0 || afterHeader > sigStart) {
            return "" to SignatureVerdict.Broken("The signed block in this message is incomplete.")
        }
        val body = text.substring(afterHeader + 2, sigStart).removeSuffix("\n")
        val lines = body.split('\n').map { if (it.startsWith("- ")) it.substring(2) else it }
        val signed = lines.joinToString("\r\n") { it.trimEnd(' ', '\t') }
        val armoredSig = text.substring(sigStart, sigEnd + "-----END PGP SIGNATURE-----".length)
        val verdict = verifyDetachedRaw(signed.toByteArray(Charsets.UTF_8), armoredSig.toByteArray(Charsets.US_ASCII), publics)
        return lines.joinToString("\n") to verdict
    }

    private fun verifyDetachedRaw(data: ByteArray, signature: ByteArray, publics: List<PGPPublicKeyRing>): SignatureVerdict {
        val sig = runCatching {
            val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(signature)))
            (factory.nextObject() as? PGPSignatureList)?.takeIf { !it.isEmpty }?.get(0)
        }.getOrNull() ?: return SignatureVerdict.Broken("The signature in this message is damaged and cannot be checked.")
        return check(sig, data, publics)
    }

    /** Line endings made CRLF, without doubling any that already were. */
    fun canonical(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + data.size / 40)
        var previous = 0
        for (b in data) {
            val c = b.toInt()
            if (c == '\n'.code && previous != '\r'.code) out.write('\r'.code)
            out.write(c)
            previous = c
        }
        return out.toByteArray()
    }

    private fun readAll(input: InputStream): ByteArray = input.use { it.readBytes() }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    /** The last sixteen characters of a fingerprint, which is how a key is named in a sentence. */
    fun short(fingerprint: String): String = fingerprint.takeLast(16)
}

/** The address inside a user id such as `Ana Lima <ana@example.org>`, or a bare address. */
internal fun emailInUserId(userId: String): String? {
    val bracketed = Regex("<([^<>\\s]+@[^<>\\s]+)>").find(userId)?.groupValues?.get(1)
    if (bracketed != null) return bracketed
    return userId.trim().takeIf { Regex("^[^\\s@<>]+@[^\\s@<>]+$").matches(it) }
}

/** A passphrase that did not unlock the key. The caller forgets it so it is asked for again. */
internal class WrongPassphrase(val fingerprint: String) :
    CryptoFailure("The passphrase for the key ${Pgp.short(fingerprint)} is not right.")

/** A secret key that needs its passphrase before it can be used. The UI asks, then tries again. */
internal class PassphraseNeeded(val fingerprint: String) :
    CryptoFailure("The key ${Pgp.short(fingerprint)} is protected by a passphrase. Type it to use the key.")
