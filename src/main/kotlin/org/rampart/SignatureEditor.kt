package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * An HTML sign-off, edited by someone who does not write HTML.
 *
 * The buttons put tags around whatever is selected and the preview underneath shows what
 * the result actually looks like, drawn by the same renderer that draws received mail. The
 * source stays visible rather than hidden behind a rich text box, because a rich text box
 * is a much larger thing to build and a much harder one to be sure of: this way the worst
 * case is that somebody sees a tag, not that their sign-off silently comes out wrong.
 */
@Composable
internal fun SignatureEditor(
    address: String,
    html: String,
    onHtml: (String) -> Unit,
    /** Opens a picker and returns a ready `data:` URI, or null if nothing was chosen. */
    onAddImage: () -> String?,
) {
    // The selection has to be held here, not derived from the string, or every button would
    // have to guess where the caret is.
    var value by remember(address) { mutableStateOf(TextFieldValue(html)) }
    // Only when the change came from somewhere else. Typing sets both, so they agree by the
    // next composition and the caret is left alone.
    if (value.text != html) value = value.copy(text = html)

    fun edit(next: TextFieldValue) {
        value = next
        onHtml(next.text)
    }

    Text(
        address,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = { edit(wrapSelection(value, "<b>", "</b>")) }) { Text("Bold") }
        TextButton(onClick = { edit(wrapSelection(value, "<i>", "</i>")) }) { Text("Italic") }
        TextButton(onClick = { edit(wrapSelection(value, "<a href=\"https://\">", "</a>")) }) { Text("Link") }
        TextButton(
            onClick = {
                // Nothing chosen is not a failure, so nothing happens and nothing is said.
                onAddImage()?.let { uri -> edit(insertAt(value, "<img src=\"$uri\" style=\"max-width:220px\">")) }
            },
        ) { Text("Image") }
    }
    Box(
        Modifier.fillMaxWidth().height(120.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = ::edit,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        )
    }

    val tooLong = value.text.length > SIGNATURE_LIMIT
    Text(
        if (tooLong) {
            "${value.text.length} characters. Most servers stop near $SIGNATURE_LIMIT and will " +
                "refuse to save this. A picture added with Image is what does it."
        } else {
            "${value.text.length} characters"
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 6.dp),
    )

    Text(
        "Preview",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
    val linkColor = MaterialTheme.colorScheme.primary
    val quoteColor = MaterialTheme.colorScheme.outline
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        if (html.isBlank()) {
            Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = quoteColor)
        } else {
            // A preview is not for clicking, so the links do nothing. Drawn by the same
            // renderer as received mail, which is the point: this is how it will look,
            // embedded picture included.
            val rendered = remember(html, linkColor) { htmlBlocks(html, linkColor, quoteColor) {} }
            // Fetched rather than held back, unlike a message. A signature is one you wrote
            // yourself, so there is nobody to be told it was opened and nothing being
            // decided on your behalf. Showing a placeholder where your own logo goes is the
            // one thing this preview must not do: it is the only place you can check it.
            var pictures by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
            // Tracked separately from the map being empty, or the line below would accuse
            // every signature of a broken picture for as long as the fetch takes.
            var fetched by remember(html) { mutableStateOf(false) }
            LaunchedEffect(html) {
                pictures = fetchRemote(html)
                fetched = true
            }
            Column {
                HtmlBody(rendered, emptyMap(), pictures)
                if (fetched && rendered.blockedImages > pictures.size) {
                    Text(
                        "A picture here could not be loaded, so check the address. Note that a " +
                            "picture from the web is held back by most clients until the reader " +
                            "asks for it. It is still the only way to put a logo in a signature: " +
                            "one carried inside is far past what a mail server will store.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Puts [before] and [after] around the selection, or at the caret when there is none.
 *
 * With a selection, the new selection covers the same text, shifted right by the opening
 * tag. Select a word, press Bold, press Italic, and it ends up wrapped twice: without this
 * the second pair lands wherever the caret happened to fall.
 *
 * With no selection the caret ends up between the two tags, so typing continues inside them.
 */
internal fun wrapSelection(value: TextFieldValue, before: String, after: String): TextFieldValue {
    // A selection dragged right to left arrives with start after end.
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
    val text = value.text.substring(0, start) + before + value.text.substring(start, end) +
        after + value.text.substring(end)
    val selection = if (start == end) {
        TextRange(start + before.length)
    } else {
        TextRange(start + before.length, end + before.length)
    }
    return value.copy(text = text, selection = selection)
}

/** Drops [insert] in at the caret, replacing whatever was selected, caret after it. */
internal fun insertAt(value: TextFieldValue, insert: String): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
    val text = value.text.substring(0, start) + insert + value.text.substring(end)
    return value.copy(text = text, selection = TextRange(start + insert.length))
}

/**
 * A picture, as a `data:` URI ready to paste into the signature.
 *
 * Embedded rather than uploaded, because a signature is reused on every message and an
 * uploaded blob is not: it would have to be re-uploaded before each send and kept track of
 * in between. The cost is that base64 makes the picture about a third larger and that cost
 * is paid on every message, which is why there is a limit and why it is small. A logo is
 * a few tens of kilobytes; anything much past that is a photograph that does not belong
 * under every email.
 */
internal fun imageDataUri(file: Path, limit: Long = 96L * 1024): String {
    val size = Files.size(file)
    if (size > limit) {
        throw IllegalArgumentException(
            "${file.fileName} is ${humanSize(size)}. A signature picture has to stay under " +
                "${humanSize(limit)}, because it rides along on every message you send.",
        )
    }
    val type = runCatching { Files.probeContentType(file) }.getOrNull().orEmpty()
    if (!type.startsWith("image/")) {
        throw IllegalArgumentException("${file.fileName} is not a picture.")
    }
    return "data:$type;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(file))
}

/**
 * The same sign-off as plain text, for the text half of the message.
 *
 * Derived rather than typed twice. Two boxes to fill in is two things to keep in step, and
 * the one people forget is the plain one, which is the half that shows up in every client
 * that does not render HTML.
 */
internal fun plainOf(html: String): String =
    flatten(htmlBlocks(html, Color.Unspecified, Color.Unspecified) {}.blocks).text.trim()

/** One `data:` picture found in a signature, with its bytes already decoded. */
internal class SignaturePicture(val src: String, val type: String, val bytes: ByteArray)

// The base64 run ends at the quote that closes the attribute, which is why the character
// class does not include one. Whitespace is allowed inside it because a signature that has
// been through another client may have been line-wrapped.
private val DATA_IMAGE = Regex("""data:(image/[A-Za-z0-9.+-]+);base64,([A-Za-z0-9+/=\s]+)""")

/**
 * Every distinct `data:` picture in [html].
 *
 * Distinct by the URI itself, so the same logo referred to twice is uploaded and attached
 * once rather than riding along in the message twice.
 */
internal fun signaturePictures(html: String): List<SignaturePicture> =
    DATA_IMAGE.findAll(html)
        .mapNotNull { match ->
            val bytes = runCatching { Base64.getMimeDecoder().decode(match.groupValues[2]) }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SignaturePicture(match.value, match.groupValues[1], bytes)
        }
        .distinctBy { it.src }
        .toList()

/** The same signature with each `data:` picture pointing at a Content-ID instead. */
internal fun withCids(html: String, cids: Map<String, String>): String {
    var out = html
    cids.forEach { (src, cid) -> out = out.replace(src, "cid:$cid") }
    return out
}

/** A filename for an attached signature picture, so a client has something to label it. */
internal fun signaturePictureName(type: String): String =
    "signature." + type.substringAfter('/').substringBefore('+').ifBlank { "png" }
