package org.rampart

/**
 * What is wrong with a message, when something is.
 *
 * **The rule this file is built around: a warning that fires on honest mail is worse than
 * no warning**, because the next one is not believed either. Every signal here had to be
 * one that is close to never true of a real message, and the ones that are merely
 * interesting were deliberately demoted to [Detail] rows in Show details rather than made
 * into banners. A Reply-To on another domain is the clearest example: every mailing list
 * and every ticketing system on earth sets one, so on its own it means nothing.
 *
 * All of it is worked out on the machine from what the message already carries. No lookup,
 * no service, no model, so it works with everything else switched off.
 */
internal data class Warning(
    /** The headline, in words somebody can act on. Never an acronym. */
    val says: String,
    /** The working, for anyone who wants it. */
    val because: String,
)

/**
 * Everything worth warning about, worst first.
 *
 * [html] is the message's own HTML **before** it is cleaned. That matters: the cleaner
 * removes forms and inputs, which is correct for rendering and would hide the very thing
 * this is looking for.
 */
internal fun warningsFor(
    fromEmail: String,
    fromName: String,
    html: String?,
    authenticationResults: String?,
    replyTo: List<String> = emptyList(),
    /** Domains the reader deals with, so a lookalike of one of those is caught too. */
    known: Set<String> = emptySet(),
): List<Warning> = buildList {
    val domain = domainOf(fromEmail)

    punycodeWarning(domain)?.let(::add)
    lookalikeWarning(domain, known)?.let(::add)
    passwordWarning(html)?.let(::add)
    impersonationWarning(fromName, fromEmail)?.let(::add)
    replyToWarning(domain, replyTo, authenticationResults)?.let(::add)
}

/**
 * A domain written in another alphabet.
 *
 * `xn--` is how a non-ASCII domain travels, and it is entirely legitimate: it is also the
 * only way to register a domain that renders on screen as an exact copy of somebody else's.
 * There is no way to tell the two apart from the address alone, so this says what it sees
 * and lets the reader decide, rather than guessing.
 */
private fun punycodeWarning(domain: String): Warning? {
    if (!domain.split('.').any { it.startsWith("xn--") }) return null
    return Warning(
        "This address is not written in the alphabet it appears to be.",
        "$domain uses an encoding that lets a domain be spelled with letters from another " +
            "script, which can look identical to a familiar name on screen.",
    )
}

/**
 * A domain one character away from a well-known one.
 *
 * Only for a domain that is **not** the real one and is confusable with it after the
 * substitutions people actually use. An exact match is the real thing and says nothing, and
 * a domain that merely contains a brand name is not flagged: `paypal-invoices.example` is
 * a different shape of problem and catching it here would fire on every agency that has a
 * client's name in its domain.
 */
private fun lookalikeWarning(domain: String, known: Set<String>): Warning? {
    if (domain.isBlank()) return null
    val candidates = (BRANDS + known.map { it.lowercase() }).toSet()
    if (domain in candidates) return null
    val confused = confusable(domain)
    val real = candidates.firstOrNull { it != domain && confusable(it) == confused } ?: return null
    /*
     * The headline names the real domain and the working names the swapped character.
     *
     * Printing the two domains side by side and leaving the reader to spot the difference
     * is the first version of this and it was useless: the whole attack is that they look
     * identical on screen, so a warning that relies on seeing the difference fails exactly
     * where it is needed. Said in words, "i in place of l" cannot be misread.
     */
    return Warning(
        "This is not $real, though it is written to look like it.",
        "The address is on $domain, which is $real with ${spelling(domain, real)}.",
    )
}

/**
 * How one domain is spelled differently from another, in words.
 *
 * The common start and the common end are taken off and what is left is reported, which
 * handles a swap of the same length (`i` for `l`) and one that is not (`rn` for `m`) with
 * the same two lines.
 */
internal fun spelling(fake: String, real: String): String {
    var prefix = 0
    while (prefix < fake.length && prefix < real.length && fake[prefix] == real[prefix]) prefix++
    var suffix = 0
    while (
        suffix < fake.length - prefix && suffix < real.length - prefix &&
        fake[fake.length - 1 - suffix] == real[real.length - 1 - suffix]
    ) {
        suffix++
    }
    val changed = fake.substring(prefix, fake.length - suffix)
    val was = real.substring(prefix, real.length - suffix)
    if (changed.isEmpty() || was.isEmpty()) return "a different spelling"
    return "$changed in place of $was"
}

/**
 * A message asking for a password.
 *
 * Close to never legitimate, because a password field in an email does not work: almost no
 * mail client submits a form, so a real company has nothing to gain by putting one there
 * and every phishing kit has everything to gain by trying.
 */
private fun passwordWarning(html: String?): Warning? {
    val source = html ?: return null
    val password = Regex("""<input[^>]*type\s*=\s*["']?password""", RegexOption.IGNORE_CASE)
    if (!password.containsMatchIn(source)) return null
    return Warning(
        "This message asks for a password.",
        "It carries a password field. A real sender would send you to their own site " +
            "rather than collect one inside an email.",
    )
}

/**
 * A display name that is itself an address, and a different one.
 *
 * The oldest trick there is: the name reads `security@yourbank.com` and the message is from
 * somewhere else entirely, so every client that shows the name and hides the address shows
 * the attacker's choice of words. Only fires when the name really is an address, so an
 * ordinary "Dana Whitfield" never trips it.
 */
private fun impersonationWarning(fromName: String, fromEmail: String): Warning? {
    val claimed = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""").find(fromName)?.value?.lowercase() ?: return null
    val real = fromEmail.trim().lowercase()
    if (claimed == real) return null
    // One organisation talking about itself. Displaying noreply@company.com and sending
    // from bounce-1@mail.company.com is what every bulk sender does, and firing on it would
    // put a fraud warning on most of the mail anybody receives.
    if (sameOrganisation(domainOf(claimed), domainOf(real))) return null
    return Warning(
        "The name on this message is an address, and not the one it came from.",
        "It is displayed as $claimed and was sent by $real.",
    )
}

/**
 * Answers going somewhere else, on a message nothing vouched for.
 *
 * **Both halves are needed, and that is the whole point.** A Reply-To on another domain is
 * completely ordinary: a mailing list, a ticketing system and a no-reply sender all set
 * one, so on its own it would fire on honest mail every day and teach somebody to ignore
 * the banner. Paired with a message that failed authentication, or that nothing vouched
 * for, it is the shape of a conversation being quietly redirected.
 *
 * The Reply-To address is shown in Show details on every message regardless, which is where
 * something interesting but ordinary belongs.
 */
private fun replyToWarning(domain: String, replyTo: List<String>, authenticationResults: String?): Warning? {
    val other = replyTo.map { domainOf(it) }.filter { it.isNotBlank() && !sameOrganisation(it, domain) }
    if (other.isEmpty() || domain.isBlank()) return null
    val auth = authenticityOf(authenticationResults, null)
    if (auth.dmarc == Check.PASS || auth.dkim == Check.PASS) return null
    return Warning(
        "Replies would go to ${other.first()}, not to $domain.",
        "Nothing vouched for this message, and the address that would receive your answer " +
            "is on a different domain from the one that sent it.",
    )
}

/**
 * Whether two domains are one organisation talking about itself.
 *
 * A bounce domain, a sending subdomain and the domain in the From line are routinely
 * different names under the same registration, so treating them as different senders is
 * how a warning ends up on ordinary mail.
 */
internal fun sameOrganisation(a: String, b: String): Boolean =
    a.isNotBlank() && b.isNotBlank() && (a == b || a.endsWith(".$b") || b.endsWith(".$a"))

/** The domain of an address, lowercased. Empty when there is not one. */
internal fun domainOf(address: String): String =
    address.substringAfterLast('<').substringBefore('>').trim()
        .substringAfterLast('@').trim().lowercase()

/**
 * A domain reduced to what it looks like rather than what it is.
 *
 * The substitutions are the ones that work on a screen at normal size: a capital I and a
 * lowercase l and a one are one shape, rn is m, vv is w, and a zero is an O. Reducing two
 * domains this way and comparing is what catches `paypaI.com` and `rnicrosoft.com`, which
 * no amount of string distance does, because they differ from the real thing by characters
 * that are not visibly different at all.
 */
internal fun confusable(domain: String): String {
    val lowered = domain.lowercase()
    val out = StringBuilder(lowered.length)
    var at = 0
    while (at < lowered.length) {
        val two = if (at + 1 < lowered.length) lowered.substring(at, at + 2) else ""
        when {
            two == "rn" -> { out.append('m'); at += 2 }
            two == "vv" -> { out.append('w'); at += 2 }
            two == "cl" -> { out.append('d'); at += 2 }
            else -> {
                out.append(
                    when (val ch = lowered[at]) {
                        'l', '1', 'i', '|' -> 'i'
                        '0' -> 'o'
                        '5' -> 's'
                        else -> ch
                    },
                )
                at++
            }
        }
    }
    return out.toString()
}

/**
 * The names worth impersonating.
 *
 * Short on purpose. Every entry is a domain somebody is actively phished with; a long list
 * of every brand in existence would raise the chance of a false match without raising the
 * chance of catching anything real, and a false match here accuses somebody of fraud.
 */
private val BRANDS = setOf(
    "paypal.com", "microsoft.com", "office.com", "outlook.com", "apple.com", "icloud.com",
    "google.com", "gmail.com", "amazon.com", "amazon.co.uk", "netflix.com", "dropbox.com",
    "docusign.com", "adobe.com", "linkedin.com", "facebook.com", "instagram.com", "x.com",
    "stripe.com", "coinbase.com", "binance.com", "chase.com", "wellsfargo.com", "hsbc.com",
    "barclays.co.uk", "lloydsbank.com", "natwest.com", "santander.co.uk", "hmrc.gov.uk",
    "irs.gov", "usps.com", "royalmail.com", "dhl.com", "fedex.com", "ups.com",
    "github.com", "gitlab.com", "slack.com", "zoom.us", "shopify.com", "squareup.com",
    "xero.com", "quickbooks.com", "intuit.com", "cloudflare.com", "godaddy.com",
)
