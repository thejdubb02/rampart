package org.rampart

/**
 * The name to put on a row, and the address underneath it when that adds anything.
 *
 * A blank name, or a name that is only the address repeated, is one line. Drawing
 * the address under itself is how a no-reply row reads twice, and a list row that
 * starts with the address and then the same address again runs out of room.
 */
internal fun displaySender(name: String, email: String): Pair<String, String?> {
    val address = email.trim()
    var who = name.trim()
    if (address.isNotBlank() && who.isNotBlank()) {
        val tail = Regex(
            """\s*(?:<\s*)?${Regex.escape(address)}(?:\s*>)?\s*$""",
            RegexOption.IGNORE_CASE,
        )
        val stripped = who.replace(tail, "").trim().trim('"')
        who = if (stripped.isBlank() || stripped.equals(address, ignoreCase = true)) "" else stripped
    }
    val same = who.isBlank() || (address.isNotBlank() && who.equals(address, ignoreCase = true))
    val primary = when {
        !same -> who
        address.isNotBlank() -> address
        who.isNotBlank() -> who
        else -> "(no sender)"
    }
    val secondary = if (!same && address.isNotBlank() && !address.equals(primary, ignoreCase = true)) {
        address
    } else {
        null
    }
    return primary to secondary
}
