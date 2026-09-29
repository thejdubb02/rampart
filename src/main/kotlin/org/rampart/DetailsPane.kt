package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    SelectionContainer {
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
                    body?.to.orEmpty().filter { it.isNotBlank() }
                        .forEachIndexed { at, who -> Line(if (at == 0) "To" else "", who) }
                    body?.cc.orEmpty().filter { it.isNotBlank() }
                        .forEachIndexed { at, who -> Line(if (at == 0) "Cc" else "", who) }
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
                Summary(checks.filter { it.label in SENDER_CHECKS })
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
                // Only when a scanner wrote something. No chip on a server without one,
                // because "not checked" on every message says nothing about any of them.
                virusVerdictOf(body?.serverVerdicts.orEmpty())?.let {
                    Verdict(Detail("Virus scan", it.says, if (it.infected) Check.FAIL else Check.PASS))
                }

                Spacer(Modifier.height(14.dp))
                Heading("Message properties")
                Line("Subject", summary.subject)
                Line("Size", humanBytes(body?.size ?: 0L))
            }
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
    val colour = verdictColour(detail.verdict)
    Row(
        Modifier
            .padding(bottom = 5.dp)
            .clip(RoundedCornerShape(50))
            .background(colour.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (detail.verdict == Check.MISSING) {
            // An empty ring rather than a tick: a tick beside "not checked" reads as a pass.
            Box(Modifier.size(11.dp).border(1.5.dp, colour, CircleShape))
        } else {
            Icon(
                if (detail.verdict == Check.FAIL) RampartIcons.Close else RampartIcons.Tick,
                contentDescription = null,
                tint = colour,
                modifier = Modifier.width(13.dp).height(13.dp),
            )
        }
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
            fontWeight = FontWeight.SemiBold,
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

/** The three checks that say whether the sender is who they claim to be. */
private val SENDER_CHECKS = setOf("SPF", "DKIM", "DMARC")

/**
 * One line above the checks that says what they add up to, so the answer does not have to
 * be worked out from three acronyms.
 *
 * Any failure is red, because a forged sender is the thing this panel exists to catch.
 * All three passing is green. Anything in between is amber: not proof of a forgery, but not
 * proof of the sender either. Nothing checked at all is grey, which is ordinary for mail
 * that never left your own server.
 */
@Composable
private fun Summary(checks: List<Detail>) {
    if (checks.isEmpty()) return
    val verdicts = checks.map { it.verdict }
    val (colour, text) = when {
        Check.FAIL in verdicts -> verdictColour(Check.FAIL) to
            "Failed a sender check. This may not be from who it says. Be careful with links and attachments."
        verdicts.all { it == Check.PASS } -> verdictColour(Check.PASS) to "Sender verified."
        verdicts.all { it == Check.MISSING } -> MaterialTheme.colorScheme.outline to
            "No sender checks were recorded for this message."
        else -> amber() to "Only partly verified. Treat unexpected requests with care."
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = colour,
        modifier = Modifier
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colour.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * Green for a pass, red for a failure, amber for not checked.
 *
 * Not the theme's primary colour for a pass: in Rampart that is red, and a passing check
 * drawn in red reads as a warning. The greens and ambers are picked per light or dark
 * background so they keep their contrast on either.
 */
@Composable
private fun verdictColour(check: Check): Color = when (check) {
    Check.PASS -> if (dark()) Color(0xFF3FB950) else Color(0xFF1A7F37)
    Check.FAIL -> MaterialTheme.colorScheme.error
    Check.MISSING -> amber()
}

@Composable
private fun amber(): Color = if (dark()) Color(0xFFD29922) else Color(0xFF9A6700)

@Composable
private fun dark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f
