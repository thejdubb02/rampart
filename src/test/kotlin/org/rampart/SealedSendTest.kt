package org.rampart

import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decisions made before a protected message is sent: whose keys, which format, and the
 * refusals. The key list is kept in memory and every key is made inside the test.
 */
class SealedSendTest {
    private class Memory : KeyBacking {
        var index = KeyIndex()
        val secrets = mutableMapOf<String, String>()
        override fun readIndex() = index
        override fun writeIndex(index: KeyIndex): Boolean { this.index = index; return true }
        override fun storeSecret(name: String, value: String): String? { secrets[name] = value; return null }
        override fun loadSecret(name: String) = secrets[name]
        override fun forgetSecret(name: String) { secrets.remove(name) }
    }

    private fun plain(): MimeMessage = MimeMessage(Session.getInstance(Properties())).apply {
        setFrom(InternetAddress("ana@example.org"))
        setRecipients(jakarta.mail.Message.RecipientType.TO, "ben@example.net")
        setSubject("Plans")
        setText("The plan is in the usual place.", "UTF-8")
        saveChanges()
    }

    @Test
    fun `a missing recipient key stops the send and names the recipient`() {
        val ring = Keyring(Memory())
        ring.generatePgp("Ana", "ana@example.org", PgpAlgorithm.CURVE25519)
        val failure = assertFailsWith<CryptoFailure> {
            planSeal(ring, "ana@example.org", listOf("ben@example.net"), sign = false, encrypt = true)
        }
        assertTrue(failure.message!!.contains("ben@example.net"), failure.message)
        assertTrue(failure.message!!.contains("not sent"))
    }

    @Test
    fun `no key of my own stops an encrypted send, because Sent must stay readable`() {
        val ring = Keyring(Memory())
        ring.addPgpPublic(Pgp.generate("Ben", "ben@example.net", PgpAlgorithm.CURVE25519).publicArmored.toByteArray(), KeySource.IMPORTED)
        val failure = assertFailsWith<CryptoFailure> {
            planSeal(ring, "ana@example.org", listOf("ben@example.net"), sign = false, encrypt = true)
        }
        assertTrue(failure.message!!.contains("ana@example.org"), failure.message)
    }

    @Test
    fun `the Sent copy is encrypted to the sender too`() {
        val ring = Keyring(Memory())
        val ana = ring.generatePgp("Ana", "ana@example.org", PgpAlgorithm.CURVE25519)
        val ben = Pgp.generate("Ben", "ben@example.net", PgpAlgorithm.CURVE25519)
        ring.addPgpPublic(ben.publicArmored.toByteArray(), KeySource.IMPORTED)
        val plan = planSeal(ring, "ana@example.org", listOf("ben@example.net"), sign = true, encrypt = true)
        assertEquals(SealScheme.PGP_MIME, plan.scheme)
        val raw = sealWith(ring, plan, plain(), sign = true, encrypt = true, plainText = null)
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("usual place"))
        // Ana, with only her own key, can read her own Sent copy.
        val opened = openSealed(raw, ring.sealKeys())
        assertTrue(opened.text.orEmpty().contains("usual place"))
        assertEquals(ana.fingerprint, (opened.signature as SignatureVerdict.Good).fingerprint)
    }

    @Test
    fun `a certificate identity sends SMIME, and signing alone needs no recipient keys`() {
        val ring = Keyring(Memory())
        ring.makeTestCertificate("Ana", "ana@example.org")
        val plan = planSeal(ring, "ana@example.org", listOf("nobody@example.com"), sign = true, encrypt = false)
        assertEquals(SealScheme.SMIME, plan.scheme)
        val raw = sealWith(ring, plan, plain(), sign = true, encrypt = false, plainText = null)
        assertTrue(String(raw, Charsets.ISO_8859_1).contains("application/pkcs7-signature"))
    }

    @Test
    fun `the inline setting chooses inline PGP`() {
        val memory = Memory()
        val ring = Keyring(memory)
        ring.generatePgp("Ana", "ana@example.org", PgpAlgorithm.CURVE25519)
        ring.setInlinePgp(true)
        val plan = planSeal(ring, "ana@example.org", emptyList(), sign = true, encrypt = false)
        assertEquals(SealScheme.PGP_INLINE, plan.scheme)
        val raw = sealWith(ring, plan, plain(), sign = true, encrypt = false, plainText = "The plan is in the usual place.")
        assertTrue(String(raw, Charsets.ISO_8859_1).contains("BEGIN PGP SIGNED MESSAGE"))
    }

    @Test
    fun `asking for nothing is refused`() {
        assertFailsWith<CryptoFailure> { planSeal(Keyring(Memory()), "ana@example.org", emptyList(), sign = false, encrypt = false) }
    }
}
