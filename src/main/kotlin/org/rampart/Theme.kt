package org.rampart

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxSize

/**
 * Archivo, the face the wordmark is cut from, bundled as four static weights.
 *
 * The upstream file is a variable font, and Compose picks a weight by choosing a file
 * rather than by setting an axis, so shipping the variable one would leave every weight
 * looking like Regular with the renderer faking the rest.
 */
internal val Archivo = FontFamily(
    Font("font/Archivo-400.ttf", FontWeight.Normal),
    Font("font/Archivo-500.ttf", FontWeight.Medium),
    Font("font/Archivo-600.ttf", FontWeight.SemiBold),
    Font("font/Archivo-700.ttf", FontWeight.Bold),
)

/** Material's scale, in Archivo, tightened where mail is read rather than skimmed. */
internal val RampartTypography: Typography = Typography().let { base ->
    fun androidx.compose.ui.text.TextStyle.f(size: TextUnit? = null, weight: FontWeight? = null) =
        copy(fontFamily = Archivo, fontSize = size ?: fontSize, fontWeight = weight ?: fontWeight)
    Typography(
        displayLarge = base.displayLarge.f(),
        displayMedium = base.displayMedium.f(),
        displaySmall = base.displaySmall.f(),
        headlineLarge = base.headlineLarge.f(),
        headlineMedium = base.headlineMedium.f(),
        headlineSmall = base.headlineSmall.f(),
        titleLarge = base.titleLarge.f(21.sp, FontWeight.SemiBold),
        titleMedium = base.titleMedium.f(15.sp, FontWeight.SemiBold),
        titleSmall = base.titleSmall.f(),
        bodyLarge = base.bodyLarge.f(14.5.sp),
        bodyMedium = base.bodyMedium.f(13.5.sp),
        bodySmall = base.bodySmall.f(12.sp),
        labelLarge = base.labelLarge.f(13.sp, FontWeight.Medium),
        labelMedium = base.labelMedium.f(11.5.sp, FontWeight.SemiBold),
        labelSmall = base.labelSmall.f(),
    )
}

/**
 * Their picture if we hold one, initials in a coloured circle otherwise.
 *
 * Still nothing fetched. A real avatar service means asking a third party who you
 * correspond with, on every message, which is exactly the leak the image blocker exists to
 * prevent. [photo] only ever comes from a contact card you already have, where the picture
 * is carried inside the card and no request leaves the machine to draw it.
 */
@Composable
internal fun Avatar(
    label: String,
    seed: String,
    size: Dp,
    color: Color = avatarColor(seed),
    photo: ImageBitmap? = null,
) {
    Box(
        modifier = Modifier.size(size).background(color, CircleShape).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(
                photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                initialsOf(label, seed),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp,
                fontFamily = Archivo,
            )
        }
    }
}

/** Frames in the working strips, and how fast they play. Both loop in exactly 4 seconds. */
private const val ROOK_WORKING_FRAMES = 48
private const val ROOK_WORKING_FPS = 12

/** The smallest strip that stays sharp at [sizePx], in device pixels so HiDPI screens get the big one. */
internal fun rookWorkingStripFile(sizePx: Float): String =
    if (sizePx > 96f) "rook-working-192.png" else "rook-working-96.png"

/**
 * The frame [progress] lands on, out of [ROOK_WORKING_FRAMES].
 *
 * Clamped rather than wrapped: the animation's own target value is exactly
 * [ROOK_WORKING_FRAMES], reached for one instant before it loops back to 0, and reading
 * that instant literally would index one frame past the end of the strip.
 */
internal fun rookWorkingFrame(progress: Float): Int =
    progress.toInt().coerceIn(0, ROOK_WORKING_FRAMES - 1)

/**
 * The two working strips, decoded once and kept for the life of the process.
 *
 * A `remember` inside [RookAvatar] would decode a fresh copy for every place Rook is drawn
 * at once, and the sidebar button, the chat header and a line in the transcript can all be
 * on screen together. The map decodes each strip the first time anything asks for it and
 * every avatar after that reuses the same bitmap.
 */
private val rookWorkingStrips = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap?>()

private fun rookWorkingStrip(file: String): ImageBitmap? = rookWorkingStrips.getOrPut(file) {
    runCatching { useResource("art/$file") { loadImageBitmap(it) } }.getOrNull()
}

/**
 * Rook's round face, bundled with the app rather than fetched, so it never has a
 * fallback to fall back to.
 *
 * [ring] draws a thin border in the theme's primary colour, for the one place this
 * is also a toggle button: the sidebar shows it when the assistant panel is open, the
 * same way the other footer icons change colour to show which one is active.
 *
 * [working] swaps the static picture for the animated one: a request is actually in
 * flight, so the same face that shows Rook is present also shows he is busy, rather than
 * a spinner drawn next to a face that is not doing anything.
 */
@Composable
internal fun RookAvatar(size: Dp, file: String = "rook-avatar-96.png", ring: Boolean = false, working: Boolean = false) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape)
            .then(
                if (ring) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier,
            ),
    ) {
        if (working) {
            RookWorking(size)
        } else {
            val image = artImage(file)
            if (image != null) {
                Image(
                    image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** The animated face itself, drawn one frame at a time out of whichever strip fits [size]. */
@Composable
private fun RookWorking(size: Dp) {
    val sizePx = with(LocalDensity.current) { size.toPx() }
    val stripFile = rookWorkingStripFile(sizePx)
    val strip = rookWorkingStrip(stripFile)
    val frameSize = if (stripFile == "rook-working-192.png") 192 else 96
    if (strip == null) return

    val cycle = rememberInfiniteTransition(label = "rook-working")
    val progress by cycle.animateFloat(
        initialValue = 0f,
        targetValue = ROOK_WORKING_FRAMES.toFloat(),
        animationSpec = infiniteRepeatable(
            tween(ROOK_WORKING_FRAMES * 1000 / ROOK_WORKING_FPS, easing = LinearEasing),
        ),
        label = "frame",
    )
    Canvas(Modifier.fillMaxSize()) {
        val frame = rookWorkingFrame(progress)
        drawImage(
            image = strip,
            srcOffset = IntOffset(frame * frameSize, 0),
            srcSize = IntSize(frameSize, frameSize),
            dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()),
        )
    }
}

/**
 * The pictures we hold, by address, for whoever is being drawn.
 *
 * A lookup rather than a parameter threaded through every list and row that might want
 * one, the same way the icon pack is done. Empty is the normal case and costs nothing.
 */
internal val LocalSenderPhotos = staticCompositionLocalOf { emptyMap<String, ImageBitmap>() }

/**
 * The colour chosen for each tag, by lowercased keyword.
 *
 * A composition local for the same reason the photos are one: tags are drawn on a list
 * row, in the reader and in the sidebar, and threading a colour map through every one of
 * those is a parameter on a dozen functions that do not otherwise care. Empty is the
 * normal case and means every tag keeps the colour derived from its name.
 */
internal val LocalTagColours = staticCompositionLocalOf { emptyMap<String, Long>() }

/**
 * Whether a tagged row in the message list carries its tag's colour.
 *
 * Bulwark's `tintListRowsByTag`. A local for the same reason as the colours above: it is
 * read in one place deep in the list and threading it through would put a parameter on
 * every function between here and there.
 */
internal val LocalTintRowsByTag = staticCompositionLocalOf { false }

/**
 * Which loader is drawn while Rampart waits.
 *
 * A local for the same reason as the two above: spinners appear in a dozen places, most of
 * them several functions deep, and threading a choice through all of them would be a
 * parameter on everything between here and there.
 */
internal val LocalLoader = staticCompositionLocalOf { Loader.RING }

/** The picture for [email], or null. Case is not part of an address. */
@Composable
internal fun photoFor(email: String): ImageBitmap? =
    LocalSenderPhotos.current[email.trim().lowercase()]

/**
 * Two letters: the first of each of the first two words of the name, or the first two
 * letters of a single word, falling back to the address. Digits and punctuation are
 * skipped, so "1password@example.org" does not come out as "1P".
 *
 * The two sources are never mixed. Taking a letter from the name and the next from the
 * address turned "Blueprint / admin@" into "BA", which belongs to nobody.
 */
internal fun initialsOf(label: String, seed: String): String {
    val words = label.split(' ', '.', '_', '-', ',').map { word -> word.filter { it.isLetter() } }
        .filter { it.isNotEmpty() }
    if (words.size >= 2) return "${words[0].first()}${words[1].first()}".uppercase()
    if (words.size == 1) return words[0].take(2).uppercase()

    val letters = seed.substringBefore('@').filter { it.isLetter() }
    return when {
        letters.length >= 2 -> letters.take(2).uppercase()
        letters.isNotEmpty() -> letters.take(1).uppercase()
        else -> "?"
    }
}

/**
 * A stable colour per address, from a palette chosen so white text clears 4.5:1 on every
 * one of them. Hashing the address rather than the display name means the same person keeps
 * their colour when they change how they sign their mail.
 */
internal fun avatarColor(seed: String): Color {
    val palette = listOf(
        0xFFB23A48, 0xFF8C5A2B, 0xFF4F6D3A, 0xFF2E6A6A,
        0xFF2F5D96, 0xFF5B4B94, 0xFF8A3A72, 0xFF6B5330,
    )
    val key = seed.lowercase().fold(0) { acc, ch -> acc * 31 + ch.code }
    return Color(palette[((key % palette.size) + palette.size) % palette.size])
}
