package org.rampart

/**
 * The headers that carry a verdict somebody else already reached about a message.
 *
 * Asked for by name on JMAP and picked out of the header block on IMAP, and kept on the
 * [Body] as the topmost copy of each. Topmost because every hop prepends its own, so the
 * first one is the one our server wrote and the last one is whatever the sender typed.
 *
 * `X-Spam-Result` is Stalwart's own list of spam filter findings, which is where its
 * phishing verdict lives; see [serverPhishing]. The rest are antivirus headers, and none
 * of them is written by Stalwart itself. Read against Stalwart 0.16.24: the spam filter
 * writes `X-Spam-Result`, `X-Spam-Score` and `X-Spam-LLM` in
 * `crates/spam-filter/src/analysis/score.rs`, message ingest writes `X-Spam-Status` in
 * `crates/email/src/message/ingest.rs`, and nothing in the tree writes a virus header or
 * sets the status the Sieve `virustest` extension reads. Stalwart has no scanner of its own.
 * What it does have is milter support, and ClamAV, Amavis and Rspamd are how a Stalwart
 * operator adds one, so these are the headers those write.
 */
internal val VERDICT_HEADERS = listOf(
    "X-Spam-Result",
    // clamav-milter: "Clean", or "Infected (Eicar-Test-Signature)".
    "X-Virus-Status",
    // Written by nearly every scanner that looked at a message, whatever it found.
    "X-Virus-Scanned",
    // Rspamd's milter headers module, and only when it found something.
    "X-Virus",
    // Amavis: "INFECTED, message contains virus: Eicar-Signature".
    "X-Amavis-Alert",
)

/**
 * What a virus scanner on the way in said about a message.
 *
 * [found] is the name the scanner gave what it found, or null when it found nothing.
 * [scanner] is who did the looking, when a header says so, which is the only thing a
 * clean verdict can usefully show beside it.
 */
internal data class VirusVerdict(
    val infected: Boolean,
    val found: String?,
    val scanner: String?,
) {
    /** The value beside the chip in Show details. */
    val says: String
        get() = when {
            infected -> found ?: "a virus"
            else -> scanner ?: "clean"
        }
}

/**
 * The scanner's verdict from [headers], or null when no scanner wrote one.
 *
 * Null is the ordinary answer and draws nothing, because most servers do not scan and a
 * chip saying so on every message is a chip nobody reads.
 *
 * A clean verdict is only as good as the server it came from: a sender can write
 * `X-Virus-Status: Clean` into their own message, and a server with no scanner passes it
 * through untouched. That is why only the topmost copy is read (see [VERDICT_HEADERS]) and
 * why a clean result is a line in Show details rather than anything louder. An infected
 * verdict has no such problem, since nobody forges a warning against their own mail.
 */
internal fun virusVerdictOf(headers: Map<String, String>): VirusVerdict? {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.replace(Regex("\\r?\\n[ \\t]+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

    val status = header("X-Virus-Status")
    val rspamd = header("X-Virus")
    val amavis = header("X-Amavis-Alert")
    val scanned = header("X-Virus-Scanned")
    val scanner = scanned?.let(::scannerName)

    // Amavis says INFECTED for a virus and BANNED for a file type the operator refused;
    // only the first is a virus, and calling a blocked .exe one would be a false alarm.
    if (amavis != null && amavis.startsWith("INFECTED", ignoreCase = true)) {
        return VirusVerdict(true, amavis.substringAfter(":", "").trim().ifEmpty { null }, scanner ?: "Amavis")
    }
    // Rspamd only writes this header when something was found, and the value is the name.
    if (rspamd != null) {
        return VirusVerdict(true, rspamd.substringBefore(',').trim().ifEmpty { null }, scanner ?: "Rspamd")
    }
    if (status != null) {
        val word = status.substringBefore('(').substringBefore(',').trim().lowercase()
        val named = Regex("\\(([^)]+)\\)").find(status)?.groupValues?.get(1)?.trim()
        when (word) {
            "infected", "yes", "virus" -> return VirusVerdict(true, named, scanner)
            "clean", "no", "ok" -> return VirusVerdict(false, null, scanner)
        }
    }
    // Scanned and nothing else said: every scanner that finds something says so in one of
    // the headers above, so a bare "scanned" is a clean result.
    if (scanner != null) return VirusVerdict(false, null, scanner)
    return null
}

/**
 * Who scanned it, without the host it ran on.
 *
 * The header reads "clamav-milter 1.0.1 at mx.example.org" or "amavisd-new at example.org",
 * and the host name is the least useful part of that to somebody reading their mail.
 */
private fun scannerName(scanned: String): String? {
    val name = scanned.substringBefore(" at ").removePrefix("by ").trim()
    return name.ifEmpty { null }
}

/**
 * The banner for a message the scanner flagged, and nothing for one it did not.
 *
 * A banner rather than only a chip in Show details, because this is the one verdict where
 * the reader is about to do something with the answer: open an attachment.
 */
internal fun virusWarning(verdict: VirusVerdict?): Warning? {
    if (verdict == null || !verdict.infected) return null
    val what = verdict.found?.let { "It reported $it." } ?: "It did not name what it found."
    val who = verdict.scanner?.let { "The virus scanner on your mail server ($it) flagged this message." }
        ?: "The virus scanner on your mail server flagged this message."
    return Warning(
        "Your mail server found a virus in this message.",
        "$who $what Do not open its attachments.",
    )
}
