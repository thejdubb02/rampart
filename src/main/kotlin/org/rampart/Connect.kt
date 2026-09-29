package org.rampart

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.skia.Image
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@Composable
internal fun Connect(
    saved: List<SavedAccount> = remember { Accounts.read() },
    canRemember: Boolean = remember { Secrets.available() },
    onCancel: (() -> Unit)? = null,
    onConnected: (SavedAccount, MailBackend) -> Unit,
) {
    var server by remember { mutableStateOf(saved.firstOrNull()?.server ?: "") }
    var user by remember { mutableStateOf(saved.firstOrNull()?.email ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var trying by remember { mutableStateOf("") }
    var serverOpen by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(canRemember) }
    val storeProblem = remember { Secrets.unavailableReason() }
    val scope = rememberCoroutineScope()

    fun connect() {
        if (busy || user.isBlank() || password.isBlank()) return
        busy = true
        error = ""
        errorDetail = null
        trying = ""
        scope.launch {
            try {
                val email = user.trim()
                val typed = server.trim()
                // A saved account already told us which protocol worked. Using
                // that skips a JMAP wait on an IMAP host, which is 15 seconds of
                // looking hung.
                val known = saved.firstOrNull {
                    it.email == email && it.server == typed
                }?.protocol.orEmpty()
                val routes = routesToTry(email, typed, known)
                if (routes.isEmpty()) {
                    serverOpen = true
                    error = noServerFor(email)
                    return@launch
                }
                for (route in routes) {
                    trying = lookingFor(route)
                    try {
                        val backend = withContext(Dispatchers.IO) {
                            openRoute(route, email, password)
                        }
                        val account = accountFor(route, email)
                        // Only after a sign-in that worked, so a typo is never saved.
                        runCatching { Accounts.remember(account) }
                        if (rememberPassword) {
                            // Signing in worked, so this is not a failure worth refusing
                            // the session over. It is worth saying out loud.
                            Secrets.store(account, password)?.let { error = "Signed in. $it" }
                        } else {
                            Secrets.forget(account)
                        }
                        onConnected(account, backend)
                        return@launch
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (passwordRejected(e)) {
                            error = "The server did not accept that email address and password."
                            return@launch
                        }
                        // Anything else is the wrong host. Shown only if none
                        // work, so a timeout on the first guess is not the
                        // message the person reads.
                    }
                }
                serverOpen = true
                error = noServerFor(email)
            } catch (e: Exception) {
                error = "Could not sign in."
                errorDetail = faultDetail(e, error)
            } finally {
                busy = false
                trying = ""
            }
        }
    }

    val submit = Modifier.onPreviewKeyEvent { event ->
        val enter = event.type == KeyEventType.KeyDown &&
            (event.key == Key.Enter || event.key == Key.NumPadEnter)
        if (!enter) false else {
            connect()
            true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = useResource("rampart-logo.svg") { loadSvgPainter(it, LocalDensity.current) },
            contentDescription = null,
            modifier = Modifier.size(72.dp),
        )
        Text(
            if (onCancel == null) "Rampart" else "Add an account",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        // Set up once, by hand or by handing someone the accounts file, and after that
        // signing in is a click and a password. The file never holds the password.
        if (saved.isNotEmpty()) {
            Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                saved.forEach { account ->
                    val here = account.email == user && account.server == server
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (here) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                MaterialTheme.shapes.small,
                            )
                            .rowHover(showWash = !here)
                            .clickable {
                                server = account.server
                                user = account.email
                                password = ""
                                error = ""
                                errorDetail = null
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(account.name, fontWeight = if (here) FontWeight.Bold else FontWeight.Normal)
                        Text(
                            account.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            user, { user = it },
            label = { Text("Email address") },
            singleLine = true,
            modifier = Modifier.width(380.dp).then(submit),
        )
        OAuthSignInChoice(user, saved, enabled = !busy, onConnected = onConnected, modifier = Modifier.width(380.dp))
        OutlinedTextField(
            password, { password = it },
            label = { Text("App password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(380.dp).then(submit),
        )
        // Closed by default and marked as a disclosure rather than a grey line that happens
        // to be clickable. Somebody who needs it is somebody whose domain published nothing,
        // and they have to be able to find it.
        Row(
            Modifier.width(380.dp).clickable { serverOpen = !serverOpen },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (serverOpen) RampartIcons.Collapse else RampartIcons.Expand,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Server settings",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (serverOpen) {
            OutlinedTextField(
                server, { server = it },
                label = { Text("Server") },
                singleLine = true,
                modifier = Modifier.width(380.dp),
            )
        }
        if (canRemember) {
            Row(
                Modifier.width(380.dp).clickable { rememberPassword = !rememberPassword },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(rememberPassword, { rememberPassword = it })
                Text("Remember this password", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onCancel != null) TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
            Button(
                enabled = !busy && user.isNotBlank() && password.isNotBlank(),
                onClick = { connect() },
            ) { Text(if (busy) "Connecting" else "Connect", maxLines = 1) }
        }
        if (trying.isNotBlank()) {
            Text(
                trying,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(380.dp),
            )
        }
        if (error.isNotBlank()) {
            FaultText(error, errorDetail, modifier = Modifier.width(380.dp))
        }
        if (storeProblem != null) {
            Text(
                "Passwords cannot be remembered on this machine, so you will be asked each time. $storeProblem",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(380.dp),
            )
        }
        Text(
            if (rememberPassword && canRemember) {
                "The password goes to the operating system's own credential store, tied to this " +
                    "Windows account. Rampart never writes it anywhere itself."
            } else {
                "The password is held in memory for this session only, and never written to disk."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
