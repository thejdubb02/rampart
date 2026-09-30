package org.rampart

/**
 * How the list and the open message share the mail area.
 *
 * Kept apart from [ListLayout] on purpose. That one is how a row is drawn. This one
 * is where the two panes sit, and it is the only place that decision is made: the
 * window asks [arrangementOf] and draws the same list and the same message either way.
 * Split is the window as it has always been. A stored value this version does not
 * know, including a blank, is read as split, so an old file or a newer one cannot
 * open onto an arrangement that is not there.
 */
internal enum class MailLayout(val key: String, val label: String, val about: String) {
    SPLIT("split", "Split pane", "The list and the message side by side."),
    FOCUSED(
        "focused",
        "Focused list",
        "The list fills the width. Opening a message replaces it until you go back.",
    ),
    BOTTOM("bottom", "Reading pane at bottom", "The list on top and the message underneath."),
    ;

    companion object {
        fun of(key: String?): MailLayout =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: SPLIT
    }
}

/**
 * The key [Settings.mailLayout] keeps, given whatever text was in the file.
 *
 * Blank and unknown both come back as split. Callers never have to repeat that rule.
 */
internal fun mailLayoutKey(stored: String?): String = MailLayout.of(stored).key

/** Shorter than this and the message under the list is a strip of cut off lines. */
internal const val READING_MIN = 140f

/** Taller than this and the list above it is the strip. */
internal const val READING_MAX = 720f

/** What a new install opens at, in dp, before anyone has dragged the divider. */
internal const val READING_DEFAULT = 280f

/**
 * Room left for the list above the message: its heading, the filters and a few rows.
 * A drag that ate the list would leave nothing to choose the next message from.
 */
internal const val READING_KEEP_LIST = 180f

/**
 * Where a drag on the divider under the list lands.
 *
 * [available] is the height of the mail area, in dp, or null before it has been
 * measured. The ceiling is the smaller of [READING_MAX] and what is left after
 * [READING_KEEP_LIST], and never below the floor: a short window would otherwise
 * hand back a ceiling under the minimum, which `coerceIn` refuses by throwing.
 */
internal fun clampReadingHeight(wanted: Float, available: Float? = null): Float {
    val sane = if (wanted.isNaN() || wanted <= 0f) READING_DEFAULT else wanted
    val room = available?.let { (it - READING_KEEP_LIST).coerceAtLeast(READING_MIN) } ?: READING_MAX
    val ceiling = minOf(READING_MAX, room).coerceAtLeast(READING_MIN)
    return sane.coerceIn(READING_MIN, ceiling)
}

/**
 * Which message is covering the list in the focused layout, or none.
 *
 * The selected row is not stored here. [FocusedNav.backed] only lifts the cover,
 * so the list comes back on the same row and at the same scroll. The scroll itself
 * lives with the list, which stays composed underneath the message for that reason.
 */
internal data class FocusedNav(val openKey: String? = null) {
    /** A message was opened over the list. [key] is [rowToken] of that row. */
    fun opened(key: String): FocusedNav = copy(openKey = key)

    /**
     * Back, Escape, or Alt+Left. The list returns. The selection is not this
     * value's job, so the caller leaves it exactly where it was.
     */
    fun backed(): FocusedNav = copy(openKey = null)
}

/**
 * The message covers the list only in the focused layout, and only while the row
 * it was opened on is still the selected one.
 */
internal fun showsFocusedMessage(layout: MailLayout, nav: FocusedNav, selectedKey: String?): Boolean =
    layout == MailLayout.FOCUSED && nav.openKey != null && nav.openKey == selectedKey

/**
 * The cover after the selected row changed on its own.
 *
 * Next and previous, and an archive that advances, move the cover onto the new
 * row when the message was what was on screen. The same change while the list
 * was up does not open a message: the cover was down, and it stays down.
 * Clearing the selection lifts it, because there is nothing left to cover with.
 */
internal fun focusAfterSelectionChange(nav: FocusedNav, previousKey: String?, nextKey: String?): FocusedNav =
    if (nav.openKey != null && nav.openKey == previousKey) FocusedNav(nextKey) else nav

/**
 * Which picture [MailPanes] draws.
 *
 * A batch of picked messages keeps the list and the batch pane side by side even
 * in the focused layout: the actions apply to the rows, and hiding the rows would
 * leave the actions with nothing to point at.
 */
internal enum class PaneArrangement { SIDE_BY_SIDE, STACKED, LIST, MESSAGE }

/** The one decision for how the two panes sit. Everything drawn follows this. */
internal fun arrangementOf(layout: MailLayout, messageOpen: Boolean, batch: Boolean): PaneArrangement =
    when (layout) {
        MailLayout.SPLIT -> PaneArrangement.SIDE_BY_SIDE
        MailLayout.BOTTOM -> PaneArrangement.STACKED
        MailLayout.FOCUSED -> when {
            batch -> PaneArrangement.SIDE_BY_SIDE
            messageOpen -> PaneArrangement.MESSAGE
            else -> PaneArrangement.LIST
        }
    }
