package org.rampart

import jakarta.mail.Session
import java.util.Properties

/*
 * Sending a signed or encrypted message.
 *
 * A protected message cannot go out the ordinary way on JMAP, where the server builds the
 * MIME from an Email object: the signature and the encryption have to cover the exact bytes
 * that travel, so Rampart builds the whole message itself, protects it, and hands the
 * finished bytes over ([MailBackend.sendRaw]). On IMAP that is what already happens.
 *
 * **The one outcome this file exists to prevent is a message going out readable after
 * somebody asked for it to be encrypted.** Every check below throws a [CryptoFailure] with a
 * sentence, and nothing catches one to try again without encryption. The ordinary send path
 * refuses a draft marked for signing or encryption outright ([refusedOption]), so a caller
 * that forgot to route a protected draft here fails loudly instead of sending in the clear.
 */

/** What the composer and the send path agree on before anything is built. */
internal data class SealPlan(
    val kind: KeyKind,
    val scheme: SealScheme,
    /** The sender's own key, which signs and which the Sent copy is encrypted to. */
    val own: KeyEntry,
    /** A key for every recipient, in the order written. Empty when only signing. */
    val recipients: List<KeyEntry>,
)

/**
 * Works out how [from] can protect a message to [recipients], or throws the sentence that
 * says why not. Pure over the key list, so the composer asks the same question live that the
 * send path asks at the end.
 */
internal fun planSeal(keyring: Keyring, from: String, recipients: List<String>, sign: Boolean, encrypt: Boolean): SealPlan {
    if (!sign && !encrypt) throw CryptoFailure("Neither signing nor encryption is on for this message.")
    val own = keyring.ownKey(from)
        ?: throw CryptoFailure(
            if (encrypt) "There is no key of yours for $from, and one is needed to keep a readable copy in Sent. Make or import one in Settings, Encryption."
            else "There is no key of yours for $from to sign with. Make or import one in Settings, Encryption.",
        )
    val kind = own.kind
    val scheme = when {
        kind == KeyKind.SMIME -> SealScheme.SMIME
        keyring.index().inlinePgp -> SealScheme.PGP_INLINE
        else -> SealScheme.PGP_MIME
    }
    if (!encrypt) return SealPlan(kind, scheme, own, emptyList())
    val missing = missingKeys(recipients) { keyring.publicFor(it, kind) != null }
    missingKeySentence(missing)?.let { throw CryptoFailure(it) }
    val keys = recipients.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        .map { keyring.publicFor(it, kind)!! }
    return SealPlan(kind, scheme, own, keys)
}

/** The finished bytes for [plan], from a message built the ordinary way. */
internal fun sealWith(
    keyring: Keyring,
    plan: SealPlan,
    plain: jakarta.mail.internet.MimeMessage,
    sign: Boolean,
    encrypt: Boolean,
    plainText: String?,
): ByteArray {
    val signer: SignWith? = if (!sign) null else when (plan.kind) {
        KeyKind.OPENPGP -> SignWith.OpenPgp(keyring.pgpSecretRing(plan.own.fingerprint), keyring.passphrases)
        KeyKind.SMIME -> SignWith.SMime(keyring.smimeIdentity(plan.own.fingerprint))
    }
    val to: EncryptTo? = if (!encrypt) null else when (plan.kind) {
        // The sender's own key is always added, so the copy in Sent can be read afterwards.
        KeyKind.OPENPGP -> EncryptTo.OpenPgp((plan.recipients + plan.own).distinctBy { it.fingerprint }.map(keyring::pgpPublicRing))
        KeyKind.SMIME -> EncryptTo.SMime(
            (plan.recipients + plan.own).distinctBy { it.fingerprint }.map { Smime.readCertificate(it.publicKey.toByteArray()) },
        )
    }
    return sealedMessage(plain, plan.scheme, signer, to, plainText)
}

/** The most one attachment may be when it has to be fetched back to be encrypted. */
private const val SEALED_FILE_LIMIT = 25L * 1024 * 1024

/**
 * Signs and or encrypts [draft] and sends it through [backend]. Returns the same notice as
 * [MailBackend.send]: null when the Sent copy landed, a sentence when only the send did.
 */
internal fun sendSealed(
    backend: MailBackend,
    keyring: Keyring,
    draft: Draft,
    identity: Identity,
    draftsMailboxId: String,
    sentMailboxId: String?,
): String? {
    val plan = planSeal(keyring, identity.email, draft.recipients, draft.sign, draft.encrypt)
    // Files already sit on the server as blobs. They are fetched back so they can go inside
    // the encryption; one that cannot be fetched stops the send rather than being left out.
    val files = draft.attachments.map { file ->
        val bytes = backend.blob(file, SEALED_FILE_LIMIT)
            ?: throw CryptoFailure("${file.name} could not be fetched to be encrypted with the message, so nothing was sent.")
        Outgoing(file.name, file.type, bytes, file.cid, file.inline)
    }
    val plain = buildMessage(Session.getInstance(Properties()), draft.copy(attachments = emptyList()), identity, files)
    val raw = try {
        sealWith(keyring, plan, plain, draft.sign, draft.encrypt, markupToPlain(draft.body))
    } catch (e: WrongPassphrase) {
        // Forgotten, so the composer offers the box again rather than failing the same way twice.
        keyring.forgetPassphrase(e.fingerprint)
        throw e
    }
    return backend.sendRaw(raw, draft, identity, draftsMailboxId, sentMailboxId)
}
