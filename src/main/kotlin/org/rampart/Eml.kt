package org.rampart

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

/**
 * The headers, in the order and the spelling they were written in.
 *
 * Both are kept on purpose: the same name appears many times on a real message, Received
 * and Authentication-Results most of all, and the order is what says which hop wrote which.
 */
internal fun headersOf(raw: String): List<Pair<String, String>> {
    val headers = mutableListOf<Pair<String, String>>()
    val lines = raw.lines()
    for (line in lines) {
        // The first empty line ends the headers. Past it is the body, and a body line that
        // happens to contain a colon is not a header.
        if (line.isEmpty()) break
        if (line.startsWith(" ") || line.startsWith("\t")) {
            if (headers.isNotEmpty()) {
                val lastIdx = headers.size - 1
                val (lastName, lastValue) = headers[lastIdx]
                val foldedPart = line.trim()
                // A continuation line belongs to the header above it, joined by the single
                // space the fold replaced.
                headers[lastIdx] = Pair(lastName, if (lastValue.isEmpty()) foldedPart else "$lastValue $foldedPart")
            }
        } else {
            val colonIndex = line.indexOf(':')
            if (colonIndex != -1) {
                val name = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                headers.add(Pair(name, value))
            }
            // A line with no colon is skipped. Real mail arrives with a "From " envelope
            // line on top and it is not a header.
        }
    }
    return headers
}

/**
 * Everything after the blank line that ends the headers.
 *
 * Both line endings occur in the wild, sometimes in the same message, and a message with
 * no blank line at all is all headers and no body rather than an error.
 */
internal fun bodyOf(raw: String): String {
    if (raw.startsWith("\r\n")) return raw.substring(2)
    if (raw.startsWith("\n")) return raw.substring(1)
    val crlf = raw.indexOf("\r\n\r\n")
    val lf = raw.indexOf("\n\n")
    return when {
        crlf >= 0 && (lf < 0 || crlf < lf) -> raw.substring(crlf + 4)
        lf >= 0 -> raw.substring(lf + 2)
        else -> ""
    }
}

/** A filename for saving the message, dated so a folder of them sorts by date. */
internal fun emlName(subject: String, receivedAt: String): String {
    val stem = if (subject.isBlank()) "message" else subject.take(60)
    // A date we cannot read is no prefix, rather than a wrong one or a thrown exception.
    val prefix = runCatching {
        DATE.format(Instant.parse(receivedAt)) + " "
    }.getOrDefault("")
    // A subject is somebody else's text, so the name it produces goes through the same
    // check as an attachment's: no path separators, nothing that escapes the folder.
    val safeStem = safeFileName(prefix + stem)
    return "$safeStem.eml"
}
