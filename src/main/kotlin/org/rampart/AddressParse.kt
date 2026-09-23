package org.rampart

import jakarta.mail.internet.InternetAddress

/**
 * One address as someone typed it: a display name, which may be empty, and the mailbox.
 */
internal data class MailboxAddress(val name: String, val email: String)

/**
 * Splits a To or Cc line on commas and semicolons, and reads `Name <a@b>` apart.
 *
 * The comma inside `"Last, First" <a@b>` is part of the name, and so is a semicolon
 * sitting inside quotes. Jakarta's parser reads one address once it has been split
 * out; it does not treat `;` as a separator, which is why the split happens here first.
 */
internal fun parseAddressList(typed: String): List<MailboxAddress> {
    if (typed.isBlank()) return emptyList()
    return splitAddressList(typed).flatMap { token ->
        val parsed = runCatching { InternetAddress.parse(token, false) }.getOrNull().orEmpty()
        if (parsed.isEmpty()) {
            val bare = bareAddress(token)
            return@flatMap if (bare.isBlank()) emptyList() else listOf(MailboxAddress("", bare))
        }
        parsed.mapNotNull { address ->
            val email = address.address?.trim().orEmpty()
            if (email.isBlank()) return@mapNotNull null
            MailboxAddress(address.personal?.trim().orEmpty(), email)
        }
    }
}

/** The same addresses as Jakarta will put them on a MIME message. */
internal fun internetAddresses(typed: String): Array<InternetAddress> =
    parseAddressList(typed).map { parsed ->
        if (parsed.name.isBlank()) InternetAddress(parsed.email)
        else InternetAddress(parsed.email, parsed.name, "UTF-8")
    }.toTypedArray()

/**
 * Pieces of an address list, split on `,` and `;` that are not inside quotes or angle brackets.
 */
internal fun splitAddressList(typed: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var escaped = false
    var angle = 0
    for (ch in typed) {
        if (escaped) {
            current.append(ch)
            escaped = false
            continue
        }
        if (ch == '\\') {
            current.append(ch)
            escaped = true
            continue
        }
        if (ch == '"') {
            quoted = !quoted
            current.append(ch)
            continue
        }
        if (!quoted && ch == '<') {
            angle++
            current.append(ch)
            continue
        }
        if (!quoted && ch == '>' && angle > 0) {
            angle--
            current.append(ch)
            continue
        }
        if (!quoted && angle == 0 && (ch == ',' || ch == ';')) {
            val piece = current.toString().trim()
            if (piece.isNotEmpty()) out += piece
            current.clear()
            continue
        }
        current.append(ch)
    }
    val piece = current.toString().trim()
    if (piece.isNotEmpty()) out += piece
    return out
}
