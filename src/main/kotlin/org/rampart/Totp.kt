package org.rampart

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time codes, RFC 6238 over RFC 4226, for turning on two-step login.
 *
 * Rampart has to do this itself because Stalwart 0.16 does not. When an account's `otpUrl`
 * is set, the server stores whatever it is given and never asks for a code made from it.
 * A secret that was mistyped into an authenticator, or never scanned at all, would be
 * saved without complaint, and the next sign-in would lock the person out of their own
 * mailbox. So the code is checked here, against the secret Rampart made, before the
 * secret is sent anywhere.
 *
 * SHA-1, six digits and thirty seconds, because that is what every authenticator app
 * reads without asking and what the `totp-rs` crate Stalwart uses expects by default.
 */
internal object Totp {
    const val DIGITS = 6
    const val PERIOD_SECONDS = 30L

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * Twenty random bytes, which is 160 bits: the length RFC 4226 recommends, and above
     * the 128 bit floor `totp-rs` refuses to go under.
     */
    fun newSecret(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(20).also(random::nextBytes)

    /** RFC 4648 base32, upper case and unpadded, which is how every authenticator types it. */
    fun base32(bytes: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
    }

    /**
     * The same, read back. Spaces, dashes, padding and lower case are allowed because a
     * person copying a secret by hand writes it in groups. Null when it is not base32.
     */
    fun fromBase32(text: String): ByteArray? {
        val clean = text.uppercase().filter { it != ' ' && it != '-' && it != '=' }
        if (clean.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /** The code for one time step, RFC 4226 section 5.3, zero padded to [digits]. */
    fun code(secret: ByteArray, step: Long, digits: Int = DIGITS): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    /** Which thirty second step a moment falls in. */
    fun stepAt(epochSeconds: Long): Long = Math.floorDiv(epochSeconds, PERIOD_SECONDS)

    /**
     * Whether [typed] is the code for now, or for the step either side of it.
     *
     * One step of slack each way is what the server allows too, and it is what makes a
     * code typed in its last second, or read off a phone whose clock is a little out,
     * still count. Spaces are dropped because apps show the code as two groups of three.
     */
    fun matches(secret: ByteArray, typed: String, epochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        val code = typed.filter { !it.isWhitespace() }
        if (code.length != DIGITS || code.any { !it.isDigit() }) return false
        val now = stepAt(epochSeconds)
        return (-1L..1L).any { code(secret, now + it) == code }
    }

    /**
     * The `otpauth://` address an authenticator app scans, in the Key Uri Format.
     *
     * The label is `issuer:account` and the issuer is repeated as a parameter, because
     * `totp-rs` refuses a URL whose two issuers disagree and some apps read only one of
     * them. A colon inside either half would split the label in the wrong place, so it is
     * taken out rather than trusted to encoding.
     */
    fun uri(secret: ByteArray, account: String, issuer: String): String {
        val cleanIssuer = issuer.replace(":", "").trim()
        val cleanAccount = account.replace(":", "").trim()
        val label = if (cleanIssuer.isEmpty()) enc(cleanAccount) else enc(cleanIssuer) + ":" + enc(cleanAccount)
        return buildString {
            append("otpauth://totp/").append(label)
            append("?secret=").append(base32(secret))
            if (cleanIssuer.isNotEmpty()) append("&issuer=").append(enc(cleanIssuer))
            append("&algorithm=SHA1&digits=").append(DIGITS)
            append("&period=").append(PERIOD_SECONDS)
        }
    }

    /** Percent encoding with spaces as %20, which is what an otpauth label expects rather than a form's plus. */
    private fun enc(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

    /** A secret shown for typing by hand, in groups of four so it can be read aloud. */
    fun grouped(secret: ByteArray): String = base32(secret).chunked(4).joinToString(" ")
}
