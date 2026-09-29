package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The key list, key lookup addresses, the Rook gate, the search setting and the server's
 * encryption at rest calls. All offline: nothing here opens a socket.
 */
class CryptoKeysTest {
    private class Memory : KeyBacking {
        var index = KeyIndex()
        val secrets = mutableMapOf<String, String>()
        override fun readIndex() = index
        override fun writeIndex(index: KeyIndex): Boolean { this.index = index; return true }
        override fun storeSecret(name: String, value: String): String? { secrets[name] = value; return null }
        override fun loadSecret(name: String) = secrets[name]
        override fun forgetSecret(name: String) { secrets.remove(name) }
    }

    // ---- Web Key Directory ----------------------------------------------------------

    @Test
    fun `the WKD hash is the z-base-32 of the SHA-1 of the lower-cased local part`() {
        // The example in draft-koch-openpgp-webkey-service, section 3.1.
        assertEquals("iy9q119eutrkn8s1mk4r39qejnbu3n5q", KeyLookup.wkdHash("Joe.Doe"))
        assertEquals(KeyLookup.wkdHash("joe.doe"), KeyLookup.wkdHash("JOE.DOE"))
        assertEquals(32, KeyLookup.wkdHash("anybody").length)
    }

    @Test
    fun `WKD addresses, advanced then direct, https only`() {
        val (advanced, direct) = KeyLookup.wkdUrls("Joe.Doe@Example.ORG")!!
        assertEquals(
            "https://openpgpkey.example.org/.well-known/openpgpkey/example.org/hu/iy9q119eutrkn8s1mk4r39qejnbu3n5q?l=Joe.Doe",
            advanced,
        )
        assertEquals("https://example.org/.well-known/openpgpkey/hu/iy9q119eutrkn8s1mk4r39qejnbu3n5q?l=Joe.Doe", direct)
        assertNull(KeyLookup.wkdUrls("not an address"))
        assertNull(KeyLookup.wkdUrls("a@evil.example/../x"))
    }

    @Test
    fun `z-base-32 of known bytes`() {
        assertEquals("", KeyLookup.zbase32(ByteArray(0)))
        assertEquals("yy", KeyLookup.zbase32(byteArrayOf(0)))
        assertEquals("9h", KeyLookup.zbase32(byteArrayOf(-1)))
    }

    @Test
    fun `keys openpgp org is asked by address over https`() {
        assertEquals("https://keys.openpgp.org/vks/v1/by-email/ana%40example.org", KeyLookup.vksUrl("ana@example.org"))
    }

    @Test
    fun `a looked up key must name the address it was looked up for`() {
        val ana = Pgp.publicRings(Pgp.generate("Ana", "ana@example.org", PgpAlgorithm.CURVE25519).publicArmored.toByteArray())
        assertEquals(1, KeyLookup.forAddress(ana, "ANA@example.org").size)
        assertEquals(0, KeyLookup.forAddress(ana, "boss@example.org").size)
    }

    @Test
    fun `a key inside a contact card is read, a link in one is not followed`() {
        val armored = Pgp.generate("Ana", "ana@example.org", PgpAlgorithm.CURVE25519).publicArmored
        val card = buildJsonObject {
            putJsonObject("cryptoKeys") {
                putJsonObject("k1") { put("uri", "data:application/pgp-keys;base64," + Base64.getEncoder().encodeToString(armored.toByteArray())) }
                putJsonObject("k2") { put("uri", "https://example.org/key.asc") }
            }
        }
        val keys = KeyLookup.keysInCard(card)
        assertEquals(1, keys.size)
        assertEquals(listOf("ana@example.org"), Pgp.emailsOf(Pgp.publicRings(keys[0]).single()))
    }

    @Test
    fun `an Autocrypt key is only taken for the sender's own address`() {
        val made = Pgp.generate("Ana", "ana@example.org", PgpAlgorithm.CURVE25519)
        val binary = Pgp.publicRings(made.publicArmored.toByteArray()).single().encoded
        val header = "addr=ana@example.org; prefer-encrypt=mutual; keydata=" + Base64.getEncoder().encodeToString(binary)
        assertNotNull(KeyLookup.autocryptKey(header, "Ana@Example.org"))
        assertNull(KeyLookup.autocryptKey(header, "boss@example.org"))
    }

    // ---- the key list ----------------------------------------------------------------

    @Test
    fun `a new key keeps its secret half out of the list file`() {
        val memory = Memory()
        val ring = Keyring(memory)
        val entry = ring.generatePgp("Ana", "ana@example.org", PgpAlgorithm.CURVE25519)
        assertTrue(entry.hasSecret)
        assertEquals(entry.fingerprint, memory.index.identityKeys["ana@example.org"])
        val listed = memory.index.toString()
        assertFalse(listed.contains("PRIVATE KEY"))
        assertTrue(memory.secrets.keys.single().startsWith("openpgp-secret-"))
        assertEquals(entry.fingerprint, ring.ownKey("ana@example.org")?.fingerprint)
        assertEquals(entry.fingerprint, Pgp.fingerprintOf(ring.pgpSecretRing(entry.fingerprint)))
    }

    @Test
    fun `a secret key is exported only after an explicit yes`() {
        val ring = Keyring(Memory())
        val entry = ring.generatePgp("Ana", "ana@example.org", PgpAlgorithm.CURVE25519)
        assertFailsWith<CryptoFailure> { ring.exportSecret(entry.fingerprint, confirmed = false) }
        val exported = String(ring.exportSecret(entry.fingerprint, confirmed = true))
        assertTrue(exported.startsWith("-----BEGIN PGP PRIVATE KEY BLOCK-----"), exported.take(60))
        assertTrue(ring.exportPublic(entry.fingerprint).startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
    }

    @Test
    fun `a store that refuses leaves no key behind`() {
        val refusing = object : KeyBacking by Memory() {
            override fun storeSecret(name: String, value: String): String? = "No credential store was found."
        }
        val ring = Keyring(refusing)
        val failure = assertFailsWith<CryptoFailure> { ring.generatePgp("Ana", "ana@example.org", PgpAlgorithm.CURVE25519) }
        assertTrue(failure.message!!.contains("No credential store was found"), failure.message)
        assertTrue(ring.entries().isEmpty())
    }

    @Test
    fun `a key that arrived attached to mail is not encrypted to until trusted`() {
        val ring = Keyring(Memory())
        val ben = Pgp.generate("Ben", "ben@example.net", PgpAlgorithm.CURVE25519)
        val added = ring.addPgpPublic(ben.publicArmored.toByteArray(), KeySource.ATTACHED).single()
        assertFalse(added.trusted)
        assertNull(ring.publicFor("ben@example.net", KeyKind.OPENPGP))
        ring.setTrusted(added.fingerprint, true)
        assertEquals(added.fingerprint, ring.publicFor("ben@example.net", KeyKind.OPENPGP)?.fingerprint)
    }

    @Test
    fun `a protected PKCS12 asks for its password at use`() {
        val ring = Keyring(Memory())
        val id = Smime.selfSigned("Ana", "ana@example.org")
        val entry = ring.importPkcs12(Smime.writePkcs12(id, "pw".toCharArray()), "pw".toCharArray())
        assertTrue(entry.needsPassphrase)
        ring.forgetPassphrases()
        assertFailsWith<PassphraseNeeded> { ring.smimeIdentity(entry.fingerprint) }
        ring.rememberPassphrase(entry.fingerprint, "pw".toCharArray())
        assertEquals(entry.fingerprint, Smime.fingerprintOf(ring.smimeIdentity(entry.fingerprint).certificate))
    }

    @Test
    fun `removing a key forgets its secret`() {
        val memory = Memory()
        val ring = Keyring(memory)
        val entry = ring.makeTestCertificate("Ana", "ana@example.org")
        assertTrue(entry.testingOnly)
        ring.remove(entry.fingerprint)
        assertTrue(memory.secrets.isEmpty())
        assertTrue(ring.entries().isEmpty())
        assertNull(memory.index.identityKeys["ana@example.org"])
    }

    // ---- Rook and search ---------------------------------------------------------------

    @Test
    fun `Rook is refused encrypted text without the explicit flag`() {
        val armored = "Hi\n-----BEGIN PGP MESSAGE-----\nhQEMA...\n-----END PGP MESSAGE-----\n"
        assertEquals(RookGate.WITHHELD, RookGate.textFor(armored))
        assertEquals(RookGate.WITHHELD, RookGate.textFor("", sealed = true, plaintext = "the secret plan"))
        assertEquals(RookGate.WITHHELD, RookGate.textFor("", sealed = true, plaintext = "the secret plan", explicit = false))
        assertEquals("the secret plan", RookGate.textFor("", sealed = true, plaintext = "the secret plan", explicit = true))
        assertEquals("an ordinary message", RookGate.textFor("an ordinary message"))
        // Explicit is not a way to get ciphertext through either: with no plaintext, still withheld.
        assertEquals(RookGate.WITHHELD, RookGate.textFor(armored, explicit = true))
    }

    @Test
    fun `a decrypted body is marked and recognised`() {
        val body = Body("<p>secret</p>", "secret")
        assertFalse(RookGate.isDecrypted(body))
        RookGate.markDecrypted(body)
        assertTrue(RookGate.isDecrypted(body))
    }

    @Test
    fun `searching inside encrypted mail is off by default and needs an encrypted store`() {
        assertFalse(EncryptedSearch.DEFAULT)
        assertFalse(KeyIndex().searchInside)
        assertFalse(EncryptedSearch.mayIndex(settingOn = false, storeEncrypted = true))
        assertFalse(EncryptedSearch.mayIndex(settingOn = true, storeEncrypted = false))
        assertTrue(EncryptedSearch.mayIndex(settingOn = true, storeEncrypted = true))
    }

    // ---- Stalwart encryption at rest --------------------------------------------------

    @Test
    fun `turning encryption at rest on sends the self-service shape`() {
        val call = enableRestCall("a1", RestCipher.AES256, "k9", encryptOnAppend = true)
        assertEquals("x:AccountSettings/set", (call[0] as JsonPrimitive).content)
        val rest = call[1].jsonObject["update"]!!.jsonObject["singleton"]!!.jsonObject["encryptionAtRest"]!!.jsonObject
        assertEquals("Aes256", (rest["@type"] as JsonPrimitive).content)
        assertEquals("k9", (rest["publicKey"] as JsonPrimitive).content)
        assertEquals("true", (rest["encryptOnAppend"] as JsonPrimitive).content)
        assertEquals("false", (rest["allowSpamTraining"] as JsonPrimitive).content)
        val off = disableRestCall("a1")[1].jsonObject["update"]!!.jsonObject["singleton"]!!.jsonObject["encryptionAtRest"]!!.jsonObject
        assertEquals("Disabled", (off["@type"] as JsonPrimitive).content)
    }

    @Test
    fun `a public key upload names its addresses as a set`() {
        val call = createPublicKeyCall("a1", "-----BEGIN PGP PUBLIC KEY BLOCK-----\nx\n-----END PGP PUBLIC KEY BLOCK-----", "Rampart", listOf("ana@example.org"))
        assertEquals("x:PublicKey/set", (call[0] as JsonPrimitive).content)
        val made = call[1].jsonObject["create"]!!.jsonObject["key"]!!.jsonObject
        assertTrue((made["key"] as JsonPrimitive).content.endsWith("\n"))
        assertEquals("true", (made["emailAddresses"]!!.jsonObject["ana@example.org"] as JsonPrimitive).content)
        assertFalse(made.containsKey("accountId"), "the server sets the account, never us")
    }

    @Test
    fun `reading the setting back`() {
        val response = buildJsonArray {
            add(JsonPrimitive("x:AccountSettings/get"))
            add(buildJsonObject {
                putJsonArray("list") {
                    add(buildJsonObject {
                        put("id", "singleton")
                        putJsonObject("encryptionAtRest") {
                            put("@type", "Aes128"); put("publicKey", "k2"); put("encryptOnAppend", false); put("allowSpamTraining", false)
                        }
                    })
                }
            })
            add(JsonPrimitive("0"))
        }
        val state = readRestState(response)
        assertEquals(RestCipher.AES128, state.cipher)
        assertEquals("k2", state.publicKeyId)
        val off = readRestState(
            JsonArray(listOf(JsonPrimitive("x"), buildJsonObject { putJsonArray("list") { add(buildJsonObject { putJsonObject("encryptionAtRest") { put("@type", "Disabled") } }) } })),
        )
        assertNull(off.cipher)
    }

    @Test
    fun `OpenPGP keys are offered only the ciphers Stalwart accepts for them`() {
        assertEquals(listOf(RestCipher.AES256, RestCipher.AES128), ciphersFor(KeyKind.OPENPGP))
        assertEquals(4, ciphersFor(KeyKind.SMIME).size)
    }

    @Test
    fun `an IMAP account or another server gets a sentence, never a request for admin rights`() {
        assertTrue(restUnavailable("imap", null)!!.contains("IMAP"))
        assertTrue(restUnavailable("jmap", null)!!.contains("not Stalwart"))
        assertNull(restUnavailable("jmap", "a1"))
        val sentence = restFailure(JmapError("The server refused the request: forbidden"), "Encryption at rest was not turned on")
        assertTrue(sentence.contains("will not ask for administrator rights"), sentence)
    }
}
