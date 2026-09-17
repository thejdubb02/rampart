package org.rampart

internal enum class Check { PASS, FAIL, MISSING }

internal data class Authenticity(
    val spf: Check,
    val dkim: Check,
    val dmarc: Check,
    /** The spam score the server gave it, when it gave one. */
    val spamScore: Double?,
    /** One short line, in words somebody can act on. Never an acronym. */
    val summary: String,
    /** True when this is worth showing at all. A clean message says nothing. */
    val worthShowing: Boolean,
)

/**
 * @param authenticationResults the raw Authentication-Results header, or null
 * @param spamStatus the raw X-Spam-Status or X-Spam-Result header, or null
 */
internal fun authenticityOf(authenticationResults: String?, spamStatus: String?): Authenticity {
    // A header folded across lines would otherwise hide a result in the middle of a
    // keyword, and a result nobody finds reads as one that was never there.
    val unfoldedAuth = authenticationResults?.replace(Regex("\\r?\\n[ \\t]+"), " ") ?: ""
    val unfoldedSpam = spamStatus?.replace(Regex("\\r?\\n[ \\t]+"), " ") ?: ""

    val spfRegex = Regex("(?i)\\bspf\\s*=\\s*([a-zA-Z]+)")
    val dkimRegex = Regex("(?i)\\bdkim\\s*=\\s*([a-zA-Z]+)")
    val dmarcRegex = Regex("(?i)\\bdmarc\\s*=\\s*([a-zA-Z]+)")

    val spf = parseMechanism(unfoldedAuth, spfRegex)
    val dkim = parseMechanism(unfoldedAuth, dkimRegex)
    val dmarc = parseMechanism(unfoldedAuth, dmarcRegex)
    val spamScore = parseSpamScore(unfoldedSpam)

    /*
     * An ordinary message gets no badge. Something shown on every message is something
     * nobody reads, and then it is not there on the one that matters.
     *
     * Requiring all three to pass was the obvious rule and it was wrong. Measured against
     * a real mailbox it would have marked every message: mail relayed through Google
     * arrives with spf=none, because the sending host is Google's and the SPF record
     * belongs to the sender's own domain. DKIM is what vouches for those, and DMARC passes
     * on either one. So the question is whether anything vouches for this message and
     * whether anything actually failed, not whether every mechanism scored.
     */
    val vouched = dmarc == Check.PASS || dkim == Check.PASS
    val failed = spf == Check.FAIL || dkim == Check.FAIL || dmarc == Check.FAIL
    /*
     * No Authentication-Results at all means our server never saw this message arrive,
     * which is true of everything in Sent and Drafts. Measured on a real mailbox, treating
     * that as unproven put a badge on one message in three, most of them Justin's own.
     * Nothing to check is not the same as something to worry about.
     */
    val checked = !unfoldedAuth.isBlank()
    val worthShowing = checked && (failed || !vouched || (spamScore != null && spamScore >= 5.0))

    val summary = when {
        // DMARC first, because it is the one that speaks for the address in the From line,
        // which is the only address a reader ever sees.
        dmarc == Check.FAIL -> "This did not come from the address it says it did."

        dkim == Check.FAIL -> "This message was changed on the way here, or is not from that sender."

        // Said more carefully than DMARC. SPF fails on perfectly honest mail that was
        // forwarded, because the forwarder is not a server the sender listed, so calling
        // this forgery would cry wolf on every message from a mailing list.
        spf == Check.FAIL -> "This reached us through a server that sender does not normally use."

        spamScore != null && spamScore >= 5.0 -> "The server thinks this is spam."

        // Missing is not failure. A sender who never set any of this up has not done
        // anything wrong, and saying they failed would be a lie about them. But if nothing
        // at all vouches for the message, that is worth saying plainly.
        !vouched -> "Nothing here proves who sent this."

        else -> "Checks passed."
    }

    return Authenticity(
        spf = spf,
        dkim = dkim,
        dmarc = dmarc,
        spamScore = spamScore,
        summary = summary,
        worthShowing = worthShowing,
    )
}

/**
 * The first definite result wins. Every hop prepends its own Authentication-Results, so
 * the topmost is the one our own server wrote, and a forwarder appending a flattering
 * copy of its own further down cannot overrule it.
 */
private fun parseMechanism(text: String, regex: Regex): Check {
    for (match in regex.findAll(text)) {
        val value = match.groupValues[1].lowercase()
        if (value == "pass") return Check.PASS
        if (value == "fail") return Check.FAIL
    }
    return Check.MISSING
}

/** A score that will not parse is no score, rather than a zero that reads as a clean bill. */
private fun parseSpamScore(text: String): Double? {
    val scoreRegex = Regex("(?i)\\bscore\\s*=\\s*([-+]?\\d+(?:\\.\\d+)?)")
    val match = scoreRegex.find(text) ?: return null
    return match.groupValues[1].toDoubleOrNull()
}
