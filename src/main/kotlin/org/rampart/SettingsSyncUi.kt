package org.rampart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * The two places settings sync touches the window: keeping it told which accounts are
 * signed in, and the section on the Accounts page. The logic is in SettingsSync.kt.
 */

/**
 * Keeps [SettingsSync] following the signed-in accounts, and tells [onApplied] which
 * settings the server changed so the window can redraw them without a restart.
 *
 * [onApplied] runs on the window's thread: the sync thread's news is handed across with
 * invokeLater, the same way the single-instance wake-up reaches the window in Main.kt.
 */
@Composable
internal fun SettingsSyncFollows(sessions: List<Session>, onApplied: (Set<String>) -> Unit) {
    // The backend as well as the key, so signing in again with a new session is followed too.
    val signedIn = sessions.map { it.key to it.jmap }
    LaunchedEffect(signedIn) {
        SettingsSync.follow(sessions.map { syncTargetFor(it.key, it.account.email, it.jmap) })
    }
    val latest by rememberUpdatedState(onApplied)
    DisposableEffect(Unit) {
        val listener: (Set<String>) -> Unit = { changed ->
            javax.swing.SwingUtilities.invokeLater { latest(changed) }
        }
        SettingsSync.addAppliedListener(listener)
        onDispose { SettingsSync.removeAppliedListener(listener) }
    }
}

/**
 * What [SettingsSync] should do for one account's backend: its Files, or the one sentence
 * saying why it has none.
 */
internal fun syncTargetFor(key: String, label: String, backend: MailBackend): SyncTarget {
    val jmap = backend as? Jmap
        ?: return SyncTarget(key, label, null, "This account is IMAP, which has nowhere to keep Rampart's settings.")
    val link = jmap.fileStore()?.link
        ?: return SyncTarget(key, label, null, "This server does not offer file storage, which is where synced settings are kept.")
    return SyncTarget(key, label, link, null)
}

/**
 * The section on the Accounts page: the per-computer switch and one line per account
 * saying when it last synced, or why it did not.
 */
@Composable
internal fun SettingsSyncSection() {
    var on by remember { mutableStateOf(SettingsSync.enabled()) }
    var lines by remember { mutableStateOf(SettingsSync.statuses()) }
    DisposableEffect(Unit) {
        val listener: (List<Pair<String, SyncStatus>>) -> Unit = { next ->
            javax.swing.SwingUtilities.invokeLater { lines = next }
        }
        SettingsSync.addStatusListener(listener)
        onDispose { SettingsSync.removeStatusListener(listener) }
    }
    fun flip(value: Boolean) {
        on = value
        SettingsSync.setEnabled(value)
    }

    Spacer(Modifier.height(30.dp))
    Section(
        "Settings on your other computers",
        "Themes, list density, saved searches, reading choices and Rook's never-read folders " +
            "follow you, through a file named settings.json in a folder named Rampart in each " +
            "account's Files. Passwords, keys, window positions and anything about this " +
            "computer never leave it.",
    )
    Row(
        Modifier.fillMaxWidth().clickable { flip(!on) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Sync settings through the server", style = MaterialTheme.typography.bodyMedium)
            Text(
                "This switch is for this computer only and is never synced itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = on, onCheckedChange = { flip(it) })
    }
    Spacer(Modifier.height(8.dp))
    if (lines.isEmpty()) {
        Text(
            "No account is signed in yet, so settings stay in the file on this computer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    for ((label, status) in lines) {
        if (status is SyncStatus.Failed) {
            FaultText(
                title = "$label is not synced.",
                detail = syncStatusLine(status).removePrefix("Not synced: "),
                modifier = Modifier.padding(vertical = 2.dp),
            )
        } else {
            Text(
                "$label: ${syncStatusLine(status)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
