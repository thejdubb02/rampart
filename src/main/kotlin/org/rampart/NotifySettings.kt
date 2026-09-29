package org.rampart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalTime

/**
 * Quiet hours and which accounts may notify, under the new-mail switch.
 *
 * Its own file so the Notifications page gains one line rather than a screenful, and so the
 * two settings are read beside [NewMailNotices], which is what obeys them.
 */
@Composable
internal fun NewMailNotifySettings(accounts: List<AccountMailboxes>, enabled: Boolean) {
    val saved = remember { QuietHours.parse(Settings.quietHours()) }
    var quietOn by remember { mutableStateOf(saved != null) }
    var from by remember { mutableStateOf(saved?.from?.toString() ?: "22:00") }
    var until by remember { mutableStateOf(saved?.until?.toString() ?: "07:00") }
    val parsed = QuietHours.parse("$from-$until")

    // Saved only when both times read, so a half-typed "2" does not switch quiet hours off.
    fun save(on: Boolean = quietOn, hours: QuietHours? = parsed) {
        if (!on) Settings.setQuietHours(null) else if (hours != null) Settings.setQuietHours(hours.encoded())
    }

    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { quietOn = !quietOn; save(on = quietOn) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Quiet hours", style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    !quietOn -> "Notify at any time of day."
                    parsed == null -> "Write each time as 22:00."
                    else -> "No new-mail notifications from ${parsed.from} to ${parsed.until}. The unread count still shows."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (quietOn && parsed == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = quietOn, onCheckedChange = { quietOn = it; save(on = it) }, enabled = enabled)
    }
    if (quietOn) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = from,
                onValueChange = { from = it; save(hours = QuietHours.parse("$it-$until")) },
                label = { Text("From") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.width(110.dp),
            )
            OutlinedTextField(
                value = until,
                onValueChange = { until = it; save(hours = QuietHours.parse("$from-$it")) },
                label = { Text("Until") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.width(110.dp),
            )
            if (parsed != null && parsed.covers(LocalTime.now(Regional.zone()))) {
                Text("Quiet now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }

    // One account has the main switch above, which already says everything this would.
    if (accounts.size > 1) {
        var silenced by remember { mutableStateOf(Settings.silencedAccounts()) }
        Spacer(Modifier.height(8.dp))
        Text("Accounts that notify", style = MaterialTheme.typography.labelLarge)
        accounts.forEach { account ->
            val on = account.key !in silenced
            fun flip() {
                Settings.setAccountNotifies(account.key, !on)
                silenced = Settings.silencedAccounts()
            }
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) { flip() }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(account.name.ifBlank { account.email }, style = MaterialTheme.typography.bodyMedium)
                    if (account.name.isNotBlank() && account.name != account.email) {
                        Text(account.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                Switch(checked = on, onCheckedChange = { flip() }, enabled = enabled)
            }
        }
    }
}
