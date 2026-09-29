package org.rampart

import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * S/MIME, end to end, with self-signed test certificates built by Bouncy Castle inside the
 * test. None of them is trusted by any certificate authority, which the last test checks.
 */
class SmimeTest {
    private val ana by lazy { Smime.selfSigned("Ana Lima", "ana@example.org") }
    private val ben by lazy { Smime.selfSigned("Ben Okafor", "ben@example.net") }

    private fun keysOf(vararg ids: SmimeIdentity) = object : SealKeys {
        override fun pgpSecrets() = emptyList<PGPSecretKeyRing>()
        override fun pgpPublics() = emptyList<PGPPublicKeyRing>()
        override fun smimeIdentities() = ids.toList()
    }

    private fun plain(): MimeMessage = MimeMessage(Session.getInstance(Properties())).apply {
        setFrom(InternetAddress("ana@example.org", "Ana Lima"))
        setRecipients(jakarta.mail.Message.RecipientType.TO, "ben@example.net")
        setSubject("Quarterly numbers")
        setText("Revenue is up four percent.\nDo not forward.", "UTF-8")
        saveChanges()
    }

    @Test
    fun `a test certificate names its address`() {
        assertEquals(listOf("ana@example.org"), Smime.emailsOf(ana.certificate))
        assertEquals("Ana Lima", Smime.nameOf(ana.certificate))
        assertTrue(Smime.selfSigned(ana.certificate))
        assertEquals(64, Smime.fingerprintOf(ana.certificate).length)
    }

    @Test
    fun `sign and encrypt, then decrypt and verify`() {
        val raw = sealedMessage(plain(), SealScheme.SMIME, SignWith.SMime(ana), EncryptTo.SMime(listOf(ben.certificate, ana.certificate)))
        val text = String(raw, Charsets.ISO_8859_1)
        assertTrue(text.contains("Content-Type: application/pkcs7-mime; smime-type=enveloped-data"), text)
        assertTrue(text.contains("Content-Transfer-Encoding: base64"))
        assertFalse(text.contains("Revenue"), "the body must not be readable")
        assertTrue(text.contains("Subject: Quarterly numbers"))
        assertEquals(Seal.SMIME_ENCRYPTED, sealOf(raw))

        val opened = openSealed(raw, keysOf(ben))
        assertTrue(opened.encrypted)
        assertTrue(opened.text.orEmpty().contains("Revenue is up four percent."), opened.toString())
        val verdict = assertIs<SignatureVerdict.Good>(opened.signature)
        assertEquals(listOf("ana@example.org"), verdict.emails)
        assertFalse(verdict.vouched, "a self-signed certificate is vouched for by nobody")
        assertTrue(sealLines(opened, "ana@example.org") { false }.any { it.alarm }, "untrusted is loud")

        // The sender's own copy opens with the sender's own certificate.
        assertTrue(openSealed(raw, keysOf(ana)).text.orEmpty().contains("Revenue"))
    }

    @Test
    fun `multipart signed has the right protocol and micalg and verifies`() {
        val raw = sealedMessage(plain(), SealScheme.SMIME, SignWith.SMime(ana), null)
        val text = String(raw, Charsets.ISO_8859_1)
        assertTrue(text.contains("Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\";"), text)
        assertTrue(text.contains("micalg=sha-256"))
        assertTrue(text.contains("Content-Type: application/pkcs7-signature; name=\"smime.p7s\""))
        assertEquals(Seal.SMIME_SIGNED, sealOf(raw))
        val opened = openSealed(raw, keysOf())
        assertIs<SignatureVerdict.Good>(opened.signature)
        assertTrue(opened.text.orEmpty().contains("Do not forward."))
    }

    @Test
    fun `a changed byte in the signed body is broken, not a crash`() {
        val raw = sealedMessage(plain(), SealScheme.SMIME, SignWith.SMime(ana), null)
        val tampered = String(raw, Charsets.ISO_8859_1).replaceFirst("four percent", "forty percent").toByteArray(Charsets.ISO_8859_1)
        assertIs<SignatureVerdict.Broken>(openSealed(tampered, keysOf()).signature)
    }

    @Test
    fun `no certificate of mine gives one sentence`() {
        val raw = sealedMessage(plain(), SealScheme.SMIME, null, EncryptTo.SMime(listOf(ben.certificate)))
        val none = assertFailsWith<CryptoFailure> { openSealed(raw, keysOf()) }
        assertTrue(none.message!!.contains("no S/MIME certificate of yours"), none.message)
        val wrong = assertFailsWith<CryptoFailure> { openSealed(raw, keysOf(ana)) }
        assertTrue(wrong.message!!.contains("certificate Rampart does not have"), wrong.message)
    }

    @Test
    fun `a PKCS12 file round trips, and a wrong password is a sentence`() {
        val file = Smime.writePkcs12(ana, "s3cret".toCharArray())
        val back = Smime.readPkcs12(file, "s3cret".toCharArray())
        assertEquals(Smime.fingerprintOf(ana.certificate), Smime.fingerprintOf(back.certificate))
        val wrong = assertFailsWith<CryptoFailure> { Smime.readPkcs12(file, "nope".toCharArray()) }
        assertEquals("The password for that certificate file is not right.", wrong.message)
        val unprotected = Smime.readPkcs12(Smime.writePkcs12(ben, CharArray(0)), CharArray(0))
        assertEquals(Smime.fingerprintOf(ben.certificate), Smime.fingerprintOf(unprotected.certificate))
    }

    @Test
    fun `opaque signed data opens and verifies`() {
        val signed = org.bouncycastle.cms.CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(
                org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder()
                    .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
                    .build("SHA256withRSA", ana.privateKey, ana.certificate),
            )
            addCertificates(org.bouncycastle.cert.jcajce.JcaCertStore(listOf(ana.certificate)))
        }.generate(org.bouncycastle.cms.CMSProcessableByteArray("Content-Type: text/plain\r\n\r\nopaque hello".toByteArray()), true).encoded
        val raw = assemble(
            listOf("From: ana@example.org", "Subject: opaque"),
            listOf("Content-Type: application/pkcs7-mime; smime-type=signed-data; name=smime.p7m", "Content-Transfer-Encoding: base64"),
            mimeBase64(signed).toByteArray(),
        )
        assertEquals(Seal.SMIME_OPAQUE_SIGNED, sealOf(raw))
        val opened = openSealed(raw, keysOf())
        assertEquals("opaque hello", opened.text?.trim())
        assertIs<SignatureVerdict.Good>(opened.signature)
    }
}
