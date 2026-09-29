package org.rampart

/*
 * The rules behind the app bar on the right edge of the window, kept apart from anything
 * drawn so they can be checked without a window. AppBar.kt is what is drawn.
 */

/**
 * The tools the app bar opens beside the mail, top to bottom in the order the bar shows them.
 *
 * [key] is what a remembered width is filed under in settings, so it must never change once
 * shipped: renaming it quietly forgets the width everybody had dragged it to. [digit] is the
 * number that opens it with Ctrl held, and [command] is the palette's id for the same thing,
 * so the key and the palette entry cannot come to mean different panels.
 */
internal enum class SideTool(
    val key: String,
    val label: String,
    val digit: Int,
    val command: String,
    /** The width it opens at before anybody has dragged it, in dp. */
    val defaultWidth: Float,
) {
    // 360 is the width the chat panel had when it was fixed, so it opens where it always did.
    ROOK("rook", "Rook", 1, "assistant", 360f),
    CALENDAR("calendar", "Calendar", 2, "side-calendar", 320f),
    CONTACTS("contacts", "Contacts", 3, "side-contacts", 340f),
    FILES("files", "Files", 4, "side-files", 300f),
    TASKS("tasks", "Tasks", 5, "side-tasks", 300f),
    ;

    /** How the shortcut is written in the shortcut list and the palette. */
    val keys: String get() = "Ctrl+$digit"

    companion object {
        fun byKey(key: String): SideTool? = entries.firstOrNull { it.key == key }

        fun byCommand(command: String): SideTool? = entries.firstOrNull { it.command == command }

        /**
         * The tool a key press opens, or null when it is not one of ours.
         *
         * Ctrl and a digit, and nothing else held: Ctrl+Shift+2 is a different key on some
         * layouts and Alt with a digit is how Windows reaches a menu, so a press that
         * carries either belongs to whatever it means there.
         */
        fun forKey(digit: Int?, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): SideTool? {
            if (digit == null || !ctrl || shift || alt || meta) return null
            return entries.firstOrNull { it.digit == digit }
        }
    }
}

/** Narrower than this and a panel is a strip of cut off words, so a drag stops here. */
internal const val SIDE_PANEL_MIN = 260f

/** Wider than this and a panel stops being beside the message and starts replacing it. */
internal const val SIDE_PANEL_MAX = 720f

/**
 * What a panel always leaves to its left, in dp: the sidebar at its widest (232), the
 * message list (368), and 320 of the message itself. The panel sits beside the message so
 * that both can be read, and a drag that squeezed the message to a sliver would undo that.
 */
internal const val SIDE_PANEL_KEEP = 920f

/**
 * Where a drag on the panel's edge lands.
 *
 * [available] is the width of everything left of the app bar, in dp, or null before it has
 * been measured. The ceiling is the smaller of [SIDE_PANEL_MAX] and what is left after
 * [SIDE_PANEL_KEEP], and never below the floor: a small window would otherwise hand back a
 * ceiling under the minimum, which `coerceIn` refuses by throwing. In a window that small
 * the panel keeps its floor and the message gives way, because a panel narrower than that
 * is no use to anybody.
 */
internal fun clampSidePanelWidth(wanted: Float, available: Float? = null): Float {
    val room = available?.let { it - SIDE_PANEL_KEEP } ?: SIDE_PANEL_MAX
    val ceiling = minOf(SIDE_PANEL_MAX, room).coerceAtLeast(SIDE_PANEL_MIN)
    val sane = if (wanted.isNaN()) SIDE_PANEL_MIN else wanted
    return sane.coerceIn(SIDE_PANEL_MIN, ceiling)
}

/**
 * Which panel is open, and how wide each one was last left.
 *
 * One at a time, because two panels beside a message leave no message. Immutable, so every
 * change is a new value the window can hold in one piece of state and redraw from.
 */
internal data class SidePanels(
    val open: SideTool? = null,
    /** Only the tools somebody has resized. The rest open at their default. */
    val widths: Map<SideTool, Float> = emptyMap(),
) {
    /** The width [tool] opens at: the one it was left at, or its default. */
    fun widthOf(tool: SideTool): Float = clampSidePanelWidth(widths[tool] ?: tool.defaultWidth)

    /**
     * Clicking a tool's icon: opens it, closes it if it was the one open, and replaces
     * whichever other one was open rather than adding a second beside it.
     */
    fun toggle(tool: SideTool): SidePanels = copy(open = if (open == tool) null else tool)

    /** Opens [tool] whatever was open, for the places that mean "show me" rather than "switch". */
    fun show(tool: SideTool): SidePanels = copy(open = tool)

    fun close(): SidePanels = copy(open = null)

    /** Closes [tool] only if it is the one open, so a stale close cannot shut a different panel. */
    fun close(tool: SideTool): SidePanels = if (open == tool) copy(open = null) else this

    /** [tool] dragged to [width], clamped to what [available] leaves room for. */
    fun resize(tool: SideTool, width: Float, available: Float? = null): SidePanels =
        copy(widths = widths + (tool to clampSidePanelWidth(width, available)))

    /** What settings keeps, keyed by [SideTool.key] so a renamed enum constant loses nothing. */
    fun savedWidths(): Map<String, Float> = widths.mapKeys { it.key.key }

    companion object {
        /**
         * The widths read back from settings. A key that names no tool, from a later version
         * or a hand edit, is dropped rather than failing, and every width is clamped again
         * because the file is not something this code wrote under today's limits.
         */
        fun restored(saved: Map<String, Float>): SidePanels = SidePanels(
            widths = saved.mapNotNull { (key, width) ->
                SideTool.byKey(key)?.let { it to clampSidePanelWidth(width) }
            }.toMap(),
        )
    }
}
