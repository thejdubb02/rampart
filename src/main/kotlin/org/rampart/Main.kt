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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
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
        LaunchedEffect(dark) { WindowChrome.setDarkTitleBar(window, dark) }
        MaterialTheme(colorScheme = if (dark) RampartDarkColors else RampartColors) {
            Surface(Modifier.fillMaxSize()) {
                App(
                    dark = dark,
                    onToggleDark = { dark = it; Settings.setDark(it) },
                    onQuit = ::exitApplication,
                )
            }
        }
    }
}

@Composable
private fun App(dark: Boolean, onToggleDark: (Boolean) -> Unit, onQuit: () -> Unit) {
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
        Reader(sessions, dark, onToggleDark, onQuit, onAddAccount = { adding = true })
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
    val storeProblem = remember { Secrets.unavailableReason() }
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
                            if (rememberPassword) {
                                // Signing in worked, so this is not a failure worth refusing
                                // the session over. It is worth saying out loud.
                                Secrets.store(account, password)?.let { error = "Signed in. $it" }
                            } else {
                                Secrets.forget(account)
                            }
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

@Composable
private fun Reader(
    sessions: List<Session>,
    dark: Boolean,
    onToggleDark: (Boolean) -> Unit,
    onQuit: () -> Unit,
    onAddAccount: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var identities by remember { mutableStateOf<Map<String, List<Identity>>>(emptyMap()) }
    var composing by remember { mutableStateOf<Draft?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showingResults by remember { mutableStateOf(false) }
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

    LaunchedEffect(Unit) { update = withContext(Dispatchers.IO) { Updates.newerVersion() } }
    LaunchedEffect(sessions.size) {
        sessions.filter { it.key !in mailboxes }.forEach { open ->
            val found = io { open.jmap.mailboxes() } ?: emptyList()
            mailboxes = mailboxes + (open.key to found)
            // An account with no identity can still read; it just cannot send, and that is
            // reported when someone tries rather than as an error on the way in.
            withContext(Dispatchers.IO) { runCatching { open.jmap.identities() }.getOrNull() }
                ?.let { identities = identities + (open.key to it) }
            if (here == null) {
                val inbox = found.firstOrNull { it.role == "inbox" } ?: found.firstOrNull()
                if (inbox != null) here = open.key to inbox
            }
        }
        loading = false
    }
    suspend fun reload() {
        val (key, mailbox) = here ?: return
        loading = true
        emails = io {
            if (showingResults && query.isNotBlank()) session(key).jmap.search(query, mailbox.id)
            else session(key).jmap.emails(mailbox.id)
        } ?: emptyList()
        loading = false
    }

    LaunchedEffect(here) {
        here ?: return@LaunchedEffect
        selected = null
        body = null
        query = ""
        showingResults = false
        reload()
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

    // What can be done to the open message depends on which folders this account has:
    // a server with no Archive folder should not offer an Archive button that fails.
    val actions = run {
        val key = here?.first
        val message = selected
        if (key == null || message == null) return@run MessageActions()
        val boxes = mailboxes[key].orEmpty()
        fun moveTo(role: String): (() -> Unit)? {
            val target = boxes.firstOrNull { it.role == role } ?: return null
            return {
                scope.launch {
                    if (io { session(key).jmap.move(listOf(message.id), target.id) } != null) {
                        emails = emails.filterNot { it.id == message.id }
                        selected = null
                        body = null
                    }
                }
                Unit
            }
        }
        MessageActions(archive = moveTo("archive"), trash = moveTo("trash"), junk = moveTo("junk"))
    }

    val composer = composing
    if (composer != null) {
        Composer(
            identities = identities[here?.first]?.map { it.email }.orEmpty(),
            initial = composer,
            sending = sending,
            error = sendError,
            onDiscard = { composing = null; sendError = null },
            onSend = { draft ->
                val key = here?.first
                val account = key?.let(::session)
                val boxes = mailboxes[key].orEmpty()
                val drafts = boxes.firstOrNull { it.role == "drafts" }
                val identity = identities[key].orEmpty().firstOrNull { it.email.equals(draft.from, true) }
                    ?: identities[key].orEmpty().firstOrNull()
                when {
                    account == null -> sendError = "Pick an account first."
                    identity == null -> sendError = "This account has no identity to send from."
                    drafts == null -> sendError = "This account has no Drafts folder, and the message is written there before it is sent."
                    else -> scope.launch {
                        sending = true
                        sendError = null
                        try {
                            withContext(Dispatchers.IO) {
                                account.jmap.send(draft, identity, drafts.id, boxes.firstOrNull { it.role == "sent" }?.id)
                            }
                            composing = null
                        } catch (e: Exception) {
                            sendError = e.message ?: e.toString()
                        } finally {
                            sending = false
                        }
                    }
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        update?.let { version ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Rampart $version is ready. It installs when you next open the app.", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onQuit) { Text("Quit and update") }
                    TextButton(onClick = { update = null }) { Text("Later") }
                }
            }
        }
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
                onWrite = {
                    val from = identities[here?.first].orEmpty().firstOrNull()?.email
                        ?: sessions.firstOrNull { it.key == here?.first }?.account.let { it?.email }.orEmpty()
                    sendError = null
                    composing = Draft(from = from)
                },
            )
            VerticalDivider()
            MessageList(
                emails = emails,
                selected = selected,
                loading = loading,
                query = query,
                onQueryChange = { query = it },
                onSearch = {
                    if (query.isBlank()) {
                        showingResults = false
                        scope.launch { reload() }
                    } else {
                        showingResults = true
                        selected = null
                        body = null
                        scope.launch { reload() }
                    }
                },
                onSelect = { selected = it },
            )
            VerticalDivider()
            Message(
                summary = selected,
                body = body,
                onReply = {
                    val message = selected ?: return@Message
                    val from = identities[here?.first].orEmpty().firstOrNull()?.email.orEmpty()
                    sendError = null
                    composing = replyTo(message, body, from)
                },
                onForward = {
                    val message = selected ?: return@Message
                    val from = identities[here?.first].orEmpty().firstOrNull()?.email.orEmpty()
                    sendError = null
                    composing = forwardOf(message, body, from)
                },
                onLink = { confirm = it },
                actions = actions,
            )
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
    onWrite: () -> Unit,
    onSelect: (String, Mailbox) -> Unit,
) {
    Column(Modifier.width(220.dp).fillMaxHeight()) {
        Button(
            onClick = onWrite,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        ) { Text("Write") }
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
        Updates.current?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
internal fun MessageList(
    emails: List<Summary>,
    selected: Summary?,
    loading: Boolean,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    onSelect: (Summary) -> Unit,
) {
    Column(Modifier.width(320.dp).fillMaxHeight()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { onQueryChange(""); onSearch() }) { Text("Clear") }
                }
            },
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(8.dp).onPreviewKeyEvent {
                // Enter searches and Escape abandons the search, which is what every mail
                // client does and what fingers expect before they read any button.
                when {
                    it.type != KeyEventType.KeyDown -> false
                    it.key == Key.Enter -> { onSearch(); true }
                    it.key == Key.Escape -> { onQueryChange(""); onSearch(); true }
                    else -> false
                }
            },
        )
        HorizontalDivider()
        Box(Modifier.fillMaxSize()) {
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                return@Box
            }
            if (emails.isEmpty()) {
                Text(
                    if (query.isBlank()) "Nothing here." else "No messages match that.",
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.Center),
                )
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
}

/** The buttons the open message offers. A null one is not offered at all. */
internal data class MessageActions(
    val archive: (() -> Unit)? = null,
    val trash: (() -> Unit)? = null,
    val junk: (() -> Unit)? = null,
)

@Composable
internal fun Message(
    summary: Summary?,
    body: Body?,
    onReply: () -> Unit = {},
    onForward: () -> Unit = {},
    actions: MessageActions = MessageActions(),
    onLink: (String) -> Unit,
) {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Text(summary.subject, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onReply, enabled = body != null) { Text("Reply") }
            TextButton(onClick = onForward, enabled = body != null) { Text("Forward") }
            actions.archive?.let { TextButton(onClick = it) { Text("Archive") } }
            actions.junk?.let { TextButton(onClick = it) { Text("Spam") } }
            actions.trash?.let { TextButton(onClick = it) { Text("Delete") } }
        }
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

internal fun String.asLocalTime(): String =
    runCatching { WHEN.format(Instant.parse(this)) }.getOrDefault(this)
