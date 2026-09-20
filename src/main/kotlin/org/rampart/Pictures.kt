package org.rampart

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

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
    html.replace(' ', ' ')   // narrow no-break space
        .replace(' ', ' ')   // figure space
        .replace(' ', ' ')   // punctuation space
        .replace(' ', ' ')   // thin space
        .replace(' ', ' ')   // hair space
        .replace("​", "")    // zero-width space
        .replace("‌", "")    // zero-width non-joiner
        .replace("‍", "")    // zero-width joiner
        .replace("﻿", "")    // byte order mark, arriving as a character
