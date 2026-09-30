package org.rampart

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How tightly packed the message list is drawn.
 *
 * Compact fits more messages on screen by putting the sender, subject and time on one line.
 * Extra compact is that same line with less padding, for a long list. It sits under Compact
 * because the settings page lists these in order.
 * Normal is the default three-line layout.
 * Spacious gives rows more breathing room and shows a two-line preview.
 */
enum class Density(val key: String, val label: String) {
    COMPACT("compact", "Compact"),
    EXTRA_COMPACT("extra-compact", "Extra compact"),
    NORMAL("normal", "Normal"),
    SPACIOUS("spacious", "Spacious"),
    ;

    companion object {
        /** Resolves a stored density key or enum name to a Density option. */
        fun of(key: String?): Density =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) || it.name.equals(key, ignoreCase = true) } ?: NORMAL
    }
}

/**
 * The message list density in force.
 */
internal val LocalListDensity = staticCompositionLocalOf { Density.NORMAL }

/**
 * Vertical padding for a message row in the list, as a pair of top and bottom padding values.
 */
internal fun densityVerticalPadding(density: Density): Pair<Dp, Dp> = when (density) {
    Density.COMPACT -> 6.dp to 6.dp
    Density.EXTRA_COMPACT -> 2.dp to 2.dp
    Density.NORMAL -> 11.dp to 12.dp
    Density.SPACIOUS -> 15.dp to 16.dp
}

/**
 * Top padding for the avatar in a message row.
 */
internal fun densityAvatarTopPadding(density: Density): Dp = when (density) {
    Density.COMPACT -> 0.dp
    Density.EXTRA_COMPACT -> 0.dp
    Density.NORMAL -> 12.dp
    Density.SPACIOUS -> 15.dp
}

/**
 * The maximum number of preview lines to show in a message row.
 */
internal fun densityPreviewLines(density: Density): Int = when (density) {
    Density.COMPACT -> 0
    Density.EXTRA_COMPACT -> 0
    Density.NORMAL -> 1
    Density.SPACIOUS -> 2
}

/**
 * Whether the avatar is shown beside the message row.
 *
 * Extra compact hides it too. A tighter row that grew an avatar would be taller, which
 * is the opposite of the point.
 */
internal fun densityShowAvatar(density: Density): Boolean = when (density) {
    Density.COMPACT, Density.EXTRA_COMPACT -> false
    Density.NORMAL, Density.SPACIOUS -> true
}
