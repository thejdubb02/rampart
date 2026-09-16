package org.rampart

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Bulwark's red. See docs/architecture.md. */
private val RampartRed = Color(0xFFDB2D54)

/**
 * Material's baseline schemes are faintly purple, which on a red-primary app reads as a
 * cast over everything. Mail is text on paper, so the neutrals are neutral.
 *
 * The brand red is lightened on dark: #DB2D54 on a near-black background is under the
 * contrast a link needs to be read at body size.
 */
internal val RampartColors = lightColorScheme(
    primary = RampartRed,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F1F4),
    onSurfaceVariant = Color(0xFF1C1B1F),
    outline = Color(0xFF6E6E78),
)

internal val RampartDarkColors = darkColorScheme(
    primary = Color(0xFFFF7A96),
    background = Color(0xFF131318),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE7E7EC),
    surfaceVariant = Color(0xFF26262F),
    onSurfaceVariant = Color(0xFFE7E7EC),
    outline = Color(0xFF9D9DA9),
)

private val WHEN = DateTimeFormatter.ofPattern("d MMM  HH:mm").withZone(ZoneId.systemDefault())

/** One signed in mailbox. Several of these is the point; the password is in none of them. */
internal class Session(val account: SavedAccount, val jmap: Jmap) {
    val key: String get() = "${account.email}@${account.server}"
}

/** What the sidebar needs to draw an account, with no live connection behind it. */
internal data class AccountMailboxes(val key: String, val name: String, val mailboxes: List<Mailbox>)

fun main() = application {
    val density = LocalDensity.current
    val icon = remember(density) { useResource("rampart-icon.svg") { loadSvgPainter(it, density) } }
    // Conveyor's launcher sets this. Showing it makes "did the update actually land" a
    // question you can answer by looking at the window instead of guessing.
    val version = System.getProperty("app.version")
    Window(
        onCloseRequest = ::exitApplication,
        title = if (version.isNullOrBlank()) "Rampart" else "Rampart $version",
        icon = icon,
    ) {
        val followSystem = isSystemInDarkTheme()
        var dark by remember { mutableStateOf(Settings.dark() ?: followSystem) }
        MaterialTheme(colorScheme = if (dark) RampartDarkColors else RampartColors) {
            Surface(Modifier.fillMaxSize()) {
                App(dark = dark, onToggleDark = { dark = it; Settings.setDark(it) })
            }
        }
    }
}

@Composable
private fun App(dark: Boolean, onToggleDark: (Boolean) -> Unit) {
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(true) }

    // Sign in again to whatever the operating system remembered. A password that no longer
    // works is not an error worth a dialog: that account simply is not signed in, and the
    // sign-in screen is already the answer.
    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) {
            Accounts.read().mapNotNull { account ->
                val password = Secrets.load(account) ?: return@mapNotNull null
                runCatching { Session(account, Jmap.connect(account.server, account.email, password)) }.getOrNull()
            }
        }
        restoring = false
    }

    if (restoring) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = useResource("rampart-logo.svg") { loadSvgPainter(it, LocalDensity.current) },
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
            CircularProgressIndicator()
        }
        return
    }

    if (sessions.isEmpty() || adding) {
        Connect(
            // Already signed in accounts are dropped from the picker: offering one again
            // only leads to signing into the same mailbox twice.
            saved = remember(sessions) {
                val open = sessions.map { it.key }.toSet()
                Accounts.read().filter { "${it.email}@${it.server}" !in open }
            },
            onCancel = if (sessions.isEmpty()) null else ({ adding = false }),
        ) { account, jmap ->
            sessions = sessions + Session(account, jmap)
            adding = false
        }
    } else {
        Reader(sessions, dark, onToggleDark, onAddAccount = { adding = true })
    }
}

@Composable
internal fun Connect(
    saved: List<SavedAccount> = remember { Accounts.read() },
    canRemember: Boolean = remember { Secrets.available() },
    onCancel: (() -> Unit)? = null,
    onConnected: (SavedAccount, Jmap) -> Unit,
) {
    var server by remember { mutableStateOf(saved.firstOrNull()?.server ?: "") }
    var user by remember { mutableStateOf(saved.firstOrNull()?.email ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(canRemember) }
    val scope = rememberCoroutineScope()

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
                            .background(
                                if (here) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                MaterialTheme.shapes.small,
                            )
                            .clickable {
                                server = account.server
                                user = account.email
                                password = ""
                                error = ""
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

        OutlinedTextField(server, { server = it }, label = { Text("Server") }, singleLine = true, modifier = Modifier.width(380.dp))
        OutlinedTextField(user, { user = it }, label = { Text("Email address") }, singleLine = true, modifier = Modifier.width(380.dp))
        OutlinedTextField(
            password, { password = it },
            label = { Text("App password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(380.dp),
        )
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
                enabled = !busy && server.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
                onClick = {
                    busy = true
                    error = ""
                    scope.launch {
                        try {
                            val jmap = withContext(Dispatchers.IO) { Jmap.connect(server, user, password) }
                            val account = SavedAccount(user.trim(), server.trim(), user.trim())
                            // Only after a sign-in that worked, so a typo is never saved.
                            runCatching { Accounts.remember(account) }
                            if (rememberPassword) Secrets.store(account, password) else Secrets.forget(account)
                            onConnected(account, jmap)
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Connecting" else "Connect") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.width(380.dp))
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

@Composable
private fun Reader(
    sessions: List<Session>,
    dark: Boolean,
    onToggleDark: (Boolean) -> Unit,
    onAddAccount: () -> Unit,
) {
    var mailboxes by remember { mutableStateOf<Map<String, List<Mailbox>>>(emptyMap()) }
    var here by remember { mutableStateOf<Pair<String, Mailbox>?>(null) }
    var emails by remember { mutableStateOf<List<Summary>>(emptyList()) }
    var selected by remember { mutableStateOf<Summary?>(null) }
    var body by remember { mutableStateOf<Body?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }

    fun session(key: String) = sessions.first { it.key == key }

    suspend fun <T> io(block: () -> T): T? = try {
        error = ""
        withContext(Dispatchers.IO) { block() }
    } catch (e: Exception) {
        error = e.message ?: e.toString()
        null
    }

    LaunchedEffect(sessions.size) {
        sessions.filter { it.key !in mailboxes }.forEach { open ->
            val found = io { open.jmap.mailboxes() } ?: emptyList()
            mailboxes = mailboxes + (open.key to found)
            if (here == null) {
                val inbox = found.firstOrNull { it.role == "inbox" } ?: found.firstOrNull()
                if (inbox != null) here = open.key to inbox
            }
        }
        loading = false
    }
    LaunchedEffect(here) {
        val (key, mailbox) = here ?: return@LaunchedEffect
        selected = null
        body = null
        loading = true
        emails = io { session(key).jmap.emails(mailbox.id) } ?: emptyList()
        loading = false
    }
    LaunchedEffect(selected) {
        val message = selected ?: return@LaunchedEffect
        val key = here?.first ?: return@LaunchedEffect
        body = null
        body = io { session(key).jmap.body(message.id) }
        if (!message.seen) {
            io { session(key).jmap.markSeen(message.id) }
            emails = emails.map { if (it.id == message.id) it.copy(seen = true) else it }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (error.isNotBlank()) {
            Text(
                error,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),
            )
        }
        Row(Modifier.fillMaxSize()) {
            Sidebar(
                accounts = sessions.map { AccountMailboxes(it.key, it.account.name, mailboxes[it.key].orEmpty()) },
                here = here,
                dark = dark,
                onToggleDark = onToggleDark,
                onAddAccount = onAddAccount,
                onSelect = { key, mailbox -> here = key to mailbox },
            )
            VerticalDivider()
            MessageList(emails, selected, loading) { selected = it }
            VerticalDivider()
            Message(selected, body) { confirm = it }
        }
    }

    confirm?.let { url ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Open this link?") },
            text = { Text(url, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { Desktop.getDesktop().browse(URI(url)) }
                    confirm = null
                }) { Text("Open in browser") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun Sidebar(
    accounts: List<AccountMailboxes>,
    here: Pair<String, Mailbox>?,
    dark: Boolean,
    onToggleDark: (Boolean) -> Unit,
    onAddAccount: () -> Unit,
    onSelect: (String, Mailbox) -> Unit,
) {
    Column(Modifier.width(220.dp).fillMaxHeight()) {
        LazyColumn(Modifier.weight(1f)) {
            accounts.forEach { account ->
                // With one account the heading is noise. With two it is the only way to
                // tell one Inbox from the other.
                if (accounts.size > 1) {
                    item(key = "head-${account.key}") {
                        Text(
                            account.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                }
                items(account.mailboxes, key = { "${account.key}/${it.id}" }) { box ->
                    val selected = here?.first == account.key && here.second.id == box.id
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable { onSelect(account.key, box) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            box.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f, false),
                        )
                        if (box.unread > 0) {
                            Text(
                                "${box.unread}",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onAddAccount) { Text("Add account") }
            TextButton(onClick = { onToggleDark(!dark) }) { Text(if (dark) "Light" else "Dark") }
        }
    }
}

@Composable
internal fun MessageList(emails: List<Summary>, selected: Summary?, loading: Boolean, onSelect: (Summary) -> Unit) {
    Box(Modifier.width(320.dp).fillMaxHeight()) {
        if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@Box
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(emails, key = { it.id }) { message ->
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (message.id == selected?.id) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { onSelect(message) }
                        .padding(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            message.from,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
                            modifier = Modifier.weight(1f, false),
                        )
                        Text(
                            message.receivedAt.asLocalTime(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(
                        message.subject,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
                    )
                    if (message.preview.isNotBlank()) {
                        Text(
                            message.preview,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
internal fun Message(summary: Summary?, body: Body?, onLink: (String) -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    val quoteColor = MaterialTheme.colorScheme.outline
    val rendered = remember(body, linkColor) {
        body?.let {
            when {
                it.html != null -> renderHtml(it.html, linkColor, quoteColor, onLink)
                it.text != null -> renderText(it.text, linkColor, onLink)
                else -> null
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        if (summary == null) {
            Text("Pick a message.", color = MaterialTheme.colorScheme.outline)
            return@Column
        }
        Text(summary.subject, style = MaterialTheme.typography.titleLarge)
        Text(
            "${summary.from}    ${summary.receivedAt.asLocalTime()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (rendered == null) {
            if (body == null) CircularProgressIndicator() else Text("This message has no readable body.")
            return@Column
        }
        if (rendered.blockedImages > 0) {
            Text(
                if (rendered.blockedImages == 1) "1 image was not loaded."
                else "${rendered.blockedImages} images were not loaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
            )
        }
        SelectionContainer { Text(rendered.text, modifier = Modifier.padding(top = 12.dp)) }
    }
}

private fun String.asLocalTime(): String =
    runCatching { WHEN.format(Instant.parse(this)) }.getOrDefault(this)
