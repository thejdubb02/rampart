package org.rampart

import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OpenPGP, end to end, with keys made inside the test. Nothing here reads a key from disk
 * or from the network, and no key made here outlives the run.
 */
class PgpTest {
    private class Person(val name: String, val email: String) {
        val made = Pgp.generate(name, email, PgpAlgorithm.CURVE25519)
        val secret: PGPSecretKeyRing = Pgp.secretRing(made.secretBinary)
        val public: PGPPublicKeyRing = Pgp.publicRings(made.publicArmored.toByteArray()).single()
    }

    private val ana by lazy { Person("Ana Lima", "ana@example.org") }
    private val ben by lazy { Person("Ben Okafor", "ben@example.net") }

    private fun keysOf(me: Person, vararg known: Person) = object : SealKeys {
        override fun pgpSecrets() = listOf(me.secret)
        override fun pgpPublics() = (listOf(me) + known).map { it.public }
        override fun smimeIdentities() = emptyList<SmimeIdentity>()
    }

    private fun plainMessage(text: String = "Meet at the usual place at nine.\nBring the plans."): MimeMessage {
        val message = MimeMessage(Session.getInstance(Properties()))
        message.setFrom(InternetAddress("ana@example.org", "Ana Lima"))
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "ben@example.net")
        message.setSubject("Plans for Tuesday", "UTF-8")
        val alt = MimeMultipart("alternative")
        alt.addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8") })
        alt.addBodyPart(MimeBodyPart().apply { setContent("<p>${text.replace("\n", "<br>")}</p>", "text/html; charset=utf-8") })
        message.setContent(alt)
        message.saveChanges()
        return message
    }

    @Test
    fun `a Curve25519 key names its address and can encrypt`() {
        assertEquals(listOf("ana@example.org"), Pgp.emailsOf(ana.public))
        assertNotNull(Pgp.encryptionKey(ana.public))
        assertEquals(40, ana.made.fingerprint.length)
    }

    @Test
    fun `an RSA key round trips too`() {
        val made = Pgp.generate("Cy", "cy@example.com", PgpAlgorithm.RSA3072)
        val secret = Pgp.secretRing(made.secretBinary)
        val public = Pgp.publicRings(made.publicArmored.toByteArray()).single()
        val sealed = Pgp.encrypt("hello".toByteArray(), listOf(public), signer = secret)
        val opened = Pgp.open(sealed, listOf(secret), listOf(public))
        assertEquals("hello", String(opened.content))
        assertIs<SignatureVerdict.Good>(opened.signature)
    }

    @Test
    fun `PGP MIME encrypt and sign, then decrypt and verify`() {
        val raw = sealedMessage(
            plainMessage(),
            SealScheme.PGP_MIME,
            SignWith.OpenPgp(ana.secret),
            EncryptTo.OpenPgp(listOf(ben.public, ana.public)),
        )
        val text = String(raw, Charsets.ISO_8859_1)
        assertTrue(text.contains("Content-Type: multipart/encrypted;"), text)
        assertTrue(text.contains("protocol=\"application/pgp-encrypted\""))
        assertTrue(text.contains("Content-Type: application/pgp-encrypted"))
        assertTrue(text.contains("Version: 1"))
        assertTrue(text.contains("-----BEGIN PGP MESSAGE-----"))
        assertTrue(text.contains("Subject: Plans for Tuesday"), "the subject stays outside")
        assertTrue(!text.contains("usual place"), "the body must not be readable")
        assertEquals(Seal.PGP_ENCRYPTED, sealOf(raw))

        val opened = openSealed(raw, keysOf(ben, ana))
        assertTrue(opened.encrypted)
        assertTrue(opened.text.orEmpty().contains("Meet at the usual place"), opened.text)
        assertTrue(opened.html.orEmpty().contains("<p>Meet at the usual place"))
        val verdict = assertIs<SignatureVerdict.Good>(opened.signature)
        assertEquals(ana.made.fingerprint, verdict.fingerprint)

        // The sender can read their own Sent copy, because it was encrypted to them too.
        assertTrue(openSealed(raw, keysOf(ana)).text.orEmpty().contains("Meet at the usual place"))
    }

    @Test
    fun `the badge says who signed and stays calm for a trusted key`() {
        val raw = sealedMessage(plainMessage(), SealScheme.PGP_MIME, SignWith.OpenPgp(ana.secret), EncryptTo.OpenPgp(listOf(ben.public)))
        val opened = openSealed(raw, keysOf(ben, ana))
        val lines = sealLines(opened, "ana@example.org") { it == ana.made.fingerprint }
        assertTrue(lines.none { it.alarm }, lines.toString())
        assertTrue(lines.any { it.text.startsWith("Encrypted with OpenPGP") })
        assertTrue(lines.any { it.text.contains("Signed by Ana Lima") })
        // The same signature is an alarm when the message claims another sender, or the key is not trusted.
        assertTrue(sealLines(opened, "boss@example.org") { true }.any { it.alarm })
        assertTrue(sealLines(opened, "ana@example.org") { false }.any { it.alarm })
    }

    @Test
    fun `PGP MIME signed only has the right structure and verifies`() {
        val raw = sealedMessage(plainMessage(), SealScheme.PGP_MIME, SignWith.OpenPgp(ana.secret), null)
        val text = String(raw, Charsets.ISO_8859_1)
        assertTrue(text.contains("Content-Type: multipart/signed; micalg=pgp-sha256;"), text)
        assertTrue(text.contains("protocol=\"application/pgp-signature\""))
        assertTrue(text.contains("Content-Type: application/pgp-signature"))
        assertEquals(Seal.PGP_SIGNED, sealOf(raw))
        val opened = openSealed(raw, keysOf(ben, ana))
        assertTrue(!opened.encrypted)
        assertIs<SignatureVerdict.Good>(opened.signature)
        assertTrue(opened.text.orEmpty().contains("Bring the plans"))
    }

    @Test
    fun `a changed byte in a signed body is a broken verdict, not a crash`() {
        val raw = sealedMessage(plainMessage(), SealScheme.PGP_MIME, SignWith.OpenPgp(ana.secret), null)
        val text = String(raw, Charsets.ISO_8859_1)
        val tampered = text.replaceFirst("usual place", "usual plaice").toByteArray(Charsets.ISO_8859_1)
        val opened = openSealed(tampered, keysOf(ben, ana))
        assertIs<SignatureVerdict.Broken>(opened.signature)
        assertTrue(sealLines(opened, "ana@example.org") { true }.any { it.alarm && it.text.startsWith("Signature broken") })
    }

    @Test
    fun `a signature by a key nobody has is shown as unchecked`() {
        val raw = sealedMessage(plainMessage(), SealScheme.PGP_MIME, SignWith.OpenPgp(ana.secret), null)
        val opened = openSealed(raw, keysOf(ben))
        assertIs<SignatureVerdict.UnknownSigner>(opened.signature)
    }

    @Test
    fun `a message for another key gives one sentence`() {
        val raw = sealedMessage(plainMessage(), SealScheme.PGP_MIME, null, EncryptTo.OpenPgp(listOf(ben.public)))
        val stranger = Person("Cy", "cy@example.com")
        val failure = assertFailsWith<CryptoFailure> { openSealed(raw, keysOf(stranger)) }
        assertTrue(failure.message!!.startsWith("This message was encrypted to a key Rampart does not have"), failure.message)
        assertTrue(failure.message!!.endsWith("."))
        val none = object : SealKeys {
            override fun pgpSecrets() = emptyList<PGPSecretKeyRing>()
            override fun pgpPublics() = emptyList<PGPPublicKeyRing>()
            override fun smimeIdentities() = emptyList<SmimeIdentity>()
        }
        assertFailsWith<CryptoFailure> { openSealed(raw, none) }
    }

    @Test
    fun `a passphrase protected key asks, and a wrong passphrase is a sentence`() {
        val made = Pgp.generate("Dee", "dee@example.com", PgpAlgorithm.CURVE25519, "correct horse".toCharArray())
        val secret = Pgp.secretRing(made.secretBinary)
        val public = Pgp.publicRings(made.publicArmored.toByteArray()).single()
        assertTrue(Pgp.needsPassphrase(secret))
        val sealed = Pgp.encrypt("private".toByteArray(), listOf(public))
        assertFailsWith<PassphraseNeeded> { Pgp.open(sealed, listOf(secret), listOf(public)) }
        val wrong = assertFailsWith<CryptoFailure> {
            Pgp.open(sealed, listOf(secret), listOf(public), Passphrases { "battery staple".toCharArray() })
        }
        assertTrue(wrong.message!!.contains("passphrase") && wrong.message!!.contains("not right"), wrong.message)
        val opened = Pgp.open(sealed, listOf(secret), listOf(public), Passphrases { "correct horse".toCharArray() })
        assertEquals("private", String(opened.content))
    }

    @Test
    fun `inline PGP encrypts the text and reads back`() {
        val plain = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom(InternetAddress("ana@example.org"))
            setSubject("Inline")
            setText("Only the text, line one.\nLine two.", "UTF-8")
            saveChanges()
        }
        val raw = sealedMessage(plain, SealScheme.PGP_INLINE, SignWith.OpenPgp(ana.secret), EncryptTo.OpenPgp(listOf(ben.public, ana.public)))
        assertEquals(Seal.PGP_INLINE_ENCRYPTED, sealOf(raw))
        val opened = openSealed(raw, keysOf(ben, ana))
        assertTrue(opened.encrypted)
        assertEquals("Only the text, line one.\r\nLine two.", opened.text)
        assertIs<SignatureVerdict.Good>(opened.signature)
    }

    @Test
    fun `inline PGP clear signing verifies, and a change breaks it`() {
        val plain = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom(InternetAddress("ana@example.org"))
            setText("- a line with a dash\nand one with trailing space   \nend", "UTF-8")
            saveChanges()
        }
        val raw = sealedMessage(plain, SealScheme.PGP_INLINE, SignWith.OpenPgp(ana.secret), null)
        assertEquals(Seal.PGP_INLINE_SIGNED, sealOf(raw))
        val opened = openSealed(raw, keysOf(ben, ana))
        assertIs<SignatureVerdict.Good>(opened.signature, opened.toString())
        assertTrue(opened.text.orEmpty().startsWith("- a line with a dash"))

        val block = Pgp.clearSign("pay 100 to Ben", ana.secret)
        val (_, good) = Pgp.verifyCleartext(block, listOf(ana.public))
        assertIs<SignatureVerdict.Good>(good)
        val (_, bad) = Pgp.verifyCleartext(block.replace("pay 100", "pay 900"), listOf(ana.public))
        assertIs<SignatureVerdict.Broken>(bad)
    }

    @Test
    fun `inline PGP refuses a message with formatting rather than dropping it`() {
        val failure = assertFailsWith<CryptoFailure> {
            sealedMessage(plainMessage(), SealScheme.PGP_INLINE, null, EncryptTo.OpenPgp(listOf(ben.public)))
        }
        assertTrue(failure.message!!.contains("Inline OpenPGP can only protect plain text"))
    }

    @Test
    fun `asking for neither signing nor encryption is refused rather than sent plain`() {
        assertFailsWith<CryptoFailure> { sealedMessage(plainMessage(), SealScheme.PGP_MIME, null, null) }
    }

    @Test
    fun `a missing recipient key blocks sending and names the recipient`() {
        val have = setOf("ana@example.org")
        val missing = missingKeys(listOf("ana@example.org", "ben@example.net", "Ben@example.net")) { it.lowercase() in have }
        assertEquals(listOf("ben@example.net"), missing)
        val sentence = missingKeySentence(missing)!!
        assertTrue(sentence.contains("ben@example.net"))
        assertTrue(sentence.contains("was not sent"))
        val two = missingKeySentence(listOf("a@x.org", "b@y.org", "c@z.org"))!!
        assertTrue(two.contains("a@x.org, b@y.org and c@z.org"), two)
        assertEquals(null, missingKeySentence(emptyList()))
    }

    @Test
    fun `a signed message wrapped by a mailing list still verifies, and the footer is called out`() {
        val signed = sealedMessage(plainMessage(), SealScheme.PGP_MIME, SignWith.OpenPgp(ana.secret), null)
        val inner = RawEntity.of(signed)
        val contentType = String(inner.headers, Charsets.ISO_8859_1).replace("\r\n\t", " ")
            .lines().first { it.startsWith("Content-Type:") }
        val wrapped = assemble(
            listOf("From: ana@example.org", "Subject: [list] Plans"),
            listOf("Content-Type: multipart/mixed; boundary=\"LIST\""),
            ("--LIST\r\n$contentType\r\n\r\n").toByteArray(Charsets.ISO_8859_1) + inner.body +
                "\r\n--LIST\r\nContent-Type: text/plain\r\n\r\nYou are on the list.\r\n--LIST--\r\n".toByteArray(),
        )
        val opened = openSealed(wrapped, keysOf(ben, ana))
        assertTrue(opened.partial)
        assertIs<SignatureVerdict.Good>(opened.signature)
        assertTrue(sealLines(opened, "ana@example.org") { true }.any { it.alarm && it.text.startsWith("Only part") })
    }

    @Test
    fun `an ordinary message is not sealed`() {
        assertFailsWith<NotSealed> { openSealed(assemble(listOf("From: a@b.org"), listOf("Content-Type: text/plain"), "hi".toByteArray()), keysOf(ana)) }
    }

    @Test
    fun `the signed part is cut at the boundary without the CRLF before it`() {
        val body = "preamble\r\n--b1\r\nContent-Type: text/plain\r\n\r\nhello\r\n--b1\r\nX: y\r\n\r\nsig\r\n--b1--\r\n".toByteArray()
        val parts = multipartParts(body, "b1")
        assertEquals(2, parts.size)
        assertEquals("Content-Type: text/plain\r\n\r\nhello", String(parts[0]))
    }
}
