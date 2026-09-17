package org.rampart

/** How to get off a list, or null when the message does not say. */
internal data class Unsubscribe(
    /** An https URL to open, when there is one. */
    val url: String?,
    /** An address to send an empty message to, when there is one. */
    val mailto: String?,
    /** The subject that mailto asks for, when it names one. Usually "unsubscribe". */
    val mailtoSubject: String?,
    /** True when the sender supports RFC 8058 one-click, so no browser is needed. */
    val oneClick: Boolean,
)

/**
 * @param listUnsubscribe the raw List-Unsubscribe header value, or null
 * @param listUnsubscribePost the raw List-Unsubscribe-Post header value, or null
 */
internal fun unsubscribeFrom(listUnsubscribe: String?, listUnsubscribePost: String?): Unsubscribe? {
    if (listUnsubscribe.isNullOrEmpty()) return null

    // Folded headers put newlines between the bracketed entries. Reading by
    // angle brackets, not by commas, is what keeps a comma in a query string
    // from splitting one URL into two.
    var https: String? = null
    var http: String? = null
    var mailto: String? = null
    var mailtoSubject: String? = null

    var i = 0
    val header = listUnsubscribe
    while (i < header.length) {
        val start = header.indexOf('<', i)
        if (start < 0) break
        val end = header.indexOf('>', start + 1)
        // An unclosed bracket is junk, not a reason to throw, and anything after
        // it is not a complete entry we can trust.
        if (end < 0) break
        i = end + 1
        val entry = header.substring(start + 1, end)
        if (!isUsableEntry(entry)) continue

        when {
            entry.startsWith("https://", ignoreCase = true) -> {
                if (https == null && entry.length > "https://".length) https = entry
            }
            entry.startsWith("http://", ignoreCase = true) -> {
                if (http == null && entry.length > "http://".length) http = entry
            }
            entry.startsWith("mailto:", ignoreCase = true) -> {
                if (mailto == null) {
                    val parsed = mailtoOf(entry)
                    if (parsed != null) {
                        mailto = parsed.first
                        mailtoSubject = parsed.second
                    }
                }
            }
            // ftp:, javascript:, file:, data:, a bare word: nothing we will open.
        }
    }

    val url = https ?: http
    if (url == null && mailto == null) return null
    // One-click POSTs an identifying token. Doing that over plain http would
    // put the token on the wire in the clear, so http is a link, not one-click.
    val oneClick = https != null && isOneClick(listUnsubscribePost)
    return Unsubscribe(url, mailto, mailtoSubject, oneClick)
}

/**
 * Whitespace, quotes or a second angle bracket inside an entry are how a payload
 * hides a second scheme after a plausible https prefix. Dropping the entry is
 * safer than stripping the bad characters and keeping the rest.
 *
 * A NUL (or any other control) is how a C API silently truncates the rest of
 * the string, so those are dropped too rather than returned still inside the URL.
 */
private fun isUsableEntry(entry: String): Boolean {
    if (entry.isEmpty()) return false
    for (ch in entry) {
        if (ch == '<' || ch == '>' || ch == '"' || ch == '\'') return false
        if (ch.isWhitespace() || ch.isISOControl()) return false
    }
    return true
}

private fun isOneClick(post: String?): Boolean {
    if (post.isNullOrEmpty()) return false
    val compact = buildString(post.length) {
        for (ch in post) if (!ch.isWhitespace()) append(ch.lowercaseChar())
    }
    return "list-unsubscribe=one-click" in compact
}

private fun mailtoOf(entry: String): Pair<String, String?>? {
    val rest = entry.substring("mailto:".length)
    val q = rest.indexOf('?')
    val addressRaw = if (q < 0) rest else rest.substring(0, q)
    if (addressRaw.isEmpty()) return null
    val address = percentDecode(addressRaw)
    // A percent-encoded CR in the address would become a second header on the
    // message we send, so the whole mailto is dropped rather than decoded onto To.
    if (address.isEmpty() || !isUsableEntry(address)) return null
    var subject: String? = null
    if (q >= 0) {
        for (part in rest.substring(q + 1).split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (!part.substring(0, eq).equals("subject", ignoreCase = true)) continue
            val decoded = percentDecode(part.substring(eq + 1))
            if (decoded.any { it.isISOControl() }) return null
            subject = decoded
            break
        }
    }
    return address to subject
}

/** Truncated or illegal % sequences stay as written, so a hostile header cannot throw. */
private fun percentDecode(raw: String): String {
    if ('%' !in raw) return raw
    val out = ArrayList<Byte>(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length) {
            val hi = hexValue(raw[i + 1])
            val lo = hexValue(raw[i + 2])
            if (hi >= 0 && lo >= 0) {
                out.add(((hi shl 4) or lo).toByte())
                i += 3
                continue
            }
        }
        if (c.code < 128) {
            out.add(c.code.toByte())
        } else {
            for (b in c.toString().toByteArray(Charsets.UTF_8)) out.add(b)
        }
        i++
    }
    return String(ByteArray(out.size) { out[it] }, Charsets.UTF_8)
}

private fun hexValue(ch: Char): Int = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    in 'A'..'F' -> ch - 'A' + 10
    else -> -1
}

/**
 * Tells the sender's own server to take this address off the list, without a browser.
 *
 * RFC 8058: an empty POST with exactly this body, to the https address the message named.
 * Only ever called for [Unsubscribe.oneClick], which is only true over https, because the
 * URL carries a token that identifies the reader and putting that on the wire in the clear
 * is the thing the scheme check exists to prevent.
 *
 * Returns whether the server accepted it. A refusal is not an error worth a dialog: the
 * link is still there to open by hand.
 */
internal fun oneClickPost(url: String): Boolean = runCatching {
    val response = java.net.http.HttpClient.newBuilder()
        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
        .connectTimeout(java.time.Duration.ofSeconds(8))
        .build()
        .send(
            java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(java.time.Duration.ofSeconds(20))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("List-Unsubscribe=One-Click"))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.discarding(),
        )
    response.statusCode() in 200..299
}.getOrDefault(false)
