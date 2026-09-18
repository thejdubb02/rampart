package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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

/**
 * The pictures we hold, by address, for whoever is being drawn.
 *
 * A lookup rather than a parameter threaded through every list and row that might want
 * one, the same way the icon pack is done. Empty is the normal case and costs nothing.
 */
internal val LocalSenderPhotos = staticCompositionLocalOf { emptyMap<String, ImageBitmap>() }

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
 * address turned "Skybox7 / admin@" into "SD", which belongs to nobody.
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
