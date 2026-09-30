package org.rampart

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A proposed mailbox write. Only the Confirm button can reach the write path. */
@Composable
internal fun MailChangeCardView(card: MailChangeCard, onConfirm: (Int) -> Unit, onCancel: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(12.dp),
    ) {
        Text(card.action, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "${card.accountName}: ${card.messages.size} ${if (card.messages.size == 1) "message" else "messages"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(8.dp))
        card.messages.take(5).forEach { Text(it.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.bodyMedium) }
        if (card.messages.size > 5) Text("And ${card.messages.size - 5} more.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        when (card.status) {
            CardStatus.WAITING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConfirm(card.number) }) { Text("Confirm") }
                TextButton(onClick = { onCancel(card.number) }) { Text("Cancel") }
            }
            CardStatus.APPLYING -> Text("Changing it.", style = MaterialTheme.typography.bodySmall)
            CardStatus.FAILED -> Text(card.outcome ?: "That did not work.", color = MaterialTheme.colorScheme.error)
            CardStatus.DONE, CardStatus.DISMISSED -> Text(
                card.outcome.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
