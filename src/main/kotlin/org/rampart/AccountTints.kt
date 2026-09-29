package org.rampart

/**
 * The colour each account's rows carry in the merged inbox, or nothing.
 *
 * Nothing unless [enabled] and at least two accounts are signed in: with one account every
 * row would be the same colour, which says nothing and costs the unread tint its contrast.
 *
 * Each account keeps the colour it was given ([chosen], by account key) or, until somebody
 * chooses, one worked out from its key. Worked out rather than handed out in order, so the
 * same account is the same colour on every computer and does not change when another
 * account is added. Two accounts landing on the same colour is the one case that moves:
 * the later key (sorted, so it is always the same one) takes the next colour along that
 * nobody else is using, because two accounts in one colour is the thing this exists to
 * prevent.
 *
 * The colours are the tag palette, which was chosen so text stays readable on each.
 */
internal fun accountTints(
    accounts: List<String>,
    chosen: Map<String, Long>,
    enabled: Boolean,
    palette: List<Long> = TAG_COLOURS,
): Map<String, Long> {
    val keys = accounts.filter { it.isNotBlank() }.distinct().sorted()
    if (!enabled || keys.size < 2 || palette.isEmpty()) return emptyMap()
    val taken = keys.mapNotNull { chosen[it] }.toMutableSet()
    return keys.associateWith { key ->
        chosen[key] ?: run {
            val start = stableIndex(key, palette.size)
            val free = (0 until palette.size).map { palette[(start + it) % palette.size] }.firstOrNull { it !in taken }
            (free ?: palette[start]).also { taken += it }
        }
    }
}

/**
 * The colour an account starts with, before anyone chooses: the same hash the tags use.
 *
 * Shown in Settings as the account's colour even while tinting is off, so turning it on
 * brings no surprises.
 */
internal fun accountColour(key: String, chosen: Map<String, Long>, all: List<String>): Long =
    accountTints((all + key).distinct(), chosen, enabled = true)[key]
        ?: chosen[key] ?: TAG_COLOURS[stableIndex(key, TAG_COLOURS.size)]

/** A string hash that is the same on every run and every computer, unlike hashCode's contract. */
private fun stableIndex(key: String, size: Int): Int {
    val hash = key.lowercase().fold(0) { acc, ch -> acc * 31 + ch.code }
    return ((hash % size) + size) % size
}

/**
 * Which tint a row gets when both are switched on.
 *
 * **The tag wins.** A tag is something somebody put on this one message; the account is
 * where every message in that mailbox came from, and the merged inbox already names it on
 * the row. The more particular fact is the one worth the colour. Blending the two makes a
 * colour neither of them is, and alternating by row makes the list look broken.
 */
internal fun rowTint(tag: Long?, account: Long?): Long? = tag ?: account
