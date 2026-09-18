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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A meeting invitation, drawn as the meeting rather than as a file called invite.ics.
 *
 * This is the part of a calendar that happens inside a mail client, and the reason it comes
 * before a calendar view rather than after one: it works against any server, including a
 * plain IMAP account with no calendar anywhere near it, because everything shown here came
 * in the message.
 *
 * Three shapes, decided by METHOD. A request is answerable and carries the buttons. A
 * cancellation says so and carries none, because there is nothing left to accept. A reply
 * is somebody else's answer arriving, and the only useful thing to do with it is show who
 * said what.
 */
@Composable
internal fun InvitationCard(
    invitation: Invitation,
    /** The address this copy was addressed to, which is the one that answers. */
    me: String,
    /** Null while an answer is being sent, so the buttons cannot be pressed twice. */
    onAnswer: ((Rsvp) -> Unit)?,
) {
    val cancelled = invitation.method.equals("CANCEL", ignoreCase = true)
    val isReply = invitation.method.equals("REPLY", ignoreCase = true)
    val mine = invitation.attendees.firstOrNull { it.email.equals(me, ignoreCase = true) }
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            when {
                cancelled -> "CANCELLED"
                isReply -> "REPLY"
                else -> "INVITATION"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (cancelled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            invitation.summary.ifBlank { "Untitled meeting" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            // A cancelled meeting is struck through in every calendar there has ever been,
            // and it is the one thing on this card that has to read at a glance.
            textDecoration = if (cancelled) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
        )
        Spacer(Modifier.height(10.dp))

        val at = whenText(invitation.starts, invitation.ends)
        Field("When", listOf(at, invitation.repeats).filter { it.isNotBlank() }.joinToString(". "))
        Field("Where", invitation.location)
        Field("Organiser", invitation.organiser?.let(::personText).orEmpty())
        val (shown, more) = shownRecipients(invitation.attendees.map(::personText), upTo = 3)
        Field(
            "Guests",
            if (shown.isEmpty()) "" else shown.joinToString(", ") + if (more > 0) " and $more more" else "",
        )

        if (isReply) {
            // The whole content of a reply is the answer, so it is said plainly rather than
            // left as a PARTSTAT on a guest line nobody reads.
            val who = invitation.attendees.firstOrNull()
            Field("Answer", who?.let { "${personText(it)} ${answerWord(it.status)}" }.orEmpty())
            return@Column
        }
        if (cancelled) return@Column

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Rsvp.entries.forEach { answer ->
                val chosen = mine?.status.equals(answer.partstat, ignoreCase = true)
                // The answer already given is the filled button, so the card says what was
                // answered without a line of text saying it.
                if (chosen) {
                    Button(
                        onClick = { onAnswer?.invoke(answer) },
                        enabled = onAnswer != null,
                        colors = ButtonDefaults.buttonColors(),
                    ) { Text(answer.word) }
                } else {
                    OutlinedButton(
                        onClick = { onAnswer?.invoke(answer) },
                        enabled = onAnswer != null,
                    ) { Text(answer.button) }
                }
                Spacer(Modifier.width(8.dp))
            }
        }
        if (mine == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                // Worth saying rather than hiding the buttons: forwarded invitations are
                // normal, and answering one still reaches the organiser.
                "You are not on the guest list. An answer still goes to the organiser.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** A label and a value, with nothing drawn at all when there is no value. */
@Composable
private fun Field(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.Start) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(84.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A guest as one readable string. The address alone when that is all the invitation gave. */
internal fun personText(who: Invitee): String =
    who.name.ifBlank { who.email }.let { if (who.name.isBlank() || who.email.isBlank()) it else "$it <${who.email}>" }

/** A PARTSTAT as a person would say it. */
internal fun answerWord(status: String): String = when (status.uppercase()) {
    "ACCEPTED" -> "accepted"
    "DECLINED" -> "declined"
    "TENTATIVE" -> "answered maybe"
    "NEEDS-ACTION", "" -> "has not answered"
    "DELEGATED" -> "passed it on"
    else -> status.lowercase()
}
