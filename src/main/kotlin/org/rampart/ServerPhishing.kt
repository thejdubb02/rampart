package org.rampart

/**
 * The findings in Stalwart's `X-Spam-Result` header, by name, with the score each one got.
 *
 * The header is one finding per line, lowest score first, written in
 * `crates/spam-filter/src/analysis/score.rs` of Stalwart 0.16.24 as
 * `TAG (score)` separated by a comma and a fold. Every finding is listed, including ones
 * that scored nothing, apart from the operator's own `X_` tags that scored zero.
 *
 * Null when there is no such header, which is the whole of the question [serverPhishing]
 * asks: whether the server looked at all. An empty map is a server that looked and found
 * nothing worth a line.
 */
internal fun spamFindings(spamResult: String?): Map<String, Double>? {
    val text = spamResult?.replace(Regex("\\r?\\n[ \\t]*"), " ")?.trim() ?: return null
    if (text.isEmpty()) return null
    val finding = Regex("""([A-Za-z0-9_]+)\s*\(\s*([-+]?\d+(?:\.\d+)?)\s*\)""")
    return finding.findAll(text).associate { it.groupValues[1].uppercase() to it.groupValues[2].toDouble() }
}

/**
 * Stalwart's names for the phishing findings, as written by its spam filter.
 *
 * Each is the name passed to `add_tag` in Stalwart 0.16.24's `crates/spam-filter`:
 * `SPOOF_DISPLAY_NAME` in `analysis/from.rs`, `SPOOF_REPLYTO` in `analysis/replyto.rs`,
 * `HOMOGRAPH_URL` and `MIXED_CHARSET_URL` in `analysis/url.rs`, and `PHISHING` in
 * `analysis/html.rs`.
 */
internal object StalwartTags {
    /** The display name holds an address on another domain from the one that sent it. */
    const val SPOOF_DISPLAY_NAME = "SPOOF_DISPLAY_NAME"

    /** Replies go to another domain, and the message is not from a list or a known correspondent. */
    const val SPOOF_REPLYTO = "SPOOF_REPLYTO"

    /** A link's host is written in another script and a lookalike of it exists. */
    const val HOMOGRAPH_URL = "HOMOGRAPH_URL"

    /** A link's host mixes letters from more than one script. */
    const val MIXED_CHARSET_URL = "MIXED_CHARSET_URL"

    /** A link's text names one site and the link goes to another. */
    const val PHISHING = "PHISHING"
}

/**
 * The warnings to show, deferring to the server wherever it already looked.
 *
 * **Stalwart's spam filter checks the same things [warningsFor] does**, and it checks
 * them with more to go on: the envelope, the connecting host, DNS. So when the message
 * carries the server's findings, the server's word is used for the signals it covers,
 * and the local check for those signals is not run at all. When there are no findings,
 * which is an IMAP account on another server, a message that skipped the filter, or a
 * server that is not Stalwart, [warningsFor] runs exactly as it always did.
 *
 * Two local checks run either way, because the server has nothing that corresponds to
 * them: a password field in the HTML, and a sender domain that is a lookalike of one the
 * reader deals with. Stalwart's homograph check is on links, not on the sender, and it
 * has no idea who the reader's correspondents are.
 *
 * The rule from [warningsFor] still holds and still decides what is said: a warning that
 * fires on honest mail is worse than none. `PHISHING` fires on any link whose text reads
 * as one address and whose target is another, which is every newsletter that sends its
 * clicks through a tracking redirect, and `SPOOF_REPLYTO` fires on a Reply-To that is on
 * another domain. So both of those only speak on a message nothing vouched for, the same
 * condition the local Reply-To check has always needed.
 */
internal fun trustWarnings(
    fromEmail: String,
    fromName: String,
    html: String?,
    authenticationResults: String?,
    replyTo: List<String> = emptyList(),
    known: Set<String> = emptySet(),
    spamResult: String? = null,
): List<Warning> {
    val findings = spamFindings(spamResult)
        ?: return warningsFor(fromEmail, fromName, html, authenticationResults, replyTo, known)
    val local = warningsFor(fromEmail, fromName, html, authenticationResults, replyTo, known, only = LOCAL_ONLY)
    return serverPhishing(findings, fromEmail, fromName, replyTo, authenticationResults) + local
}

/** The local signals Stalwart has no counterpart for. See [trustWarnings]. */
internal val LOCAL_ONLY = setOf(TrustSignal.PUNYCODE_SENDER, TrustSignal.LOOKALIKE_SENDER, TrustSignal.PASSWORD_FIELD)

/**
 * The warnings the server's findings amount to, worst first.
 *
 * The wording follows [warningsFor]: the headline in words somebody can act on, and the
 * working underneath, which says it was the server that found it.
 */
internal fun serverPhishing(
    findings: Map<String, Double>,
    fromEmail: String,
    fromName: String,
    replyTo: List<String>,
    authenticationResults: String?,
): List<Warning> = buildList {
    val auth = authenticityOf(authenticationResults, null)
    val vouched = auth.dmarc == Check.PASS || auth.dkim == Check.PASS
    val domain = domainOf(fromEmail)

    if (StalwartTags.HOMOGRAPH_URL in findings || StalwartTags.MIXED_CHARSET_URL in findings) {
        add(
            Warning(
                "A link in this message is not written in the alphabet it appears to be.",
                "Your mail server found a link whose address uses letters from another " +
                    "script, which can look identical to a familiar site on screen.",
            ),
        )
    }
    if (StalwartTags.SPOOF_DISPLAY_NAME in findings) {
        val claimed = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""").find(fromName)?.value?.lowercase()
        add(
            Warning(
                "The name on this message is an address, and not the one it came from.",
                if (claimed != null) {
                    "Your mail server found it displayed as $claimed and sent by ${fromEmail.trim().lowercase()}."
                } else {
                    "Your mail server found an address in the sender's name on a different " +
                        "domain from the one that sent it."
                },
            ),
        )
    }
    if (!vouched && StalwartTags.PHISHING in findings) {
        add(
            Warning(
                "A link in this message goes somewhere other than where it says.",
                "Your mail server found a link whose text names one site and whose address " +
                    "is another, on a message nothing vouched for.",
            ),
        )
    }
    if (!vouched && StalwartTags.SPOOF_REPLYTO in findings) {
        val other = replyTo.map { domainOf(it) }.firstOrNull { it.isNotBlank() && !sameOrganisation(it, domain) }
        add(
            Warning(
                if (other != null && domain.isNotBlank()) {
                    "Replies would go to $other, not to $domain."
                } else {
                    "Replies to this message would go to another domain."
                },
                "Your mail server found the reply address on a different domain from the " +
                    "sender, and nothing vouched for this message.",
            ),
        )
    }
}
