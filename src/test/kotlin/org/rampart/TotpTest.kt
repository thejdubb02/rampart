package org.rampart

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The codes Rampart checks before it turns two-step login on.
 *
 * The server never checks a code against a new secret, so if this is wrong a person can
 * be told their authenticator is set up when it is not, and be locked out at the next
 * sign-in. The vectors are the published ones, not ones worked out from this code.
 */
class TotpTest {
    /** The RFC 4226 and RFC 6238 SHA-1 test key, the ASCII string "12345678901234567890". */
    private val rfcKey = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @Test
    fun `RFC 4226 appendix D codes`() {
        val expected = listOf(
            "755224", "287082", "359152", "969429", "338314",
            "254676", "287922", "162583", "399871", "520489",
        )
        expected.forEachIndexed { counter, code ->
            assertEquals(code, Totp.code(rfcKey, counter.toLong()), "counter $counter")
        }
    }

    @Test
    fun `RFC 6238 appendix B SHA-1 codes at eight digits`() {
        val vectors = mapOf(
            59L to "94287082",
            1111111109L to "07081804",
            1111111111L to "14050471",
            1234567890L to "89005924",
            2000000000L to "69279037",
            20000000000L to "65353130",
        )
        vectors.forEach { (time, code) ->
            assertEquals(code, Totp.code(rfcKey, Totp.stepAt(time), digits = 8), "time $time")
        }
    }

    @Test
    fun `a code matches now and one step either side, and no further`() {
        val time = 1_234_567_890L
        val now = Totp.code(rfcKey, Totp.stepAt(time))
        val before = Totp.code(rfcKey, Totp.stepAt(time) - 1)
        val after = Totp.code(rfcKey, Totp.stepAt(time) + 1)
        val stale = Totp.code(rfcKey, Totp.stepAt(time) - 3)
        assertTrue(Totp.matches(rfcKey, now, time))
        assertTrue(Totp.matches(rfcKey, before, time))
        assertTrue(Totp.matches(rfcKey, after, time))
        // The stale code is only a fair test if it differs from the three accepted ones.
        if (stale !in setOf(now, before, after)) assertFalse(Totp.matches(rfcKey, stale, time))
    }

    @Test
    fun `the space apps show between the halves is allowed, anything else is not a code`() {
        val time = 1_111_111_111L
        val code = Totp.code(rfcKey, Totp.stepAt(time))
        assertTrue(Totp.matches(rfcKey, code.take(3) + " " + code.drop(3), time))
        assertFalse(Totp.matches(rfcKey, "", time))
        assertFalse(Totp.matches(rfcKey, code.take(5), time))
        assertFalse(Totp.matches(rfcKey, code + "0", time))
        assertFalse(Totp.matches(rfcKey, "12a456", time))
    }

    @Test
    fun `base32 matches RFC 4648 section 10 without padding`() {
        assertEquals("", Totp.base32("".toByteArray()))
        assertEquals("MY", Totp.base32("f".toByteArray()))
        assertEquals("MZXQ", Totp.base32("fo".toByteArray()))
        assertEquals("MZXW6", Totp.base32("foo".toByteArray()))
        assertEquals("MZXW6YQ", Totp.base32("foob".toByteArray()))
        assertEquals("MZXW6YTB", Totp.base32("fooba".toByteArray()))
        assertEquals("MZXW6YTBOI", Totp.base32("foobar".toByteArray()))
    }

    @Test
    fun `base32 reads back what it wrote, however a person typed it`() {
        val secret = Totp.newSecret()
        assertContentEquals(secret, Totp.fromBase32(Totp.base32(secret)))
        assertContentEquals(secret, Totp.fromBase32(Totp.grouped(secret).lowercase()))
        assertContentEquals("foobar".toByteArray(), Totp.fromBase32("MZXW-6YTB-OI======"))
        assertNull(Totp.fromBase32("not base32!"))
        assertNull(Totp.fromBase32(""))
    }

    @Test
    fun `a new secret is 160 bits, which clears the 128 bit floor the server enforces`() {
        val secret = Totp.newSecret()
        assertEquals(20, secret.size)
        assertEquals(32, Totp.base32(secret).length)
        assertFalse(secret.contentEquals(Totp.newSecret()))
    }

    @Test
    fun `the otpauth address carries one issuer in both places and nothing a parser would split on`() {
        val secret = "foobar".toByteArray()
        assertEquals(
            "otpauth://totp/example.com:jo%40example.com?secret=MZXW6YTBOI&issuer=example.com" +
                "&algorithm=SHA1&digits=6&period=30",
            Totp.uri(secret, "jo@example.com", "example.com"),
        )
        val odd = Totp.uri(secret, "a:b@x.org", "My Mail: Home")
        assertTrue(odd.startsWith("otpauth://totp/My%20Mail%20Home:ab%40x.org?"), odd)
        assertTrue("&issuer=My%20Mail%20Home&" in odd, odd)
    }

    @Test
    fun `the key is shown in groups of four`() {
        assertEquals("MZXW 6YTB OI", Totp.grouped("foobar".toByteArray()))
    }
}
