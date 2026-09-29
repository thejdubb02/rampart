package org.rampart

import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSAlgorithm
import org.bouncycastle.cms.CMSAuthEnvelopedData
import org.bouncycastle.cms.CMSEnvelopedData
import org.bouncycastle.cms.CMSEnvelopedDataGenerator
import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientId
import org.bouncycastle.cms.RecipientInformationStore
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder
import org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.Selector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/*
 * S/MIME, on Bouncy Castle's CMS code.
 *
 * The provider is an instance handed to each builder rather than registered with the JVM,
 * for the same reason Pgp.kt uses the lightweight API: installing a provider changes what
 * every other `getInstance` in the process returns, TLS included, and a mail client's
 * encryption feature has no business doing that.
 *
 * What a certificate proves is decided here, once: it is trusted when it chains to a
 * certificate authority this computer's own trust store accepts, or when the person has
 * said so in the key list (a private CA, a colleague's self-signed certificate). There is
 * no pinning and no built-in list of our own, for the same reason `CLAUDE.md` gives for TLS.
 */

/** A certificate and its private key: what signs and decrypts for one address. */
internal class SmimeIdentity(
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
    val chain: List<X509Certificate> = listOf(certificate),
)

internal object Smime {
    private val provider by lazy { BouncyCastleProvider() }
    private val random = SecureRandom()

    /** The `micalg` for what [signDetached] writes. */
    const val MICALG = "sha-256"

    // ---- certificates ----------------------------------------------------------------

    /**
     * Reads a PKCS#12 file, the form a certificate authority hands out, with its password.
     *
     * The password is used here and not kept: the credential store holds the file as it
     * was given, and the password is asked for again when the key is next needed. That is
     * the passphrase rule in `docs/encryption.md`.
     */
    fun readPkcs12(bytes: ByteArray, password: CharArray): SmimeIdentity {
        val store = KeyStore.getInstance("PKCS12")
        try {
            store.load(ByteArrayInputStream(bytes), password)
        } catch (e: Exception) {
            val wrong = generateSequence(e as Throwable) { it.cause }.any {
                it is java.security.UnrecoverableKeyException || it.message.orEmpty().contains("password", ignoreCase = true)
            }
            throw CryptoFailure(
                if (wrong) "The password for that certificate file is not right."
                else "That is not a certificate file Rampart can read. It should be a .p12 or .pfx file.",
            )
        }
        val alias = store.aliases().asSequence().firstOrNull { store.isKeyEntry(it) }
            ?: throw CryptoFailure("That certificate file holds no private key, so it cannot sign or decrypt.")
        val key = store.getKey(alias, password) as? PrivateKey
            ?: throw CryptoFailure("The private key in that certificate file could not be read.")
        val chain = store.getCertificateChain(alias).orEmpty().filterIsInstance<X509Certificate>()
        val cert = chain.firstOrNull() ?: (store.getCertificate(alias) as? X509Certificate)
            ?: throw CryptoFailure("That certificate file holds no certificate.")
        return SmimeIdentity(cert, key, chain.ifEmpty { listOf(cert) })
    }

    /** A PKCS#12 file holding [identity], protected by [password], for the credential store or an export. */
    fun writePkcs12(identity: SmimeIdentity, password: CharArray): ByteArray {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("rampart", identity.privateKey, password, identity.chain.toTypedArray())
        return ByteArrayOutputStream().also { store.store(it, password) }.toByteArray()
    }

    /**
     * A self-signed certificate, **for testing only**.
     *
     * Nobody else's mail client will trust it, and nobody should: a certificate that
     * vouches for itself proves nothing about the address in it. It is here so the S/MIME
     * path can be tried end to end before paying a certificate authority, and the key list
     * marks it as a test certificate for as long as it exists.
     */
    fun selfSigned(name: String, email: String, days: Long = 365): SmimeIdentity {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072, random) }.generateKeyPair()
        val subject = X500NameBuilder(BCStyle.INSTANCE).apply {
            if (name.isNotBlank()) addRDN(BCStyle.CN, name.trim())
            addRDN(BCStyle.EmailAddress, email.trim())
        }.build()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(128, random),
            Date(now - 60_000),
            Date(now + days * 86_400_000),
            subject,
            pair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_emailProtection))
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.rfc822Name, email.trim())))
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private)
        val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
        return SmimeIdentity(cert, pair.private)
    }

    /** Reads one certificate from PEM or DER. */
    fun readCertificate(bytes: ByteArray): X509Certificate = try {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    } catch (e: Exception) {
        throw CryptoFailure("That is not a certificate Rampart can read.")
    }

    fun pem(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\r\n" + mimeBase64(cert.encoded) + "-----END CERTIFICATE-----\r\n"

    /** SHA-256 of the certificate, upper case hex, which is how the key list names it. */
    fun fingerprintOf(cert: X509Certificate): String = Pgp.hex(MessageDigest.getInstance("SHA-256").digest(cert.encoded))

    /** The addresses a certificate is for: its subject alternative names, else the E= in its subject. */
    fun emailsOf(cert: X509Certificate): List<String> {
        val alt = runCatching {
            cert.subjectAlternativeNames.orEmpty()
                .filter { (it[0] as? Int) == GeneralName.rfc822Name }
                .mapNotNull { it[1] as? String }
        }.getOrDefault(emptyList())
        if (alt.isNotEmpty()) return alt.map { it.lowercase() }.distinct()
        val holder = X509CertificateHolder(cert.encoded)
        return holder.subject.getRDNs(BCStyle.EmailAddress).mapNotNull { it.first?.value?.toString() }.map { it.lowercase() }
    }

    /** The common name, or the address when there is none, for a line in the key list. */
    fun nameOf(cert: X509Certificate): String {
        val holder = X509CertificateHolder(cert.encoded)
        return holder.subject.getRDNs(BCStyle.CN).firstOrNull()?.first?.value?.toString()
            ?: emailsOf(cert).firstOrNull().orEmpty()
    }

    fun selfSigned(cert: X509Certificate): Boolean =
        cert.subjectX500Principal == cert.issuerX500Principal &&
            runCatching { cert.verify(cert.publicKey) }.isSuccess

    // ---- encrypting and signing ------------------------------------------------------

    /**
     * Envelopes [data] for [recipients] with AES-256-CBC, the key carried by RSA key transport.
     *
     * CBC rather than GCM because Outlook and older Apple Mail read CBC and not always the
     * authenticated form, and a message the recipient cannot open protects nobody. RSA only:
     * an elliptic curve certificate needs key agreement, which is listed as not done yet in
     * `docs/encryption.md` rather than half done here.
     */
    fun encrypt(data: ByteArray, recipients: List<X509Certificate>): ByteArray {
        if (recipients.isEmpty()) throw CryptoFailure("There is nobody to encrypt this to.")
        val generator = CMSEnvelopedDataGenerator()
        for (cert in recipients) {
            if (cert.publicKey !is RSAPublicKey) {
                throw CryptoFailure("The certificate for ${emailsOf(cert).firstOrNull() ?: nameOf(cert)} uses a key type Rampart cannot encrypt to yet.")
            }
            generator.addRecipientInfoGenerator(JceKeyTransRecipientInfoGenerator(cert).setProvider(provider))
        }
        val encryptor = JceCMSContentEncryptorBuilder(CMSAlgorithm.AES256_CBC).setProvider(provider).setSecureRandom(random).build()
        return generator.generate(CMSProcessableByteArray(data), encryptor).encoded
    }

    /** A detached CMS signature over [data], carrying the signer's certificate chain. */
    fun signDetached(data: ByteArray, identity: SmimeIdentity): ByteArray {
        val algorithm = if (identity.privateKey.algorithm.equals("RSA", true)) "SHA256withRSA" else "SHA256withECDSA"
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(
            JcaSimpleSignerInfoGeneratorBuilder().setProvider(provider).build(algorithm, identity.privateKey, identity.certificate),
        )
        generator.addCertificates(JcaCertStore(identity.chain))
        return generator.generate(CMSProcessableByteArray(data), false).encoded
    }

    // ---- reading ---------------------------------------------------------------------

    /**
     * Opens enveloped data (or authenticated enveloped data, which is what Stalwart writes for
     * AES-GCM at rest) with whichever of [identities] it was addressed to.
     */
    fun decrypt(der: ByteArray, identities: List<SmimeIdentity>): ByteArray {
        val info = try {
            ContentInfo.getInstance(org.bouncycastle.asn1.ASN1Primitive.fromByteArray(der))
        } catch (e: Exception) {
            throw CryptoFailure("The encrypted part of this message is damaged and cannot be read.")
        }
        val recipients: RecipientInformationStore = try {
            if (info.contentType == PKCSObjectIdentifiers.id_ct_authEnvelopedData) CMSAuthEnvelopedData(info).recipientInfos
            else CMSEnvelopedData(info).recipientInfos
        } catch (e: CMSException) {
            throw CryptoFailure("The encrypted part of this message is not S/MIME Rampart can read.")
        }
        for (identity in identities) {
            val recipient = recipients.get(JceKeyTransRecipientId(identity.certificate)) ?: continue
            return try {
                recipient.getContent(JceKeyTransEnvelopedRecipient(identity.privateKey).setProvider(provider))
            } catch (e: CMSException) {
                throw CryptoFailure("This message could not be decrypted with your certificate: it may have been changed on the way.")
            }
        }
        if (identities.isEmpty()) {
            throw CryptoFailure("This message is encrypted with S/MIME, and Rampart has no S/MIME certificate of yours to open it with.")
        }
        throw CryptoFailure("This message was encrypted to a certificate Rampart does not have, so it cannot be opened here.")
    }

    /** Checks a detached signature (multipart/signed) against the signed part's bytes. */
    fun verifyDetached(data: ByteArray, signature: ByteArray): SignatureVerdict = try {
        verdictOf(CMSSignedData(CMSProcessableByteArray(data), signature))
    } catch (e: CMSException) {
        SignatureVerdict.Broken("The signature attached to this message is damaged and cannot be checked.")
    }

    /** Opens signed data that carries its own content (smime-type=signed-data). */
    fun verifyOpaque(der: ByteArray): Pair<ByteArray, SignatureVerdict> {
        val signed = try {
            CMSSignedData(der)
        } catch (e: CMSException) {
            throw CryptoFailure("The signed part of this message is damaged and cannot be read.")
        }
        val content = (signed.signedContent?.content as? ByteArray)
            ?: throw CryptoFailure("The signed part of this message carries no content.")
        return content to verdictOf(signed)
    }

    private fun verdictOf(signed: CMSSignedData): SignatureVerdict {
        val signer = signed.signerInfos.signers.firstOrNull()
            ?: return SignatureVerdict.Broken("The signature part holds no signature.")
        @Suppress("UNCHECKED_CAST")
        val holder = signed.certificates.getMatches(signer.sid as Selector<X509CertificateHolder>).firstOrNull()
            ?: return SignatureVerdict.UnknownSigner(signer.sid.serialNumber?.toString(16).orEmpty())
        val converter = JcaX509CertificateConverter().setProvider(provider)
        val cert = converter.getCertificate(holder)
        val intact = try {
            signer.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(holder))
        } catch (e: Exception) {
            false
        }
        if (!intact) return SignatureVerdict.Broken("The signature does not match the message: it was changed after it was signed.")
        @Suppress("UNCHECKED_CAST")
        val others = (signed.certificates.getMatches(null) as Collection<X509CertificateHolder>).map { converter.getCertificate(it) }
        return SignatureVerdict.Good(nameOf(cert), fingerprintOf(cert), emailsOf(cert), vouched = chainsToSystem(cert, others), certificatePem = pem(cert))
    }

    /**
     * Whether a certificate authority in this computer's trust store vouches for [cert],
     * given the intermediate certificates the message carried. No revocation check: that
     * would be a network request per message opened, and `docs/encryption.md` says so.
     */
    fun chainsToSystem(cert: X509Certificate, intermediates: List<X509Certificate>): Boolean = runCatching {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val anchors = factory.trustManagers.filterIsInstance<X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }
            .map { TrustAnchor(it, null) }
            .toSet()
        if (anchors.isEmpty()) return@runCatching false
        val certs = CertificateFactory.getInstance("X.509")
        val path = certs.generateCertPath(listOf(cert) + intermediates.filter { it != cert && !selfSigned(it) })
        val params = PKIXParameters(anchors).apply { isRevocationEnabled = false }
        CertPathValidator.getInstance("PKIX").validate(path, params)
        true
    }.getOrDefault(false)
}
