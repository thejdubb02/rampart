package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, Security: the signed-in person's own password, two-step login and app passwords.
 *
 * Stalwart only, over JMAP, as that person. On any other account the page is one sentence
 * saying why there is nothing here, which is the rule for a capability a server lacks:
 * visible and explained, never an error and never silently missing.
 *
 * [session] is the account Settings is showing, or null before any account is signed in.
 */
@Composable
internal fun SecurityPage(session: Session?) {
    val jmap = session?.jmap as? Jmap
    Section(
        "Security",
        "Your own password, two-step login and app passwords, changed on the server as you, " +
            "with your own sign-in. Rampart never asks for administrator rights to do it.",
    )
    val unavailable = securityUnavailable(session?.account?.protocol, jmap?.managementAccountId)
    if (session == null || jmap == null || unavailable != null) {
        Text(
            unavailable ?: "This account cannot be managed from here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    // Keyed on the account, so switching accounts in the sidebar starts this page afresh
    // rather than showing one mailbox's app passwords under another's address.
    val key = session.key
    val security = remember(key, jmap) { AccountSecurity(jmap) }
    val scope = rememberCoroutineScope()
    var state by remember(key) { mutableStateOf<PasswordState?>(null) }
    var stateError by remember(key) { mutableStateOf<String?>(null) }
    var apps by remember(key) { mutableStateOf<List<AppPasswordInfo>?>(null) }
    var appsError by remember(key) { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { security.passwordState() } }
            state = read.getOrNull()
            stateError = read.exceptionOrNull()?.let(::whyFailed)
            val listed = withContext(Dispatchers.IO) { runCatching { security.appPasswords() } }
            apps = listed.getOrNull()
            appsError = listed.exceptionOrNull()?.let(::whyFailed)
        }
    }
    LaunchedEffect(key) { reload() }

    Text(
        "For ${session.account.email}",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(16.dp))

    val current = state
    when {
        stateError != null -> FaultText(stateError!!)
        current == null -> Spinner(size = 20.dp, thickness = 2.dp)
        !current.hasPassword -> Text(
            "This account signs in some other way than a password, so there is no password to change here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        else -> {
            PasswordSection(session, jmap, security, current.twoStepOn)
            Spacer(Modifier.height(22.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(18.dp))
            TwoStepSection(session, jmap, security, current.twoStepOn, onChanged = ::reload)
        }
    }

    Spacer(Modifier.height(22.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(18.dp))
    AppPasswordsSection(security, apps, appsError, onChanged = ::reload)
}

/**
 * Whether Rampart is signing in to this account with the password just typed as the
 * current one, and so has to follow it when it changes.
 *
 * Asked of the credential store rather than assumed. Rampart may already be on an app
 * password, and then a change to the mailbox password must leave it alone. With nothing
 * stored, the in-memory session was opened with a typed password, which is the mailbox
 * password, so that counts as yes.
 */
private fun signsInWith(account: SavedAccount, password: String): Boolean {
    val stored = runCatching { Secrets.load(account) }.getOrNull()
    return stored == null || stored == password
}

@Composable
private fun PasswordSection(session: Session, jmap: Jmap, security: AccountSecurity, twoStepOn: Boolean) {
    val scope = rememberCoroutineScope()
    var current by remember(session.key) { mutableStateOf("") }
    var new by remember(session.key) { mutableStateOf("") }
    var repeat by remember(session.key) { mutableStateOf("") }
    var code by remember(session.key) { mutableStateOf("") }
    var busy by remember(session.key) { mutableStateOf(false) }
    var result by remember(session.key) { mutableStateOf<String?>(null) }
    var worked by remember(session.key) { mutableStateOf(false) }

    Section(
        "Password",
        "The password for this mailbox everywhere it is used: webmail, other mail apps, and Rampart.",
    )
    SecretField(current, "Current password") { current = it; result = null }
    Spacer(Modifier.height(8.dp))
    SecretField(new, "New password") { new = it; result = null }
    Spacer(Modifier.height(8.dp))
    SecretField(repeat, "New password again") { repeat = it; result = null }
    if (twoStepOn) {
        Spacer(Modifier.height(8.dp))
        CodeField(code) { code = it; result = null }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !busy,
            onClick = {
                val problem = newPasswordProblem(current, new, repeat)
                    ?: if (twoStepOn && code.filter(Char::isDigit).length != Totp.DIGITS) {
                        "Type the six digit code from your authenticator app."
                    } else {
                        null
                    }
                if (problem != null) {
                    result = problem
                    worked = false
                    return@Button
                }
                busy = true
                result = null
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching {
                            // Asked before the change, while the old password is still the
                            // one to compare against. With two-step login on, Rampart cannot be
                            // signing in with the mailbox password, because a Basic sign-in has
                            // nowhere to put the code, so it is on an app password and stays there.
                            val follow = !twoStepOn && signsInWith(session.account, current)
                            security.changePassword(current, new, code.takeIf { twoStepOn })
                            if (follow) {
                                jmap.usePassword(session.account.email, new)
                                Secrets.store(session.account, new)
                                    ?.let { "Changed. Rampart could not keep the new one, so it will ask for it next time: $it" }
                                    ?: "Changed. Rampart has switched to the new password too."
                            } else {
                                "Changed. Rampart signs in with an app password, so it did not need to change."
                            }
                        }
                    }
                    result = outcome.getOrElse { whyFailed(it) }
                    worked = outcome.isSuccess
                    if (worked) {
                        current = ""
                        new = ""
                        repeat = ""
                        code = ""
                    }
                    busy = false
                }
            },
        ) { Text(if (busy) "Changing" else "Change password") }
        if (busy) {
            Spacer(Modifier.width(12.dp))
            Spinner(size = 20.dp, thickness = 2.dp)
        }
    }
    Outcome(result, worked)
}

/**
 * Two-step login, which Stalwart calls OTP authentication.
 *
 * Turning it on is the one change here that can lock somebody out, so it is done in the
 * order that cannot: Rampart makes the secret, the person scans it, Rampart checks a code
 * from their app against it, and only then is it sent to the server.
 *
 * It also stops the mailbox password working for mail apps. Stalwart has no way for IMAP,
 * SMTP or a JMAP Basic sign-in to carry a code, so once this is on every one of those
 * needs an app password, Rampart included. Where Rampart is signing in with the mailbox
 * password, it makes itself an app password first and switches to it, so turning this on
 * does not disconnect the app it was turned on from.
 */
@Composable
private fun TwoStepSection(
    session: Session,
    jmap: Jmap,
    security: AccountSecurity,
    on: Boolean,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var setting by remember(session.key, on) { mutableStateOf(false) }
    var secret by remember(session.key, on) { mutableStateOf(Totp.newSecret()) }
    var current by remember(session.key, on) { mutableStateOf("") }
    var code by remember(session.key, on) { mutableStateOf("") }
    var busy by remember(session.key, on) { mutableStateOf(false) }
    var result by remember(session.key) { mutableStateOf<String?>(null) }
    var worked by remember(session.key) { mutableStateOf(false) }
    /** The app password Rampart made for itself, shown once when it could not be kept. */
    var unkept by remember(session.key) { mutableStateOf<String?>(null) }

    Section(
        "Two-step login",
        if (on) {
            "On. Signing in to webmail asks for a code from your authenticator app as well as the password."
        } else {
            "Off. With it on, signing in to webmail also asks for a six digit code from an " +
                "authenticator app on your phone, so a stolen password is not enough on its own."
        },
    )

    if (on) {
        Text(
            "To turn it off, type your password and a current code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(10.dp))
        SecretField(current, "Current password") { current = it; result = null }
        Spacer(Modifier.height(8.dp))
        CodeField(code) { code = it; result = null }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    if (current.isEmpty() || code.filter(Char::isDigit).length != Totp.DIGITS) {
                        result = "Type your password and the six digit code from your authenticator app."
                        worked = false
                        return@OutlinedButton
                    }
                    busy = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching { security.disableTwoStep(current, code) }
                        }
                        result = outcome.fold(
                            { "Two-step login is off. App passwords you made keep working until you revoke them." },
                            { whyFailed(it) },
                        )
                        worked = outcome.isSuccess
                        busy = false
                        if (worked) onChanged()
                    }
                },
            ) { Text(if (busy) "Turning off" else "Turn off two-step login") }
            if (busy) {
                Spacer(Modifier.width(12.dp))
                Spinner(size = 20.dp, thickness = 2.dp)
            }
        }
        Outcome(result, worked)
        unkept?.let { NewSecret(it, "Rampart's own app password", onDone = { unkept = null }) }
        return
    }

    if (!setting) {
        OutlinedButton(onClick = { setting = true; result = null }) { Text("Set up two-step login") }
        Outcome(result, worked)
        return
    }

    val issuer = session.account.email.substringAfter('@', "").ifBlank { "Mail" }
    val uri = Totp.uri(secret, session.account.email, issuer)
    Text(
        "1. Scan this with an authenticator app, or type the key into it by hand.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.Top) {
        QrCode(uri, "QR code for your authenticator app")
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text("Key", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            SelectionContainer {
                Text(
                    Totp.grouped(secret),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { clipboard.setText(AnnotatedString(Totp.base32(secret))) }) { Text("Copy key") }
            Spacer(Modifier.height(6.dp))
            Text(
                "Time based, six digits, every 30 seconds. Most apps choose that without asking.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("2. Type the code the app shows, and your password.", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(10.dp))
    CodeField(code) { code = it; result = null }
    Spacer(Modifier.height(8.dp))
    SecretField(current, "Current password") { current = it; result = null }
    Spacer(Modifier.height(10.dp))
    Text(
        "Once this is on, the mailbox password alone no longer signs in to mail apps: each one needs " +
            "an app password, from the section below. Rampart makes one for itself if it needs to.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !busy,
            onClick = {
                val problem = when {
                    !Totp.matches(secret, code) ->
                        "That code does not match this key. Check the app added the key above, and that the phone's clock is right."
                    current.isEmpty() -> "Type your current password."
                    else -> null
                }
                if (problem != null) {
                    result = problem
                    worked = false
                    return@Button
                }
                busy = true
                result = null
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching { turnOn(session, jmap, security, current, uri) }
                    }
                    outcome.onSuccess { unkept = it }
                    result = outcome.fold(
                        {
                            if (it == null) {
                                "Two-step login is on."
                            } else {
                                "Two-step login is on, and Rampart is using an app password it made " +
                                    "for itself, but it could not keep that password. Copy it now; Rampart " +
                                    "will ask for it the next time it starts."
                            }
                        },
                        { whyFailed(it) },
                    )
                    worked = outcome.isSuccess
                    busy = false
                    if (worked) {
                        setting = false
                        onChanged()
                    }
                }
            },
        ) { Text(if (busy) "Turning on" else "Turn on two-step login") }
        Spacer(Modifier.width(8.dp))
        TextButton(enabled = !busy, onClick = { setting = false; secret = Totp.newSecret(); code = ""; result = null }) {
            Text("Cancel")
        }
        if (busy) {
            Spacer(Modifier.width(12.dp))
            Spinner(size = 20.dp, thickness = 2.dp)
        }
    }
    Outcome(result, worked)
}

/**
 * Turns two-step login on, and keeps Rampart signed in across it.
 *
 * Returns the app password Rampart made for itself when the credential store would not
 * keep it, so the page can show it once; null otherwise. If the server refuses to turn
 * two-step login on, the app password made for it is revoked again rather than left
 * lying about unused.
 */
private fun turnOn(session: Session, jmap: Jmap, security: AccountSecurity, current: String, uri: String): String? {
    val needsOwn = signsInWith(session.account, current)
    val own = if (needsOwn) security.createAppPassword("Rampart on ${computerName()}", AppPasswordLife.NEVER) else null
    try {
        security.enableTwoStep(current, uri)
    } catch (e: Exception) {
        own?.let { runCatching { security.revokeAppPassword(it.id) } }
        throw e
    }
    if (own == null) return null
    jmap.usePassword(session.account.email, own.secret)
    return if (Secrets.store(session.account, own.secret) == null) null else own.secret
}

/** The name an app password Rampart makes for itself is labelled with, so it can be found and revoked later. */
private fun computerName(): String =
    runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "this computer"

@Composable
private fun AppPasswordsSection(
    security: AccountSecurity,
    apps: List<AppPasswordInfo>?,
    error: String?,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(security) { mutableStateOf("") }
    var life by remember(security) { mutableStateOf(AppPasswordLife.NEVER) }
    var busy by remember(security) { mutableStateOf(false) }
    var result by remember(security) { mutableStateOf<String?>(null) }
    var fresh by remember(security) { mutableStateOf<NewAppPassword?>(null) }
    var revoking by remember(security) { mutableStateOf<String?>(null) }

    Section(
        "App passwords",
        "A separate password for each mail app, so one can be revoked without changing the " +
            "others. With two-step login on, mail apps need one of these to sign in at all.",
    )

    when {
        error != null -> FaultText(error)
        apps == null -> Spinner(size = 20.dp, thickness = 2.dp)
        apps.isEmpty() -> Text(
            "None yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        else -> apps.forEach { app ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        app.description.ifBlank { "Unnamed" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        listOfNotNull(
                            app.created.takeIf { it.isNotBlank() }?.let { "Made $it" },
                            if (app.expires.isBlank()) "does not expire" else "expires ${app.expires}",
                        ).joinToString(", ").replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (revoking == app.id) {
                    Text(
                        "Anything signed in with it stops working.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching { security.revokeAppPassword(app.id) }
                            }
                            result = outcome.exceptionOrNull()?.let(::whyFailed)
                            revoking = null
                            busy = false
                            if (outcome.isSuccess) onChanged()
                        }
                    }) { Text("Revoke") }
                    TextButton(enabled = !busy, onClick = { revoking = null }) { Text("Keep") }
                } else {
                    TextButton(enabled = !busy, onClick = { revoking = app.id; result = null }) { Text("Revoke") }
                }
            }
        }
    }

    fresh?.let { made ->
        NewSecret(made.secret, "New app password", onDone = { fresh = null })
    }

    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = name,
        onValueChange = { name = it; result = null },
        label = { Text("What it is for") },
        placeholder = { Text("Thunderbird on the laptop") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AppPasswordLife.entries.forEach { option ->
            Row(
                Modifier.clickable { life = option }.padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = life == option, onClick = { life = option })
                Text(option.label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !busy,
            onClick = {
                if (name.isBlank()) {
                    result = "Say what it is for, so it can be recognised later when it needs revoking."
                    return@Button
                }
                busy = true
                result = null
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching { security.createAppPassword(name, life) }
                    }
                    outcome.onSuccess {
                        fresh = it
                        name = ""
                        onChanged()
                    }
                    result = outcome.exceptionOrNull()?.let(::whyFailed)
                    busy = false
                }
            },
        ) { Text(if (busy) "Working" else "Make app password") }
        if (busy) {
            Spacer(Modifier.width(12.dp))
            Spinner(size = 20.dp, thickness = 2.dp)
        }
    }
    Outcome(result, worked = false)
}

/**
 * A secret shown the one time it can be, with a copy button and a plain warning.
 *
 * Not private: the phone setup page makes an app password the same way this page does, and
 * shows it under the same once-only rule, so it reuses this rather than a second copy of it.
 */
@Composable
internal fun NewSecret(secret: String, title: String, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(secret) { mutableStateOf(false) }
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
            .padding(14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(secret, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "This is the only time it is shown. Rampart does not keep a copy and the server cannot show it again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { clipboard.setText(AnnotatedString(secret)); copied = true }) {
                Text(if (copied) "Copied" else "Copy")
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun SecretField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CodeField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        // Digits and the space some apps show between the two halves, and no more than
        // fits, so a pasted code with a label around it does not become a wrong code.
        onValueChange = { typed -> onChange(typed.filter { it.isDigit() || it == ' ' }.take(Totp.DIGITS + 1)) },
        label = { Text("Six digit code from your authenticator app") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Outcome(result: String?, worked: Boolean) {
    result ?: return
    Spacer(Modifier.height(10.dp))
    if (worked) {
        Text(result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    } else {
        FaultText(result)
    }
}
