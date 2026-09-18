package org.rampart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The mailbox, counted.
 *
 * The first screen in Rampart that webmail has no answer to, and it is built entirely out
 * of what is already on the machine. No pixel, no endpoint, no setting, no model. See
 * [MailStats] for why that is the point rather than a limitation.
 *
 * One number on here is about something still to do, which is the list of conversations
 * waiting on an answer. The rest is description. That ratio is deliberate: a dashboard
 * where everything demands action is one nobody opens twice.
 */
@Composable
internal fun DashboardPane(
    stats: MailStats?,
    accountName: String,
    onOpen: (Summary) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Text("How your mail is going", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            if (stats == null) "Counting." else "$accountName, the last 30 days, from the ${stats.kept} messages kept on this machine.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (stats == null) return@Column
        Spacer(Modifier.height(22.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            Figure("Arrived", stats.arrived.toString(), Modifier.weight(1f))
            Figure("Sent", stats.sent.sumOf { it.count }.toString(), Modifier.weight(1f))
            Figure(
                "Junk",
                "${percent(stats.junk, stats.arrived)}%",
                Modifier.weight(1f),
                detail = "${stats.junk} of ${stats.arrived}",
            )
            Figure(
                "You reply in",
                spokenMinutes(medianMinutes(stats.replyMinutes)).ifBlank { "no answer yet" },
                Modifier.weight(1f),
                detail = if (stats.replyMinutes.isEmpty()) "" else "across ${stats.replyMinutes.size} conversations",
            )
        }

        Spacer(Modifier.height(26.dp))
        Heading("What arrives, and what you send")
        Chart(stats.received, stats.sent)

        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Heading("Who writes to you")
                Ranked(stats.topSenders, "Nothing kept from the last 30 days.")
            }
            Column(Modifier.weight(1f)) {
                Heading("Unread, by how old it is")
                Ranked(stats.unread, "Nothing unread. That is the whole report.")
            }
        }

        Spacer(Modifier.height(26.dp))
        Heading("Waiting on you")
        if (stats.waiting.isEmpty()) {
            Empty("Nothing is waiting on an answer.")
        } else {
            // Oldest first, because the useful order for a list of things somebody has not
            // answered is the order in which they have been waiting.
            stats.waiting.forEach { message ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onOpen(message) }
                        .padding(vertical = 7.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(message.from, message.fromEmail, 24.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            message.subject.ifBlank { "No subject" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            message.from.ifBlank { message.fromEmail },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        message.receivedAt.asLocalTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

/** One big number with its name under it. */
@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier, detail: String = "") {
    Column(
        modifier.clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * Thirty days of arrivals and answers, side by side.
 *
 * Both series share one scale, which is the only way the comparison means anything: drawn
 * against their own maxima, a day of two sent messages would stand as tall as a day of
 * ninety received.
 */
@Composable
private fun Chart(received: List<DayCount>, sent: List<DayCount>) {
    val tallest = (received + sent).maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    val arrivals = MaterialTheme.colorScheme.primary
    // Neutral against the accent rather than a second accent. Rampart has eighteen themes
    // and in several of them primary and tertiary are two shades of the same hue, which
    // makes a two series chart one series with a texture.
    val answers = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Key("Arrived", arrivals)
            Spacer(Modifier.width(14.dp))
            Key("Sent", answers)
            Spacer(Modifier.width(14.dp))
            Text(
                "Tallest day: $tallest",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val slot = size.width / received.size.coerceAtLeast(1)
            val bar = (slot * 0.36f).coerceAtLeast(1f)
            received.forEachIndexed { at, day ->
                val left = at * slot
                fun draw(count: Int, colour: Color, offset: Float) {
                    if (count <= 0) return
                    val high = size.height * (count.toFloat() / tallest)
                    drawRect(
                        color = colour,
                        topLeft = androidx.compose.ui.geometry.Offset(left + offset, size.height - high),
                        size = androidx.compose.ui.geometry.Size(bar, high),
                    )
                }
                draw(day.count, arrivals, slot * 0.08f)
                draw(sent.getOrNull(at)?.count ?: 0, answers, slot * 0.50f)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            // Three labels, not thirty. A date under every bar at this width is a grey smear.
            listOf(received.firstOrNull(), received.getOrNull(received.size / 2), received.lastOrNull())
                .forEach { day ->
                    Text(
                        day?.day?.let(::axisDay).orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
        }
    }
}

@Composable
private fun Key(label: String, colour: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(10.dp).height(10.dp).background(colour))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

/** A ranked list with a bar behind each row, so the shape is readable without the numbers. */
@Composable
private fun Ranked(rows: List<Counted>, whenEmpty: String) {
    if (rows.isEmpty()) {
        Empty(whenEmpty)
        return
    }
    val most = rows.maxOf { it.count }.coerceAtLeast(1)
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    rows.forEach { row ->
        Box(Modifier.fillMaxWidth().height(30.dp).clip(MaterialTheme.shapes.extraSmall)) {
            Box(Modifier.fillMaxWidth(row.count.toFloat() / most).fillMaxHeight().background(tint))
            Row(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(row.count.toString(), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(3.dp))
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}
