package org.rampart

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

@Composable
private fun ColumnScope.Draw(
    block: Block,
    carried: Map<String, ImageBitmap>,
    fetched: Map<String, ImageBitmap>,
) {
    when (block) {
        is Block.Words -> Text(
            block.text,
            style = MaterialTheme.typography.bodyLarge,
            // Headings step down from a size that is clearly a heading to one that is barely
            // one, which is what h1 to h6 mean. Level 0 is ordinary text and keeps its size.
            fontSize = if (block.level == 0) MaterialTheme.typography.bodyLarge.fontSize
            else (24 - block.level * 2).sp,
            fontWeight = if (block.level == 0) null else FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        is Block.Quote -> Row(Modifier.padding(bottom = 10.dp)) {
            Spacer(
                Modifier.width(3.dp).height(IntrinsicMin)
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
            val bitmap = if (block.src.startsWith("cid:", ignoreCase = true)) {
                carried[block.src.substring(4).trim().trim('<', '>')]
            } else {
                fetched[block.src]
            }
            if (bitmap != null) {
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

        is Block.Grid -> Column(Modifier.padding(bottom = 10.dp)) {
            block.rows.forEachIndexed { at, cells ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    cells.forEach { cell ->
                        Text(
                            cell,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (at < block.rows.size - 1) {
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

/** The quote's edge runs the height of what it encloses, which is not known until layout. */
private val IntrinsicMin
    @Composable get() = androidx.compose.foundation.layout.IntrinsicSize.Min.let { 0.dp }

/** Everything the blocks say, as one string, for a preview or a plain text copy. */
internal fun flatten(blocks: List<Block>): AnnotatedString = AnnotatedString.Builder().apply {
    fun walk(list: List<Block>) {
        list.forEach { block ->
            when (block) {
                is Block.Words -> { append(block.text); append('\n') }
                is Block.Quote -> walk(block.inner)
                is Block.Listing -> block.items.forEach { append("- "); append(it); append('\n') }
                is Block.Grid -> block.rows.forEach { row ->
                    row.forEachIndexed { at, cell -> if (at > 0) append('\t'); append(cell) }
                    append('\n')
                }
                is Block.Picture -> if (block.alt.isNotBlank()) { append(block.alt); append('\n') }
                Block.Rule -> append("\n")
            }
        }
    }
    walk(blocks)
}.toAnnotatedString()
