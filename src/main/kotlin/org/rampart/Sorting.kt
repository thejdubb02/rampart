package org.rampart

/**
 * How the list is ordered.
 *
 * Kept as a value rather than a boolean, because "newest first" and "oldest first" are not
 * the only two anybody wants and adding the third to a boolean is how a setting becomes a
 * pair of booleans that can both be true.
 */
internal enum class Order(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    UNREAD("Unread first"),
    SENDER("Sender"),
    SUBJECT("Subject"),
}

/**
 * The list in the chosen order.
 *
 * `receivedAt` is an ISO timestamp, so sorting it as text is the same as sorting it as a
 * date and needs no parsing. Every order falls back to newest first inside a tie, because a
 * list that reshuffles equal rows between refreshes looks broken even when it is not.
 *
 * Subject sorting ignores a leading Re: or Fwd:, which is the whole point of sorting by
 * subject: the reply belongs next to what it is replying to.
 */
internal fun sorted(emails: List<Summary>, order: Order): List<Summary> = when (order) {
    Order.NEWEST -> emails.sortedByDescending { it.receivedAt }
    Order.OLDEST -> emails.sortedBy { it.receivedAt }
    Order.UNREAD -> emails.sortedWith(compareBy<Summary> { it.seen }.thenByDescending { it.receivedAt })
    Order.SENDER -> emails.sortedWith(
        compareBy<Summary> { it.from.ifBlank { it.fromEmail }.lowercase() }.thenByDescending { it.receivedAt },
    )
    Order.SUBJECT -> emails.sortedWith(
        compareBy<Summary> { bareSubject(it.subject) }.thenByDescending { it.receivedAt },
    )
}

private val PREFIX = Regex("^\\s*((re|fw|fwd|aw|sv)\\s*(\\[\\d+])?\\s*:\\s*)+", RegexOption.IGNORE_CASE)

/**
 * Which row to open after the one at [index] leaves the list.
 *
 * The next row that is staying, in the order the list is already in. When this
 * was the last row, the one above it. Nothing left means nothing selected. An
 * index outside the list means the open row was not in this list.
 */
internal fun <T> nextInList(list: List<T>, index: Int, leaving: (T) -> Boolean = { false }): T? {
    if (index !in list.indices) return null
    for (i in index + 1 until list.size) {
        if (!leaving(list[i])) return list[i]
    }
    for (i in index - 1 downTo 0) {
        if (!leaving(list[i])) return list[i]
    }
    return null
}

/** A subject with its reply and forward prefixes taken off, lowercased for comparison. */
internal fun bareSubject(subject: String): String = subject.replace(PREFIX, "").trim().lowercase()
