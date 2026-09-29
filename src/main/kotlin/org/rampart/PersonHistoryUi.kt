package org.rampart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The top of a person's history, over the ordinary message list. See PersonHistory.kt for
 * where the rows and the numbers come from. The rows themselves are the list's own, so
 * every row action works on them exactly as it does in a folder.
 */

private val HISTORY_DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)

/** A stored instant as a day, in the reader's zone, or null for one that will not read. */
internal fun historyDay(instant: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
    instant?.let { runCatching { Instant.parse(it).atZone(zone).format(HISTORY_DAY) }.getOrNull() }

/** The counts line: how many each way, and the span of time they cover. */
internal fun historySummary(stats: PersonStats, zone: ZoneId = ZoneId.systemDefault()): String {
    fun messages(n: Int) = if (n == 1) "1 message" else "%,d messages".format(n)
    val counts = "${messages(stats.received)} from them, ${messages(stats.sent)} to them"
    val first = historyDay(stats.first, zone)
    val last = historyDay(stats.last, zone)
    return when {
        first == null && last == null -> counts
        first == last || last == null -> "$counts, on ${first ?: last}"
        first == null -> "$counts, most recently $last"
        else -> "$counts, from $first to $last"
    }
}

@Composable
internal fun PersonHeader(
    person: Correspondent,
    stats: PersonStats,
    loading: Boolean,
    /** Who answered, when it was not every server. See [answeredNote]. */
    note: String?,
    onWrite: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                person.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            person.normalised.firstOrNull()?.let { address ->
                Button(onClick = { onWrite(address) }) { Text("Write to") }
                Spacer(Modifier.width(6.dp))
            }
            TextButton(onClick = onClose) { Text("Close") }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                person.normalised.forEach { address ->
                    Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (loading && stats == PersonStats()) "Looking in every account." else historySummary(stats),
            style = MaterialTheme.typography.bodyMedium,
        )
        note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
