package org.rampart

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * Whether the window animates.
 *
 * One flag, read wherever a transition is built, so turning animations off does not mean
 * finding every call. Off still changes whatever was going to change. It just does not travel.
 *
 * Spinners and the undo countdown are not this. A spinner that stops looks stuck, and the
 * undo bar is a timer: cutting it short would close the offer before anyone could take it.
 */
internal val LocalAnimationsEnabled = staticCompositionLocalOf { true }

/**
 * A short transition when animation is on, and a snap when it is off.
 *
 * New motion should use this rather than a bare [tween], so the switch covers it.
 * Material's own scheme is not reachable from here, so each transition reads the flag
 * itself instead of asking the theme.
 */
@Composable
internal fun <T> motionTween(millis: Int): FiniteAnimationSpec<T> =
    if (LocalAnimationsEnabled.current) tween(millis) else snap()

/**
 * Fade a list row in and out.
 *
 * The row does not slide. The list already corrects its scroll when a folder opens,
 * and a placement animation fights that correction.
 *
 * Written as an extension on [Modifier] because the rows call it that way. The list
 * item is only the context: that is where the row's own fade lives, and a table or a
 * card is not itself that scope.
 */
context(itemScope: LazyItemScope)
@Composable
internal fun Modifier.rowChange(): Modifier = with(itemScope) {
    this@rowChange.animateItem(
        fadeInSpec = motionTween(120),
        placementSpec = snap<IntOffset>(),
        fadeOutSpec = motionTween(120),
    )
}
