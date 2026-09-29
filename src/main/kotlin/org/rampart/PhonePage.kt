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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files

/**
 * Settings, Your phone: everything a phone needs to sync this account's calendars and
 * contacts straight off the server, so that once it is set up, Rampart is not involved.
 *
 * Stalwart only, the same as the Security page next to it in the nav: the CalDAV and CardDAV
 * addresses below are Stalwart's own paths, and the app password button calls the same
 * server objects [AccountSecurity] does. Any other server is one sentence saying why there
 * is nothing here, the rule for a capability a server lacks everywhere else in Settings.
 */
@Composable
internal fun PhonePage(
    session: Session?,
    accounts: List<AccountMailboxes> = emptyList(),
    chosen: String? = null,
    onChoose: (String) -> Unit = {},
) {
    Section(
        "Your phone",
        "Calendars and contacts live on your mail server, and your phone syncs with it " +
            "directly, so a change you make anywhere shows up everywhere.",
    )
    SettingsAccountPicker(accounts, chosen, onChoose)
    val jmap = session?.jmap as? Jmap
    val hasCalendarsOrContacts = jmap?.let { it.hasCalendars() || it.hasContacts() } ?: false
    val unavailable = phoneSetupUnavailable(session?.account?.protocol, jmap?.managementAccountId, hasCalendarsOrContacts)
    if (session == null || jmap == null || unavailable != null) {
        Text(
            unavailable ?: "This account cannot be set up from here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }

    val host = hostOfServer(session.account.server)
    // Keyed on the account, the same as the Security page, so switching accounts in the
    // sidebar starts this page over rather than making a phone password for the wrong one.
    val security = remember(session.key, jmap) { AccountSecurity(jmap) }

    Section("What your phone needs", "Copy these into your phone's own account setup, below.")
    CopyField("Server", host)
    CopyField("User name", session.account.email)
    CopyField("Calendar address", caldavUrl(host))
    CopyField("Contacts address", carddavUrl(host))
    Spacer(Modifier.height(6.dp))
    PhonePassword(security)

    Spacer(Modifier.height(22.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(18.dp))

    var os by remember(session.key) { mutableStateOf("android") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = os == "android", onClick = { os = "android" }, label = { Text("Android") })
        FilterChip(selected = os == "iphone", onClick = { os = "iphone" }, label = { Text("iPhone") })
    }
    Spacer(Modifier.height(16.dp))
    if (os == "android") AndroidSteps(host) else IPhoneSteps(host, session.account.email)
}

/** A value the phone setup needs, shown with a copy button, the same way a secret is shown once. */
@Composable
private fun CopyField(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = { clipboard.setText(AnnotatedString(value)); copied = true }) {
                Text(if (copied) "Copied" else "Copy")
            }
        }
    }
}

/**
 * The password a phone signs in with, made the same way the Security page makes any other
 * app password: named so it can be found and revoked later, shown once, never kept by
 * Rampart.
 */
@Composable
private fun PhonePassword(security: AccountSecurity) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf<NewAppPassword?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Text("Password", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Your phone needs a password of its own, separate from your mailbox password, so it " +
            "can be revoked on its own if the phone is ever lost.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(8.dp))
    val current = made
    if (current != null) {
        NewSecret(current.secret, "App password for this phone", onDone = { made = null })
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                error = null
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching { security.createAppPassword("Phone", AppPasswordLife.NEVER) }
                    }
                    made = outcome.getOrNull()
                    error = outcome.exceptionOrNull()?.let(::whyFailed)
                    busy = false
                }
            },
        ) { Text(if (busy) "Making it" else "Make a password for this phone") }
    }
    error?.let {
        Spacer(Modifier.height(8.dp))
        FaultText(it)
    }
}

/** Android, by way of DAVx5, which speaks CalDAV and CardDAV directly and needs no server of its own. */
@Composable
private fun AndroidSteps(host: String) {
    Row(verticalAlignment = Alignment.Top) {
        QrCode(DAVX5_DOWNLOAD_URL, "QR code that opens the DAVx5 download page on your phone")
        Spacer(Modifier.width(20.dp))
        Column {
            Step(1, "Scan this with your phone's camera. It opens the DAVx5 download page: " +
                "free on F-Droid, or paid on Google Play.")
            Step(2, "Open DAVx5 and tap the plus button.")
            Step(3, "Choose \"Login with URL and user name\".")
            Step(4, "Base URL: https://$host/. User name and password: the ones above.")
            Step(5, "Create the account, then tick the calendars and address books to sync.")
            Step(6, "Open your phone's Calendar and Contacts apps. They are there.")
            Spacer(Modifier.height(4.dp))
            Text(
                "If DAVx5 asks to be excluded from battery optimisation, allow it, so it keeps syncing " +
                    "in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** iPhone, either from a setup file that only asks for the password, or entered by hand. */
@Composable
private fun IPhoneSteps(host: String, email: String) {
    var result by remember { mutableStateOf<String?>(null) }
    var worked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text("The easy way: a setup file", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Button(onClick = {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = mobileConfig(host, email).toByteArray(Charsets.UTF_8)
                    val path = uniqueIn(downloadsFolder(), "rampart-phone-setup.mobileconfig")
                    Files.write(path, bytes)
                    path
                }
            }
            result = outcome.fold({ "Saved to $it." }, ::whyFailed)
            worked = outcome.isSuccess
        }
    }) { Text("Save iPhone setup file") }
    result?.let {
        Spacer(Modifier.height(8.dp))
        if (worked) {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        } else {
            FaultText(it)
        }
    }
    Spacer(Modifier.height(10.dp))
    Step(1, "Send the file to your phone: AirDrop it, or email it to yourself and open the attachment there.")
    Step(2, "Open the file. On the phone, go to Settings, where it now says \"Profile Downloaded\".")
    Step(3, "Tap Install. When it asks for a password, use the app password above.")

    Spacer(Modifier.height(20.dp))
    Text("Or set it up by hand", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        "Calendar: Settings, Calendar, Accounts, Add Account, Other, Add CalDAV Account. " +
            "Server: $host. User name and password: the ones above.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Contacts: the same steps, under Settings, Contacts, Accounts, Add Account, Other, " +
            "Add CardDAV Account.",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun Step(number: Int, text: String) {
    Text("$number. $text", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
}
