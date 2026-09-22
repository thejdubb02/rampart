package org.rampart

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * A picture the message carried, turned into one the engine will actually draw.
 *
 * **This is the fault that kept a hotel's logo off the screen for a week.** The file is a
 * JPEG, which is where everybody stops looking, and it is a four-component Adobe CMYK
 * JPEG carrying six hundred kilobytes of colour profile: the kind a design tool writes
 * when somebody exports for print and drops it into a signature. Chromium decodes those,
 * which is why the same message is fine in webmail. The engine here does not, on Windows,
 * and the way it does not is the worst kind: the header parses, so the picture is laid out
 * at its real size and reserves the space, and then nothing is painted into it. A blank
 * rectangle exactly where the logo goes, no error, no broken-image mark, nothing in a log.
 *
 * Every other explanation fitted too, which is why this took so long: it was a big picture,
 * so the size limits looked guilty; it was always the same sender, so the sender looked
 * guilty; it worked here and not there, so the platform looked guilty. It was the file.
 *
 * Skia is already in the process, it reads formats the engine will not, and it writes
 * plain baseline JPEG and PNG that nothing refuses. So every carried picture is decoded
 * and written out again before the engine is offered it. The logo goes from 702 KB to 72,
 * because almost all of it was the profile.
 *
 * Anything that cannot be decoded is passed through exactly as it arrived. A picture this
 * cannot read might still be one the engine can, and guessing wrong in that direction
 * costs nothing.
 */
internal fun drawable(type: String, bytes: ByteArray): Pair<String, ByteArray> {
    val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return type to bytes
    /*
     * JPEG where there is no transparency to lose, PNG where there is.
     *
     * Not the other way round and not always PNG: a photograph written as PNG is several
     * times the size it arrived at, and these are embedded in a document.
     */
    val opaque = runCatching { image.imageInfo.colorInfo.isOpaque }.getOrDefault(false)
    val format = if (opaque) EncodedImageFormat.JPEG else EncodedImageFormat.PNG
    val encoded = runCatching { image.encodeToData(format, 90)?.bytes }.getOrNull()
        ?: return type to bytes
    /*
     * Kept even when it came out bigger, which a small picture usually does.
     *
     * The first version of this kept whichever was smaller, and the test caught what that
     * means: a small CMYK JPEG re-encodes to more bytes than it arrived as, so the rule
     * handed back the original and the picture stayed undrawable. Size is not what this
     * is for. There is no way to ask the engine whether it can paint something, so the one
     * that certainly draws wins every time.
     */
    return (if (opaque) "image/jpeg" else "image/png") to encoded
}

/**
 * A small picture for a file just picked from disk.
 *
 * The full image is what gets uploaded. This is only what sits beside the name, and a
 * photo from a phone is several megabytes of pixels the row will never show. Anything
 * that will not decode comes back null, and the row draws a glyph instead.
 */
internal fun scaledPreview(bytes: ByteArray, edge: Int = 112): ImageBitmap? = runCatching {
    val image = Image.makeFromEncoded(bytes)
    val longest = maxOf(image.width, image.height)
    if (longest <= 0) return null
    if (longest <= edge) return image.toComposeImageBitmap()
    val scale = edge.toFloat() / longest
    val width = (image.width * scale).toInt().coerceAtLeast(1)
    val height = (image.height * scale).toInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.drawImageRect(
        image,
        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeWH(width.toFloat(), height.toFloat()),
    )
    // Encoded and read back so the bitmap does not keep the surface it was drawn on.
    val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
    surface.close()
    png?.let { Image.makeFromEncoded(it).toComposeImageBitmap() }
}.getOrNull()

/**
 * Characters that arrive in mail and come out as an empty box.
 *
 * A narrow no-break space is the one that actually turns up, in every message written on a
 * Mac and in every Outlook forwarded header: `From:` followed by U+202F, a date with U+202F
 * before the AM. It is a space, it is invisible when a font has it, and it is a hollow
 * rectangle when the font the message chose does not. Three of them in one signature is
 * what "strange text artifacts" meant.
 *
 * Replaced with the ordinary space they are, rather than hoping for a font that has them.
 * Nothing is lost: none of these carry meaning a reader could act on, and a line break
 * that a no-break space was holding together is a better outcome than a box in the middle
 * of a sentence. A zero-width one becomes nothing at all rather than a space, because
 * those sit inside words.
 */
internal fun withoutTofu(html: String): String =
    html.replace(INVISIBLE_SPACE, " ").replace(NOTHING_AT_ALL, "")

/** Figure, punctuation, thin and hair spaces, and the narrow no-break space. */
private val INVISIBLE_SPACE = Regex("[\u2007-\u200A\u202F]")

/**
 * The zero-width ones, and a byte order mark that arrived as a character.
 *
 * Nothing rather than a space: these sit inside words, so replacing them would break the
 * word in half instead of joining it back up.
 */
private val NOTHING_AT_ALL = Regex("[\u200B-\u200D\uFEFF]")
