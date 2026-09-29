package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.EventQueue
import java.net.URI

/**
 * The buttons and words for signing in through Google or Microsoft. The flow itself is in
 * OAuth.kt and the accounts side in OAuthAccounts.kt; this file only asks and reports.
 */

/**
 * Accounts whose provider has refused their sign-in, waiting on the person to redo it.
 *
 * Filled from whichever thread found out, through the event queue, and read by the banner
 * above the mailbox. A list rather than a flag per account, because an account in this
 * state may not have a session at all: the startup restore is exactly where a revoked
 * sign-in is usually found.
 */
internal val signInLost = mutableStateListOf<SavedAccount>()

private fun sameMailbox(a: SavedAccount, b: SavedAccount) = a.email.equals(b.email, ignoreCase = true) && a.server == b.server

private fun markSignInLost(account: SavedAccount) {
    EventQueue.invokeLater {
        if (signInLost.none { sameMailbox(it, account) }) signInLost += account
    }
}

private fun clearSignInLost(account: SavedAccount) {
    EventQueue.invokeLater { signInLost.removeAll { sameMailbox(it, account) } }
}

private fun listenForLostSignIns() {
    OAuthAccounts.onSignInLost = { account, _ -> markSignInLost(account) }
}

/**
 * An account signed in through the browser on an earlier run, opened again, or null.
 *
 * Called from the startup restore in place of the stored password. A refused sign-in puts
 * the account on [signInLost], so it comes back as a "Sign in again" button rather than
 * quietly missing; anything else, a network failure included, is the same null a password
 * account gets when its server is not answering.
 */
internal fun restoreOAuth(account: SavedAccount): MailBackend? {
    listenForLostSignIns()
    return try {
        OAuthAccounts.restore(account)
    } catch (e: OAuthFailure.SignInAgain) {
        markSignInLost(account)
        null
    } catch (e: Exception) {
        null
    }
}

/**
 * Hands the browser the sign-in address, or says it could not.
 *
 * Desktop.browse where the toolkit has it, and the platform's own opener where it does
 * not, which on Linux is most window managers without a GNOME or KDE session. The address
 * is always one this file built from a fixed https endpoint, and it goes to the opener as
 * one argument, never through a shell.
 */
private fun openInBrowser(url: String): Boolean {
    val desktop = runCatching {
        Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
    }.getOrDefault(false)
    if (desktop && runCatching { Desktop.getDesktop().browse(URI(url)) }.isSuccess) return true
    val os = System.getProperty("os.name").orEmpty()
    val opener = when {
        os.startsWith("Windows") -> return false
        os.startsWith("Mac") -> "open"
        else -> "xdg-open"
    }
    return runCatching { ProcessBuilder(opener, url).start() }.isSuccess
}

/** Where one run of the browser flow has got to, for the words under the button. */
private class OAuthFlow {
    var running by mutableStateOf<OAuthProvider?>(null)

    /** The provider last tried, so a "not configured" answer can offer its client ID field. */
    var tried by mutableStateOf<OAuthProvider?>(null)
    var url by mutableStateOf("")
    var error by mutableStateOf("")
    var detail by mutableStateOf<String?>(null)
    var notice by mutableStateOf("")

    @Volatile
    var current: BrowserSignIn? = null

    fun cancel() {
        current?.close()
    }
}

/**
 * Runs the browser flow and, when [connect] is true, opens the mailbox with the result.
 *
 * [connect] is false for "Sign in again" on an account that still has a mailbox open: the
 * new tokens go to the keeper that mailbox already asks, and nothing else has to change.
 * [expected] is that account, and a different account picked in the browser is refused
 * rather than quietly put in its place.
 *
 * Returns the account and, when connected, its mailbox; null when it did not work, with
 * the reason left on [flow].
 */
private suspend fun signInThroughBrowser(
    flow: OAuthFlow,
    provider: OAuthProvider,
    email: String,
    connect: Boolean,
    expected: SavedAccount? = null,
): Pair<SavedAccount, MailBackend?>? {
    listenForLostSignIns()
    flow.error = ""
    flow.detail = null
    flow.notice = ""
    flow.tried = provider
    val client = OAuthClients.current(provider)
    if (!client.configured) {
        flow.error = notConfiguredSentence(provider)
        return null
    }
    flow.running = provider
    var opened: SavedAccount? = null
    try {
        val signIn = withContext(Dispatchers.IO) { BrowserSignIn(provider, client, email.trim()) }
        flow.current = signIn
        flow.url = signIn.url
        if (!withContext(Dispatchers.IO) { openInBrowser(signIn.url) }) {
            flow.notice = "Rampart could not open a browser. Copy the link below into one to carry on."
        }
        val tokens = withContext(Dispatchers.IO) { signIn.await() }
        val address = tokens.email ?: email.trim()
        if (expected != null && !address.equals(expected.email, ignoreCase = true)) {
            flow.error = "That signed in as $address, but this is ${expected.email}'s mailbox. Sign in again and choose ${expected.email}."
            return null
        }
        val account = expected ?: OAuthAccounts.accountFor(provider, address)
        val keeper = OAuthAccounts.keeperFor(account, provider, client, tokens)
        val kept = OAuthAccounts.store(account, tokens)
        opened = account
        val backend = if (connect) withContext(Dispatchers.IO) { OAuthAccounts.open(account, keeper) } else null
        opened = null
        runCatching { OAuthAccounts.remember(account) }
        clearSignInLost(account)
        flow.notice = when {
            kept != null -> "Signed in, but it could not be kept for next time, so you will be asked again. $kept"
            email.isNotBlank() && !address.equals(email.trim(), ignoreCase = true) ->
                "Signed in as $address, the account chosen in the browser."
            else -> ""
        }
        return account to backend
    } catch (e: CancellationException) {
        throw e
    } catch (e: OAuthFailure.Cancelled) {
        return null
    } catch (e: OAuthFailure) {
        flow.error = e.message.orEmpty()
        return null
    } catch (e: Exception) {
        flow.error = mailServerRefusedToken(provider, e) ?: plainNetworkError(e, provider.imapHost)
        flow.detail = faultDetail(e, flow.error)
        return null
    } finally {
        // A mailbox that did not open leaves no tokens behind for an account nobody saved.
        opened?.let { if (expected == null) OAuthAccounts.forget(it) }
        flow.current?.close()
        flow.current = null
        flow.running = null
        flow.url = ""
    }
}

/** The part of the sign-in screen that offers Google or Microsoft instead of a password. */
@Composable
internal fun OAuthSignInChoice(
    email: String,
    saved: List<SavedAccount>,
    enabled: Boolean,
    onConnected: (SavedAccount, MailBackend) -> Unit,
    modifier: Modifier = Modifier,
) {
    val flow = remember { OAuthFlow() }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { flow.cancel() } }

    val typed = email.trim()
    // Certain from the address, or from a saved account that signed in this way before.
    val known = remember(typed, saved) {
        providerForDomain(typed) ?: saved.firstOrNull { it.email.equals(typed, ignoreCase = true) && it.oauth.isNotBlank() }
            ?.let { OAuthProviders.byId(it.oauth) }
    }
    // A custom domain hosted at either provider, from its MX records, once typing pauses.
    var hosted by remember { mutableStateOf<OAuthProvider?>(null) }
    LaunchedEffect(typed, known) {
        hosted = null
        val domain = oauthDomainOf(typed)
        if (known == null && '@' in typed && '.' in domain) {
            delay(700)
            hosted = withContext(Dispatchers.IO) { providerForMx(mxHosts(domain)) }
        }
    }
    var manual by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<OAuthProvider?>(null) }
    val suggested = known ?: hosted
    val lost = signInLost.any { it.email.equals(typed, ignoreCase = true) }

    fun start(provider: OAuthProvider) {
        if (flow.running != null) return
        scope.launch {
            val done = signInThroughBrowser(flow, provider, typed, connect = true)
            val backend = done?.second
            if (done != null && backend != null) onConnected(done.first, backend)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (suggested != null) {
            Button(
                onClick = { start(suggested) },
                enabled = enabled && flow.running == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (lost) "Sign in again with ${suggested.name}" else "Sign in with ${suggested.name}", maxLines = 1) }
            Text(
                "${suggested.name} asks for your password on its own page, in your browser. Rampart never sees it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            TextButton(onClick = { manual = !manual }, enabled = enabled) {
                Text("Signing in with Google or Microsoft?", style = MaterialTheme.typography.bodyMedium)
            }
            if (manual) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OAuthProviders.all.forEach { provider ->
                        OutlinedButton(
                            onClick = { start(provider) },
                            enabled = enabled && flow.running == null,
                        ) { Text(provider.name, maxLines = 1) }
                    }
                }
            }
        }
        OAuthProgress(flow)
        val offered = suggested ?: flow.tried
        if (offered != null && flow.running == null) {
            val shownEditor = editing
            TextButton(onClick = { editing = if (shownEditor == offered) null else offered }) {
                Text("Use your own ${offered.name} client ID", style = MaterialTheme.typography.bodySmall)
            }
            if (shownEditor == offered) OAuthClientEditor(offered, onDone = { editing = null })
        }
    }
}

/** While the browser is out, what is happening and how to stop it; afterwards, how it went. */
@Composable
private fun OAuthProgress(flow: OAuthFlow) {
    val clipboard = LocalClipboardManager.current
    val running = flow.running
    if (running != null) {
        Text(
            "Waiting for ${running.name} in your browser. Finish signing in there and come back.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val url = flow.url
            if (url.isNotBlank()) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }) { Text("Copy the link") }
            }
            TextButton(onClick = { flow.cancel() }) { Text("Cancel") }
        }
    }
    if (flow.notice.isNotBlank()) {
        Text(flow.notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
    if (flow.error.isNotBlank()) {
        FaultText(flow.error, flow.detail)
    }
}

/**
 * For somebody who registered their own application with Google or Microsoft.
 *
 * Nothing here is a secret, including Google's "client secret" for a Desktop app, which
 * Google itself says is not confidential; docs/connecting.md explains. It is written to the
 * config directory beside accounts.json, and a blank ID goes back to the build's own.
 */
@Composable
private fun OAuthClientEditor(provider: OAuthProvider, onDone: () -> Unit) {
    val current = remember(provider) { OAuthClients.current(provider) }
    var id by remember(provider) { mutableStateOf(current.clientId) }
    var secret by remember(provider) { mutableStateOf(current.clientSecret) }
    var tenant by remember(provider) { mutableStateOf(current.tenant) }
    var problem by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(id, { id = it }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (provider == OAuthProviders.GOOGLE) {
            OutlinedTextField(
                secret, { secret = it },
                label = { Text("Client secret (not confidential for a Desktop app)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                tenant, { tenant = it },
                label = { Text("Tenant: common, consumers, organizations, or your own") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val saved = runCatching {
                    OAuthClients.saveOverride(
                        provider,
                        OAuthClient(id.trim(), secret.trim(), tenant.trim().ifBlank { "common" }, current.redirectHost),
                    )
                }
                if (saved.isSuccess) onDone() else problem = "It could not be saved: ${saved.exceptionOrNull()?.message.orEmpty()}"
            }) { Text("Save") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
        if (problem.isNotBlank()) FaultText(problem)
    }
}

/**
 * Above the mailbox, one line per account whose provider has refused its sign-in.
 *
 * Never "connection failed": the mailbox is fine and the network is fine, and the one thing
 * that fixes it is the button beside the sentence. [open] is the accounts that still have a
 * mailbox open, which only need new tokens; the rest are opened and handed to [onConnected].
 */
@Composable
internal fun SignInAgainBanner(open: Set<String>, onConnected: (SavedAccount, MailBackend) -> Unit) {
    if (signInLost.isEmpty()) return
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        signInLost.toList().forEach { account ->
            key(OAuthAccounts.key(account)) {
                SignInAgainRow(account, live = OAuthAccounts.key(account) in open, onConnected = onConnected)
            }
        }
    }
}

@Composable
private fun SignInAgainRow(account: SavedAccount, live: Boolean, onConnected: (SavedAccount, MailBackend) -> Unit) {
    val provider = OAuthProviders.byId(account.oauth) ?: return
    val flow = remember(account) { OAuthFlow() }
    val scope = rememberCoroutineScope()
    DisposableEffect(account) { onDispose { flow.cancel() } }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "${provider.name} no longer accepts the sign-in for ${account.email}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = flow.running == null,
                onClick = {
                    scope.launch {
                        val done = signInThroughBrowser(flow, provider, account.email, connect = !live, expected = account)
                        val backend = done?.second
                        if (done != null && backend != null) onConnected(done.first, backend)
                    }
                },
            ) { Text("Sign in again", maxLines = 1) }
            TextButton(onClick = {
                flow.cancel()
                clearSignInLost(account)
            }) { Text("Not now") }
        }
        OAuthProgress(flow)
    }
}
