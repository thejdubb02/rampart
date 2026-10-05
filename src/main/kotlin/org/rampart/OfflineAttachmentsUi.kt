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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/*
 * Attachments offline on screen: the setting under Settings, Accounts, and the one call that
 * opens a file from the kept copy. The rules are in OfflineAttachments.kt.
 */

/**
 * Fetches [attachment] into [into], from the copy kept on this computer when there is one
 * and from the server otherwise.
 *
 * The kept copy is used even with a connection, since it is the same bytes (a blob id names
 * fixed content, and the seal proves the file is the one written) and it saves a download.
 * Either way the file is scanned before it is handed back; see Defender.kt.
 */
internal fun Session.fetchAttachment(attachment: Attachment, into: Path): Path = Defender.check(
    OfflineAttachments.restore(key, { Secrets.mailKey(account) }, attachment, into)
        ?: jmap.download(attachment, into)
)

/** The same thing for the accounts the background pass should look after. */
internal fun offlineAccounts(sessions: List<Session>, inboxOf: (String) -> String?): List<OfflineAccount> =
    sessions.map { open ->
        OfflineAccount(
            key = open.key,
            backend = open.jmap,
            mailKey = { Secrets.mailKey(open.account) },
            inboxId = inboxOf(open.key),
            canFilterAttachment = open.jmap !is Imap,
        )
    }

/**
 * Settings, Accounts: keep attachments for offline use, per account, with the two caps and
 * how much room it is taking.
 *
 * Only the accounts signed in on this computer, not mailboxes shared with them: the copy is
 * kept under the signed-in account's own key.
 */
@Composable
internal fun OfflineAttachmentsSection(accounts: List<AccountMailboxes>) {
    val saved = remember(accounts) { runCatching { Accounts.read() }.getOrDefault(emptyList()) }
    val own = accounts.filter { a -> saved.any { "${it.email}@${it.server}" == a.key } }
    if (own.isEmpty()) return
    Spacer(Modifier.height(18.dp))
    Section(
        "Attachments offline",
        "Keeps the files from the last 30 days of each Inbox on this computer, encrypted, so they open " +
            "without a connection. Downloaded in the background, one at a time. Off until you turn it on.",
    )
    // Asked off the UI thread, since it may ask the operating system's keychain.
    LaunchedEffect(own.map { it.key }) {
        withContext(Dispatchers.IO) {
            own.forEach { a ->
                saved.firstOrNull { "${it.email}@${it.server}" == a.key }?.let { account ->
                    OfflineAttachments.noteKey(a.key) { Secrets.mailKey(account) }
                }
            }
        }
    }
    val status by OfflineAttachments.status.collectAsState()
    val scope = rememberCoroutineScope()
    own.forEach { account ->
        var prefs by remember(account.key) { mutableStateOf(OfflineAttachments.prefs(account.key)) }
        val state = status[account.key] ?: OfflineStatus()
        fun choose(next: OfflinePrefs) {
            prefs = next
            // Turning it off deletes the files, which can be a lot of them.
            scope.launch(Dispatchers.IO) { OfflineAttachments.setPrefs(account.key, next) }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(shortAccountName(account.name, account.email), style = MaterialTheme.typography.bodyMedium)
            Text(account.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            if (state.available == false) {
                Text(
                    OFFLINE_UNAVAILABLE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().clickable { choose(prefs.copy(on = !prefs.on)) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(checked = prefs.on, onCheckedChange = { choose(prefs.copy(on = it)) })
                    Spacer(Modifier.width(12.dp))
                    Text("Keep attachments for offline use", style = MaterialTheme.typography.bodyMedium)
                }
                if (prefs.on) {
                    CapChoice("Largest file", OFFLINE_FILE_CHOICES, prefs.perFile) { choose(prefs.copy(perFile = it)) }
                    CapChoice("In all", OFFLINE_TOTAL_CHOICES, prefs.total) { choose(prefs.copy(total = it)) }
                    Text(
                        "Using ${humanSize(state.usedBytes)} for ${state.files} " +
                            (if (state.files == 1) "file" else "files") +
                            (if (state.working) ", still downloading." else "."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    state.problem?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun CapChoice(label: String, options: List<Long>, chosen: Long, onPick: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp))
        options.forEach { option ->
            FilterChip(
                selected = option == chosen,
                onClick = { onPick(option) },
                label = { Text(humanSize(option), style = MaterialTheme.typography.bodySmall) },
            )
        }
    }
}
