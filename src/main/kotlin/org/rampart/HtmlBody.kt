package org.rampart

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.style.TextAlign

/**
 * The message, drawn where the sender put things.
 *
 * Pictures sit in the flow rather than in a heap underneath, quoted mail has an edge down
 * its side, a list looks like a list, and a table of figures stays a table. Everything here
 * is drawn from [Block]s that have already been through the cleaner, so nothing in this file
 * ever sees a tag or an attribute: by the time it runs, the dangerous half is gone.
 */
@Composable
internal fun HtmlBody(
    doc: HtmlDoc,
    /** Pictures the message carries, by the `cid` the body refers to them by. */
    carried: Map<String, ImageBitmap>,
    /** Pictures fetched from the web once somebody said to, by the address they came from. */
    fetched: Map<String, ImageBitmap>,
) {
    SelectionContainer {
        Column(Modifier.fillMaxWidth()) {
            doc.blocks.forEach { Draw(it, carried, fetched) }
        }
    }
}

/** Whether the cell being drawn asked for its contents to be centred. */
private val LocalCellCentred = staticCompositionLocalOf { false }

/**
 * The colour to write in, when the cell underneath has one of its own.
 *
 * Unspecified everywhere else, which means the theme decides, which is right for a message
 * that states no colours. It matters where a cell does: the banner on a hotel report is
 * white on dark brown, and Rampart does not read text colour, so it wrote the theme's near
 * black onto the brown and the name of the hotel disappeared.
 *
 * Chosen from the background rather than read from the mail, because it cannot be wrong.
 * A sender who states a colour we ignored still gets readable text; a sender who states
 * nothing, which is most of them, gets readable text as well.
 */
private val LocalCellInk = staticCompositionLocalOf { Color.Unspecified }

/**
 * Black or white, whichever can be read on [background].
 *
 * The weights are the usual perceptual ones: the eye takes far more brightness from green
 * than from blue, so a flat average calls mid blue light and puts black on it.
 */
internal fun inkFor(background: Int): Color {
    val colour = Color(background)
    val luminance = 0.299f * colour.red + 0.587f * colour.green + 0.114f * colour.blue
    return if (luminance > 0.6f) Color(0xFF1A1A1A) else Color(0xFFFAFAFA)
}

@Composable
private fun ColumnScope.Draw(
    block: Block,
    carried: Map<String, ImageBitmap>,
    fetched: Map<String, ImageBitmap>,
) {
    when (block) {
        is Block.Words -> {
        val centred = LocalCellCentred.current || block.centred
        Text(
            block.text,
            style = MaterialTheme.typography.bodyLarge,
            // Told to the paragraph, because the column's alignment never reaches the words
            // inside it. **And the paragraph has to be given the width first:** a Text in a
            // column is only as wide as its own characters, so centring inside it moves a
            // long paragraph, which already fills the line, and does nothing at all to a
            // one word heading, which is exactly the case somebody notices.
            textAlign = if (centred) TextAlign.Center else null,
            color = LocalCellInk.current,
            // Headings step down from a size that is clearly a heading to one that is barely
            // one, which is what h1 to h6 mean. Level 0 is ordinary text and keeps its size.
            fontSize = if (block.level == 0) MaterialTheme.typography.bodyLarge.fontSize
            else (24 - block.level * 2).sp,
            fontWeight = if (block.level == 0) null else FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 10.dp)
                .then(if (centred) Modifier.fillMaxWidth() else Modifier),
        )
        }

        // IntrinsicSize.Min on the row is what lets the edge be as tall as the quote: a
        // Spacer has no height of its own, so without it the line does not appear at all.
        is Block.Quote -> Row(Modifier.padding(bottom = 10.dp).height(IntrinsicSize.Min)) {
            Spacer(
                Modifier.width(3.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Column(Modifier.padding(start = 12.dp)) {
                block.inner.forEach { Draw(it, carried, fetched) }
            }
        }

        is Block.Listing -> Column(Modifier.padding(bottom = 10.dp)) {
            block.items.forEachIndexed { at, item ->
                Row(Modifier.padding(bottom = 3.dp)) {
                    Text(
                        if (block.ordered) "${at + 1}." else "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.width(26.dp),
                    )
                    Text(item, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        is Block.Picture -> {
            val bitmap = remember(block.src, carried, fetched) {
                when {
                    block.src.startsWith("cid:", true) ->
                        carried[block.src.substring(4).trim().trim('<', '>')]
                    // Carried inside the body itself, so there is nothing to fetch and
                    // nothing to agree to. This is how our own signatures hold a logo.
                    block.src.startsWith("data:image/", true) -> embeddedImage(block.src)
                    else -> fetched[block.src]
                }
            }
            if (bitmap != null) {
                // A picture is as wide as it is, so centring it has to be done by the box
                // around it rather than by the image. Told to the image it does nothing,
                // which is why a centred masthead still sat against the left margin.
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = if (block.centred || LocalCellCentred.current) {
                        Alignment.TopCenter
                    } else {
                        Alignment.TopStart
                    },
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = block.alt.ifBlank { null },
                        // Inside rather than Fit, so a small logo stays a small logo. Fit blew a
                        // signature image up to the width of the reading pane.
                        contentScale = ContentScale.Inside,
                        modifier = Modifier
                            .sizeIn(
                                maxWidth = (block.width ?: 620).coerceAtMost(620).dp,
                                maxHeight = 520.dp,
                            )
                            .padding(bottom = 10.dp),
                    )
                }
            } else if (block.alt.isNotBlank()) {
                // Only when the sender wrote one. An empty alt on a held back picture is a
                // spacer or a tracking pixel, and announcing those is worse than silence.
                Text(
                    block.alt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }

        /*
         * Cells side by side, each drawing its own blocks.
         *
         * Widths come from the table where it states them and are shared evenly where it
         * does not, which is what a browser does with no other instruction. A cell holds
         * blocks rather than a line of text, so a figure with a label under it stays a
         * figure with a label under it, and a nested table inside a cell draws itself.
         */
        is Block.Layout -> Column(Modifier.padding(bottom = 10.dp)) {
            block.rows.forEachIndexed { at, row ->
                Row(
                    Modifier.fillMaxWidth()
                        .then(row.background?.let { Modifier.background(Color(it)) } ?: Modifier),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.cells.forEach { cell ->
                        Column(
                            Modifier.weight(cell.weight)
                                .then(cell.background?.let { Modifier.background(Color(it)) } ?: Modifier)
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            horizontalAlignment =
                                if (cell.centred) Alignment.CenterHorizontally else Alignment.Start,
                        ) {
                            CompositionLocalProvider(
                                LocalCellCentred provides cell.centred,
                                LocalCellInk provides (
                                    (cell.background ?: row.background)?.let { inkFor(it) }
                                        ?: LocalCellInk.current
                                    ),
                            ) {
                                cell.blocks.forEach { Draw(it, carried, fetched) }
                            }
                        }
                    }
                }
                // Only a table of data gets lines. A layout table is furniture, and a border
                // round somebody's newsletter is not in their design.
                if (block.ruled && at < block.rows.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        Block.Rule -> HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }
}

/** Everything the blocks say, as one string, for a preview or a plain text copy. */
internal fun flatten(blocks: List<Block>): AnnotatedString = AnnotatedString.Builder().apply {
    fun walk(list: List<Block>) {
        list.forEach { block ->
            when (block) {
                is Block.Words -> { append(block.text); append('\n') }
                is Block.Quote -> walk(block.inner)
                is Block.Listing -> block.items.forEach { append("- "); append(it); append('\n') }
                is Block.Layout -> block.rows.forEach { row ->
                    row.cells.forEachIndexed { at, cell ->
                        if (at > 0) append('\t')
                        walk(cell.blocks)
                    }
                    append('\n')
                }
                is Block.Picture -> if (block.alt.isNotBlank()) { append(block.alt); append('\n') }
                Block.Rule -> append("\n")
            }
        }
    }
    walk(blocks)
}.toAnnotatedString()

/**
 * A `data:` picture, decoded, or null when it is not one we can draw.
 *
 * Capped well below what a message is allowed to be. A body can claim to hold a hundred
 * megabyte picture, and finding out by decoding it is the expensive way to learn that.
 */
internal fun embeddedImage(src: String, limit: Int = 8 * 1024 * 1024): ImageBitmap? = runCatching {
    val comma = src.indexOf(',')
    if (comma < 0 || !src.substring(0, comma).endsWith(";base64", ignoreCase = true)) return null
    val encoded = src.substring(comma + 1)
    // Four base64 characters are three bytes, so the length says how big it is without
    // decoding it first.
    if (encoded.length / 4 * 3 > limit) return null
    val bytes = java.util.Base64.getMimeDecoder().decode(encoded)
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()
