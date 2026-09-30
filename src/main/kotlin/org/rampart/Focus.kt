package org.rampart

/**
 * Whether a message belongs in the Focused tab or the Other tab.
 */
internal enum class FocusCategory {
    FOCUSED,
    OTHER,
}

/**
 * The tabs shown at the top of an inbox when Focused inbox is turned on.
 */
internal enum class FocusTab {
    FOCUSED,
    OTHER,
}

/**
 * An explicit per-sender preference stored in synced settings.
 */
internal enum class FocusOverride(val value: String) {
    FOCUSED("focused"),
    OTHER("other");

    companion object {
        fun of(value: String?): FocusOverride? = when (value?.trim()?.lowercase()) {
            "focused", "focus", "person" -> FOCUSED
            "other", "bulk" -> OTHER
            else -> null
        }
    }
}

/**
 * Tokens that indicate a sender is an automated or unmonitored mailbox.
 */
private val NO_REPLY_PATTERNS = listOf(
    "noreply",
    "no-reply",
    "donotreply",
    "notifications",
    "mailer-daemon",
    "bounce",
)

/**
 * Common marketing and mailing platforms detected in Return-Path or Received headers.
 */
private val MARKETING_PLATFORMS = listOf(
    "sendgrid",
    "mailchimp",
    "mcsv",
    "amazonses",
    "mailgun",
    "constantcontact",
    "hubspot",
    "klaviyo",
)

/**
 * True when the address or display name looks like an automated or no-reply mailbox.
 */
internal fun isNoReplySender(email: String, name: String = ""): Boolean {
    val localPart = email.substringBefore('@').lowercase().trim()
    val fullEmail = email.lowercase().trim()
    val lowerName = name.lowercase().trim()
    return NO_REPLY_PATTERNS.any { pattern ->
        localPart.contains(pattern) || fullEmail.contains(pattern) || lowerName.contains(pattern)
    }
}

/**
 * True when Return-Path or Received headers name a known bulk marketing platform.
 */
internal fun hasMarketingPlatform(headers: List<Pair<String, String>>): Boolean {
    for ((name, value) in headers) {
        if (name.equals("Return-Path", ignoreCase = true) || name.equals("Received", ignoreCase = true)) {
            val lower = value.lowercase()
            if (MARKETING_PLATFORMS.any { platform -> lower.contains(platform) }) return true
        }
    }
    return false
}

/**
 * Decides whether a message came from a person or is bulk mail.
 *
 * Evaluation order:
 * 1. An explicit override for this sender wins over every other rule.
 * 2. A sender the user has written to before (in knownSenders) is always a person.
 * 3. Bulk indicators: List-Id, List-Unsubscribe, Precedence: bulk/list, Auto-Submitted other
 *    than "no", no-reply sender address, or marketing platform in Return-Path/Received.
 * 4. Otherwise, the message is treated as personal mail.
 */
internal fun classifyFocus(
    fromEmail: String,
    fromName: String = "",
    listId: String = "",
    headers: List<Pair<String, String>> = emptyList(),
    knownSenders: Set<String> = emptySet(),
    overrides: Map<String, String> = emptyMap(),
): FocusCategory {
    val email = fromEmail.trim().lowercase()

    // 1. Per-sender override wins over everything
    val override = overrides[email]?.let { FocusOverride.of(it) }
    if (override == FocusOverride.FOCUSED) return FocusCategory.FOCUSED
    if (override == FocusOverride.OTHER) return FocusCategory.OTHER

    // 2. Senders the user has written to before are always people
    if (email.isNotEmpty() && email in knownSenders) {
        return FocusCategory.FOCUSED
    }

    // 3. Bulk rules
    if (listId.isNotBlank()) return FocusCategory.OTHER

    for ((name, value) in headers) {
        val trimmed = value.trim()
        if (name.equals("List-Id", ignoreCase = true) && trimmed.isNotEmpty()) {
            return FocusCategory.OTHER
        }
        if (name.equals("List-Unsubscribe", ignoreCase = true) && trimmed.isNotEmpty()) {
            return FocusCategory.OTHER
        }
        if (name.equals("Precedence", ignoreCase = true)) {
            val lower = trimmed.lowercase()
            if (lower == "bulk" || lower == "list") return FocusCategory.OTHER
        }
        if (name.equals("Auto-Submitted", ignoreCase = true)) {
            val lower = trimmed.lowercase()
            if (lower.isNotEmpty() && lower != "no") return FocusCategory.OTHER
        }
    }

    if (isNoReplySender(email, fromName)) {
        return FocusCategory.OTHER
    }

    if (hasMarketingPlatform(headers)) {
        return FocusCategory.OTHER
    }

    // 4. Default to person
    return FocusCategory.FOCUSED
}

/**
 * Classifies a summary. The list calls this through [isFocused], so List-Unsubscribe,
 * Precedence and Auto-Submitted kept on the summary are read here. Extra [headers]
 * are checked as well, for a caller that already has them in hand.
 */
internal fun classifyFocus(
    summary: Summary,
    knownSenders: Set<String> = emptySet(),
    overrides: Map<String, String> = emptyMap(),
    headers: List<Pair<String, String>> = emptyList(),
): FocusCategory = classifyFocus(
    fromEmail = summary.fromEmail,
    fromName = summary.from,
    listId = summary.listId,
    headers = bulkHeaders(summary) + headers,
    knownSenders = knownSenders,
    overrides = overrides,
)

/** The three bulk headers the list fetch keeps on a row. Blank ones are left out. */
private fun bulkHeaders(summary: Summary): List<Pair<String, String>> = buildList {
    val unsubscribe = summary.listUnsubscribe.trim()
    if (unsubscribe.isNotEmpty()) add("List-Unsubscribe" to unsubscribe)
    val precedence = summary.precedence.trim()
    if (precedence.isNotEmpty()) add("Precedence" to precedence)
    val autoSubmitted = summary.autoSubmitted.trim()
    if (autoSubmitted.isNotEmpty()) add("Auto-Submitted" to autoSubmitted)
}

/**
 * True when the message is classified as personal mail.
 */
internal fun isFocused(
    summary: Summary,
    knownSenders: Set<String> = emptySet(),
    overrides: Map<String, String> = emptyMap(),
    headers: List<Pair<String, String>> = emptyList(),
): Boolean = classifyFocus(summary, knownSenders, overrides, headers) == FocusCategory.FOCUSED

/**
 * True when the message is classified as bulk mail.
 */
internal fun isBulk(
    summary: Summary,
    knownSenders: Set<String> = emptySet(),
    overrides: Map<String, String> = emptyMap(),
    headers: List<Pair<String, String>> = emptyList(),
): Boolean = classifyFocus(summary, knownSenders, overrides, headers) == FocusCategory.OTHER

/**
 * A group of bulk messages from the same sender in the Other tab.
 */
internal data class FocusBundle(
    val sender: String,
    val senderEmail: String,
    val messages: List<Summary>,
) {
    val count: Int get() = messages.size
    val newest: Summary get() = messages.first()
    val unreadCount: Int get() = messages.count { !it.seen }
}

/**
 * Groups bulk messages by sender so a high-volume newsletter or notification feed takes up
 * a single expandable row in the Other tab.
 */
internal fun bundleOtherMessages(messages: List<Summary>): List<FocusBundle> {
    if (messages.isEmpty()) return emptyList()
    val groups = LinkedHashMap<String, MutableList<Summary>>()
    for (message in messages) {
        val key = message.fromEmail.trim().lowercase().ifEmpty { message.from.trim().lowercase() }
        groups.getOrPut(key) { mutableListOf() }.add(message)
    }
    return groups.values.map { list ->
        val sender = list.first().from.ifBlank { list.first().fromEmail }
        val senderEmail = list.first().fromEmail
        FocusBundle(
            sender = sender,
            senderEmail = senderEmail,
            messages = list,
        )
    }
}
