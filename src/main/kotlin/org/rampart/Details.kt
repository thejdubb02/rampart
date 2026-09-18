package org.rampart

/**
 * What the headers say about a message, for the panel behind Show details.
 *
 * [Authenticity] already reads the same headers and reduces them to one line of warning,
 * which is the right thing on an ordinary message: a badge shown every time is a badge
 * nobody reads. This is the other half, for when somebody wants the working rather than
 * the verdict, and it is the difference between "this looks wrong" and being able to say
 * which check failed and on whose domain.
 */
internal data class Detail(val label: String, val value: String, val verdict: Check = Check.MISSING)

/**
 * SPF, DKIM and DMARC as they were actually recorded, each with what it was checked
 * against.
 *
 * Only the topmost Authentication-Results line, for the reason [authenticityOf] gives:
 * every hop prepends its own, ours is first, and a forwarder further down can neither
 * vouch for a message our server could not verify nor condemn one it was happy with.
 */
internal fun authChecks(authenticationResults: String?): List<Detail> {
    val line = topLine(authenticationResults)
    return listOf(
        Detail("SPF", propertyOf(line, SPF_DOMAIN), verdictOf(line, "spf")),
        Detail("DKIM", propertyOf(line, DKIM_DOMAIN), verdictOf(line, "dkim")),
        Detail("DMARC", propertyOf(line, DMARC_POLICY).let { if (it.isBlank()) "" else "policy: $it" },
            verdictOf(line, "dmarc")),
    )
}

/**
 * Who handed the message to our server, from the topmost Received line.
 *
 * The bottom of the stack is the sender's own claim about itself and can say anything; the
 * top was written by our own server about the host it was actually talking to, which is
 * the only hop worth showing. A name and an address that disagree is the ordinary shape of
 * mail that is not what it says it is.
 */
internal fun senderHost(received: List<String>): Detail? {
    val line = received.firstOrNull()?.let(::unfold) ?: return null
    val address = IP.find(line)?.groupValues?.get(1)?.trim()
    val name = HELO.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() && it != address }
    if (address == null && name == null) return null
    return Detail(
        label = "Reverse DNS",
        value = listOfNotNull(name, address).joinToString(" · "),
        // A hop that named itself and resolves to an address is as far as a header can
        // take this. Anything stronger is a lookup, and a lookup on every message opened
        // is a request per message to somebody else's resolver.
        verdict = if (name != null && address != null) Check.PASS else Check.MISSING,
    )
}

/** When the message says it was sent, and when it actually landed. The gap is the point. */
internal fun handling(sentAt: String?, receivedAt: String?): List<Detail> = listOfNotNull(
    sentAt?.takeIf { it.isNotBlank() }?.let { Detail("Sent", it) },
    receivedAt?.takeIf { it.isNotBlank() }?.let { Detail("Received", it) },
)

/**
 * A size a person reads, from a byte count.
 *
 * Powers of 1024 with the short names, which is what every mail client shows and what the
 * number in a mailbox quota is counted in.
 */
internal fun humanBytes(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

/**
 * The recipients that fit, and how many are left over.
 *
 * A message to nine people should not push the body off the screen, and a bare count with
 * no names is no use either. Two names and a number is what every client settled on.
 */
internal fun shownRecipients(all: List<String>, upTo: Int = 2): Pair<List<String>, Int> {
    val kept = all.filter { it.isNotBlank() }
    return kept.take(upTo) to (kept.size - minOf(kept.size, upTo))
}

/**
 * The domain a message was actually sent through, when that is not the one it claims.
 *
 * "via" is worth saying because it is the ordinary explanation for mail that looks odd and
 * is not: a newsletter sent through a provider, or an alias delivered from somewhere else.
 * Null when the sending domain matches the From address, which is the normal case and
 * needs no chip.
 */
internal fun sentVia(fromEmail: String, authenticationResults: String?): String? {
    val line = topLine(authenticationResults)
    val envelope = propertyOf(line, SPF_DOMAIN).substringAfterLast('@').lowercase()
    val claimed = fromEmail.substringAfterLast('@').trim().lowercase()
    if (envelope.isBlank() || claimed.isBlank()) return null
    // Same registrable-looking tail is not "via" anything: mail from bounces.example.com
    // for an address at example.com is one organisation talking about itself.
    if (envelope == claimed || envelope.endsWith(".$claimed") || claimed.endsWith(".$envelope")) return null
    return envelope
}

private fun unfold(header: String) = header.replace(Regex("\\r?\\n[ \\t]+"), " ")

private fun topLine(header: String?) =
    unfold(header.orEmpty()).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

private fun verdictOf(line: String, mechanism: String): Check {
    val found = Regex("(?i)\\b$mechanism\\s*=\\s*([a-zA-Z]+)").find(line)?.groupValues?.get(1)?.lowercase()
    return when (found) {
        "pass" -> Check.PASS
        "fail", "softfail", "permerror", "temperror", "policy" -> Check.FAIL
        else -> Check.MISSING
    }
}

private fun propertyOf(line: String, pattern: Regex) =
    pattern.find(line)?.groupValues?.get(1)?.trim()?.trim('"')?.removeSuffix(";").orEmpty()

private val SPF_DOMAIN = Regex("(?i)smtp\\.(?:mailfrom|helo)\\s*=\\s*([^\\s;]+)")
private val DKIM_DOMAIN = Regex("(?i)header\\.(?:d|i)\\s*=\\s*([^\\s;]+)")
private val DMARC_POLICY = Regex("(?i)\\bp\\s*=\\s*([^\\s;]+)")
private val IP = Regex("\\[?((?:\\d{1,3}\\.){3}\\d{1,3})]?")
private val HELO = Regex("(?i)\\bfrom\\s+([A-Za-z0-9._-]+\\.[A-Za-z]{2,})")
