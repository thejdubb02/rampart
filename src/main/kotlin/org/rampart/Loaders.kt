package org.rampart

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The thing that spins while Rampart is waiting.
 *
 * Drawn rather than borrowed. The good ones on the web are CSS, and none of that survives
 * the trip into a desktop toolkit: a `@keyframes` rule and a `box-shadow` have no
 * equivalent here, so the only honest way to have one is to draw it. Each of these is a
 * `Canvas` and an infinite transition, which is a dozen lines apiece.
 *
 * **Every one takes its colour from the theme rather than carrying its own.** That is the
 * whole point of having them: Rampart ships eighteen themes, and a loader with a hardcoded
 * purple looks like somebody else's component dropped into the middle of the app. The
 * colour is [MaterialTheme]'s primary unless the caller says otherwise, so a loader on a
 * coloured surface can be handed the right contrast instead of guessing.
 */
enum class Loader(val label: String) {
    /** The Material circle. Familiar, and the right answer for anybody who wants no opinion. */
    RING("Ring"),
    PULSE("Pulsing dots"),
    ORBIT("Orbit"),
    BARS("Bars"),
    WAVE("Wave"),
    ;

    companion object {
        fun of(name: String): Loader = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: RING
    }
}

/**
 * A loader, in whichever shape is chosen.
 *
 * [size] is the whole thing, so every one of these occupies the same square and swapping
 * between them never moves anything else on the screen. That is not a detail: a loader that
 * changes the layout when it changes shape makes the setting feel like it breaks the app.
 */
@Composable
internal fun Spinner(
    modifier: Modifier = Modifier,
    which: Loader = LocalLoader.current,
    size: androidx.compose.ui.unit.Dp = 28.dp,
    colour: Color = MaterialTheme.colorScheme.primary,
    /** Thinner where a loader sits inside a button rather than in the middle of a pane. */
    thickness: androidx.compose.ui.unit.Dp = 3.dp,
) {
    val box = modifier.then(Modifier.size(size))
    when (which) {
        Loader.RING -> Ring(box, colour, thickness)
        Loader.PULSE -> Pulse(box, colour)
        Loader.ORBIT -> Orbit(box, colour)
        Loader.BARS -> Bars(box, colour)
        Loader.WAVE -> Wave(box, colour)
    }
}

/**
 * An arc chasing its own tail.
 *
 * The sweep grows and shrinks as well as rotating, which is what stops it reading as a
 * clock hand. A constant arc at constant speed looks like a progress bar that is stuck.
 */
@Composable
private fun Ring(modifier: Modifier, colour: Color, thickness: androidx.compose.ui.unit.Dp) {
    val cycle = rememberInfiniteTransition(label = "ring")
    val turn by cycle.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "turn",
    )
    val sweep by cycle.animateFloat(
        initialValue = 55f,
        targetValue = 285f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "sweep",
    )
    Canvas(modifier) {
        val stroke = thickness.toPx()
        drawArc(
            color = colour,
            startAngle = turn,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * Three dots breathing in turn.
 *
 * The quietest of them, and the one that suits a line of text: it has no rotation, so it
 * does not pull the eye the way a spinning thing does while somebody is trying to read.
 */
@Composable
private fun Pulse(modifier: Modifier, colour: Color) {
    val cycle = rememberInfiniteTransition(label = "pulse")
    val phases = (0..2).map { at ->
        cycle.animateFloat(
            initialValue = 0.5f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = 1080
                    0.5f at 0
                    1f at 180
                    0.5f at 480
                },
                initialStartOffset = androidx.compose.animation.core.StartOffset(at * 160),
            ),
            label = "dot$at",
        )
    }
    Canvas(modifier) {
        val radius = size.minDimension / 7f
        val gap = (size.width - radius * 2) / 2f
        phases.forEachIndexed { at, scale ->
            drawCircle(
                color = colour.copy(alpha = scale.value.coerceIn(0.45f, 1f)),
                radius = radius * scale.value,
                center = Offset(radius + gap * at, size.height / 2),
            )
        }
    }
}

/** A dot going round a faint track, which reads as motion at a glance from across the room. */
@Composable
private fun Orbit(modifier: Modifier, colour: Color) {
    val cycle = rememberInfiniteTransition(label = "orbit")
    val turn by cycle.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "turn",
    )
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val dot = radius / 4.5f
        drawCircle(colour.copy(alpha = 0.18f), radius = radius - dot, style = Stroke(width = dot / 2))
        // Two dots opposite each other, so the shape stays balanced while it turns and the
        // eye is not dragged round by a single weight.
        listOf(0f, Math.PI.toFloat()).forEach { offset ->
            drawCircle(
                color = colour,
                radius = dot,
                center = center + Offset(
                    (radius - dot) * cos(turn + offset).toFloat(),
                    (radius - dot) * sin(turn + offset).toFloat(),
                ),
            )
        }
    }
}

/** Four bars rising and falling, the equaliser shape. Reads as busy rather than as waiting. */
@Composable
private fun Bars(modifier: Modifier, colour: Color) {
    val cycle = rememberInfiniteTransition(label = "bars")
    val heights = (0..3).map { at ->
        cycle.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(520, easing = LinearEasing),
                RepeatMode.Reverse,
                initialStartOffset = androidx.compose.animation.core.StartOffset(at * 130),
            ),
            label = "bar$at",
        )
    }
    Canvas(modifier) {
        val bar = size.width / 7f
        heights.forEachIndexed { at, tall ->
            val height = size.height * tall.value
            drawRoundRect(
                color = colour,
                topLeft = Offset(at * bar * 2, (size.height - height) / 2),
                size = Size(bar, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar / 2),
            )
        }
    }
}

/** A row of dots riding a sine wave, which is the least impatient-looking of the five. */
@Composable
private fun Wave(modifier: Modifier, colour: Color) {
    val cycle = rememberInfiniteTransition(label = "wave")
    val phase by cycle.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "phase",
    )
    Canvas(modifier) {
        val dots = 4
        val radius = size.width / 12f
        val step = (size.width - radius * 2) / (dots - 1)
        repeat(dots) { at ->
            val lift = sin(phase - at * 0.7f).toFloat()
            drawCircle(
                color = colour.copy(alpha = (0.45f + 0.55f * ((lift + 1) / 2)).coerceIn(0f, 1f)),
                radius = radius,
                center = Offset(radius + step * at, size.height / 2 + lift * size.height / 5f),
            )
        }
    }
}
