package org.rampart

/**
 * How full a mailbox is, when the server keeps a limit.
 *
 * **A quota is optional on every protocol that has one**, and that is the part worth
 * designing around rather than treating as an error. JMAP advertises
 * `urn:ietf:params:jmap:quota` and can then return nothing at all, which is what this
 * account does: the capability says the server can answer the question, not that a limit
 * has been set. IMAP's QUOTA extension behaves the same way. So "no quota" is a normal
 * answer and gets a sentence, never a failure.
 */
internal data class MailQuota(
    /** What is limited, as the server names it. Empty where it names nothing useful. */
    val name: String,
    val used: Long,
    /** The ceiling, or null where the server reports usage without one. */
    val limit: Long?,
    /**
     * `octets` for a size, `count` for a number of messages. RFC 9425 allows others and a
     * server may invent one, so this is carried as written rather than parsed into an enum.
     */
    val resourceType: String,
) {
    val isSize: Boolean get() = resourceType.equals("octets", ignoreCase = true)
}

/**
 * How much of the quota is gone, from 0 to 1, or null when there is no ceiling.
 *
 * Clamped at 1, because a mailbox can be over its limit and a bar drawn past its own end
 * looks like a rendering fault rather than a full mailbox. The words say the real number.
 */
internal fun quotaShare(quota: MailQuota): Float? {
    val limit = quota.limit ?: return null
    if (limit <= 0) return null
    return (quota.used.toFloat() / limit).coerceIn(0f, 1f)
}

/**
 * The line shown under an account, in words.
 *
 * Sizes go through [humanSize] so they read the same as an attachment does. A count is left
 * as a number, because "1.2 KB messages" is nonsense.
 */
internal fun quotaText(quota: MailQuota): String {
    val used = if (quota.isSize) humanSize(quota.used) else "${quota.used}"
    val limit = quota.limit?.let { if (quota.isSize) humanSize(it) else "$it" }
    val unit = if (quota.isSize) "" else " messages"
    val of = if (limit == null) "$used$unit used" else "$used of $limit$unit used"
    val share = quotaShare(quota)?.let { " (${(it * 100).toInt()}%)" }.orEmpty()
    return of + share
}

/**
 * Whether it is worth drawing attention to. Nine tenths full is the point at which somebody
 * can still do something about it; a bar that turns red at capacity is a bar that tells you
 * after it matters.
 */
internal fun quotaIsTight(quota: MailQuota): Boolean = (quotaShare(quota) ?: 0f) >= 0.9f

/**
 * The one worth showing when a server reports several.
 *
 * A size limit is what people mean by a full mailbox, so it wins over a message count. The
 * tightest of them wins after that, because the one about to stop delivery is the one worth
 * the line, not whichever the server happened to list first.
 */
internal fun mainQuota(quotas: List<MailQuota>): MailQuota? =
    quotas.filter { it.limit != null && it.limit > 0 }
        .maxWithOrNull(compareBy({ if (it.isSize) 1 else 0 }, { quotaShare(it) ?: 0f }))
        ?: quotas.firstOrNull()
