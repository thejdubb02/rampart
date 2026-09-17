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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.skia.Image
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
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN = DateTimeFormatter.ofPattern("d MMM  HH:mm").withZone(ZoneId.systemDefault())

/** One signed in mailbox. Several of these is the point; the password is in none of them. */
internal class Session(val account: SavedAccount, val jmap: Jmap) {
    val key: String get() = "${account.email}@${account.server}"
}

/** What the sidebar needs to draw an account, with no live connection behind it. */
internal data class AccountMailboxes(
    val key: String,
    val name: String,
    val email: String,
    val mailboxes: List<Mailbox>,
)

fun main() = application {
    val density = LocalDensity.current
    val icon = remember(density) { useResource("rampart-icon.svg") { loadSvgPainter(it, density) } }
    val saved = remember { Settings.window() }
    val windowState = rememberWindowState(
        placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = saved?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition(Alignment.Center),
        size = DpSize((saved?.width ?: 1440).dp, (saved?.height ?: 900).dp),
    )

    /**
     * Where the window was is written down on the way out. A maximized window reports the
     * whole screen as its size, so the size from before it was maximized is kept instead:
     * un-maximizing a restored window should give back the size someone chose, not the
     * monitor.
     */
    fun remember() {
        val maximized = windowState.placement == WindowPlacement.Maximized
        val position = windowState.position
        val previous = Settings.window()
        Settings.setWindow(
            if (maximized || !position.isSpecified) {
                SavedWindow(
                    x = previous?.x ?: 0,
                    y = previous?.y ?: 0,
                    width = previous?.width ?: 1440,
                    height = previous?.height ?: 900,
                    maximized = maximized,
                )
            } else {
                SavedWindow(
                    x = position.x.value.toInt(),
                    y = position.y.value.toInt(),
                    width = windowState.size.width.value.toInt(),
                    height = windowState.size.height.value.toInt(),
                    maximized = false,
                )
            },
        )
    }

    fun quit() {
        remember()
        exitApplication()
    }

    val tray = rememberTrayState()
    // No tray on this desktop means no notifications, and nothing else changes. Constructing
    // one anyway logs a warning on every start and still cannot deliver anything.
    if (isTraySupported) {
        Tray(
            icon = icon,
            state = tray,
            tooltip = "Rampart",
            onAction = { windowState.isMinimized = false },
        )
    }

    Window(
        onCloseRequest = ::quit,
        // The version lives in the sidebar. A title bar is for saying which app this is.
        title = "Rampart",
        icon = icon,
        state = windowState,
    ) {
        val followSystem = isSystemInDarkTheme()
        // Settings.dark() is the pre-themes switch. Reading it here is what stops an
        // existing install opening light again the first time it runs a build with themes.
        var theme by remember { mutableStateOf(themeFor(Settings.theme(), Settings.dark() ?: followSystem)) }
        LaunchedEffect(theme) { WindowChrome.setDarkTitleBar(window, theme.dark) }
        CompositionLocalProvider(LocalRampartTheme provides theme) {
            MaterialTheme(colorScheme = theme.scheme(), typography = RampartTypography) {
                Surface(Modifier.fillMaxSize()) {
                    App(
                        onTheme = { theme = it; Settings.setTheme(it.key) },
                        onQuit = ::quit,
                        notify = { title, message ->
                            tray.sendNotification(Notification(title, message, Notification.Type.Info))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun App(onTheme: (Theme) -> Unit, onQuit: () -> Unit, notify: (String, String) -> Unit) {
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
        Reader(sessions, onTheme, onQuit, notify, onAddAccount = { adding = true })
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
    onTheme: (Theme) -> Unit,
    onQuit: () -> Unit,
    notify: (String, String) -> Unit,
    onAddAccount: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var identities by remember { mutableStateOf<Map<String, List<Identity>>>(emptyMap()) }
    var composing by remember { mutableStateOf<Draft?>(null) }
    // Where the autosaved copy of what is being written currently lives, so the next save
    // replaces it rather than adding another, and sending or discarding can clear it away.
    var draftId by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showingResults by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(Settings.sidebarCollapsed()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var signatureError by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    var installNote by remember { mutableStateOf<String?>(null) }
    var notifyOnArrival by remember { mutableStateOf(Settings.notifyOnArrival()) }
    val searchField = remember { FocusRequester() }
    val keyboard = remember { FocusRequester() }
    var mailboxes by remember { mutableStateOf<Map<String, List<Mailbox>>>(emptyMap()) }
    var here by remember { mutableStateOf<Pair<String, Mailbox>?>(null) }
    var emails by remember { mutableStateOf<List<Summary>>(emptyList()) }
    var selected by remember { mutableStateOf<Summary?>(null) }
    var body by remember { mutableStateOf<Body?>(null) }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var thread by remember { mutableStateOf<List<Summary>>(emptyList()) }
    var bodyError by remember { mutableStateOf<String?>(null) }
    var inlineImages by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var remoteImages by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var unsubscribed by remember { mutableStateOf<String?>(null) }
    var allowedSenders by remember { mutableStateOf(Settings.imageSenders()) }
    var saved by remember { mutableStateOf<String?>(null) }
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

    /*
     * Asked once the window is up, and then every half hour, because a window that stays
     * open for a day would otherwise never hear about a build published after it started.
     * Windows fetches the package itself in the background; this is only what puts the
     * restart button on screen.
     *
     * Whatever GitHub calls the latest release is what gets installed, however many builds
     * have gone out in between. There is no stepping through versions: the update manifest
     * names one current package and Windows fetches that.
     */
    LaunchedEffect(Unit) {
        while (true) {
            if (!installing) update = withContext(Dispatchers.IO) { Updates.newerVersion() }
            delay(30 * 60_000L)
        }
    }

    // Windows closes this window when the new version is in place, so still being here
    // three minutes later means it did not happen. Said plainly rather than left on
    // "Installing" forever, and the next check offers it again.
    LaunchedEffect(installing) {
        if (!installing) return@LaunchedEffect
        delay(3 * 60_000L)
        installNote = "That did not install. Windows will fetch it in the background instead."
        delay(20_000L)
        installNote = null
        installing = false
    }

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

    /**
     * Mail that arrives while Rampart is open turns up on its own.
     *
     * Polling rather than JMAP push, because a poll is the same shape against every server
     * and against IMAP later, and because the check itself is one small request: the inbox
     * is only re-read on the rounds where the account's mail state has actually moved.
     *
     * A round that fails is skipped, not reported. The network dropping for a minute is not
     * something to put a red bar on screen for, and the next round fixes it.
     */
    LaunchedEffect(sessions) {
        val states = mutableMapOf<String, String>()
        val seen = mutableMapOf<String, Set<String>>()
        while (true) {
            for (open in sessions) {
                val inbox = mailboxes[open.key]?.firstOrNull { it.role == "inbox" } ?: continue
                val found = withContext(Dispatchers.IO) {
                    runCatching {
                        val state = open.jmap.mailState() ?: return@runCatching null
                        if (state == states[open.key]) null
                        else arrivals(state, open.jmap.emails(inbox.id, limit = 30), seen[open.key])
                    }.getOrNull()
                } ?: continue

                states[open.key] = found.state
                seen[open.key] = found.summaries.map { it.id }.toSet()
                // The unread counts in the sidebar move when mail arrives and when it is
                // read in another client, so they are refreshed on any change, not just on
                // an arrival.
                withContext(Dispatchers.IO) { runCatching { open.jmap.mailboxes() }.getOrNull() }
                    ?.let { mailboxes = mailboxes + (open.key to it) }
                // Anything this account changed can be in the folder on screen, not only in
                // its inbox: mail read or filed in another client moves the open folder too.
                if (here?.first == open.key && !showingResults) reload()
                if (notifyOnArrival) arrivalText(found.fresh)?.let { (title, body) -> notify(title, body) }
            }
            // Until every account has been looked at once there is nothing to compare
            // against, so those first rounds come quickly rather than a minute apart.
            delay(if (states.size == sessions.size) 30_000 else 5_000)
        }
    }

    /** Folder counts and the open folder, brought up to date now rather than at the next poll. */
    suspend fun refreshNow() {
        val key = here?.first ?: return
        withContext(Dispatchers.IO) { runCatching { session(key).jmap.mailboxes() }.getOrNull() }
            ?.let { mailboxes = mailboxes + (key to it) }
        reload()
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
        bodyError = null
        inlineImages = emptyMap()
        remoteImages = emptyList()
        unsubscribed = null
        attachments = emptyList()
        saved = null
        // Cleared only when this is a different conversation, so moving between messages in
        // the same thread does not make the list of them flicker away and come back.
        if (thread.none { it.id == message.id }) thread = emptyList()
        // Kept apart from the shared error bar. A message that will not open has to say so
        // where the message would have been: a spinner that never stops is indistinguishable
        // from one that is still going, and it was being shown for a failure.
        body = try {
            withContext(Dispatchers.IO) { session(key).jmap.body(message.id) }
        } catch (e: Exception) {
            bodyError = e.message ?: e.toString()
            null
        }
        attachments = io { session(key).jmap.attachments(message.id) } ?: emptyList()

        // Images the message carries with it are drawn. Fetching them asks the server this
        // account is already signed in to, so it tells the sender nothing, which is the
        // whole difference between these and the remote ones that stay blocked.
        val embedded = attachments.filter { it.inline && it.type.startsWith("image/") }
        if (embedded.isNotEmpty()) {
            inlineImages = withContext(Dispatchers.IO) {
                embedded.mapNotNull { part ->
                    val bytes = runCatching { session(key).jmap.blob(part) }.getOrNull()
                        ?: return@mapNotNull null
                    // A part that claims to be an image and is not must not take the pane
                    // down with it.
                    val bitmap = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }
                        .getOrNull() ?: return@mapNotNull null
                    part.blobId to bitmap
                }.toMap()
            }
        }
        if (thread.isEmpty()) thread = io { session(key).jmap.thread(message.threadId) } ?: emptyList()

        // Already answered for this sender, so it is not asked again. The question is
        // whether to tell them the message was opened, and that was settled the first time.
        if (imageSenderKey(message.fromEmail) in allowedSenders) remoteImages = fetchRemote(body)

        // A draft is not something to read. Clicking one puts it back in the composer,
        // under the id it is already saved at, so carrying on writing replaces that copy
        // instead of leaving the old one behind.
        if (here?.second?.role == "drafts" && !showingResults) {
            draftId = message.id
            sendError = null
            composing = draftOf(message, body, identities[key].orEmpty().firstOrNull()?.email.orEmpty())
            return@LaunchedEffect
        }

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
        MessageActions(
            archive = moveTo("archive"),
            trash = moveTo("trash"),
            junk = moveTo("junk"),
            star = {
                val wanted = !message.flagged
                // The star turns over at once and is put back if the server says no. A
                // star that waits for a round trip feels broken at the speed people click.
                emails = emails.map { if (it.id == message.id) it.copy(flagged = wanted) else it }
                selected = selected?.copy(flagged = wanted)
                scope.launch {
                    if (io { session(key).jmap.setKeyword(listOf(message.id), "\$flagged", wanted) } == null) {
                        emails = emails.map { if (it.id == message.id) it.copy(flagged = !wanted) else it }
                        selected = selected?.copy(flagged = !wanted)
                    }
                }
                Unit
            },
        )
    }

    /**
     * The shortcuts, and the one rule that makes them safe: a bare letter does nothing
     * while the search box has focus, or a reply would become a search for "r".
     */
    fun shortcut(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.key == Key.Slash && !searchFocused) {
            searchField.requestFocus()
            return true
        }
        if (searchFocused) return false
        val at = emails.indexOfFirst { it.id == selected?.id }
        fun step(delta: Int): Boolean {
            if (emails.isEmpty()) return true
            selected = emails[nextIndex(at, emails.size, delta)]
            return true
        }
        return when (event.key) {
            Key.J, Key.DirectionDown -> step(1)
            Key.K, Key.DirectionUp -> step(-1)
            Key.F5 -> { scope.launch { refreshNow() }; true }
            Key.C -> { sendError = null; composing = Draft(from = identities[here?.first].orEmpty().firstOrNull()?.email.orEmpty()); true }
            Key.R -> { selected?.let { m -> composing = replyTo(m, body, identities[here?.first].orEmpty().firstOrNull()?.email.orEmpty()) }; true }
            Key.F -> { selected?.let { m -> composing = forwardOf(m, body, identities[here?.first].orEmpty().firstOrNull()?.email.orEmpty()) }; true }
            Key.E -> { actions.archive?.invoke(); true }
            Key.Delete, Key.Backspace -> { actions.trash?.invoke(); true }
            Key.Escape -> {
                if (query.isNotEmpty()) { query = ""; showingResults = false; scope.launch { reload() } }
                true
            }
            else -> false
        }
    }

    val composer = composing
    if (composer != null) {
        Composer(
            identities = identities[here?.first]?.map { it.email }.orEmpty(),
            // The sign-off comes off the identity on the server, so one written in Bulwark
            // is the one used here without anything having to be imported or kept in step.
            initial = identities[here?.first].orEmpty()
                .firstOrNull { it.email.equals(composer.from, ignoreCase = true) }
                ?.let { signed(composer, it.textSignature, it.htmlSignature) }
                ?: composer,
            sending = sending,
            error = sendError,
            onDiscard = {
                // What was autosaved goes with it. Discard has to mean discarded, or the
                // Drafts folder fills with messages somebody decided against.
                val key = here?.first
                val going = draftId
                if (key != null && going != null) {
                    scope.launch { io { session(key).jmap.destroy(listOf(going)) } }
                }
                composing = null
                draftId = null
                sendError = null
            },
            onAttach = { files ->
                val key = here?.first
                val account = key?.let(::session)
                    ?: throw JmapError("Pick an account before attaching anything.")
                // One at a time rather than in parallel: a mail server is not a CDN, and
                // three large files racing each other is how an upload limit gets hit.
                withContext(Dispatchers.IO) { files.map { account.jmap.upload(it) } }
            },
            onSave = { draft ->
                val key = here?.first
                val account = key?.let(::session)
                val drafts = mailboxes[key].orEmpty().firstOrNull { it.role == "drafts" }
                val identity = identities[key].orEmpty().firstOrNull { it.email.equals(draft.from, true) }
                    ?: identities[key].orEmpty().firstOrNull()
                if (account == null || drafts == null || identity == null) {
                    throw JmapError("There is nowhere to save this: the account has no Drafts folder.")
                }
                draftId = withContext(Dispatchers.IO) {
                    account.jmap.saveDraft(draft, identity, drafts.id, draftId)
                }
            },
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
                                // The sent message is its own copy in Sent, so the working
                                // copy in Drafts is now a duplicate of mail already gone.
                                draftId?.let { runCatching { account.jmap.destroy(listOf(it)) } }
                            }
                            composing = null
                            draftId = null
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

    LaunchedEffect(Unit) { runCatching { keyboard.requestFocus() } }
    Column(
        Modifier.fillMaxSize()
            .focusRequester(keyboard)
            .focusable()
            .onPreviewKeyEvent(::shortcut),
    ) {
        update?.let { version ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    installNote ?: "Rampart $version is ready.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!installing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            // The window stays up while Windows fetches the package, which
                            // can be most of a minute, and Windows closes it at the swap.
                            // Quitting first would leave nothing on screen during the wait,
                            // which looks exactly like a button that did nothing.
                            if (Updates.restartToUpdate()) {
                                installing = true
                                installNote = "Installing Rampart $version. This window will close itself."
                            } else {
                                // Not a packaged copy. Closing is still the right move:
                                // Windows installs it on its own, only later.
                                onQuit()
                            }
                        }) { Text("Restart now") }
                        TextButton(onClick = { update = null }) { Text("Later") }
                    }
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
        SearchBar(
            query = query,
            focusRequester = searchField,
            onFocusChanged = { searchFocused = it },
            onQueryChange = { query = it },
            onSearch = {
                settingsOpen = false
                showingResults = query.isNotBlank()
                selected = null
                body = null
                scope.launch { reload() }
            },
        )
        Row(Modifier.fillMaxSize()) {
            Sidebar(
                accounts = sessions.map {
                    AccountMailboxes(it.key, it.account.name, it.account.email, mailboxes[it.key].orEmpty())
                },
                here = here,
                onSettings = { settingsOpen = !settingsOpen },
                inSettings = settingsOpen,
                onAddAccount = onAddAccount,
                collapsed = collapsed,
                onToggleCollapsed = { collapsed = !collapsed; Settings.setSidebarCollapsed(collapsed) },
                onSelect = { key, mailbox -> here = key to mailbox },
                onWrite = {
                    val from = identities[here?.first].orEmpty().firstOrNull()?.email
                        ?: sessions.firstOrNull { it.key == here?.first }?.account.let { it?.email }.orEmpty()
                    sendError = null
                    composing = Draft(from = from)
                },
            )
            VerticalDivider()
            if (settingsOpen) {
                SettingsPane(
                    accounts = sessions.map {
                        AccountMailboxes(it.key, it.account.name, it.account.email, mailboxes[it.key].orEmpty())
                    },
                    identities = identities[here?.first].orEmpty(),
                    signatureError = signatureError,
                    onSignature = { identity, html ->
                        val key = here?.first
                        if (key != null) {
                            signatureError = null
                            scope.launch {
                                // The plain half is derived from the HTML, so there is one
                                // thing to edit and the two cannot drift apart.
                                val text = plainOf(html)
                                try {
                                    withContext(Dispatchers.IO) {
                                        session(key).jmap.setSignature(identity.id, text, html)
                                    }
                                    identities = identities + (
                                        key to identities[key].orEmpty().map {
                                            if (it.id == identity.id) {
                                                it.copy(textSignature = text, htmlSignature = html)
                                            } else {
                                                it
                                            }
                                        }
                                        )
                                } catch (e: Exception) {
                                    signatureError = e.message ?: e.toString()
                                }
                            }
                        }
                    },
                    onPickSignatureImage = {
                        val file = pickFiles().firstOrNull()
                        if (file == null) {
                            null
                        } else {
                            try {
                                signatureError = null
                                imageDataUri(file)
                            } catch (e: Exception) {
                                signatureError = e.message ?: e.toString()
                                null
                            }
                        }
                    },
                    update = update,
                    notifyOnArrival = notifyOnArrival,
                    onNotifyOnArrival = { notifyOnArrival = it; Settings.setNotifyOnArrival(it) },
                    onTheme = onTheme,
                    onAddAccount = onAddAccount,
                    onRestart = { Updates.restartToUpdate(); onQuit() },
                    onClose = { settingsOpen = false },
                )
                return@Row
            }
            MessageList(
                emails = emails,
                selected = selected,
                loading = loading,
                title = here?.second?.name.orEmpty(),
                onRefresh = { scope.launch { refreshNow() } },
                onSelect = { selected = it },
            )
            VerticalDivider()
            Message(
                summary = selected,
                body = body,
                onReply = { all ->
                    val message = selected ?: return@Message
                    val ours = identities[here?.first].orEmpty().map { it.email }.toSet()
                    sendError = null
                    composing = replyTo(message, body, ours.firstOrNull().orEmpty(), all, ours)
                },
                replyAll = selected?.let {
                    hasOtherRecipients(it, body, identities[here?.first].orEmpty().map { id -> id.email }.toSet())
                } ?: false,
                onForward = {
                    val message = selected ?: return@Message
                    val from = identities[here?.first].orEmpty().firstOrNull()?.email.orEmpty()
                    sendError = null
                    composing = forwardOf(message, body, from)
                },
                onLink = { confirm = it },
                remoteImages = remoteImages,
                unsubscribed = unsubscribed,
                onUnsubscribe = { off ->
                    when {
                        // One-click is the only route that finishes without leaving Rampart,
                        // and it is the only one the sender promised would work that way.
                        off.oneClick && off.url != null -> {
                            unsubscribed = "Asking to be taken off the list."
                            scope.launch {
                                val done = withContext(Dispatchers.IO) { oneClickPost(off.url) }
                                unsubscribed = if (done) {
                                    "Asked to be taken off the list. It can take a few days."
                                } else {
                                    // Not an error worth a dialog: the link is still there.
                                    "That did not go through. Try the link instead."
                                }
                            }
                        }
                        // A page to open is a page somebody should see before it acts, so it
                        // goes through the same confirmation as any other link in a message.
                        off.url != null -> confirm = off.url
                        off.mailto != null -> {
                            sendError = null
                            composing = Draft(
                                from = identities[here?.first].orEmpty().firstOrNull()?.email.orEmpty(),
                                to = off.mailto,
                                subject = off.mailtoSubject ?: "unsubscribe",
                            )
                        }
                    }
                },
                onShowImages = { always ->
                    val message = selected
                    if (message != null) {
                        if (always) {
                            val key = imageSenderKey(message.fromEmail)
                            Settings.allowImagesFrom(key)
                            allowedSenders = allowedSenders + key
                        }
                        scope.launch { remoteImages = fetchRemote(body) }
                    }
                },
                bodyError = bodyError,
                images = inlineImages,
                thread = thread,
                onPick = { selected = it },
                actions = actions,
                attachments = attachments,
                savedTo = saved,
                onDownload = { attachment ->
                    val key = here?.first
                    if (key != null) {
                        scope.launch {
                            val landed = io {
                                val folder = downloadsFolder()
                                session(key).jmap.download(attachment, folder)
                            }
                            saved = landed?.toString()
                        }
                    }
                },
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
    collapsed: Boolean = false,
    inSettings: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    onSettings: () -> Unit,
    onAddAccount: () -> Unit,
    onWrite: () -> Unit,
    onSelect: (String, Mailbox) -> Unit,
) {
    Column(
        Modifier.width(if (collapsed) 60.dp else 232.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = if (collapsed) 8.dp else 12.dp, vertical = 14.dp),
        horizontalAlignment = if (collapsed) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (collapsed) {
            FilledIconButton(onClick = onWrite, modifier = Modifier.size(40.dp)) {
                Icon(RampartIcons.Write, contentDescription = "Write", modifier = Modifier.size(17.dp))
            }
        } else {
            Button(
                onClick = onWrite,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(40.dp),
            ) {
                Icon(RampartIcons.Write, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Write", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(14.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            accounts.forEachIndexed { index, account ->
                // With one account the heading is noise. With two it is the only way to tell
                // one Inbox from the other. Collapsed, there is no room for it at all, so
                // the accounts are separated by a rule instead.
                if (accounts.size > 1) {
                    item(key = "head-${account.key}") {
                        if (collapsed) {
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        } else {
                            Text(
                                shortAccountName(account.name, account.email).uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(
                                    start = 10.dp,
                                    end = 10.dp,
                                    top = if (index == 0) 4.dp else 16.dp,
                                    bottom = 4.dp,
                                ),
                            )
                        }
                    }
                }
                items(account.mailboxes, key = { "${account.key}/${it.id}" }) { box ->
                    FolderRow(
                        mailbox = box,
                        collapsed = collapsed,
                        selected = here?.first == account.key && here.second.id == box.id,
                        onClick = { onSelect(account.key, box) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))

        accounts.forEach { account ->
            if (collapsed) {
                Box(Modifier.padding(vertical = 4.dp)) { Avatar(account.name, account.email, 26.dp) }
            } else {
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(account.name, account.email, 26.dp)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            shortAccountName(account.name, account.email),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            account.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (collapsed) {
            IconButton(onClick = onSettings, modifier = Modifier.size(32.dp)) {
                Icon(
                    RampartIcons.Settings,
                    contentDescription = "Settings",
                    tint = if (inSettings) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = onToggleCollapsed, modifier = Modifier.size(32.dp)) {
                Icon(
                    RampartIcons.Expand,
                    contentDescription = "Widen the sidebar",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onAddAccount) { Text("Add account", style = MaterialTheme.typography.bodySmall) }
                IconButton(onClick = onSettings, modifier = Modifier.size(28.dp)) {
                    Icon(
                        RampartIcons.Settings,
                        contentDescription = "Settings",
                        tint = if (inSettings) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onToggleCollapsed, modifier = Modifier.size(28.dp)) {
                    Icon(
                        RampartIcons.Collapse,
                        contentDescription = "Narrow the sidebar",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Updates.current?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
            }
        }
    }
}

/**
 * Accounts are remembered under the address they were signed in with, so the heading was
 * showing a truncated email over a list of folders. The part before the @ is what a person
 * would call it.
 */
internal fun shortAccountName(name: String, email: String): String {
    if (name.isNotBlank() && !name.contains('@')) return name
    val local = email.substringBefore('@')
    val host = email.substringAfter('@', "").substringBefore('.')
    return if (local.equals("admin", ignoreCase = true) && host.isNotBlank()) host else local.ifBlank { email }
}

@Composable
private fun FolderRow(mailbox: Mailbox, collapsed: Boolean, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = if (collapsed) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                RampartIcons.forRole(mailbox.role),
                contentDescription = if (collapsed) mailbox.name else null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            // Collapsed there is no room for a count, so an unread folder carries a dot on
            // the corner of its icon instead of losing the signal altogether.
            if (collapsed && mailbox.unread > 0) {
                Box(
                    Modifier.size(7.dp).offset(x = 9.dp, y = (-8).dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        if (!collapsed) {
            Spacer(Modifier.width(10.dp))
            Text(
                mailbox.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (mailbox.unread > 0) {
                Text(
                    "${mailbox.unread}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
        }
    }
}

@Composable
internal fun SearchBar(
    query: String,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Built rather than borrowed: Material's text field has a minimum height of its
        // own and forcing it shorter clips the text inside it, which is what a 38dp
        // OutlinedTextField did here.
        Row(
            Modifier.width(520.dp).height(34.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(start = 11.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "Search all mail",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                        .copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocusChanged(it.isFocused) }
                        .onPreviewKeyEvent {
                            // Enter searches and Escape abandons it, which is what fingers
                            // do before they read any button.
                            when {
                                it.type != KeyEventType.KeyDown -> false
                                it.key == Key.Enter -> { onSearch(); true }
                                it.key == Key.Escape -> { onQueryChange(""); onSearch(); true }
                                else -> false
                            }
                        },
                )
            }
            if (query.isNotEmpty()) {
                TextButton(
                    onClick = { onQueryChange(""); onSearch() },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(26.dp),
                ) { Text("Clear", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
internal fun MessageList(
    emails: List<Summary>,
    selected: Summary?,
    loading: Boolean,
    title: String = "",
    onRefresh: () -> Unit = {},
    onSelect: (Summary) -> Unit,
) {
    Column(
        Modifier.width(368.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val unread = emails.count { !it.seen }
                if (unread > 0) {
                    Text(
                        "$unread unread",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(Modifier.width(6.dp))
                // Rampart looks for new mail on its own, but waiting up to a minute to find
                // out whether something arrived is not the same as being able to ask.
                IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(26.dp)) {
                    Icon(
                        RampartIcons.Refresh,
                        contentDescription = "Check for new mail",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                emails.isEmpty() -> Text(
                    "Nothing here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(emails, key = { it.id }) { message ->
                        MessageRow(message, message.id == selected?.id) { onSelect(message) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: Summary, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .height(IntrinsicSize.Min),
    ) {
        // A 2px edge rather than a fully tinted row: it marks the selection without
        // competing with the unread dot for the same piece of attention.
        Box(
            Modifier.width(2.dp).fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Column(Modifier.padding(start = 12.dp, end = 14.dp, top = 11.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp)) {
                    if (!message.seen) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary, CircleShape))
                    }
                }
                if (message.flagged) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        RampartIcons.Star,
                        contentDescription = "Starred",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(11.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    message.from,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (message.seen) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    message.receivedAt.asLocalTime(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(Modifier.padding(start = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (message.seen) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // The row stands for the whole conversation, so it has to say how much of
                // one is behind it. Without this the list silently hides the other replies.
                if (message.threadSize > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        message.threadSize.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            if (message.preview.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    message.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 13.dp),
                )
            }
        }
    }
}

/** The buttons the open message offers. A null one is not offered at all. */
internal data class MessageActions(
    val archive: (() -> Unit)? = null,
    val trash: (() -> Unit)? = null,
    val junk: (() -> Unit)? = null,
    val star: (() -> Unit)? = null,
)

@Composable
internal fun Message(
    summary: Summary?,
    body: Body?,
    onReply: (all: Boolean) -> Unit = {},
    replyAll: Boolean = false,
    /** Why the message would not open, when it would not. */
    bodyError: String? = null,
    /** Decoded images the message carries, by blob id. */
    images: Map<String, ImageBitmap> = emptyMap(),
    /** Pictures fetched from the web, once the reader said to. */
    remoteImages: List<ImageBitmap> = emptyList(),
    onShowImages: (always: Boolean) -> Unit = {},
    /** What happened to an unsubscribe that was pressed, when one was. */
    unsubscribed: String? = null,
    onUnsubscribe: (Unsubscribe) -> Unit = {},
    /** The whole conversation, oldest first, including [summary]. Empty when there is none. */
    thread: List<Summary> = emptyList(),
    onPick: (Summary) -> Unit = {},
    onForward: () -> Unit = {},
    actions: MessageActions = MessageActions(),
    attachments: List<Attachment> = emptyList(),
    savedTo: String? = null,
    onDownload: (Attachment) -> Unit = {},
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        ThemeArt(Modifier.align(Alignment.BottomEnd))
        Column(Modifier.fillMaxSize()) {
            if (summary == null) {
                Box(Modifier.fillMaxSize()) {
                    Text(
                        "Pick a message.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                return@Column
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.star?.let { star ->
                    IconButton(onClick = star, modifier = Modifier.size(34.dp)) {
                        Icon(
                            RampartIcons.Star,
                            contentDescription = if (summary.flagged) "Remove the star" else "Star this",
                            tint = if (summary.flagged) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                OutlinedButton(onClick = { onReply(false) }, enabled = body != null) { Text("Reply") }
                // Only when it would reach someone Reply would not.
                if (replyAll) {
                    OutlinedButton(onClick = { onReply(true) }, enabled = body != null) { Text("Reply all") }
                }
                OutlinedButton(onClick = onForward, enabled = body != null) { Text("Forward") }
                Spacer(Modifier.weight(1f))
                actions.archive?.let { OutlinedButton(onClick = it) { Text("Archive") } }
                actions.junk?.let { OutlinedButton(onClick = it) { Text("Spam") } }
                actions.trash?.let { OutlinedButton(onClick = it) { Text("Delete") } }
                // Only when the sender said how. Every client that offers Unsubscribe on
                // mail that has no List-Unsubscribe is really offering to send a reply
                // saying "unsubscribe" to somebody who is not reading replies.
                unsubscribeFrom(body?.listUnsubscribe, body?.listUnsubscribePost)?.let { off ->
                    OutlinedButton(onClick = { onUnsubscribe(off) }) {
                        Text(if (off.oneClick) "Unsubscribe" else "Unsubscribe...")
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Capped, because a paragraph set across a whole desktop window is a line
                // length nobody can follow back to the start of.
                Column(Modifier.widthIn(max = 660.dp).fillMaxWidth()) {
                    // The subject heads the conversation rather than the message: in a
                    // thread every message carries the same one with more Re: in front.
                    Text(summary.subject, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(14.dp))

                    val proof = remember(body) {
                        authenticityOf(body?.authenticationResults?.joinToString("\n"), body?.spamStatus)
                    }
                    if (proof.worthShowing) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                proof.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    unsubscribed?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }

                    // The rest of the conversation sits around this message in date order,
                    // one line each. Reading the thread is then scrolling, not going back
                    // to the list and finding the next one by hand.
                    val (earlier, later) = conversationAround(thread, summary)
                    earlier.forEach { ThreadRow(it) { onPick(it) } }
                    if (earlier.isNotEmpty()) Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(summary.from, summary.fromEmail.ifBlank { summary.from }, 34.dp)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                summary.from,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (summary.fromEmail.isNotBlank()) {
                                Text(
                                    summary.fromEmail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            summary.receivedAt.asLocalTime(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    if (rendered == null) {
                        Spacer(Modifier.height(20.dp))
                        when {
                            bodyError != null -> Text(
                                "This message would not open. $bodyError",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            body == null -> CircularProgressIndicator()
                            else -> Text("This message has no readable body.")
                        }
                    } else {
                    if (rendered.blockedImages > 0 && remoteImages.isEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (rendered.blockedImages == 1) "1 picture is held back."
                                else "${rendered.blockedImages} pictures are held back.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            // Fetching one tells the sender the message was opened, so it is
                            // the reader's call, and the answer is kept per sender rather
                            // than asked again on the next newsletter from the same place.
                            TextButton(onClick = { onShowImages(false) }) {
                                Text("Show", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onShowImages(true) }) {
                                Text(
                                    "Always from " + imageSenderKey(summary.fromEmail),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    SelectionContainer { Text(rendered.text, style = MaterialTheme.typography.bodyLarge) }
                    }

                    val drawn = attachments.filter { it.blobId in images }
                    if (drawn.isNotEmpty() || remoteImages.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        (drawn.mapNotNull { images[it.blobId] } + remoteImages).forEach { bitmap ->
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                // Inside rather than Fit, so a small logo stays a small
                                // logo. Fit blew a signature image up to the width of the
                                // reading pane.
                                contentScale = ContentScale.Inside,
                                // ponytail: drawn under the text rather than where the body
                                // puts them. Placing them in the flow means the renderer
                                // returning blocks instead of one string, which is a bigger
                                // change than seeing the picture is worth.
                                modifier = Modifier
                                    .sizeIn(maxWidth = 620.dp, maxHeight = 520.dp)
                                    .padding(vertical = 6.dp),
                            )
                        }
                    }

                    // Only the ones that are not already on screen above.
                    val files = attachments.filter { it.blobId !in images }
                    if (files.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(14.dp))
                        files.forEach { attachment ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        // The name a sender chose is shown as the name it will be
                                        // saved under, so the two can never disagree.
                                        safeFileName(attachment.name),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        humanSize(attachment.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                TextButton(onClick = { onDownload(attachment) }) { Text("Save") }
                            }
                        }
                        if (savedTo != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Saved to $savedTo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    if (later.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        later.forEach { ThreadRow(it) { onPick(it) } }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

/**
 * Where j and k land. Stepping off either end stays put rather than wrapping: a keystroke
 * that silently jumps from the newest message to the oldest is a keystroke people stop
 * trusting.
 */
internal fun nextIndex(current: Int, size: Int, delta: Int): Int {
    if (size == 0) return 0
    if (current < 0) return if (delta > 0) 0 else size - 1
    return (current + delta).coerceIn(0, size - 1)
}

/**
 * Where a saved attachment goes. The platform Downloads folder when there is one, the home
 * directory otherwise, and it is created rather than assumed: a save that fails because a
 * folder is missing is a bad way to learn the folder was missing.
 */
internal fun downloadsFolder(): java.nio.file.Path {
    val home = java.nio.file.Path.of(System.getProperty("user.home"))
    val downloads = home.resolve("Downloads")
    val target = if (java.nio.file.Files.isDirectory(downloads)) downloads else home
    java.nio.file.Files.createDirectories(target)
    return target
}

internal fun String.asLocalTime(): String =
    runCatching { WHEN.format(Instant.parse(this)) }.getOrDefault(this)

/**
 * One message in a conversation that is not the one being read: who, when, and the first
 * line of it. Clicking it opens that message where this one is.
 */
@Composable
private fun ThreadRow(message: Summary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(message.from, message.fromEmail.ifBlank { message.from }, 24.dp)
        Spacer(Modifier.width(9.dp))
        Text(
            message.from,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (message.seen) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            message.preview,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            message.receivedAt.asLocalTime(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * A conversation split into what came before the message being read and what came after.
 *
 * A thread that does not contain that message is stale, from the one that was open a
 * moment ago, and is dropped: putting somebody else's conversation around this message
 * would be worse than showing no conversation at all.
 */
internal fun conversationAround(thread: List<Summary>, open: Summary): Pair<List<Summary>, List<Summary>> {
    val at = thread.indexOfFirst { it.id == open.id }
    if (at < 0) return emptyList<Summary>() to emptyList()
    return thread.take(at) to thread.drop(at + 1)
}
