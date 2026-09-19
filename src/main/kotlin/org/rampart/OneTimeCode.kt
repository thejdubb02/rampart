package org.rampart

/**
 * The code somebody is waiting for, found and handed over.
 *
 * A sign-in code is the one message you open in a hurry, copy four characters out of, and
 * never look at again. Every step of that is friction a mail client can remove: Gmail
 * shows the code on the row in the list and offers to copy it, so the message never has to
 * be opened at all, and it is one of the few genuinely loved small features in any mail
 * client.
 *
 * **There is no model anywhere near this.** It is a keyword and a number next to each
 * other, which is exactly what a one-time code is, and a local function is faster, free,
 * works offline, and cannot be confidently wrong in an interesting way.
 *
 * The whole difficulty is not finding codes. It is not finding things that are not codes:
 * an order number, a phone number, a price, a year, a tracking number and a flight number
 * are all digits in a sentence, and a client that offers to copy an order number as a
 * sign-in code is worse than one that offers nothing.
 */

/**
 * The one-time code in a message, or null when there is not obviously one.
 *
 * [subject] is checked first and separately, because a subject is short enough that a
 * number in it is almost always the code, and because half of these messages put it there
 * precisely so it can be read from a notification.
 */
internal fun oneTimeCode(subject: String, body: String): String? =
    codeIn(subject) ?: codeIn(body.take(BODY_SEARCHED))

/**
 * The same, for an open message, which has both parts of itself.
 *
 * The text part first, because it is already text. Where a sender wrote only HTML, the
 * markup comes off here rather than the code being hunted for between tags: a code inside
 * `<strong>847291</strong>` has a tag pressed against it on both sides and no word
 * boundary anywhere near it.
 */
internal fun oneTimeCode(subject: String, text: String?, html: String?): String? =
    oneTimeCode(subject, text?.takeIf { it.isNotBlank() } ?: plainTextOf(html))

private fun plainTextOf(html: String?): String =
    if (html.isNullOrBlank()) "" else org.jsoup.Jsoup.parse(html).text()

/**
 * How much of the body is looked at.
 *
 * A code is at the top or it is not the point of the message. Reading further finds the
 * order number in the footer of a receipt that happened to say "code" somewhere.
 */
private const val BODY_SEARCHED = 1200

private fun codeIn(text: String): String? {
    if (text.isBlank()) return null
    val flat = text.replace(' ', ' ')
    if (!SAYS_CODE.containsMatchIn(flat)) return null
    /*
     * Google's, which is the one everybody has seen, and the only branded shape worth a
     * case of its own: `G-123456`. The prefix is not part of what you type.
     */
    GOOGLE.find(flat)?.let { return it.groupValues[1] }
    // Judged where it was found rather than after normalising, because half the reasons a
    // number is not a code are in the characters either side of it.
    return CANDIDATE.findAll(flat).firstOrNull { plausible(it, flat) }?.let(::tidy)
}

/** What is put on the clipboard: no spaces or hyphens, because no code box wants them. */
private fun tidy(match: MatchResult): String = match.value.replace(" ", "").replace("-", "")

/**
 * Whether the message says it is sending a code at all.
 *
 * Checked before any number is looked at, because this is what keeps a receipt, a
 * statement and a delivery notification out. Without it, every message with a six digit
 * number in it offers to copy something.
 */
private val SAYS_CODE = Regex(
    """\b(
        one[- ]?time|single[- ]?use|
        verification|verify|verifying|
        confirmation|confirm|
        authentication|authenticate|
        security\s+code|access\s+code|login\s+code|sign[- ]?in\s+code|
        passcode|pass\s?code|otp|2fa|two[- ]factor|
        your\s+code|code\s+is|code:|enter\s+the\s+code|use\s+the\s+code|
        pin\s+is|pin:
    )\b""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS),
)

/** `G-123456`, which no other kind of number looks like. */
private val GOOGLE = Regex("""\bG-(\d{5,8})\b""")

/**
 * What a code can look like.
 *
 * Four to eight characters of digits and upper case letters, or two groups of three or
 * four with a space or a hyphen between them, which is how a longer one is usually
 * printed: `284 917`, `8NU-WJD`. Lower case is deliberately not matched, because `sunday`
 * is six letters and `monday` is another one.
 *
 * Having at least one digit in it is checked in [plausible] rather than here, so it
 * applies to every shape rather than to whichever branch it was written into.
 */
private val CANDIDATE = Regex("""\b(?:[A-Z0-9]{3,4}[ -][A-Z0-9]{3,4}|[A-Z0-9]{4,8})\b""")

/**
 * Whether a number that looked like a code actually is one.
 *
 * Everything here is a rule that exists because of something a real message contains.
 */
private fun plausible(match: MatchResult, text: String): Boolean {
    val candidate = tidy(match)
    if (candidate.length !in 4..8) return false
    // Letters alone are a word. Every real code has a digit in it somewhere.
    if (candidate.none { it.isDigit() }) return false
    // A year, which appears in the footer of very nearly every message ever sent.
    if (candidate.length == 4 && candidate.toIntOrNull() in 1900..2100) return false
    // All the same character is a placeholder in an example, not somebody's code.
    if (candidate.all { it == candidate[0] }) return false

    val before = text.substring(maxOf(0, match.range.first - 26), match.range.first)
    // A number the message named as something else: an order, a reference, a total.
    if (NOT_A_CODE.containsMatchIn(before.lowercase())) return false
    val runUp = before.trimEnd()
    // A price.
    if (runUp.endsWith("$") || runUp.endsWith("£") || runUp.endsWith("€")) return false
    /*
     * Part of a longer run of digits, which is what a phone number is.
     *
     * `020 7946 0958` offers three candidates and only the first is anywhere near the word
     * that would rule it out, so ruling out by the word alone let the last group through as
     * a code. A group of digits with another group directly in front of it is not one.
     */
    if (runUp.lastOrNull()?.isDigit() == true) return false

    val after = text.substring(minOf(text.length, match.range.last + 1))
    // A decimal, so an amount rather than a code.
    if (after.startsWith(".") && after.getOrNull(1)?.isDigit() == true) return false
    // More digits attached to it, same reasoning as the run-up.
    if (after.firstOrNull()?.isDigit() == true) return false
    return true
}

/** Words that name the number as something other than a code. */
private val NOT_A_CODE = Regex(
    """\b(
        order|invoice|receipt|account|reference|ref|ticket\s?\#|case|
        tracking|shipment|parcel|flight|booking|reservation|
        total|amount|balance|due|price|
        phone|tel|call|ext|extension|zip|postcode|
        version|build|port|suite|apt|unit
    )\b\s*(no\.?|number|\#|:)?\s*$""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS),
)
