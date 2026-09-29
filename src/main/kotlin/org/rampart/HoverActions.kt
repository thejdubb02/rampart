package org.rampart

/**
 * The small buttons a message row shows under the pointer, and which of them somebody wants.
 *
 * [key] is what is stored in settings, and never changes once shipped: a renamed key is a
 * choice that silently falls back to the default on the next start.
 */
internal enum class HoverAction(val key: String, val label: String) {
    READ("read", "Mark read or unread"),
    ARCHIVE("archive", "Archive"),
    DELETE("delete", "Delete"),
    STAR("star", "Star"),
    SNOOZE("snooze", "Snooze"),
    MOVE("move", "Move to a folder"),
    ;

    companion object {
        fun of(key: String): HoverAction? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/**
 * What a row showed before this was a choice: read, archive and delete, in that order.
 *
 * The default has to be exactly this, so nobody who never opens the setting sees a row
 * that changed under them.
 */
internal val DEFAULT_HOVER_ACTIONS = listOf(HoverAction.READ, HoverAction.ARCHIVE, HoverAction.DELETE)

/**
 * How many fit.
 *
 * The buttons take the place of the time, and that slot has room for four at the size the
 * row draws them. A fifth would push the row wider on hover, which moves everything under
 * the pointer at the moment somebody is aiming at it.
 */
internal const val HOVER_ACTIONS_MAX = 4

/**
 * The stored choice read back.
 *
 * Nothing stored is the default. Unknown keys (from a newer Rampart) and repeats are
 * dropped, the order is kept, and anything past [HOVER_ACTIONS_MAX] is cut. An empty list
 * that was stored on purpose stays empty: no buttons on hover is a real choice.
 */
internal fun hoverActionsOf(stored: List<String>?): List<HoverAction> {
    if (stored == null) return DEFAULT_HOVER_ACTIONS
    return stored.mapNotNull(HoverAction::of).distinct().take(HOVER_ACTIONS_MAX)
}

/** The choice as it is stored. */
internal fun hoverActionKeys(actions: List<HoverAction>): List<String> = actions.map { it.key }

/**
 * [current] with [action] switched on or off.
 *
 * Switched on, it goes at the end, so the buttons keep the order they were chosen in.
 * Switched on when four are already chosen, nothing changes: the settings page says why
 * rather than dropping one somebody picked.
 */
internal fun toggledHoverAction(current: List<HoverAction>, action: HoverAction): List<HoverAction> = when {
    action in current -> current - action
    current.size >= HOVER_ACTIONS_MAX -> current
    else -> current + action
}
