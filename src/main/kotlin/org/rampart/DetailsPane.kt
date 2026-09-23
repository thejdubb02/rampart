package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Everything the headers say, for when the one line summary is not enough.
 *
 * Deliberately behind a toggle. The authenticity badge above stays as it is, shown only
 * when something is worth saying, because a badge on every message is one nobody reads and
 * then it is not there on the message that matters. This is the other half: the working,
 * on request.
 */
@Composable
internal fun MessageDetails(
    summary: Summary,
    body: Body?,
    spamScore: Double?,
) {
    val recipients = (body?.to.orEmpty() + body?.cc.orEmpty()).filter { it.isNotBlank() }
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f)) {
                Heading("Recipients and routing")
                val (who, address) = displaySender(summary.from, summary.fromEmail)
                Line("From", listOfNotNull(who.takeIf { it != "(no sender)" }, address?.let { "<$it>" }).joinToString(" "))
                // Ordinary rather than alarming, which is why it lives here and not in a
                // banner: every mailing list and every ticketing system sets one. It is
                // still the address that would receive an answer, so it is worth being able
                // to look it up.
                body?.replyTo.orEmpty().filter { it.isNotBlank() }
                    .forEachIndexed { at, who -> Line(if (at == 0) "Reply to" else "", who) }
                recipients.forEachIndexed { at, who -> Line(if (at == 0) "To" else "", who) }
                // Both written out the same way, or the gap between them is not a
                // comparison, it is two different formats side by side.
                handling(body?.sentAt?.asFullLocalTime(), summary.receivedAt.asFullLocalTime())
                    .forEach { Line(it.label, it.value) }

                Spacer(Modifier.height(14.dp))
                Heading("Identifiers and threading")
                Line("Message ID", body?.messageId?.firstOrNull().orEmpty(), mono = true)
                Line("Thread ID", summary.threadId, mono = true)
            }
            Column(Modifier.weight(1f)) {
                Heading("Authentication and security")
                val checks = authChecks(body?.authenticationResults?.joinToString("\n")) +
                    listOfNotNull(senderHost(body?.received.orEmpty()))
                checks.forEach { Verdict(it) }
                spamScore?.let {
                    Verdict(
                        Detail(
                            "Spam score",
                            // Under five is what every scorer treats as ordinary mail, and
                            // saying "ham" next to the number is how the server's own logs
                            // put it.
                            "$it · ${if (it < 5.0) "ham" else "spam"}",
                            if (it < 5.0) Check.PASS else Check.FAIL,
                        ),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Heading("Message properties")
                Line("Subject", summary.subject)
                Line("Size", humanBytes(body?.size ?: 0L))
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** A label and a value. A value with nothing in it draws nothing rather than an empty row. */
@Composable
private fun Line(label: String, value: String, mono: Boolean = false) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A check, as a chip that says what it looked at.
 *
 * A pass is stated as plainly as a failure. A panel that only speaks up when something is
 * wrong leaves you unable to tell "checked and fine" from "never checked", and those are
 * very different things when you are deciding whether to trust a message.
 */
@Composable
private fun Verdict(detail: Detail) {
    val colour = when (detail.verdict) {
        Check.PASS -> MaterialTheme.colorScheme.primary
        Check.FAIL -> MaterialTheme.colorScheme.error
        Check.MISSING -> MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier.padding(bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (detail.verdict == Check.FAIL) RampartIcons.Close else RampartIcons.Tick,
            contentDescription = null,
            tint = colour,
            modifier = Modifier.width(13.dp).height(13.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            detail.label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            when (detail.verdict) {
                Check.PASS -> "PASS"
                Check.FAIL -> "FAIL"
                Check.MISSING -> "not checked"
            },
            style = MaterialTheme.typography.labelSmall,
            color = colour,
        )
        if (detail.value.isNotBlank()) {
            Spacer(Modifier.width(7.dp))
            Text(
                detail.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
