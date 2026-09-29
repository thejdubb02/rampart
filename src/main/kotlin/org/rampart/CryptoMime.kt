package org.rampart

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64

/*
 * Encrypted and signed mail at the MIME level: recognising it, opening it, and building it.
 *
 * The formats, and why each is here:
 *
 * - PGP/MIME (RFC 3156): multipart/encrypted with an application/pgp-encrypted version part,
 *   and multipart/signed with an application/pgp-signature part. What Thunderbird, Proton
 *   and GnuPG-based clients send, and what Stalwart's own encryption at rest writes.
 * - Inline PGP: an armored block in a text/plain body. Old, still common, and the only thing
 *   some webmail plugins produce, so it is read. It is only written on request, because it
 *   cannot carry HTML or attachments.
 * - S/MIME (RFC 8551): application/pkcs7-mime enveloped or signed data, and multipart/signed
 *   with an application/pkcs7-signature part. What Outlook and Apple Mail send.
 *
 * **A signed part is checked against its bytes exactly as they arrived.** Parsing it with a
 * MIME library and writing it back out is how a signature that is fine gets reported as
 * broken: header folding and line endings come back differently. So the signed part is cut
 * out of the raw message at its boundary lines, which is what the signer signed.
 *
 * **What comes out of here is a text and an HTML string, nothing else.** The reader draws
 * them through the same cleaner as any other message (EmailDocument.kt), because decrypting
 * something does not make it safe. An attacker can encrypt to your public key as easily as
 * anyone else can.
 */

/** A crypto problem, as the one sentence a person is shown. */
internal open class CryptoFailure(message: String) : Exception(message)

/** What checking a signature concluded. Trust is decided by the caller, which knows the keys. */
internal sealed interface SignatureVerdict {
    /** The signature is intact. [emails] are what the signing key or certificate names. */
    data class Good(
        val signer: String,
        val fingerprint: String,
        val emails: List<String>,
        /** For S/MIME: a certificate authority this computer trusts vouches for the signer. */
        val vouched: Boolean = false,
        /** For S/MIME: the signer's certificate as PEM, so it can be kept for writing back. */
        val certificatePem: String? = null,
    ) : SignatureVerdict

    /** The signature is there and does not match. The message was changed, or forged. */
    data class Broken(val reason: String) : SignatureVerdict

    /** Signed by a key Rampart does not have, so nothing could be checked. */
    data class UnknownSigner(val keyId: String) : SignatureVerdict
}

/** The kinds of protected message there are, as far as reading one goes. */
internal enum class Seal {
    NONE,
    PGP_ENCRYPTED,
    PGP_SIGNED,
    PGP_INLINE_ENCRYPTED,
    PGP_INLINE_SIGNED,
    SMIME_ENCRYPTED,
    SMIME_SIGNED,
    SMIME_OPAQUE_SIGNED,
    ;

    val encrypted: Boolean get() = this == PGP_ENCRYPTED || this == PGP_INLINE_ENCRYPTED || this == SMIME_ENCRYPTED
    val scheme: String get() = if (name.startsWith("SMIME")) "S/MIME" else "OpenPGP"
}

/** A file that was inside the encryption. Named here, offered by the reader. */
internal data class SealedFile(val name: String, val type: String, val bytes: ByteArray)

/** A protected message, opened. */
internal data class OpenedSeal(
    val seal: Seal,
    val encrypted: Boolean,
    val signature: SignatureVerdict?,
    val text: String?,
    val html: String?,
    val files: List<SealedFile> = emptyList(),
    /**
     * For inline PGP: words the message carried outside the armored block. They were not
     * encrypted and not signed, so the reader shows them apart and says so.
     */
    val outside: String? = null,
    /**
     * The protected part was found inside an ordinary multipart, usually because a mailing
     * list wrapped a signed message to add its footer. What sits around it is not protected.
     */
    val partial: Boolean = false,
)

/** Thrown when a message turns out to carry no protection at all. The reader then shows nothing. */
internal class NotSealed : CryptoFailure("This message is not encrypted or signed.")

/**
 * Every key the reader may use. An interface so the tests hand over keys made a moment ago
 * and the application hands over its key store.
 */
internal interface SealKeys {
    fun pgpSecrets(): List<PGPSecretKeyRing>
    fun pgpPublics(): List<PGPPublicKeyRing>
    fun smimeIdentities(): List<SmimeIdentity>
    val passphrases: Passphrases get() = Passphrases { null }
}

// ---- recognising -------------------------------------------------------------------------

/** The header block and the body of one MIME entity, split at the first empty line. */
internal class RawEntity(val headers: ByteArray, val body: ByteArray) {
    private val parsed: List<Pair<String, String>> by lazy { parseHeaders(String(headers, Charsets.ISO_8859_1)) }

    fun header(name: String): String? = parsed.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    val contentType: ContentType? by lazy {
        runCatching { ContentType(header("Content-Type") ?: "text/plain; charset=us-ascii") }.getOrNull()
    }

    /** The whole entity as it arrived, headers and all. */
    val whole: ByteArray get() = headers + body

    /** The body with its transfer encoding undone. */
    fun decoded(): ByteArray {
        val encoding = header("Content-Transfer-Encoding")?.trim()?.lowercase() ?: return body
        if (encoding == "7bit" || encoding == "8bit" || encoding == "binary") return body
        return runCatching { MimeUtility.decode(ByteArrayInputStream(body), encoding).readBytes() }
            .getOrElse { throw CryptoFailure("A part of this message is encoded in a way Rampart cannot read.") }
    }

    companion object {
        /** Splits [bytes] at the first blank line, CRLF or bare LF. The headers keep that blank line. */
        fun of(bytes: ByteArray): RawEntity {
            for (i in bytes.indices) {
                if (bytes[i] != '\n'.code.toByte()) continue
                if (i + 1 < bytes.size && bytes[i + 1] == '\n'.code.toByte()) return RawEntity(bytes.copyOfRange(0, i + 2), bytes.copyOfRange(i + 2, bytes.size))
                if (i + 2 < bytes.size && bytes[i + 1] == '\r'.code.toByte() && bytes[i + 2] == '\n'.code.toByte()) {
                    return RawEntity(bytes.copyOfRange(0, i + 3), bytes.copyOfRange(i + 3, bytes.size))
                }
            }
            return RawEntity(bytes, ByteArray(0))
        }

        private fun parseHeaders(text: String): List<Pair<String, String>> {
            val unfolded = text.replace(Regex("\r?\n[ \t]+"), " ")
            return unfolded.lines().mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
        }
    }
}

/**
 * The parts of a multipart body, each exactly as it arrived (headers and body), without the
 * line break that belongs to the boundary after it. RFC 2046 section 5.1.1: that CRLF is part
 * of the delimiter, not of the part, and including it is the classic way to fail a signature.
 */
internal fun multipartParts(body: ByteArray, boundary: String): List<ByteArray> {
    val text = String(body, Charsets.ISO_8859_1)
    val delimiter = "--$boundary"
    val starts = mutableListOf<Int>()
    var at = 0
    while (true) {
        val found = text.indexOf(delimiter, at)
        if (found < 0) break
        val lineStart = found == 0 || text[found - 1] == '\n'
        if (lineStart) starts += found
        at = found + delimiter.length
    }
    val parts = mutableListOf<ByteArray>()
    for (i in 0 until starts.size - 1) {
        val lineEnd = text.indexOf('\n', starts[i])
        if (lineEnd < 0) break
        if (text.startsWith("$delimiter--", starts[i])) break
        var end = starts[i + 1]
        if (end > 0 && text[end - 1] == '\n') end--
        if (end > 0 && text[end - 1] == '\r') end--
        val from = lineEnd + 1
        if (from <= end) parts += body.copyOfRange(from, end)
    }
    return parts
}

private const val PGP_MESSAGE = "-----BEGIN PGP MESSAGE-----"
private const val PGP_SIGNED = "-----BEGIN PGP SIGNED MESSAGE-----"

/** What kind of protection the top of [raw] carries, or [Seal.NONE]. */
internal fun sealOf(raw: ByteArray): Seal {
    val entity = RawEntity.of(raw)
    val type = entity.contentType ?: return Seal.NONE
    return sealOfType(type) ?: run {
        if (type.match("text/plain")) {
            val text = runCatching { String(entity.decoded(), charsetOf(type)) }.getOrDefault("")
            inlineSeal(text)
        } else if (type.primaryType.equals("multipart", true)) {
            // A plain text part at the top of a mixed message is where inline PGP usually is.
            val boundary = type.getParameter("boundary") ?: return Seal.NONE
            multipartParts(entity.body, boundary).firstOrNull()?.let { first ->
                val part = RawEntity.of(first)
                val partType = part.contentType
                if (partType != null && partType.match("text/plain")) {
                    inlineSeal(runCatching { String(part.decoded(), charsetOf(partType)) }.getOrDefault(""))
                } else {
                    Seal.NONE
                }
            } ?: Seal.NONE
        } else {
            Seal.NONE
        }
    }
}

private fun sealOfType(type: ContentType): Seal? = when {
    type.match("multipart/encrypted") &&
        type.getParameter("protocol").orEmpty().equals("application/pgp-encrypted", true) -> Seal.PGP_ENCRYPTED
    type.match("multipart/signed") &&
        type.getParameter("protocol").orEmpty().equals("application/pgp-signature", true) -> Seal.PGP_SIGNED
    type.match("multipart/signed") && type.getParameter("protocol").orEmpty().lowercase()
        .let { it == "application/pkcs7-signature" || it == "application/x-pkcs7-signature" } -> Seal.SMIME_SIGNED
    type.match("application/pkcs7-mime") || type.match("application/x-pkcs7-mime") ->
        when (type.getParameter("smime-type")?.lowercase()) {
            "signed-data" -> Seal.SMIME_OPAQUE_SIGNED
            // Absent is read as enveloped: it is what old Outlook leaves off, and a signed
            // blob misread as enveloped fails with a sentence rather than showing anything.
            else -> Seal.SMIME_ENCRYPTED
        }
    else -> null
}

private fun inlineSeal(text: String): Seal = when {
    text.contains(PGP_MESSAGE) -> Seal.PGP_INLINE_ENCRYPTED
    text.contains(PGP_SIGNED) -> Seal.PGP_INLINE_SIGNED
    else -> Seal.NONE
}

/**
 * Whether a message's attachment list alone says it is encrypted. The list is what the
 * reader has before it fetches anything, which is enough to show the badge straight away.
 */
internal fun sealedByParts(types: List<String>, names: List<String>): Boolean =
    types.any { t ->
        val lower = t.lowercase()
        lower.startsWith("application/pgp-encrypted") || lower.startsWith("application/pkcs7-mime") ||
            lower.startsWith("application/x-pkcs7-mime")
    } || names.any { it.equals("encrypted.asc", true) || it.equals("smime.p7m", true) }

/**
 * Whether a message is worth fetching whole to check: encrypted by its parts, signed by
 * its parts (a detached signature arrives as an attachment), or an armored block in its text.
 */
internal fun mayBeSealed(types: List<String>, names: List<String>, text: String?): Boolean =
    sealedByParts(types, names) ||
        types.any { t ->
            val lower = t.lowercase()
            lower.startsWith("application/pgp-signature") || lower.startsWith("application/pkcs7-signature") ||
                lower.startsWith("application/x-pkcs7-signature")
        } ||
        names.any { it.equals("smime.p7s", true) } ||
        text.orEmpty().let { it.contains(PGP_MESSAGE) || it.contains(PGP_SIGNED) }

/**
 * The OpenPGP keys a message carries for its sender: an Autocrypt header naming the From
 * address, and any application/pgp-keys part at the top level or among [files] found inside
 * the encryption. Nothing here is trusted; the reader offers to keep them, untrusted.
 */
internal fun keysCarried(raw: ByteArray, fromEmail: String, files: List<SealedFile> = emptyList()): List<ByteArray> {
    val entity = RawEntity.of(raw)
    val found = mutableListOf<ByteArray>()
    entity.header("Autocrypt")?.let { KeyLookup.autocryptKey(it, fromEmail) }?.let(found::add)
    val type = entity.contentType
    if (type != null && type.primaryType.equals("multipart", true)) {
        type.getParameter("boundary")?.let { boundary ->
            multipartParts(entity.body, boundary).map(RawEntity::of)
                .filter { it.contentType?.match("application/pgp-keys") == true }
                .mapNotNull { runCatching { it.decoded() }.getOrNull() }
                .forEach(found::add)
        }
    }
    files.filter { it.type.equals("application/pgp-keys", true) }.forEach { found += it.bytes }
    return found
}

private fun charsetOf(type: ContentType): java.nio.charset.Charset =
    runCatching { charset(MimeUtility.javaCharset(type.getParameter("charset") ?: "utf-8")) }.getOrDefault(Charsets.UTF_8)

// ---- opening -----------------------------------------------------------------------------

/** How deep a protected message may nest inside another before Rampart stops. */
private const val MAX_DEPTH = 4

/**
 * Opens [raw], a whole message as it arrived: decrypts it, checks any signature, and hands
 * back text and HTML for the reader. Throws [CryptoFailure] with a sentence when it cannot.
 */
internal fun openSealed(raw: ByteArray, keys: SealKeys): OpenedSeal {
    val entity = RawEntity.of(raw)
    if (sealOf(raw) != Seal.NONE) return openEntity(entity, keys, 0, encrypted = false, signature = null)
    val nested = nestedSealed(entity, 0) ?: throw NotSealed()
    return openEntity(nested, keys, 0, encrypted = false, signature = null).copy(partial = true)
}

/** The first protected entity inside an ordinary multipart, looked for a few levels down. */
private fun nestedSealed(entity: RawEntity, depth: Int): RawEntity? {
    if (depth > MAX_DEPTH) return null
    val type = entity.contentType ?: return null
    if (!type.primaryType.equals("multipart", true)) return null
    val boundary = type.getParameter("boundary") ?: return null
    for (part in multipartParts(entity.body, boundary).map(RawEntity::of)) {
        val partType = part.contentType ?: continue
        if (sealOfType(partType) != null) return part
        nestedSealed(part, depth + 1)?.let { return it }
    }
    return null
}

private fun openEntity(
    entity: RawEntity,
    keys: SealKeys,
    depth: Int,
    encrypted: Boolean,
    signature: SignatureVerdict?,
): OpenedSeal {
    if (depth > MAX_DEPTH) throw CryptoFailure("This message is wrapped in too many layers of encryption to open.")
    val type = entity.contentType ?: return shown(entity, Seal.NONE, encrypted, signature)
    val seal = sealOfType(type)
    return when (seal) {
        Seal.PGP_ENCRYPTED -> {
            val parts = multipartParts(entity.body, type.getParameter("boundary") ?: "")
            val payload = parts.getOrNull(1)?.let { RawEntity.of(it).decoded() }
                ?: throw CryptoFailure("The encrypted part of this message is missing.")
            val opened = Pgp.open(payload, keys.pgpSecrets(), keys.pgpPublics(), keys.passphrases)
            openEntity(RawEntity.of(opened.content), keys, depth + 1, encrypted = true, signature = opened.signature ?: signature)
                .copy(seal = Seal.PGP_ENCRYPTED)
        }
        Seal.PGP_SIGNED, Seal.SMIME_SIGNED -> {
            val parts = multipartParts(entity.body, type.getParameter("boundary") ?: "")
            if (parts.size < 2) throw CryptoFailure("The signature part of this message is missing.")
            val signedBytes = parts[0]
            val signatureBytes = RawEntity.of(parts[1]).decoded()
            val verdict = if (seal == Seal.PGP_SIGNED) {
                Pgp.verifyDetached(signedBytes, signatureBytes, keys.pgpPublics())
            } else {
                Smime.verifyDetached(Pgp.canonical(signedBytes), signatureBytes)
            }
            openEntity(RawEntity.of(signedBytes), keys, depth + 1, encrypted, signature ?: verdict).copy(seal = if (encrypted) outerSeal(seal) else seal)
        }
        Seal.SMIME_ENCRYPTED -> {
            val der = entity.decoded()
            val content = Smime.decrypt(der, keys.smimeIdentities())
            openEntity(RawEntity.of(content), keys, depth + 1, encrypted = true, signature = signature)
                .copy(seal = Seal.SMIME_ENCRYPTED)
        }
        Seal.SMIME_OPAQUE_SIGNED -> {
            val (content, verdict) = Smime.verifyOpaque(entity.decoded())
            openEntity(RawEntity.of(content), keys, depth + 1, encrypted, signature ?: verdict)
                .copy(seal = if (encrypted) Seal.SMIME_ENCRYPTED else Seal.SMIME_OPAQUE_SIGNED)
        }
        else -> shown(entity, if (encrypted) Seal.PGP_ENCRYPTED else Seal.NONE, encrypted, signature, keys)
    }
}

private fun outerSeal(inner: Seal): Seal = if (inner == Seal.SMIME_SIGNED) Seal.SMIME_ENCRYPTED else Seal.PGP_ENCRYPTED

/**
 * The readable content of an entity that is not itself protected: the first text and the
 * first HTML part, and every other leaf as a file. Inline PGP inside it is opened here too.
 */
private fun shown(entity: RawEntity, seal: Seal, encrypted: Boolean, signature: SignatureVerdict?, keys: SealKeys? = null): OpenedSeal {
    val part = try {
        MimeBodyPart(ByteArrayInputStream(entity.whole))
    } catch (e: Exception) {
        throw CryptoFailure("The decrypted message is not in a form Rampart can read.")
    }
    var text: String? = null
    var html: String? = null
    val files = mutableListOf<SealedFile>()
    fun walk(p: Part, depth: Int) {
        if (depth > 12) return
        val disposition = runCatching { p.disposition }.getOrNull()
        when {
            p.isMimeType("multipart/*") -> {
                val multi = p.content as Multipart
                for (i in 0 until multi.count) walk(multi.getBodyPart(i), depth + 1)
            }
            p.isMimeType("text/plain") && text == null && !Part.ATTACHMENT.equals(disposition, true) ->
                text = p.content?.toString()
            p.isMimeType("text/html") && html == null && !Part.ATTACHMENT.equals(disposition, true) ->
                html = p.content?.toString()
            else -> {
                val bytes = runCatching { p.inputStream.use { it.readBytes() } }.getOrNull() ?: return
                files += SealedFile(p.fileName ?: "attachment", p.contentType.substringBefore(';').trim(), bytes)
            }
        }
    }
    walk(part, 0)
    // Inline PGP: the text part carries an armored block rather than the message itself.
    val body = text
    if (keys != null && body != null && (body.contains(PGP_MESSAGE) || body.contains(PGP_SIGNED))) {
        return openInline(body, keys, encrypted, signature).copy(html = null, files = files)
    }
    return OpenedSeal(seal, encrypted, signature, text, html, files)
}

/** Opens the armored block in an inline PGP text, and keeps what was outside it apart. */
private fun openInline(body: String, keys: SealKeys, wasEncrypted: Boolean, signature: SignatureVerdict?): OpenedSeal {
    val text = body.replace("\r\n", "\n")
    if (text.contains(PGP_MESSAGE)) {
        val start = text.indexOf(PGP_MESSAGE)
        val endMarker = "-----END PGP MESSAGE-----"
        val end = text.indexOf(endMarker, start).takeIf { it >= 0 }?.plus(endMarker.length)
            ?: throw CryptoFailure("The encrypted block in this message is cut short.")
        val block = text.substring(start, end)
        val outside = (text.substring(0, start) + text.substring(end)).trim().ifBlank { null }
        val opened = Pgp.open(block.toByteArray(Charsets.US_ASCII), keys.pgpSecrets(), keys.pgpPublics(), keys.passphrases)
        var inner = String(opened.content, Charsets.UTF_8)
        var verdict = opened.signature ?: signature
        // A block that was signed in clear and then encrypted: check the inner signature too.
        if (inner.contains(PGP_SIGNED)) {
            val (clear, innerVerdict) = Pgp.verifyCleartext(inner, keys.pgpPublics())
            inner = clear
            verdict = verdict ?: innerVerdict
        }
        return OpenedSeal(Seal.PGP_INLINE_ENCRYPTED, true, verdict, inner, null, outside = outside)
    }
    val start = text.indexOf(PGP_SIGNED)
    val endMarker = "-----END PGP SIGNATURE-----"
    val end = text.indexOf(endMarker, start).takeIf { it >= 0 }?.plus(endMarker.length) ?: text.length
    val (clear, verdict) = Pgp.verifyCleartext(text.substring(start, end), keys.pgpPublics())
    val outside = (text.substring(0, start) + text.substring(end)).trim().ifBlank { null }
    return OpenedSeal(Seal.PGP_INLINE_SIGNED, wasEncrypted, signature ?: verdict, clear, null, outside = outside)
}

// ---- the badge ---------------------------------------------------------------------------

/**
 * The line the reader shows above a protected message, and whether it is an alarm.
 *
 * A signature is trusted when the key or certificate is one this person has (made,
 * imported, or taken from their own address book or a verified directory) or, for S/MIME,
 * one a certificate authority vouches for, **and** it names the address the message says it
 * is from. A good signature by a stranger's key, or by a key for some other address, proves
 * only that somebody signed it, and saying "signed" in green over that would be the lie
 * this badge exists to prevent. Those are shown in red with the reason.
 */
internal data class SealLine(val text: String, val alarm: Boolean)

internal fun sealLines(
    opened: OpenedSeal,
    fromEmail: String,
    trustedFingerprint: (String) -> Boolean,
): List<SealLine> {
    val lines = mutableListOf<SealLine>()
    if (opened.encrypted) {
        lines += SealLine("Encrypted with ${opened.seal.scheme}. Only you and the people it was sent to can read it.", false)
    }
    when (val v = opened.signature) {
        null -> if (opened.encrypted) {
            lines += SealLine("Not signed, so anybody with your public key could have written it.", false)
        }
        is SignatureVerdict.Broken -> lines += SealLine("Signature broken: ${v.reason.removeSuffix(".")}.", true)
        is SignatureVerdict.UnknownSigner -> lines += SealLine(
            "Signed by a key Rampart does not have (${v.keyId}), so the signature could not be checked.",
            true,
        )
        is SignatureVerdict.Good -> {
            val known = v.vouched || trustedFingerprint(v.fingerprint)
            val matches = v.emails.any { it.equals(fromEmail.trim(), ignoreCase = true) }
            val who = v.signer.ifBlank { v.emails.firstOrNull().orEmpty() }
            lines += when {
                !matches -> SealLine("Signed by $who, which is not the address this message says it is from.", true)
                !known -> SealLine("Signed by $who, but with a key you have not told Rampart to trust.", true)
                else -> SealLine("Signed by $who. The signature checks out.", false)
            }
        }
    }
    opened.outside?.let {
        lines += SealLine("Some words in this message sit outside the protected part and are shown apart from it.", true)
    }
    if (opened.partial) {
        lines += SealLine("Only part of this message is protected. The rest, often a mailing list's footer, is not covered by it.", true)
    }
    return lines
}

// ---- building ----------------------------------------------------------------------------

/** How an outgoing message is protected. */
internal enum class SealScheme { PGP_MIME, PGP_INLINE, SMIME }

/** Who signs: one of the two kinds of secret key. */
internal sealed interface SignWith {
    data class OpenPgp(val ring: PGPSecretKeyRing, val passphrases: Passphrases = Passphrases { null }) : SignWith
    data class SMime(val identity: SmimeIdentity) : SignWith
}

/** Who it is encrypted to, the sender's own key always among them. */
internal sealed interface EncryptTo {
    data class OpenPgp(val rings: List<PGPPublicKeyRing>) : EncryptTo
    data class SMime(val certificates: List<X509Certificate>) : EncryptTo
}

private val boundaryRandom = SecureRandom()

private fun boundary(): String {
    val bytes = ByteArray(18).also(boundaryRandom::nextBytes)
    return "=_rampart_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Base64 in 76 character lines with CRLF, as MIME wants it. */
internal fun mimeBase64(bytes: ByteArray): String =
    Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(bytes) + "\r\n"

/** A message split into the headers that stay outside and the MIME entity that gets protected. */
internal data class Splittable(val outer: List<String>, val entity: ByteArray)

/**
 * Splits a finished message into its outer headers (From, To, Subject, Date, Message-ID and
 * the rest) and its content, as one MIME entity with only the Content- headers.
 *
 * The subject stays outside. That is how PGP/MIME and S/MIME work everywhere, and why the
 * composer says so when encryption is on: the body is hidden and the subject line is not.
 */
internal fun splitForSealing(message: MimeMessage): Splittable {
    val out = ByteArrayOutputStream()
    message.writeTo(out)
    val entity = RawEntity.of(Pgp.canonical(out.toByteArray()))
    val lines = String(entity.headers, Charsets.ISO_8859_1).replace("\r\n", "\n").trimEnd('\n').split('\n')
    val outer = mutableListOf<String>()
    val inner = StringBuilder()
    var current: StringBuilder? = null
    var currentIsContent = false
    fun flush() {
        val header = current?.toString() ?: return
        if (currentIsContent) inner.append(header).append("\r\n")
        else if (!header.startsWith("MIME-Version:", true)) outer += header
        current = null
    }
    for (line in lines) {
        if (line.startsWith(" ") || line.startsWith("\t")) {
            current?.append("\r\n")?.append(line)
            continue
        }
        flush()
        currentIsContent = line.startsWith("Content-", true)
        current = StringBuilder(line)
    }
    flush()
    inner.append("\r\n")
    return Splittable(outer, inner.toString().toByteArray(Charsets.ISO_8859_1) + entity.body)
}

/** A finished message: [outer] headers, a MIME-Version, then [contentHeaders] and [body]. */
internal fun assemble(outer: List<String>, contentHeaders: List<String>, body: ByteArray): ByteArray {
    val head = StringBuilder()
    outer.forEach { head.append(it).append("\r\n") }
    head.append("MIME-Version: 1.0\r\n")
    contentHeaders.forEach { head.append(it).append("\r\n") }
    head.append("\r\n")
    return head.toString().toByteArray(Charsets.ISO_8859_1) + body
}

/** An entity's headers and body, as the bytes of one MIME entity. */
private fun entityOf(contentHeaders: List<String>, body: ByteArray): ByteArray =
    (contentHeaders.joinToString("") { "$it\r\n" } + "\r\n").toByteArray(Charsets.ISO_8859_1) + body

/** multipart/signed with a PGP signature (RFC 3156 section 5). Returns the content headers and body. */
internal fun pgpSigned(entity: ByteArray, signer: SignWith.OpenPgp): Pair<List<String>, ByteArray> {
    val canonical = Pgp.canonical(entity)
    val signature = Pgp.signDetached(canonical, signer.ring, signer.passphrases)
    val b = boundary()
    val body = StringBuilder()
        .append("This is an OpenPGP/MIME signed message (RFC 4880 and 3156).\r\n")
        .append("--").append(b).append("\r\n")
        .toString().toByteArray(Charsets.ISO_8859_1) +
        canonical +
        ("\r\n--$b\r\n" +
            "Content-Type: application/pgp-signature; name=\"signature.asc\"\r\n" +
            "Content-Description: OpenPGP digital signature\r\n" +
            "Content-Disposition: attachment; filename=\"signature.asc\"\r\n\r\n" +
            signature.replace("\r\n", "\n").replace("\n", "\r\n") +
            "\r\n--$b--\r\n").toByteArray(Charsets.ISO_8859_1)
    val headers = listOf(
        "Content-Type: multipart/signed; micalg=${Pgp.MICALG};\r\n\tprotocol=\"application/pgp-signature\";\r\n\tboundary=\"$b\"",
    )
    return headers to body
}

/** multipart/encrypted (RFC 3156 section 4), signed inside when [signer] is set. */
internal fun pgpEncrypted(entity: ByteArray, to: EncryptTo.OpenPgp, signer: SignWith.OpenPgp?): Pair<List<String>, ByteArray> {
    val armored = Pgp.encrypt(Pgp.canonical(entity), to.rings, signer?.ring, signer?.passphrases ?: Passphrases { null })
    val b = boundary()
    val body = ("This is an OpenPGP/MIME encrypted message (RFC 4880 and 3156).\r\n" +
        "--$b\r\n" +
        "Content-Type: application/pgp-encrypted\r\n" +
        "Content-Description: PGP/MIME version identification\r\n\r\n" +
        "Version: 1\r\n\r\n" +
        "--$b\r\n" +
        "Content-Type: application/octet-stream; name=\"encrypted.asc\"\r\n" +
        "Content-Description: OpenPGP encrypted message\r\n" +
        "Content-Disposition: inline; filename=\"encrypted.asc\"\r\n\r\n" +
        String(armored, Charsets.US_ASCII).replace("\r\n", "\n").replace("\n", "\r\n") +
        "\r\n--$b--\r\n").toByteArray(Charsets.ISO_8859_1)
    return listOf("Content-Type: multipart/encrypted;\r\n\tprotocol=\"application/pgp-encrypted\";\r\n\tboundary=\"$b\"") to body
}

/**
 * Inline PGP: the text alone, as one text/plain part. Refuses a message with files or
 * formatting it would have to drop, because leaving an attachment out of the encryption
 * would be sending part of the message in the clear.
 */
internal fun pgpInline(text: String, to: EncryptTo.OpenPgp?, signer: SignWith.OpenPgp?): Pair<List<String>, ByteArray> {
    val content = when {
        to != null -> String(
            Pgp.encrypt(text.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8), to.rings, signer?.ring, signer?.passphrases ?: Passphrases { null }),
            Charsets.US_ASCII,
        )
        signer != null -> Pgp.clearSign(text, signer.ring, signer.passphrases)
        else -> text
    }
    val sink = ByteArrayOutputStream()
    MimeUtility.encode(sink, "quoted-printable").use {
        it.write(content.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
    }
    return listOf("Content-Type: text/plain; charset=utf-8", "Content-Transfer-Encoding: quoted-printable") to sink.toByteArray()
}

/** multipart/signed with a CMS signature (RFC 8551 section 3.5). */
internal fun smimeSigned(entity: ByteArray, signer: SignWith.SMime): Pair<List<String>, ByteArray> {
    val canonical = Pgp.canonical(entity)
    val signature = Smime.signDetached(canonical, signer.identity)
    val b = boundary()
    val body = ("This is a cryptographically signed message in MIME format.\r\n" +
        "--$b\r\n").toByteArray(Charsets.ISO_8859_1) +
        canonical +
        ("\r\n--$b\r\n" +
            "Content-Type: application/pkcs7-signature; name=\"smime.p7s\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "Content-Disposition: attachment; filename=\"smime.p7s\"\r\n" +
            "Content-Description: S/MIME Cryptographic Signature\r\n\r\n" +
            mimeBase64(signature) +
            "--$b--\r\n").toByteArray(Charsets.ISO_8859_1)
    return listOf(
        "Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\";\r\n\tmicalg=${Smime.MICALG};\r\n\tboundary=\"$b\"",
    ) to body
}

/** application/pkcs7-mime enveloped data (RFC 8551 section 3.3), signed first when [signer] is set. */
internal fun smimeEncrypted(entity: ByteArray, to: EncryptTo.SMime, signer: SignWith.SMime?): Pair<List<String>, ByteArray> {
    val inside = if (signer != null) {
        val (headers, body) = smimeSigned(entity, signer)
        entityOf(headers, body)
    } else {
        Pgp.canonical(entity)
    }
    val der = Smime.encrypt(inside, to.certificates)
    return listOf(
        "Content-Type: application/pkcs7-mime; smime-type=enveloped-data; name=\"smime.p7m\"",
        "Content-Transfer-Encoding: base64",
        "Content-Disposition: attachment; filename=\"smime.p7m\"",
        "Content-Description: S/MIME Encrypted Message",
    ) to mimeBase64(der).toByteArray(Charsets.US_ASCII)
}

/**
 * The whole outgoing message, protected as asked, ready to hand to SMTP or to a JMAP upload.
 *
 * [plain] is the message as Rampart would otherwise have sent it. With neither [sign] nor
 * [encrypt] this refuses rather than passing the message through: a caller that reached this
 * function asked for protection, and the one outcome that must never happen is the message
 * going out unprotected without anybody deciding that.
 */
internal fun sealedMessage(
    plain: MimeMessage,
    scheme: SealScheme,
    sign: SignWith?,
    encrypt: EncryptTo?,
    plainText: String? = null,
): ByteArray {
    if (sign == null && encrypt == null) throw CryptoFailure("Neither signing nor encryption was asked for, so there is nothing to seal.")
    val split = splitForSealing(plain)
    val (headers, body) = when (scheme) {
        SealScheme.PGP_MIME -> {
            val signer = sign as? SignWith.OpenPgp ?: sign?.let { throw CryptoFailure("An S/MIME certificate cannot sign an OpenPGP message.") }
            when (encrypt) {
                null -> pgpSigned(split.entity, signer!!)
                is EncryptTo.OpenPgp -> pgpEncrypted(split.entity, encrypt, signer)
                is EncryptTo.SMime -> throw CryptoFailure("S/MIME certificates cannot encrypt an OpenPGP message.")
            }
        }
        SealScheme.PGP_INLINE -> {
            val signer = sign as? SignWith.OpenPgp ?: sign?.let { throw CryptoFailure("An S/MIME certificate cannot sign an OpenPGP message.") }
            val to = encrypt?.let { it as? EncryptTo.OpenPgp ?: throw CryptoFailure("S/MIME certificates cannot encrypt an OpenPGP message.") }
            val type = plain.contentType.orEmpty()
            if (!type.startsWith("text/plain", true)) {
                throw CryptoFailure("Inline OpenPGP can only protect plain text, and this message has formatting or files. Use PGP/MIME instead.")
            }
            pgpInline(plainText ?: plain.content.toString(), to, signer)
        }
        SealScheme.SMIME -> {
            val signer = sign as? SignWith.SMime ?: sign?.let { throw CryptoFailure("An OpenPGP key cannot sign an S/MIME message.") }
            when (encrypt) {
                null -> smimeSigned(split.entity, signer!!)
                is EncryptTo.SMime -> smimeEncrypted(split.entity, encrypt, signer)
                is EncryptTo.OpenPgp -> throw CryptoFailure("OpenPGP keys cannot encrypt an S/MIME message.")
            }
        }
    }
    return assemble(split.outer, headers, body)
}

// ---- missing keys --------------------------------------------------------------------

/** The recipients [has] cannot find a key for, in the order they were written. */
internal fun missingKeys(recipients: List<String>, has: (String) -> Boolean): List<String> =
    recipients.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.filterNot(has)

/**
 * The sentence shown when encryption was asked for and somebody has no key.
 *
 * Names every address, because "a recipient has no key" sends somebody hunting through the
 * To line. And never offers to send it anyway: turning encryption off is a decision the
 * person makes on the message, not a button on an error.
 */
internal fun missingKeySentence(missing: List<String>): String? = when (missing.size) {
    0 -> null
    1 -> "There is no encryption key for ${missing[0]}, so the message was not sent. Find their key, or turn encryption off to send it readable."
    else -> "There are no encryption keys for ${missing.dropLast(1).joinToString(", ")} and ${missing.last()}, " +
        "so the message was not sent. Find their keys, or turn encryption off to send it readable."
}
