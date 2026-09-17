package org.rampart

/** What a poll turned up for one account. */
internal data class Arrivals(
    val state: String,
    val summaries: List<Summary>,
    /** Unseen messages that were not in the inbox the last time we looked. */
    val fresh: List<Summary>,
)

/**
 * What changed in an inbox between two looks at it.
 *
 * Separated from the polling so it can be tested without a server, because the two ways
 * this goes wrong are both silent. Announcing mail that was already there means a pile of
 * notifications every time the app starts, and treating a message that was marked read
 * elsewhere as new means the same message announced again on the next poll.
 *
 * [known] is null on the first look at an account, which announces nothing: everything in
 * the mailbox at that point is mail you have already had a chance to see.
 */
internal fun arrivals(state: String, summaries: List<Summary>, known: Set<String>?): Arrivals =
    Arrivals(
        state = state,
        summaries = summaries,
        fresh = if (known == null) emptyList() else summaries.filter { !it.seen && it.id !in known },
    )

/**
 * One line for the notification. Several messages at once become one notification rather
 * than a stack of them, because a stack is what makes people turn notifications off.
 */
internal fun arrivalText(fresh: List<Summary>): Pair<String, String>? = when (fresh.size) {
    0 -> null
    1 -> fresh[0].from to fresh[0].subject.ifBlank { "(no subject)" }
    else -> "${fresh.size} new messages" to fresh.take(3).joinToString(", ") { it.from }
}
