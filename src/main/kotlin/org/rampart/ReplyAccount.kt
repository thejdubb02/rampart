package org.rampart

/**
 * The strip, when there is one.
 *
 * [cameTo] is spelled the way the identity is, so the button can hand it to From.
 */
internal data class ReplyAccountWarning(
    val cameTo: String,
    val from: String,
) {
    val line: String get() = "This came to $cameTo. You are sending from $from."
    val switchLabel: String get() = "Send from $cameTo"
}

/**
 * Which of [own] the message was delivered to, or null when none of them are on it.
 *
 * To, then Cc, then Delivered-To, then X-Original-To. Being written to is a stronger
 * claim than being copied, and the two delivery headers are how a list names the
 * mailbox when To names the list. They are absent on most mail. Nothing matching is
 * a BCC or a list we cannot see, and there is no address to offer.
 *
 * [own] is every identity of every signed-in account. Case is not part of an address.
 * A name in front of one is not either: headers write both.
 */
internal fun cameToAddress(
    to: List<String> = emptyList(),
    cc: List<String> = emptyList(),
    deliveredTo: List<String> = emptyList(),
    originalTo: List<String> = emptyList(),
    own: Collection<String>,
): String? {
    if (own.isEmpty()) return null
    val known = LinkedHashMap<String, String>()
    for (address in own) {
        val key = addressKey(bareAddress(address))
        if (key.isNotEmpty() && key !in known) known[key] = address.trim()
    }
    if (known.isEmpty()) return null
    fun match(addresses: List<String>): String? {
        for (raw in addresses) {
            known[addressKey(bareAddress(raw))]?.let { return it }
        }
        return null
    }
    return match(to) ?: match(cc) ?: match(deliveredTo) ?: match(originalTo)
}

/**
 * The warning when [from] is not the address [cameToAddress] found.
 *
 * All inboxes writes from the account the message is filed in, and From can be
 * switched after that. Either one sends the answer out of a mailbox the sender
 * did not write to. Null when the message names none of [own], and null when
 * From already is that address, whatever its case. The caller still sends
 * either way: a warning that blocks is one people click past without reading.
 */
internal fun replyAccountWarning(
    to: List<String> = emptyList(),
    cc: List<String> = emptyList(),
    deliveredTo: List<String> = emptyList(),
    originalTo: List<String> = emptyList(),
    own: Collection<String>,
    from: String,
): ReplyAccountWarning? {
    val came = cameToAddress(to, cc, deliveredTo, originalTo, own) ?: return null
    if (addressKey(bareAddress(from)) == addressKey(bareAddress(came))) return null
    return ReplyAccountWarning(cameTo = came, from = from.trim())
}

/**
 * Draft-only headers naming who the message being answered was addressed to.
 *
 * A draft is stored as a message, and these fields are not part of that message's
 * own To and Cc. They have to be written onto the draft and read back, or opening
 * it again has nothing to warn from. They are not written on the way out.
 */
internal const val ANSWERED_TO = "X-Rampart-Answered-To"
internal const val ANSWERED_CC = "X-Rampart-Answered-Cc"
internal const val ANSWERED_DELIVERED_TO = "X-Rampart-Answered-Delivered-To"
internal const val ANSWERED_ORIGINAL_TO = "X-Rampart-Answered-Original-To"

private val ANSWERED_HEADER_NAMES = listOf(
    ANSWERED_TO,
    ANSWERED_CC,
    ANSWERED_DELIVERED_TO,
    ANSWERED_ORIGINAL_TO,
)

/** Header lines to store on a draft. An empty list is left off, so an old draft stays unchanged. */
internal fun answeredHeaderLines(draft: Draft): List<Pair<String, String>> {
    val fields = listOf(
        ANSWERED_TO to draft.sourceTo,
        ANSWERED_CC to draft.sourceCc,
        ANSWERED_DELIVERED_TO to draft.sourceDeliveredTo,
        ANSWERED_ORIGINAL_TO to draft.sourceOriginalTo,
    )
    return fields.mapNotNull { (name, addresses) ->
        val line = addresses.map { it.trim() }.filter { it.isNotEmpty() }
        if (line.isEmpty()) null else name to line.joinToString(", ")
    }
}

/** Addresses out of the draft-only headers, in the order they were written. */
internal fun answeredAddresses(values: List<String>): List<String> =
    values.flatMap { header -> parseAddressList(header).map { it.email } }

/**
 * The same lines as [answeredHeaderLines], in the form Email/set accepts.
 *
 * One form per header. Setting the text and the address list together is refused,
 * and the text is what [answeredAddresses] reads back.
 */
internal fun answeredJmapFields(draft: Draft): List<Pair<String, String>> =
    answeredHeaderLines(draft).map { (name, value) -> "header:$name:asText" to value }

/**
 * What Email/get must ask for so [answeredAddresses] can see those headers.
 *
 * `:all` comes back as a list. The form without it is one string, and a string is
 * not a list, so the header would be stored and then read as nothing.
 */
internal fun answeredGetProperties(): List<String> =
    ANSWERED_HEADER_NAMES.map { "header:$it:asText:all" }
