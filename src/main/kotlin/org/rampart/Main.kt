package org.rampart

import kotlinx.serialization.json.JsonObject
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density as ComposeDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.awt.Cursor
import java.awt.Desktop
import javax.swing.JOptionPane
import java.net.URI
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

fun main() {
    // Catches a startup crash on whichever thread it lands on, Swing's event thread
    // included: a deferred Windows package update that half-applied is exactly the kind
    // of thing that breaks composition on the first frame rather than in this function's
    // own call stack. Without this, that failure was the window simply never appearing,
    // with nothing recorded and nothing shown.
    Thread.setDefaultUncaughtExceptionHandler { _, thrown -> reportStartupFailure(thrown) }
    try {
        // Before anything is drawn, so a second copy costs a moment rather than a window.
        if (!SingleInstance.claim()) return
        // Before the first window, because it is read once when the scene is made.
        enableWebBody()
        application { Rampart() }
    } catch (thrown: Throwable) {
        reportStartupFailure(thrown)
    }
}

/**
 * What Justin hit on 2026-09-21: a deferred update applied at close, the swap did not go
 * cleanly, and the next launch gave no window and no reason. This is the fallback for
 * whatever of that is visible from inside the JVM at all: a package broken badly enough
 * that Windows never starts this process is outside anything here, but a package broken
 * just enough to start and then throw is not, and until now that case was just as silent.
 *
 * Plain AWT rather than Compose: whatever just failed may be the render pipeline itself,
 * so the one thing allowed to fail here is Swing.
 */
private fun reportStartupFailure(thrown: Throwable) {
    runCatching { Diagnostics.crash(thrown) }
    val repair = JOptionPane.showConfirmDialog(
        null,
        "Rampart did not start.\n\n${thrown.message ?: thrown.javaClass.simpleName}\n\n" +
            "This usually means an update did not finish installing. Reinstall the current version now?",
        "Rampart",
        JOptionPane.YES_NO_OPTION,
        JOptionPane.ERROR_MESSAGE,
    )
    if (repair == JOptionPane.YES_OPTION) {
        // Hands the staged manifest to Windows App Installer, which updates Rampart
        // and starts it again. The error dialog is only for a handoff that did not
        // open. lastProblem is already a sentence by then.
        if (!Updates.restartToUpdate()) {
            JOptionPane.showMessageDialog(
                null,
                Updates.lastProblem ?: "The reinstall did not go in.",
                "Rampart",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }
    kotlin.system.exitProcess(1)
}

@Composable
private fun ApplicationScope.Rampart() {
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
    /*
     * The count on the tray icon, and the reason it is hoisted this far.
     *
     * The tray belongs to the application rather than to the window, and the unread counts
     * live in the pane that draws the sidebar. Everything in between is a window that may
     * be minimised, hidden or behind something, and the whole point of a tray count is
     * that it is true in all three of those.
     */
    var unread by remember { mutableStateOf(0) }
    var closed by remember { mutableStateOf(false) }

    fun show() {
        closed = false
        windowState.isMinimized = false
    }

    // Clickable notifications and the taskbar count, chosen once for this computer. See DesktopShell.kt.
    remember(tray) {
        NewMailNotices.shell = desktopShell(
            if (isTraySupported) { title, body -> tray.sendNotification(Notification(title, body, Notification.Type.Info)) } else null,
        )
    }

    // No tray on this desktop means no notifications, and nothing else changes. Constructing
    // one anyway logs a warning on every start and still cannot deliver anything.
    if (isTraySupported) {
        Tray(
            icon = remember(icon, unread) { BadgedIcon(icon, unread) },
            state = tray,
            tooltip = if (unread > 0) "Rampart, $unread unread" else "Rampart",
            onAction = { show(); NewMailNotices.shell.trayActivated() },
            menu = {
                Item(if (closed) "Open Rampart" else "Show Rampart", onClick = ::show)
                Item("Quit", onClick = ::quit)
            },
        )
    }

    // Closed to the tray is closed, not quit: the window goes away, the count stays, and
    // mail keeps arriving. Only where there is a tray to go to, or it would be a close
    // button that loses the application.
    if (closed) return

    Window(
        onCloseRequest = {
            if (isTraySupported && Settings.closeToTray()) {
                remember()
                closed = true
            } else {
                quit()
            }
        },
        // The version lives in the sidebar. A title bar is for saying which app this is.
        title = "Rampart",
        icon = icon,
        state = windowState,
    ) {
        val followSystem = isSystemInDarkTheme()
        // The stored choice, not a theme remembered once. System is a null theme and a null
        // dark preference, and it has to be worked out again when the computer changes, or
        // it would only be right at the moment the window opened.
        var themeKey by remember { mutableStateOf(Settings.theme()) }
        var darkPref by remember { mutableStateOf(Settings.dark()) }
        var customThemes by remember { mutableStateOf(Settings.customThemes()) }
        var uiFont by remember { mutableStateOf(UiFont.of(Settings.fontSize())) }
        var animationsOn by remember { mutableStateOf(Settings.animations()) }
        // A theme being previewed before it is in the saved list. Cleared when the stored
        // choice is read again, so a change from another computer is not hidden behind it.
        var pinned by remember { mutableStateOf<Theme?>(null) }
        val theme = pinned ?: appearanceTheme(themeKey, darkPref, followSystem, THEMES + customThemes)
        var pack by remember { mutableStateOf(iconPack(Settings.iconPack())) }
        // The whole window, not just the part Compose draws. A black strip above a purple
        // theme is the one piece of the app that never matched the rest of it.
        LaunchedEffect(theme) {
            WindowChrome.setTitleBarColour(
                window,
                caption = theme.background.toArgb(),
                text = theme.text.toArgb(),
                dark = theme.dark,
            )
        }
        // The same count as the tray icon, on the taskbar button or the dock.
        LaunchedEffect(unread) { NewMailNotices.shell.unread(unread, window) }
        LaunchedEffect(Unit) {
            // After the window is up, so the first message does not pay for starting WebKit.
            warmWebBody()
        }
        LaunchedEffect(Unit) {
            SingleInstance.bringToFront {
                javax.swing.SwingUtilities.invokeLater {
                    windowState.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            }
        }
        // Two axes, on purpose. Somebody who likes the dark palette and wants heavier
        // glyphs should not have to choose between them. The font scale multiplies the
        // operating system's own, so Medium changes nothing and a larger system font still
        // applies. sp grows with it. The message is drawn by its own engine and does not.
        val baseDensity = LocalDensity.current
        fun refreshAppearance() {
            themeKey = Settings.theme()
            darkPref = Settings.dark()
            customThemes = Settings.customThemes()
            uiFont = UiFont.of(Settings.fontSize())
            animationsOn = Settings.animations()
            pinned = null
        }
        CompositionLocalProvider(
            LocalRampartTheme provides theme,
            LocalIconPack provides pack,
            LocalDensity provides ComposeDensity(baseDensity.density, baseDensity.fontScale * uiFont.scale),
            LocalAnimationsEnabled provides animationsOn,
        ) {
            MaterialTheme(
                colorScheme = theme.scheme(),
                typography = RampartTypography,
            ) {
                Surface(Modifier.fillMaxSize()) {
                    App(
                        onTheme = { picked ->
                            Settings.setAppearance(picked.key, picked.dark)
                            themeKey = picked.key
                            darkPref = picked.dark
                            customThemes = Settings.customThemes()
                            pinned = picked
                        },
                        onAppearance = ::refreshAppearance,
                        icons = pack,
                        onIcons = { pack = it; Settings.setIconPack(it.key) },
                        onQuit = ::quit,
                        notify = { title, message ->
                            tray.sendNotification(Notification(title, message, Notification.Type.Info))
                        },
                        onUnread = { unread = it },
                        windowSize = { windowState.size },
                    )
                    AdminWindow()
                }
            }
        }
    }
}

/**
 * Small enough that a subject line and the formatting row still fit, and no smaller: below
 * this the panel stops being useful before it stops being draggable.
 */
internal val ComposeMinSize = DpSize(380.dp, 320.dp)

/** A window size to fall back on before the real one has ever been reported, shared by
 *  every default for [Reader]'s `windowSize` parameter rather than repeated at each one. */
private val DefaultWindowSize = DpSize(1440.dp, 900.dp)

/**
 * Where a drag on the compose panel's corner handle lands, between [ComposeMinSize] and the
 * live window it is dragged inside.
 *
 * Pure, and kept apart from the drag gesture itself, so the boundary cases can be checked
 * directly rather than by driving a pointer: dragging below the minimum, dragging past the
 * window, and a window that is itself smaller than the minimum, which is the case a plain
 * `coerceIn` gets backwards, handing back a maximum below the minimum it is meant to floor.
 *
 * The window bound replaces the flat 620dp ceiling the panel used before there was a handle
 * to drag: `margin` is the same 16dp the panel already keeps clear on each side it isn't
 * anchored to (`Modifier.padding(16.dp)`, doubled because growing the panel can now use up
 * the gap on both edges of an axis), and the 0.8 is the same `fillMaxHeight(0.8f)` fraction
 * the panel always capped its height at. A window smaller than 620 is no longer asked for
 * room it does not have, and one larger is no longer left with 620 unused.
 */
internal fun clampComposeSize(wanted: DpSize, window: DpSize): DpSize {
    val margin = 32.dp
    val maxWidth = (window.width - margin).coerceAtLeast(ComposeMinSize.width)
    val maxHeight = (window.height * 0.8f).coerceAtLeast(ComposeMinSize.height)
    return DpSize(
        wanted.width.coerceIn(ComposeMinSize.width, maxWidth),
        wanted.height.coerceIn(ComposeMinSize.height, maxHeight),
    )
}

@Composable
private fun App(
    onTheme: (Theme) -> Unit,
    onQuit: () -> Unit,
    notify: (String, String) -> Unit,
    icons: IconPack = LineIcons,
    onIcons: (IconPack) -> Unit = {},
    /** Unread conversations in the inboxes that feed All inboxes, for the tray badge. */
    onUnread: (Int) -> Unit = {},
    /** See [Reader], which is what actually needs this. */
    windowSize: () -> DpSize = { DefaultWindowSize },
    /** Theme mode, font size and animations live on the window, above this. */
    onAppearance: () -> Unit = {},
) {
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(true) }

    // Sign in again to whatever the operating system remembered. A password that no longer
    // works is not an error worth a dialog: that account simply is not signed in, and the
    // sign-in screen is already the answer.
    LaunchedEffect(Unit) {
        // Every account at once rather than each waiting for the one before: signing in is
        // a session request and often a redirect, so three accounts one after another was
        // six round trips of waiting before the window could show anything.
        sessions = withContext(Dispatchers.IO) {
            Accounts.read().map { account ->
                async {
                    // Signed in through Google or Microsoft: tokens, not a password. OAuthUi.kt.
                    if (account.oauth.isNotBlank()) return@async restoreOAuth(account)?.let { Session(account, it) }
                    val password = Secrets.load(account) ?: return@async null
                    runCatching { Session(account, openSaved(account, password)) }.getOrNull()
                }
            }.awaitAll().filterNotNull()
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
            Spinner()
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
        Column(Modifier.fillMaxSize()) {
            // An account whose Google or Microsoft sign-in was refused, with the button to redo it.
            SignInAgainBanner(sessions.map { it.key }.toSet()) { account, backend -> sessions = sessions + Session(account, backend) }
            Box(Modifier.weight(1f)) {
                Reader(
                    sessions, onTheme, onQuit, notify,
                    onAddAccount = { adding = true },
                    icons = icons, onIcons = onIcons, onUnread = onUnread,
                    windowSize = windowSize,
                    onAppearance = onAppearance,
                )
            }
        }
    }
}

/**
 * What to put on screen for a failure. Never returns for a cancellation.
 *
 * `catch (e: Exception)` catches [kotlinx.coroutines.CancellationException] along with
 * everything else, and cancellation is not a fault: it is how a screen says it has stopped
 * caring about a request it started. Switching folder cancels the load. Closing the
 * composer cancels the save. Signing out cancels all of it.
 *
 * Reported as a failure, the user is shown Compose's own internal wording for it, "The
 * coroutine scope left the composition", in the same red bar that reports a server
 * refusing to send their mail. So this rethrows instead, which is what a cancellation is
 * supposed to do: unwind the coroutine that was cancelled and tell nobody.
 */
/**
 * A call that may fail, without swallowing the cancellation that everything here depends on.
 *
 * `runCatching` catches [CancellationException] with everything else, and in a coroutine
 * that turns "the reader moved to another message" into "the server failed", which then
 * writes an error where the next message is about to be drawn.
 */
private suspend fun <T> tried(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

internal fun whyFailed(e: Throwable): String {
    if (e is CancellationException) throw e
    return e.message ?: e.toString()
}

/**
 * Addresses already in this account's book, lowercased.
 *
 * The book kept beside the accounts, not the server's contact cards: that file is the
 * one every account has, and "someone I know" is "someone already in it". [memory] is
 * the copy the window is holding, which is ahead of the file once mail has been read
 * this session. The file is only opened when that copy has not been loaded yet.
 */
private fun knownAddresses(key: String, memory: Map<String, List<Person>>): Set<String> {
    val people = memory[key] ?: AddressBook.read(AddressBook.file(key))
    return people.map { it.email.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}

/**
 * One page of a folder under the quick filters.
 *
 * Unread and starred are asked of whichever protocol is live. Attachment too, where
 * [asked] still has it, which is only JMAP. Tagged and known sender go to the server
 * on JMAP (an OR of the keywords, an OR of the book) and to the saved copy on IMAP,
 * because that protocol has no search term for either that stays honest on a real
 * folder. A missing copy is an empty page, not the unfiltered folder.
 */
private fun quickPage(
    backend: MailBackend,
    store: Store?,
    mailboxId: String,
    asked: QuickFilters,
    known: Collection<String>,
    from: Int = 0,
    limit: Int = 100,
): List<Summary> {
    if (backend is Imap && (asked.tagged || asked.knownSender)) {
        return store?.messages(mailboxId, limit, from, filters = asked, knownSenders = known).orEmpty()
    }
    val words = if (asked.tagged) store?.keywords().orEmpty() else emptyList()
    return backend.emails(
        mailboxId,
        limit = limit,
        from = from,
        filters = asked,
        knownSenders = known,
        userKeywords = words,
    )
}

@Composable
private fun Reader(
    sessions: List<Session>,
    onTheme: (Theme) -> Unit,
    onQuit: () -> Unit,
    notify: (String, String) -> Unit,
    onAddAccount: () -> Unit,
    icons: IconPack = LineIcons,
    onIcons: (IconPack) -> Unit = {},
    /** Unread conversations in the inboxes that feed All inboxes, for the tray badge. */
    onUnread: (Int) -> Unit = {},
    /**
     * The window's current content size, read fresh rather than carried as a plain value: a
     * compose panel drag needs it only at the moment of the drag, and a state read here
     * would recompose this whole screen on every frame of an unrelated window resize.
     * [Rampart] already owns a [androidx.compose.ui.window.WindowState] for exactly this;
     * this is that state's `size`, handed down rather than re-asked for.
     */
    windowSize: () -> DpSize = { DefaultWindowSize },
    /** Theme, font size and animations are held by the window. This asks it to read them again. */
    onAppearance: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    fun phoneAlert(title: String, message: String) {
        val provider = Settings.phoneAlertProvider()
        if (provider.equals("gotify", ignoreCase = true)) {
            val server = Settings.gotifyServer()
            val token = Secrets.loadNamed(Secrets.GOTIFY_TOKEN)
            if (server.isBlank() || token.isNullOrBlank()) return
            scope.launch(Dispatchers.IO) {
                runCatching { Gotify.send(server, token, title, message) }
            }
        } else {
            val topic = Settings.ntfyServer()
            if (topic.isBlank()) return
            scope.launch(Dispatchers.IO) {
                runCatching { Ntfy.send(topic, Secrets.loadNamed(Secrets.NTFY_TOKEN), title, message) }
            }
        }
    }
    var identities by remember { mutableStateOf<Map<String, List<Identity>>>(emptyMap()) }
    var vacation by remember { mutableStateOf<Vacation?>(null) }
    var vacationError by remember { mutableStateOf<String?>(null) }
    var composing by remember { mutableStateOf<ComposeSession?>(null) }
    var composeEpoch by remember { mutableStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var sendDetail by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<String?>(null) }
    var updateCheckFailure by remember { mutableStateOf<UpdateCheckFailure?>(null) }
    // Whether a check asked for from the About page, rather than the half-hourly one, is
    // still in flight, so the button there can say so instead of doing nothing visibly.
    var checkingUpdate by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showingResults by remember { mutableStateOf(false) }
    /**
     * The tag being looked at, and whose account, or null for an ordinary folder view.
     *
     * A third kind of list beside a folder and a search result. It is not a folder: the
     * messages in it are wherever they were filed, which is the point of a tag.
     */
    var viewingTag by remember { mutableStateOf<String?>(null) }
    // A person's history in the list pane, when one is open. See PersonHistory.kt.
    var viewingPerson by remember { mutableStateOf<Correspondent?>(null) }
    var personHistory by remember { mutableStateOf<PersonHistory?>(null) }
    var personStats by remember { mutableStateOf(PersonStats()) }
    var personNote by remember { mutableStateOf<String?>(null) }
    /** Every tag each account has, read out of its local copy. */
    var tagsSeen by remember { mutableStateOf<Map<String, Map<String, Int>>>(emptyMap()) }
    var tagColours by remember { mutableStateOf(Settings.tagColours()) }
    var folded by remember { mutableStateOf(Settings.collapsedSections()) }
    var tintRows by remember { mutableStateOf(Settings.tintRowsByTag()) }
    var density by remember { mutableStateOf(Density.of(Settings.density())) }
    var sidebarIcons by remember { mutableStateOf(SidebarIcons.of(Settings.sidebarIcons())) }
    var undoBarSeconds by remember { mutableStateOf(Settings.undoBarSeconds()) }
    var loader by remember { mutableStateOf(Loader.of(Settings.loader())) }
    /*
     * What is waiting to go out, so a Drafts row can say when. The file is the
     * record. This is only so the list does not re-read that file for every row
     * on every recomposition. It is written again whenever the file changes.
     */
    var scheduledSends by remember { mutableStateOf(ScheduledSends.pending()) }
    // Ids currently in [deliverNow], so the minute poll and Send now cannot both send one message.
    val firing = remember { mutableSetOf<String>() }
    var trackingServer by remember { mutableStateOf(Settings.trackingServer()) }
    /** The meeting this message is about, when it is about one. */
    var invitation by remember { mutableStateOf<Invitation?>(null) }
    /** What the answer being sent was, so the buttons say so and cannot be pressed twice. */
    var answering by remember { mutableStateOf<Rsvp?>(null) }
    /** The message being dragged onto a tag, while it is being dragged. */
    var dragging by remember { mutableStateOf<Summary?>(null) }
    /** Where the pointer is during that drag, in window coordinates. */
    var dragAt by remember { mutableStateOf<Offset?>(null) }
    /**
     * Where each tag row is on screen.
     *
     * A plain map rather than state: it is written during layout and only read when a drag
     * ends, so making it state would recompose the whole window every time the sidebar
     * moved a pixel.
     */
    // Keyed by the keyword alone: the tag list is one list now, and a message dropped on a
    // tag is tagged in its own account, which the message already says.
    val tagBounds = remember { mutableMapOf<String, Rect>() }
    // Any text field, not just search: a bare letter is a shortcut only when nothing
    // is being typed into. Tagging a message shares this for the same reason.
    var typing by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(Settings.sidebarCollapsed()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var contactsOpen by remember { mutableStateOf(false) }
    var dashboardOpen by remember { mutableStateOf(false) }
    var calendarOpen by remember { mutableStateOf(false) }
    /** Null until it has been counted, which is one pass over the local copy. */
    var stats by remember { mutableStateOf<MailStats?>(null) }
    // The server's cards, with the JSON each came from, so a save can be built on top
    // of it and leave the properties this build does not draw alone.
    var contacts by remember { mutableStateOf<List<Pair<Contact, JsonObject>>>(emptyList()) }
    var contactBooks by remember { mutableStateOf<List<ContactBook>>(emptyList()) }
    var senderPhotos by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var quotas by remember { mutableStateOf<Map<String, List<MailQuota>>>(emptyMap()) }
    var contactsLoading by remember { mutableStateOf(false) }
    var contactsError by remember { mutableStateOf<String?>(null) }
    var signatureError by remember { mutableStateOf<String?>(null) }
    /** What the bottom bar says about updates. See [UpdateBarState]. */
    var barState by remember { mutableStateOf<UpdateBarState>(UpdateBarState.Hidden) }
    /**
     * What [ChangelogDialog] is showing, or null while it is closed. Worked out once, when
     * this composable is first entered, from whatever is already known synchronously
     * ([Updates.current], the changelog baked into this build, and what was last recorded
     * seen): no network call decides whether this appears, only whether a newer package
     * exists at all, which the update bar already asks about on its own schedule.
     *
     * A fresh install, or an existing one updating into the very first build to carry this
     * feature, has [Settings.changelogSeen] empty. There is nothing to compare the running
     * version against then, so nothing is shown; the running version is recorded as seen
     * instead, purely so the *next* update has a baseline and does not stay silent forever.
     */
    var changelogDialog by remember {
        mutableStateOf<List<Change>?>(
            Updates.current?.let { running ->
                val seen = Settings.changelogSeen()
                when {
                    seen.isEmpty() -> {
                        Settings.setChangelogSeen(running)
                        null
                    }
                    Settings.changelogSuppressed() || !Updates.isNewer(running, seen) -> null
                    else -> unseenChanges(changelog(), seen)
                }
            },
        )
    }
    var notifyOnArrival by remember { mutableStateOf(Settings.notifyOnArrival()) }
    var notifyOnOpen by remember { mutableStateOf(Settings.notifyOnOpen()) }
    var order by remember { mutableStateOf(Settings.order()) }
    var orderAll by remember { mutableStateOf(Settings.orderAppliesToAll()) }
    // Not remembered between runs on purpose: opening the app into a folder that is hiding
    // most of itself, with no memory of having asked for that, reads as lost mail.
    var quick by remember { mutableStateOf(QuickFilters()) }
    /**
     * Why a toggle could not be answered from the saved copy, when that is the only
     * place left to ask. Null the rest of the time, including when the toggle is simply
     * off. Cleared as soon as a later load does not need it.
     */
    var filterNote by remember { mutableStateOf<String?>(null) }
    var paperMessages by remember { mutableStateOf<Set<CardKey>>(emptySet()) }
    // Not remembered: a panel is the right default every time, and a message that needed
    // the whole window last week is not a reason to open the next one that way.
    var composeFull by remember { mutableStateOf(false) }
    // Unlike composeFull, this is remembered: a corner someone dragged is a size they chose,
    // and a fresh compose panel opening back at 620x620 would throw that away every time.
    // Read once, at the top of the session; written back to Settings only when a drag ends,
    // not on every pixel of it, the same restraint a text field would use for something
    // typed continuously.
    var composeWidth by remember { mutableStateOf(Settings.composeWidth().dp) }
    var composeHeight by remember { mutableStateOf(Settings.composeHeight().dp) }
    // Set while a send is waiting out its window, so the bar can offer to take it back.
    var undoSend by remember { mutableStateOf<(() -> Unit)?>(null) }
    var sendCancelled by remember { mutableStateOf(false) }
    // The server's filter script, for the account whose settings are showing.
    var filters by remember { mutableStateOf<Script?>(null) }
    var filterScript by remember { mutableStateOf<Jmap.SieveInfo?>(null) }
    var filtersSaving by remember { mutableStateOf(false) }
    var filtersSupported by remember { mutableStateOf(true) }
    var filtersError by remember { mutableStateOf<String?>(null) }
    // Which account's filters are on the screen, or null for the set kept for all of them.
    var filterAccount by remember { mutableStateOf<String?>(null) }
    // Which account the per-account settings pages are showing.
    var chosenSettingsAccount by remember { mutableStateOf<String?>(null) }
    var globalFilters by remember { mutableStateOf(Filters.read()) }
    // Who you write to, per account, read once and kept up to date as mail goes past.
    var books by remember { mutableStateOf<Map<String, List<Person>>>(emptyMap()) }
    // A folder operation waiting on a name, or on a yes.
    var folderAsk by remember { mutableStateOf<FolderAsk?>(null) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var savedSearches by remember { mutableStateOf(Settings.savedSearches()) }
    var savedSearchCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // The child folders of each split saved search, by parent id, from the last count.
    var savedSearchGroups by remember { mutableStateOf<Map<String, List<SplitGroup>>>(emptyMap()) }
    var activeSavedSearch by remember { mutableStateOf<SavedSearch?>(null) }
    var savedSearchAsk by remember { mutableStateOf<SavedSearchAsk?>(null) }
    /** The message whose row asked for a filter, or null while that dialog is closed. */
    var filterFor by remember { mutableStateOf<Summary?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var listEpoch by remember { mutableStateOf(0) }
    // Set when a page comes back short, so the bottom of a folder is not re-queried forever.
    var exhausted by remember { mutableStateOf(false) }
    val searchField = remember { FocusRequester() }
    val keyboard = remember { FocusRequester() }
    var mailboxes by remember { mutableStateOf<Map<String, List<Mailbox>>>(emptyMap()) }

    var here by remember { mutableStateOf<Pair<String, Mailbox>?>(null) }
    var emails by remember { mutableStateOf<List<Summary>>(emptyList()) }
    // True when the selection came from a right-click, which picks the row for its menu
    // and must not also open a draft in the composer.
    var selectedForMenu by remember { mutableStateOf(false) }
    // Which folder the rows in `emails` came from, so switching folders never shows the
    // last folder's rows under the new folder's name while the new one loads.
    var emailsFrom by remember { mutableStateOf<Any?>(null) }
    var selected by remember { mutableStateOf<Summary?>(null) }
    var focusedInbox by remember { mutableStateOf(Settings.focusedInbox()) }
    var focusOverrides by remember { mutableStateOf(Settings.focusOverrides()) }
    var focusTab by remember { mutableStateOf(FocusTab.FOCUSED) }
    var expandedBundles by remember { mutableStateOf(setOf<String>()) }
    /**
     * Everything fetched for the open conversation's messages, by id. See [Card].
     *
     * A card that is expanded needs its own body, and a reader who opens four messages in
     * a thread must not pay for the first one again when they come back to it. See
     * [loadCard], which is the only place anything is written in here.
     */
    var cards by remember { mutableStateOf<Map<CardKey, Card>>(emptyMap()) }
    /**
     * How many loads have started for each card.
     *
     * A load that finishes after a newer one has started for the same card must not
     * write. The number at the start of the load is the only one allowed to.
     */
    val cardEpoch = remember { mutableMapOf<CardKey, Int>() }
    /** The whole conversation, oldest first, including whichever message was opened. */
    var thread by remember { mutableStateOf<List<Summary>>(emptyList()) }
    /**
     * Which messages of [thread] are drawn as a full card rather than a one-line row.
     *
     * Expanding never swaps what is already on screen: a click adds or removes exactly one
     * id here, and every card already open stays exactly where it was. See [initialExpanded]
     * for what this starts as.
     */
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    /**
     * Cards the reader opened themselves, which is the only thing that marks one read.
     *
     * Separate from [expanded] because a card can be open without anybody having asked for
     * it: unread messages in a conversation start open so they can be seen. Marking those
     * read would mark a ten message thread read the moment it was opened, including the
     * nine nobody scrolled to, which is the exact failure the mark read delay exists to
     * prevent. Pressing a collapsed message open is a different statement, and the same one
     * clicking a message in the list makes.
     */
    var openedByHand by remember { mutableStateOf<Set<String>>(emptySet()) }
    /**
     * Messages the reader marked unread while looking at them.
     *
     * The mark-as-read effects run again whenever the open row changes, and marking it
     * unread is such a change, so without this the message went straight back to read,
     * on the server as well as on screen. Cleared when a different message is opened.
     */
    var keptUnread by remember { mutableStateOf<Set<String>>(emptySet()) }

    /**
     * Messages filed out of this conversation while the rest of it was still being fetched.
     *
     * The thread request goes out when a conversation opens and lands a moment later. Archive
     * a card in between and the answer, which was assembled before the move, puts it back on
     * screen as though the button had not worked. Held here so the thread is filtered by it
     * whenever it is assigned.
     */
    var filed by remember { mutableStateOf<Set<String>>(emptySet()) }
    /**
     * Conversations whose filing is already in flight.
     *
     * The row, the card and the conversation menu are three ways to ask for the same
     * move, and a double click is two of them. A second one is ignored until the first
     * finishes, so it cannot send the move twice. Keyed by account and thread.
     */
    val filing = remember { mutableSetOf<String>() }
    /** The card [expanded] should be scrolled into view once it has been laid out. */
    var pendingScrollTo by remember { mutableStateOf<String?>(null) }
    /** Whether the open conversation is muted. Read off disk when the message changes. */
    var conversationMuted by remember { mutableStateOf(false) }
    // Held rather than read where it is used, so changing it in Settings changes the
    // message already on screen instead of the one after next.
    var messageMode by remember { mutableStateOf(Settings.messageMode()) }
    var messageScale by remember { mutableStateOf(Settings.messageScale()) }
    // The focused layout's cover. The selected row stays in [selected], so going
    // back lands on the same one. [focusedPrev] is the row that cover was decided
    // against, so a later move can tell "the open message changed" from "the list moved".
    var focusedNav by remember { mutableStateOf(FocusedNav()) }
    var focusedPrev by remember { mutableStateOf<String?>(null) }
    // Worked out while the frame is built, not after it. Next and previous, and an
    // archive that advances, change the selected row on their own. Waiting for a later
    // effect would draw the list for a moment between one message and the next.
    // Becoming the focused layout with a row already chosen opens it. A fresh launch
    // has no row yet, so the list is what comes up. Back only clears the cover, and
    // this leaves it cleared: the same row was already the one on screen.
    val selectedKey = selected?.let(::rowToken)
    val layoutNow = MailLayoutState.layout
    var layoutSeen by remember { mutableStateOf(layoutNow) }
    val enteringFocused = layoutNow == MailLayout.FOCUSED && layoutSeen != MailLayout.FOCUSED
    val navForFrame = if (enteringFocused && selectedKey != null) focusedNav.opened(selectedKey) else focusedNav
    val followedNav = focusAfterSelectionChange(navForFrame, focusedPrev, selectedKey)
    SideEffect {
        layoutSeen = layoutNow
        if (focusedNav != followedNav) focusedNav = followedNav
        if (focusedPrev != selectedKey) focusedPrev = selectedKey
    }
    // A transcript belongs to the account whose mail it describes. In All inboxes the
    // selected row changes this key, so no history follows the person into another account.
    var rookConversations by remember { mutableStateOf(RookConversations()) }
    var trackingBadges by remember(sessions) {
        mutableStateOf(trackingBadgesOf(sessions.flatMap { it.store?.tracking().orEmpty() }))
    }
    var chatThinking by remember { mutableStateOf(false) }
    // Told by the composer and the filter box whenever either has a request of its own
    // in flight, so the app bar's Rook button can animate for those too, not only chat.
    var composeBusy by remember { mutableStateOf(false) }
    var filterBusy by remember { mutableStateOf(false) }
    // Read once rather than on every recomposition of the panel: it comes off disk.
    var chatAgreed by remember { mutableStateOf(Assistant.agreed(Assistant.CHAT)) }
    /*
     * Every message id this panel has put on screen in this conversation.
     *
     * The whole of the guard against a message talking the model into acting on something
     * nobody asked about: an action can only name an id that came out of a search run
     * here. Cleared with the transcript, because a new conversation has been shown nothing.
     */
    val chatShown = remember { mutableMapOf<String, MessageAllowances>() }
    // Every pending card is kept under the account where Rook made it. Switching an All
    // inbox row therefore switches cards as well as words and allowed ids.
    var settingCards by remember { mutableStateOf<Map<String, ChangeDesk>>(emptyMap()) }
    // Filters Rook has asked for. Only a Save press in the panel writes one, and it writes
    // to the account stored on the card, which is the account Rook was working in.
    var filterCards by remember { mutableStateOf<Map<String, FilterDesk>>(emptyMap()) }
    var mailChangeCards by remember { mutableStateOf<Map<String, MailChangeDesk>>(emptyMap()) }
    fun appendRook(key: String, lines: List<Said>) {
        rookConversations = rookConversations.append(key, lines)
    }
    fun appendRook(key: String, line: Said) = appendRook(key, listOf(line))
    /** A filter description waiting to be allowed out, the same ask the Filters page makes. */
    var filterConsent by remember { mutableStateOf<FilterConsent?>(null) }
    /*
     * The paragraph a thread was last summarised into, and everything about getting one.
     *
     * Kept beside [thread] rather than derived from it: asking the model is a request that
     * costs money and takes a moment, so what it answered has to survive recomposition
     * until something explicitly asks for a fresh one.
     */
    var summarising by remember { mutableStateOf(false) }
    /** The sentence [LlmError] carried, or one built locally, from the last attempt. */
    var summariseError by remember { mutableStateOf<String?>(null) }
    /** The packet waiting on the viewer: the first use asking permission, or a later look
     * asked for on demand. Null when the dialog is closed. */
    var summarisePacket by remember { mutableStateOf<String?>(null) }
    /** Which conversation [summarisePacket] was built for, so an answer that lands after
     * the reader has moved to a different thread is dropped rather than shown as this
     * one's. */
    var summariseFor by remember { mutableStateOf<String?>(null) }
    // Read once rather than on every recomposition, for the same reason as chatAgreed.
    var summariseAgreed by remember { mutableStateOf(Assistant.agreed(Assistant.SUMMARISE)) }
    /** Suggested replies for the open conversation, made only when asked. `WritingHelpUi.kt`. */
    val suggestReplies = remember { SuggestReplies(scope) }
    // Ask Rook from the search box, the Today view, and action items. See AskInboxUi.kt
    // and BriefingPane.kt: each is a button press, never a timer.
    val askJob = remember { RookJob<InboxAnswer>() }
    val today = remember { TodayView() }
    val actionJob = remember { RookJob<List<ActionItem>>() }
    /**
     * Messages shown their remote pictures for this session alone, on top of whatever
     * [allowedSenders] remembers permanently.
     *
     * Deliberately not part of [Card]: it is not scoped to the open conversation the way
     * everything in there is, on purpose. Once a reader has said "show this one", asking
     * again because they came back to it a minute later would be the button lying about
     * what it just agreed to. It is a session-long promise, so it is a session-long set,
     * kept apart from [allowedSenders] because "show this once" and "always show this
     * sender" are different promises and only one of them survives a restart.
     */
    var shownOnce by remember { mutableStateOf<Set<String>>(emptySet()) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var anchor by remember { mutableStateOf<String?>(null) }
    var undo by remember { mutableStateOf<Undoable?>(null) }
    var allowedSenders by remember { mutableStateOf(Settings.imageSenders()) }
    val picturesOn = remember(SenderPictureSignals.revision.value) { Settings.messagePictures() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    /** A message/rfc822 attachment opened for reading. Not a message in the mailbox. */
    var attached by remember { mutableStateOf<AttachedMessage?>(null) }
    var attachedSaved by remember { mutableStateOf<String?>(null) }
    /** A winmail.dat opened into the files it actually contained. */
    var tnef by remember { mutableStateOf<TnefContents?>(null) }
    var tnefName by remember { mutableStateOf("") }
    var tnefSaved by remember { mutableStateOf<String?>(null) }
    var showShortcuts by remember { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }

    /** Mailboxes shared with the signed-in logins, kept apart from them (SharedMailboxesUi.kt). */
    val sharing = rememberSharing(sessions)

    /*
     * The number on the tray icon, and on the taskbar where the window draws one.
     *
     * Unread conversations in the inboxes that feed All inboxes, and only the inboxes:
     * a count that included Junk and Archive would go up when the filter caught something,
     * which is the opposite of what a badge is for. A shared mailbox switched out of that
     * view still has its own row, and it is not added in here. The All inboxes row uses
     * the same total. This sits after the shared-mailbox preferences exist, because the
     * total cannot be decided without them.
     */
    LaunchedEffect(mailboxes, sharing.prefs) {
        onUnread(unifiedInboxUnread(mailboxes, sharing.prefs))
    }

    fun session(key: String) = sessions.firstOrNull { it.key == key } ?: sharing.shared.first { it.key == key }

    /** The red bar: a sentence, and the technical line under it when there is one. */
    fun report(title: String, detail: String? = null) {
        error = title
        errorDetail = detail?.trim()?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
    }

    /** Whether the open folder is the merged one rather than a real folder on a server. */
    fun unified() = here?.first == ALL_ACCOUNTS

    /**
     * Which server to talk to about [message].
     *
     * In a normal folder that is simply the account the folder belongs to. In the merged
     * inbox every message carries its own, and using the folder's would send every reply,
     * star and archive to whichever account happened to be first.
     */
    fun accountOf(message: Summary?): String? =
        message?.account?.ifBlank { null } ?: here?.first?.takeIf { it != ALL_ACCOUNTS }

    /** Follow-up flags (FollowUpUi.kt). A row is repainted only once the server has taken the change. */
    val followUps = rememberFollowUps(
        scope = scope,
        backend = { k -> runCatching { session(k).jmap }.getOrNull() },
        accountOf = { accountOf(it) },
        own = { k -> identities[k].orEmpty().map { it.email.trim().lowercase() }.toSet() },
        report = { title, detail -> report(title, detail) },
        repaint = { account, id, keywords ->
            emails = emails.map { if (it.sameMail(account, id)) it.copy(keywords = keywords) else it }
            thread = thread.map { if (it.sameMail(account, id)) it.copy(keywords = keywords) else it }
            selected?.let { if (it.sameMail(account, id)) selected = it.copy(keywords = keywords) }
            // Cleared or moved later, it leaves the Follow up view at once.
            if (viewingTag == FOLLOW_UP && !followUpDue(keywords, Instant.now())) {
                emails = emails.filterNot { it.sameMail(account, id) }
            }
        },
    )

    /** How the list collapses its rows into conversations. See [ThreadContext]. */
    fun threadContext() = ThreadContext(
        account = here?.first?.takeIf { it != ALL_ACCOUNTS },
        collapse = !showingResults && activeSavedSearch == null && viewingTag == null && viewingPerson == null,
        leaveOut = sessions.associate { open ->
            val boxes = mailboxes[open.key].orEmpty()
            open.key to setOfNotNull(folderFor("trash", boxes)?.id, folderFor("junk", boxes)?.id)
        },
    )

    /** [Card] for [message], or an empty one for a message nothing has been fetched for yet. */
    fun cardFor(message: Summary?): Card {
        val account = message?.let { accountOf(it) } ?: return Card()
        return cards[CardKey(account, message.id)] ?: Card()
    }

    /**
     * [cardFor], with [edit] applied and written back. The only way anything joins [cards].
     *
     * [epoch] set means this write belongs to one load. A newer load of the same card
     * has already moved the counter on, and this write is dropped.
     */
    fun updateCard(account: String, id: String, epoch: Int? = null, edit: Card.() -> Card) {
        val slot = CardKey(account, id)
        if (epoch != null && cardEpoch[slot] != epoch) return
        cards = cards + (slot to (cards[slot] ?: Card()).edit())
    }

    /**
     * The account a new message is written from. The one being replied to, when there is
     * one, so a reply in the merged inbox goes back out of the mailbox it arrived in.
     */
    fun writingAccount(): String? = accountOf(selected)?.let(::sendingKey) ?: sessions.firstOrNull()?.key

    /**
     * The address to write as, given the message being answered.
     *
     * The account's first identity for a new message, and for a reply or a forward the one
     * the message was actually addressed to. See [identityFor]. [account] is the mailbox
     * this message is being written from, captured by the caller: the default is whoever
     * is selected right now, which is only right at the moment writing starts.
     */
    fun writingIdentity(body: Body?, account: String? = writingAccount()): String {
        val mine = identities[account].orEmpty().map { it.email }
        return identityFor(body, mine, mine.firstOrNull().orEmpty())
    }

    /**
     * Opens the composer on [from], which stays fixed for as long as this message is open.
     *
     * [savedId] is the server draft this continues, or null for a message that has not been
     * saved yet. A new message must start at null. Reusing the previous message's id makes
     * the first autosave replace that draft, and the earlier message is gone.
     */
    fun write(from: String?, draft: Draft, savedId: String? = null) {
        // A reply from a shared mailbox is written from the login it came through (SharedBackend.kt).
        val account = from?.let(::sendingKey)
        val epoch = ++composeEpoch
        if (account == null) {
            composing = ComposeSession(account, draft, DraftSaves(savedId))
            return
        }
        // A signature edited in Bulwark is picked up when a composer opens.
        // Resolve it before the session is made, so refreshing the identities cannot
        // replace text after somebody has started typing.
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { session(account).jmap.identities() }.getOrNull()
            }
            if (epoch != composeEpoch) return@launch
            if (fresh != null) identities = identities + (account to fresh)
            composing = ComposeSession(account, draft, DraftSaves(savedId))
        }
    }

    /**
     * Where these messages are now, which is what undo puts them back into. In a real folder
     * that is the folder on screen. In the merged inbox it is that account's own inbox,
     * which is the only folder a message in there can have come from.
     */
    fun sourceFolder(key: String): String? =
        if (!unified()) here?.second?.id
        else folderFor("inbox", mailboxes[key].orEmpty())?.id

    /**
     * The same folder as [sourceFolder], by name rather than id.
     *
     * What [Assistant.deniedFolders] is written in, because that is what somebody typed or
     * ticked in Settings, and an id would mean nothing to them there.
     */
    fun currentFolderName(key: String): String? =
        sourceFolder(key)?.let { id -> mailboxes[key].orEmpty().firstOrNull { it.id == id }?.name }

    /**
     * Whether a message is sitting in Junk right now.
     *
     * Decided on the folder's role rather than its name, the same way everything else here
     * is, because our own server calls it "Junk Mail" and a French one calls it something
     * else again.
     */
    fun inJunk(message: Summary): Boolean {
        val key = accountOf(message) ?: return false
        val junk = folderFor("junk", mailboxes[key].orEmpty())?.id ?: return false
        return sourceFolder(key) == junk
    }

    /**
     * Whether [message]'s pictures are drawn: everywhere but Junk when [picturesOn], else a
     * sender allowed for good, from [allowedSenders], or one the reader said yes to just for
     * this message, from [shownOnce].
     */
    fun showRemoteFor(message: Summary?): Boolean =
        message != null && (
            (picturesOn && !inJunk(message)) ||
                imageSenderKey(message.fromEmail) in allowedSenders || rowToken(message) in shownOnce
            )

    /** Settings are always about one real account, never about the merged row. */
    fun settingsAccount(): String? =
        here?.first?.takeIf { it != ALL_ACCOUNTS }?.let(::sendingKey) ?: sessions.firstOrNull()?.key

    /** The account the per-account settings pages show: the one picked there, while it is still signed in. */
    fun pickedSettingsAccount(): String? =
        chosenSettingsAccount?.takeIf { k -> sessions.any { it.key == k } } ?: settingsAccount()

    /**
     * Folders a search of the whole account should leave out.
     *
     * Junk and Deleted, unless the folder on screen is that one. The merged inbox
     * is not inside either, so both stay out there.
     */
    fun searchExcept(account: String): List<String> {
        val role = if (!unified() && here?.first == account) here?.second?.role else null
        val boxes = mailboxes[account].orEmpty()
        return foldersToSkip(role, folderFor("junk", boxes)?.id, folderFor("trash", boxes)?.id)
    }

    /** One account's share of [everyInbox]. A failure is an empty share, so the others still show. */
    fun inboxOf(open: Session, memory: Map<String, List<Person>>, asked: QuickFilters): List<Summary> {
        val inbox = folderFor("inbox", mailboxes[open.key].orEmpty()) ?: return emptyList()
        return runCatching {
            if (showingResults && query.isNotBlank()) {
                val found = open.jmap.search(query, null, except = searchExcept(open.key))
                if (asked.active) {
                    val known = if (asked.knownSender) knownAddresses(open.key, memory) else emptySet()
                    found.filter { matchesQuick(it, asked, known) }
                } else {
                    found
                }
            } else {
                val known = if (asked.knownSender) knownAddresses(open.key, memory) else emptySet()
                quickPage(open.jmap, open.store, inbox.id, asked, known)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * The inbox of every signed in account, as one list.
     *
     * Every account at once. They are separate servers with nothing to wait on between
     * them, and asked in turn the merged inbox took as long as all of them added up.
     */
    suspend fun everyInbox(memory: Map<String, List<Person>>, asked: QuickFilters): List<Summary> = merged(
        coroutineScope {
            sharing.inUnified(sessions, mailboxes).map { open -> async(Dispatchers.IO) { open.key to inboxOf(open, memory, asked) } }.awaitAll()
        }.toMap(),
    )

    suspend fun <T> io(block: () -> T): T? = try {
        report("")
        withContext(Dispatchers.IO) { block() }
    } catch (e: Exception) {
        report("That did not work.", whyFailed(e))
        null
    }

    /** The message this id is, on this account, wherever the conversation or the list has it. */
    fun findSummary(account: String, id: String): Summary? =
        selected?.takeIf { it.sameMail(account, id) }
            ?: thread.firstOrNull { it.sameMail(account, id) }
            ?: emails.firstOrNull { it.sameMail(account, id) }

    /**
     * Whether an undesigned message is drawn dark.
     *
     * Computed while composing. The loader runs later, off this thread, and a theme
     * read from there is not a theme read. [pageBackground] and [pageText] are the
     * same snapshot: a hybrid's light page is decided here, and the loader must use
     * this one rather than asking again later.
     */
    val openTheme = LocalRampartTheme.current
    val darkMail = pageIsDark(messageMode, openTheme, MaterialTheme.colorScheme.surface.luminance())
    val pageBackground = plainPageBackground(openTheme)
    val pageText = plainPageText(openTheme)

    fun knownDomains(): Set<String> =
        books.values.flatten().map { domainOf(it.email) }.filter { it.isNotBlank() }.toSet()

    fun restoredBody(account: String, body: Body): Body {
        val links = runCatching { session(account).store?.trackedLinks().orEmpty() }.getOrDefault(emptyMap())
        return restoreTrackedLinks(body, Settings.trackingServer(), links)
    }

    fun publishCard(
        account: String,
        id: String,
        epoch: Int,
        body: Body,
        attachments: List<Attachment>,
        pictures: Map<String, ByteArray>,
        reading: Reading?,
        remote: Boolean,
        calendar: String?,
        emailBlobId: String?,
        loaded: Boolean,
    ) {
        val restored = restoredBody(account, body)
        updateCard(account, id, epoch) {
            copy(
                body = restored,
                bodyError = null,
                attachments = attachments,
                imageBytes = pictures,
                images = reading?.images ?: images,
                reading = reading,
                pageRemote = remote,
                calendar = calendar,
                emailBlobId = emailBlobId,
                loaded = loaded,
            )
        }
    }

    /**
     * Whether [kept] is still the message the server has.
     *
     * The same check the list uses: the account's state string. Unchanged means nothing
     * in the account moved, so this message cannot have. When the account did move, the
     * message's own blob and size answer the narrower question, and a match is still not
     * a reason to fetch the body or build the page again.
     */
    suspend fun cacheStillGood(key: String, id: String, kept: Kept): Boolean {
        // One request for both answers. They used to be asked one after the other, which was
        // a second round trip on every open after anything at all had changed in the account.
        val (state, stamp) = withContext(Dispatchers.IO) {
            runCatching { session(key).jmap.stateAndStamp(id) }.getOrNull()
        } ?: return false
        state ?: return false
        if (kept.mailState != null && state == kept.mailState) return true
        val folder = sourceFolder(key)
        val cursor = folder?.let { withContext(Dispatchers.IO) { session(key).store?.cursor(it) } }
        if (cursor != null && state == cursor) return true
        val blobId = kept.emailBlobId ?: return false
        stamp ?: return false
        if (stamp.blobId != blobId || stamp.size != kept.body.size) return false
        withContext(Dispatchers.IO) { runCatching { session(key).store?.setKeptState(id, state) } }
        return true
    }

    /**
     * Fills one card in, page and all, before it is drawn.
     *
     * A copy already stored is the first paint when it is there. It is fetched again only
     * when the account has moved and this message's own bytes have moved with it. Pictures
     * the body cites come along before that paint, when they are small enough to be worth
     * waiting for, so the engine is handed one document and loads it once.
     *
     * [force] is the click that just opened a message. A card expanded inside a thread
     * that is already loaded is left alone, which is why closing it and opening it again
     * does not fetch it a second time.
     */
    suspend fun loadCard(key: String, id: String, force: Boolean = false) {
        val slot = CardKey(key, id)
        if (!force && cards[slot]?.loaded == true) return
        // Counted before the first suspend. A second load of this card moves the
        // counter, and every write below checks it still matches.
        val epoch = (cardEpoch[slot] ?: 0) + 1
        cardEpoch[slot] = epoch
        fun live() = cardEpoch[slot] == epoch
        val remote = showRemoteFor(findSummary(key, id))
        val dark = darkMail
        val known = knownDomains()
        val pageBg = pageBackground
        val pageInk = pageText
        val kept = withContext(Dispatchers.IO) { runCatching { session(key).store?.kept(id) }.getOrNull() }
        if (!live()) return
        val showing = cards[slot]
        if (
            kept != null && showing?.reading != null && showing.pageRemote == remote &&
            showing.body == restoredBody(key, kept.body) && cacheStillGood(key, id, kept) &&
            plainPageMatches(showing.reading, pageBg, pageInk)
        ) {
            if (!live()) return
            Diagnostics.count(Metric.MESSAGE_OPEN_CACHE_HIT)
            updateCard(key, id, epoch) { copy(loaded = true, calendar = kept.calendar, emailBlobId = kept.emailBlobId) }
            if (selected?.sameMail(key, id) == true) invitation = kept.calendar?.let { invitationIn(it) }
            return
        }
        if (kept != null) {
            val reading = withContext(Dispatchers.Default) {
                prepareReading(
                    findSummary(key, id)?.fromEmail.orEmpty(),
                    findSummary(key, id)?.from.orEmpty(),
                    restoredBody(key, kept.body),
                    kept.attachments,
                    kept.pictures,
                    remote,
                    dark,
                    known,
                    pageBg,
                    pageInk,
                )
            }
            if (!live()) return
            publishCard(key, id, epoch, kept.body, kept.attachments, kept.pictures, reading, remote, kept.calendar, kept.emailBlobId, loaded = false)
            if (selected?.sameMail(key, id) == true) invitation = kept.calendar?.let { invitationIn(it) }
            if (cacheStillGood(key, id, kept)) {
                if (!live()) return
                Diagnostics.count(Metric.MESSAGE_OPEN_CACHE_HIT)
                updateCard(key, id, epoch) { copy(loaded = true) }
                return
            }
        }
        Diagnostics.time(Metric.MESSAGE_OPEN_FETCH) {
            val opened = withContext(Dispatchers.IO) { tried { session(key).jmap.open(id) } }
            if (!live()) return@time
            val fresh = opened.getOrNull()
            if (fresh == null) {
                // Only a failure when there was nothing kept. Offline, with a copy on
                // disk, is a card that opens rather than an error where it should be.
                // A fetch that failed with nothing on disk is not marked loaded, or the
                // next open would keep showing the error after the network came back.
                if (kept == null) {
                    updateCard(key, id, epoch) {
                        copy(bodyError = whyFailed(opened.exceptionOrNull() ?: Exception("That message would not open.")))
                    }
                } else {
                    updateCard(key, id, epoch) { copy(loaded = true) }
                }
                return@time
            }
            val pictures = withContext(Dispatchers.IO) { cidBytes(session(key).jmap, fresh.body, fresh.attachments) }
            val person = findSummary(key, id)
            val reading = withContext(Dispatchers.Default) {
                prepareReading(
                    person?.fromEmail.orEmpty(),
                    person?.from.orEmpty(),
                    restoredBody(key, fresh.body),
                    fresh.attachments,
                    pictures,
                    remote,
                    dark,
                    known,
                    pageBg,
                    pageInk,
                )
            }
            if (!live()) return@time
            // JMAP hands the state back with the message itself. Asking again afterwards was a
            // round trip on the path to the message appearing, spent on a stamp for the copy.
            val state = fresh.state ?: withContext(Dispatchers.IO) { runCatching { session(key).jmap.mailState() }.getOrNull() }
            if (!live()) return@time
            withContext(Dispatchers.IO) {
                runCatching {
                    session(key).store?.putKept(
                        id, fresh.body, fresh.attachments, pictures,
                        fresh.emailBlobId, state, fresh.calendar,
                    )
                }
            }
            if (!live()) return@time
            // The same document is not handed over again. A second load is the jump.
            val same = cards[slot]?.reading?.page == reading.page && reading.page != null
            if (same) {
                updateCard(key, id, epoch) {
                    copy(
                        body = restoredBody(key, fresh.body),
                        attachments = fresh.attachments,
                        imageBytes = pictures,
                        images = reading.images,
                        calendar = fresh.calendar,
                        emailBlobId = fresh.emailBlobId,
                        loaded = true,
                        bodyError = null,
                    )
                }
            } else {
                publishCard(
                    key, id, epoch, fresh.body, fresh.attachments, pictures, reading, remote,
                    fresh.calendar, fresh.emailBlobId, loaded = true,
                )
            }
            if (selected?.sameMail(key, id) == true) invitation = fresh.calendar?.let { invitationIn(it) }
        }
    }

    /** One update check shared by the half-hourly poll and the button on the About page. */
    suspend fun checkForUpdate() {
        val result = withContext(Dispatchers.IO) { Updates.newerVersion() }
        update = (result as? UpdateCheckResult.Newer)?.version
        updateCheckFailure = (result as? UpdateCheckResult.Failed)?.reason
        Diagnostics.event(Metric.UPDATE_CHECK, updateCheckCategory(result))
    }

    /*
     * The first check waits a minute, so opening the window does no update traffic, then
     * every half hour. A window left open all day would otherwise never hear about a build
     * published after it started. Windows fetches the package itself in the background;
     * this only decides when the version line can offer it.
     *
     * Whatever GitHub calls the latest release is what gets installed, however many builds
     * have gone out in between. There is no stepping through versions: the update manifest
     * names one current package and Windows fetches that.
     *
     * A development build has nothing to install over, so this loop does not run there.
     */
    LaunchedEffect(Unit) {
        if (!Updates.canUpdate()) return@LaunchedEffect
        delay(Updates.FIRST_CHECK_DELAY_MS)
        while (true) {
            if (!barState.busy) checkForUpdate()
            delay(Updates.CHECK_INTERVAL_MS)
        }
    }

    /*
     * The manifest is fetched as soon as a newer version is known, quietly, while the app
     * carries on. Opening it is what a click does, and that is when Windows App Installer
     * takes over.
     *
     * A mail client is open for days, so the moment somebody finally agrees to update is
     * the worst possible moment to begin a download.
     *
     * Once per version. A fetch that failed is not retried in a loop: the next half-hourly
     * check finds the same version, and nothing here is allowed to become a machine that
     * downloads a manifest over and over. The check itself must not say the package is
     * ready. This is what fetches it, and only a fetch that lands sets the bar to waiting.
     */
    LaunchedEffect(update) {
        if (!Updates.canUpdate()) return@LaunchedEffect
        val version = update ?: return@LaunchedEffect
        if (!Updates.shouldPrefetch(barState, version)) return@LaunchedEffect
        val staged = withContext(Dispatchers.IO) { Updates.stage() }
        Diagnostics.event(Metric.UPDATE_STAGE, stageOutcome(staged))
        if (staged) barState = UpdateBarState.Waiting(version)
    }

    /**
     * What a click on the bar means: check what is actually newest right now, fetch the
     * manifest first if that turns out not to be what is already on disk, then hand the
     * file to Windows App Installer.
     *
     * The re-check is the whole point of this function. [from] may have been staged hours
     * or days ago, and a click must never quietly install something that stopped being
     * current while it sat waiting; see [Updates.targetVersion].
     *
     * App Installer keeps its own window up and closes Rampart itself when the update
     * proceeds. This function keeps running, so a cancelled window leaves the bar
     * clickable again instead of stuck on installing.
     */
    suspend fun installLatest(from: String) {
        // Waiting is the only state that means this version is already on disk.
        val stagedVersion = (barState as? UpdateBarState.Waiting)?.version
        barState = UpdateBarState.Staging(from)
        val fresh = withContext(Dispatchers.IO) { Updates.newerVersion() }
        val target = Updates.targetVersion(from, fresh)
        if (stagedVersion != target) {
            val staged = withContext(Dispatchers.IO) { Updates.stage() }
            Diagnostics.event(Metric.UPDATE_STAGE, stageOutcome(staged))
            if (!staged) {
                barState = Updates.clickFailure(target, Updates.lastProblem)
                return
            }
        }
        barState = UpdateBarState.Installing(target)
        if (!withContext(Dispatchers.IO) { Updates.restartToUpdate() }) {
            Diagnostics.event(Metric.UPDATE_APPLY, UpdateApplyCategory.FAILED)
            barState = Updates.clickFailure(target, Updates.lastProblem)
        } else {
            // App Installer has its own window now. Rampart stays open, so a cancelled
            // window can be asked again from the same bar.
            barState = UpdateBarState.Waiting(target)
        }
    }

    LaunchedEffect(sessions.size) {
        // Asked of every new account at once, each as one request (see MailBackend.startup),
        // then applied in the accounts' own order so the first inbox shown is the same one.
        val waiting = sessions.filter { it.key !in mailboxes }
        val started = coroutineScope {
            waiting.map { open -> async { io { open.jmap.startup() } } }.awaitAll()
        }
        waiting.zip(started).forEach { (open, start) ->
            val found = start?.mailboxes ?: emptyList()
            mailboxes = mailboxes + (open.key to found)
            // An account with no identity can still read; it just cannot send, and that is
            // reported when someone tries rather than as an error on the way in.
            start?.identities?.let { identities = identities + (open.key to it) }
            if (here == null) {
                val inbox = folderFor("inbox", found) ?: found.firstOrNull()
                if (inbox != null) here = open.key to inbox
            }
            // Only once every account is in, or it would land on one account's inbox and
            // move under whoever was reading it a second later.
            if (sessions.size > 1 && sessions.all { it.key in mailboxes } && here?.first != ALL_ACCOUNTS) {
                here = ALL_ACCOUNTS to allInboxes(0)
            }
        }
        loading = false
    }
    // Shared mailboxes are read after your own, and one holding no folder you can see is
    // simply not drawn (SharedMailboxesUi.kt).
    LaunchedEffect(sharing) {
        sharing.shared.filter { it.key !in mailboxes }.forEach { open ->
            val found = withContext(Dispatchers.IO) { runCatching { open.jmap.mailboxes() } }
            found.onSuccess { mailboxes = mailboxes + (open.key to it) }
            found.onFailure { report("The mailbox shared as ${open.account.name} could not be read.", whyFailed(it)) }
        }
    }
    /*
     * Folds the senders of whatever is on screen into that account's address book.
     *
     * No extra request: these summaries were fetched to be shown, and their senders are the
     * people you actually correspond with. Written back to disk so the second run already
     * knows them.
     *
     * Learning from Sent would be better still, because who you write *to* matters more
     * than who writes to you, and it happens on its own as soon as somebody opens Sent.
     */
    fun learnFrom(seen: List<Summary>) {
        if (seen.isEmpty()) return
        val byAccount = seen.groupBy { it.account.ifBlank { here?.first.orEmpty() } }
        var changed = books
        byAccount.forEach { (key, group) ->
            if (key.isBlank() || key == ALL_ACCOUNTS) return@forEach
            var book = changed[key] ?: AddressBook.read(AddressBook.file(key))
            group.forEach { book = noted(book, it.fromEmail, it.from) }
            changed = changed + (key to book)
            runCatching { AddressBook.write(book, AddressBook.file(key)) }
        }
        books = changed
    }

    /**
     * A person's history, first page and counts. Every account at once, each asking its own
     * server and falling back to its copy (see [AccountSource]). The rows are stamped with
     * their account, as the unified inbox's are, so every row action finds the right server.
     */
    suspend fun loadPerson(person: Correspondent) {
        val epoch = ++listEpoch
        loadingMore = false
        val place = listOf("person", person.normalised)
        if (place != emailsFrom) { emails = emptyList(); emailsFrom = place }
        loading = true
        filterNote = null
        val history = PersonHistory(sessions.map { AccountSource(it.key, it.jmap, it.store, person.normalised) })
        val first = history.next()
        if (epoch != listEpoch || viewingPerson != person) return
        personHistory = history
        personStats = history.stats
        personNote = answeredNote(
            history.answered(),
            sessions.associate { it.key to shortAccountName(it.account.name, it.account.email) },
        )
        emails = first
        exhausted = history.exhausted
        loading = false
    }

    suspend fun reload() {
        val (openKey, mailbox) = here ?: return
        viewingPerson?.let { loadPerson(it); return }
        val key = openKey
        val memory = books
        val canAttach = if (key == ALL_ACCOUNTS) sessions.none { it.jmap is Imap } else session(key).jmap !is Imap
        // A toggle that cannot be answered must not stay on, or the row would say it is
        // filtering a list that was fetched without it.
        if (quick.attachment && !canAttach) quick = quick.copy(attachment = false)
        val request = ListRequest(key, mailbox.id, query, showingResults, viewingTag, quick, 0)
        val epoch = ++listEpoch
        loadingMore = false
        val folder = listOf(key, mailbox.id, viewingTag, showingResults)
        if (folder != emailsFrom) { emails = emptyList(); emailsFrom = folder }
        fun live() = epoch == listEpoch && here?.let {
            ListRequest(it.first, it.second.id, query, showingResults, viewingTag, quick, 0)
        } == request
        val asked = quick.asked(canAttach)
        val known = if (asked.knownSender && key != ALL_ACCOUNTS) {
            io { knownAddresses(key, memory) }.orEmpty()
        } else {
            emptySet()
        }
        // A saved search with conditions has its own run, in SavedSearchRun.kt: the
        // server is asked the tree where it can take one, and the local copy otherwise.
        val conditions = activeSavedSearch?.takeIf { it.condition != null }
        if (conditions != null) {
            loading = emails.isEmpty()
            val targets = if (key == ALL_ACCOUNTS) sessions else listOf(session(key))
            val run = io {
                combinedRuns(
                    targets.associate { open ->
                        val jmap = open.jmap as? Jmap
                        val found = runConditionSearch(
                            conditions.condition,
                            searchExcept(open.key),
                            jmap?.let { server ->
                                { filter: JsonObject -> server.query(filter, SAVED_SEARCH_PAGE) }
                            },
                            open.store,
                        )
                        val mine = if (asked.knownSender) knownAddresses(open.key, memory) else emptySet()
                        open.key to found.copy(
                            messages = if (asked.active) found.messages.filter { matchesQuick(it, asked, mine) }
                            else found.messages,
                        )
                    },
                ).let { if (key == ALL_ACCOUNTS) it else it.copy(messages = it.messages.map { m -> m.copy(account = "") }) }
            }
            if (!live()) return
            filterNote = run?.note
            run?.note?.let { note -> run?.detail?.let { report(note, it) } }
            emails = run?.messages ?: emails
            learnFrom(emails)
            loading = false
            exhausted = true
            return
        }
        /*
         * What is already on disk goes up first, and the server is asked afterwards.
         *
         * The spinner is only for a folder we have never seen: showing a blank pane and a
         * spinner over mail we already hold is the thing a local store exists to stop. A
         * folder read once opens instantly and corrects itself a moment later.
         *
         * A filtered list is not a picture of the folder. It is shown from the copy when
         * the copy can answer it, and it does not get to set the cursor: the next plain
         * open would otherwise skip mail the filter had hidden.
         */
        val plain = key != ALL_ACCOUNTS && !showingResults && viewingTag == null && !asked.active
        val imapLocal = key != ALL_ACCOUNTS && session(key).jmap is Imap && (asked.tagged || asked.knownSender)
        if (imapLocal) {
            loading = emails.isEmpty()
            val found = io {
                val kept = session(key).store ?: return@io null
                kept.messages(mailbox.id, filters = asked, knownSenders = known)
            }
            if (!live()) return
            filterNote = if (found == null) {
                "This folder has no saved copy, so that filter cannot be answered."
            } else {
                null
            }
            // A failed read keeps whatever is already on screen.
            emails = found ?: emails
            learnFrom(emails)
            loading = false
            exhausted = emails.size < 100
            return
        }
        filterNote = null
        // Attachment is not in the copy. Showing the copy first would flash a page the
        // toggle had not been applied to. Everything else the copy can answer is shown
        // at once, filtered the same way the server is about to be asked.
        val cached = if (key == ALL_ACCOUNTS || showingResults || viewingTag != null || asked.attachment) emptyList()
        else io { session(key).store?.messages(mailbox.id, filters = asked, knownSenders = known) }.orEmpty()
        if (!live()) return
        if (cached.isNotEmpty()) emails = cached
        /*
         * A spinner only where there is nothing to look at.
         *
         * Every refresh used to blank this pane and put a spinner over it, including the
         * refresh caused by opening a message: marking it read changes the account's state,
         * the server pushes that back, and the list reloads because a change is a change
         * whoever made it. The list came back identical a moment later, so the whole visible
         * effect was a flash.
         *
         * The unified inbox felt it worst. It keeps no local copy, so `cached` is always
         * empty there and the spinner was unconditional.
         */
        loading = cached.isEmpty() && emails.isEmpty()

        /*
         * If nothing about this account's mail has moved since this folder was last read,
         * the folder has not moved either, and the copy on screen is already right.
         *
         * The state is account-wide rather than per folder, which makes this conservative
         * in the right direction: any change anywhere costs a re-read, and no change
         * anywhere is proof that this folder is unchanged. The check itself is the cheapest
         * question the protocol has, a few hundred bytes against the several hundred
         * kilobytes of re-reading the folder.
         *
         * Only for a plain folder view. A filtered or searched list is not a faithful
         * picture of the folder, so it neither trusts the cursor nor sets one.
         */
        // Read whenever this is a plain folder view, not only when there is something
        // cached: a first read has to leave a cursor behind or the second one cannot skip.
        val state = if (plain) io { session(key).jmap.mailState() } else null
        val savedCursor = if (state != null) io { session(key).store?.cursor(mailbox.id) } else null
        if (!live()) return
        // An empty cache with a matching cursor is a folder the server has already
        // told us is empty. Skipping only when the cache holds rows would fetch that
        // folder again on every refresh.
        if (state != null && state == savedCursor) {
            loading = false
            exhausted = cached.size < 100
            return
        }
        val tagging = request.tag
        var freshPage: List<Summary>? = null
        val loadedEmails = if (tagging != null) {
            /*
             * A tag is asked of every account that has it, and the answers are put together.
             *
             * A keyword lives in one mailbox and cannot be read from another, so this is the
             * only way one list can mean one idea. Each account is caught on its own, so one
             * server being unreachable loses its share rather than the whole list, and an
             * account that has never seen the keyword is not asked at all.
             */
            // The Follow up view (FollowUpUi.kt) asks every account, since its keyword is not a tag.
            val holders = if (tagging == FOLLOW_UP) sessions.map { it.key }
                else accountsWith(tagsSeen, tagging).ifEmpty { listOf(key) }
            withContext(Dispatchers.IO) {
                holders.flatMap { account ->
                    // Stamped with its account, as the unified inbox does: ids can repeat
                    // across accounts, so an unstamped row opens or files the wrong message.
                    (runCatching { session(account).jmap.withKeyword(tagging) }.getOrNull()
                        ?: runCatching { session(account).store?.withKeyword(tagging) }.getOrNull().orEmpty())
                        .map { it.copy(account = account) }
                }
            }.sortedByDescending { it.receivedAt }
                .let { if (tagging == FOLLOW_UP) dueFollowUps(it, Instant.now()) else it }
        } else if (key == ALL_ACCOUNTS && UnifiedView.of(mailbox.id) != null) {
            // All unread, all starred, all mail (UnifiedViews.kt), from the folders chosen for each.
            val view = UnifiedView.of(mailbox.id)!!
            val sources = sharing.inUnified(sessions, mailboxes)
            val boxes = mailboxes
            val prefs = sharing.prefs
            withContext(Dispatchers.IO) { readUnified(view, sources, boxes, prefs) }
                .also { filterNote = it.note }.messages
        } else if (key == ALL_ACCOUNTS) {
            // One account failing is not the whole list failing, so each is caught inside
            // rather than out here: the others still show.
            withContext(Dispatchers.IO) { everyInbox(memory, asked) }
        } else {
            val fetched = io {
                if (request.results && request.query.isNotBlank()) {
                    val found = session(key).jmap.search(request.query, null, except = searchExcept(key))
                    if (found != null && asked.active) {
                        found.filter { matchesQuick(it, asked, known) }
                    } else {
                        found
                    }
                } else {
                    quickPage(session(key).jmap, session(key).store, mailbox.id, asked, known)
                }
            }
            if (!request.results) freshPage = fetched
            fetched
                // The server is still the authority on search, because it can see mail we
                // have never fetched. The local copy is the answer when it cannot be
                // reached, which is the difference between "no results" and "no network".
                ?: io {
                    val storeHits = session(key).store?.search(request.query)
                    if (asked.active && storeHits != null) storeHits.filter { matchesQuick(it, asked, known) }
                    else storeHits
                }.takeIf { request.results && request.query.isNotBlank() }
                // A refresh that failed is not an empty folder. Blanking the rows
                // that were already there is what a spinner over a loaded list did.
                ?: if (request.results) emptyList() else emails
        }
        if (!live()) return
        emails = loadedEmails
        loading = false
        exhausted = false
        learnFrom(emails)
        // Written back after the server has answered, so the copy is what the server
        // last said rather than what we guessed it would say. A failed fetch leaves
        // the copy alone: wiping it because the network dropped would hide mail we
        // already have.
        val page = freshPage
        if (plain && page != null) {
            io {
                val store = session(key).store ?: return@io
                // A page shorter than a full one is the whole folder, so anything the copy
                // holds beyond it is gone from the server. Pruning by date alone kept every
                // draft an autosave had replaced, since each replacement is newer than the
                // one it destroyed, and those flashed up on every refresh of Drafts.
                if (page.size < 100) {
                    store.clear(mailbox.id)
                    if (page.isNotEmpty()) store.put(mailbox.id, page)
                } else {
                    store.pruneToPage(mailbox.id, page)
                    store.put(mailbox.id, page)
                }
                // The copy matches the top of the folder this far, at this state. Scrolling
                // further reads pages from the copy while that stays true. See ListPaging.kt.
                markFirstPage(store, mailbox.id, state, page)
            }
            // The cursor is set from the state read before the fetch, never after it: mail
            // arriving between the two would otherwise be marked as already seen and the
            // next open would skip it.
            state?.let { io { session(key).store?.setCursor(mailbox.id, it) } }
        }
    }


    /*
     * The next page, asked for when the list gets near its own bottom.
     *
     * Only a single folder pages. The merged inbox is assembled from several accounts and
     * sorted here rather than by any one server, so "the next hundred" has no single
     * meaning across them; it loads its first page and stops, which is what it did before.
     *
     * A page that comes back shorter than asked for means the folder has run out, and
     * `exhausted` stops the list asking again every time somebody scrolls the last row into
     * view. Without it a short folder re-queries on every frame at the bottom.
     */
    fun loadMore() {
        // A person's history pages across every account at once. See PersonHistory.kt.
        viewingPerson?.let { person ->
            val history = personHistory ?: return
            if (loadingMore || exhausted || loading) return
            loadingMore = true
            val epoch = listEpoch
            scope.launch {
                val page = history.next()
                if (epoch != listEpoch || viewingPerson != person) return@launch
                emails = emails + page
                exhausted = history.exhausted
                loadingMore = false
            }
            return
        }
        val (key, mailbox) = here ?: return
        if (key == ALL_ACCOUNTS || showingResults || viewingTag != null || loadingMore || exhausted || loading) return
        loadingMore = true
        val memory = books
        val canAttach = session(key).jmap !is Imap
        val asked = quick.asked(canAttach)
        val offset = emails.size
        val request = ListRequest(key, mailbox.id, query, showingResults, viewingTag, quick, offset)
        val epoch = listEpoch
        scope.launch {
            Diagnostics.time(Metric.LIST_PAGE_LOAD) {
                // The plain folder is read from the copy where the copy is known to match,
                // which is no round trip at all. See ListPaging.kt for when that is.
                val plain = !asked.active
                val fromCopy = if (plain) io { pageFromCopy(session(key).store, mailbox.id, offset) } else null
                val page = fromCopy ?: io {
                    val known = if (asked.knownSender) knownAddresses(key, memory) else emptySet()
                    quickPage(
                        session(key).jmap,
                        session(key).store,
                        mailbox.id,
                        asked,
                        known,
                        from = offset,
                    ).also { fetched -> if (plain) extendCopy(session(key).store, mailbox.id, offset, fetched) }
                }.orEmpty()
                val current = here?.let {
                    ListRequest(it.first, it.second.id, query, showingResults, viewingTag, quick, emails.size)
                }
                if (epoch != listEpoch || current != request) return@time
                // Ids already on screen are dropped rather than trusted: mail arriving between
                // two pages shifts every position down, and the seam is where it shows up twice.
                val known = emails.map { it.id }.toSet()
                emails = emails + page.filterNot { it.id in known }
                exhausted = page.size < 100
                loadingMore = false
            }
        }
    }

    /**
     * Mail that arrives while Rampart is open turns up on its own.
     *
     * Still a poll, because a poll is the same shape against every server and against IMAP
     * later, and because the check itself is one small request: the inbox is only re-read on
     * the rounds where the account's mail state has actually moved. Push, below, does not
     * replace any of that. It only cuts the round short when the server says something
     * moved, so mail lands in a second or two instead of up to half a minute.
     *
     * A round that fails is skipped, not reported. The network dropping for a minute is not
     * something to put a red bar on screen for, and the next round fixes it.
     */
    /**
     * Marks arrivals in a muted conversation read and files them, without a word.
     *
     * Not through the ordinary move: that one reports failures and leaves an undo, and
     * neither belongs to something nobody asked for at the moment it happens. Archive is
     * where they go, never Trash. Mute means stop bothering me, not throw it away.
     */
    suspend fun hush(key: String, ids: List<String>) {
        val seen = withContext(Dispatchers.IO) {
            runCatching { session(key).jmap.setKeyword(ids, "\$seen", true) }
        }
        seen.exceptionOrNull()?.let { report("Muted messages could not be marked read.", whyFailed(it)) }
        val into = folderFor("archive", mailboxes[key].orEmpty())?.id ?: return
        val moved = withContext(Dispatchers.IO) { runCatching { session(key).jmap.move(ids, into) } }
        if (moved.isFailure) {
            report("Muted messages could not be archived.", whyFailed(moved.exceptionOrNull()!!))
            return
        }
        withContext(Dispatchers.IO) { runCatching { session(key).store?.forget(ids) } }
        emails = emails.filterNot { it.sameMail(key, ids.toSet()) }
    }

    /**
     * The next few messages are fetched before anybody asks for them.
     *
     * Opening a message nobody has read is one round trip, and one round trip is the whole
     * remaining wait now that the list and the open path have stopped making dozens. A body
     * already in the local store opens with none at all, and the reading pane already
     * prefers that copy, so this changes nothing about how a message is shown. It only fills
     * the store earlier.
     *
     * Worth far more here than it would be for a web client against JMAP, because on IMAP
     * what a message costs is waiting rather than bytes: fetching one nobody has clicked on
     * is almost free, and it removes the pause entirely when they do.
     *
     * **It never marks anything read.** The body is fetched with the same call the reading
     * pane uses, which does not touch the seen flag. A read ahead that quietly marked mail
     * read would destroy real information to save a moment.
     *
     * Restarted whenever the list or the selection changes, which cancels the previous run,
     * so changing folder does not leave the old folder still being read. One at a time and
     * only a screenful: a folder of thirty thousand must not read itself into the store, and
     * on IMAP every fetch takes the folder lock, so a burst of them would be in front of the
     * message somebody actually clicked.
     */
    /*
     * Deliberately not keyed on the selection.
     *
     * It was, and that made pressing j or k cancel the whole run, wait another six hundred
     * milliseconds and start again from the top of the list, so somebody moving through
     * their mail at any speed got no read ahead at all. The message being looked at is
     * skipped by reading [selected] inside the loop instead, which is the current value
     * either way and costs nothing.
     */
    LaunchedEffect(emails.firstOrNull()?.id, emails.size, here) {
        val account = here?.first ?: return@LaunchedEffect
        // A moment's grace, so the list paints and a reader who is already clicking gets the
        // connection to themselves rather than queueing behind a fetch nobody asked for.
        delay(600)
        for (row in emails.take(READ_AHEAD)) {
            if (selected?.sameMail(row) == true) continue
            // The unified inbox is rows from several accounts, so the account comes from the
            // row rather than from the folder. It used to return early here, which switched
            // the read ahead off entirely in the view most likely to be left open.
            val key = if (account == ALL_ACCOUNTS) accountOf(row) ?: continue else account
            val store = session(key).store ?: continue
            withContext(Dispatchers.IO) {
                if (store.kept(row.id) == null) {
                    runCatching {
                        val opened = session(key).jmap.open(row.id)
                        val pictures = cidBytes(session(key).jmap, opened.body, opened.attachments)
                        store.putKept(
                            row.id, opened.body, opened.attachments, pictures,
                            // Stamped with the state the open came back with, so the first
                            // click on a read-ahead message can trust it without the stamp check.
                            opened.emailBlobId, opened.state, opened.calendar,
                        )
                    }
                }
            }
            // Between messages rather than inside one, because a fetch in flight cannot be
            // taken back. This is where a reader clicking something gets in front.
            yield()
        }
    }

    val pushes = remember { Channel<Unit>(Channel.CONFLATED) }
    /**
     * The mail state our own last change produced, per account.
     *
     * A push of that same state is the echo of the change already on screen.
     * Re-reading the folder for it is what made marking a message read flash
     * the whole list.
     */
    val ownState = remember { mutableMapOf<String, String>() }

    fun noteState(key: String, applied: Applied?) {
        applied?.newState?.let { ownState[key] = it }
    }

    /**
     * Runs a change on the server and remembers the state it produced.
     *
     * Null means it was refused. The sentence is already on the error bar.
     */
    suspend fun changed(key: String, block: () -> Applied): Applied? {
        val outcome = tried { withContext(Dispatchers.IO) { block() } }
        val applied = outcome.getOrNull()
        if (applied == null) {
            outcome.exceptionOrNull()?.let { report("The server would not do that.", whyFailed(it)) }
            return null
        }
        noteState(key, applied)
        return applied
    }

    /**
     * Flips seen on the rows this account owns.
     *
     * [onlyIf] set means a later edit has already moved the row, and this one
     * leaves it. That is how a failed mark-read puts the row back without
     * undoing a click the reader made while the request was in flight.
     */
    fun paintSeen(account: String, ids: Set<String>, read: Boolean, onlyIf: Boolean? = null) {
        fun paint(row: Summary) =
            if (!row.sameMail(account, ids) || (onlyIf != null && row.seen != onlyIf)) row else row.copy(seen = read)
        emails = emails.map(::paint)
        thread = thread.map(::paint)
        selected?.let { if (it.sameMail(account, ids) && (onlyIf == null || it.seen == onlyIf)) selected = it.copy(seen = read) }
        // And the conversations behind collapsed rows, so a row whose unread comes from
        // another message in it changes with the click. See [ThreadGists].
        ThreadGists.paint(account, ids, seen = read)
    }

    /** Writes the rows just marked back to each account's own folder. */
    suspend fun rememberSeen(account: String, ids: Set<String>) {
        val changedRows = emails.filter { it.sameMail(account, ids) }
        val writes = seenCacheWrites(
            unified = unified(),
            account = account,
            folderId = here?.second?.id?.takeIf { it != ALL_ACCOUNTS },
            changed = changedRows,
            inboxId = { who -> folderFor("inbox", mailboxes[who].orEmpty())?.id },
        )
        writes.forEach { write ->
            val rows = changedRows.filter { it.sameMail(write.account, write.ids.toSet()) }
            if (rows.isEmpty()) return@forEach
            withContext(Dispatchers.IO) { runCatching { session(write.account).store?.put(write.mailbox, rows) } }
        }
    }
    LaunchedEffect(sessions) {
        val states = mutableMapOf<String, String>()
        val seen = mutableMapOf<String, Set<String>>()
        while (true) {
            for (open in sessions) {
                val inbox = folderFor("inbox", mailboxes[open.key].orEmpty()) ?: continue
                // The page and the folder counts come back together: one request once the
                // state has moved, where it used to be two.
                var counts: List<Mailbox>? = null
                val found = withContext(Dispatchers.IO) {
                    runCatching {
                        val state = open.jmap.mailState() ?: return@runCatching null
                        if (state == states[open.key]) null
                        else {
                            val (page, boxes) = open.jmap.pageAndFolders(inbox.id, 30)
                            counts = boxes
                            arrivals(state, page, seen[open.key])
                        }
                    }.getOrNull()
                } ?: continue

                states[open.key] = found.state
                seen[open.key] = found.summaries.map { it.id }.toSet()
                // The unread counts in the sidebar move when mail arrives and when it is
                // read in another client, so they are refreshed on any change, not just on
                // an arrival.
                counts?.let { mailboxes = mailboxes + (open.key to it) }
                // Anything this account changed can be in the folder on screen, not only in
                // its inbox: mail read or filed in another client moves the open folder too.
                // A state we just produced ourselves, with nothing new in the inbox, is
                // the echo of that action. The list already shows it.
                val echoed = found.fresh.isEmpty() && found.state == ownState[open.key]
                if ((here?.first == open.key || unified()) && !showingResults && !echoed) reload()
                /*
                 * A conversation muted on this computer only is quietened here, on the way in.
                 *
                 * The fallback for accounts without Sieve, whose mute cannot be a rule on the
                 * server (see [ServerMute]), so it applies when Rampart next sees the
                 * message rather than at delivery. Quietly, and never reported: a failed
                 * hush is the message staying in the inbox, which is the state it was
                 * already in.
                 */
                val hushed = found.fresh.filter { Muted.muted(open.key, it.threadId) }
                if (hushed.isNotEmpty()) hush(open.key, hushed.map { it.id })
                val announce = found.fresh.filterNot { it in hushed }
                if (notifyOnArrival) NewMailNotices.arrived(open.key, announce)
                if (Settings.ntfyImportantMail()) {
                    announce.filter { "\$important" in it.keywords }.forEach {
                        phoneAlert("Important new mail", "${it.from}: ${it.subject.ifBlank { "(no subject)" }}")
                    }
                }
                if (Settings.ntfyBounce()) {
                    announce.filter {
                        val sender = it.fromEmail.lowercase()
                        "mailer-daemon" in sender || "postmaster" in sender || "bounce" in sender
                    }.forEach { phoneAlert("Message bounced", it.subject.ifBlank { "A delivery failed." }) }
                }
            }
            // Until every account has been looked at once there is nothing to compare
            // against, so those first rounds come quickly rather than half a minute apart.
            val quiet = if (states.size == sessions.size) 30_000L else 5_000L
            val woke = withTimeoutOrNull(quiet) { pushes.receive() }
            if (woke != null) {
                // A burst of pushes, including the echo of something just done here,
                // is one pass. The pass reads the current state, so waiting does not
                // drop a change, it only stops reading the folder once per push.
                delay(400)
                while (pushes.tryReceive().isSuccess) Unit
            }
        }
    }

    /**
     * The server's own word that something moved, which saves waiting for the next round.
     *
     * Conflated on purpose: ten changes in a second are one round of work, and the round
     * reads the current state rather than a list of what happened, so nothing is lost by
     * dropping the extras. A socket that closes is reopened after a pause; while it is
     * down the poll above carries on by itself, which is why none of this reports an error.
     */
    LaunchedEffect(sessions) {
        if (sessions.none { it.jmap.hasPush }) return@LaunchedEffect
        val gone = Channel<Unit>(Channel.CONFLATED)
        while (true) {
            val open = mutableListOf<AutoCloseable>()
            try {
                for (account in sessions) {
                    withContext(Dispatchers.IO) {
                        account.jmap.watch({ pushes.trySend(Unit) }, { gone.trySend(Unit) })
                    }?.let { open += it }
                }
                gone.receive()
            } finally {
                open.forEach { runCatching { it.close() } }
            }
            // A laptop that has just woken up, or a server being restarted, would otherwise
            // be reconnected to in a tight loop. Nothing is missed by waiting: the poll is
            // still running underneath, and the next round reads the state from scratch.
            delay(30_000)
        }
    }

    /** Folder counts and the open folder, brought up to date now rather than at the next poll. */
    suspend fun refreshNow() {
        val key = here?.first ?: return
        val touched = if (key == ALL_ACCOUNTS) sessions.map { it.key } else listOf(key)
        touched.forEach { one ->
            withContext(Dispatchers.IO) { runCatching { session(one).jmap.mailboxes() }.getOrNull() }
                ?.let { mailboxes = mailboxes + (one to it) }
        }
        reload()
    }

    /*
     * Fetched when the pane opens and once on the first signed-in account, because the
     * second reason for having them is autocomplete, which has to know before anyone asks
     * for the list. All of them in one call: ContactCard/query is not implemented in
     * Stalwart 0.16 and answers serverUnavailable in every form, so there is no paging to
     * do and nothing to search server side.
     */
    /*
     * Counted when the dashboard is open, and when no message is selected, because
     * that page lists what is waiting on an answer. Again whenever the list underneath
     * has moved.
     *
     * Not on a timer and not in the background: it is a handful of queries over a local
     * file, so it is cheap when somebody is looking at it and pointless when nobody is.
     */
    LaunchedEffect(dashboardOpen, selected == null, emails, here) {
        if (!dashboardOpen && selected != null) return@LaunchedEffect
        val key = here?.first?.takeIf { it != ALL_ACCOUNTS } ?: sessions.firstOrNull()?.key
            ?: return@LaunchedEffect
        val boxes = mailboxes[key].orEmpty()
        val inbox = folderFor("inbox", boxes)?.id ?: return@LaunchedEffect
        val mine = identities[key].orEmpty().map { it.email }.toSet()
        stats = withContext(Dispatchers.IO) {
            runCatching {
                session(key).store?.stats(
                    inbox = inbox,
                    sent = listOfNotNull(folderFor("sent", boxes)?.id),
                    junk = listOfNotNull(folderFor("junk", boxes)?.id),
                    mine = mine,
                )
            }.getOrNull()
        }
    }

    /*
     * What is left of today, for the empty reading pane. The same read the calendar
     * panel makes. Only while nothing is open: it asks the server, and a message on
     * screen does not need it. No calendar, or a read that fails, leaves the list
     * empty so the page shows the date and the buttons without a line that never ends.
     */
    val glanceAccount = here?.first?.takeIf { it != ALL_ACCOUNTS } ?: sessions.firstOrNull()?.key
    // Reset with the account, so one account's events are not shown as another's
    // while the next read is on its way.
    var stillToday by remember(glanceAccount) { mutableStateOf<List<Occurrence>>(emptyList()) }
    LaunchedEffect(selected == null, glanceAccount) {
        if (selected != null) return@LaunchedEffect
        val backend = glanceAccount?.let { account -> sessions.firstOrNull { it.key == account }?.jmap }
        val client = eventCalendarFor(backend)
        if (client == null) {
            stillToday = emptyList()
            return@LaunchedEffect
        }
        val viewer = Regional.zone()
        stillToday = withContext(Dispatchers.IO) {
            runCatching { readSideAgenda(client, viewer).today }.getOrElse { emptyList() }
        }
    }

    // Files is a page like Contacts, and closes and opens against the others the same way.
    FilesFollows(
        backends = sessions.map { it.jmap },
        othersOpen = settingsOpen || contactsOpen || dashboardOpen || calendarOpen,
        place = listOf(here?.first, here?.second?.id, viewingTag, showingResults),
    ) { settingsOpen = false; contactsOpen = false; dashboardOpen = false; calendarOpen = false }
    // Settings another computer changed, redrawn the way Rook's confirmed cards are.
    SettingsSyncFollows(sessions) { changed ->
        if (
            "settings.theme" in changed || "settings.customThemes" in changed || "settings.dark" in changed ||
            "settings.fontSize" in changed || "settings.animations" in changed
        ) {
            onAppearance()
        }
        if ("settings.iconPack" in changed) onIcons(iconPack(Settings.iconPack()))
        if ("settings.sidebarIcons" in changed) sidebarIcons = SidebarIcons.of(Settings.sidebarIcons())
        if ("settings.loader" in changed) loader = Loader.of(Settings.loader())
        if ("settings.density" in changed) density = Density.of(Settings.density())
        if ("settings.tintRowsByTag" in changed) tintRows = Settings.tintRowsByTag()
        if ("settings.tagColours" in changed) tagColours = Settings.tagColours()
        if ("settings.hoverActions" in changed) HoverChoice.reload()
        if ("settings.tintRowsByAccount" in changed || "settings.accountColours" in changed) AccountTintState.reload()
        if ("settings.undoBarSeconds" in changed) undoBarSeconds = Settings.undoBarSeconds()
        if ("settings.order" in changed) order = Settings.order()
        if ("settings.orderAppliesToAll" in changed) orderAll = Settings.orderAppliesToAll()
        if ("settings.messageMode" in changed) messageMode = Settings.messageMode()
        if ("settings.messageScale" in changed) messageScale = Settings.messageScale()
        if ("settings.mailLayout" in changed) MailLayoutState.reload()
        if ("settings.savedSearches" in changed) savedSearches = Settings.savedSearches()
        if ("settings.focusedInbox" in changed) focusedInbox = Settings.focusedInbox()
        if ("settings.focusOverrides" in changed) focusOverrides = Settings.focusOverrides()
        if ("settings.senderPictures" in changed || "settings.senderPicturesInJunk" in changed ||
            "settings.messagePictures" in changed
        ) {
            SenderPictureSignals.bump()
        }
    }
    CalendarJumpFollows(calendarOpen) {
        calendarOpen = true; settingsOpen = false; contactsOpen = false; dashboardOpen = false
    }
    // An event Rook proposed, as a card waiting for Save. See CalendarFromMailUi.kt.
    ProposedEventDialog()
    // The same for a task, and whether any account can keep one. See TasksUi.kt.
    ProposedTaskDialog()
    TasksFollow(sessions.map { it.jmap })

    // The page or the panel beside the mail: either one showing is a reason to read again.
    val contactsShowing = contactsOpen || AppBar.showing(SideTool.CONTACTS)
    LaunchedEffect(contactsShowing, sessions.size) {
        val key = writingAccount() ?: return@LaunchedEffect
        if (contacts.isNotEmpty() && !contactsShowing) return@LaunchedEffect
        if (!session(key).jmap.hasContacts()) return@LaunchedEffect
        contactsLoading = true
        contactsError = null
        try {
            withContext(Dispatchers.IO) {
                // Both in one trip. The books are what name and filter the list, so
                // fetching them separately would draw the list once without them.
                val jmap = session(key).jmap
                val (books, fetched) = jmap.booksAndContacts()
                contactBooks = books
                val photos = photosFrom(jmap, fetched)
                contacts = fetched
                senderPhotos = photos
            }
        } catch (e: Exception) {
            // Never fatal. The address book built from mail is the one that has to work.
            contactsError = whyFailed(e)
        } finally {
            contactsLoading = false
        }
    }

    LaunchedEffect(settingsOpen, chosenSettingsAccount, here) {
        val key = pickedSettingsAccount()
        if (!settingsOpen || key == null) return@LaunchedEffect
        vacationError = null
        vacation = withContext(Dispatchers.IO) { runCatching { session(key).jmap.vacation() }.getOrNull() }
    }

    /*
     * How full each mailbox is, asked for only while the page that shows it is open.
     *
     * Every account rather than the one in front, because the Accounts page lists them all
     * and a bar under one of three would look like the other two had failed. Each is caught
     * on its own so one server that will not answer does not blank the others.
     */
    LaunchedEffect(settingsOpen, sessions) {
        if (!settingsOpen) return@LaunchedEffect
        quotas = withContext(Dispatchers.IO) {
            sessions.associate { open ->
                open.key to runCatching { open.jmap.quota() }.getOrDefault(emptyList())
            }
        }
    }

    /*
     * The account's tags, re-read whenever its local copy might have gained one.
     *
     * Keyed on the list rather than on a timer, because the only way a tag appears is a
     * message carrying it arriving in the copy, and that is exactly when this changes.
     */
    LaunchedEffect(emails, sessions) {
        val onScreen = emails.groupBy { accountOf(it) }.mapValues { (_, list) -> list.flatMap { it.keywords } }
        val memory = books
        tagsSeen = withContext(Dispatchers.IO) {
            sessions.associate { open ->
                // The list that is open as well as the copy on disk, so tags exist on the
                // first run and on a machine where there is nowhere safe to keep a store.
                val kept = runCatching { open.store?.keywordCounts() }.getOrNull().orEmpty()
                // Only tags the store has never heard of are counted from the screen. Adding
                // the two would count every message in the open folder twice, and the count
                // beside a tag is worth less than nothing if it is wrong.
                val unseen = onScreen[open.key].orEmpty().filterNot { it in kept }
                open.key to (kept + unseen.groupingBy { it }.eachCount())
            }
        }
    }

    // Its own effect, and after a pause, because each count is a search of the local store:
    // a burst of arriving mail restarts the wait instead of running every search each time.
    LaunchedEffect(emails, sessions, savedSearches) {
        if (savedSearches.isEmpty()) return@LaunchedEffect
        delay(2000)
        val memory = books
        // Searches with conditions are counted in SavedSearchRun.kt, and their split
        // children come from the same pass, so a child appears or goes with the count.
        val advanced = withContext(Dispatchers.IO) {
            savedSearches.filter { it.condition != null }.associateWith { search ->
                val targets = if (search.account == ALL_ACCOUNTS) sessions else sessions.filter { it.key == search.account }
                combinedCounts(
                    targets.map { open ->
                        countConditionSearch(search, searchExcept(open.key), open.store, knownAddresses(open.key, memory))
                    },
                    search.split,
                )
            }
        }
        savedSearchGroups = advanced.entries.associate { (search, count) -> search.id to count.groups }
        val advancedCounts = advanced.entries.flatMap { (search, count) ->
            listOf(search.id to count.unread) + count.groups.map { childSearch(search, it).id to it.unread }
        }.toMap()
        savedSearchCounts = advancedCounts + withContext(Dispatchers.IO) {
            savedSearches.filter { it.condition == null }.associate { search ->
                val count = if (search.account == ALL_ACCOUNTS) {
                    sessions.sumOf { session ->
                        val store = session.store
                        val known = knownAddresses(session.key, memory)
                        val inbox = folderFor("inbox", mailboxes[session.key].orEmpty())
                        if (store == null || inbox == null) 0 else {
                            countUnreadSavedSearch(search, store, inbox.id, known)
                        }
                    }
                } else {
                    val session = sessions.firstOrNull { it.key == search.account }
                    val store = session?.store
                    val known = knownAddresses(search.account, memory)
                    val inbox = folderFor("inbox", mailboxes[search.account].orEmpty())
                    if (store == null || inbox == null) 0 else {
                        countUnreadSavedSearch(search, store, inbox.id, known)
                    }
                }
                search.id to count
            }
        }
    }

    /** Puts a tag on a message, wherever the message is, and remembers it locally. */
    fun tagMessage(message: Summary, keyword: String) {
        val key = accountOf(message) ?: return
        scope.launch {
            if (keyword in message.keywords) return@launch
            val keywords = message.keywords + keyword
            emails = emails.map { if (it.sameMail(message)) it.copy(keywords = keywords) else it }
            if (selected?.sameMail(message) == true) selected = selected?.copy(keywords = keywords)
            if (changed(key) { session(key).jmap.setKeyword(listOf(message.id), keyword, true) } == null) {
                emails = emails.map { if (it.sameMail(message)) it.copy(keywords = message.keywords) else it }
                if (selected?.sameMail(message) == true) selected = selected?.copy(keywords = message.keywords)
            }
        }
    }

    /**
     * Answers an invitation.
     *
     * Its own send rather than the composer's, deliberately. There is no undo window,
     * because the answer is one button and there is nothing written to regret; nothing is
     * saved in Drafts, because a reply that is entirely a calendar part is not something
     * anybody wants to find there; and the answer is not shown for review first, because
     * a mail client that makes you approve pressing Accept has not saved you from
     * anything.
     */
    fun answerInvitation(answer: Rsvp) {
        val meeting = invitation ?: return
        val message = selected ?: return
        val key = accountOf(message) ?: return
        val identity = identities[key].orEmpty().let { mine ->
            mine.firstOrNull { it.email.equals(writingIdentity(cardFor(message).body), ignoreCase = true) } ?: mine.firstOrNull()
        } ?: return
        val boxes = mailboxes[key].orEmpty()
        val drafts = folderFor("drafts", boxes) ?: return
        answering = answer
        val addresses = identities[key].orEmpty().map { it.email } + identity.email
        val calendarBlob = calendarPartIn(cardFor(message).attachments)?.blobId
        scope.launch {
            // Through the account's calendar first, where its server keeps one, and by email
            // whenever the server did not send the reply itself. InvitationCalendar.kt decides.
            val onServer = withContext(Dispatchers.IO) {
                runCatching {
                    answerInCalendar(calendarClientFor(session(key).jmap), meeting, answer, addresses, calendarBlob)
                }.getOrElse { CalendarAnswer(false, "Your calendar could not be reached, so the answer went by email.", whyFailed(it)) }
            }
            val sent: Result<String?> = if (onServer.replySent) Result.success(null) else withContext(Dispatchers.IO) {
                runCatching {
                    val ics = rsvpCalendar(meeting, answer, identity.email, identity.name)
                    val file = Files.createTempFile("rampart-rsvp", ".ics")
                    try {
                        Files.writeString(file, ics)
                        val part = session(key).jmap.upload(file)
                        val draft = rsvpDraft(meeting, answer, message, cardFor(message).body, identity.email)
                            .copy(attachments = listOf(part.copy(name = rsvpFileName(answer))))
                        session(key).jmap.send(draft, identity, drafts.id, folderFor("sent", boxes)?.id)
                    } finally {
                        Files.deleteIfExists(file)
                    }
                }
            }
            answering = null
            if (sent.isSuccess) {
                sent.getOrNull()?.let { if (error.isBlank()) report(it) }
                onServer.note?.let { report(it, onServer.detail) }
                // Shown as answered straight away. The organiser's copy is what counts and
                // it has gone; re-reading our own part would say nothing new.
                invitation = meeting.copy(
                    attendees = meeting.attendees.map {
                        if (it.email.equals(identity.email, ignoreCase = true)) it.copy(status = answer.partstat) else it
                    },
                )
            } else {
                report("The answer could not be sent.")
            }
        }
    }

    /**
     * Opens a tag: the same list pane, showing everything carrying that keyword.
     *
     * Across every account that has it, because a tag is one idea even when it lives in two
     * mailboxes. An account that has never seen the keyword is not asked.
     */
    /**
     * Opens a person's history in the list pane: the address clicked, and every other
     * address the server's address book has on the same card, because a person who writes
     * from work and from home is one person.
     */
    fun openPerson(name: String, address: String) {
        val clicked = address.trim().lowercase()
        if (clicked.isEmpty()) return
        val card = contacts.map { it.first }.firstOrNull { card -> card.emails.any { it.trim().lowercase() == clicked } }
        val person = Correspondent(
            name = card?.label?.ifBlank { null } ?: name.ifBlank { clicked },
            addresses = listOf(clicked) + card?.emails.orEmpty(),
        )
        settingsOpen = false
        contactsOpen = false
        dashboardOpen = false
        calendarOpen = false
        selected = null
        query = ""
        showingResults = false
        activeSavedSearch = null
        viewingTag = null
        personStats = PersonStats()
        personNote = null
        personHistory = null
        viewingPerson = person
        // here has not changed, so nothing else will start the load.
        scope.launch { reload() }
    }

    fun openTag(keyword: String) {
        selected = null
        query = ""
        showingResults = false
        activeSavedSearch = null
        viewingPerson = null
        viewingTag = keyword
        // here has not changed, so nothing else will start the load.
        scope.launch { reload() }
    }

    /**
     * Opens a saved search query folder.
     *
     * Runs the query and quick filters through the search path.
     */
    fun openSavedSearch(search: SavedSearch) {
        settingsOpen = false
        contactsOpen = false
        dashboardOpen = false
        calendarOpen = false
        viewingTag = null
        viewingPerson = null
        activeSavedSearch = search
        query = search.query
        quick = search.filters
        showingResults = true
        selected = null
        if (search.account == ALL_ACCOUNTS) {
            here = ALL_ACCOUNTS to allInboxes(0)
        } else {
            val accountMailboxes = mailboxes[search.account].orEmpty()
            val inbox = folderFor("inbox", accountMailboxes) ?: accountMailboxes.firstOrNull()
            if (inbox != null) {
                here = search.account to inbox
            }
        }
        scope.launch { reload() }
    }

    // Attachments kept for offline use (OfflineAttachmentsUi.kt), in the background.
    LaunchedEffect(sessions) {
        OfflineAttachments.run { offlineAccounts(sessions) { k -> folderFor("inbox", mailboxes[k].orEmpty())?.id } }
    }

    // New mail is announced a burst at a time, checked again just before it is shown.
    LaunchedEffect(sessions) {
        NewMailNotices.run { key ->
            val open = sessions.firstOrNull { it.key == key } ?: return@run null
            val inbox = folderFor("inbox", mailboxes[key].orEmpty()) ?: return@run null
            withContext(Dispatchers.IO) { runCatching { open.jmap.emails(inbox.id, limit = 30) }.getOrNull() }
        }
    }
    // A click on a notification opens that message, in its account's inbox.
    LaunchedEffect(Unit) {
        for (ref in NewMailNotices.opens) {
            val inbox = folderFor("inbox", mailboxes[ref.account].orEmpty()) ?: continue
            settingsOpen = false
            contactsOpen = false
            dashboardOpen = false
            calendarOpen = false
            activeSavedSearch = null
            val alreadyThere = here?.first == ALL_ACCOUNTS || (here?.first == ref.account && here?.second?.id == inbox.id)
            if (!alreadyThere) {
                here = ref.account to inbox
                // Changing folder clears the selection, so the message is chosen once the
                // new list has it, or after a few seconds regardless.
                withTimeoutOrNull(5_000) {
                    snapshotFlow { emails }.first { list -> list.any { it.sameMail(ref.account, ref.summary.id) } }
                }
            }
            val opened = emails.firstOrNull { it.sameMail(ref.account, ref.summary.id) }
                ?: ref.summary.copy(account = ref.account)
            selected = opened
            // A notification is an open, the same as choosing the row. In the focused
            // layout that covers the list. Next and previous do not, and this is not those.
            focusedNav = focusedNav.opened(rowToken(opened))
        }
    }

    LaunchedEffect(here) {
        here ?: return@LaunchedEffect
        selected = null
        if (activeSavedSearch == null) {
            query = ""
            showingResults = false
            viewingTag = null
        }
        viewingPerson = null
        reload()
    }
    // What the list needs to collapse its rows: each account's joins, and the conversation
    // behind each row that stands for more than one message. See ThreadRowsState.kt. Not
    // for a search, whose rows are drawn as they matched.
    LaunchedEffect(emails, showingResults, activeSavedSearch, viewingTag) {
        sessions.forEach { open ->
            Rethreads.load(open.key) { open.store }?.let {
                report("The conversations joined on this computer could not be read.", it)
            }
        }
        if (showingResults || activeSavedSearch != null || viewingTag != null) return@LaunchedEffect
        ThreadGists.fill(emails, { accountOf(it) }) { key -> sessions.firstOrNull { it.key == key }?.jmap }?.let {
            report("Rampart could not read the rest of these conversations, so each row shows only its own message.", whyFailed(it))
        }
    }
    // Whether the server holds a mute for this conversation. Its own effect, so the first
    // read of an account's filters does not hold up the rest of opening the message.
    LaunchedEffect(selected) {
        val message = selected ?: return@LaunchedEffect
        val key = accountOf(message) ?: return@LaunchedEffect
        val onServer = withContext(Dispatchers.IO) {
            runCatching { ServerMute.muted(session(key).jmap, key, message.threadId) }
        }
        onServer.exceptionOrNull()?.let {
            report("Rampart could not read this account's filters to see if the conversation is muted.", whyFailed(it))
        }
        if (onServer.getOrDefault(false)) conversationMuted = true
    }
    // selectedForMenu is a key so a left-click on a draft that a right-click already
    // selected still opens it. Rethreads.changes is one so a join or a split made while the
    // conversation is open redraws it.
    LaunchedEffect(selected, selectedForMenu, Rethreads.changes) {
        val message = selected ?: return@LaunchedEffect
        val key = accountOf(message) ?: return@LaunchedEffect
        paperMessages = emptySet()
        invitation = null
        answering = null
        // Read here rather than during composition: it comes off disk, and a file read on
        // every recomposition of the reading pane is a file read on every keystroke.
        conversationMuted = Muted.muted(key, message.threadId)
        // Cleared only when this is a different conversation, so expanding or collapsing a
        // card in the same thread never throws away what another card already fetched, and
        // never re-decides which cards were open to begin with.
        var fetchedThread: Deferred<Result<List<Summary>>>? = null
        /*
         * Whatever was selected is open, always, even when the conversation is already the
         * one on screen.
         *
         * Moving with j and k to another message in the same thread takes the branch below
         * and therefore never touched [expanded], so the message just selected rendered as
         * a collapsed one line row: selected, scrolled to, and with no way to read it. A
         * selection is a request to read that message, so it opens, and the cards already
         * open stay open because this adds rather than replaces.
         */
        // Click to on-screen, the number that answers "why did opening that one feel
        // slow": the card's own fetch, the thread it belongs to when that is not already
        // known, and the meeting invitation when there is one. Not the mark-as-read pause
        // or the draft hand-off below, neither of which is what "loading" means to whoever
        // is waiting on it.
        Diagnostics.time(Metric.MESSAGE_OPEN_TOTAL) {
            expanded = expanded + message.id
            if (thread.none { it.id == message.id }) {
                thread = emptyList()
                // A summary, a pending packet or an error belongs to the conversation it was
                // made for. Left in place across a switch, it would be shown as if it were an
                // answer about whatever is open now.
                summariseError = null
                summarisePacket = null
                summariseFor = null
                cards = emptyMap()
                // The load counters go with the cards they guard. Kept, they gained one
                // entry for every message opened in the session and never lost any; a load
                // still in flight for the old conversation now finds no counter and stops,
                // which is what should happen to it anyway.
                cardEpoch.clear()
                openedByHand = emptySet()
                filed = emptySet()
                // The clicked message opens at once, before the rest of the thread is even
                // known. See initialExpanded below for what joins it once the thread answers.
                expanded = setOf(message.id)
                /*
                 * The thread goes out alongside the click's own card rather than after it: they
                 * do not depend on each other and each is a round trip, so asking in turn made
                 * opening a message cost two of them end to end before the rest of the
                 * conversation was even known to be there.
                 */
                // After any joins and splits made on this computer, which with none made is
                // exactly the server's thread. See [rethreadedThread].
                val re = Rethreads.of(key)
                val known = if (re.empty) emptyList() else emails.filter { accountOf(it) == key }
                fetchedThread = async(Dispatchers.IO) { tried { rethreadedThread(session(key).jmap, message, re, known) } }
            }
            // A stored copy that still matches the server opens with no further fetch.
            // loadCard is what decides, and it builds the page before the card is shown.
            loadCard(key, message.id, force = true)
            // Off the path to the conversation appearing: it is a store write and, for a
            // tracked message, a call to the companion, and nothing on screen waits for it.
            cardFor(message).body?.let { opened -> scope.launch {
                val answered = opened.inReplyTo + opened.references
                if (answered.isNotEmpty()) {
                    val ids = withContext(Dispatchers.IO) {
                        session(key).store?.markReplied(message.fromEmail, answered, Instant.now()).orEmpty()
                    }
                    if (ids.isNotEmpty()) {
                        val base = Settings.trackingServer()
                        val token = withContext(Dispatchers.IO) { Secrets.trackingToken() }
                        if (base.isNotBlank() && !token.isNullOrBlank()) withContext(Dispatchers.IO) {
                            ids.forEach { runCatching { TrackingClient.stopAlerts(base, token, it) } }
                        }
                    }
                }
            } }

            /*
             * The meeting, when the open did not already bring it back.
             *
             * A small invitation is part of that open. This is the remainder: a backend
             * that cannot put it on the same request, and only when the part is small.
             */
            if (invitation == null) {
                val card = cardFor(message)
                val parsed = card.calendar?.let { invitationIn(it) }
                invitation = parsed ?: card.attachments.firstOrNull {
                    it.type.substringBefore(';').equals("text/calendar", ignoreCase = true) &&
                        it.size in 1..CHEAP_CALENDAR
                }?.let { part ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            session(key).jmap.blob(part, CHEAP_CALENDAR)?.let { invitationIn(String(it, Charsets.UTF_8)) }
                        }.getOrNull()
                    }
                }
            }

            fetchedThread?.let {
                val loaded = it.await().getOrNull().orEmpty()
                    // A thread comes from one account: JMAP does not thread across servers, so
                    // every message in it shares the click's own key. Stamped here because
                    // Email/get does not know about the merged inbox, and a card whose account
                    // cannot be worked out cannot reply as, file, or archive anything.
                    .map { row -> if (row.account.isBlank()) row.copy(account = key) else row }
                // Filtered by anything filed while this was in flight, or the answer, which was
                // assembled before the move, would put it back on screen.
                val standing = loaded.filterNot { it.id in filed }
                thread = standing
                expanded = initialExpanded(message)
                // Only now, because scrolling to where this card is about to land means knowing
                // the whole thread first: asked for before the earlier messages that push it
                // down the page were known about, it would scroll to where the card used to be.
                pendingScrollTo = message.id
            }
        }

        // A draft is not something to read. Clicking one puts it back in the composer,
        // under the id it is already saved at, so carrying on writing replaces that copy
        // instead of leaving the old one behind. The from address is the one the draft
        // was saved with. The account's first identity is only the stand-in when the
        // draft itself names nobody.
        if (here?.second?.role == "drafts" && !showingResults && !selectedForMenu) {
            sendError = null; sendDetail = null
            val from = message.fromEmail.ifBlank {
                identities[key].orEmpty().firstOrNull()?.email.orEmpty()
            }
            val card = cardFor(message)
            // The card's own list when this open already fetched it. Otherwise ask now,
            // so a draft is not opened with its files missing and then saved that way.
            if (!card.loaded || card.body == null) {
                report("That draft could not be opened.", card.bodyError)
                return@LaunchedEffect
            }
            write(key, draftOf(message, card.body, from, card.attachments), message.id)
            return@LaunchedEffect
        }

        if (!message.seen) {
            /*
             * The pause, when there is one, comes out of this effect rather than a timer:
             * moving to another message cancels it, so a message you passed through on the
             * way down the list is never marked.
             *
             * That is the whole reason to want a delay. Arrow-keying down an inbox with no
             * pause marks everything you go past as read, which is how a morning's unread
             * mail disappears while somebody is looking for one message in it.
             */
            val wait = Settings.markReadDelay()
            // Negative means never on its own, so the row menu is the only way. Checked
            // before the delay, not after, or "never" would be "after a pause".
            if (wait < 0) return@LaunchedEffect
            if (wait > 0) delay(wait)
            // The row changes now. The server is told after, and a refusal puts the
            // row back. Waiting for the server first is what left it bold for the
            // whole round trip.
            if (selected?.sameMail(message) != true || message.id in keptUnread) return@LaunchedEffect
            paintSeen(key, setOf(message.id), true)
            scope.launch {
                if (changed(key) { session(key).jmap.markSeen(message.id) } == null) {
                    paintSeen(key, setOf(message.id), false, onlyIf = true)
                } else {
                    rememberSeen(key, setOf(message.id))
                }
            }
        }
    }

    /**
     * A card the reader opened themselves is marked read, after the usual pause.
     *
     * An effect rather than a coroutine launched from the press, so that collapsing the
     * card, opening a different conversation or closing the pane cancels the wait the way
     * Compose cancels everything else. Launched from the screen's own scope it would carry
     * on regardless: a card opened and immediately closed again still got marked, and two
     * quick presses sent two requests for the same message.
     */
    LaunchedEffect(selected?.id) {
        keptUnread = keptUnread.filter { it == selected?.id }.toSet()
    }

    LaunchedEffect(openedByHand, thread, keptUnread) {
        val wait = Settings.markReadDelay()
        // Negative means never on its own, the same as everywhere else it is read.
        if (wait < 0) return@LaunchedEffect
        val waiting = thread.filter { it.id in openedByHand && it.id in expanded && !it.seen && it.id !in keptUnread }
        if (waiting.isEmpty()) return@LaunchedEffect
        if (wait > 0) delay(wait)
        val still = thread.filter { it.id in openedByHand && it.id in expanded && !it.seen && it.id !in keptUnread }
        still.groupBy { accountOf(it) }.forEach { (key, rows) ->
            if (key == null) return@forEach
            val ids = rows.map { it.id }.toSet()
            paintSeen(key, ids, true)
            scope.launch {
                if (changed(key) { session(key).jmap.setKeyword(ids.toList(), "\$seen", true) } == null) {
                    paintSeen(key, ids, false, onlyIf = true)
                } else {
                    rememberSeen(key, ids)
                }
            }
        }
    }

    /**
     * The body of any card [expanded] is showing open that the effect above does not
     * already cover: an unread message that started open beside the one that was clicked,
     * or one the reader expanded by hand. A body already in [cards] is skipped, which is
     * what makes opening a card a second time free rather than a second fetch.
     *
     * One at a time rather than all at once, the same restraint as the read ahead below: a
     * burst of fetches would be in front of whichever card somebody is actually waiting on.
     */
    LaunchedEffect(expanded, selected) {
        val key = accountOf(selected) ?: return@LaunchedEffect
        for (id in expanded) {
            if (id == selected?.id || cards[CardKey(key, id)]?.body != null) continue
            loadCard(key, id)
            yield()
        }
    }

    /**
     * Opens or closes one card of the thread stack, in place.
     *
     * Never a swap: this only ever adds or removes [id] from [expanded], so everything else
     * already on screen, expanded or not, stays exactly where it was. See [initialExpanded]
     * for what a conversation starts with, before anything here has been clicked.
     */
    /**
     * Opens or closes one card, and marks it read when opening it was a deliberate act.
     *
     * **Only on a press, never because a card started open.** Unread messages in a
     * conversation are expanded when it opens, so marking everything expanded read would
     * mark a ten message thread read the moment it was opened, including the nine nobody
     * scrolled to. That is the exact failure the mark read delay setting exists to prevent,
     * and doing it in the name of a tidy unread count would be destroying real information.
     *
     * Pressing a collapsed message open is different. It is somebody saying they want to
     * read this one, which is the same statement clicking it in the list makes, so it is
     * treated the same way and honours the same setting, including "never".
     */
    fun toggleExpand(id: String) {
        val opening = id !in expanded
        expanded = if (opening) expanded + id else expanded - id
        // Recorded rather than acted on. The waiting is an effect below, so collapsing the
        // card again, or leaving the conversation, cancels it: launched from the screen's
        // own scope it would carry on and mark the message read from under a reader who
        // changed their mind, and pressing twice would send two of them.
        openedByHand = if (opening) openedByHand + id else openedByHand - id
    }

    /** Re-reads one account's folders, so the sidebar shows what the server now has. */
    suspend fun refreshFolders(key: String) {
        io { session(key).jmap.mailboxes() }?.let { mailboxes = mailboxes + (key to it) }
    }

    /**
     * The move itself, wherever it was asked for.
     *
     * Filing by role and filing into a folder somebody picked off a list are the same act
     * once the target is known, and the parts that are easy to forget are the ones after
     * the move: dropping the local copy, taking it out of the list on screen, and leaving
     * something to undo with.
     */
    /**
     * Telling the server that a move was a verdict, when it was one.
     *
     * **Not spam moved the message and told the server nothing.** The folder a message sits
     * in is where it is, not what it is, so moving one back to the inbox corrected the
     * symptom and left the filter believing exactly what it believed before. The same
     * sender arrived in Junk again the next morning, which is what was being reported.
     *
     * `$junk` and `$notjunk` are what the rest of the world writes for this (RFC 5788), so
     * a verdict made here is a verdict every other client and the server's own classifier
     * can read. Set before the move rather than after: over IMAP a moved message is a new
     * message with a new number, and the old number then names nothing.
     *
     * Only the two moves that are a verdict. Archiving something is not a statement about
     * whether it is spam, and stamping every filed message would teach the classifier that
     * everything ever tidied away was ham.
     */
    suspend fun sayJunk(key: String, ids: List<String>, from: String?, into: String): Boolean {
        val junk = folderFor("junk", mailboxes[key].orEmpty())?.id
        val spam = junkVerdict(junk, from, into) ?: return true
        val first = withContext(Dispatchers.IO) {
            runCatching { session(key).jmap.setKeyword(ids, "\$junk", spam) }
        }
        if (first.isFailure) {
            report("The spam verdict could not be saved.", whyFailed(first.exceptionOrNull()!!))
            return false
        }
        val second = withContext(Dispatchers.IO) {
            runCatching { session(key).jmap.setKeyword(ids, "\$notjunk", !spam) }
        }
        if (second.isFailure) {
            report("Only part of the spam verdict was saved. The message was not moved.", whyFailed(second.exceptionOrNull()!!))
            return false
        }
        return true
    }

    /**
     * Opens the next row after the open one leaves, in the order on screen.
     *
     * Called before the row is taken out of [emails], because the neighbour is
     * chosen from the list the reader is looking at. The next row, or the one
     * above when this was the last, or nothing when the list is now empty.
     */
    // The order on screen, which is not always the one that was chosen. Off, "apply to
    // all folders" keeps that choice for the Inbox and leaves every other list newest first.
    fun listOrder() = orderForList(
        order,
        listIsInbox(here?.second?.role, showingResults, viewingTag != null, viewingPerson != null),
        orderAll,
    )

    fun advancePast(leaving: (Summary) -> Boolean) {
        val shown = sorted(emails, listOrder())
        val at = shown.indexOfFirst { selected?.sameMail(it) == true }
        val open = selected
        if (at < 0) {
            if (open != null && leaving(open)) selected = null
            return
        }
        if (!leaving(shown[at])) return
        selected = nextInList(shown, at, leaving)
    }

    suspend fun carryOut(key: String, message: Summary, into: String, from: String?, verb: String) {
        if (!sayJunk(key, listOf(message.id), from, into)) return
        // The row stays until the server accepts the move. A refusal used to take
        // it off the list and offer Undo, and the message came back on the next
        // refresh because the server had never moved it.
        if (changed(key) { session(key).jmap.move(listOf(message.id), into) } == null) return
        // Out of the local copy as well, or the folder it left would show it
        // again the next time that folder is opened from disk.
        io { session(key).store?.forget(listOf(message.id)) }
        advancePast { it.sameMail(message) }
        emails = emails.filterNot { it.sameMail(message) }
        // And out of the conversation on screen. A card archived from inside the stack used
        // to stay there, fully interactive, as though the button had not worked, until the
        // reader opened something else.
        thread = thread.filterNot { it.id == message.id }
        expanded = expanded - message.id
        openedByHand = openedByHand - message.id
        cards = cards - CardKey(key, message.id)
        // Remembered, because the thread request for this conversation may still be in
        // flight and its answer was assembled before this move.
        filed = filed + message.id
        // One message can be taken back the same way a batch can. Filing the
        // wrong thing is a click, and having to go and find it again is the
        // part that makes people slow and careful about a button.
        undo = from?.let { Undoable(listOf(Move(key, listOf(message.id), it)), verb) }
    }

    /**
     * One message into the folder for [role]. Null when this account has no such folder.
     *
     * Not spam, and only that. Archive, delete and spam file the whole conversation.
     * See [fileConversation]. Move to a folder somebody picked is [carryOut] directly,
     * and stays the one message.
     */
    fun moveThis(message: Summary, role: String): (() -> Unit)? {
        val key = accountOf(message) ?: return null
        val target = folderFor(role, mailboxes[key].orEmpty()) ?: return null
        val from = sourceFolder(key)
        return { scope.launch { carryOut(key, message, target.id, from, pastTense(role)) } }
    }

    /**
     * Puts a message away until later.
     *
     * The keyword goes on before the move, and the move is what the other clients see. Both
     * are on the server, so the phone agrees about where the message is rather than still
     * showing it in the inbox, which is the failure that makes a snooze worse than none.
     */
    fun snooze(message: Summary, until: SnoozeUntil) {
        val key = accountOf(message) ?: return
        scope.launch {
            val boxes = mailboxes[key].orEmpty()
            val folder = boxes.firstOrNull { it.name.equals(SNOOZE_FOLDER, ignoreCase = true) }?.id
                ?: io { session(key).jmap.createMailbox(SNOOZE_FOLDER) }?.also { refreshFolders(key) }
                ?: run { report("This account would not make a $SNOOZE_FOLDER folder."); return@launch }
            val due = until.dueAt(ZonedDateTime.now(Regional.zone())).toInstant()
            val from = sourceFolder(key)
            if (changed(key) { session(key).jmap.setKeyword(listOf(message.id), snoozeKeyword(due), true) } == null) return@launch
            if (changed(key) { session(key).jmap.move(listOf(message.id), folder) } == null) {
                changed(key) { session(key).jmap.setKeyword(listOf(message.id), snoozeKeyword(due), false) }
                return@launch
            }
            io { session(key).store?.forget(listOf(message.id)) }
            advancePast { it.sameMail(message) }
            emails = emails.filterNot { it.sameMail(message) }
            undo = from?.let { Undoable(listOf(Move(key, listOf(message.id), it)), "Snoozed") }
        }
    }

    /**
     * Brings back whatever has come due, whenever Rampart happens to be looking.
     *
     * ponytail: this is the punctuality ceiling, and it is the protocol's rather than ours.
     * Neither JMAP nor IMAP can schedule anything and Sieve runs only at delivery, so
     * nothing on the server can move a message back by itself. The folder is honest while
     * it waits; a companion server could make it punctual.
     */
    suspend fun wakeSnoozed(key: String) {
        val boxes = mailboxes[key].orEmpty()
        val folder = boxes.firstOrNull { it.name.equals(SNOOZE_FOLDER, ignoreCase = true) } ?: return
        val inbox = folderFor("inbox", boxes) ?: return
        val now = Instant.now()
        val waiting = io { session(key).jmap.emails(folder.id, limit = 200) }.orEmpty()
        val due = waiting.filter { dueBack(it.keywords, now) }
        if (due.isEmpty()) return
        val results = withContext(Dispatchers.IO) {
            due.map { message ->
                val moved = runCatching { session(key).jmap.move(listOf(message.id), inbox.id) }
                val followups = mutableListOf<Result<*>>()
                if (moved.isSuccess) {
                    snoozedIn(message.keywords)?.let {
                        followups += runCatching {
                            session(key).jmap.setKeyword(listOf(message.id), it.keyword, false)
                        }
                    }
                // Unread on the way back, because the point of snoozing was to deal with it
                // later and a message that returns already read returns invisible.
                    followups += runCatching {
                        session(key).jmap.setKeyword(listOf(message.id), "\$seen", false)
                    }
                }
                Triple(message, moved, followups)
            }
        }
        val moved = results.filter { it.second.isSuccess }.map { it.first.id }
        if (moved.isNotEmpty()) io { session(key).store?.forget(moved) }
        val failure = results.firstNotNullOfOrNull { (_, move, followups) ->
            move.exceptionOrNull() ?: followups.firstNotNullOfOrNull { it.exceptionOrNull() }
        }
        failure?.let {
            report("Some snoozed messages could not be returned.", whyFailed(it))
        }
        refreshFolders(key)
    }

    /*
     * What has been opened, asked of the companion rather than of the mail server.
     *
     * The whole loop is one call, and it needs nothing from the mailbox at all: Rampart
     * asks what has been fetched since it last asked, writes it into the local copy, and
     * matches it to messages here, where the mapping from id to message lives. The server
     * is never told what it is answering about.
     *
     * A notification fires only for a fetch this poll actually inserted, and only when
     * [classify] calls it a person. The store ignoring a duplicate is what stops the same
     * open popping up again on the next poll, and [classify] is what stops a scanner
     * popping up at all. Each account is asked on its own, because the row that says who
     * the message went to lives in whichever account sent it.
     *
     * Silent on every failure. A tracking server that is down is not a reason to interrupt
     * somebody reading their mail, and the cursor does not move, so nothing is lost.
     */
    LaunchedEffect(sessions, trackingServer) {
        if (trackingServer.isBlank()) return@LaunchedEffect
        while (true) {
            val token = withContext(Dispatchers.IO) { Secrets.trackingToken() }
            if (!token.isNullOrBlank()) {
                runCatching {
                    val since = Instant.ofEpochMilli(Settings.trackingCursor())
                    val found = withContext(Dispatchers.IO) {
                        TrackingClient.since(trackingServer, token, since)
                    }
                    if (found.isNotEmpty()) {
                        val rookActivity = mutableListOf<String>()
                        val notices = withContext(Dispatchers.IO) {
                            buildList {
                                sessions.forEach { open ->
                                    runCatching {
                                        val store = open.store ?: return@runCatching
                                        val inserted = store.recordFetches(found)
                                        if (inserted.isEmpty()) return@runCatching
                                        val tracked = store.trackedByIds(inserted.map { it.id })
                                        inserted.filter { fetch ->
                                            val row = tracked[fetch.id]
                                            row != null && row.repliedAt == null && classify(fetch, row.sentAt) == Opened.READ
                                        }.forEach { fetch ->
                                            val row = tracked.getValue(fetch.id)
                                            val action = if (fetch.event == "click") "clicked a link in" else "opened"
                                            rookActivity += "${row.recipient} $action ${row.subject.ifBlank { "your email" }}."
                                        }
                                        openText(
                                            opensToAnnounce(
                                                inserted,
                                                tracked,
                                            ),
                                        )?.let { add(it) }
                                    }
                                }
                            }
                        }
                        if (notifyOnOpen) notices.forEach { (title, body) -> notify(title, body) }
                        val rookKey = selected?.let { accountOf(it) } ?: settingsAccount()
                        if (rookActivity.isNotEmpty() && rookKey != null) appendRook(rookKey, rookActivity.map { Said("assistant", it) })
                        trackingBadges = trackingBadgesOf(sessions.flatMap { it.store?.tracking().orEmpty() })
                        if (Settings.ntfyTrackedOpen() && !TrackingClient.companionPushes) {
                            notices.forEach { (title, body) -> phoneAlert(title, body) }
                        }
                        // Moved only after the rows are written, so a crash between the two
                        // re-reads rather than skips. The store ignores a duplicate.
                        Settings.setTrackingCursor(found.maxOf { it.at.toEpochMilli() })
                    }
                }
            }
            delay(120_000)
        }
    }

    /**
     * Sends [draft] the way the Send button does, without touching the compose window.
     *
     * The tracking id is minted here, at the last moment, and never in the composer.
     * A draft that is edited and saved five times would otherwise carry five ids, or
     * the same id on two different messages if it were copied. Minting on the way out
     * means one id belongs to exactly one message that actually went. The Message-ID
     * is minted with it, because a tracked message keeps a different object in Sent
     * from the one that was sent and the two have to agree or the reply threads
     * against nothing.
     *
     * Success and failure come back as a result. The compose window decides what to
     * do with that. A scheduled send has no window open, and it makes the same call.
     */
    suspend fun deliverNow(
        account: Session,
        draft: Draft,
        identity: Identity,
        draftsId: String,
        sentId: String?,
        draftId: String?,
        key: String,
    ): Result<String?> = tried {
        val trackingBase = Settings.trackingServer()
        var tracked: Tracked? = null
        var clickLinks: List<String> = emptyList()
        var outgoing = if ((!draft.tracked && !draft.clickTracked) || trackingBase.isBlank() ||
            draft.recipients.any { recipient -> identities[key].orEmpty().any { it.email.equals(recipient, true) } }) {
            draft
        } else {
            val id = newTrackingId()
            val sentAt = Instant.now()
            val rewritten = if (draft.clickTracked) {
                htmlPartOf(draft)?.let { rewriteTrackedLinks(it, trackingBase, id) }
            } else null
            draft.copy(
                html = rewritten?.html ?: draft.html,
                trackingPixel = if (draft.tracked) pixelHtml(trackingBase, id) else "",
                messageId = newTrackingId() + "@" + (domainOf(identity.email).ifBlank { "rampart.invalid" }),
            ).also { ready ->
                clickLinks = rewritten?.originals.orEmpty()
                tracked = Tracked(
                    id = id,
                    messageId = ready.messageId.orEmpty(),
                    account = key,
                    recipient = trackingRecipient(draft.to, draft.cc),
                    subject = draft.subject,
                    sentAt = sentAt,
                )
            }
        }
        // Measured from here, not from an undo wait the caller may have done first:
        // that pause is deliberate, not server latency, and counting it would make
        // every send look exactly [Settings.undoSeconds] slower than it was.
        var cleanupDraft: String? = null
        var trackingNotice: String? = null
        val notice = Diagnostics.time(Metric.SEND_COMPOSE_TO_SENT) {
            withContext(Dispatchers.IO) {
                tracked?.let { row ->
                    val token = Secrets.trackingToken()
                    val registered = TrackingClient.registerMessage(
                        trackingBase, token, row, clickLinks, Settings.ntfyOpenLabels(),
                    )
                    if (!registered) {
                        val retryLinks = clickLinks
                        scope.launch(Dispatchers.IO) {
                            delay(1_000)
                            TrackingClient.registerMessage(
                                trackingBase, token, row, retryLinks, Settings.ntfyOpenLabels(),
                            )
                        }
                        outgoing = draft
                        tracked = null
                        clickLinks = emptyList()
                        trackingNotice = "The message was sent without tracking because the companion server could not be reached."
                    }
                }
                // Signed or encrypted mail is built and protected here; it never falls back to plain.
                val filed = if (outgoing.sign || outgoing.encrypt) {
                    sendSealed(account.jmap, Keys.ring, outgoing, identity, draftsId, sentId)
                } else {
                    account.jmap.send(outgoing, identity, draftsId, sentId)
                }
                // Written down only once it has actually gone. A tracked id for a
                // message that failed to send would sit in the list forever waiting
                // for an open that cannot come.
                tracked?.let { account.store?.track(it, clickLinks) }
                // The sent message is its own copy in Sent, so the working copy in
                // Drafts is now a duplicate of mail already gone.
                val cleanup = draftId?.let { id ->
                    runCatching { account.jmap.destroy(listOf(id)) }.exceptionOrNull()
                }
                if (cleanup != null) cleanupDraft = draftId
                listOfNotNull(
                    filed,
                    trackingNotice,
                    cleanup?.let {
                        "The message was sent, but its working draft could not be removed. Try deleting it from Drafts."
                    },
                ).joinToString(" ").ifBlank { null }
            }
        }
        cleanupDraft?.let { id ->
            scope.launch {
                delay(5_000)
                withContext(Dispatchers.IO) { runCatching { account.jmap.destroy(listOf(id)) } }
            }
        }
        trackingBadges = trackingBadgesOf(sessions.flatMap { it.store?.tracking().orEmpty() })
        var book = books[key] ?: AddressBook.read(AddressBook.file(key))
        // Somebody new also goes on the server's address book, so phones see them too.
        scope.launch {
            val recipients = parseAddressList(draft.to) + parseAddressList(draft.cc)
            val mine = identities[key].orEmpty().map { it.email }
            withContext(Dispatchers.IO) { runCatching { LearnedContacts.save(account.jmap, key, recipients, mine) } }
                .exceptionOrNull()?.let {
                    report("The message was sent, but a new address did not reach your contacts.", whyFailed(it))
                }
        }
        draft.recipients.forEach { book = noted(book, it) }
        books = books + (key to book)
        runCatching { AddressBook.write(book, AddressBook.file(key)) }
        notice
    }.also { result ->
        result.onFailure { thrown ->
            Diagnostics.event(Metric.SEND_FAILURE, sendFailureCategoryOf(thrown))
        }
    }

    /**
     * Submits [draft] the way [deliverNow] does, but asks the server to hold it until
     * [holdUntil] instead of sending it now.
     *
     * Everything [deliverNow] does around the actual send, minting the tracking id, filing
     * the working draft's cleanup, noting the address book, applies here unchanged, which
     * is why the two read almost the same. They are kept as separate functions rather than
     * merged behind one signature because what comes back differs: a submission id to
     * cancel by, and the id of wherever the message ended up filed, not just a notice.
     */
    suspend fun deliverDelayed(
        account: Session,
        draft: Draft,
        identity: Identity,
        draftsId: String,
        sentId: String?,
        draftId: String?,
        key: String,
        holdUntil: Instant,
    ): Result<DelayedSend> = tried {
        val trackingBase = Settings.trackingServer()
        var tracked: Tracked? = null
        var clickLinks: List<String> = emptyList()
        var outgoing = if ((!draft.tracked && !draft.clickTracked) || trackingBase.isBlank() ||
            draft.recipients.any { recipient -> identities[key].orEmpty().any { it.email.equals(recipient, true) } }) {
            draft
        } else {
            val id = newTrackingId()
            val sentAt = Instant.now()
            val rewritten = if (draft.clickTracked) {
                htmlPartOf(draft)?.let { rewriteTrackedLinks(it, trackingBase, id) }
            } else null
            draft.copy(
                html = rewritten?.html ?: draft.html,
                trackingPixel = if (draft.tracked) pixelHtml(trackingBase, id) else "",
                messageId = newTrackingId() + "@" + (domainOf(identity.email).ifBlank { "rampart.invalid" }),
            ).also { ready ->
                clickLinks = rewritten?.originals.orEmpty()
                tracked = Tracked(
                    id = id,
                    messageId = ready.messageId.orEmpty(),
                    account = key,
                    recipient = trackingRecipient(draft.to, draft.cc),
                    subject = draft.subject,
                    sentAt = sentAt,
                )
            }
        }
        var cleanupDraft: String? = null
        var trackingNotice: String? = null
        val delayed = Diagnostics.time(Metric.SEND_COMPOSE_TO_SENT) {
            withContext(Dispatchers.IO) {
                tracked?.let { row ->
                    val token = Secrets.trackingToken()
                    val registered = TrackingClient.registerMessage(
                        trackingBase, token, row, clickLinks, Settings.ntfyOpenLabels(),
                    )
                    if (!registered) {
                        val retryLinks = clickLinks
                        scope.launch(Dispatchers.IO) {
                            delay(1_000)
                            TrackingClient.registerMessage(
                                trackingBase, token, row, retryLinks, Settings.ntfyOpenLabels(),
                            )
                        }
                        outgoing = draft
                        tracked = null
                        clickLinks = emptyList()
                        trackingNotice = "The message was scheduled without tracking because the companion server could not be reached."
                    }
                }
                val made = account.jmap.sendDelayed(outgoing, identity, draftsId, sentId, holdUntil)
                tracked?.let { account.store?.track(it, clickLinks) }
                val cleanup = draftId?.let { id ->
                    runCatching { account.jmap.destroy(listOf(id)) }.exceptionOrNull()
                }
                if (cleanup != null) cleanupDraft = draftId
                made.copy(
                    notice = listOfNotNull(
                        made.notice,
                        trackingNotice,
                        cleanup?.let {
                            "The message was scheduled, but its working draft could not be removed. Try deleting it from Drafts."
                        },
                    ).joinToString(" ").ifBlank { null },
                )
            }
        }
        cleanupDraft?.let { id ->
            scope.launch {
                delay(5_000)
                withContext(Dispatchers.IO) { runCatching { account.jmap.destroy(listOf(id)) } }
            }
        }
        trackingBadges = trackingBadgesOf(sessions.flatMap { it.store?.tracking().orEmpty() })
        var book = books[key] ?: AddressBook.read(AddressBook.file(key))
        draft.recipients.forEach { book = noted(book, it) }
        books = books + (key to book)
        runCatching { AddressBook.write(book, AddressBook.file(key)) }
        delayed
    }.also { result ->
        result.onFailure { thrown ->
            Diagnostics.event(Metric.SEND_FAILURE, sendFailureCategoryOf(thrown))
        }
    }

    /**
     * Sends one scheduled message, if its account is open and ready.
     *
     * Null means skipped: the account is not signed in, its folders have not
     * arrived yet, a send of this same message is already in flight, or the server is
     * already holding this one itself. None of those cancel the schedule, except the
     * last: a message the server holds is never fired from here at all, so calling this
     * a second time on it (from the "Send now" row action, say) cannot send it twice. A
     * failure is a result, and the schedule stays so the next pass can try again.
     */
    suspend fun fireOne(item: ScheduledSend): Result<Unit>? {
        if (item.heldSubmissionId != null) return null
        if (!firing.add(item.id)) return null
        try {
            val account = sessions.firstOrNull { it.key == item.account } ?: return null
            val identity = identityForDraft(identities[item.account].orEmpty(), item.identityEmail) ?: return null
            val boxes = mailboxes[item.account].orEmpty()
            val drafts = folderFor("drafts", boxes) ?: return null
            val result = deliverNow(
                account,
                item.draft,
                identity,
                drafts.id,
                folderFor("sent", boxes)?.id,
                item.draftId,
                item.account,
            )
            // Dropped before this function returns, so a second pass cannot
            // start another send of a message that has already gone.
            if (result.isSuccess) {
                result.getOrNull()?.let { notice -> if (error.isBlank()) report(notice) }
                ScheduledSends.cancel(item.id)
                if (Settings.ntfyScheduledSend()) {
                    phoneAlert("Scheduled message sent", item.draft.subject.ifBlank { "(no subject)" })
                }
                return Result.success(Unit)
            }
            return Result.failure(result.exceptionOrNull() ?: Exception("That message could not be sent."))
        } finally {
            firing.remove(item.id)
        }
    }

    /*
     * Fires anything whose time has come, for accounts that are signed in.
     *
     * Whether that means sending it here or leaving it to the server was decided back
     * when it was scheduled, in onSchedule, from that account's maxDelayedSend. This
     * only handles what onSchedule left for us:
     *
     * - A server-held item (heldSubmissionId set) was already submitted at scheduling
     *   time, with the server asked to hold it until now. There is nothing to send here;
     *   once its time has passed the server has taken care of it, so the local record is
     *   simply dropped.
     * - Anything else is one Rampart itself is holding, because the account's server
     *   either has no delayed send at all or not enough of it for this wait. The message
     *   is already in Drafts. This only decides when.
     *
     * If Rampart is not running when a client-held time arrives, nothing sends until the
     * next time it is opened. A message can go out late. If Rampart is never opened
     * again, the draft sits in Drafts until somebody sends it by hand. It does not
     * disappear. A server-held message has no such gap, which is the point of asking for
     * one: it goes out on time whether or not Rampart is running to notice.
     *
     * An account that is not signed in is skipped, not cancelled, and is tried
     * again whenever that account is open. A send that fails is left in the
     * list and tried again on the next pass.
     */
    suspend fun fireDue() {
        var sentAny = false
        var problem: String? = null
        try {
            for (item in ScheduledSends.due()) {
                if (item.heldSubmissionId != null) {
                    ScheduledSends.cancel(item.id)
                    continue
                }
                if (sessions.none { it.key == item.account }) continue
                when (val outcome = fireOne(item)) {
                    null -> Unit
                    else -> if (outcome.isSuccess) {
                        if (selected?.id == item.draftId) selected = null
                        sentAny = true
                    } else {
                        outcome.exceptionOrNull()?.let { thrown ->
                            problem = "A scheduled message could not be sent. " +
                                "It is still in Drafts and will be tried again."
                            report(problem!!, whyFailed(thrown))
                        }
                    }
                }
            }
            if (sentAny) refreshNow()
            problem?.let { if (error.isBlank()) report(it) }
        } finally {
            scheduledSends = ScheduledSends.pending()
        }
    }

    /** Send a scheduled draft now, from its row in Drafts. */
    fun sendScheduledNow(message: Summary) {
        val item = ScheduledSends.pending().firstOrNull { it.draftId == message.id } ?: return
        scope.launch {
            when (val outcome = fireOne(item)) {
                null -> Unit
                else -> if (outcome.isSuccess) {
                    if (selected?.id == item.draftId) selected = null
                    scheduledSends = ScheduledSends.pending()
                    refreshNow()
                } else {
                    outcome.exceptionOrNull()?.let { thrown ->
                        report(
                            "That message could not be sent. It is still scheduled.",
                            whyFailed(thrown),
                        )
                    }
                }
            }
        }
    }

    /**
     * Drop the schedule.
     *
     * A client-held message was never touched, so dropping the local record is the whole
     * of it: the draft stays in Drafts exactly as it was. A server-held one has already
     * been submitted, so cancelling it means asking the server to call it back and then
     * putting the message back in Drafts as an editable draft ourselves, since JMAP has
     * no way to hand a submission back to us once it exists.
     */
    fun cancelScheduled(message: Summary) {
        val item = ScheduledSends.pending().firstOrNull { it.draftId == message.id } ?: return
        val held = item.heldSubmissionId
        if (held == null) {
            ScheduledSends.cancel(item.id)
            scheduledSends = ScheduledSends.pending()
            return
        }
        scope.launch {
            val account = sessions.firstOrNull { it.key == item.account } ?: return@launch
            val identity = identityForDraft(identities[item.account].orEmpty(), item.identityEmail) ?: return@launch
            val drafts = folderFor("drafts", mailboxes[item.account].orEmpty()) ?: return@launch
            val outcome = runCatching { account.jmap.cancelDelayed(held) }
            if (outcome.isFailure) {
                report("That message could not be reached to cancel it.", whyFailed(outcome.exceptionOrNull()!!))
                return@launch
            }
            // Non-null here is the server's own sentence for why not, usually that the
            // hold has already ended. Nothing here is touched: the message is on its way
            // or gone, and the schedule is left for fireDue to clean up once it is due.
            val refusal = outcome.getOrNull()
            if (refusal != null) {
                report(refusal)
                return@launch
            }
            runCatching { account.jmap.saveDraft(item.draft, identity, drafts.id, null) }
            runCatching { account.jmap.destroy(listOf(item.draftId)) }
            if (selected?.id == item.draftId) selected = null
            ScheduledSends.cancel(item.id)
            scheduledSends = ScheduledSends.pending()
            refreshNow()
        }
    }

    /*
     * Its own round rather than a line inside the poll above, because a local function
     * cannot be called before it is declared and the poll is written further up.
     *
     * A minute is as often as it is worth asking: the messages here are ones somebody
     * deliberately put down, so a minute either way is nothing, and the folder shows what is
     * due in the meantime.
     *
     * The body runs once immediately, so a message whose time passed while Rampart
     * was closed goes on this open rather than a minute later. Folders and identities
     * arrive in their own effect, a moment after sign-in. While a due message is
     * waiting on those, this looks again each second, up to half a minute, and then
     * settles into the usual minute. Nothing scheduled means no extra wait, so the
     * snooze check is not slowed for anyone who never schedules a message.
     */
    LaunchedEffect(sessions) {
        var waited = 0
        var gaveUp = false
        while (true) {
            val notReady = !gaveUp && ScheduledSends.due().any { item ->
                sessions.any { it.key == item.account } &&
                    (mailboxes[item.account] == null || identities[item.account] == null)
            }
            if (notReady && waited < 30) {
                waited++
                delay(1_000)
                continue
            }
            if (notReady) gaveUp = true
            waited = 0
            // A group mailbox can hold snoozed mail too, and nothing else would wake it.
            (sessions + sharing.shared).forEach { runCatching { wakeSnoozed(it.key) } }
            // Follow-up flags ride the same minute, and ask each server every few minutes.
            runCatching { followUps.check(sessions.map { it.key to it.jmap }) }
            runCatching { fireDue() }
            delay(60_000)
        }
    }

    /** Starring one message, from the reader or from a row. */
    fun starOne(message: Summary) {
        val key = accountOf(message) ?: return
        val wanted = !message.flagged
        // The star turns over at once and is put back if the server says no. A star that
        // waits for a round trip feels broken at the speed people click.
        fun show(value: Boolean) {
            emails = emails.map { if (it.sameMail(message)) it.copy(flagged = value) else it }
            if (selected?.sameMail(message) == true) selected = selected?.copy(flagged = value)
            ThreadGists.paint(key, setOf(message.id), flagged = value)
        }
        show(wanted)
        scope.launch {
            if (changed(key) { session(key).jmap.setKeyword(listOf(message.id), "\$flagged", wanted) } == null) {
                show(!wanted)
            }
        }
    }

    /** A star on or off for several messages at once: the rest of a collapsed conversation. */
    fun starMany(key: String, ids: Set<String>, on: Boolean) {
        if (ids.isEmpty()) return
        fun show(value: Boolean) {
            emails = emails.map { if (it.sameMail(key, ids)) it.copy(flagged = value) else it }
            thread = thread.map { if (it.sameMail(key, ids)) it.copy(flagged = value) else it }
            selected?.let { if (it.sameMail(key, ids)) selected = it.copy(flagged = value) }
            ThreadGists.paint(key, ids, flagged = value)
        }
        show(on)
        scope.launch {
            if (changed(key) { session(key).jmap.setKeyword(ids.toList(), "\$flagged", on) } == null) show(!on)
        }
    }

    /** Read or unread, from a row, without having to open the message to do it. */
    /**
     * Read or unread, for one message or for a whole conversation.
     *
     * The list, the conversation around the open message and the open message itself all
     * move first and the server is told after, because the alternative is a row that stays
     * bold for as long as the round trip takes.
     */
    fun setSeen(key: String, ids: Set<String>, read: Boolean) {
        if (ids.isEmpty()) return
        paintSeen(key, ids, read)
        scope.launch {
            if (changed(key) { session(key).jmap.setKeyword(ids.toList(), "\$seen", read) } == null) {
                paintSeen(key, ids, !read, onlyIf = read)
                return@launch
            }
            // The copy on disk learns it too. Without this a reload served from the local
            // store shows the row unread again for the moment before the server answers,
            // which is the same flash by a different route. In the merged list each row
            // goes to its own account, under that account's inbox: writing the whole
            // list under "*all*" moved real inbox rows onto a folder that is not one.
            rememberSeen(key, ids)
        }
    }

    fun markRead(message: Summary, read: Boolean) {
        keptUnread = if (read) keptUnread - message.id else keptUnread + message.id
        accountOf(message)?.let { setSeen(it, setOf(message.id), read) }
    }

    /** What Ask Rook and the Today view may read on [key]. Read only: see [inboxReader]. */
    fun readerFor(key: String): InboxReader =
        inboxReader(session(key).jmap, mailboxes[key].orEmpty(), key) { rookTextOf(restoredBody(key, it)) }

    /**
     * Everything the assistant panel is able to reach.
     *
     * Built here because this is where the sessions are, and deliberately small: what is
     * on this object is exactly what a model in that panel can do, so the list is the
     * security boundary rather than the prompt being polite. Nothing here sends, and
     * nothing here deletes for good.
     *
     * The calls block, and the panel runs them on a background thread.
     */
    fun toolsFor(key: String): MailTools = object : MailTools {
        override fun search(text: String, limit: Int): List<Summary> =
            runCatching {
                session(key).jmap.search(text, null, limit, searchExcept(key))
            }.getOrDefault(emptyList())

        override fun read(id: String): String? =
            runCatching { rookTextOf(restoredBody(key, session(key).jmap.body(id))) }.getOrNull()?.ifBlank { null }

        override fun file(ids: List<String>, role: String): Int {
            if (ids.isEmpty()) return 0
            val target = folderFor(role, mailboxes[key].orEmpty())?.id ?: return 0
            val from = sourceFolder(key)
            val moved = runCatching { session(key).jmap.move(ids, target) }
            if (moved.isFailure) return 0
            noteState(key, moved.getOrNull())
            runCatching { session(key).store?.forget(ids) }
            // Undoable like every other move. An action nobody typed is the one that most
            // needs taking back.
            scope.launch {
                val gone = ids.toSet()
                advancePast { it.sameMail(key, gone) }
                emails = emails.filterNot { it.sameMail(key, gone) }
                undo = from?.let { Undoable(listOf(Move(key, ids, it)), pastTense(role)) }
            }
            return ids.size
        }

        override fun markRead(ids: List<String>, read: Boolean): Int {
            if (ids.isEmpty()) return 0
            val changed = runCatching { session(key).jmap.setKeyword(ids, "\$seen", read) }
            if (changed.isFailure) return 0
            noteState(key, changed.getOrNull())
            scope.launch {
                paintSeen(key, ids.toSet(), read)
                rememberSeen(key, ids.toSet())
            }
            return ids.size
        }

        override fun tag(ids: List<String>, keyword: String, on: Boolean): Int {
            if (ids.isEmpty()) return 0
            val word = keyword.trim().lowercase()
            if (runCatching { session(key).jmap.setKeyword(ids, word, on) }.isFailure) return 0
            scope.launch { reload() }
            return ids.size
        }

        override fun draftReply(id: String, text: String): Boolean {
            val message = (emails + thread).firstOrNull { it.id == id } ?: return false
            val letter = runCatching { restoredBody(key, session(key).jmap.body(id)) }.getOrNull()
            scope.launch {
                val ours = identities[key].orEmpty().map { it.email }.toSet()
                sendError = null; sendDetail = null
                // The model's words go above the quoted original, where a person's would.
                val reply = replyTo(message, letter, identities[key].orEmpty().firstOrNull()?.email.orEmpty(), false, ours)
                write(key, reply.copy(body = text.trim() + "\n\n" + reply.body))
            }
            return true
        }

        override fun trackingToday(): String {
            val zone = Regional.zone()
            val start = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val lines = session(key).store?.tracking().orEmpty().flatMap { (tracked, events) ->
                events.filter { it.at >= start && classify(it, tracked.sentAt) == Opened.READ }.map { event ->
                    val action = if (event.event == "click") "clicked ${event.url}" else "opened"
                    "${tracked.recipient} $action ${tracked.subject.ifBlank { "(no subject)" }} at ${Regional.dateTime(event.at)}"
                }
            }
            return if (lines.isEmpty()) "No person opens or clicks were recorded today." else lines.joinToString("\n")
        }
    }

    /**
     * One question, and whatever the model does about it.
     *
     * The whole exchange happens off the main thread and comes back as lines to add. The
     * transcript is what the person reads and what goes back up next turn, so an action
     * that happened is in both or in neither.
     */
    fun ask(question: String) {
        if (question.isBlank() || chatThinking) return
        // The open message's account first, so Rook in the unified inbox acts where the
        // person is looking rather than on whichever account signed in first.
        val key = selected?.let { accountOf(it) } ?: settingsAccount() ?: return
        val config = Assistant.config()
        Assistant.whyNot(Assistant.CHAT, config)?.let {
            appendRook(key, Said("result", it))
            return
        }
        appendRook(key, Said("user", question))
        chatThinking = true
        val history = rookConversations.said(key)
        val folders = mailboxes[key].orEmpty().map { it.name }
        val who = sessions.firstOrNull { it.key == key }?.account?.email.orEmpty()
        val desk = settingCards[key] ?: ChangeDesk()
        val here = sessions.firstOrNull { it.key == key }
        val known = identities[key].orEmpty()
        val signedIn = sessions.map { it.account.email }
        val drafted = mutableListOf<ChangeCard>()
        // Cheap to build: nothing is read from the calendar until Rook asks for a day.
        val calendar = calendarToolsFor(here?.jmap, here?.let { shortAccountName(it.account.name, it.account.email) }.orEmpty())
        // The message on screen is what "this email" means, so Rook is told about it
        // without having to search for it first, and may act on it like a search result.
        val open = selected?.takeIf { accountOf(it) == key }
        val openText = open?.let { cardFor(it).body }?.let(::rookTextOf).orEmpty()
        val allowances = chatShown.getOrPut(key, ::MessageAllowances)
        open?.let(allowances::showOpen)
        // Only the open message can be linked from a task Rook proposes. See TaskFromMail.kt.
        val tasks = taskToolsFor(
            here?.jmap,
            here?.let { shortAccountName(it.account.name, it.account.email) }.orEmpty(),
            open?.let { taskLinkOf(it, cardFor(it).body) },
        )
        // Cheap to build: nothing is read, and no rule is made, until Rook asks for a filter.
        val filterDesk = filterCards[key] ?: FilterDesk()
        val mailDesk = mailChangeCards[key] ?: MailChangeDesk()
        val mailChanges = MailChangeTools(key, who, allowances, mailDesk.nextNumber)
        val filterTools = filterToolsFor(
            here?.jmap,
            key,
            here?.let { shortAccountName(it.account.name, it.account.email) }.orEmpty(),
            folders,
            config,
            filterDesk.nextNumber,
            filterDesk.waiting.size,
        )
        val attached = RookAttachment.file
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    val settings = SettingsTools(liveSettingsMap(here, known, signedIn), desk.nextNumber, desk.waiting.size)
                    try {
                        converse(
                            config = config,
                            key = Secrets.loadNamed(Assistant.KEY),
                            system = Chat.system(folders, who) + "\n\n" + settingsPrompt() +
                                "\n\n" + calendarPrompt(java.time.LocalDate.now(Regional.zone()), Regional.zone()) +
                                "\n\n" + taskPrompt(java.time.LocalDate.now(Regional.zone()), Regional.zone(), open != null) +
                                open?.let { "\n\n" + Chat.openMessage(it, openText) }.orEmpty() +
                                rookFileContext(attached),
                            history = history,
                            shown = allowances,
                            tools = toolsFor(key),
                            record = { tokensIn, tokensOut ->
                                Assistant.record(Assistant.CHAT, tokensIn, tokensOut, config)
                            },
                            settings = settings,
                            calendar = calendar,
                            tasks = tasks,
                            filters = filterTools,
                            mailChanges = mailChanges,
                        )
                    } finally {
                        drafted += settings.drafted
                    }
                }.getOrElse { listOf(Said("result", it.message ?: "The model could not be reached.")) }
            }
            appendRook(key, added)
            settingCards = settingCards + (key to ((settingCards[key] ?: ChangeDesk()).add(drafted)))
            filterCards = filterCards + (key to ((filterCards[key] ?: FilterDesk()).add(key, filterTools.proposed)))
            var changedDesk = mailChangeCards[key] ?: MailChangeDesk()
            mailChanges.proposed.forEach { changedDesk = changedDesk.add(it) }
            mailChangeCards = mailChangeCards + (key to changedDesk)
            // The account is stamped here, not inside the tool: the tool does not know which
            // account the window will save to, and a later switch must not move the rule.
            filterTools.consent?.let { pending ->
                if (filterConsent == null) filterConsent = pending.copy(account = key)
            }
            ProposedEvents.offer(calendar, here?.jmap, key)
            ProposedTasks.offer(tasks, here?.jmap)
            chatThinking = false
        }
    }

    /** Confirm is the sole path from a Rook mail card to a mailbox write. */
    fun confirmMailChange(key: String, number: Int) {
        val desk = mailChangeCards[key] ?: return
        val card = desk.card(number)?.takeIf { it.status == CardStatus.WAITING && it.account == key } ?: return
        mailChangeCards = mailChangeCards + (key to desk.start(number))
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { applyMailChange(card, toolsFor(key)) }
                    .getOrElse { it.message ?: "The mail could not be changed." }
            }
            val latest = mailChangeCards[key] ?: MailChangeDesk()
            mailChangeCards = mailChangeCards + (key to latest.finish(number, failure))
            appendRook(
                key,
                Said("result", failure?.let { "The card was not applied: $it" } ?: "Confirmed: ${card.action.lowercase()} for ${card.messages.size} messages."),
            )
        }
    }

    /** Cancel closes the proposal and has no route to [MailTools]. */
    fun cancelMailChange(key: String, number: Int) {
        val desk = mailChangeCards[key] ?: return
        val card = desk.card(number)?.takeIf { it.status == CardStatus.WAITING && it.account == key } ?: return
        mailChangeCards = mailChangeCards + (key to desk.dismiss(number))
        appendRook(key, Said("result", "Cancelled ${card.action.lowercase()}. Nothing changed."))
    }

    /**
     * Confirm, pressed on one of Rook's setting cards: the only path by which a change it
     * asked for is written. [applyCard] checks the card again before writing, and the
     * outcome goes into the transcript so Rook knows on its next turn.
     */
    fun confirmCard(key: String, number: Int) {
        val desk = settingCards[key] ?: return
        val card = desk.card(number)?.takeIf { it.status == CardStatus.WAITING } ?: return
        // The account Rook was working in when it drafted the card, not whichever the
        // settings page would pick, so a change meant for one account never lands on another.
        val here = sessions.firstOrNull { it.key == key }
        val known = key?.let { identities[it] }.orEmpty()
        val signedIn = sessions.map { it.account.email }
        settingCards = settingCards + (key to desk.start(number))
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { applyCard(card, liveSettingsMap(here, known, signedIn)) }
                    .getOrElse { it.message ?: "The change could not be made." }
            }
            settingCards = settingCards + (key to ((settingCards[key] ?: ChangeDesk()).finish(number, failure)))
            appendRook(key, Said("result", failure?.let { "Card $number was not applied: $it" } ?: "Confirmed and changed: ${card.summary}."))
            if (failure != null) return@launch
            // The window holds some settings in memory so a change shows at once. Those
            // are read again here, as the Settings pages' own callbacks would have.
            for (line in card.lines) {
                when (line.id) {
                    "rampart.theme", "rampart.themeMode", "rampart.fontSize", "rampart.animations" -> onAppearance()
                    "rampart.icons" -> onIcons(iconPack(Settings.iconPack()))
                    "rampart.sidebarIcons" -> sidebarIcons = SidebarIcons.of(Settings.sidebarIcons())
                    "rampart.loader" -> loader = Loader.of(Settings.loader())
                    "rampart.density" -> density = Density.of(Settings.density())
                    "rampart.listLayout" -> ListLayoutState.reload()
                    "rampart.mailLayout" -> MailLayoutState.reload()
                    "rampart.tintRowsByTag" -> tintRows = Settings.tintRowsByTag()
                    "rampart.undoBarSeconds" -> undoBarSeconds = Settings.undoBarSeconds()
                    "rampart.notifyOnArrival" -> notifyOnArrival = Settings.notifyOnArrival()
                    "rampart.notifyOnOpen" -> notifyOnOpen = Settings.notifyOnOpen()
                    "rampart.order" -> order = Settings.order()
                    "rampart.orderAppliesToAll" -> orderAll = Settings.orderAppliesToAll()
                    "rampart.messageMode" -> messageMode = Settings.messageMode()
                    "rampart.messageScale" -> messageScale = Settings.messageScale()
                }
            }
            if (key != null && card.home == SettingHome.MAILBOX) {
                withContext(Dispatchers.IO) {
                    runCatching { session(key).jmap.vacation() to session(key).jmap.identities() }.getOrNull()
                }?.let { (away, ids) ->
                    vacation = away
                    identities = identities + (key to ids)
                }
            }
        }
    }

    /**
     * Every message in the open thread, as [Turn]s, oldest first.
     *
     * Any card already expanded reuses `cards` rather than being fetched again: it is
     * already here, decoded, and asking for it twice would be a second round trip for
     * something already sitting on screen. Everything still collapsed has so far only ever
     * been headers, so each of those costs one more fetch.
     *
     * All of them at once, rather than one after another. A twenty message thread fetched
     * in turn is twenty round trips end to end, which is the wait somebody sits through
     * before the button has even sent anything. The same rule as opening a message, where
     * the body and the parts are asked for together.
     */
    suspend fun summariseTurns(key: String, message: Summary, decrypted: Boolean = false): List<Turn> = coroutineScope {
        thread.ifEmpty { listOf(message) }.map { m ->
            async(Dispatchers.IO) {
                val server = cards[CardKey(key, m.id)]?.body?.let(::plainTextOf)
                    ?: runCatching { plainTextOf(restoredBody(key, session(key).jmap.body(m.id))) }.getOrDefault("")
                // Encrypted mail is withheld unless this is the explicit "Decrypt to summarise". See RookGate.kt.
                val text = RookGate.textFor(server, SealedView.sealed(key, m.id), SealedView.plaintext(key, m.id), decrypted)
                Turn(m.from, m.receivedAt, text)
            }
        }.awaitAll()
    }

    /** The exact bytes [runSummarise] would post, which is what the viewer shows first. */
    suspend fun summarisePacketFor(key: String, message: Summary, config: AssistantConfig, decrypted: Boolean = false): String =
        Llm.packet(config.model, Summarise.system(), Summarise.user(message.subject, summariseTurns(key, message, decrypted)))

    /** Posts a packet already agreed to, or already shown on demand, and records what it cost. */
    fun runSummarise(packet: String, config: AssistantConfig, forThread: String) {
        summarising = true
        summariseError = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val reply = Llm.ask(config, Secrets.loadNamed(Assistant.KEY), packet)
                    Assistant.record(Assistant.SUMMARISE, reply.tokensIn, reply.tokensOut, config)
                    reply
                }
            }
            summarising = false
            if (selected?.threadId != forThread) return@launch
            outcome.fold(
                onSuccess = {
                    val text = it.text.trim()
                    selected?.let { message -> accountOf(message) }?.let { account ->
                        appendRook(account, Said("assistant", text))
                    }
                },
                onFailure = {
                    val err = it.message ?: "The model could not be reached."
                    summariseError = err
                    selected?.let { message -> accountOf(message) }?.let { account ->
                        appendRook(account, Said("result", err))
                    }
                },
            )
        }
    }

    /**
     * Builds the packet for the open thread, then either shows it or sends it straight
     * away.
     *
     * [viewFirst] is the "let me look" path a reader can reach whether or not this
     * feature has already been agreed to. The button itself only takes that path before
     * the first agreement; after that it goes straight through, and looking is something
     * offered separately. See [PacketViewer].
     */
    fun summarise(message: Summary, key: String, config: AssistantConfig, viewFirst: Boolean = false, decrypted: Boolean = false) {
        if (summarising) return
        Assistant.whyNot(Assistant.SUMMARISE, config, key, currentFolderName(key))?.let {
            summariseError = it
            appendRook(key, Said("result", it))
            return
        }
        summariseError = null
        val forThread = message.threadId
        summarising = true
        scope.launch {
            val packet = withContext(Dispatchers.IO) { summarisePacketFor(key, message, config, decrypted) }
            summarising = false
            // The reader has moved on to a different conversation while this was being
            // built. An answer or a permission dialog for a thread nobody is looking at
            // is not shown.
            if (selected?.threadId != forThread) return@launch
            // Decrypted text is always shown before it goes, whatever was agreed before.
            if (viewFirst || decrypted || !Assistant.agreed(Assistant.SUMMARISE)) {
                summariseFor = forThread
                summarisePacket = packet
            } else {
                runSummarise(packet, config, forThread)
            }
        }
    }

    /** Which messages marking the conversation read applies to. See [conversationIds]. */
    fun conversationOf(message: Summary): List<String> =
        conversationIds(thread, identities[accountOf(message)].orEmpty().map { it.email }.toSet())

    fun readConversation(message: Summary, read: Boolean) {
        accountOf(message)?.let { setSeen(it, conversationOf(message).toSet(), read) }
    }

    /**
     * Files the whole conversation out of the folder on screen.
     *
     * The list is one row per conversation, so moving only the message the row happens
     * to show leaves the row there and the click looks like it did nothing. The server
     * says which messages of the thread are in this folder. A reply of yours that is
     * still in the folder is one of them. A copy that is also in Sent stays in Sent,
     * because the move takes the message out of this folder only.
     *
     * Before the thread has loaded, the rows on screen are empty. That used to file
     * nothing and say nothing. The server is asked anyway, and a backend that cannot
     * say falls back to those rows, or to this one message.
     */
    fun fileConversation(message: Summary, role: String, verb: String) {
        val key = accountOf(message) ?: return
        val flight = key + "\u0000" + message.threadId.ifBlank { message.id }
        // A second click on the same conversation while this one is in flight. Ignored,
        // rather than sent twice.
        if (!filing.add(flight)) return
        val target = folderFor(role, mailboxes[key].orEmpty())
        if (target == null) {
            filing.remove(flight)
            report(noSuchFolder(role))
            return
        }
        val folder = sourceFolder(key)
        scope.launch {
            try {
                /*
                 * Archiving by year or month puts it in a subfolder of Archive, made on the
                 * first message of a new period and never otherwise. A folder with fifteen
                 * years of mail in it is a folder nobody opens.
                 *
                 * If making it fails the message still goes to Archive itself. Refusing to
                 * archive because a subfolder could not be created would be the wrong way
                 * round: the filing is the point, the tidiness is not.
                 */
                val bucket = if (role == "archive") archiveBucket(message.receivedAt, Settings.archiveBy()) else null
                val into = bucket?.let { name ->
                    val existing = mailboxes[key].orEmpty()
                        .firstOrNull { it.parentId == target.id && it.name == name }
                    existing?.id ?: io { session(key).jmap.createMailbox(name, target.id) }
                        ?.also { refreshFolders(key) }
                } ?: target.id
                // Null is "cannot say". A failure is a failure, and is not then filed
                // as if the server had said the thread was empty.
                val fromServer = if (folder != null && message.threadId.isNotBlank()) {
                    val outcome = tried {
                        withContext(Dispatchers.IO) {
                            session(key).jmap.conversationIn(message.threadId, folder)
                        }
                    }
                    if (outcome.isFailure) {
                        report("That did not work.", whyFailed(outcome.exceptionOrNull()!!))
                        return@launch
                    }
                    outcome.getOrNull()
                } else {
                    null
                }
                val ids = filingIds(message.id, message.threadId, fromServer, thread.map { it.id })
                if (!sayJunk(key, ids, folder, into)) return@launch
                val moved = if (folder != null) {
                    changed(key) { session(key).jmap.moveFrom(ids, folder, into) }
                } else {
                    changed(key) { session(key).jmap.move(ids, into) }
                }
                if (moved == null) return@launch
                io { session(key).store?.forget(ids) }
                val gone = ids.toSet()
                // The list row often is not the id that was moved. Any row of this
                // conversation, on this account, is the row the click was about.
                fun leaves(row: Summary) =
                    row.sameMail(key, gone) ||
                        (message.threadId.isNotBlank() && row.threadId == message.threadId &&
                            (row.account.isBlank() || row.account == key))
                advancePast(::leaves)
                emails = emails.filterNot(::leaves)
                thread = thread.filterNot { it.id in gone }
                expanded = expanded - gone
                openedByHand = openedByHand - gone
                cards = cards.filterKeys { card -> card.account != key || card.id !in gone }
                filed = filed + gone
                // Back the same way: out of the folder they went to, into the one they
                // left. A plain move would drop a copy that was also in Sent.
                undo = folder?.let { Undoable(listOf(Move(key, ids, it, into)), verb) }
            } finally {
                filing.remove(flight)
            }
        }
    }

    /**
     * Mute: archive what is there and quieten what comes next.
     *
     * Both halves, because either on its own is half a feature. Archiving without
     * remembering is the same conversation back in the inbox in ten minutes, and
     * remembering without archiving leaves the pile that prompted it.
     */
    fun muteConversation(message: Summary, on: Boolean) {
        val key = accountOf(message) ?: return
        val backend = session(key).jmap
        if (!backend.hasSieve()) {
            Muted.set(key, message.threadId, on)
            conversationMuted = on
            if (on) fileConversation(message, "archive", "Muted")
            return
        }
        // On the server, so it holds with Rampart closed. See [ServerMute].
        scope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching { ServerMute.set(backend, key, message, on, mailboxes[key].orEmpty()) }
            }
            done.exceptionOrNull()?.let {
                val what = if (on) "muted" else "unmuted"
                report("The conversation could not be $what on the server.", whyFailed(it))
                return@launch
            }
            if (!on) Muted.set(key, message.threadId, false)
            if (selected?.threadId != message.threadId) return@launch
            conversationMuted = on
            if (on) fileConversation(message, "archive", "Muted")
        }
    }

    /*
     * Joining conversations and splitting a message out of one, kept on this computer
     * because no server can hold it: see [Rethreading]. The undo strip takes either back,
     * by putting the joins as they were rather than working the change out in reverse.
     */
    fun rethread(key: String, next: Rethreading, notice: String, reopen: Boolean) {
        val before = Rethreads.of(key)
        scope.launch {
            Rethreads.save(key, { session(key).store }, next, reopen)?.let {
                report("That could not be changed on this computer.", it)
                return@launch
            }
            if (reopen) thread = emptyList()
            undo = Undoable(emptyList(), "", notice = notice, local = {
                scope.launch {
                    val failed = Rethreads.save(key, { session(key).store }, before, reopen)
                    if (failed != null) report("That could not be undone.", failed)
                    else if (reopen) thread = emptyList()
                }
            })
        }
    }

    /** The picked rows as one conversation. Refused, in one sentence, across accounts. */
    fun joinPicked() {
        val rows = threadRowsFor(emails, threadContext()).flatMap { it.rows }.filter { rowToken(it) in picked }
        val accounts = rows.mapNotNull { accountOf(it) }.distinct()
        if (accounts.size > 1) {
            report(JOIN_ACROSS_ACCOUNTS)
            return
        }
        val key = accounts.singleOrNull() ?: return
        val threads = rows.map { threadKey(it) }.distinct()
        val next = Rethreads.of(key).joined(threads, rows.map { it.id })
        if (next == Rethreads.of(key)) {
            report("Those are already one conversation.")
            return
        }
        picked = emptySet()
        rethread(key, next, "${rows.size} conversations joined into one.", reopen = false)
    }

    /** [message] in a conversation of its own, from the reader's conversation menu. */
    fun splitOut(message: Summary) {
        val key = accountOf(message) ?: return
        rethread(key, Rethreads.of(key).split(message.id), "1 message split into its own conversation.", reopen = true)
    }

    /*
     * The same operations, reachable without opening the message first.
     *
     * Reply and forward have to fetch that message's body before they can quote it: the
     * body held in state belongs to whatever is open, which on a right-click is usually
     * something else. Quoting the wrong message is worse than a moment's wait.
     */
    /*
     * Carries out a folder job once the question attached to it has been answered.
     *
     * Every one of these re-reads the account's folders rather than editing the list here.
     * A sidebar built from what we think we did drifts from the server the first time a
     * rename is refused, and a folder tree that is subtly wrong is worse than a slow one.
     */
    fun doFolderJob(ask: FolderAsk, answer: String) {
        scope.launch {
            folderError = try {
                withContext(Dispatchers.IO) {
                    val jmap = session(ask.account).jmap
                    when (ask.job) {
                        FolderJob.CreateInside -> jmap.createMailbox(answer, ask.mailbox?.id)
                        FolderJob.Rename -> jmap.updateMailbox(ask.mailbox!!.id, name = answer)
                        FolderJob.ToTop -> jmap.updateMailbox(ask.mailbox!!.id, reparent = true)
                        FolderJob.Delete -> jmap.destroyMailbox(ask.mailbox!!.id)
                        // Handled before a dialog is ever shown, so it never reaches here.
                        FolderJob.Export, FolderJob.Import, FolderJob.Share -> Unit
                    }
                }
                null
            } catch (e: Exception) {
                whyFailed(e).ifBlank { "The server would not do that." }
            }
            refreshFolders(ask.account)
            // A folder that was open and is now gone leaves the list pointing at nothing.
            if (ask.job == FolderJob.Delete && here?.second?.id == ask.mailbox?.id) {
                here = mailboxes[ask.account].orEmpty().firstOrNull { it.role == "inbox" }
                    ?.let { ask.account to it }
                reload()
            }
            folderAsk = null
        }
    }

    /*
     * Reads the account's filters when the settings pane opens, not before.
     *
     * Two calls and a blob download, so it is not worth doing on every start for a screen
     * most people open rarely. Which script: the active one if there is one, because that
     * is the one actually running, otherwise the one a builder made, otherwise none and we
     * write a new one on the first save.
     */
    suspend fun loadFilters(key: String) {
        filtersError = null
        val jmap = session(key).jmap
        // Deliberately not through `io`: that puts a failure in the window's error bar,
        // and a settings screen that could not read its own data has somewhere of its own
        // to say so. The bar is for things that happened to the mail.
        suspend fun <T> quietly(block: () -> T): T? =
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { filtersError = it.message ?: it.toString() }
                .getOrNull()

        filtersSupported = quietly { jmap.hasSieve() } == true
        if (!filtersSupported) {
            filters = Script(emptyList())
            return
        }
        val chosen = theOneRunning(quietly { jmap.sieveScripts() }.orEmpty())
        filterScript = chosen
        val script = if (chosen == null) Script(emptyList()) else scriptOf(quietly { jmap.sieveText(chosen) }.orEmpty())
        filters = script
        // A machine that has never had the global set takes what the server is already
        // running as the set, rather than showing nothing and then deleting it.
        Filters.adopt(script)
        globalFilters = Filters.read()
    }

    /*
     * Read when the settings pane opens, and only then. Two calls and a blob download is
     * not worth doing on every start for a screen most people open rarely.
     */
    LaunchedEffect(settingsOpen, filterAccount) {
        val key = filterAccount ?: return@LaunchedEffect
        if (!settingsOpen) return@LaunchedEffect
        filters = null
        runCatching { loadFilters(key) }
            .onFailure { filtersError = it.message ?: "The filters could not be read." }
    }

    fun saveFilters(key: String, next: Script) {
        filtersSaving = true
        scope.launch {
            filtersError = try {
                withContext(Dispatchers.IO) {
                    session(key).jmap.saveSieve(filterScript?.name ?: "rampart", sieveOf(next), filterScript)
                }
                filters = next
                null
            } catch (e: Exception) {
                whyFailed(e).ifBlank { "The server would not take those filters." }
            }
            filtersSaving = false
            // Re-read rather than trust: the server rewrites nothing, but an activation
            // that half worked should show as what is actually there.
            if (filtersError == null) loadFilters(key)
        }
    }

    /**
     * Save, pressed on one of Rook's filter cards: the only path by which a filter it
     * asked for is written. The write is the Filters page's own save, for the account
     * stored on the card. The outcome goes into the transcript so Rook knows next turn.
     */
    fun saveFilterCard(key: String, number: Int) {
        val desk = filterCards[key] ?: return
        val card = desk.card(number)?.takeIf { it.status == CardStatus.WAITING } ?: return
        val here = sessions.firstOrNull { it.key == card.account }
        if (here == null) {
            filterCards = filterCards + (key to desk.start(number).finish(number, "That account is no longer signed in, so the filter was not saved."))
            appendRook(key, Said("result", "The filter \"${card.rule.name}\" was not saved: that account is no longer signed in."))
            return
        }
        filterCards = filterCards + (key to desk.start(number))
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    addFilter(here.jmap, card.account, card.rule)
                    null
                } catch (e: Exception) {
                    whyFailed(e)
                }
            }
            filterCards = filterCards + (key to ((filterCards[key] ?: FilterDesk()).finish(number, failure)))
            appendRook(key, Said(
                "result",
                if (failure == null) "Saved the filter \"${card.rule.name}\"."
                else "The filter \"${card.rule.name}\" was not saved: $failure",
            ))
            if (failure == null && settingsOpen && filterAccount == card.account) {
                runCatching { loadFilters(card.account) }
            }
        }
    }

    /** Cancel on a filter card. The rule is dropped and nothing is written. */
    fun cancelFilterCard(key: String, number: Int) {
        val desk = filterCards[key] ?: return
        val card = desk.card(number)?.takeIf { it.status == CardStatus.WAITING } ?: return
        filterCards = filterCards + (key to desk.dismiss(number))
        appendRook(key, Said("result", "The filter \"${card.rule.name}\" was not saved."))
    }

    /**
     * The person allowed a filter description out. Build the rule now, the way the Filters
     * page does the moment they press Send it, and put the card up. Still nothing is saved.
     */
    fun allowFilterWords() {
        val pending = filterConsent ?: return
        Assistant.agree(Assistant.FILTER)
        filterConsent = null
        val here = sessions.firstOrNull { it.key == pending.account }
        if (here == null) {
            appendRook(pending.account, Said("result", "That account is no longer signed in, so no filter was made."))
            return
        }
        val config = Assistant.config()
        val name = shortAccountName(here.account.name, here.account.email)
        val foldersNow = mailboxes[pending.account].orEmpty().map { it.name }
        val desk = filterCards[pending.account] ?: FilterDesk()
        val tools = filterToolsFor(
            here.jmap,
            pending.account,
            name,
            foldersNow,
            config,
            desk.nextNumber,
            desk.waiting.size,
        )
        chatThinking = true
        scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    try {
                        tools.offer(pending.words)
                    } catch (e: Exception) {
                        whyFailed(e)
                    }
                }
                appendRook(pending.account, Said("result", answer))
                filterCards = filterCards + (pending.account to ((filterCards[pending.account] ?: FilterDesk()).add(pending.account, tools.proposed)))
                tools.consent?.let { again ->
                    if (filterConsent == null) filterConsent = again.copy(account = pending.account)
                }
            } finally {
                chatThinking = false
            }
        }
    }

    fun createFilterFolder(name: String) {
        val targets = sessions.filter { session ->
            (filterAccount == null || session.key == filterAccount) &&
                mailboxes[session.key].orEmpty().none { it.name.equals(name, ignoreCase = true) }
        }
        filtersSaving = true
        scope.launch {
            filtersError = runCatching {
                targets.forEach { target ->
                    withContext(Dispatchers.IO) { target.jmap.createMailbox(name) }
                    refreshFolders(target.key)
                }
            }.exceptionOrNull()?.let(::whyFailed)
            filtersSaving = false
        }
    }

    /*
     * Saves the set kept for every account, and puts it on every account's server.
     *
     * Written here first and pushed after, so the record of what the set is survives an
     * account being offline. Every account is visited even when the set was only switched
     * off for one of them: turning it off has to remove the rules that were pushed last
     * time, and a screen switch that changes nothing on the server would be a lie.
     *
     * An account that refuses is named rather than swallowed, and the others still get it.
     * Failing them all because one server was down is the worse outcome.
     */
    fun saveGlobalFilters(next: GlobalFilters) {
        globalFilters = next
        Filters.write(next)
        filtersSaving = true
        val open = sessions.toList()
        scope.launch {
            val refused = withContext(Dispatchers.IO) {
                open.filter { runCatching { pushGlobals(it.jmap, next.forAccount(it.key)) }.getOrDefault(false).not() }
            }
            filtersSaving = false
            filtersError = if (refused.isEmpty()) {
                null
            } else {
                "Saved here, but not on " + refused.joinToString(", ") { it.account.email } +
                    ". That server either does not keep rules or has a filter script Rampart did not write."
            }
            // What is on the screen for one account has just changed underneath it.
            filterAccount?.let { runCatching { loadFilters(it) } }
        }
    }

    /**
     * A new message with the original attached as a .eml file, and nothing quoted.
     *
     * The file is written the same way [openAttached] writes one, uploaded through the
     * same call the Attach button uses, and removed whether the upload worked or not.
     */
    fun forwardAsFile(message: Summary) {
        val key = accountOf(message) ?: return
        sendError = null; sendDetail = null
        scope.launch {
            val letter = cardFor(message).body ?: io { restoredBody(key, session(key).jmap.body(message.id)) }
            val mine = identities[key].orEmpty().map { it.email }
            val from = identityFor(letter, mine, mine.firstOrNull().orEmpty())
            val draft = io {
                val raw = session(key).jmap.raw(message.id) ?: return@io null
                val dir = Files.createTempDirectory("rampart-attached")
                try {
                    val name = forwardEmlName(message.subject, message.receivedAt)
                    val path = dir.resolve(name)
                    Files.writeString(path, raw)
                    val uploaded = session(key).jmap.upload(path)
                    forwardAsAttachment(message, from, uploaded.copy(type = "message/rfc822"), letter)
                } finally {
                    runCatching { dir.toFile().deleteRecursively() }
                }
            }
            if (draft != null) write(key, draft)
        }
    }

    val rowActions = RowActions(
        snooze = { message, until -> snooze(message, until) },
        followUp = { message -> followUps.ask(message) },
        reply = { message, all ->
            val key = accountOf(message)
            if (key != null) {
                scope.launch {
                    val ours = identities[key].orEmpty().map { it.email }
                    val text = io { restoredBody(key, session(key).jmap.body(message.id)) }
                    write(key, replyTo(message, text, writingIdentity(text, key), all, ours.toSet()))
                }
            }
        },
        forward = { message ->
            val key = accountOf(message)
            if (key != null) {
                scope.launch {
                    // This message's own body, not whatever is open in the reader. Forwarding
                    // from a row while looking at something else would otherwise answer as
                    // the identity the other message was addressed to.
                    val forwarded = io { restoredBody(key, session(key).jmap.body(message.id)) }
                    val mine = identities[key].orEmpty().map { it.email }
                    write(key, forwardOf(message, forwarded, identityFor(forwarded, mine, mine.firstOrNull().orEmpty())))
                }
            }
        },
        forwardFile = { message -> forwardAsFile(message) },
        archive = { message -> fileConversation(message, "archive", pastTense("archive")) },
        junk = { message -> fileConversation(message, "junk", pastTense("junk")) },
        notJunk = { message -> moveThis(message, "inbox")?.invoke() },
        isJunk = ::inJunk,
        trash = { message -> fileConversation(message, "trash", pastTense("trash")) },
        star = ::starOne,
        markRead = ::markRead,
        filter = { message -> filterFor = message },
        sendScheduled = ::sendScheduledNow,
        cancelScheduled = ::cancelScheduled,
        // The Move hover button, the same move and the same folders as the reader's Move.
        moveInto = { message, into ->
            accountOf(message)?.let { key ->
                val from = sourceFolder(key)
                scope.launch { carryOut(key, message, into, from, "Moved") }
            }
        },
        folders = { message ->
            accountOf(message)?.let { key ->
                mailboxes[key].orEmpty().filter { it.id != sourceFolder(key) && it.role !in MOVE_COVERED }
            }.orEmpty()
        },
        markIds = { message, ids, read -> accountOf(message)?.let { setSeen(it, ids, read) } },
        starIds = { message, ids, on -> accountOf(message)?.let { starMany(it, ids, on) } },
        alwaysFocused = { message ->
            val email = message.fromEmail.trim().lowercase()
            if (email.isNotEmpty()) {
                Settings.setFocusOverride(email, "focused")
                focusOverrides = Settings.focusOverrides()
            }
        },
        alwaysOther = { message ->
            val email = message.fromEmail.trim().lowercase()
            if (email.isNotEmpty()) {
                Settings.setFocusOverride(email, "other")
                focusOverrides = Settings.focusOverrides()
            }
        },
    )

    /**
     * What can be done to [message], the buttons on its own card in the reading pane.
     *
     * A function rather than a single value computed for whichever message is [selected],
     * because expanding never swaps: several cards can be open at once, each archiving,
     * starring or replying as itself rather than as whichever one was clicked from the list.
     */
    fun actionsFor(message: Summary): MessageActions {
        if (accountOf(message) == null) return MessageActions()
        /*
         * Spam and Not spam are the same move in opposite directions, and only one of them
         * is ever the right thing to offer. A message in Junk needs a way out, and that way
         * out is what was missing: there was no button, no menu entry and no shortcut, so
         * anything the filter got wrong stayed wrong.
         *
         * Moving it back is also how the server learns. Stalwart trains its classifier on
         * exactly this move, so there is nothing else to call: filing it in the inbox is
         * both the fix and the correction.
         */
        val junked = inJunk(message)
        val boxes = mailboxes[accountOf(message)!!].orEmpty()
        return MessageActions(
            archive = { fileConversation(message, "archive", pastTense("archive")) },
            snooze = { until -> snooze(message, until) },
            followUp = { followUps.ask(message) },
            trash = { fileConversation(message, "trash", pastTense("trash")) },
            junk = if (junked) null else ({ fileConversation(message, "junk", pastTense("junk")) }),
            notJunk = if (junked) moveThis(message, "inbox") else null,
            star = { starOne(message) },
            // Unread is how most people say "come back to this", and it was reachable
            // only by right-clicking the row the message was opened from.
            /*
             * Always offered, and it says which way it goes.
             *
             * It used to appear only on a message the server thought had been read, which
             * is a button that is there or not there depending on something nobody is
             * looking at. With the mark-as-read delay set to never, the case it was written
             * for, the message never becomes read and the button never appeared at all.
             */
            markUnread = { markRead(message, !message.seen) },
            moveInto = { into ->
                val key = accountOf(message) ?: return@MessageActions
                val from = sourceFolder(key)
                scope.launch { carryOut(key, message, into, from, "Moved") }
                Unit
            },
            // Everything except where it already is, and except the three that have a
            // button of their own: offering Archive twice is how a menu stops being read.
            folders = boxes.filter { it.id != accountOf(message)?.let(::sourceFolder) && it.role !in MOVE_COVERED },
            // Only where there is a conversation to act on. One message is not one.
            conversation = thread.takeIf { it.size > 1 }?.let { all ->
                ConversationActions(
                    count = all.size,
                    unread = all.count { !it.seen },
                    muted = conversationMuted,
                    muteNote = muteNote(
                        canServer = sessions.firstOrNull { it.key == accountOf(message) }?.jmap?.hasSieve() == true,
                        onServer = ServerMute.knownMuted(accountOf(message).orEmpty(), message.threadId),
                        muted = conversationMuted,
                    ),
                    onRead = { read -> readConversation(message, read) },
                    onArchive = { fileConversation(message, "archive", "Archived") },
                    onTrash = { fileConversation(message, "trash", "Deleted") },
                    onMute = { on -> muteConversation(message, on) },
                    onSplit = { splitOut(message) },
                )
            },
        )
    }

    // Keyboard shortcuts and the command palette act on whichever message is primary, the
    // one clicked from the list, the same as before there was more than one card to choose
    // between.
    val actions = selected?.let(::actionsFor) ?: MessageActions()

    /** Shows [message] as it arrived, or puts it away again. Its own card, not a shared one. */
    fun toggleSource(message: Summary) {
        val key = accountOf(message) ?: return
        if (cardFor(message).source != null) {
            updateCard(key, message.id) { copy(source = null) }
            return
        }
        updateCard(key, message.id) { copy(source = "Fetching the original...") }
        scope.launch {
            val raw = io { session(key).jmap.raw(message.id) }
                ?: "The server would not hand over the original of this message."
            updateCard(key, message.id) { copy(source = raw) }
        }
    }

    /**
     * A message/rfc822 attachment, fetched the same way Save fetches a file, then read
     * as a message. The file lands in a temporary folder and is removed: opening it is
     * not the same as saving it into Downloads.
     */
    fun openAttached(key: String, attachment: Attachment) {
        scope.launch {
            val parsed = io {
                val dir = Files.createTempDirectory("rampart-attached")
                try {
                    val path = session(key).fetchAttachment(attachment, dir)
                    readAttachedMessage(Files.readAllBytes(path))
                } finally {
                    runCatching { dir.toFile().deleteRecursively() }
                }
            }
            if (parsed != null) {
                attachedSaved = null
                attached = parsed
            }
        }
    }

    /**
     * A winmail.dat, fetched the same way Save fetches a file, then read as the files
     * Outlook packed into it. When it cannot be read, the wrapper is saved, which is
     * what Save already did, and a sentence says so.
     */
    fun openTnef(key: String, messageId: String, attachment: Attachment) {
        scope.launch {
            val fetched = io {
                val dir = Files.createTempDirectory("rampart-tnef")
                try {
                    val path = session(key).fetchAttachment(attachment, dir)
                    val bytes = Files.readAllBytes(path)
                    val contents = readTnef(bytes)
                    if (contents != null) contents to null
                    else null to Files.write(uniqueIn(downloadsFolder(), attachment.name), bytes)
                } finally {
                    runCatching { dir.toFile().deleteRecursively() }
                }
            } ?: return@launch
            val (contents, saved) = fetched
            if (contents != null) {
                tnef = contents
                tnefName = attachment.name
                tnefSaved = null
            } else if (saved != null) {
                updateCard(key, messageId) { copy(saved = saved.toString()) }
                report(TNEF_UNREADABLE)
            }
        }
    }

    /** The encryption and signature line above one message, and its decryption. See CryptoReader.kt. */
    @Composable
    fun sealedPanelFor(message: Summary) {
        val key = accountOf(message)
        val card = cardFor(message)
        val open = sessions.firstOrNull { it.key == key }
        SealedPanel(
            account = key,
            summary = message,
            body = card.body,
            attachments = card.attachments,
            emailBlobId = card.emailBlobId,
            backend = open?.jmap,
            store = { open?.store },
            known = knownDomains(),
            dark = darkMail,
            pageBackground = pageBackground,
            pageText = pageText,
            onDecryptToSummarise = key?.let { k ->
                val cfg = Assistant.config()
                if (cfg.mode == AssistantMode.OFF) {
                    null
                } else {
                    {
                        AppBar.show(SideTool.ROOK)
                        summarise(message, k, cfg, viewFirst = true, decrypted = true)
                    }
                }
            },
        )
    }

    /**
     * The same Reply and Forward the card's toolbar runs, so the pinned bar cannot
     * grow a second copy of that path.
     */
    fun replyFromCard(cardSummary: Summary, all: Boolean) {
        sendError = null; sendDetail = null
        val account = accountOf(cardSummary) ?: writingAccount()
        val body = cardFor(cardSummary).body
        val mine = identities[account].orEmpty().map { it.email }.toSet()
        write(account, replyTo(cardSummary, body, writingIdentity(body, account), all, mine))
    }

    fun forwardFromCard(cardSummary: Summary) {
        sendError = null; sendDetail = null
        val account = accountOf(cardSummary) ?: writingAccount()
        val body = cardFor(cardSummary).body
        write(account, forwardOf(cardSummary, body, writingIdentity(body, account)))
    }

    /**
     * Who the pinned bar answers: the newest message somebody else wrote, or the
     * newest message when the conversation is only your own mail.
     */
    fun replyBarTarget(messages: List<Summary>): Summary? = messages.lastOrNull { message ->
        identities[accountOf(message)].orEmpty().none { it.email.equals(message.fromEmail, ignoreCase = true) }
    } ?: messages.lastOrNull()

    /**
     * One message's card: its own header, avatar, body and action row, exactly what the
     * reading pane has always drawn for whichever message was open, called once per
     * expanded card rather than once for the whole pane.
     *
     * [cardSummary] is which message, never [selected]: several cards can be expanded at
     * once, and each answers Reply, Archive, Tag and the rest as itself. [invitation]
     * and [answering] stay tied to whichever message the conversation was
     * opened on, because a meeting invitation and a thread summary are read once per
     * conversation, not once per message in it.
     */
    @Composable
    fun renderCard(
        cardSummary: Summary,
        showSubject: Boolean,
        onHeaderClick: (() -> Unit)?,
        externalScroll: ScrollState?,
    ) {
        val card = cardFor(cardSummary)
        val key = accountOf(cardSummary)
        val ours = identities[key].orEmpty().map { it.email }.toSet()
        val isPrimary = selected?.sameMail(cardSummary) == true
        val remote = showRemoteFor(cardSummary)
        // Show images rebuilds the page off this thread. The first build happened in
        // loadCard, with whatever the reader had already allowed. The account is part
        // of the key so two messages that share an id do not reuse each other's page.
        // Dark and light already share one document. A hybrid's tint is written into
        // the light page, so a new theme is a new page.
        LaunchedEffect(key, cardSummary.id, remote, pageBackground, pageText) {
            val current = cardFor(cardSummary)
            val body = current.body ?: return@LaunchedEffect
            if (
                current.reading != null && current.pageRemote == remote &&
                plainPageMatches(current.reading, pageBackground, pageText)
            ) return@LaunchedEffect
            val reading = withContext(Dispatchers.Default) {
                prepareReading(
                    cardSummary.fromEmail,
                    cardSummary.from,
                    body,
                    current.attachments,
                    current.imageBytes,
                    remote,
                    darkMail,
                    knownDomains(),
                    pageBackground,
                    pageText,
                )
            }
            if (key != null) updateCard(key, cardSummary.id) {
                copy(reading = reading, pageRemote = remote, images = reading.images.ifEmpty { images })
            }
        }
        // A message the reader decrypted is drawn from its decrypted copy. See CryptoReader.kt.
        val unsealed = SealedView.of(key, cardSummary.id)
        Message(
            summary = cardSummary,
            body = unsealed?.body ?: card.body,
            accountKey = key.orEmpty(),
            sentBy = if (ours.any { it.equals(cardSummary.fromEmail, ignoreCase = true) }) sessions.firstOrNull { it.key == key }?.jmap else null,
            tracking = key?.let { account ->
                session(account).store?.tracking()?.filter { it.first.messageId in card.body?.messageId.orEmpty() }
            }.orEmpty(),
            onReply = { all -> replyFromCard(cardSummary, all) },
            replyAll = hasOtherRecipients(cardSummary, card.body, ours),
            onForward = { forwardFromCard(cardSummary) },
            onForwardFile = { forwardAsFile(cardSummary) },
            onLink = { confirm = it },
            invitation = if (isPrimary) invitation else null,
            /*
             * Built from the address book, which already holds everyone corresponded with.
             * A lookalike of a household name is caught by the built-in list; a lookalike
             * of the client you invoice every month is only catchable from what this
             * particular person's mail actually looks like.
             */
            knownDomains = remember(books, sessions) {
                books.values.flatten().map { domainOf(it.email) }.filter { it.isNotBlank() }.toSet()
            },
            answering = if (isPrimary) answering else null,
            onAnswer = ::answerInvitation,
            invitationContext = if (isPrimary && key != null) {
                InvitationContext(sessions.firstOrNull { it.key == key }?.jmap, key, calendarPartIn(card.attachments), ours.toList())
            } else {
                null
            },
            addToCalendar = if (isPrimary && key != null) {
                sessions.firstOrNull { it.key == key }?.let {
                    MailCalendar(it.jmap, key, shortAccountName(it.account.name, it.account.email), currentFolderName(key))
                }
            } else {
                null
            },
            me = writingIdentity(card.body),
            showRemote = showRemoteFor(cardSummary),
            unsubscribed = card.unsubscribed,
            inContacts = contacts.any { it.first.emails.any { e -> e.equals(cardSummary.fromEmail, ignoreCase = true) } },
            onAddContact = if (sessions.any { it.jmap.hasContacts() }) {
                { message ->
                    val bookKey = accountOf(message)
                    if (bookKey != null) {
                        scope.launch {
                            contactsError = null
                            try {
                                withContext(Dispatchers.IO) {
                                    val jmap = session(bookKey).jmap
                                    val known = contactBooks.ifEmpty { jmap.addressBooks() }
                                    val book = known.firstOrNull { it.isDefault } ?: known.firstOrNull()
                                    jmap.saveContact(
                                        Contact(
                                            name = message.from.trim(),
                                            emails = listOf(message.fromEmail),
                                            bookIds = listOfNotNull(book?.id),
                                        ),
                                    )
                                    val fetched = jmap.contacts()
                                    contacts = fetched
                                    senderPhotos = photosFrom(jmap, fetched)
                                }
                            } catch (e: Exception) {
                                contactsError = whyFailed(e)
                            }
                        }
                    }
                }
            } else {
                null
            },
            onReceipt = { to ->
                sendError = null; sendDetail = null
                val account = key ?: writingAccount()
                val from = identities[account].orEmpty().firstOrNull()?.email.orEmpty()
                write(
                    account,
                    Draft(
                        from = from,
                        to = to,
                        subject = receiptSubject(cardSummary.subject),
                        body = receiptBody(
                            cardSummary.subject,
                            java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME),
                            from,
                        ),
                        inReplyTo = card.body?.messageId?.firstOrNull(),
                        references = card.body?.references.orEmpty() + card.body?.messageId.orEmpty(),
                        replying = true,
                    ),
                )
            },
            onUnsubscribe = { off ->
                when {
                    // One-click is the only route that finishes without leaving Rampart,
                    // and it is the only one the sender promised would work that way.
                    off.oneClick && off.url != null -> {
                        if (key != null) updateCard(key, cardSummary.id) { copy(unsubscribed = UNSUBSCRIBE_PENDING) }
                        scope.launch {
                            val done = withContext(Dispatchers.IO) { oneClickPost(off.url) }
                            if (key != null) updateCard(key, cardSummary.id) {
                                copy(
                                    unsubscribed = if (done) {
                                        UNSUBSCRIBE_DONE
                                    } else {
                                        // Not an error worth a dialog: the link is still there.
                                        UNSUBSCRIBE_FAILED
                                    },
                                )
                            }
                        }
                    }
                    // A page to open is a page somebody should see before it acts, so it
                    // goes through the same confirmation as any other link in a message.
                    off.url != null -> confirm = off.url
                    off.mailto != null -> {
                        sendError = null; sendDetail = null
                        val account = key ?: writingAccount()
                        write(
                            account,
                            Draft(
                                from = identities[account].orEmpty().firstOrNull()?.email.orEmpty(),
                                to = off.mailto,
                                subject = off.mailtoSubject ?: "unsubscribe",
                            ),
                        )
                    }
                }
            },
            onShowImages = { always ->
                if (always) {
                    val senderKey = imageSenderKey(cardSummary.fromEmail)
                    Settings.allowImagesFrom(senderKey)
                    allowedSenders = allowedSenders + senderKey
                }
                shownOnce = shownOnce + rowToken(cardSummary)
            },
            bodyError = card.bodyError,
            images = card.images,
            imageBytes = card.imageBytes,
            reading = unsealed?.reading ?: card.reading,
            paneHeight = windowSize().height.value.toInt(),
            actions = actionsFor(cardSummary),
            // A suggestion opens as an ordinary reply with the words above the quote, unsent.
            suggest = if (isPrimary && key != null) {
                suggestReplies.actions(
                    threadId = cardSummary.threadId.ifEmpty { cardSummary.id },
                    account = key,
                    folder = currentFolderName(key),
                    subject = cardSummary.subject,
                    turns = { summariseTurns(key, cardSummary) },
                    backend = sessions.firstOrNull { it.key == key }?.jmap,
                    onPick = { words ->
                        sendError = null; sendDetail = null
                        val all = bareReplyAll(Settings.defaultReplyAll(), hasOtherRecipients(cardSummary, card.body, ours))
                        val reply = replyTo(cardSummary, card.body, writingIdentity(card.body, key), all, ours)
                        write(key, reply.copy(body = words.trim() + reply.body))
                    },
                )
            } else {
                null
            },
            attachments = card.attachments,
            savedTo = card.saved,
            source = card.source,
            onSource = { toggleSource(cardSummary) },
            paper = CardKey(key.orEmpty(), cardSummary.id) in paperMessages,
            messageMode = messageMode,
            messageScale = messageScale,
            // Per message rather than a setting: it is a look at this one, and having
            // to turn it back off in settings would make it a mode instead.
            onPaper = { on ->
                val slot = CardKey(key.orEmpty(), cardSummary.id)
                paperMessages = if (on) paperMessages + slot else paperMessages - slot
            },
            onSaveSource = {
                val text = card.source
                if (text != null) {
                    scope.launch {
                        val path = io {
                            val at = downloadsFolder().resolve(emlName(cardSummary.subject, cardSummary.receivedAt))
                            java.nio.file.Files.writeString(at, text)
                            at
                        }
                        if (path != null && key != null) updateCard(key, cardSummary.id) { copy(saved = path.toString()) }
                    }
                }
            },
            onTag = { keyword, on ->
                if (key != null) {
                    // Same bargain as the star: it moves now, and moves back if the
                    // server says no. Nobody waits on a round trip to see a label.
                    fun put(value: Boolean) {
                        val keywords = if (value) cardSummary.keywords + keyword else cardSummary.keywords - keyword
                        emails = emails.map { if (it.sameMail(cardSummary)) it.copy(keywords = keywords) else it }
                        thread = thread.map { if (it.id == cardSummary.id) it.copy(keywords = keywords) else it }
                        if (selected?.sameMail(cardSummary) == true) selected = selected?.copy(keywords = keywords)
                    }
                    put(on)
                    scope.launch {
                        if (changed(key) { session(key).jmap.setKeyword(listOf(cardSummary.id), keyword, on) } == null) put(!on)
                    }
                }
            },
            onTyping = { typing = it },
            onDownload = { attachment ->
                if (key != null) {
                    scope.launch {
                        val landed = io {
                            val folder = downloadsFolder()
                            session(key).fetchAttachment(attachment, folder)
                        }
                        if (landed != null) updateCard(key, cardSummary.id) { copy(saved = landed.toString()) }
                    }
                }
            },
            onDragFile = if (key == null) null else { attachment ->
                materializeAttachment { dir -> session(key).fetchAttachment(attachment, dir) }
            },
            onDragFailed = { report("That file could not be dragged out.", it) },
            saveToFiles = key?.let { filesOf(session(it).jmap) },
            onOpenMessage = { attachment ->
                if (key != null) openAttached(key, attachment)
            },
            onOpenTnef = { attachment ->
                if (key != null) openTnef(key, cardSummary.id, attachment)
            },
            onLoadImage = { attachment ->
                if (key == null) null
                else io {
                    val bytes = session(key).jmap.blob(attachment) ?: return@io null
                    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
                }
            },
            onLoadBytes = { attachment ->
                if (key == null) null
                else io { session(key).jmap.blob(attachment) }
            },
            showSubject = showSubject,
            onHeaderClick = onHeaderClick,
            onPerson = { name, address -> openPerson(name, address) },
            externalScroll = externalScroll,
        )
    }

    /** Opens [role] on the account whose folder is showing, or the first that has one. */
    fun goTo(role: String) {
        val key = here?.first?.takeIf { it != ALL_ACCOUNTS } ?: sessions.firstOrNull()?.key ?: return
        folderFor(role, mailboxes[key].orEmpty())?.let { here = key to it }
    }

    /**
     * What a command from the palette does. The keyboard shortcuts call the same things,
     * so the two cannot drift into meaning different things by the same name.
     */
    fun run(id: String) {
        val account = writingAccount()
        val ours = identities[account].orEmpty().map { it.email }.toSet()
        val from = ours.firstOrNull().orEmpty()
        when (id) {
            "compose" -> { sendError = null; sendDetail = null; write(account, Draft(from = from)) }
            "reply" -> selected?.let {
                val body = cardFor(it).body
                val all = bareReplyAll(Settings.defaultReplyAll(), hasOtherRecipients(it, body, ours))
                write(account, replyTo(it, body, writingIdentity(body, account), all, ours))
            }
            "reply-all" -> selected?.let {
                write(account, replyTo(it, cardFor(it).body, writingIdentity(cardFor(it).body, account), true, ours))
            }
            "forward" -> selected?.let {
                write(account, forwardOf(it, cardFor(it).body, writingIdentity(cardFor(it).body, account)))
            }
            "forward-file" -> selected?.let { forwardAsFile(it) }
            "archive" -> actions.archive?.invoke()
            "trash" -> actions.trash?.invoke()
            // One command, and it does whichever of the two is the one on offer, so the
            // same key both files spam and rescues it depending on where you are.
            "junk" -> (actions.junk ?: actions.notJunk)?.invoke()
            "star" -> actions.star?.invoke()
            "read" -> selected?.let { if (!it.seen) markRead(it, true) }
            "source" -> selected?.let { toggleSource(it) }
            "search" -> searchField.requestFocus()
            "refresh" -> scope.launch { refreshNow() }
            "next" -> emails.indexOfFirst { selected?.sameMail(it) == true }
                .let { if (emails.isNotEmpty()) selected = emails[nextIndex(it, emails.size, 1)] }
            "previous" -> emails.indexOfFirst { selected?.sameMail(it) == true }
                .let { if (emails.isNotEmpty()) selected = emails[nextIndex(it, emails.size, -1)] }
            "go-unified" -> if (sessions.size > 1) here = ALL_ACCOUNTS to allInboxes(0)
            "go-inbox" -> goTo("inbox")
            "go-archive" -> goTo("archive")
            "go-sent" -> goTo("sent")
            "go-drafts" -> goTo("drafts")
            // A top level folder, on whichever account is showing. The same dialog the
            // right-click menu opens, with nothing to sit inside.
            "unread-only" -> {
                quick = quick.copy(unread = !quick.unread)
                selected = null
                scope.launch { reload() }
            }
            "new-folder" -> {
                val key = here?.first?.takeIf { it != ALL_ACCOUNTS } ?: sessions.firstOrNull()?.key
                if (key != null) folderAsk = FolderAsk(key, null, FolderJob.CreateInside)
            }
            "settings" -> { settingsOpen = true; contactsOpen = false; calendarOpen = false }
            "contacts" -> { contactsOpen = true; settingsOpen = false; dashboardOpen = false; calendarOpen = false }
            "dashboard" -> { dashboardOpen = true; settingsOpen = false; contactsOpen = false; calendarOpen = false }
            "shortcuts" -> showShortcuts = true
            "calendar" -> { calendarOpen = true; settingsOpen = false; dashboardOpen = false; contactsOpen = false }
            // Rook and the other panels beside the mail, the same toggle their button and
            // their Ctrl key are.
            "assistant", "side-calendar", "side-contacts", "side-files", "side-tasks" -> SideTool.byCommand(id)?.let { AppBar.toggle(it) }
            // Only where there is a conversation. Muting one message is not a thing, and a
            // command that quietly does nothing is worse than one that is not offered.
            "mute" -> selected?.takeIf { thread.size > 1 }?.let { muteConversation(it, !conversationMuted) }
        }
    }

    /**
     * The shortcuts, and the one rule that makes them safe: a bare letter does nothing
     * while the search box has focus, or a reply would become a search for "r".
     */
    fun shortcut(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // Before the typing guard: whatever has focus, this is the way out of the overlay.
        if (showShortcuts) {
            showShortcuts = false
            return true
        }
        // A forwarded message is on top of the mailbox. Keys belong to it, and Esc is
        // the way out, the same as the other overlays.
        if (attached != null) {
            if (event.key == Key.Escape) {
                attached = null
                attachedSaved = null
            }
            return true
        }
        if (event.isCtrlPressed && event.key == Key.Comma) {
            settingsOpen = true
            return true
        }
        // Above the modifier guard below, because the modifier is what makes it this.
        if (event.isCtrlPressed && event.key == Key.K) {
            showPalette = true
            return true
        }
        // Ctrl and a digit opens a panel from the app bar. Also above the typing guard: no
        // text field does anything with it, and a panel is often wanted mid-sentence.
        if (AppBar.key(event)) return true
        // Below the typing guard: in a text field Shift+/ is a question mark, and taking it
        // there made "?" impossible to type in Rook or a search. Shift is what makes it a
        // question mark on most layouts, so the search key below has to say it does not
        // want one or Shift+/ lands in the search box instead of the list.
        if (typing) return false
        // Alt+Left is Back in the focused layout, the same step as the button on the
        // message. Anywhere else the key is left alone, because it was not ours before.
        if (
            event.isAltPressed && !event.isCtrlPressed && !event.isShiftPressed && !event.isMetaPressed &&
            event.key == Key.DirectionLeft &&
            showsFocusedMessage(MailLayoutState.layout, focusedNav, selected?.let(::rowToken))
        ) {
            focusedNav = focusedNav.backed()
            return true
        }
        if (event.key == Key.Slash && event.isShiftPressed) {
            showShortcuts = true
            return true
        }
        if (event.key == Key.Slash && !event.isCtrlPressed && !event.isAltPressed) {
            searchField.requestFocus()
            return true
        }
        // Everything below is a bare key. The same key held with a modifier belongs to
        // whatever the modifier means, and taking Ctrl+C to open a composer, or Ctrl+A and
        // Ctrl+F from select-all and find, is how a keyboard stops being trustworthy. One
        // guard rather than a check on each: every letter down there had the same fault.
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val current = selected
        val at = emails.indexOfFirst { current != null && it.sameMail(current) }
        fun step(delta: Int): Boolean {
            if (emails.isEmpty()) return true
            selected = emails[nextIndex(at, emails.size, delta)]
            return true
        }
        return when (event.key) {
            Key.J, Key.DirectionDown -> step(1)
            Key.K, Key.DirectionUp -> step(-1)
            Key.F5 -> { scope.launch { refreshNow() }; true }
            Key.C -> {
                sendError = null; sendDetail = null
                val account = writingAccount()
                write(account, Draft(from = identities[account].orEmpty().firstOrNull()?.email.orEmpty()))
                true
            }
            Key.U -> { run("unread-only"); true }
            Key.R -> {
                selected?.let { m ->
                    val account = writingAccount()
                    val body = cardFor(m).body
                    val ours = identities[account].orEmpty().map { it.email }.toSet()
                    val all = bareReplyAll(Settings.defaultReplyAll(), hasOtherRecipients(m, body, ours))
                    write(account, replyTo(m, body, writingIdentity(body, account), all, ours))
                }
                true
            }
            Key.F -> {
                selected?.let { m ->
                    val account = writingAccount()
                    write(account, forwardOf(m, cardFor(m).body, writingIdentity(cardFor(m).body, account)))
                }
                true
            }
            Key.A -> {
                selected?.let { m ->
                    val account = writingAccount()
                    val ours = identities[account].orEmpty().map { it.email }.toSet()
                    val body = cardFor(m).body
                    if (hasOtherRecipients(m, body, ours)) {
                        write(account, replyTo(m, body, writingIdentity(body, account), true, ours))
                    }
                }
                true
            }
            Key.S -> { actions.star?.invoke(); true }
            Key.E -> { actions.archive?.invoke(); true }
            Key.Delete, Key.Backspace -> { actions.trash?.invoke(); true }
            Key.Enter, Key.NumPadEnter -> {
                val key = selected?.let(::rowToken)
                if (MailLayoutState.layout == MailLayout.FOCUSED && key != null && focusedNav.openKey != key) {
                    focusedNav = focusedNav.opened(key)
                    true
                } else {
                    false
                }
            }
            Key.Escape -> {
                // Search first, then the focused cover, then the open message. The
                // composer sits outside this handler, and a focused search field
                // returns above, so Escape there still belongs to them. In the
                // focused layout Escape goes back to the list and leaves the row
                // selected, which is what Back does.
                if (query.isNotEmpty()) {
                    query = ""
                    showingResults = false
                    scope.launch { reload() }
                } else if (showsFocusedMessage(MailLayoutState.layout, focusedNav, selected?.let(::rowToken))) {
                    focusedNav = focusedNav.backed()
                } else if (selected != null) {
                    selected = null
                }
                true
            }
            else -> false
        }
    }

    /*
     * The composer, as a panel rather than a screen.
     *
     * It used to replace the window, which meant writing a reply and checking what was in
     * the message above it were two things you could not do at once. A local composable
     * rather than a separate one because it closes over a dozen pieces of this screen's
     * state, and threading those through a parameter list would be a worse trade than the
     * indentation.
     */
    @Composable
    fun ComposerPanel(writing: ComposeSession) {
        // The account this message was opened on. Not [writingAccount]: that follows
        // whichever row is selected, and selecting another account while writing would
        // retarget the save and wipe what has been typed.
        val key = writing.account
        val openedOn = remember(writing) { selected }
        var replyContext by remember { mutableStateOf<List<Turn>>(emptyList()) }
        LaunchedEffect(writing.draft.replying, key, openedOn?.id) {
            val message = openedOn
            if (writing.draft.replying && key != null && message != null) {
                replyContext = runCatching { summariseTurns(key, message) }.getOrDefault(emptyList())
            }
        }
        val accountIdentities = identities[key].orEmpty()
        val save: suspend (Draft) -> Unit = { draft ->
            val account = key?.let(::session)
            val drafts = folderFor("drafts", mailboxes[key].orEmpty())
            val identity = identityForDraft(accountIdentities, draft.from)
            if (account == null || drafts == null || identity == null) {
                throw JmapError("There is nowhere to save this: the account has no Drafts folder.")
            }
            writing.saves.save { replacing ->
                account.jmap.saveDraft(draft, identity, drafts.id, replacing)
            }
        }

        Composer(
            identities = accountIdentities,
            // The sign-off comes off the identity on the server, so one written in Bulwark
            // is the one used here without anything having to be imported or kept in step.
            // Computed from the account captured when writing started, so a later change
            // of selection does not hand the composer a new initial draft.
            // Remembered per session: the identities were refreshed before this session
            // was constructed, and later changes must not replace what is being typed.
            initial = remember(writing) { draftOpening(writing.draft, accountIdentities, Settings.signatureAboveQuote()) },
            sending = sending,
            error = sendError,
            errorDetail = sendDetail,
            replyContext = replyContext,
            account = key,
            folder = key?.let { currentFolderName(it) },
            schedule = sessions.firstOrNull { it.key == key }?.let {
                ScheduleSource(it.jmap, shortAccountName(it.account.name, it.account.email))
            },
            onDiscard = {
                // What was autosaved goes with it. Discard has to mean discarded, or the
                // Drafts folder fills with messages somebody decided against. Any save
                // already in flight is waited out first, because the id it comes back
                // with is the copy that now exists.
                val accountKey = key
                val saves = writing.saves
                composing = null
                sendError = null; sendDetail = null
                scope.launch {
                    yield()
                    val going = saves.awaitIdle()
                    if (accountKey != null && going != null) {
                        val applied = io {
                            runCatching { session(accountKey).jmap.destroy(listOf(going)) }.getOrNull()
                        }
                        if (applied != null) {
                            noteState(accountKey, applied)
                            // The sidebar count is the folder list, and nothing else re-reads
                            // it when a draft is destroyed. The poll would, eventually.
                            refreshFolders(accountKey)
                            emails = emails.filterNot { it.sameMail(accountKey, setOf(going)) }
                            thread = thread.filterNot { it.sameMail(accountKey, setOf(going)) }
                            if (selected?.sameMail(accountKey, setOf(going)) == true) selected = null
                        }
                    }
                }
            },
            onClose = { changed ->
                // The autosave may still be waiting out its pause, so the latest typing is
                // saved here first. The panel stays open until that lands: a close that
                // quietly failed to save would lose the very thing Close promises to keep.
                val accountKey = key
                if (changed == null) {
                    composing = null
                    sendError = null; sendDetail = null
                } else scope.launch {
                    try {
                        save(changed)
                        composing = null
                        sendError = null; sendDetail = null
                        if (accountKey != null) refreshFolders(accountKey)
                    } catch (e: Exception) {
                        sendError = "Could not save the draft, so it is still open."
                        sendDetail = e.message
                    }
                }
            },
            onAttach = { files ->
                val account = key?.let(::session)
                    ?: throw JmapError("Pick an account before attaching anything.")
                // One at a time rather than in parallel: a mail server is not a CDN, and
                // three large files racing each other is how an upload limit gets hit.
                withContext(Dispatchers.IO) { files.map { account.jmap.upload(it) } }
            },
            onDragFile = { attachment ->
                val accountKey = key
                    ?: throw JmapError("Pick an account before dragging a file out.")
                materializeAttachment { dir -> session(accountKey).jmap.download(attachment, dir) }
            },
            fromFiles = key?.let { filesOf(session(it).jmap) },
            onSave = save,
            book = withContacts(books[key].orEmpty(), contacts.map { it.first }),
            sealCards = contacts.map { it.second },
            full = composeFull,
            onFull = { composeFull = it },
            trackingReady = trackingServer.isNotBlank(),
            trackingDefaultOn = key?.let(Settings::trackNewMail) ?: Settings.trackNewMail(),
            ownAddresses = accountIdentities.map { it.email },
            signedInAddresses = identities.values.flatten().map { it.email },
            sendExtensions = sessions.firstOrNull { it.key == key }?.jmap?.submissionExtensions.orEmpty(),
            onSend = { draft ->
                val account = key?.let(::session)
                val boxes = mailboxes[key].orEmpty()
                val drafts = folderFor("drafts", boxes)
                val identity = identityForDraft(identities[key].orEmpty(), draft.from)
                when {
                    account == null -> { sendError = "Pick an account first."; sendDetail = null }
                    identity == null -> { sendError = "This account has no identity to send from."; sendDetail = null }
                    drafts == null -> {
                        sendError = "This account has no Drafts folder, and the message is written there before it is sent."
                        sendDetail = null
                    }
                    else -> scope.launch {
                        sending = true
                        sendError = null; sendDetail = null
                        // The autosave effect keys on this flag. Yield so that recomposition
                        // cancels a debounce that has not started, before anything here sends.
                        yield()
                        /*
                         * The pause before it goes, held here rather than asked of the
                         * server.
                         *
                         * This is the "undo send" window, not a schedule: a few seconds,
                         * the same length on every account regardless of what the server
                         * can do, so there is nothing for a delayed-send capability to buy
                         * here even on a server that has one. A genuinely scheduled send is
                         * a different matter, and onSchedule below asks the server to hold
                         * that one itself whenever its maxDelayedSend covers the wait, so
                         * it goes out even if Rampart is not running to fire it.
                         *
                         * Cancelling is a plain flag rather than cancelling the coroutine,
                         * because the window is the easy part and what matters is that
                         * nothing after it runs: a cancelled send must not destroy the
                         * draft it was going to replace.
                         */
                        val wait = Settings.undoSeconds()
                        if (wait > 0) {
                            undoSend = { sendCancelled = true }
                            var left = wait
                            while (left > 0 && !sendCancelled) {
                                delay(1000)
                                left--
                            }
                            undoSend = null
                            if (sendCancelled) {
                                sendCancelled = false
                                sending = false
                                return@launch
                            }
                        }
                        try {
                            // After any save that started before Send, so the id destroyed
                            // below is the copy the server actually has.
                            val latest = writing.saves.awaitIdle()
                            val result = deliverNow(
                                account,
                                draft,
                                identity,
                                drafts.id,
                                folderFor("sent", boxes)?.id,
                                latest,
                                key,
                            )
                            if (result.isSuccess) {
                                composing = null
                                result.getOrNull()?.let { report(it) }
                            } else {
                                result.exceptionOrNull()?.let { thrown ->
                                    sendError = "That message could not be sent."
                                    sendDetail = faultDetail(thrown, "That message could not be sent.")
                                }
                            }
                        } finally {
                            sending = false
                        }
                    }
                }
            },
            onSchedule = { draft, sendAt ->
                val account = key?.let(::session)
                val boxes = mailboxes[key].orEmpty()
                val drafts = folderFor("drafts", boxes)
                val identity = identityForDraft(identities[key].orEmpty(), draft.from)
                when {
                    account == null -> { sendError = "Pick an account first."; sendDetail = null }
                    identity == null -> { sendError = "This account has no identity to send from."; sendDetail = null }
                    drafts == null -> {
                        sendError = "This account has no Drafts folder, and the message is written there before it is sent."
                        sendDetail = null
                    }
                    else -> scope.launch {
                        // Same flag as Send, so an autosave already waiting out its
                        // debounce is cancelled instead of landing after this draft
                        // has been put away for later.
                        sending = true
                        sendError = null; sendDetail = null
                        yield()
                        try {
                            val secondsAhead = (sendAt - System.currentTimeMillis()) / 1000
                            // Covered by the server: submit it now, with the server asked
                            // to hold it, so it goes out on time whether or not Rampart is
                            // still running when that time comes. Not covered, whether
                            // because the server has no delayed send at all or not enough
                            // of it for this particular wait: fall back to Rampart holding
                            // the draft and firing it itself, exactly as before.
                            if (serverCanHold(account.jmap.maxDelayedSend, secondsAhead)) {
                                val latest = writing.saves.awaitIdle()
                                val result = deliverDelayed(
                                    account,
                                    draft,
                                    identity,
                                    drafts.id,
                                    folderFor("sent", boxes)?.id,
                                    latest,
                                    key,
                                    Instant.ofEpochMilli(sendAt),
                                )
                                if (result.isSuccess) {
                                    val delayed = result.getOrNull()
                                    if (delayed != null) {
                                        ScheduledSends.schedule(
                                            ScheduledSend(
                                                id = java.util.UUID.randomUUID().toString(),
                                                account = key,
                                                draftId = delayed.filedId,
                                                identityEmail = identity.email,
                                                draft = draft,
                                                sendAt = sendAt,
                                                heldSubmissionId = delayed.submissionId,
                                            ),
                                        )
                                        scheduledSends = ScheduledSends.pending()
                                        delayed.notice?.let { report(it) }
                                    }
                                    composing = null
                                } else {
                                    result.exceptionOrNull()?.let { thrown ->
                                        sendError = "Could not schedule that message."
                                        sendDetail = faultDetail(thrown, "Could not schedule that message.")
                                    }
                                }
                            } else {
                                val id = writing.saves.save { replacing ->
                                    account.jmap.saveDraft(draft, identity, drafts.id, replacing)
                                }
                                ScheduledSends.schedule(
                                    ScheduledSend(
                                        id = java.util.UUID.randomUUID().toString(),
                                        account = key,
                                        draftId = id,
                                        identityEmail = identity.email,
                                        draft = draft,
                                        sendAt = sendAt,
                                    ),
                                )
                                scheduledSends = ScheduledSends.pending()
                                composing = null
                            }
                        } catch (e: Exception) {
                            sendError = "Could not schedule that message."
                            sendDetail = faultDetail(e, "Could not schedule that message.")
                        } finally {
                            sending = false
                        }
                    }
                }
            },
            onBusy = { composeBusy = it },
        )

    }

    LaunchedEffect(Unit) { runCatching { keyboard.requestFocus() } }
    val bodyCover = remember { BodyCover() }
    CompositionLocalProvider(LocalBodyCover provides bodyCover) {
    // Any of these sits over the message. The live panel would paint through them.
    CoverBody(
        composing != null || showPalette || showShortcuts ||
            confirm != null || summarisePacket != null || actionJob.waiting != null || attached != null || folderAsk != null ||
            filterFor != null || filterConsent != null || changelogDialog != null || tnef != null,
    )
    FollowUpDialog(followUps)
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize()
            .focusRequester(keyboard)
            .focusable()
            .onPreviewKeyEvent(::shortcut),
    ) {
        if (error.isNotBlank()) {
            // Dismissible, because an error that can only be cleared by succeeding at
            // something else sits there long after it stopped being true, and then it is
            // read as the state of the app rather than as one thing that went wrong.
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FaultText(
                    error,
                    errorDetail,
                    modifier = Modifier.weight(1f),
                    titleColor = MaterialTheme.colorScheme.onErrorContainer,
                    detailColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                )
                IconButton(onClick = { report("") }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        RampartIcons.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        /*
         * The pictures, decoded once per account rather than per row.
         *
         * Contact cards and blobs already on the mail server. A card that states an
         * external URL is left alone, because fetching it would tell the sender the
         * message was opened. A sender picture, when the setting is on, comes through
         * the companion instead, and is looked up where the avatar is drawn.
         */
        // The mail fills whatever is left after the bars. A row at the full
        // window height would run under the update bar and cut the sidebar off.
        val pictureSignal = SenderPictureSignals.revision.value
        val picturePolicy = remember(pictureSignal) {
            SenderPicturePolicy(
                enabled = Settings.senderPictures() && Settings.trackingServer().isNotBlank(),
                showInJunk = Settings.senderPicturesInJunk(),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        CompositionLocalProvider(
            LocalSenderPhotos provides senderPhotos,
            LocalTagColours provides tagColours,
            LocalTintRowsByTag provides tintRows,
            LocalAccountTints provides AccountTintState.tints(sessions.map { it.key }),
            LocalLoader provides loader,
            LocalListDensity provides density,
            LocalSidebarIcons provides sidebarIcons,
            LocalSenderPicturePolicy provides picturePolicy,
        ) {
        /*
         * One address book, drawn in two places: the full page, and the panel the app bar
         * opens beside the mail. Held once so the two cannot drift apart in what saving,
         * deleting or writing to somebody does.
         */
        val contactsPane: @Composable () -> Unit = {
            ContactsPane(
                contacts = contacts.map { it.first },
                loading = contactsLoading,
                error = contactsError,
                books = contactBooks,
                onSave = if (sessions.any { it.jmap.hasContacts() }) { wanted ->
                    val key = writingAccount()
                    if (key != null) {
                        scope.launch {
                            contactsError = null
                            try {
                                withContext(Dispatchers.IO) {
                                    val jmap = session(key).jmap
                                    val original = contacts.firstOrNull { it.first.id == wanted.id }?.second
                                    // A card has to land in a book: Stalwart refuses one
                                    // that belongs to none. The editor picks it now, so
                                    // this is only the fallback for a card that arrived
                                    // from somewhere else without one.
                                    val books = wanted.bookIds.ifEmpty {
                                        val known = contactBooks.ifEmpty { jmap.addressBooks() }
                                        listOfNotNull(
                                            (known.firstOrNull { it.isDefault } ?: known.firstOrNull())?.id,
                                        )
                                    }
                                    jmap.saveContact(wanted.copy(bookIds = books), original)
                                    val fetched = jmap.contacts()
                                    contacts = fetched
                                    senderPhotos = photosFrom(jmap, fetched)
                                }
                            } catch (e: Exception) {
                                contactsError = whyFailed(e)
                            }
                        }
                    }
                } else {
                    null
                },
                onDelete = { gone ->
                    val key = writingAccount()
                    if (key != null) {
                        scope.launch {
                            contactsError = null
                            try {
                                withContext(Dispatchers.IO) {
                                    val jmap = session(key).jmap
                                    jmap.deleteContact(gone.id)
                                    val fetched = jmap.contacts()
                                    contacts = fetched
                                    senderPhotos = photosFrom(jmap, fetched)
                                }
                            } catch (e: Exception) {
                                contactsError = whyFailed(e)
                            }
                        }
                    }
                },
                onHistory = { contact -> openPerson(contact.label, contact.emails.first()) },
                onWrite = { address ->
                    contactsOpen = false
                    sendError = null; sendDetail = null
                    val account = writingAccount()
                    write(
                        account,
                        Draft(
                            from = identities[account].orEmpty().firstOrNull()?.email.orEmpty(),
                            to = address,
                        ),
                    )
                },
            )
        }
        /*
         * The app bar down the right edge, and the panel it opens beside the mail. What was
         * already here is the content, in a row of its own that gives the panel its width.
         * Labelled Row so the early returns below still leave the same block they always did.
         *
         * The same account list the folder column draws. The faces on the bar are that
         * list with the shared mailboxes taken out, which is what the old stack did.
         */
        val sidebarAccounts = sessions.map {
            AccountMailboxes(it.key, it.account.name, it.account.email, mailboxes[it.key].orEmpty())
        } + sharing.sidebarAccounts(mailboxes)
        WithSideTools(
            working = chatThinking || summarising || actionJob.running || composeBusy || filterBusy,
            accounts = sidebarAccounts,
            currentAccount = here?.first?.takeIf { it != ALL_ACCOUNTS },
            onAddAccount = onAddAccount,
            inDashboard = dashboardOpen,
            onDashboard = {
                dashboardOpen = !dashboardOpen
                today.open = false
                if (dashboardOpen) {
                    contactsOpen = false
                    settingsOpen = false
                    calendarOpen = false
                }
            },
            inSettings = settingsOpen,
            onSettings = {
                settingsOpen = !settingsOpen
                if (settingsOpen) {
                    contactsOpen = false
                    dashboardOpen = false
                    calendarOpen = false
                }
            },
            updateState = barState,
            onUpdate = {
                val from = when (val current = barState) {
                    is UpdateBarState.Waiting -> current.version
                    is UpdateBarState.Failed -> current.version
                    else -> update
                }
                if (from != null) scope.launch { installLatest(from) }
            },
            panel = { tool ->
                when (tool) {
                    SideTool.ROOK -> {
                        val key = selected?.let { accountOf(it) } ?: settingsAccount().orEmpty()
                        val accountSaid = rookConversations.said(key)
                        val accountSettings = settingCards[key] ?: ChangeDesk()
                        val accountFilters = filterCards[key] ?: FilterDesk()
                        val accountMail = mailChangeCards[key] ?: MailChangeDesk()
                        ChatPane(
                        said = accountSaid,
                        thinking = chatThinking || summarising || actionJob.running,
                        unavailable = Assistant.whyNot(Assistant.CHAT),
                        onSend = { ask(it) },
                        onClear = {
                            rookConversations = rookConversations.clear(key)
                            // A new conversation has been shown nothing, so it may act on
                            // nothing until it looks something up again.
                            chatShown[key]?.clear()
                            settingCards = settingCards + (key to accountSettings.clear())
                            filterCards = filterCards + (key to accountFilters.clear())
                            mailChangeCards = mailChangeCards + (key to accountMail.clear())
                            filterConsent = null
                        },
                        onClose = { AppBar.close(SideTool.ROOK) },
                        onSettings = { settingsOpen = true },
                        model = Assistant.config().model,
                        agreed = chatAgreed,
                        onAgree = { Assistant.agree(Assistant.CHAT); chatAgreed = true },
                        onTyping = { typing = it },
                        cards = accountSettings.cards,
                        onConfirmCard = { confirmCard(key, it) },
                        onDismissCard = { settingCards = settingCards + (key to accountSettings.dismiss(it)) },
                        mailCards = accountMail.cards,
                        onConfirmMail = { confirmMailChange(key, it) },
                        onCancelMail = { cancelMailChange(key, it) },
                        filterCards = accountFilters.cards,
                        onSaveFilter = { saveFilterCard(key, it) },
                        onCancelFilter = { cancelFilterCard(key, it) },
                        hasOpenMessage = selected != null,
                        summarising = summarising,
                        actionItemsRunning = actionJob.running,
                        onSummarise = selected?.let { sel ->
                            accountOf(sel)?.let { key ->
                                val cfg = Assistant.config()
                                if (cfg.mode == AssistantMode.OFF) null
                                else {
                                    {
                                        AppBar.show(SideTool.ROOK)
                                        appendRook(key, Said("user", "Summarise this thread"))
                                        summarise(sel, key, cfg, viewFirst = false)
                                    }
                                }
                            }
                        },
                        onActionItems = selected?.let { sel ->
                            accountOf(sel)?.let { key ->
                                val cfg = Assistant.config()
                                if (cfg.mode == AssistantMode.OFF) null
                                else {
                                    {
                                        AppBar.show(SideTool.ROOK)
                                        appendRook(key, Said("user", "Action items in this thread"))
                                        val why = Assistant.whyNot(Assistant.ACTIONS, cfg, key, currentFolderName(key))
                                        val forThread = sel.threadId.ifBlank { sel.id }
                                        startActionItems(
                                            job = actionJob,
                                            scope = scope,
                                            why = why,
                                            thread = forThread,
                                            subject = sel.subject,
                                            onDone = { items ->
                                                if (selected?.let { it.threadId.ifBlank { it.id } } != forThread) return@startActionItems
                                                val text = if (items.isEmpty()) {
                                                    "Nobody in this thread owes anything."
                                                } else {
                                                    items.joinToString("\n") { item ->
                                                        val line = "${item.who}: ${item.what}" + if (item.due.isNotBlank()) ", by ${item.due}" else ""
                                                        if (item.unverified.isNotEmpty()) {
                                                            "$line (check: ${item.unverified.joinToString(", ")})"
                                                        } else {
                                                            line
                                                        }
                                                    }
                                                }
                                                appendRook(key, Said("assistant", text))
                                            },
                                            onFailure = { (title, detail) ->
                                                if (selected?.let { it.threadId.ifBlank { it.id } } != forThread) return@startActionItems
                                                val err = if (detail != null) "$title $detail" else title
                                                appendRook(key, Said("result", err))
                                            },
                                        ) {
                                            summariseTurns(key, sel)
                                        }
                                    }
                                }
                            }
                        },
                        onViewPacket = selected?.let { sel ->
                            accountOf(sel)?.let { key ->
                                val cfg = Assistant.config()
                                val why = Assistant.whyNot(Assistant.SUMMARISE, cfg, key, currentFolderName(key))
                                if (cfg.mode == AssistantMode.OFF || why != null) null
                                else {
                                    {
                                        AppBar.show(SideTool.ROOK)
                                        summarise(sel, key, cfg, viewFirst = true)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        // Saved prompts and a file as context. See RookExtrasUi.kt.
                        extras = { typed, enabled, insert ->
                            val key = writingAccount()
                            RookPanelExtras(key?.let { session(it).jmap }, key, typed, enabled, insert)
                        },
                    )
                    }
                    SideTool.CALENDAR -> {
                        val key = (here?.first?.takeIf { it != ALL_ACCOUNTS } ?: sessions.firstOrNull()?.key)
                        val open = sessions.firstOrNull { it.key == key }
                        AgendaPanel(
                            open?.jmap,
                            open?.let { shortAccountName(it.account.name, it.account.email) }.orEmpty(),
                            onOpenCalendar = { AppBar.close(SideTool.CALENDAR); run("calendar") },
                        )
                    }
                    SideTool.CONTACTS -> contactsPane()
                    SideTool.FILES -> FilesPanel(writingAccount()?.let { session(it) }) { folder ->
                        AppBar.close(SideTool.FILES)
                        FilesPage.openAt(folder)
                    }
                    SideTool.TASKS -> {
                        val key = (here?.first?.takeIf { it != ALL_ACCOUNTS } ?: sessions.firstOrNull()?.key)
                        val open = sessions.firstOrNull { it.key == key }
                        TasksPanel(open?.jmap, open?.let { shortAccountName(it.account.name, it.account.email) }.orEmpty())
                    }
                }
            },
        ) Row@{
            Sidebar(
                search = {
                    SearchBar(
                        query = query,
                        focusRequester = searchField,
                        onFocusChanged = { typing = it },
                        onQueryChange = { query = it },
                        onSearch = {
                            settingsOpen = false
                            contactsOpen = false
                            calendarOpen = false
                            activeSavedSearch = null
                            showingResults = query.isNotBlank()
                            selected = null
                            scope.launch { reload() }
                        },
                    )
                    AskRookOffer(
                        query = query,
                        job = askJob,
                        onAsk = { settingsAccount()?.let { key -> askRook(askJob, scope, query, readerFor(key)) } },
                        onOpen = { message ->
                            settingsOpen = false; contactsOpen = false; dashboardOpen = false; calendarOpen = false
                            selected = message
                            focusedNav = focusedNav.opened(rowToken(message))
                        },
                    )
                },
                accounts = sidebarAccounts,
                unifiedUnread = unifiedInboxUnread(mailboxes, sharing.prefs),
                folderRefusal = sharing::folderRefusal,
                followUp = { narrowed ->
                    FollowUpRow(followUps, narrowed, selected = viewingTag == FOLLOW_UP) {
                        settingsOpen = false; contactsOpen = false; dashboardOpen = false; calendarOpen = false
                        activeSavedSearch = null
                        openTag(FOLLOW_UP)
                    }
                },
                unifiedViews = { narrowed ->
                    UnifiedViewRows(
                        collapsed = narrowed,
                        selectedId = here?.takeIf { it.first == ALL_ACCOUNTS && activeSavedSearch == null }?.second?.id,
                        onSelect = { box ->
                            settingsOpen = false; contactsOpen = false; dashboardOpen = false; calendarOpen = false
                            activeSavedSearch = null
                            here = ALL_ACCOUNTS to box
                        },
                        onChoose = { sharing.choosing = it },
                    )
                },
                here = here,
                tags = remember(tagsSeen, tagColours) { mergedTags(tagsSeen, tagColours) },
                hereTag = viewingTag,
                savedSearches = withSplitChildren(savedSearches, savedSearchGroups),
                selectedSavedSearchId = activeSavedSearch?.id,
                savedSearchCounts = savedSearchCounts,
                onSelectSavedSearch = { search -> openSavedSearch(search) },
                onManageSavedSearch = { search, job -> savedSearchAsk = SavedSearchAsk(search, job) },
                onSelectTag = { keyword ->
                    settingsOpen = false
                    contactsOpen = false
                    dashboardOpen = false
                    calendarOpen = false
                    activeSavedSearch = null
                    openTag(keyword)
                },
                onTagColour = { keyword, colour ->
                    Settings.setTagColour(keyword, colour)
                    tagColours = Settings.tagColours()
                },
                folded = folded,
                onFold = { section ->
                    folded = if (section in folded) folded - section else folded + section
                    Settings.setCollapsedSections(folded)
                },
                dragAt = dragAt,
                onTagBounds = { keyword, bounds -> tagBounds[keyword] = bounds },
                onDashboard = { dashboardOpen = !dashboardOpen; today.open = false; if (dashboardOpen) { contactsOpen = false; settingsOpen = false; calendarOpen = false } },
                inDashboard = dashboardOpen,
                inSettings = settingsOpen,
                collapsed = collapsed,
                onToggleCollapsed = { collapsed = !collapsed; Settings.setSidebarCollapsed(collapsed) },
                onViewChangelog = { changelogDialog = unseenChanges(changelog(), Settings.changelogSeen()) },
                folderMenu = { key, box, job -> folderAsk = FolderAsk(key, box, job) },
                onSelect = { key, mailbox ->
                    settingsOpen = false
                    contactsOpen = false
                    dashboardOpen = false
                    calendarOpen = false
                    activeSavedSearch = null
                    here = key to mailbox
                },
                onWrite = {
                    val account = writingAccount()
                    val from = identities[account].orEmpty().firstOrNull()?.email
                        ?: sessions.firstOrNull { it.key == account }?.account?.email.orEmpty()
                    sendError = null; sendDetail = null
                    write(account, Draft(from = from))
                },
            )
            savedSearchAsk?.let { ask ->
                // Editing opens the condition builder, which also converts a search saved
                // before conditions existed. See SavedSearchBuilder.kt.
                if (ask.job == SavedSearchJob.EditQuery) {
                    SavedSearchBuilder(
                        search = ask.search,
                        onClose = { savedSearchAsk = null },
                        onSave = { name, condition, split ->
                            val updated = updateSavedSearchConditions(savedSearches, ask.search.id, name, condition, split)
                            Settings.setSavedSearches(updated)
                            savedSearches = updated
                            val saved = updated.firstOrNull { it.id == ask.search.id }
                            val showing = activeSavedSearch?.let { it.id == ask.search.id || it.parentId == ask.search.id } == true
                            savedSearchAsk = null
                            if (saved != null && showing) openSavedSearch(saved)
                        },
                    )
                    return@let
                }
                SavedSearchDialog(
                    ask = ask,
                    onClose = { savedSearchAsk = null },
                    onConfirm = { name, queryText ->
                        when (ask.job) {
                            SavedSearchJob.SaveCurrent -> {
                                val currentAccount = here?.first ?: ALL_ACCOUNTS
                                val newSearch = SavedSearch(
                                    name = name,
                                    account = currentAccount,
                                    query = query,
                                    filters = quick,
                                )
                                val updated = saveSearch(savedSearches, newSearch)
                                Settings.setSavedSearches(updated)
                                savedSearches = updated
                                activeSavedSearch = newSearch
                            }
                            SavedSearchJob.Rename -> {
                                val updated = renameSavedSearch(savedSearches, ask.search.id, name)
                                Settings.setSavedSearches(updated)
                                savedSearches = updated
                                if (activeSavedSearch?.id == ask.search.id) {
                                    activeSavedSearch = activeSavedSearch?.copy(name = name)
                                }
                            }
                            SavedSearchJob.EditQuery -> {
                                val updated = updateSavedSearchQuery(savedSearches, ask.search.id, queryText, ask.search.filters)
                                Settings.setSavedSearches(updated)
                                savedSearches = updated
                                if (activeSavedSearch?.id == ask.search.id) {
                                    activeSavedSearch = activeSavedSearch?.copy(query = queryText)
                                    query = queryText
                                    scope.launch { reload() }
                                }
                            }
                            SavedSearchJob.Delete -> {
                                val updated = deleteSavedSearch(savedSearches, ask.search.id)
                                Settings.setSavedSearches(updated)
                                savedSearches = updated
                                if (activeSavedSearch?.id == ask.search.id || activeSavedSearch?.parentId == ask.search.id) {
                                    activeSavedSearch = null
                                    showingResults = false
                                    query = ""
                                    val inbox = folderFor("inbox", mailboxes[ask.search.account].orEmpty())
                                    if (inbox != null) {
                                        here = ask.search.account to inbox
                                    }
                                }
                            }
                        }
                        savedSearchAsk = null
                    },
                )
            }
            sharing.choosing?.let { view ->
                UnifiedViewsDialog(
                    state = sharing,
                    view = view,
                    mailboxes = mailboxes,
                    onClose = { sharing.choosing = null },
                    onChanged = { if (here?.first == ALL_ACCOUNTS) scope.launch { reload() } },
                )
            }
            folderAsk?.let { ask ->
                if (ask.job == FolderJob.Share && ask.mailbox != null) {
                    // Shared mailboxes (SharedMailboxesUi.kt): the dialog does its own asking.
                    ShareFolderDialog(remember(ask) { folderSharingFor(ask.account, sessions) }, ask.mailbox) { folderAsk = null }
                } else if ((ask.job == FolderJob.Export || ask.job == FolderJob.Import) && ask.mailbox != null) {
                    val key = ask.account
                    val backend = runCatching { session(key).jmap }.getOrNull()
                    if (backend != null) {
                        if (ask.job == FolderJob.Export) {
                            ExportFolderDialog(
                                accountName = ask.account,
                                mailbox = ask.mailbox,
                                allMailboxes = mailboxes[key].orEmpty(),
                                backend = backend,
                                onClose = { folderAsk = null },
                            )
                        } else {
                            ImportFolderDialog(ask.mailbox, backend) { folderAsk = null }
                        }
                    } else {
                        folderAsk = null
                    }
                } else {
                    FolderDialog(
                        ask = ask,
                        error = folderError,
                        onClose = { folderAsk = null; folderError = null },
                        onConfirm = { answer -> doFolderJob(ask, answer) },
                    )
                }
            }
            filterFor?.let { message ->
                // The set kept for every account, so a folder has to exist on all of them.
                // A name only one server has is a rule the others will refuse.
                val folders = commonFolders(sessions.map { open -> mailboxes[open.key].orEmpty().map { it.name }.toSet() })
                FilterFromMessageDialog(
                    message = message,
                    folders = folders,
                    onClose = { filterFor = null },
                    onMade = { rule ->
                        saveGlobalFilters(globalFilters.copy(rules = globalFilters.rules + rule))
                    },
                    onBusy = { filterBusy = it },
                )
            }
            changelogDialog?.let { changes ->
                ChangelogDialog(
                    changes = changes,
                    onClose = { suppress ->
                        Updates.current?.let { Settings.setChangelogSeen(it) }
                        Settings.setChangelogSuppressed(suppress)
                        changelogDialog = null
                    },
                )
            }
            VerticalDivider()
            if (FilesPage.open) {
                FilesPane(writingAccount()?.let { session(it) })
            } else if (calendarOpen) {
                val key = CalendarJump.target?.account ?: (here?.first?.takeIf { it != ALL_ACCOUNTS } ?: sessions.firstOrNull()?.key)
                val open = sessions.firstOrNull { it.key == key }
                CalendarPane(open?.jmap, open?.let { shortAccountName(it.account.name, it.account.email) }.orEmpty())
            } else if (dashboardOpen && today.open) {
                val key = settingsAccount()
                BriefingPane(
                    view = today,
                    account = key.orEmpty(),
                    accountName = sessions.firstOrNull { it.key == key }
                        ?.let { shortAccountName(it.account.name, it.account.email) }.orEmpty(),
                    onLoad = { refresh ->
                        key?.let { k ->
                            val inbox = folderFor("inbox", mailboxes[k].orEmpty())?.name
                            openBriefing(today, scope, k, readerFor(k), inbox, { Secrets.mailKey(session(k).account) }, refresh)
                        }
                    },
                    onOpen = { message ->
                        dashboardOpen = false
                        today.open = false
                        selected = message
                        focusedNav = focusedNav.opened(rowToken(message))
                    },
                    onBack = { today.open = false },
                )
            } else if (dashboardOpen) {
                DashboardPane(
                    onToday = if (Assistant.config().mode == AssistantMode.OFF) null else ({ today.open = true }),
                    stats = stats,
                    tracking = if (Settings.trackNewMail() || sessions.any { Settings.trackNewMail(it.key) }) {
                        val selectedAccount = here?.first?.takeIf { it != ALL_ACCOUNTS }
                        trackingStats(
                            sessions.filter { selectedAccount == null || it.key == selectedAccount }
                                .flatMap { it.store?.tracking().orEmpty() },
                        )
                    } else null,
                    unavailable = if (sessions.any { it.store != null }) "" else
                        "There is no local copy of this mailbox on this machine, and every " +
                            "figure here is counted from one. Rampart runs without it where " +
                            "there is nowhere safe to keep the key that encrypts it.",
                    accountName = (here?.first?.takeIf { it != ALL_ACCOUNTS } ?: sessions.firstOrNull()?.key)
                        ?.let { key -> sessions.firstOrNull { it.key == key } }
                        ?.let { shortAccountName(it.account.name, it.account.email) }.orEmpty(),
                    onOpen = { message ->
                        dashboardOpen = false
                        selected = message
                        focusedNav = focusedNav.opened(rowToken(message))
                    },
                )
            } else if (contactsOpen) {
                contactsPane()
            } else if (settingsOpen) {
                val currentSettingsAccount = pickedSettingsAccount()
                SettingsPane(
                    accounts = sessions.map {
                        AccountMailboxes(it.key, it.account.name, it.account.email, mailboxes[it.key].orEmpty())
                    },
                    account = currentSettingsAccount,
                    onAccount = { chosenSettingsAccount = it },
                    backendFor = { key -> runCatching { session(key).jmap }.getOrNull() },
                    identities = identities[currentSettingsAccount].orEmpty(),
                    quotas = quotas,
                    onTintRowsByTag = { tintRows = it },
                    onDensity = { density = it },
                    onAppearance = onAppearance,
                    onOrderAll = { orderAll = it },
                    sidebarIcons = sidebarIcons,
                    onSidebarIcons = { sidebarIcons = it },
                    onUndoBarSeconds = { undoBarSeconds = it },
                    onLoader = { loader = it },
                    onTrackingServer = { trackingServer = it },
                    vacation = vacation,
                    vacationError = vacationError,
                    onVacation = { wanted ->
                        val key = currentSettingsAccount
                        vacationError = vacationProblem(wanted)
                        if (key != null && vacationError == null) {
                            scope.launch {
                                vacationError = try {
                                    withContext(Dispatchers.IO) { session(key).jmap.setVacation(wanted) }
                                    vacation = wanted
                                    null
                                } catch (e: Exception) {
                                    whyFailed(e).ifBlank { "The server would not save it." }
                                }
                            }
                        }
                    },
                    signatureError = signatureError,
                    onSignature = { identity, html ->
                        val key = currentSettingsAccount
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
                                    signatureError = whyFailed(e)
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
                                signatureError = whyFailed(e)
                                null
                            }
                        }
                    },
                    // Not the raw `update` signal: that flips true as soon as a newer
                    // version is merely known, which can be before it has actually been
                    // staged. This page's own button reads the same state the bottom bar
                    // does, so it is never offered before there is something to install.
                    update = when (val current = barState) {
                        is UpdateBarState.Waiting -> current.version
                        is UpdateBarState.Failed -> current.version
                        else -> null
                    },
                    checkingUpdate = checkingUpdate,
                    updateCheckFailure = updateCheckFailure,
                    onCheckNow = {
                        if (!checkingUpdate) scope.launch {
                            checkingUpdate = true
                            checkForUpdate()
                            checkingUpdate = false
                        }
                    },
                    notifyOnArrival = notifyOnArrival,
                    onNotifyOnArrival = { notifyOnArrival = it; Settings.setNotifyOnArrival(it) },
                    notifyOnOpen = notifyOnOpen,
                    onNotifyOnOpen = { notifyOnOpen = it; Settings.setNotifyOnOpen(it) },
                    onMessageMode = { messageMode = it },
                    onMessageScale = { messageScale = it },
                    onTheme = onTheme,
                    iconPack = icons,
                    onIconPack = onIcons,
                    onAddAccount = onAddAccount,
                    /*
                     * The same re-check-and-restage flow the bottom bar's own click runs,
                     * not a bare `restartToUpdate()`. A click has to ask what is newest
                     * first, because a manifest staged earlier can have stopped being
                     * current. Windows App Installer closes Rampart when the update
                     * proceeds, so this button does not quit on its own.
                     */
                    onRestart = {
                        val from = when (val current = barState) {
                            is UpdateBarState.Waiting -> current.version
                            is UpdateBarState.Failed -> current.version
                            else -> null
                        }
                        if (from != null) scope.launch { installLatest(from) }
                    },
                    filters = filters,
                    filtersSupported = filtersSupported,
                    filtersSaving = filtersSaving,
                    filtersError = filtersError,
                    filterAccount = filterAccount,
                    onFilterAccount = { filterAccount = it },
                    globalFilters = globalFilters,
                    onGlobalFilters = { saveGlobalFilters(it) },
                    onFilters = { next -> filterAccount?.let { saveFilters(it, next) } },
                    onCreateFilterFolder = ::createFilterFolder,
                    onFilterBusy = { filterBusy = it },
                    onClose = { settingsOpen = false },
                    security = currentSettingsAccount?.let { key -> sessions.firstOrNull { it.key == key } },
                )
                return@Row
            }
            // The undo card sits over the list, not the message: the message is a native panel
            // that paints over anything drawn on top of it. When the focused layout covers
            // the list, the same card sits between Back and the message, still off that panel.
            val isInboxView = !showingResults && query.isEmpty() && viewingPerson == null &&
                viewingTag == null && activeSavedSearch == null &&
                here?.second?.role == "inbox" &&
                (here?.first != ALL_ACCOUNTS || here?.second?.id == ALL_ACCOUNTS)
            val focusActive = focusedInbox && isInboxView
            // The book this window already keeps. A sender in it stays in Focused.
            // Someone moved between the tabs is recorded in focusOverrides, passed with it.
            val memory = books
            val focusKnown = if (focusActive) {
                val k = here?.first?.takeIf { it != ALL_ACCOUNTS }
                if (k != null) knownAddresses(k, memory)
                else sessions.flatMap { knownAddresses(it.key, memory) }.toSet()
            } else emptySet()

            val (focusedEmails, otherEmails) = if (focusActive) {
                emails.partition { isFocused(it, focusKnown, focusOverrides) }
            } else {
                emails to emptyList()
            }

            val otherUnread = if (focusActive) otherEmails.count { !it.seen } else 0
            val otherBundles = if (focusActive) bundleOtherMessages(otherEmails) else emptyList()
            val shownEmails = if (focusActive) {
                if (focusTab == FocusTab.FOCUSED) focusedEmails else otherEmails
            } else emails

            val openMessage = selected
            // The merged inbox is not Junk. A real folder is, by its role or by the id we
            // already use for the Junk actions, so a logo is not fetched for that mail.
            val viewingJunk = run {
                val account = here?.first
                val folder = here?.second
                if (account == null || account == ALL_ACCOUNTS || folder == null) {
                    false
                } else {
                    folderIsJunk(folder.role, folder.id, folderFor("junk", mailboxes[account].orEmpty())?.id)
                }
            }
            CompositionLocalProvider(LocalViewingJunk provides viewingJunk) {
            MailPanes(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                page = { content -> ReadingPage(messageMode, Modifier.fillMaxSize(), content) },
                layout = MailLayoutState.layout,
                messageOpen = showsFocusedMessage(
                    MailLayoutState.layout,
                    followedNav,
                    openMessage?.let(::rowToken),
                ),
                batch = picked.size > 1,
                onBack = { focusedNav = focusedNav.backed() },
                list = { wide ->
                MessageList(
                    emails = shownEmails,
                    selected = selected,
                    loading = loading,
                    focusEnabled = focusActive,
                    focusTab = focusTab,
                    onFocusTab = { focusTab = it; selected = null },
                    otherUnread = otherUnread,
                    otherBundles = otherBundles,
                    expandedBundles = expandedBundles,
                    onToggleBundle = { key ->
                        expandedBundles = if (key in expandedBundles) expandedBundles - key else expandedBundles + key
                    },
                    title = listHeading(
                        searching = showingResults,
                        query = query,
                        folder = viewingPerson?.let { "Every message, every account" }
                            ?: viewingTag?.let { if (it == FOLLOW_UP) FOLLOW_UP_VIEW else tagsOf(setOf(it)).firstOrNull()?.label ?: it }
                            ?: here?.second?.name.orEmpty(),
                        savedSearchName = activeSavedSearch?.name,
                    ),
                    /*
                     * Dragging a message onto a tag in the sidebar.
                     *
                     * The drop is worked out here from where the pointer was let go, not by the
                     * tag row noticing a pointer over itself, because the row being dragged
                     * consumes pointer movement for the length of the drag and nothing
                     * underneath ever hears about it.
                     */
                    onDrag = { message, at ->
                        if (at != null) {
                            dragging = message
                            dragAt = at
                        } else {
                            val where = dragAt
                            val hit = where?.let { point ->
                                tagBounds.entries.firstOrNull { it.value.contains(point) }?.key
                            }
                            val dropped = dragging
                            if (hit != null && dropped != null) tagMessage(dropped, hit)
                            dragging = null
                            dragAt = null
                        }
                    },
                    // Only in the merged list. Everywhere else the folder says which account it
                    // is, and repeating it on every row would be noise on most screens.
                    header = viewingPerson?.let { person ->
                        {
                            PersonHeader(
                                person = person,
                                stats = personStats,
                                loading = loading,
                                note = personNote,
                                onWrite = { address ->
                                    sendError = null; sendDetail = null
                                    val account = writingAccount()
                                    write(account, Draft(from = identities[account].orEmpty().firstOrNull()?.email.orEmpty(), to = address))
                                },
                                onClose = {
                                    viewingPerson = null
                                    personHistory = null
                                    selected = null
                                    scope.launch { reload() }
                                },
                            )
                        }
                    },
                    accountLabels = if (unified() || (viewingPerson != null && sessions.size > 1)) {
                        sessions.associate { it.key to shortAccountName(it.account.name, it.account.email) }
                    } else {
                        emptyMap()
                    },
                    picked = picked,
                    onRefresh = { scope.launch { refreshNow() } },
                    order = listOrder(),
                    onOrder = { order = it; Settings.setOrder(it) },
                    rowActions = rowActions,
                    scheduled = scheduledSends.associate { it.draftId to it.sendAt },
                    trackingBadges = trackingBadges,
                    filters = quick,
                    onFilters = { next ->
                        quick = next
                        selected = null
                        scope.launch { reload() }
                    },
                    onClearFilters = {
                        quick = QuickFilters()
                        selected = null
                        scope.launch { reload() }
                    },
                    folderTotal = if (here?.first == ALL_ACCOUNTS) {
                        sessions.sumOf { open -> folderFor("inbox", mailboxes[open.key].orEmpty())?.total ?: 0 }
                    } else {
                        here?.second?.total ?: 0
                    },
                    attachmentReason = attachmentReason(
                        imapAccount = here?.first?.let { it != ALL_ACCOUNTS && session(it).jmap is Imap } == true,
                        mergedWithImap = here?.first == ALL_ACCOUNTS && sessions.any { it.jmap is Imap },
                    ),
                    filterNote = filterNote ?: here?.let { (key, box) -> sharing.folderNote(key, box.id) },
                    loadingMore = loadingMore,
                    onNeedMore = ::loadMore,
                    searching = showingResults,
                    onSaveSearch = if (activeSavedSearch == null && (showingResults || query.isNotBlank() || quick.active)) {
                        {
                            savedSearchAsk = SavedSearchAsk(
                                SavedSearch(
                                    name = query.ifBlank { "Saved search" },
                                    account = here?.first ?: ALL_ACCOUNTS,
                                    query = query,
                                    filters = quick,
                                ),
                                SavedSearchJob.SaveCurrent,
                            )
                        }
                    } else null,
                    threads = threadContext(),
                    fillWidth = wide,
                    onSelect = { message, ctrl, shift ->
                        val token = rowToken(message)
                        picked = pickedAfter(emails.map { rowToken(it) }, picked, anchor, token, ctrl, shift)
                        if (!shift) anchor = token
                        if (!ctrl && !shift) {
                            // A right-click is a menu, not an open. Covering the list would
                            // hide the row the menu belongs to. Read it before it is cleared.
                            val menu = RowPress.menu
                            selectedForMenu = menu
                            selected = message
                            if (!menu) focusedNav = focusedNav.opened(token)
                        }
                        RowPress.menu = false
                    },
                )
                },
                notice = {
                // A small card floating at the bottom, like a phone's own undo notice. Not in the
                // column above the panes, where it pushed every pane down, and not a full-width
                // strip across the top, where it covered the toolbar you needed while it was up.
                if (undoSend != null || undo != null) {
                    Column(
                        Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        undoSend?.let { cancel ->
                            // The drain here is the real deadline rather than a display choice: the message
                            // is being held for exactly this long and then it goes. No Dismiss, because
                            // dismissing the offer would not stop the send, and a button that looks like it
                            // might is worse than no button.
                            UndoBar(
                                text = "Sending.",
                                seconds = Settings.undoSeconds(),
                                restartOn = cancel,
                                onUndo = cancel,
                            )
                        }
                        undo?.let { last ->
                            UndoBar(
                                text = last.notice ?: movedNotice(last.count, last.what),
                                seconds = undoBarSeconds,
                                restartOn = last,
                                onUndo = {
                                    // A join or a split is put back on this computer, with
                                    // nothing to send to any server.
                                    val putBack = last.local
                                    if (putBack != null) {
                                        putBack()
                                        undo = null
                                    } else scope.launch {
                                        // Every account is put back, and the notice only clears if they all
                                        // did. One that failed leaves the offer up rather than pretending.
                                        val results = withContext(Dispatchers.IO) {
                                            last.moves.map { move ->
                                                move to runCatching {
                                                    // A filing only took them out of one folder, so putting
                                                    // them back takes them out of that one and restores the
                                                    // other. A plain move would drop a copy also in Sent.
                                                    val placed = move.toMailboxId
                                                    if (placed != null) {
                                                        session(move.accountKey).jmap.moveFrom(move.ids, placed, move.fromMailboxId)
                                                    } else {
                                                        session(move.accountKey).jmap.move(move.ids, move.fromMailboxId)
                                                    }
                                                }
                                            }
                                        }
                                        val failed = results.filter { it.second.isFailure }.map { it.first }
                                        if (failed.isEmpty()) {
                                            undo = null
                                            refreshNow()
                                        } else {
                                            undo = Undoable(failed, last.what)
                                            report(
                                                "Some messages could not be put back.",
                                                whyFailed(results.first { it.second.isFailure }.second.exceptionOrNull()!!),
                                            )
                                        }
                                    }
                                },
                                // Only this one expires. The move can still be undone by hand afterwards,
                                // so the offer running out costs nothing.
                                onExpire = { undo = null },
                                onDismiss = { undo = null },
                            )
                        }
                    }
                }
                },
                reading = {
                    ReadingPage(messageMode, Modifier.fillMaxSize()) {
                    if (picked.size > 1) {
                Picked(
                    count = picked.size,
                    // The folder itself rather than any one message: a batch is whatever is
                    // on screen, and what is on screen is one folder.
                    inJunk = here?.second?.let { folder ->
                        folder.role == "junk" || folder.id == here?.first?.let { key ->
                            folderFor("junk", mailboxes[key].orEmpty())?.id
                        }
                    } == true,
                    onClear = { picked = emptySet() },
                    onJoin = if (threadContext().collapse) ({ joinPicked() }) else null,
                    onFile = { role, what ->
                        // Grouped by account, because in the merged inbox the picked
                        // messages can come from several, and each has its own Archive.
                        val byAccount = emails.filter { rowToken(it) in picked }
                            .groupBy { accountOf(it) }
                            .mapNotNull { (key, group) ->
                                key ?: return@mapNotNull null
                                val from = sourceFolder(key)
                                val target = folderFor(role, mailboxes[key].orEmpty())
                                if (from == null || target == null) null
                                else Triple(key, Move(key, group.map { it.id }, from), target.id)
                            }
                        if (byAccount.isNotEmpty()) {
                            scope.launch {
                                val ready = byAccount.filter { (key, move, target) ->
                                    sayJunk(key, move.ids, move.fromMailboxId, target)
                                }
                                val results = withContext(Dispatchers.IO) {
                                    ready.map { (key, move, target) ->
                                        Triple(key, move, runCatching { session(key).jmap.move(move.ids, target) })
                                    }
                                }
                                val failed = results.mapNotNull { it.third.exceptionOrNull() }
                                if (failed.isNotEmpty()) {
                                    report("Those messages could not be moved.", whyFailed(failed.first()))
                                }
                                val done = results.filter { it.third.isSuccess }
                                done.forEach { (key, _, result) -> noteState(key, result.getOrNull()) }
                                if (done.isNotEmpty()) {
                                    done.forEach { (key, move, _) ->
                                        runCatching { session(key).store?.forget(move.ids) }
                                    }
                                    advancePast { row ->
                                        done.any { (key, move, _) -> row.sameMail(key, move.ids.toSet()) }
                                    }
                                    emails = emails.filterNot { row ->
                                        done.any { (key, move, _) -> row.sameMail(key, move.ids.toSet()) }
                                    }
                                    picked = emptySet()
                                    // A refusal is an error, not an undo. Undo is only for
                                    // a move the server actually made.
                                    if (failed.isEmpty()) undo = Undoable(done.map { it.second }, what)
                                }
                            }
                        }
                    },
                    onRead = {
                        emails.filter { rowToken(it) in picked && !it.seen }
                            .groupBy { accountOf(it) }
                            .forEach { (key, group) ->
                                if (key != null) setSeen(key, group.map { it.id }.toSet(), true)
                            }
                        picked = emptySet()
                    },
                )
                    } else {
                        val message = openMessage
            if (message == null) {
                Message(
                    summary = null,
                    body = null,
                    onLink = { confirm = it },
                    messageMode = messageMode,
                    nothingOpen = {
                        val region = Regional.current()
                        NothingOpen(
                            date = LocalDate.now(region.zone),
                            region = region,
                            unread = glanceUnread(
                                here?.first,
                                mailboxes,
                                unifiedInboxUnread(mailboxes, sharing.prefs),
                            ),
                            waiting = stats?.waiting,
                            stillToday = stillToday.map { event ->
                                GlanceLine(timeText(event, region), event.title)
                            },
                            onOpen = { opened ->
                                dashboardOpen = false
                                selected = opened
                                focusedNav = focusedNav.opened(rowToken(opened))
                            },
                            onWrite = {
                                val account = writingAccount()
                                val from = identities[account].orEmpty().firstOrNull()?.email
                                    ?: sessions.firstOrNull { it.key == account }?.account?.email.orEmpty()
                                sendError = null
                                sendDetail = null
                                write(account, Draft(from = from))
                            },
                            onToday = if (Assistant.config().mode == AssistantMode.OFF) {
                                null
                            } else {
                                {
                                    dashboardOpen = true
                                    today.open = true
                                    contactsOpen = false
                                    settingsOpen = false
                                    calendarOpen = false
                                }
                            },
                            onDashboard = {
                                dashboardOpen = true
                                today.open = false
                                contactsOpen = false
                                settingsOpen = false
                                calendarOpen = false
                            },
                        )
                    },
                )
            } else {
                /*
                 * One place for the card, whether or not the thread has arrived.
                 *
                 * A lone message and a stack used to be two branches, so the moment the
                 * thread answered the card was thrown away and a new one built, and the
                 * message loaded a second time. Keyed by id inside the one list, the card
                 * that was already on screen stays the same card when the rows around it
                 * appear. A lone message still has no count and no collapsed rows: the
                 * stack's chrome is only added once there is more than one.
                 */
                val stacked = thread.size > 1
                val stackScroll = rememberScrollState()
                Box(
                    Modifier.fillMaxSize().background(
                        if (stacked && LocalRampartTheme.current.page?.background != MaterialTheme.colorScheme.background) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            Color.Transparent
                        },
                    ),
                ) {
                    if (stacked) ThemeArt(Modifier.align(Alignment.BottomEnd))
                    Column(Modifier.fillMaxSize()) {
                        // Outside the scroll, so opening a card further down cannot
                        // carry the subject off the top, and it is here before the
                        // cards finish loading.
                        if (stacked) {
                            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
                                Text(
                                    message.subject.ifBlank { "(no subject)" },
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${thread.size} messages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    if (conversationMuted) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Muted",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.clip(MaterialTheme.shapes.small)
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .padding(horizontal = 7.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Column(
                            Modifier.weight(1f).fillMaxWidth().then(
                                if (stacked) {
                                    Modifier.verticalScroll(stackScroll)
                                        .padding(horizontal = 20.dp, vertical = 12.dp)
                                } else {
                                    Modifier
                                },
                            ),
                        ) {
                        val rows = if (stacked) thread else listOf(message)
                        rows.forEachIndexed { index, m ->
                            key(m.id) {
                                if (stacked && m.id !in expanded) {
                                    ThreadRow(m) { toggleExpand(m.id) }
                                } else {
                                    val requester = remember(m.id) { BringIntoViewRequester() }
                                    if (stacked) {
                                        LaunchedEffect(pendingScrollTo) {
                                            if (pendingScrollTo == m.id) {
                                                requester.bringIntoView()
                                                pendingScrollTo = null
                                            }
                                        }
                                    }
                                    // A column rather than a box, so the encryption line sits above the card.
                                    Column(if (stacked) Modifier.bringIntoViewRequester(requester) else Modifier) {
                                        sealedPanelFor(m)
                                        renderCard(
                                            m,
                                            showSubject = !stacked,
                                            onHeaderClick = if (stacked) {
                                                { toggleExpand(m.id) }
                                            } else {
                                                null
                                            },
                                            externalScroll = if (stacked) stackScroll else null,
                                        )
                                    }
                                }
                            }
                            if (stacked && index < rows.lastIndex) {
                                Spacer(Modifier.height(6.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        }
                        // Outside the scroll, so a long earlier message cannot carry
                        // Reply off the bottom. A single message already keeps its
                        // toolbar there. Hidden while a composer is open.
                        if (stacked) {
                            val target = replyBarTarget(thread)
                            if (target != null) {
                                val targetKey = accountOf(target)
                                LaunchedEffect(targetKey, target.id) {
                                    if (targetKey == null) return@LaunchedEffect
                                    val slot = CardKey(targetKey, target.id)
                                    if (cards[slot]?.loaded != true && cardEpoch[slot] == null) loadCard(targetKey, target.id)
                                }
                                if (composing == null) {
                                    val card = cardFor(target)
                                    val shown = SealedView.of(targetKey, target.id)?.body ?: card.body
                                    val ours = identities[targetKey].orEmpty().map { it.email }.toSet()
                                    PinnedReplyBar(
                                        who = target.from.ifBlank { target.fromEmail },
                                        bodyReady = shown != null,
                                        replyAll = hasOtherRecipients(target, card.body, ours),
                                        onReply = { all -> replyFromCard(target, all) },
                                        onForward = { forwardFromCard(target) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
                    }
                    }
                },
            )
            }
        }
        }
        }
        UpdateBar(barState) {
            val from = when (val current = barState) {
                is UpdateBarState.Waiting -> current.version
                is UpdateBarState.Failed -> current.version
                else -> null
            }
            if (from != null) scope.launch { installLatest(from) }
        }
    }

        /*
         * Bottom right, over the mail, the way every webmail does it. Writing a reply and
         * looking at what is above it are the same task, and a composer that takes the
         * window makes them two.
         *
         * Full screen is one button away for a long message, because a panel is the wrong
         * shape for anything with a table in it.
         */
        // Kept for the fade out. Without it the panel would vanish before the exit could run.
        // Off, the fade snaps, which is the same as the panel appearing and disappearing at once.
        val panelWriting = composing
        var heldCompose by remember { mutableStateOf(panelWriting) }
        if (panelWriting != null) heldCompose = panelWriting
        val writing = panelWriting ?: heldCompose
        if (writing != null) {
            // Clamped against the live window on every draw, not only while a drag is in
            // progress: a window shrunk since the size was chosen (or since a previous run)
            // should not reopen a panel that no longer fits, without needing the drag itself
            // to have run first.
            val panelSize = clampComposeSize(DpSize(composeWidth, composeHeight), windowSize())
            AnimatedVisibility(
                visible = panelWriting != null,
                enter = fadeIn(motionTween(160)),
                exit = fadeOut(motionTween(120)),
                modifier = Modifier.align(if (composeFull) Alignment.Center else Alignment.BottomEnd),
            ) {
            Box(
                Modifier.then(
                    if (composeFull) Modifier.fillMaxSize()
                    else Modifier.padding(16.dp).width(panelSize.width).height(panelSize.height),
                ),
            ) {
                ComposerFrame(full = composeFull) {
                    // A new session is a new composer, even when its draft equals the last one.
                    key(writing) { ComposerPanel(writing) }
                }
                /*
                 * The corner that moves. The panel is anchored bottom right, so the top
                 * left corner is the one that grows or shrinks the same way a resizable
                 * window's does everywhere else on the desktop: dragging it changes width
                 * and height together, diagonally, which is the ordinary meaning of a
                 * corner handle. Absent in full screen, where there is nothing to resize.
                 */
                if (!composeFull) {
                    val density = LocalDensity.current
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .size(20.dp)
                            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)))
                            .pointerInput(Unit) {
                                // A cancelled drag, the pointer leaving the window mid motion,
                                // still moved the corner; losing that silently on the way out
                                // would be a worse surprise than saving it a little early, so
                                // both endings persist the same way.
                                val persist: () -> Unit = {
                                    Settings.setComposeWidth(composeWidth.value)
                                    Settings.setComposeHeight(composeHeight.value)
                                }
                                detectDragGestures(
                                    onDragEnd = persist,
                                    onDragCancel = persist,
                                ) { _, dragAmount ->
                                    // The corner is top left, so moving the pointer up and
                                    // left, negative on both axes, is what grows the panel.
                                    val next = clampComposeSize(
                                        DpSize(
                                            composeWidth - with(density) { dragAmount.x.toDp() },
                                            composeHeight - with(density) { dragAmount.y.toDp() },
                                        ),
                                        windowSize(),
                                    )
                                    composeWidth = next.width
                                    composeHeight = next.height
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            RampartIcons.Resize,
                            contentDescription = "Resize",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            }
        }
    }

    if (showPalette) {
        CommandPalette(onClose = { showPalette = false }) { command -> run(command.id) }
    }

    if (showShortcuts) ShortcutsOverlay { showShortcuts = false }

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

    // The packet the Filters page would have shown, before a description is sent off.
    filterConsent?.let { pending ->
        FilterConsentDialog(
            packet = pending.packet,
            onSend = { allowFilterWords() },
            onCancel = {
                filterConsent = null
                appendRook(pending.account, Said("result", "The person did not allow describing filters in words, so no filter was made."))
            },
        )
    }

    // The same dialog whether this is the first time Summarise has been pressed or a
    // later look asked for on demand: see [PacketViewer].
    summarisePacket?.let { packet ->
        PacketViewer(
            packet = packet,
            agreed = summariseAgreed,
            onSend = {
                summarisePacket = null
                runSummarise(packet, Assistant.config(), summariseFor.orEmpty())
            },
            onAgree = { Assistant.agree(Assistant.SUMMARISE); summariseAgreed = true },
            onDismiss = { summarisePacket = null },
        )
    }
    RookJobConsent(actionJob)

    attached?.let { mail ->
        val shots = remember(mail) {
            mail.partBytes.mapNotNull { (id, bytes) ->
                val part = mail.attachments.find { it.blobId == id } ?: return@mapNotNull null
                if (fileGlyph(part.type) != FileGlyph.IMAGE) return@mapNotNull null
                scaledPreview(bytes)?.let { id to it }
            }.toMap()
        }
        // Clicks on the dimmed mail behind this must not archive or open something else.
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Surface(
                Modifier.align(Alignment.Center).padding(32.dp).fillMaxSize(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 12.dp,
            ) {
                val who = mail.fromName.ifBlank { mail.fromEmail }.ifBlank { "Unknown sender" }
                Message(
                    summary = Summary(
                        id = "attached",
                        from = who,
                        fromEmail = mail.fromEmail,
                        subject = mail.subject.ifBlank { "(no subject)" },
                        receivedAt = mail.sentAt.orEmpty(),
                        preview = "",
                        seen = true,
                    ),
                    body = mail.body,
                    messageMode = messageMode,
                    readOnly = true,
                    onClose = {
                        attached = null
                        attachedSaved = null
                    },
                    attachments = mail.attachments,
                    images = shots,
                    imageBytes = mail.partBytes,
                    savedTo = attachedSaved,
                    onDownload = { part ->
                        val bytes = mail.partBytes[part.blobId]
                        if (bytes == null) {
                            report("That file is not in the attached message.")
                        } else {
                            scope.launch {
                                val path = io {
                                    Defender.check(Files.write(uniqueIn(downloadsFolder(), part.name), bytes))
                                }
                                if (path != null && attached === mail) attachedSaved = path.toString()
                            }
                        }
                    },
                    onDragFile = { part ->
                        val bytes = mail.partBytes[part.blobId]
                            ?: throw IllegalStateException("That file is not in the attached message.")
                        materializeAttachment { dir -> Defender.check(Files.write(uniqueIn(dir, part.name), bytes)) }
                    },
                    onDragFailed = { report("That file could not be dragged out.", it) },
                    onOpenTnef = { part ->
                        val bytes = mail.partBytes[part.blobId]
                        if (bytes == null) {
                            report("That file is not in the attached message.")
                        } else {
                            scope.launch {
                                val fetched = io {
                                    val contents = readTnef(bytes)
                                    if (contents != null) contents to null
                                    else null to Files.write(uniqueIn(downloadsFolder(), part.name), bytes)
                                } ?: return@launch
                                if (attached !== mail) return@launch
                                val (contents, saved) = fetched
                                if (contents != null) {
                                    tnef = contents
                                    tnefName = part.name
                                    tnefSaved = null
                                } else if (saved != null) {
                                    attachedSaved = saved.toString()
                                    report(TNEF_UNREADABLE)
                                }
                            }
                        }
                    },
                    onLink = { confirm = it },
                )
            }
        }
    }

    tnef?.let { contents ->
        TnefOverlay(
            name = tnefName,
            contents = contents,
            savedTo = tnefSaved,
            onSave = { fileName, bytes ->
                val open = tnef
                scope.launch {
                    val path = io { Files.write(uniqueIn(downloadsFolder(), fileName), bytes) }
                    if (path != null && tnef === open) tnefSaved = path.toString()
                }
            },
            onClose = {
                tnef = null
                tnefSaved = null
            },
        )
    }
    }
}

/**
 * How many messages are fetched ahead of being asked for. See the read ahead in [Reader].
 *
 * About a screenful. Enough that the ones somebody is looking at are already here, few
 * enough that opening a folder does not quietly download it.
 */
private const val READ_AHEAD = 12
