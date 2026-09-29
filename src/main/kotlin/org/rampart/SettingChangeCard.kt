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

/**
 * A setting change Rook asked for, waiting for a person.
 *
 * The before and after are drawn with the admin console's own [ChangeList], so a change
 * proposed here reads exactly like one made there. Confirm is the only way the change
 * happens, and it is a button in the window: nothing the model writes can press it.
 */
@Composable
internal fun SettingChangeCard(card: ChangeCard, onConfirm: (Int) -> Unit, onDismiss: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(12.dp),
    ) {
        Text(card.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Changed with " + when (card.home) {
                SettingHome.COMPUTER -> "nothing but this computer's settings file."
                SettingHome.MAILBOX -> "your own mail sign-in."
                SettingHome.SERVER -> "the separate admin sign-in."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(8.dp))
        ChangeList(card.lines.map { AdminChange(it.name, it.beforeWords, it.afterWords) })
        Spacer(Modifier.height(10.dp))
        when (card.status) {
            CardStatus.WAITING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConfirm(card.number) }) { Text("Confirm") }
                TextButton(onClick = { onDismiss(card.number) }) { Text("Leave it") }
            }
            CardStatus.APPLYING -> Text(
                "Changing it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            CardStatus.FAILED -> Text(
                card.outcome ?: "That did not work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            CardStatus.DONE, CardStatus.DISMISSED -> Text(
                card.outcome.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
