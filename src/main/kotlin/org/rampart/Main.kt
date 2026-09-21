package org.rampart

import kotlinx.serialization.json.JsonObject
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.skia.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.awt.Cursor
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

private val WHEN = DateTimeFormatter.ofPattern("d MMM  HH:mm").withZone(ZoneId.systemDefault())

/**
 * The update, as a line in the window's own bottom bar rather than a card over the mail.
 *
 * It used to float over the bottom right of the reading pane, which is exactly where a
 * message sits, and it offered Later: a decision nobody needed to make, since a bar that
 * waits indefinitely already does what Later was for. Nothing here fades in and out on its
 * own, and nothing shows when there is nothing to say: this sits under a client somebody
 * leaves open for days, so quiet has to be the default rather than an afterthought.
 *
 * The one animation is a slow fade on the icon, and only while [state] is [Waiting]: that
 * is the state that can sit unread for as long as somebody leaves it, so it is the only one
 * that earns something to draw the eye. [busy][UpdateBarState.busy] states get the app's own
 * spinner instead, which says "working" rather than "look at me", and a [Failed] bar is
 * read from the words alone.
 */
@Composable
internal fun UpdateBar(state: UpdateBarState, onClick: () -> Unit) {
    if (state is UpdateBarState.Hidden) return
    val clickable = state is UpdateBarState.Waiting || state is UpdateBarState.Failed
    val iconAlpha = if (state is UpdateBarState.Waiting) {
        val cycle = rememberInfiniteTransition(label = "update-ready")
        val alpha by cycle.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
            label = "update-ready-alpha",
        )
        alpha
    } else {
        1f
    }
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // `enabled` rather than a hand-built if/else choosing between a clickable
            // modifier and a bare one: the platform already has a disabled state for a
            // clickable, and reaching for it here is one call instead of a branch.
            .clickable(
                enabled = clickable,
                onClickLabel = when (state) {
                    is UpdateBarState.Failed -> "Try installing Rampart ${state.version} again"
                    is UpdateBarState.Waiting -> "Install Rampart ${state.version} now"
                    else -> null
                },
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            RampartIcons.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = iconAlpha),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(state.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (state.busy) Spinner(size = 13.dp, thickness = 2.dp)
    }
}

/**
 * What changed, shown once right after Rampart has just been the target of an update, or
 * again on demand from the version line at the bottom of the sidebar (the only way back
 * on once suppressed; see below). [changes] is already filtered to what has not been seen
 * yet, newest first: the same order [changelog] and Settings' own Changes() page already
 * read in, so nothing here re-sorts release notes a different way depending on where they
 * are looked at from. The row layout mirrors that page's for the same reason. Empty reads
 * as "you're all caught up" rather than a blank dialog, which is what a manual reopen sees
 * once the automatic one has already been through everything there was.
 *
 * Closing always calls [onClose] with the checkbox's state, whatever it is: recording the
 * running version as seen happens either way, because the checkbox decides whether the
 * *next* update announces itself, not whether this viewing counted. It starts unchecked
 * every time, including on a reopen of an already-suppressed dialog, which is deliberate:
 * there is nowhere else in this build to flip [Settings.changelogSuppressed] back off
 * (that page lives outside what this change is allowed to touch), so opening this by hand
 * and pressing "Got it" without ticking the box is itself how suppression is lifted.
 */
@Composable
private fun ChangelogDialog(changes: List<Change>, onClose: (suppress: Boolean) -> Unit) {
    var suppress by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onClose(suppress) },
        title = { Text("What's new in Rampart") },
        text = {
            Column {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    if (changes.isEmpty()) {
                        Text(
                            "You're all caught up.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    changes.forEach { change ->
                        val running = isRunning(change)
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.width(96.dp)) {
                                Text(
                                    change.version,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (running) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (running) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    change.date,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(change.what, style = MaterialTheme.typography.bodyMedium)
                                if (running) {
                                    Text(
                                        "This is the version you are running.",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.clickable(role = Role.Checkbox) { suppress = !suppress },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = suppress, onCheckedChange = { suppress = it })
                    Text("Don't show this again", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onClose(suppress) }) { Text("Got it") } },
    )
}

/**
 * Which changes the person running this has not been shown yet: everything in [all] whose
 * version is newer than [seen], in whatever order [all] already carries them.
 *
 * A pure function rather than an inline filter written twice, because both the automatic
 * startup dialog and a manual reopen from the version line ask this question and there
 * must be exactly one definition of "since last seen" for the two to agree on. [changelog]
 * hands its list newest first already, and nothing here reorders it a second way.
 */
internal fun unseenChanges(all: List<Change>, seen: String): List<Change> =
    all.filter { Updates.isNewer(it.version, seen) }

/**
 * What just happened to `update.stage`, from [Updates.stage]'s own boolean plus whatever it
 * left in [Updates.lastProblem]. Shared by both places that attempt a stage, the bar's own
 * effect and [installLatest]'s re-check, so there is one definition of "not ready yet"
 * rather than two that could quietly drift apart.
 */
private fun stageOutcome(staged: Boolean): UpdateStageCategory = when {
    staged -> UpdateStageCategory.STAGED
    Updates.lastProblem == Updates.NOT_READY -> UpdateStageCategory.NOT_READY
    else -> UpdateStageCategory.FAILED
}

/** One signed in mailbox. Several of these is the point; the password is in none of them. */
internal class Session(val account: SavedAccount, val jmap: MailBackend) {
    val key: String get() = "${account.email}@${account.server}"

    /**
     * This account's local copy, or null when there is nowhere safe to keep the key.
     *
     * Opened once and lazily: a person who never leaves the inbox should not pay for a file
     * being created, and a failure to open one is never a reason not to show mail. Rampart
     * without a local store is Rampart as it was last week, which works.
     */
    val store: Store? by lazy {
        runCatching {
            Secrets.mailKey(account)?.let { Store.open(Store.file(key), it) }
        }.getOrNull()
    }
}

/** What the sidebar needs to draw an account, with no live connection behind it. */
internal data class AccountMailboxes(
    val key: String,
    val name: String,
    val email: String,
    val mailboxes: List<Mailbox>,
)

fun main() {
    // Before anything is drawn, so a second copy costs a moment rather than a window.
    if (!SingleInstance.claim()) return
    // Before the first window, because it is read once when the scene is made.
    enableWebBody()
    application { Rampart() }
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

    // No tray on this desktop means no notifications, and nothing else changes. Constructing
    // one anyway logs a warning on every start and still cannot deliver anything.
    if (isTraySupported) {
        Tray(
            icon = remember(icon, unread) { BadgedIcon(icon, unread) },
            state = tray,
            tooltip = if (unread > 0) "Rampart, $unread unread" else "Rampart",
            onAction = ::show,
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
        // Settings.dark() is the pre-themes switch. Reading it here is what stops an
        // existing install opening light again the first time it runs a build with themes.
        var theme by remember { mutableStateOf(themeFor(Settings.theme(), Settings.dark() ?: followSystem)) }
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
        // glyphs should not have to choose between them.
        CompositionLocalProvider(
            LocalRampartTheme provides theme,
            LocalIconPack provides pack,
        ) {
            MaterialTheme(colorScheme = theme.scheme(), typography = RampartTypography) {
                Surface(Modifier.fillMaxSize()) {
                    App(
                        onTheme = { theme = it; Settings.setTheme(it.key) },
                        icons = pack,
                        onIcons = { pack = it; Settings.setIconPack(it.key) },
                        onQuit = ::quit,
                        notify = { title, message ->
                            tray.sendNotification(Notification(title, message, Notification.Type.Info))
                        },
                        onUnread = { unread = it },
                        windowSize = { windowState.size },
                    )
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
    /** Unread across every signed-in inbox, for the badge on the tray icon. */
    onUnread: (Int) -> Unit = {},
    /** See [Reader], which is what actually needs this. */
    windowSize: () -> DpSize = { DefaultWindowSize },
) {
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
                runCatching { Session(account, openSaved(account, password)) }.getOrNull()
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
        Reader(
            sessions, onTheme, onQuit, notify,
            onAddAccount = { adding = true },
            icons = icons, onIcons = onIcons, onUnread = onUnread,
            windowSize = windowSize,
        )
    }
}

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
    var trying by remember { mutableStateOf("") }
    var serverOpen by remember { mutableStateOf(false) }
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

        OutlinedTextField(
            user, { user = it },
            label = { Text("Email address") },
            singleLine = true,
            modifier = Modifier.width(380.dp),
        )
        OutlinedTextField(
            password, { password = it },
            label = { Text("App password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(380.dp),
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
                onClick = {
                    busy = true
                    error = ""
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
                            error = whyFailed(e)
                        } finally {
                            busy = false
                            trying = ""
                        }
                    }
                },
            ) { Text(if (busy) "Connecting" else "Connect") }
        }
        if (trying.isNotBlank()) {
            Text(
                trying,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(380.dp),
            )
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

/**
 * Everything about one message that has to be fetched, or decided, before its card in the
 * thread stack can be drawn.
 *
 * One map, [Reader]'s `cards`, keyed by message id, rather than a body map and a bodyError
 * map and an attachments map and so on side by side: those were nine declarations that
 * moved together, were cleared together and were never once independent of each other, so
 * the next field a card needs would otherwise be three edits in three places rather than
 * one field here.
 */
internal data class Card(
    val body: Body? = null,
    /** Why [body] would not open, when it would not. Set only when there was nothing kept. */
    val bodyError: String? = null,
    val attachments: List<Attachment> = emptyList(),
    /** Pictures this message carries, decoded, by blob id. */
    val images: Map<String, ImageBitmap> = emptyMap(),
    /** The same parts undecoded, for the engine, which wants bytes rather than a bitmap. */
    val imageBytes: Map<String, ByteArray> = emptyMap(),
    /** What happened after pressing Unsubscribe on this message, when it was pressed. */
    val unsubscribed: String? = null,
    /** This message's own raw source, once "View source" has asked for it. */
    val source: String? = null,
    /** Where an attachment, or this message's source, was last saved to. */
    val saved: String? = null,
    /**
     * Whether the fetch for this card ran all the way through.
     *
     * Not the same question as whether [body] is set. The body is filled from disk first
     * and then again from the server, and the parts and pictures follow it, so a load
     * interrupted part way leaves a card with a body and no attachments. Asking "has a
     * body" before deciding to skip meant that card was never completed: it stayed
     * half loaded for as long as the conversation was open, with its attachments missing
     * and no way to ask for them again.
     */
    val loaded: Boolean = false,
)

@Composable
private fun Reader(
    sessions: List<Session>,
    onTheme: (Theme) -> Unit,
    onQuit: () -> Unit,
    notify: (String, String) -> Unit,
    onAddAccount: () -> Unit,
    icons: IconPack = LineIcons,
    onIcons: (IconPack) -> Unit = {},
    /** Unread across every signed-in inbox, for the badge on the tray icon. */
    onUnread: (Int) -> Unit = {},
    /**
     * The window's current content size, read fresh rather than carried as a plain value: a
     * compose panel drag needs it only at the moment of the drag, and a state read here
     * would recompose this whole screen on every frame of an unrelated window resize.
     * [Rampart] already owns a [androidx.compose.ui.window.WindowState] for exactly this;
     * this is that state's `size`, handed down rather than re-asked for.
     */
    windowSize: () -> DpSize = { DefaultWindowSize },
) {
    val scope = rememberCoroutineScope()
    var identities by remember { mutableStateOf<Map<String, List<Identity>>>(emptyMap()) }
    var vacation by remember { mutableStateOf<Vacation?>(null) }
    var vacationError by remember { mutableStateOf<String?>(null) }
    var composing by remember { mutableStateOf<Draft?>(null) }
    // Where the autosaved copy of what is being written currently lives, so the next save
    // replaces it rather than adding another, and sending or discarding can clear it away.
    var draftId by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showingResults by remember { mutableStateOf(false) }
    /**
     * The tag being looked at, and whose account, or null for an ordinary folder view.
     *
     * A third kind of list beside a folder and a search result. It is not a folder: the
     * messages in it are wherever they were filed, which is the point of a tag.
     */
    var viewingTag by remember { mutableStateOf<String?>(null) }
    /** Every tag each account has, read out of its local copy. */
    var tagsSeen by remember { mutableStateOf<Map<String, Map<String, Int>>>(emptyMap()) }
    var tagColours by remember { mutableStateOf(Settings.tagColours()) }
    var folded by remember { mutableStateOf(Settings.collapsedSections()) }
    var tintRows by remember { mutableStateOf(Settings.tintRowsByTag()) }
    var undoBarSeconds by remember { mutableStateOf(Settings.undoBarSeconds()) }
    var loader by remember { mutableStateOf(Loader.of(Settings.loader())) }
    var pendingTrack: Tracked? = null
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
    /** Null until it has been counted, which is one pass over the local copy. */
    var stats by remember { mutableStateOf<MailStats?>(null) }
    // The server's cards, with the JSON each came from, so a save can be built on top
    // of it and leave the properties this build does not draw alone.
    var contacts by remember { mutableStateOf<List<Pair<Contact, JsonObject>>>(emptyList()) }
    var contactBooks by remember { mutableStateOf<List<ContactBook>>(emptyList()) }
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
    // Not remembered between runs on purpose: opening the app into a folder that is hiding
    // most of itself, with no memory of having asked for that, reads as lost mail.
    var quick by remember { mutableStateOf(QuickFilters()) }
    /**
     * Why a toggle could not be answered from the saved copy, when that is the only
     * place left to ask. Null the rest of the time, including when the toggle is simply
     * off. Cleared as soon as a later load does not need it.
     */
    var filterNote by remember { mutableStateOf<String?>(null) }
    var paper by remember { mutableStateOf(false) }
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
    var globalFilters by remember { mutableStateOf(Filters.read()) }
    // Who you write to, per account, read once and kept up to date as mail goes past.
    var books by remember { mutableStateOf<Map<String, List<Person>>>(emptyMap()) }
    // A folder operation waiting on a name, or on a yes.
    var folderAsk by remember { mutableStateOf<FolderAsk?>(null) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    // Set when a page comes back short, so the bottom of a folder is not re-queried forever.
    var exhausted by remember { mutableStateOf(false) }
    val searchField = remember { FocusRequester() }
    val keyboard = remember { FocusRequester() }
    var mailboxes by remember { mutableStateOf<Map<String, List<Mailbox>>>(emptyMap()) }

    /*
     * The number on the tray icon.
     *
     * Every signed-in inbox, added up, and only the inboxes: a count that included Junk
     * and Archive would go up when the filter caught something, which is the opposite of
     * what a badge is for. Sent from here because this is where the counts are, and read
     * where the tray is, which is outside the window on purpose.
     */
    LaunchedEffect(mailboxes) {
        onUnread(mailboxes.values.sumOf { boxes -> folderFor("inbox", boxes)?.unread ?: 0 })
    }
    var here by remember { mutableStateOf<Pair<String, Mailbox>?>(null) }
    var emails by remember { mutableStateOf<List<Summary>>(emptyList()) }
    var selected by remember { mutableStateOf<Summary?>(null) }
    /**
     * Everything fetched for the open conversation's messages, by id. See [Card].
     *
     * A card that is expanded needs its own body, and a reader who opens four messages in
     * a thread must not pay for the first one again when they come back to it. See
     * [loadCard], which is the only place anything is written in here.
     */
    var cards by remember { mutableStateOf<Map<String, Card>>(emptyMap()) }
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
     * Messages filed out of this conversation while the rest of it was still being fetched.
     *
     * The thread request goes out when a conversation opens and lands a moment later. Archive
     * a card in between and the answer, which was assembled before the move, puts it back on
     * screen as though the button had not worked. Held here so the thread is filtered by it
     * whenever it is assigned.
     */
    var filed by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** The card [expanded] should be scrolled into view once it has been laid out. */
    var pendingScrollTo by remember { mutableStateOf<String?>(null) }
    /** Whether the open conversation is muted. Read off disk when the message changes. */
    var conversationMuted by remember { mutableStateOf(false) }
    // Held rather than read where it is used, so changing it in Settings changes the
    // message already on screen instead of the one after next.
    var messageMode by remember { mutableStateOf(Settings.messageMode()) }
    var messageScale by remember { mutableStateOf(Settings.messageScale()) }
    var chatOpen by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<List<Said>>(emptyList()) }
    var chatThinking by remember { mutableStateOf(false) }
    // Read once rather than on every recomposition of the panel: it comes off disk.
    var chatAgreed by remember { mutableStateOf(Assistant.agreed(Assistant.CHAT)) }
    /*
     * Every message id this panel has put on screen in this conversation.
     *
     * The whole of the guard against a message talking the model into acting on something
     * nobody asked about: an action can only name an id that came out of a search run
     * here. Cleared with the transcript, because a new conversation has been shown nothing.
     */
    val chatShown = remember { mutableSetOf<String>() }
    /*
     * The paragraph a thread was last summarised into, and everything about getting one.
     *
     * Kept beside [thread] rather than derived from it: asking the model is a request that
     * costs money and takes a moment, so what it answered has to survive recomposition
     * until something explicitly asks for a fresh one.
     */
    var threadSummary by remember { mutableStateOf<String?>(null) }
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
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    var showShortcuts by remember { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }

    fun session(key: String) = sessions.first { it.key == key }

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

    /** [Card] for [message], or an empty one for a message nothing has been fetched for yet. */
    fun cardFor(message: Summary?): Card = message?.let { cards[it.id] } ?: Card()

    /** [cardFor], with [edit] applied and written back. The only way anything joins [cards]. */
    fun updateCard(id: String, edit: Card.() -> Card) {
        cards = cards + (id to (cards[id] ?: Card()).edit())
    }

    /**
     * Whether [message]'s pictures are drawn: a sender allowed for good, from [allowedSenders],
     * or one the reader said yes to just for this message, from [shownOnce].
     */
    fun showRemoteFor(message: Summary?): Boolean =
        message != null && (imageSenderKey(message.fromEmail) in allowedSenders || message.id in shownOnce)

    /**
     * The account a new message is written from. The one being replied to, when there is
     * one, so a reply in the merged inbox goes back out of the mailbox it arrived in.
     */
    fun writingAccount(): String? = accountOf(selected) ?: sessions.firstOrNull()?.key

    /**
     * The address to write as, given the message being answered.
     *
     * The account's first identity for a new message, and for a reply or a forward the one
     * the message was actually addressed to. See [identityFor].
     */
    fun writingIdentity(body: Body?): String {
        val mine = identities[writingAccount()].orEmpty().map { it.email }
        return identityFor(body, mine, mine.firstOrNull().orEmpty())
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

    /** Settings are always about one real account, never about the merged row. */
    fun settingsAccount(): String? =
        here?.first?.takeIf { it != ALL_ACCOUNTS } ?: sessions.firstOrNull()?.key

    /** The inbox of every signed in account, as one list. */
    suspend fun everyInbox(memory: Map<String, List<Person>>, asked: QuickFilters): List<Summary> = merged(
        sessions.associate { open ->
            val inbox = folderFor("inbox", mailboxes[open.key].orEmpty())
            open.key to (
                inbox?.let {
                    runCatching {
                        if (showingResults && query.isNotBlank()) open.jmap.search(query, it.id)
                        else {
                            val known = if (asked.knownSender) knownAddresses(open.key, memory) else emptySet()
                            quickPage(open.jmap, open.store, it.id, asked, known)
                        }
                    }.getOrDefault(emptyList())
                } ?: emptyList()
                )
        },
    )

    suspend fun <T> io(block: () -> T): T? = try {
        error = ""
        withContext(Dispatchers.IO) { block() }
    } catch (e: Exception) {
        error = whyFailed(e)
        null
    }

    /**
     * Fills in one message's body and attachments, the same round trip whether it is the
     * one clicked from the list or one expanded afterwards inside its thread.
     *
     * [force] re-fetches even when [cards] already has a body, which is right exactly once:
     * the message a click just opened, because a body read before can gain a decoded part
     * or lose a broken one between reads. Every other card is left alone when it is already
     * here, which is the whole reason collapsing one and opening it again is free.
     */
    suspend fun loadCard(key: String, id: String, force: Boolean = false) {
        if (!force && cards[id]?.loaded == true) return
        val kept = io { session(key).store?.body(id) }
        if (kept != null) {
            Diagnostics.count(Metric.MESSAGE_OPEN_CACHE_HIT)
            updateCard(id) { copy(body = kept) }
        }
        // Spans the round trip below, cache hit or not: the card always re-asks the server
        // even when a kept copy is shown first, so this is the number that answers "why did
        // opening this message feel slow just now."
        Diagnostics.time(Metric.MESSAGE_OPEN_FETCH) {
            coroutineScope {
                // The body and the parts list do not depend on each other and each is a round
                // trip, so they go out together rather than one after the other.
                val fetchedBody = async(Dispatchers.IO) { tried { session(key).jmap.body(id) } }
                val fetchedParts = async(Dispatchers.IO) { tried { session(key).jmap.attachments(id) } }
                val fresh = fetchedBody.await().fold(
                    onSuccess = { it.also { b -> io { session(key).store?.putBody(id, b) } } },
                    onFailure = { e ->
                        // Only a failure when there was nothing kept. Offline, with a copy on
                        // disk, is a card that opens rather than an error where it should be.
                        if (kept == null) updateCard(id) { copy(bodyError = whyFailed(e)) }
                        kept
                    },
                )
                if (fresh != null) updateCard(id) { copy(body = fresh) }
                val parts = fetchedParts.await().getOrNull() ?: emptyList()
                /*
                 * Finished only when there is actually a body, which is not the same as having
                 * tried. A fetch that failed with nothing on disk leaves an error where the
                 * message should be, and calling that loaded would mean the card never asked
                 * again: opening it a second time would return early and show the same error
                 * for as long as the conversation stayed open, with the network long since back.
                 *
                 * Marked here rather than after the pictures below, because a card with its body
                 * and its parts list is complete enough not to be fetched from the top again.
                 */
                updateCard(id) { copy(attachments = parts, loaded = fresh != null) }
                // Images the message carries with it are drawn. Fetching them asks the server
                // this account is already signed in to, so it tells the sender nothing, which
                // is the whole difference between these and the remote ones that stay blocked.
                val embedded = parts.filter { it.inline && it.type.startsWith("image/") }
                if (embedded.isEmpty()) return@coroutineScope
                val blobs = embedded.map { part ->
                    async(Dispatchers.IO) {
                        tried { session(key).jmap.blob(part) }.getOrNull()?.let { part.blobId to it }
                    }
                }.awaitAll().filterNotNull().toMap()
                val images = blobs.mapNotNull { (blobId, raw) ->
                    // A part that claims to be an image and is not must not take the card down
                    // with it.
                    runCatching { Image.makeFromEncoded(raw).toComposeImageBitmap() }.getOrNull()?.let { blobId to it }
                }.toMap()
                updateCard(id) { copy(imageBytes = blobs, images = images) }
            }
        }
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
            if (!barState.busy) {
                val found = withContext(Dispatchers.IO) { Updates.newerVersion() }
                update = found
                // A failed check and "nothing newer" both come back null from newerVersion:
                // CURRENT covers both here rather than claiming a distinction this call site
                // cannot actually tell apart.
                Diagnostics.event(
                    Metric.UPDATE_CHECK,
                    if (found != null) UpdateCheckCategory.NEWER_FOUND else UpdateCheckCategory.CURRENT,
                )
            }
            delay(30 * 60_000L)
        }
    }

    /*
     * Fetched as soon as there is one, quietly, while the app carries on.
     *
     * A mail client is open for days, so the moment somebody finally agrees to restart is
     * the worst possible moment to begin a download. Once this has run, pressing the button
     * swaps files that are already on the machine, and closing Rampart without pressing
     * anything installs it anyway.
     *
     * Once per version. A fetch that failed is not retried in a loop: the next half-hourly
     * check finds the same version, and nothing here is allowed to become a machine that
     * downloads a package over and over.
     */
    LaunchedEffect(update) {
        val version = update ?: return@LaunchedEffect
        val already = (barState as? UpdateBarState.Waiting)?.version == version
        if (already || barState.busy) return@LaunchedEffect
        val staged = withContext(Dispatchers.IO) { Updates.stage() }
        Diagnostics.event(Metric.UPDATE_STAGE, stageOutcome(staged))
        if (staged) barState = UpdateBarState.Waiting(version)
    }

    /**
     * What a click on the bar means: check what is actually newest right now, fetch it
     * first if that turns out not to be what is already on disk, then hand it to Windows.
     *
     * The re-check is the whole point of this function. [from] may have been staged hours
     * or days ago, and "regardless of how many versions behind you are" means a click must
     * never quietly install something that stopped being current while it sat waiting; see
     * [Updates.targetVersion]. A working install is never observed from here: Windows kills
     * this process to swap the files in, partway through the wait inside
     * [Updates.restartToUpdate], so nothing after that call runs, which is the success case
     * rather than a bug in this function.
     */
    suspend fun installLatest(from: String) {
        barState = UpdateBarState.Staging(from)
        val fresh = withContext(Dispatchers.IO) { Updates.newerVersion() }
        val target = Updates.targetVersion(from, fresh)
        if (target != from) {
            val staged = withContext(Dispatchers.IO) { Updates.stage() }
            Diagnostics.event(Metric.UPDATE_STAGE, stageOutcome(staged))
            if (!staged) {
                barState = Updates.afterFailure(target, Updates.lastProblem)
                return
            }
        }
        barState = UpdateBarState.Installing(target)
        if (!withContext(Dispatchers.IO) { Updates.restartToUpdate() }) {
            // A genuine success never reaches here: Windows kills this process mid-call, so
            // FAILED is the only outcome this call site can ever actually record for
            // update.apply. See [Updates.restartToUpdate]'s own KDoc.
            Diagnostics.event(Metric.UPDATE_APPLY, UpdateApplyCategory.FAILED)
            barState = Updates.afterFailure(target, Updates.lastProblem)
        }
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
                val inbox = folderFor("inbox", found) ?: found.firstOrNull()
                if (inbox != null) here = open.key to inbox
            }
            // Only once every account is in, or it would land on one account's inbox and
            // move under whoever was reading it a second later.
            if (sessions.size > 1 && mailboxes.size == sessions.size && here?.first != ALL_ACCOUNTS) {
                here = ALL_ACCOUNTS to allInboxes(0)
            }
        }
        loading = false
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

    suspend fun reload() {
        val (openKey, mailbox) = here ?: return
        val key = openKey
        val memory = books
        val canAttach = if (key == ALL_ACCOUNTS) sessions.none { it.jmap is Imap } else session(key).jmap !is Imap
        // A toggle that cannot be answered must not stay on, or the row would say it is
        // filtering a list that was fetched without it.
        if (quick.attachment && !canAttach) quick = quick.copy(attachment = false)
        val asked = quick.asked(canAttach)
        val known = if (asked.knownSender && key != ALL_ACCOUNTS) {
            io { knownAddresses(key, memory) }.orEmpty()
        } else {
            emptySet()
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
            filterNote = if (found == null) {
                "This folder has no saved copy, so that filter cannot be answered."
            } else {
                null
            }
            emails = found.orEmpty()
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
        if (state != null && cached.isNotEmpty() && state == io { session(key).store?.cursor(mailbox.id) }) {
            loading = false
            exhausted = false
            return
        }
        val tagging = viewingTag
        emails = if (tagging != null) {
            /*
             * A tag is asked of every account that has it, and the answers are put together.
             *
             * A keyword lives in one mailbox and cannot be read from another, so this is the
             * only way one list can mean one idea. Each account is caught on its own, so one
             * server being unreachable loses its share rather than the whole list, and an
             * account that has never seen the keyword is not asked at all.
             */
            val holders = accountsWith(tagsSeen, tagging).ifEmpty { listOf(key) }
            withContext(Dispatchers.IO) {
                holders.flatMap { account ->
                    runCatching { session(account).jmap.withKeyword(tagging) }.getOrNull()
                        ?: runCatching { session(account).store?.withKeyword(tagging) }.getOrNull().orEmpty()
                }
            }.sortedByDescending { it.receivedAt }
        } else if (key == ALL_ACCOUNTS) {
            // One account failing is not the whole list failing, so each is caught inside
            // rather than out here: the others still show.
            withContext(Dispatchers.IO) { everyInbox(memory, asked) }
        } else {
            io {
                if (showingResults && query.isNotBlank()) session(key).jmap.search(query, mailbox.id)
                else quickPage(session(key).jmap, session(key).store, mailbox.id, asked, known)
            }
                // The server is still the authority on search, because it can see mail we
                // have never fetched. The local copy is the answer when it cannot be
                // reached, which is the difference between "no results" and "no network".
                ?: io { session(key).store?.search(query) }.takeIf { showingResults && query.isNotBlank() }
                ?: emptyList()
        }
        loading = false
        exhausted = false
        learnFrom(emails)
        // Written back after the server has answered, so the copy is what the server
        // last said rather than what we guessed it would say.
        if (plain && emails.isNotEmpty()) {
            io { session(key).store?.put(mailbox.id, emails) }
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
        val (key, mailbox) = here ?: return
        if (key == ALL_ACCOUNTS || showingResults || viewingTag != null || loadingMore || exhausted || loading) return
        loadingMore = true
        val memory = books
        val canAttach = session(key).jmap !is Imap
        val asked = quick.asked(canAttach)
        scope.launch {
            Diagnostics.time(Metric.LIST_PAGE_LOAD) {
                val page = io {
                    val known = if (asked.knownSender) knownAddresses(key, memory) else emptySet()
                    quickPage(
                        session(key).jmap,
                        session(key).store,
                        mailbox.id,
                        asked,
                        known,
                        from = emails.size,
                    )
                }.orEmpty()
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
        withContext(Dispatchers.IO) {
            runCatching { session(key).jmap.setKeyword(ids, "\$seen", true) }
            folderFor("archive", mailboxes[key].orEmpty())?.id?.let { into ->
                runCatching { session(key).jmap.move(ids, into) }
                runCatching { session(key).store?.forget(ids) }
            }
        }
        val gone = ids.toSet()
        emails = emails.filterNot { it.id in gone }
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
            if (row.id == selected?.id) continue
            // The unified inbox is rows from several accounts, so the account comes from the
            // row rather than from the folder. It used to return early here, which switched
            // the read ahead off entirely in the view most likely to be left open.
            val key = if (account == ALL_ACCOUNTS) accountOf(row) ?: continue else account
            val store = session(key).store ?: continue
            withContext(Dispatchers.IO) {
                if (store.body(row.id) == null) {
                    runCatching { store.putBody(row.id, session(key).jmap.body(row.id)) }
                }
            }
            // Between messages rather than inside one, because a fetch in flight cannot be
            // taken back. This is where a reader clicking something gets in front.
            yield()
        }
    }

    val pushes = remember { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(sessions) {
        val states = mutableMapOf<String, String>()
        val seen = mutableMapOf<String, Set<String>>()
        while (true) {
            for (open in sessions) {
                val inbox = folderFor("inbox", mailboxes[open.key].orEmpty()) ?: continue
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
                if ((here?.first == open.key || unified()) && !showingResults) reload()
                /*
                 * A muted conversation is quietened here, on the way in.
                 *
                 * This is the only place it can happen: nothing on the server knows what a
                 * thread is the way this does, so a mute applies when Rampart next sees the
                 * message rather than at delivery. Quietly, and never reported: a failed
                 * hush is the message staying in the inbox, which is the state it was
                 * already in.
                 */
                val hushed = found.fresh.filter { Muted.muted(open.key, it.threadId) }
                if (hushed.isNotEmpty()) hush(open.key, hushed.map { it.id })
                val announce = found.fresh.filterNot { it in hushed }
                if (notifyOnArrival) arrivalText(announce)?.let { (title, body) -> notify(title, body) }
            }
            // Until every account has been looked at once there is nothing to compare
            // against, so those first rounds come quickly rather than half a minute apart.
            val quiet = if (states.size == sessions.size) 30_000L else 5_000L
            withTimeoutOrNull(quiet) { pushes.receive() }
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
     * Counted on opening, and again whenever the list underneath has moved.
     *
     * Not on a timer and not in the background: it is a handful of queries over a local
     * file, so it is cheap when somebody is looking at it and pointless when nobody is.
     */
    LaunchedEffect(dashboardOpen, emails, here) {
        if (!dashboardOpen) return@LaunchedEffect
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

    LaunchedEffect(contactsOpen, sessions.size) {
        val key = writingAccount() ?: return@LaunchedEffect
        if (contacts.isNotEmpty() && !contactsOpen) return@LaunchedEffect
        if (!session(key).jmap.hasContacts()) return@LaunchedEffect
        contactsLoading = true
        contactsError = null
        try {
            withContext(Dispatchers.IO) {
                // Both in one trip. The books are what name and filter the list, so
                // fetching them separately would draw the list once without them.
                contactBooks = runCatching { session(key).jmap.addressBooks() }.getOrDefault(emptyList())
                contacts = session(key).jmap.contacts()
            }
        } catch (e: Exception) {
            // Never fatal. The address book built from mail is the one that has to work.
            contactsError = whyFailed(e)
        } finally {
            contactsLoading = false
        }
    }

    LaunchedEffect(settingsOpen, here) {
        val key = settingsAccount()
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

    /** Puts a tag on a message, wherever the message is, and remembers it locally. */
    fun tagMessage(message: Summary, keyword: String) {
        val key = accountOf(message) ?: return
        scope.launch {
            if (keyword in message.keywords) return@launch
            val keywords = message.keywords + keyword
            emails = emails.map { if (it.id == message.id) it.copy(keywords = keywords) else it }
            if (selected?.id == message.id) selected = selected?.copy(keywords = keywords)
            if (io { session(key).jmap.setKeyword(listOf(message.id), keyword, true) } == null) {
                emails = emails.map { if (it.id == message.id) it.copy(keywords = message.keywords) else it }
                if (selected?.id == message.id) selected = selected?.copy(keywords = message.keywords)
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
        scope.launch {
            val sent = withContext(Dispatchers.IO) {
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
                }.isSuccess
            }
            answering = null
            if (sent) {
                // Shown as answered straight away. The organiser's copy is what counts and
                // it has gone; re-reading our own part would say nothing new.
                invitation = meeting.copy(
                    attendees = meeting.attendees.map {
                        if (it.email.equals(identity.email, ignoreCase = true)) it.copy(status = answer.partstat) else it
                    },
                )
            } else {
                error = "The answer could not be sent."
            }
        }
    }

    /**
     * Opens a tag: the same list pane, showing everything carrying that keyword.
     *
     * Across every account that has it, because a tag is one idea even when it lives in two
     * mailboxes. An account that has never seen the keyword is not asked.
     */
    fun openTag(keyword: String) {
        selected = null
        query = ""
        showingResults = false
        viewingTag = keyword
        // here has not changed, so nothing else will start the load.
        scope.launch { reload() }
    }

    LaunchedEffect(here) {
        here ?: return@LaunchedEffect
        selected = null
        query = ""
        showingResults = false
        viewingTag = null
        reload()
    }
    LaunchedEffect(selected) {
        val message = selected ?: return@LaunchedEffect
        val key = accountOf(message) ?: return@LaunchedEffect
        paper = false
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
        expanded = expanded + message.id
        if (thread.none { it.id == message.id }) {
            thread = emptyList()
            // A summary, a pending packet or an error belongs to the conversation it was
            // made for. Left in place across a switch, it would be shown as if it were an
            // answer about whatever is open now.
            threadSummary = null
            summariseError = null
            summarisePacket = null
            summariseFor = null
            cards = emptyMap()
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
            fetchedThread = async(Dispatchers.IO) { tried { session(key).jmap.thread(message.threadId) } }
        }
        // A message read once opens with no round trip at all, which is most of what
        // "instant" means in a mail client. Still re-fetched underneath, because a body
        // can gain a decoded part or lose a broken one between reads.
        loadCard(key, message.id, force = true)

        /*
         * The meeting, when the message carries one.
         *
         * Read here rather than from the body, because an invitation is a part of its own:
         * Outlook and Google both send it as a third format inside multipart/alternative
         * beside the text and the HTML, and it is small enough that fetching it to find out
         * costs nothing worth saving.
         */
        cardFor(message).attachments.firstOrNull { it.type.equals("text/calendar", ignoreCase = true) }?.let { part ->
            invitation = withContext(Dispatchers.IO) {
                runCatching {
                    session(key).jmap.blob(part)?.let { invitationIn(String(it, Charsets.UTF_8)) }
                }.getOrNull()
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

        // A draft is not something to read. Clicking one puts it back in the composer,
        // under the id it is already saved at, so carrying on writing replaces that copy
        // instead of leaving the old one behind.
        if (here?.second?.role == "drafts" && !showingResults) {
            draftId = message.id
            sendError = null
            composing = draftOf(message, cardFor(message).body, identities[key].orEmpty().firstOrNull()?.email.orEmpty())
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
            io { session(key).jmap.markSeen(message.id) }
            emails = emails.map { if (it.id == message.id) it.copy(seen = true) else it }
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
    LaunchedEffect(openedByHand, thread) {
        val wait = Settings.markReadDelay()
        // Negative means never on its own, the same as everywhere else it is read.
        if (wait < 0) return@LaunchedEffect
        val waiting = thread.filter { it.id in openedByHand && it.id in expanded && !it.seen }
        if (waiting.isEmpty()) return@LaunchedEffect
        if (wait > 0) delay(wait)
        waiting.groupBy { accountOf(it) }.forEach { (key, rows) ->
            if (key == null) return@forEach
            val ids = rows.map { it.id }
            io { session(key).jmap.setKeyword(ids, "\$seen", true) }
            val marked = ids.toSet()
            thread = thread.map { if (it.id in marked) it.copy(seen = true) else it }
            emails = emails.map { if (it.id in marked) it.copy(seen = true) else it }
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
            if (id == selected?.id || cards[id]?.body != null) continue
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

    /*
     * Filing one message, wherever the click came from: the button above the open message
     * and the right-click menu on a row are the same operation and must behave the same
     * way, including leaving the same thing behind to undo. One function, two callers.
     *
     * Returns null when this account has no folder for that role, so a server with no
     * Archive never offers an Archive that would fail.
     */
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
    suspend fun sayJunk(key: String, ids: List<String>, from: String?, into: String) {
        val junk = folderFor("junk", mailboxes[key].orEmpty())?.id
        val spam = junkVerdict(junk, from, into) ?: return
        io {
            session(key).jmap.setKeyword(ids, "\$junk", spam)
            session(key).jmap.setKeyword(ids, "\$notjunk", !spam)
        }
    }

    suspend fun carryOut(key: String, message: Summary, into: String, from: String?, verb: String) {
        sayJunk(key, listOf(message.id), from, into)
        if (io { session(key).jmap.move(listOf(message.id), into) } == null) return
        // Out of the local copy as well, or the folder it left would show it
        // again the next time that folder is opened from disk.
        io { session(key).store?.forget(listOf(message.id)) }
        emails = emails.filterNot { it.id == message.id }
        // And out of the conversation on screen. A card archived from inside the stack used
        // to stay there, fully interactive, as though the button had not worked, until the
        // reader opened something else.
        thread = thread.filterNot { it.id == message.id }
        expanded = expanded - message.id
        openedByHand = openedByHand - message.id
        cards = cards - message.id
        // Remembered, because the thread request for this conversation may still be in
        // flight and its answer was assembled before this move.
        filed = filed + message.id
        if (selected?.id == message.id) selected = null
        // One message can be taken back the same way a batch can. Filing the
        // wrong thing is a click, and having to go and find it again is the
        // part that makes people slow and careful about a button.
        undo = from?.let { Undoable(listOf(Move(key, listOf(message.id), it)), verb) }
    }

    fun fileAway(message: Summary, role: String): (() -> Unit)? {
        val key = accountOf(message) ?: return null
        val target = folderFor(role, mailboxes[key].orEmpty()) ?: return null
        val from = sourceFolder(key)
        return {
            scope.launch {
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

                carryOut(key, message, into, from, pastTense(role))
            }
            Unit
        }
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
                ?: run { error = "This account would not make a $SNOOZE_FOLDER folder."; return@launch }
            val due = until.dueAt(ZonedDateTime.now()).toInstant()
            val from = sourceFolder(key)
            if (io { session(key).jmap.setKeyword(listOf(message.id), snoozeKeyword(due), true) } == null) return@launch
            if (io { session(key).jmap.move(listOf(message.id), folder) } == null) return@launch
            io { session(key).store?.forget(listOf(message.id)) }
            emails = emails.filterNot { it.id == message.id }
            if (selected?.id == message.id) selected = null
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
        withContext(Dispatchers.IO) {
            due.forEach { message ->
                snoozedIn(message.keywords)?.let {
                    runCatching { session(key).jmap.setKeyword(listOf(message.id), it.keyword, false) }
                }
                // Unread on the way back, because the point of snoozing was to deal with it
                // later and a message that returns already read returns invisible.
                runCatching { session(key).jmap.setKeyword(listOf(message.id), "\$seen", false) }
                runCatching { session(key).jmap.move(listOf(message.id), inbox.id) }
            }
        }
        io { session(key).store?.forget(due.map { it.id }) }
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
                        val notices = withContext(Dispatchers.IO) {
                            buildList {
                                sessions.forEach { open ->
                                    runCatching {
                                        val store = open.store ?: return@runCatching
                                        val inserted = store.recordFetches(found)
                                        if (inserted.isEmpty()) return@runCatching
                                        openText(
                                            opensToAnnounce(
                                                inserted,
                                                store.trackedByIds(inserted.map { it.id }),
                                            ),
                                        )?.let { add(it) }
                                    }
                                }
                            }
                        }
                        if (notifyOnOpen) notices.forEach { (title, body) -> notify(title, body) }
                        // Moved only after the rows are written, so a crash between the two
                        // re-reads rather than skips. The store ignores a duplicate.
                        Settings.setTrackingCursor(found.maxOf { it.at.toEpochMilli() })
                    }
                }
            }
            delay(120_000)
        }
    }

    /*
     * Its own round rather than a line inside the poll above, because a local function
     * cannot be called before it is declared and the poll is written further up.
     *
     * A minute is as often as it is worth asking: the messages here are ones somebody
     * deliberately put down, so a minute either way is nothing, and the folder shows what is
     * due in the meantime.
     */
    LaunchedEffect(sessions) {
        while (true) {
            sessions.forEach { runCatching { wakeSnoozed(it.key) } }
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
            emails = emails.map { if (it.id == message.id) it.copy(flagged = value) else it }
            if (selected?.id == message.id) selected = selected?.copy(flagged = value)
        }
        show(wanted)
        scope.launch {
            if (io { session(key).jmap.setKeyword(listOf(message.id), "\$flagged", wanted) } == null) {
                show(!wanted)
            }
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
        emails = emails.map { if (it.id in ids) it.copy(seen = read) else it }
        thread = thread.map { if (it.id in ids) it.copy(seen = read) else it }
        if (selected?.id in ids) selected = selected?.copy(seen = read)
        scope.launch {
            io { session(key).jmap.setKeyword(ids.toList(), "\$seen", read) }
            // The copy on disk learns it too. Without this a reload served from the local
            // store shows the row unread again for the moment before the server answers,
            // which is the same flash by a different route.
            here?.second?.id?.let { box -> io { session(key).store?.put(box, emails) } }
        }
    }

    fun markRead(message: Summary, read: Boolean) {
        accountOf(message)?.let { setSeen(it, setOf(message.id), read) }
    }

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
            runCatching { session(key).jmap.search(text, null, limit) }.getOrDefault(emptyList())

        override fun read(id: String): String? =
            runCatching { plainTextOf(session(key).jmap.body(id)) }.getOrNull()?.ifBlank { null }

        override fun file(ids: List<String>, role: String): Int {
            if (ids.isEmpty()) return 0
            val target = folderFor(role, mailboxes[key].orEmpty())?.id ?: return 0
            val from = sourceFolder(key)
            if (runCatching { session(key).jmap.move(ids, target) }.isFailure) return 0
            runCatching { session(key).store?.forget(ids) }
            // Undoable like every other move. An action nobody typed is the one that most
            // needs taking back.
            scope.launch {
                val gone = ids.toSet()
                emails = emails.filterNot { it.id in gone }
                if (selected?.id in gone) selected = null
                undo = from?.let { Undoable(listOf(Move(key, ids, it)), pastTense(role)) }
            }
            return ids.size
        }

        override fun markRead(ids: List<String>, read: Boolean): Int {
            if (ids.isEmpty()) return 0
            if (runCatching { session(key).jmap.setKeyword(ids, "\$seen", read) }.isFailure) return 0
            scope.launch { setSeen(key, ids.toSet(), read) }
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
            val letter = runCatching { session(key).jmap.body(id) }.getOrNull()
            scope.launch {
                val ours = identities[key].orEmpty().map { it.email }.toSet()
                sendError = null
                // The model's words go above the quoted original, where a person's would.
                val reply = replyTo(message, letter, identities[key].orEmpty().firstOrNull()?.email.orEmpty(), false, ours)
                composing = reply.copy(body = text.trim() + "\n\n" + reply.body)
            }
            return true
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
        val key = settingsAccount() ?: return
        val config = Assistant.config()
        Assistant.whyNot(Assistant.CHAT, config)?.let {
            said = said + Said("result", it)
            return
        }
        said = said + Said("user", question)
        chatThinking = true
        val history = said
        val folders = mailboxes[key].orEmpty().map { it.name }
        val who = sessions.firstOrNull { it.key == key }?.account?.email.orEmpty()
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    converse(
                        config = config,
                        key = Secrets.loadNamed(Assistant.KEY),
                        system = Chat.system(folders, who),
                        history = history,
                        shown = chatShown,
                        tools = toolsFor(key),
                        record = { tokensIn, tokensOut ->
                            Assistant.record(Assistant.CHAT, tokensIn, tokensOut, config)
                        },
                    )
                }.getOrElse { listOf(Said("result", it.message ?: "The model could not be reached.")) }
            }
            said = said + added
            chatThinking = false
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
    suspend fun summariseTurns(key: String, message: Summary): List<Turn> = coroutineScope {
        thread.ifEmpty { listOf(message) }.map { m ->
            async(Dispatchers.IO) {
                val text = cards[m.id]?.body?.let(::plainTextOf)
                    ?: runCatching { plainTextOf(session(key).jmap.body(m.id)) }.getOrDefault("")
                Turn(m.from, m.receivedAt, text)
            }
        }.awaitAll()
    }

    /** The exact bytes [runSummarise] would post, which is what the viewer shows first. */
    suspend fun summarisePacketFor(key: String, message: Summary, config: AssistantConfig): String =
        Llm.packet(config.model, Summarise.system(), Summarise.user(message.subject, summariseTurns(key, message)))

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
                onSuccess = { threadSummary = it.text.trim() },
                onFailure = { summariseError = it.message ?: "The model could not be reached." },
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
    fun summarise(message: Summary, key: String, config: AssistantConfig, viewFirst: Boolean = false) {
        if (summarising) return
        Assistant.whyNot(Assistant.SUMMARISE, config, key, currentFolderName(key))?.let {
            summariseError = it
            return
        }
        summariseError = null
        val forThread = message.threadId
        summarising = true
        scope.launch {
            val packet = withContext(Dispatchers.IO) { summarisePacketFor(key, message, config) }
            summarising = false
            // The reader has moved on to a different conversation while this was being
            // built. An answer or a permission dialog for a thread nobody is looking at
            // is not shown.
            if (selected?.threadId != forThread) return@launch
            if (viewFirst || !Assistant.agreed(Assistant.SUMMARISE)) {
                summariseFor = forThread
                summarisePacket = packet
            } else {
                runSummarise(packet, config, forThread)
            }
        }
    }

    /*
     * The same three acts, done to a whole conversation.
     *
     * Kept apart from the single-message versions rather than folded into them, because
     * what they leave behind is different: one message archived leaves the rest of the
     * thread in the inbox, and the undo for the conversation has to put twelve back. See
     * [conversationIds] for the two kinds of message they will not touch.
     */
    fun conversationOf(message: Summary): List<String> =
        conversationIds(thread, identities[accountOf(message)].orEmpty().map { it.email }.toSet())

    fun readConversation(message: Summary, read: Boolean) {
        accountOf(message)?.let { setSeen(it, conversationOf(message).toSet(), read) }
    }

    fun fileConversation(message: Summary, role: String, verb: String) {
        val key = accountOf(message) ?: return
        val target = folderFor(role, mailboxes[key].orEmpty())?.id ?: return
        val ids = conversationOf(message)
        if (ids.isEmpty()) return
        val from = sourceFolder(key)
        scope.launch {
            sayJunk(key, ids, from, target)
            if (io { session(key).jmap.move(ids, target) } == null) return@launch
            io { session(key).store?.forget(ids) }
            val gone = ids.toSet()
            emails = emails.filterNot { it.id in gone }
            selected = null
            thread = emptyList()
            // Twelve messages filed by one click is exactly the act that has to be
            // reversible, and a move is only ever undone by a move the other way.
            undo = from?.let { Undoable(listOf(Move(key, ids, it)), verb) }
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
        Muted.set(key, message.threadId, on)
        conversationMuted = on
        if (on) fileConversation(message, "archive", "Muted")
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

    val rowActions = RowActions(
        snooze = { message, until -> snooze(message, until) },
        reply = { message, all ->
            val key = accountOf(message)
            if (key != null) {
                scope.launch {
                    val ours = identities[key].orEmpty().map { it.email }
                    val text = io { session(key).jmap.body(message.id) }
                    composing = replyTo(message, text, writingIdentity(text), all, ours.toSet())
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
                    val forwarded = io { session(key).jmap.body(message.id) }
                    val mine = identities[key].orEmpty().map { it.email }
                    composing = forwardOf(message, forwarded, identityFor(forwarded, mine, mine.firstOrNull().orEmpty()))
                }
            }
        },
        archive = { message -> fileAway(message, "archive")?.invoke() },
        junk = { message -> fileAway(message, "junk")?.invoke() },
        notJunk = { message -> fileAway(message, "inbox")?.invoke() },
        isJunk = ::inJunk,
        trash = { message -> fileAway(message, "trash")?.invoke() },
        star = ::starOne,
        markRead = ::markRead,
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
        fun moveTo(role: String): (() -> Unit)? = fileAway(message, role)
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
            archive = moveTo("archive"),
            snooze = { until -> snooze(message, until) },
            trash = moveTo("trash"),
            junk = if (junked) null else moveTo("junk"),
            notJunk = if (junked) moveTo("inbox") else null,
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
            folders = boxes.filter { it.id != here?.second?.id && it.role !in MOVE_COVERED },
            // Only where there is a conversation to act on. One message is not one.
            conversation = thread.takeIf { it.size > 1 }?.let { all ->
                ConversationActions(
                    count = all.size,
                    unread = all.count { !it.seen },
                    muted = conversationMuted,
                    onRead = { read -> readConversation(message, read) },
                    onArchive = { fileConversation(message, "archive", "Archived") },
                    onTrash = { fileConversation(message, "trash", "Deleted") },
                    onMute = { on -> muteConversation(message, on) },
                )
            },
        )
    }

    // Keyboard shortcuts and the command palette act on whichever message is primary, the
    // one clicked from the list, the same as before there was more than one card to choose
    // between.
    val actions = selected?.let(::actionsFor) ?: MessageActions()

    /**
     * The Summarise button on the open thread, and what pressing it has done.
     *
     * Recomputed on every draw, the same as [actions] above it, because whether the button
     * can be pressed depends on things that can change while the window is open: the
     * ceiling, a key just typed into Settings, a folder just added to the never list.
     */
    val summariseState = run {
        val message = selected ?: return@run null
        val key = accountOf(message) ?: return@run null
        val config = Assistant.config()
        val why = Assistant.whyNot(Assistant.SUMMARISE, config, key, currentFolderName(key))
        SummariseActions(
            disabledBecause = why,
            running = summarising,
            paragraph = threadSummary,
            error = summariseError,
            onSummarise = { summarise(message, key, config) },
            // Null rather than a no-op when it is disabled: a denied folder must not be
            // summarisable at all, and offering "look at what would be sent" would still
            // build the very packet that is meant never to exist.
            onViewPacket = if (why == null) {
                { summarise(message, key, config, viewFirst = true) }
            } else {
                null
            },
        )
    }

    /** Shows [message] as it arrived, or puts it away again. Its own card, not a shared one. */
    fun toggleSource(message: Summary) {
        val key = accountOf(message) ?: return
        if (cardFor(message).source != null) {
            updateCard(message.id) { copy(source = null) }
            return
        }
        updateCard(message.id) { copy(source = "Fetching the original...") }
        scope.launch {
            val raw = io { session(key).jmap.raw(message.id) }
                ?: "The server would not hand over the original of this message."
            updateCard(message.id) { copy(source = raw) }
        }
    }

    /**
     * One message's card: its own header, avatar, body and action row, exactly what the
     * reading pane has always drawn for whichever message was open, called once per
     * expanded card rather than once for the whole pane.
     *
     * [cardSummary] is which message, never [selected]: several cards can be expanded at
     * once, and each answers Reply, Archive, Tag and the rest as itself. [invitation],
     * [answering] and [summariseState] stay tied to whichever message the conversation was
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
        val isPrimary = cardSummary.id == selected?.id
        Message(
            summary = cardSummary,
            body = card.body,
            onReply = { all ->
                sendError = null
                composing = replyTo(cardSummary, card.body, writingIdentity(card.body), all, ours)
            },
            replyAll = hasOtherRecipients(cardSummary, card.body, ours),
            onForward = {
                sendError = null
                composing = forwardOf(cardSummary, card.body, writingIdentity(card.body))
            },
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
                                    contacts = jmap.contacts()
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
                sendError = null
                composing = Draft(
                    from = identities[key].orEmpty().firstOrNull()?.email.orEmpty(),
                    to = to,
                    subject = receiptSubject(cardSummary.subject),
                    body = receiptBody(
                        cardSummary.subject,
                        java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME),
                        identities[key].orEmpty().firstOrNull()?.email.orEmpty(),
                    ),
                    inReplyTo = card.body?.messageId?.firstOrNull(),
                    references = card.body?.references.orEmpty() + card.body?.messageId.orEmpty(),
                    replying = true,
                )
            },
            onUnsubscribe = { off ->
                when {
                    // One-click is the only route that finishes without leaving Rampart,
                    // and it is the only one the sender promised would work that way.
                    off.oneClick && off.url != null -> {
                        updateCard(cardSummary.id) { copy(unsubscribed = "Asking to be taken off the list.") }
                        scope.launch {
                            val done = withContext(Dispatchers.IO) { oneClickPost(off.url) }
                            updateCard(cardSummary.id) {
                                copy(
                                    unsubscribed = if (done) {
                                        "Asked to be taken off the list. It can take a few days."
                                    } else {
                                        // Not an error worth a dialog: the link is still there.
                                        "That did not go through. Try the link instead."
                                    },
                                )
                            }
                        }
                    }
                    // A page to open is a page somebody should see before it acts, so it
                    // goes through the same confirmation as any other link in a message.
                    off.url != null -> confirm = off.url
                    off.mailto != null -> {
                        sendError = null
                        composing = Draft(
                            from = identities[key].orEmpty().firstOrNull()?.email.orEmpty(),
                            to = off.mailto,
                            subject = off.mailtoSubject ?: "unsubscribe",
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
                shownOnce = shownOnce + cardSummary.id
            },
            bodyError = card.bodyError,
            images = card.images,
            imageBytes = card.imageBytes,
            actions = actionsFor(cardSummary),
            summarise = if (isPrimary) summariseState else null,
            attachments = card.attachments,
            savedTo = card.saved,
            source = card.source,
            onSource = { toggleSource(cardSummary) },
            paper = paper,
            messageMode = messageMode,
            messageScale = messageScale,
            // Per message rather than a setting: it is a look at this one, and having
            // to turn it back off in settings would make it a mode instead.
            onPaper = { paper = it },
            onSaveSource = {
                val text = card.source
                if (text != null) {
                    scope.launch {
                        val path = io {
                            val at = downloadsFolder().resolve(emlName(cardSummary.subject, cardSummary.receivedAt))
                            java.nio.file.Files.writeString(at, text)
                            at
                        }
                        if (path != null) updateCard(cardSummary.id) { copy(saved = path.toString()) }
                    }
                }
            },
            onTag = { keyword, on ->
                if (key != null) {
                    // Same bargain as the star: it moves now, and moves back if the
                    // server says no. Nobody waits on a round trip to see a label.
                    fun put(value: Boolean) {
                        val keywords = if (value) cardSummary.keywords + keyword else cardSummary.keywords - keyword
                        emails = emails.map { if (it.id == cardSummary.id) it.copy(keywords = keywords) else it }
                        thread = thread.map { if (it.id == cardSummary.id) it.copy(keywords = keywords) else it }
                        if (selected?.id == cardSummary.id) selected = selected?.copy(keywords = keywords)
                    }
                    put(on)
                    scope.launch {
                        if (io { session(key).jmap.setKeyword(listOf(cardSummary.id), keyword, on) } == null) put(!on)
                    }
                }
            },
            onTyping = { typing = it },
            onDownload = { attachment ->
                if (key != null) {
                    scope.launch {
                        val landed = io {
                            val folder = downloadsFolder()
                            session(key).jmap.download(attachment, folder)
                        }
                        if (landed != null) updateCard(cardSummary.id) { copy(saved = landed.toString()) }
                    }
                }
            },
            showSubject = showSubject,
            onHeaderClick = onHeaderClick,
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
        val ours = identities[writingAccount()].orEmpty().map { it.email }.toSet()
        val from = ours.firstOrNull().orEmpty()
        when (id) {
            "compose" -> { sendError = null; composing = Draft(from = from) }
            "reply" -> selected?.let { composing = replyTo(it, cardFor(it).body, writingIdentity(cardFor(it).body)) }
            "reply-all" -> selected?.let {
                composing = replyTo(it, cardFor(it).body, writingIdentity(cardFor(it).body), true, ours)
            }
            "forward" -> selected?.let { composing = forwardOf(it, cardFor(it).body, writingIdentity(cardFor(it).body)) }
            "archive" -> actions.archive?.invoke()
            "trash" -> actions.trash?.invoke()
            // One command, and it does whichever of the two is the one on offer, so the
            // same key both files spam and rescues it depending on where you are.
            "junk" -> (actions.junk ?: actions.notJunk)?.invoke()
            "star" -> actions.star?.invoke()
            "read" -> {
                val message = selected
                val key = accountOf(message)
                if (message != null && key != null && !message.seen) {
                    emails = emails.map { if (it.id == message.id) it.copy(seen = true) else it }
                    scope.launch { io { session(key).jmap.setKeyword(listOf(message.id), "\$seen", true) } }
                }
            }
            "source" -> selected?.let { toggleSource(it) }
            "search" -> searchField.requestFocus()
            "refresh" -> scope.launch { refreshNow() }
            "next" -> emails.indexOfFirst { it.id == selected?.id }
                .let { if (emails.isNotEmpty()) selected = emails[nextIndex(it, emails.size, 1)] }
            "previous" -> emails.indexOfFirst { it.id == selected?.id }
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
            "settings" -> { settingsOpen = true; contactsOpen = false }
            "contacts" -> { contactsOpen = true; settingsOpen = false; dashboardOpen = false }
            "dashboard" -> { dashboardOpen = true; settingsOpen = false; contactsOpen = false }
            "shortcuts" -> showShortcuts = true
            "assistant" -> chatOpen = !chatOpen
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
        if (event.isCtrlPressed && event.key == Key.Comma) {
            settingsOpen = true
            return true
        }
        // Above the modifier guard below, because the modifier is what makes it this.
        if (event.isCtrlPressed && event.key == Key.K) {
            showPalette = true
            return true
        }
        // Shift is what makes it a question mark on most layouts, so the search key has to
        // say it does not want one or Shift+/ lands in the search box instead of the list.
        if (event.key == Key.Slash && event.isShiftPressed) {
            showShortcuts = true
            return true
        }
        if (event.key == Key.Slash && !typing && !event.isCtrlPressed && !event.isAltPressed) {
            searchField.requestFocus()
            return true
        }
        if (typing) return false
        // Everything below is a bare key. The same key held with a modifier belongs to
        // whatever the modifier means, and taking Ctrl+C to open a composer, or Ctrl+A and
        // Ctrl+F from select-all and find, is how a keyboard stops being trustworthy. One
        // guard rather than a check on each: every letter down there had the same fault.
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
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
            Key.C -> { sendError = null; composing = Draft(from = identities[writingAccount()].orEmpty().firstOrNull()?.email.orEmpty()); true }
            Key.U -> { run("unread-only"); true }
            Key.R -> {
                selected?.let { m -> composing = replyTo(m, cardFor(m).body, writingIdentity(cardFor(m).body)) }
                true
            }
            Key.F -> {
                selected?.let { m -> composing = forwardOf(m, cardFor(m).body, writingIdentity(cardFor(m).body)) }
                true
            }
            Key.A -> {
                selected?.let { m ->
                    val ours = identities[writingAccount()].orEmpty().map { it.email }.toSet()
                    val body = cardFor(m).body
                    if (hasOtherRecipients(m, body, ours)) {
                        composing = replyTo(m, body, writingIdentity(body), true, ours)
                    }
                }
                true
            }
            Key.S -> { actions.star?.invoke(); true }
            Key.E -> { actions.archive?.invoke(); true }
            Key.Delete, Key.Backspace -> { actions.trash?.invoke(); true }
            Key.Escape -> {
                if (query.isNotEmpty()) { query = ""; showingResults = false; scope.launch { reload() } }
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
    fun ComposerPanel(composer: Draft) {
        Composer(
            identities = identities[writingAccount()]?.map { it.email }.orEmpty(),
            // The sign-off comes off the identity on the server, so one written in Bulwark
            // is the one used here without anything having to be imported or kept in step.
            initial = identities[writingAccount()].orEmpty()
                .firstOrNull { it.email.equals(composer.from, ignoreCase = true) }
                ?.let { signed(composer, it.textSignature, it.htmlSignature, Settings.signatureAboveQuote()) }
                ?: composer,
            sending = sending,
            error = sendError,
            onDiscard = {
                // What was autosaved goes with it. Discard has to mean discarded, or the
                // Drafts folder fills with messages somebody decided against.
                val key = writingAccount()
                val going = draftId
                if (key != null && going != null) {
                    scope.launch { io { session(key).jmap.destroy(listOf(going)) } }
                }
                composing = null
                draftId = null
                sendError = null
            },
            onAttach = { files ->
                val key = writingAccount()
                val account = key?.let(::session)
                    ?: throw JmapError("Pick an account before attaching anything.")
                // One at a time rather than in parallel: a mail server is not a CDN, and
                // three large files racing each other is how an upload limit gets hit.
                withContext(Dispatchers.IO) { files.map { account.jmap.upload(it) } }
            },
            onSave = { draft ->
                val key = writingAccount()
                val account = key?.let(::session)
                val drafts = folderFor("drafts", mailboxes[key].orEmpty())
                val identity = identities[key].orEmpty().firstOrNull { it.email.equals(draft.from, true) }
                    ?: identities[key].orEmpty().firstOrNull()
                if (account == null || drafts == null || identity == null) {
                    throw JmapError("There is nowhere to save this: the account has no Drafts folder.")
                }
                draftId = withContext(Dispatchers.IO) {
                    account.jmap.saveDraft(draft, identity, drafts.id, draftId)
                }
            },
            book = withContacts(books[writingAccount()].orEmpty(), contacts.map { it.first }),
            full = composeFull,
            onFull = { composeFull = it },
            trackingReady = trackingServer.isNotBlank(),
            trackedBefore = { domain -> domain in Settings.trackedDomains() },
            onSend = { draft ->
                val key = writingAccount()
                val account = key?.let(::session)
                val boxes = mailboxes[key].orEmpty()
                val drafts = folderFor("drafts", boxes)
                val identity = identities[key].orEmpty().firstOrNull { it.email.equals(draft.from, true) }
                    ?: identities[key].orEmpty().firstOrNull()
                when {
                    account == null -> sendError = "Pick an account first."
                    identity == null -> sendError = "This account has no identity to send from."
                    drafts == null -> sendError = "This account has no Drafts folder, and the message is written there before it is sent."
                    else -> scope.launch {
                        sending = true
                        sendError = null
                        /*
                         * The pause before it goes, held here rather than asked of the
                         * server.
                         *
                         * Scheduled send is not possible against this server: it advertises
                         * submission with no maxDelayedSend, which per RFC 8621 means zero,
                         * so a future sendAt is refused. Holding it in the client is the
                         * only version of undo that works, and it is the version every
                         * webmail actually uses.
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
                        /*
                         * The tracking id is minted here, at the last moment, and never in
                         * the composer.
                         *
                         * A draft that is edited and saved five times would otherwise carry
                         * five ids, or the same id on two different messages if it were
                         * copied. Minting on the way out means one id belongs to exactly one
                         * message that actually went.
                         *
                         * The Message-ID is minted with it, because a tracked message keeps
                         * a different object in Sent from the one that was sent and the two
                         * have to agree or the reply threads against nothing.
                         */
                        val trackingBase = Settings.trackingServer()
                        val outgoing = if (!draft.tracked || trackingBase.isBlank()) {
                            draft
                        } else {
                            val id = newTrackingId()
                            draft.copy(
                                trackingPixel = pixelHtml(trackingBase, id),
                                messageId = newTrackingId() + "@" + (domainOf(identity.email).ifBlank { "rampart.invalid" }),
                            ).also { ready ->
                                pendingTrack = Tracked(
                                    id = id,
                                    messageId = ready.messageId.orEmpty(),
                                    account = key,
                                    recipient = draft.recipients.firstOrNull().orEmpty(),
                                    subject = draft.subject,
                                    sentAt = Instant.now(),
                                )
                            }
                        }
                        try {
                            // Measured from here, not from the undo wait above: that pause is
                            // deliberate UX, not server latency, and counting it would make
                            // every send look exactly [Settings.undoSeconds] slower than it was.
                            Diagnostics.time(Metric.SEND_COMPOSE_TO_SENT) {
                                withContext(Dispatchers.IO) {
                                    account.jmap.send(outgoing, identity, drafts.id, folderFor("sent", boxes)?.id)
                                    // Written down only once it has actually gone. A tracked id
                                    // for a message that failed to send would sit in the list
                                    // forever waiting for an open that cannot come.
                                    pendingTrack?.let { account.store?.track(it) }
                                    // The sent message is its own copy in Sent, so the working
                                    // copy in Drafts is now a duplicate of mail already gone.
                                    draftId?.let { runCatching { account.jmap.destroy(listOf(it)) } }
                                }
                            }
                            // Who you write to counts for more than who writes to you,
                            // so a sent message is the strongest signal the book gets.
                            // Remembered by domain, so the next message to the same place
                            // starts with the same answer rather than asking again.
                            draft.recipients.firstOrNull()?.let {
                                Settings.rememberTracking(trackingDomain(it), draft.tracked)
                            }
                            pendingTrack = null
                            var book = books[key] ?: AddressBook.read(AddressBook.file(key))
                            draft.recipients.forEach { book = noted(book, it) }
                            books = books + (key to book)
                            runCatching { AddressBook.write(book, AddressBook.file(key)) }
                            composing = null
                            draftId = null
                        } catch (e: Exception) {
                            sendError = whyFailed(e)
                            Diagnostics.event(Metric.SEND_FAILURE, sendFailureCategoryOf(e))
                        } finally {
                            sending = false
                        }
                    }
                }
            },
        )

    }

    LaunchedEffect(Unit) { runCatching { keyboard.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize()
            .focusRequester(keyboard)
            .focusable()
            .onPreviewKeyEvent(::shortcut),
    ) {
        // Offered in the same place as the other undo, because they are the same promise:
        // the thing you just did can be taken back without going and finding it.
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
                text = movedNotice(last.count, last.what),
                seconds = undoBarSeconds,
                restartOn = last,
                onUndo = {
                    scope.launch {
                        // Every account is put back, and the notice only clears if they all
                        // did. One that failed leaves the offer up rather than pretending.
                        val allBack = withContext(Dispatchers.IO) {
                            last.moves.all { move ->
                                runCatching { session(move.accountKey).jmap.move(move.ids, move.fromMailboxId) }
                                    .isSuccess
                            }
                        }
                        if (allBack) {
                            undo = null
                            refreshNow()
                        }
                    }
                },
                // Only this one expires. The move can still be undone by hand afterwards,
                // so the offer running out costs nothing.
                onExpire = { undo = null },
                onDismiss = { undo = null },
            )
        }
        if (error.isNotBlank()) {
            // Dismissible, because an error that can only be cleared by succeeding at
            // something else sits there long after it stopped being true, and then it is
            // read as the state of the app rather than as one thing that went wrong.
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { error = "" }, modifier = Modifier.size(24.dp)) {
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
         * The pictures, decoded once rather than per row.
         *
         * Only the ones carried inside a contact card, which is how a vCard photo has
         * always travelled and costs no request. A card that states a URL instead is left
         * alone: that is somebody else's server, and fetching it on every message is the
         * leak the image blocker exists to stop.
         */
        val senderPhotos = remember(contacts) {
            buildMap {
                contacts.forEach { (contact, _) ->
                    val picture = contact.photo.takeIf { it.startsWith("data:image", true) }
                        ?.let { embeddedImage(it) } ?: return@forEach
                    contact.emails.forEach { put(it.trim().lowercase(), picture) }
                }
            }
        }
        CompositionLocalProvider(
            LocalSenderPhotos provides senderPhotos,
            LocalTagColours provides tagColours,
            LocalTintRowsByTag provides tintRows,
            LocalLoader provides loader,
        ) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(
                asking = chatOpen,
                onAsk = { chatOpen = !chatOpen },
                search = {
                    SearchBar(
                        query = query,
                        focusRequester = searchField,
                        onFocusChanged = { typing = it },
                        onQueryChange = { query = it },
                        onSearch = {
                            settingsOpen = false
                            contactsOpen = false
                            showingResults = query.isNotBlank()
                            selected = null
                            scope.launch { reload() }
                        },
                    )
                },
                accounts = sessions.map {
                    AccountMailboxes(it.key, it.account.name, it.account.email, mailboxes[it.key].orEmpty())
                },
                here = here,
                tags = remember(tagsSeen, tagColours) { mergedTags(tagsSeen, tagColours) },
                hereTag = viewingTag,
                onSelectTag = { keyword ->
                    settingsOpen = false
                    contactsOpen = false
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
                onSettings = { settingsOpen = !settingsOpen; if (settingsOpen) { contactsOpen = false; dashboardOpen = false } },
                onDashboard = { dashboardOpen = !dashboardOpen; if (dashboardOpen) { contactsOpen = false; settingsOpen = false } },
                inDashboard = dashboardOpen,
                inSettings = settingsOpen,
                onContacts = { contactsOpen = !contactsOpen; if (contactsOpen) { settingsOpen = false; dashboardOpen = false } },
                inContacts = contactsOpen,
                onAddAccount = onAddAccount,
                collapsed = collapsed,
                onToggleCollapsed = { collapsed = !collapsed; Settings.setSidebarCollapsed(collapsed) },
                onViewChangelog = { changelogDialog = unseenChanges(changelog(), Settings.changelogSeen()) },
                folderMenu = { key, box, job -> folderAsk = FolderAsk(key, box, job) },
                onSelect = { key, mailbox -> here = key to mailbox },
                onWrite = {
                    val from = identities[writingAccount()].orEmpty().firstOrNull()?.email
                        ?: sessions.firstOrNull { it.key == writingAccount() }?.account?.email.orEmpty()
                    sendError = null
                    composing = Draft(from = from)
                },
            )
            folderAsk?.let { ask ->
                FolderDialog(
                    ask = ask,
                    error = folderError,
                    onClose = { folderAsk = null; folderError = null },
                    onConfirm = { answer -> doFolderJob(ask, answer) },
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
            if (dashboardOpen) {
                DashboardPane(
                    stats = stats,
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
                    },
                )
            } else if (contactsOpen) {
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
                                        contacts = jmap.contacts()
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
                                        contacts = jmap.contacts()
                                    }
                                } catch (e: Exception) {
                                    contactsError = whyFailed(e)
                                }
                            }
                        }
                    },
                    onWrite = { address ->
                        contactsOpen = false
                        sendError = null
                        composing = Draft(
                            from = identities[writingAccount()].orEmpty().firstOrNull()?.email.orEmpty(),
                            to = address,
                        )
                    },
                )
            } else if (settingsOpen) {
                SettingsPane(
                    accounts = sessions.map {
                        AccountMailboxes(it.key, it.account.name, it.account.email, mailboxes[it.key].orEmpty())
                    },
                    identities = identities[settingsAccount()].orEmpty(),
                    quotas = quotas,
                    onTintRowsByTag = { tintRows = it },
                    onUndoBarSeconds = { undoBarSeconds = it },
                    onLoader = { loader = it },
                    onTrackingServer = { trackingServer = it },
                    vacation = vacation,
                    vacationError = vacationError,
                    onVacation = { wanted ->
                        val key = settingsAccount()
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
                        val key = settingsAccount()
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
                     * not a bare `restartToUpdate()`. Before the bar existed this button
                     * called that directly and then quit itself, which is now wrong twice
                     * over: `restartToUpdate()` alone can install a version that went stale
                     * while it sat staged, and quitting here raced the install, since the
                     * process that is actually supposed to end this one is PowerShell's own
                     * `-ForceTargetApplicationShutdown`, not a call made from inside it.
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
                    onClose = { settingsOpen = false },
                )
                return@Row
            }
            MessageList(
                emails = emails,
                selected = selected,
                loading = loading,
                title = viewingTag?.let { tagsOf(setOf(it)).firstOrNull()?.label ?: it }
                    ?: here?.second?.name.orEmpty(),
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
                accountLabels = if (unified()) {
                    sessions.associate { it.key to shortAccountName(it.account.name, it.account.email) }
                } else {
                    emptyMap()
                },
                picked = picked,
                onRefresh = { scope.launch { refreshNow() } },
                order = order,
                onOrder = { order = it; Settings.setOrder(it) },
                rowActions = rowActions,
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
                filterNote = filterNote,
                loadingMore = loadingMore,
                onNeedMore = ::loadMore,
                onSelect = { message, ctrl, shift ->
                    picked = pickedAfter(emails.map { it.id }, picked, anchor, message.id, ctrl, shift)
                    if (!shift) anchor = message.id
                    if (!ctrl && !shift) selected = message
                },
            )
            VerticalDivider()
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
                    onFile = { role, what ->
                        // Grouped by account, because in the merged inbox the picked
                        // messages can come from several, and each has its own Archive.
                        val byAccount = emails.filter { it.id in picked }
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
                                byAccount.forEach { (key, move, target) ->
                                    sayJunk(key, move.ids, move.fromMailboxId, target)
                                }
                                val done = withContext(Dispatchers.IO) {
                                    byAccount.filter { (key, move, target) ->
                                        runCatching { session(key).jmap.move(move.ids, target) }.isSuccess
                                    }
                                }
                                if (done.isNotEmpty()) {
                                    val moved = done.flatMap { it.second.ids }.toSet()
                                    done.forEach { (key, move, _) ->
                                        runCatching { session(key).store?.forget(move.ids) }
                                    }
                                    emails = emails.filterNot { it.id in moved }
                                    picked = emptySet()
                                    selected = null
                                    undo = Undoable(done.map { it.second }, what)
                                }
                            }
                        }
                    },
                    onRead = {
                        val byAccount = emails.filter { it.id in picked && !it.seen }
                            .groupBy { accountOf(it) }
                        if (byAccount.isNotEmpty()) {
                            scope.launch {
                                val read = withContext(Dispatchers.IO) {
                                    byAccount.mapNotNull { (key, group) ->
                                        key ?: return@mapNotNull null
                                        val ids = group.map { it.id }
                                        runCatching { session(key).jmap.setKeyword(ids, "\$seen", true) }
                                            .map { ids }.getOrNull()
                                    }.flatten().toSet()
                                }
                                emails = emails.map { if (it.id in read) it.copy(seen = true) else it }
                            }
                        }
                    },
                )
                return@Row
            }
            val message = selected
            when {
                message == null -> Message(summary = null, body = null, onLink = { confirm = it })
                // Rule 9: one message must look like a message, not a stack of one. No
                // count line, no collapsed rows, no header of its own above it.
                thread.size <= 1 -> renderCard(
                    message,
                    showSubject = true,
                    onHeaderClick = null,
                    externalScroll = null,
                )
                else -> {
                    val stackScroll = rememberScrollState()
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        ThemeArt(Modifier.align(Alignment.BottomEnd))
                        Column(
                            Modifier.fillMaxSize().verticalScroll(stackScroll)
                                .padding(horizontal = 20.dp, vertical = 20.dp),
                        ) {
                            // The plain header: the subject once, the count, and the muted
                            // marker, none of which belong to any one card below it.
                            Text(message.subject, style = MaterialTheme.typography.titleLarge)
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
                            Spacer(Modifier.height(14.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(6.dp))

                            thread.forEachIndexed { index, m ->
                                if (m.id in expanded) {
                                    // Brought into view once, the moment this is the card
                                    // the conversation was opened on. Every later expand or
                                    // collapse leaves the scroll exactly where it was: see
                                    // rule 1, this is never a swap.
                                    val requester = remember(m.id) { BringIntoViewRequester() }
                                    LaunchedEffect(pendingScrollTo) {
                                        if (pendingScrollTo == m.id) {
                                            requester.bringIntoView()
                                            pendingScrollTo = null
                                        }
                                    }
                                    Box(Modifier.bringIntoViewRequester(requester)) {
                                        renderCard(
                                            m,
                                            showSubject = false,
                                            onHeaderClick = { toggleExpand(m.id) },
                                            externalScroll = stackScroll,
                                        )
                                    }
                                } else {
                                    ThreadRow(m) { toggleExpand(m.id) }
                                }
                                if (index < thread.lastIndex) {
                                    Spacer(Modifier.height(6.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                }
            }
            /*
             * Beside the mail rather than instead of it.
             *
             * Every question worth asking it is about something on screen, so a panel that
             * replaced the screen would mean leaving the thing being asked about.
             */
            if (chatOpen) {
                VerticalDivider()
                ChatPane(
                    said = said,
                    thinking = chatThinking,
                    unavailable = Assistant.whyNot(Assistant.CHAT),
                    onSend = { ask(it) },
                    onClear = {
                        said = emptyList()
                        // A new conversation has been shown nothing, so it may act on
                        // nothing until it looks something up again.
                        chatShown.clear()
                    },
                    onClose = { chatOpen = false },
                    onSettings = { settingsOpen = true },
                    model = Assistant.config().model,
                    agreed = chatAgreed,
                    onAgree = { Assistant.agree(Assistant.CHAT); chatAgreed = true },
                    onTyping = { typing = it },
                )
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
        composing?.let { draft ->
            // Clamped against the live window on every draw, not only while a drag is in
            // progress: a window shrunk since the size was chosen (or since a previous run)
            // should not reopen a panel that no longer fits, without needing the drag itself
            // to have run first.
            val panelSize = clampComposeSize(DpSize(composeWidth, composeHeight), windowSize())
            Box(
                Modifier
                    .align(if (composeFull) Alignment.Center else Alignment.BottomEnd)
                    .then(
                        if (composeFull) Modifier.fillMaxSize()
                        else Modifier.padding(16.dp).width(panelSize.width).height(panelSize.height),
                    ),
            ) {
                ComposerFrame(full = composeFull) {
                    ComposerPanel(draft)
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
}

/**
 * A folder job waiting on an answer.
 *
 * [mailbox] is null only for a new top level folder, which is the one job with nothing to
 * act on. Everything else names the folder it was invoked from.
 */
internal data class FolderAsk(val account: String, val mailbox: Mailbox?, val job: FolderJob)

/** What a right-click on a folder asked for. Answered by whoever owns the sidebar. */
internal enum class FolderJob { CreateInside, Rename, ToTop, Delete }

@Composable
internal fun Sidebar(
    accounts: List<AccountMailboxes>,
    here: Pair<String, Mailbox>?,
    collapsed: Boolean = false,
    inSettings: Boolean = false,
    inContacts: Boolean = false,
    inDashboard: Boolean = false,
    /** Whether the assistant panel is showing, so its button says so. */
    asking: Boolean = false,
    onAsk: () -> Unit = {},
    onToggleCollapsed: () -> Unit = {},
    onSettings: () -> Unit,
    onContacts: () -> Unit = {},
    onDashboard: () -> Unit = {},
    onAddAccount: () -> Unit,
    onWrite: () -> Unit,
    /** The version line at the bottom is clicked to see what changed. A no-op default so
     *  the screenshot tests, which have no changelog to open, need not pass one. */
    onViewChangelog: () -> Unit = {},
    /** What a right-click on a folder can ask for. Null hides the menu entirely. */
    folderMenu: ((String, Mailbox, FolderJob) -> Unit)? = null,
    onSelect: (String, Mailbox) -> Unit,
    /** Every tag on every account, merged into one list. */
    tags: List<TagRow> = emptyList(),
    /** The tag being looked at, across every account that has it. */
    hereTag: String? = null,
    onSelectTag: (String) -> Unit = {},
    /** A colour chosen for a tag, or null to put it back to the one from its name. */
    onTagColour: (String, Long?) -> Unit = { _, _ -> },
    /** The sections folded away, by the ids [foldFolders], [foldTags] and [foldTag] make. */
    folded: Set<String> = emptySet(),
    /** Folds a section away, or opens it again. */
    onFold: (String) -> Unit = {},
    /** Where the pointer is while a message is being dragged, in window coordinates. */
    dragAt: Offset? = null,
    /** Where each tag row ended up, so a drag that ends over one can find it. */
    onTagBounds: (String, Rect) -> Unit = { _, _ -> },
    /** The search field, drawn at the top. Absent while the sidebar is narrowed. */
    search: @Composable () -> Unit = {},
) {
    Column(
        Modifier.width(if (collapsed) 60.dp else 232.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = if (collapsed) 8.dp else 12.dp, vertical = 14.dp),
        horizontalAlignment = if (collapsed) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        // Nothing at all when narrowed: a field 44dp wide is not a field, and the sidebar
        // is narrowed precisely to get the width back. Ctrl+K still reaches search.
        if (!collapsed) {
            search()
            Spacer(Modifier.height(10.dp))
        }
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
            // One account has nothing to merge, so the row would be a second name for Inbox.
            if (accounts.size > 1) {
                item(key = "all-inboxes") {
                    val unread = accounts.sumOf { a ->
                        folderFor("inbox", a.mailboxes)?.unread ?: 0
                    }
                    FolderRow(
                        mailbox = allInboxes(unread),
                        collapsed = collapsed,
                        selected = here?.first == ALL_ACCOUNTS,
                        onClick = { onSelect(ALL_ACCOUNTS, allInboxes(unread)) },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
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
                            GroupHeading(
                                text = shortAccountName(account.name, account.email).uppercase(),
                                open = foldFolders(account.key) !in folded,
                                onClick = { onFold(foldFolders(account.key)) },
                                top = if (index == 0) 4.dp else 16.dp,
                            )
                        }
                    }
                }
                // One account has no heading to fold, so its folders are always shown.
                val folders = if (accounts.size > 1 && foldFolders(account.key) in folded) emptyList()
                else nested(account.mailboxes)
                items(folders, key = { "${account.key}/${it.mailbox.id}" }) { row ->
                    FolderRow(
                        mailbox = row.mailbox,
                        depth = row.depth,
                        collapsed = collapsed,
                        // A tag view is not in any folder, so nothing in the folder list is
                        // the thing being looked at while one is open.
                        selected = hereTag == null && here?.first == account.key &&
                            here.second.id == row.mailbox.id,
                        onClick = { onSelect(account.key, row.mailbox) },
                        onManage = folderMenu?.let { manage -> { what -> manage(account.key, row.mailbox, what) } },
                    )
                }
            }
            /*
             * One TAGS section, under every account rather than inside each of them.
             *
             * A keyword lives in one mailbox and cannot be read from another, so per account
             * is the truthful model underneath and it is kept. It is the wrong thing to draw:
             * nobody has two Billing tags because they have two mailboxes, and a heading
             * under every account with nothing under most of them says the opposite of what
             * is true. Opening one asks every account that has it.
             *
             * Narrowed there is no room for a word, and a column of coloured dots says
             * nothing, so the tags are simply not there until the sidebar is open.
             */
            val rows = if (collapsed) emptyList() else tags
            if (rows.isNotEmpty()) {
                item(key = "tags-heading") {
                    GroupHeading(
                        text = "TAGS",
                        open = foldTags() !in folded,
                        onClick = { onFold(foldTags()) },
                        top = 16.dp,
                    )
                }
                val shown = if (foldTags() in folded) emptyList() else visibleTags(rows, "", folded)
                items(shown, key = { "tag/${it.keyword}" }) { row ->
                    TagLine(
                        row = row,
                        selected = hereTag.equals(row.keyword, ignoreCase = true),
                        // A branch with something under it folds when its own chevron is
                        // clicked, and opens the list when its name is.
                        open = if (rows.any { it.keyword.startsWith(row.keyword + NEST) }) {
                            foldTag("", row.keyword) !in folded
                        } else {
                            null
                        },
                        onFold = { onFold(foldTag("", row.keyword)) },
                        onClick = { if (row.real) onSelectTag(row.keyword) },
                        onColour = { onTagColour(row.keyword, it) },
                        dragAt = dragAt,
                        onMeasured = { onTagBounds(row.keyword, it) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))

        /*
         * One compact control instead of a full width row per account: which addresses
         * this is signed into is not news anybody reads on every glance down the sidebar.
         * It folds into a stack of faces that reads at a glance as "there is more than
         * one" and opens straight onto Settings, already on Accounts, on a single click.
         * See [AccountStack] for the cap and what the last slot does past it.
         */
        if (collapsed) {
            // Overlapping is a horizontal idea: stacked vertically in a 44dp rail it would
            // just be circles touching edge to edge, so collapsed keeps the plain column
            // of bare faces it has always had, which is already as compact as this gets.
            accounts.forEach { account ->
                Box(Modifier.padding(vertical = 4.dp)) { Avatar(account.name, account.email, 26.dp) }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountStack(accounts, onClick = onSettings)
                Spacer(Modifier.weight(1f))
                AddAccountFace(onClick = onAddAccount)
            }
        }

        if (collapsed) {
            IconButton(onClick = onDashboard, modifier = Modifier.size(32.dp)) {
                Icon(
                    RampartIcons.Dashboard,
                    contentDescription = "How your mail is going",
                    tint = if (inDashboard) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = onContacts, modifier = Modifier.size(32.dp)) {
                Icon(
                    RampartIcons.Contacts,
                    contentDescription = "Contacts",
                    tint = if (inContacts) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = onAsk, modifier = Modifier.size(32.dp)) {
                Icon(
                    RampartIcons.Ask,
                    contentDescription = "The assistant",
                    tint = if (asking) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
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
            // "Add account" used to live here as its own text row; that job now belongs to
            // the "+" beside the stack of faces above, so this row is icons only.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDashboard, modifier = Modifier.size(28.dp)) {
                    Icon(
                        RampartIcons.Dashboard,
                        contentDescription = "How your mail is going",
                        tint = if (inDashboard) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onContacts, modifier = Modifier.size(28.dp)) {
                    Icon(
                        RampartIcons.Contacts,
                        contentDescription = "Contacts",
                        tint = if (inContacts) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onAsk, modifier = Modifier.size(28.dp)) {
                    Icon(
                        RampartIcons.Ask,
                        contentDescription = "The assistant",
                        tint = if (asking) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
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
            /*
             * The version line, quiet and always in the same place at the bottom of the
             * sidebar. What to do about a newer one is still entirely the bottom bar's
             * job, and this still says nothing about one. It is clickable now for a
             * second, unrelated reason: it is the one place in the window that always
             * names the version running right now, which makes it the natural way back
             * to the changelog once the dialog that shows automatically has been
             * dismissed, or switched off entirely with its own checkbox.
             */
            Updates.current?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                        .clickable(onClickLabel = "What changed in Rampart", role = Role.Button, onClick = onViewChangelog),
                )
            }
        }
    }
}

/** The face size the account stack and its "+" use, matching the old per-row avatar. */
private val ACCOUNT_FACE_SIZE = 26.dp

/** How much of one face the next one covers. */
private val ACCOUNT_FACE_OVERLAP = 12.dp

/** The ring drawn around each face, in the sidebar's own background, so an overlap reads
 *  as one face sitting in front of another rather than two circles merging into one. */
private val ACCOUNT_FACE_RING = 2.dp

/** How many real faces the stack shows before the rest are folded into a "+N" badge. */
private const val MAX_ACCOUNT_FACES = 3

/**
 * The overlapped stack of avatars that replaced a full width row per account.
 *
 * Justin's own description of what he wanted: "show both icons overlapped showing its
 * logged into two or more emails, when you click on it it takes you the email settings
 * page." One account is not a stack: its avatar is shown alone, still clickable, because
 * reaching account settings from your own picture is a pattern nobody has to be taught
 * (macOS, Slack and Google all do exactly this even when there is only one account signed
 * in), and swapping to a different control the moment a second account arrives would make
 * this change shape under somebody's finger rather than just grow.
 *
 * Capped at [MAX_ACCOUNT_FACES] real faces. A stack that keeps widening for every account
 * stops reading as "there is more than one" and starts reading as the list this replaced;
 * past the cap the last slot becomes a "+N" badge instead, the same size as a face, so the
 * stack does not change width again as further accounts are added.
 */
@Composable
private fun AccountStack(accounts: List<AccountMailboxes>, onClick: () -> Unit) {
    val faceBox = ACCOUNT_FACE_SIZE + ACCOUNT_FACE_RING * 2
    val shown = accounts.take(MAX_ACCOUNT_FACES)
    // A plain Row would still measure each face at its full width even though later ones
    // are drawn shifted over the one before, leaving a gap of dead space between the last
    // visible face and whatever sits next to the stack. Sized explicitly instead, to the
    // width the faces actually cover once overlapped, so the "+" beside it sits where it
    // looks like it should rather than where an unshifted row would have put it.
    val width = faceBox + (faceBox - ACCOUNT_FACE_OVERLAP) * maxOf(0, shown.size - 1)
    Box(
        Modifier.size(width = width, height = faceBox)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Account settings", role = Role.Button, onClick = onClick),
    ) {
        if (accounts.size <= 1) {
            accounts.firstOrNull()?.let { account ->
                Face { Avatar(account.name, account.email, ACCOUNT_FACE_SIZE) }
            }
        } else {
            val overflow = accounts.size - shown.size
            shown.forEachIndexed { index, account ->
                Box(Modifier.offset(x = (faceBox - ACCOUNT_FACE_OVERLAP) * index)) {
                    // The badge takes the last shown slot rather than sitting beside it, so
                    // that account's own avatar is hidden too: the count it shows has to be
                    // one more than the accounts past the cap, not just the accounts past it.
                    if (overflow > 0 && index == shown.lastIndex) {
                        Face { OverflowBadge(overflow + 1) }
                    } else {
                        Face { Avatar(account.name, account.email, ACCOUNT_FACE_SIZE) }
                    }
                }
            }
        }
    }
}

/**
 * One face in the stack, ringed in the sidebar's own background so it reads as sitting in
 * front of whatever it overlaps rather than merging into it.
 */
@Composable
private fun Face(content: @Composable () -> Unit) {
    Box(
        Modifier.size(ACCOUNT_FACE_SIZE + ACCOUNT_FACE_RING * 2)
            .background(MaterialTheme.colorScheme.background, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * What the stack's last slot shows once there are more accounts than [MAX_ACCOUNT_FACES].
 * Sized and coloured like a face rather than drawn as one, so it reads as "more" rather
 * than as somebody's unlabelled initials.
 */
@Composable
private fun OverflowBadge(count: Int) {
    Box(
        Modifier.size(ACCOUNT_FACE_SIZE).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The "add another account" affordance beside the stack. Justin asked for "a plus button
 * to login to another one or something", so a plain "+" rather than a new glyph added to
 * the icon pack: [IconPack] is a large surface shared by every theme, and a button used in
 * exactly one place does not earn a new member on it. Its hit target is its own, separate
 * from [AccountStack]'s, so clicking it calls [onClick] (the existing onAddAccount)
 * directly rather than opening Settings first.
 */
@Composable
private fun AddAccountFace(onClick: () -> Unit) {
    Box(
        Modifier.size(ACCOUNT_FACE_SIZE)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClickLabel = "Add another account", role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
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

/**
 * Asks for whatever a folder job needs before it runs.
 *
 * Two shapes rather than two dialogs: the naming jobs want a name, and the rest want a yes.
 * A delete says what is about to go rather than asking "are you sure", which is a question
 * nobody reads.
 */
@Composable
private fun FolderDialog(
    ask: FolderAsk,
    error: String?,
    onClose: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val needsName = ask.job == FolderJob.CreateInside || ask.job == FolderJob.Rename
    var name by remember(ask) { mutableStateOf(if (ask.job == FolderJob.Rename) ask.mailbox?.name.orEmpty() else "") }
    var busy by remember(ask) { mutableStateOf(false) }
    val title = when (ask.job) {
        FolderJob.CreateInside -> ask.mailbox?.let { "New folder inside ${it.name}" } ?: "New folder"
        FolderJob.Rename -> "Rename ${ask.mailbox?.name.orEmpty()}"
        FolderJob.ToTop -> "Move ${ask.mailbox?.name.orEmpty()} to the top level"
        FolderJob.Delete -> "Delete ${ask.mailbox?.name.orEmpty()}"
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(title) },
        text = {
            Column {
                when {
                    needsName -> OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        enabled = !busy,
                        singleLine = true,
                        label = { Text("Name") },
                    )
                    ask.job == FolderJob.Delete -> Text(
                        // The count is what makes this a decision rather than a reflex.
                        if ((ask.mailbox?.unread ?: 0) > 0) {
                            "It has ${ask.mailbox?.unread} unread. The server will refuse if " +
                                "there is anything in it, and nothing is deleted if it does."
                        } else {
                            "The server will refuse if there is anything in it, and nothing " +
                                "is deleted if it does."
                        },
                    )
                    else -> Text("It will sit alongside your other top level folders.")
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { busy = true; onConfirm(name.trim()) },
                enabled = !busy && (!needsName || name.isNotBlank()),
            ) { Text(if (ask.job == FolderJob.Delete) "Delete" else "Save") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") } },
    )
}

/** A section name in the sidebar, with the chevron that folds what is under it. */
@Composable
private fun GroupHeading(text: String, open: Boolean, onClick: () -> Unit, top: Dp) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick)
            .padding(start = 10.dp, end = 10.dp, top = top, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Chevron(open)
    }
}

/**
 * Points down when the section is open and right when it is folded.
 *
 * One icon turned rather than two drawn. The pack has a chevron and a quarter turn is what
 * separates the two states anyway, so a second icon would be the same path with the numbers
 * swapped, in three packs.
 */
@Composable
private fun Chevron(open: Boolean) {
    Icon(
        RampartIcons.Expand,
        contentDescription = if (open) "Fold away" else "Open",
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(13.dp).rotate(if (open) 90f else 0f),
    )
}

/**
 * One tag in the sidebar.
 *
 * A dot in the tag's colour and its own level of the name, indented by how deep it is.
 * Only the last level is written: "Clients / Acme / Renewals" spelled out in full on every
 * line is three quarters repetition in a 232dp column, and the indent already says whose
 * it is.
 *
 * A level nobody has tagged anything with is a heading rather than a row: it can be dropped
 * onto, because that is a reasonable thing to mean, but clicking it would open a list that
 * is empty by definition.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun TagLine(
    row: TagRow,
    selected: Boolean,
    onClick: () -> Unit,
    onColour: (Long?) -> Unit,
    /** Whether this branch is open, or null on a tag with nothing under it. */
    open: Boolean? = null,
    onFold: () -> Unit = {},
    /**
     * Where the pointer is while a message is being dragged, in window coordinates, or
     * null when nothing is being dragged.
     *
     * A position rather than the usual enter and leave events, because the row being
     * dragged consumes pointer movement for the length of the drag and nothing underneath
     * hears about it. [onMeasured] hands this row's rectangle up so the drop can be worked
     * out where the drag actually ends.
     */
    dragAt: Offset? = null,
    onMeasured: (Rect) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val over = dragAt != null && bounds.contains(dragAt)
    val background = when {
        over -> MaterialTheme.colorScheme.secondaryContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp)
            .onGloballyPositioned { bounds = it.boundsInWindow(); onMeasured(bounds) }
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.button == PointerButton.Secondary) menu = true
            }
            .padding(start = 10.dp + (row.depth * 12).dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                "Colour",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp),
            )
            // Six to a row, so twelve colours are two rows rather than a menu twelve items
            // long that runs off the bottom of a short window.
            TAG_COLOURS.chunked(6).forEach { chunk ->
                Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
                    chunk.forEach { colour ->
                        Box(
                            Modifier.padding(3.dp).size(20.dp)
                                .background(Color(colour), CircleShape)
                                .clickable { menu = false; onColour(colour) },
                        )
                    }
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Back to the colour from its name") },
                onClick = { menu = false; onColour(null) },
            )
        }
        if (open == null) {
            Box(Modifier.size(10.dp).background(Color(row.color), CircleShape))
        } else {
            // The chevron takes the dot's place rather than sitting beside it: a branch is
            // a heading, and two markers on one row is one more than the row can carry.
            Box(
                Modifier.size(15.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onFold),
                contentAlignment = Alignment.Center,
            ) { Chevron(open) }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            row.label.substringAfterLast('/'),
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.real) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Fills, so the count is pinned to the right edge in the same column the folder
            // counts are in rather than trailing the word at whatever width it happens to be.
            modifier = Modifier.weight(1f),
        )
        // How much is behind the tag, which is what makes it worth clicking. Nothing at all
        // when it is empty, rather than a zero: a row of noughts is a list of words again.
        if (row.count > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                row.count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun FolderRow(
    mailbox: Mailbox,
    collapsed: Boolean,
    selected: Boolean,
    depth: Int = 0,
    /** What the right-click menu can do, or null where folders cannot be managed. */
    onManage: ((FolderJob) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.button == PointerButton.Secondary && onManage != null) menu = true
            }
            // Collapsed the sidebar is icons only, so there is no room to show depth and
            // indenting would push them off their own column.
            .padding(start = if (collapsed) 0.dp else 10.dp + (depth * 12).dp)
            .padding(end = if (collapsed) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
    ) {
        onManage?.let { manage ->
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("New folder inside") },
                    onClick = { menu = false; manage(FolderJob.CreateInside) },
                )
                // A folder the server gave a role to is one the client should not offer to
                // rename or delete: the role is what Archive and Trash are found by, and a
                // renamed one still has it while looking like somebody's own folder.
                if (!isProtected(mailbox)) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menu = false; manage(FolderJob.Rename) },
                    )
                    if (mailbox.parentId != null) {
                        DropdownMenuItem(
                            text = { Text("Move to the top level") },
                            onClick = { menu = false; manage(FolderJob.ToTop) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menu = false; manage(FolderJob.Delete) },
                    )
                }
            }
        }
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
    /*
     * In the sidebar, above Write, rather than on a bar of its own.
     *
     * It had the full width of the window and nothing beside it, which is a 52dp stripe of
     * nothing above everything else on screen. Here it costs no row that was not there.
     */
    Row(
        Modifier.fillMaxWidth().height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Built rather than borrowed: Material's text field has a minimum height of its
        // own and forcing it shorter clips the text inside it, which is what a 38dp
        // OutlinedTextField did here.
        Row(
            Modifier.fillMaxWidth().height(34.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(start = 9.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                RampartIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(7.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "Search mail",
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

/**
 * The toggles under the folder title.
 *
 * A row of its own, not squeezed into the title strip: five chips, a count and a
 * clear button do not fit beside the folder name. They wrap, because the list is
 * a narrow column and the labels are not optional.
 *
 * The count is how many rows have come back against how many the folder says it
 * holds. It is absent until a toggle is on, because an unfiltered folder already
 * has its name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickFilterRow(
    filters: QuickFilters,
    onFilters: (QuickFilters) -> Unit,
    onClear: () -> Unit,
    shown: Int,
    folderTotal: Int,
    attachmentReason: String?,
    filterNote: String?,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip("Unread", RampartIcons.Unread, filters.unread) {
                onFilters(filters.copy(unread = !filters.unread))
            }
            FilterChip("Starred", RampartIcons.Star, filters.starred) {
                onFilters(filters.copy(starred = !filters.starred))
            }
            FilterChip("Tagged", RampartIcons.Tag, filters.tagged) {
                onFilters(filters.copy(tagged = !filters.tagged))
            }
            FilterChip(
                "Attachment",
                RampartIcons.Attachment,
                on = filters.attachment && attachmentReason == null,
                enabled = attachmentReason == null,
            ) {
                onFilters(filters.copy(attachment = !filters.attachment))
            }
            FilterChip("Sender I Know", RampartIcons.Contacts, filters.knownSender) {
                onFilters(filters.copy(knownSender = !filters.knownSender))
            }
            if (filters.active) {
                Text(
                    if (folderTotal > 0) "$shown of $folderTotal" else "$shown matched",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                )
                Text(
                    "Clear",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onClear)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        val note = filterNote ?: attachmentReason
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    icon: ImageVector,
    on: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        on -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (on && enabled) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (on && enabled) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
    }
}

@Composable
internal fun MessageList(
    emails: List<Summary>,
    selected: Summary?,
    loading: Boolean,
    title: String = "",
    /** Account key to a short name, when a row has to say which mailbox it arrived in. */
    accountLabels: Map<String, String> = emptyMap(),
    /** Everything picked out, which is [selected] alone until somebody holds a key down. */
    picked: Set<String> = emptySet(),
    onRefresh: () -> Unit = {},
    /** How the rows are ordered, and how to change it. */
    order: Order = Order.NEWEST,
    onOrder: (Order) -> Unit = {},
    /** What a right-click or a hover button on a row can do. */
    rowActions: RowActions = RowActions(),
    /** Dragging a row onto a tag. See [MessageRow]. */
    onDrag: ((Summary, Offset?) -> Unit)? = null,
    /**
     * The old unread switch, kept so a caller that still passes it gets the unread
     * toggle. The row below is the control. This is only the default for [filters].
     */
    unreadOnly: Boolean = false,
    /** Which toggles are on. Asked of the server, or of the saved copy, never of this list. */
    filters: QuickFilters = QuickFilters(unread = unreadOnly),
    onFilters: (QuickFilters) -> Unit = {},
    /** Puts every toggle back. Shown only while [filters] is doing something. */
    onClearFilters: () -> Unit = {},
    /** How many messages the folder says it holds. 0 when it did not say. */
    folderTotal: Int = 0,
    /**
     * Why the attachment toggle cannot be pressed, or null when it can. The chip stays
     * on screen either way: a missing chip looks like a feature that was never there.
     */
    attachmentReason: String? = null,
    /** Set when a toggle had to be answered from a copy that is not there. */
    filterNote: String? = null,
    /** Whether the next page is already on its way, so the foot says so. */
    loadingMore: Boolean = false,
    /** Called when the list gets near its own bottom and wants the next page. */
    onNeedMore: () -> Unit = {},
    /**
     * Draws every row as though the pointer were over it.
     *
     * Only ever passed by the screenshot tests. A headless scene has no pointer, and the
     * hover state is where a row's layout is most likely to go wrong, so it has to be
     * photographable.
     */
    showHover: Boolean = false,
    onSelect: (Summary, ctrl: Boolean, shift: Boolean) -> Unit,
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
                var sorting by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { sorting = true }, modifier = Modifier.size(26.dp)) {
                        Icon(
                            RampartIcons.Sort,
                            contentDescription = "Sort order, currently ${order.label.lowercase()}",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                        Order.entries.forEach { option ->
                            val chosen = option == order
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (chosen) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                leadingIcon = {
                                    // A tick on the one in use, and an empty slot of the
                                    // same width on the others so the labels line up. An
                                    // asterisk was standing in for this and read as a typo.
                                    Box(Modifier.size(14.dp)) {
                                        if (chosen) {
                                            Icon(
                                                RampartIcons.Tick,
                                                contentDescription = "In use",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                },
                                // Material's default row is built for a finger. This is a
                                // five item menu on a desktop, and at that height it reads
                                // as a list of pages rather than a set of choices.
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                onClick = {
                                    sorting = false
                                    onOrder(option)
                                },
                            )
                        }
                    }
                }
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
        QuickFilterRow(
            filters = filters,
            onFilters = onFilters,
            onClear = onClearFilters,
            shown = emails.size,
            folderTotal = folderTotal,
            attachmentReason = attachmentReason,
            filterNote = filterNote,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val scroll = rememberLazyListState()
        /*
         * A folder nobody has scrolled opens at the top, and goes on opening at the top.
         *
         * Rows are keyed by message id, so the list keeps whichever message was at the top
         * of the viewport pinned when the contents change. That is exactly right when
         * somebody scrolled to it and exactly wrong when they never left the top, and a
         * folder is filled twice: once from the copy on disk and once from the server. The
         * two do not always agree on order or on how many there are, so the message at the
         * top of the first list can be sixty rows down the second, and the list scrolls
         * sixty rows to keep it in view. The folder then opens in the middle of itself.
         *
         * Scrolling is what makes a position worth keeping, so that is what is watched. An
         * anchor correction is done during layout and is not a scroll, which is the whole
         * distinction this needs. Reset per folder, because arriving somewhere new is the
         * one time nobody has a position in it yet.
         */
        var moved by remember(title) { mutableStateOf(false) }
        LaunchedEffect(scroll, title) {
            snapshotFlow { scroll.isScrollInProgress }.collect { if (it) moved = true }
        }
        LaunchedEffect(emails, title) { if (!moved) scroll.scrollToItem(0) }
        /*
         * Ask for the next page a screenful early, so the rows are already there by the
         * time somebody scrolls onto them. Derived rather than read every frame: without
         * that this recomposes the whole list on every pixel of scrolling.
         */
        val wantsMore by remember(emails.size) {
            derivedStateOf {
                val last = scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                emails.isNotEmpty() && last >= emails.size - 10
            }
        }
        LaunchedEffect(wantsMore) { if (wantsMore) onNeedMore() }
        Box(Modifier.fillMaxSize()) {
            when {
                loading -> Spinner(Modifier.align(Alignment.Center))
                emails.isEmpty() -> Text(
                    filterNote ?: when {
                        filters.unread && !filters.starred && !filters.tagged &&
                            !filters.attachment && !filters.knownSender -> "Nothing unread here."
                        filters.active -> "Nothing matches."
                        else -> "Nothing here."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize(), state = scroll) {
                    // LazyColumn only builds the rows on screen, so a folder with thirty
                    // thousand messages in it costs the same as one with twenty. What that
                    // folder still needs is the next page, which is what `onNeedMore` is.
                    items(sorted(emails, order), key = { it.id }) { message ->
                        MessageRow(
                            message = message,
                            selected = message.id == selected?.id || message.id in picked,
                            accountLabel = accountLabels[message.account],
                            actions = rowActions,
                            showHover = showHover,
                            onDrag = onDrag,
                            onSelect = onSelect,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (loadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                Spinner(
                                    Modifier.align(Alignment.Center),
                                    size = 18.dp,
                                    thickness = 2.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MessageRow(
    message: Summary,
    selected: Boolean,
    accountLabel: String? = null,
    actions: RowActions = RowActions(),
    showHover: Boolean = false,
    /**
     * Dragging the row onto a tag. Called with where the pointer is, in window
     * coordinates, and with null when the drag ends.
     *
     * Null means the list does not support dragging, which is every list but the mail one.
     */
    onDrag: ((Summary, Offset?) -> Unit)? = null,
    onSelect: (Summary, ctrl: Boolean, shift: Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var pointerOver by remember { mutableStateOf(false) }
    val hovered = pointerOver || showHover
    // Where this row sits, so the pointer offsets a drag reports (which are relative to
    // the row) can be turned into a position in the window.
    var origin by remember { mutableStateOf(Offset.Zero) }

    /*
     * Unread rows carry a tint as well as the dot and the weight.
     *
     * Three signals rather than one because the dot is 7px and the weight difference is a
     * font grade: on a full inbox, at a glance, neither of them separates what has been
     * read from what has not. The tint is deliberately faint, taken from the theme's own
     * surface rather than a colour of its own, so a mostly unread inbox does not turn into
     * a wall of highlight.
     *
     * Selection still wins, because that is the row being acted on.
     */
    /*
     * A tag's colour on the row itself, when that is switched on.
     *
     * Bulwark's `tintListRowsByTag`, which is the last thing its tag settings do that this
     * did not. Very faint on purpose: at any strength that reads as a colour it fights the
     * unread tint above it and an inbox where everything is tagged becomes unreadable,
     * which is the reason it is a setting rather than the default.
     *
     * The first tag wins where a message has several. Blending them makes a brown nobody
     * chose, and the chips beside the subject already say what the others are.
     */
    val tint = if (!LocalTintRowsByTag.current) null else {
        tagsOf(message.keywords, LocalTagColours.current).firstOrNull()?.let { Color(it.color) }
    }
    val background = when {
        selected -> MaterialTheme.colorScheme.surfaceVariant
        tint != null && !message.seen -> tint.copy(alpha = 0.22f)
        tint != null -> tint.copy(alpha = 0.11f)
        !message.seen -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth()
            .background(background)
            .onGloballyPositioned { origin = it.boundsInWindow().topLeft }
            .then(
                if (onDrag == null) Modifier
                else Modifier.pointerInput(message.id) {
                    detectDragGestures(
                        onDragStart = { at -> onDrag(message, origin + at) },
                        onDragEnd = { onDrag(message, null) },
                        onDragCancel = { onDrag(message, null) },
                    ) { change, _ -> onDrag(message, origin + change.position) }
                },
            )
            // The modifier keys have to be read from the press itself. clickable() does not
            // carry them, and holding control to add a second message to a selection is how
            // every desktop list has worked for thirty years.
            .onPointerEvent(PointerEventType.Press) { event ->
                when (event.button) {
                    PointerButton.Primary -> {
                        val keys = event.keyboardModifiers
                        onSelect(message, keys.isCtrlPressed || keys.isMetaPressed, keys.isShiftPressed)
                    }
                    // Right-click selects as well as opening the menu. A menu acting on a
                    // message other than the one under the pointer is how the wrong thing
                    // gets deleted.
                    PointerButton.Secondary -> {
                        if (!selected) onSelect(message, false, false)
                        menu = true
                    }
                    else -> Unit
                }
            }
            .onPointerEvent(PointerEventType.Enter) { pointerOver = true }
            .onPointerEvent(PointerEventType.Exit) { pointerOver = false }
            .height(IntrinsicSize.Min),
    ) {
        RowMenu(message, actions, menu) { menu = false }
        // A 2px edge rather than a fully tinted row: it marks the selection without
        // competing with the unread dot for the same piece of attention.
        Box(
            Modifier.width(2.dp).fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        // Their mark, beside the row rather than above it. Top aligned rather than centred:
        // a row is three lines tall and a circle floating in the middle of it reads as
        // belonging to the preview rather than to the sender.
        Box(Modifier.padding(start = 10.dp, top = 12.dp)) {
            Avatar(
                label = message.from,
                seed = message.fromEmail,
                size = 30.dp,
                photo = photoFor(message.fromEmail),
            )
        }
        Column(Modifier.padding(start = 10.dp, end = 14.dp, top = 11.dp, bottom = 12.dp)) {
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
                    fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                accountLabel?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                /*
                 * The buttons take the date's place, in a slot of a fixed size.
                 *
                 * Both parts are needed. Swapping one for the other is what every list with
                 * hover actions does, because buttons appearing beside the date would push
                 * it sideways under the pointer. The fixed size is what stops the swap
                 * itself moving anything: three icons are not as wide as "16 Sep 09:12" and
                 * are taller than it, so without a reserved slot the row jumps both ways as
                 * the pointer crosses it.
                 */
                Box(
                    Modifier.width(HOVER_SLOT).height(18.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (hovered) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            actions.markRead?.let { mark ->
                                RowButton(
                                    if (message.seen) RampartIcons.Unread else RampartIcons.Read,
                                    if (message.seen) "Mark unread" else "Mark read",
                                ) { mark(message, !message.seen) }
                            }
                            actions.archive?.let { archive ->
                                RowButton(RampartIcons.Archive, "Archive") { archive(message) }
                            }
                            actions.trash?.let { trash ->
                                RowButton(RampartIcons.Trash, "Delete") { trash(message) }
                            }
                        }
                    } else {
                        Text(
                            message.receivedAt.asLocalTime(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(Modifier.padding(start = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
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
                // Dots rather than chips: a label is worth seeing at a glance, and four of
                // them spelt out would push the subject off the row it belongs to.
                tagsOf(message.keywords, LocalTagColours.current).take(4).forEach { tag ->
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(tag.color)))
                }
            }
            /*
             * The sign-in code, on the row, so the message never has to be opened.
             *
             * This is the whole point of the feature rather than a flourish on it: a code
             * is wanted for about forty seconds and the message is never read. It is drawn
             * instead of the preview, because the preview of one of these messages is the
             * sentence the code was found in.
             */
            val code = remember(message.id) { oneTimeCode(message.subject, message.preview) }
            if (code != null) {
                val clipboard = LocalClipboardManager.current
                Spacer(Modifier.height(3.dp))
                Row(
                    Modifier.padding(start = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.clickable { clipboard.setText(AnnotatedString(code)) },
                    ) {
                        Text(
                            code,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "Copy",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else if (message.preview.isNotBlank()) {
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
/**
 * What the right-click menu and the hover buttons on a row can do.
 *
 * Separate from [MessageActions], which belongs to the message that is open and therefore
 * needs no argument. A row has to say which message it means, and there are eight of them
 * on screen at once.
 *
 * Every member is nullable for the same reason as [MessageActions]: an account with no Junk
 * folder does not get a Junk entry rather than getting one that fails.
 */
/**
 * Draws its contents on paper rather than in the theme, when asked.
 *
 * A themed reader is the right default: most mail is text, and text should be set in
 * whatever the person chose to look at all day. It is wrong for the rest. A newsletter or
 * an invoice was drawn against white by whoever sent it, and on a dark theme its own
 * colours land on a background nobody tested them against, which is how a logo turns into
 * a dark rectangle and a pale caption disappears.
 *
 * Only the message changes. The window, the list and the sidebar stay as they were, because
 * the point is to see one message as it was meant to look, not to change the app.
 */
@Composable
private fun Paper(on: Boolean, content: @Composable () -> Unit) {
    if (!on) {
        content()
        return
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = Color.White,
            onBackground = Color(0xFF17171B),
            surface = Color.White,
            onSurface = Color(0xFF17171B),
            surfaceVariant = Color(0xFFF4F4F6),
            onSurfaceVariant = Color(0xFF17171B),
            outline = Color(0xFF63636E),
            outlineVariant = Color(0xFFE6E6EB),
        ),
        typography = MaterialTheme.typography,
    ) {
        Surface(color = Color.White, shape = MaterialTheme.shapes.small) {
            Box(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) { content() }
        }
    }
}

/** One of the small buttons that appear on a row under the pointer. */
@Composable
private fun RowButton(icon: ImageVector, what: String, onClick: () -> Unit) {
    // 18dp rather than Material's default, so three of them fit the slot the date leaves
    // and the row is the same height whether the pointer is over it or not.
    IconButton(onClick = onClick, modifier = Modifier.size(18.dp)) {
        Icon(
            icon,
            contentDescription = what,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(13.dp),
        )
    }
}

/**
 * The width kept for the date, and for the buttons that replace it.
 *
 * Wide enough for the longest date this list shows and for three buttons, so neither state
 * is cramped and neither is what decides the width.
 */
private val HOVER_SLOT = 78.dp

/**
 * The right-click menu on a row.
 *
 * Every entry is left out rather than disabled when the account cannot do it, because a
 * greyed row invites a second click to find out why. The order is the one every mail client
 * uses, and the destructive pair is at the bottom behind a divider so a menu that opens
 * under the pointer cannot delete something on the way past.
 */
@Composable
private fun RowMenu(message: Summary, actions: RowActions, open: Boolean, onClose: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onClose) {
        // Closing before acting, so the menu is gone by the time the list under it changes.
        @Composable
        fun entry(label: String, does: () -> Unit) = DropdownMenuItem(
            text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            onClick = {
                onClose()
                does()
            },
        )
        actions.reply?.let {
            entry("Reply") { it(message, false) }
            entry("Reply all") { it(message, true) }
        }
        actions.forward?.let { entry("Forward") { it(message) } }
        actions.markRead?.let {
            entry(if (message.seen) "Mark unread" else "Mark read") { it(message, !message.seen) }
        }
        actions.star?.let { entry(if (message.flagged) "Remove star" else "Star") { it(message) } }
        actions.archive?.let { entry("Archive") { it(message) } }
        actions.snooze?.let { put ->
            // The four named times rather than a submenu with a calendar in it: the whole
            // value of a snooze is that it is one gesture.
            HorizontalDivider()
            SnoozeUntil.entries.forEach { until -> entry(until.label) { put(message, until) } }
        }
        if (actions.junk != null || actions.notJunk != null || actions.trash != null) {
            HorizontalDivider()
            if (actions.isJunk?.invoke(message) == true) {
                actions.notJunk?.let { entry("Not spam") { it(message) } }
            } else {
                actions.junk?.let { entry("Mark as spam") { it(message) } }
            }
            actions.trash?.let { entry("Delete") { it(message) } }
        }
    }
}

internal data class RowActions(
    val reply: ((Summary, all: Boolean) -> Unit)? = null,
    val forward: ((Summary) -> Unit)? = null,
    val archive: ((Summary) -> Unit)? = null,
    val junk: ((Summary) -> Unit)? = null,
    /** Out of Junk again, offered on a row that is in it. */
    val notJunk: ((Summary) -> Unit)? = null,
    /** Whether this row is in Junk, which decides which of the two above is shown. */
    val isJunk: ((Summary) -> Boolean)? = null,
    val trash: ((Summary) -> Unit)? = null,
    val star: ((Summary) -> Unit)? = null,
    val markRead: ((Summary, read: Boolean) -> Unit)? = null,
    /** Putting it away until later. Null on an account with nowhere to put it. */
    val snooze: ((Summary, SnoozeUntil) -> Unit)? = null,
)

/**
 * How deep a folder sits, for indenting it in a flat list.
 *
 * Counted by walking up the parents rather than stored, because the list Move is given is
 * already filtered and a stored depth would be the depth in the full tree.
 */
internal fun depthOf(folder: Mailbox, among: List<Mailbox>): Int {
    var depth = 0
    var parent = folder.parentId
    while (parent != null && depth < 6) {
        val next = among.firstOrNull { it.id == parent } ?: break
        depth++
        parent = next.parentId
    }
    return depth
}

/** Roles that already have a button on the message, so Move does not offer them again. */
internal val MOVE_COVERED = setOf("archive", "junk", "trash", "drafts", "sent")

internal data class MessageActions(
    val archive: (() -> Unit)? = null,
    val snooze: ((SnoozeUntil) -> Unit)? = null,
    val trash: (() -> Unit)? = null,
    val junk: (() -> Unit)? = null,
    /** Out of Junk again. Present exactly when [junk] is not, never both and never neither. */
    val notJunk: (() -> Unit)? = null,
    val star: (() -> Unit)? = null,
    /** Marking it unread again, which is how most people say "come back to this". */
    val markUnread: (() -> Unit)? = null,
    /**
     * Filing it anywhere, by mailbox id.
     *
     * Archive, Spam and Delete are the three folders worth their own button; everything
     * else a mailbox has is this. Null on an account with nowhere else to put it.
     */
    val moveInto: ((String) -> Unit)? = null,
    /** The folders that move can offer, already in the order the sidebar shows them. */
    val folders: List<Mailbox> = emptyList(),
    /**
     * The whole conversation, as one act.
     *
     * Separate from the buttons above, which are all about the one message on screen. A
     * thread is what people think in and the list already collapses one into a single row,
     * so a row standing for twelve messages that archives one of them is the row lying.
     */
    val conversation: ConversationActions? = null,
)

/** What can be done to a conversation. Null where the open message is not in one. */
internal data class ConversationActions(
    val count: Int,
    val unread: Int,
    val muted: Boolean,
    val onRead: (Boolean) -> Unit,
    val onArchive: () -> Unit,
    val onTrash: () -> Unit,
    val onMute: (Boolean) -> Unit,
)

/**
 * The Summarise button, and what has come of pressing it.
 *
 * Null hides the whole feature, which is what an account not yet known looks like: there
 * is nowhere yet to send anything, so there is nothing to offer a reason about.
 */
internal data class SummariseActions(
    /** Why the button cannot be pressed, in one sentence. Null when it can be. */
    val disabledBecause: String?,
    val running: Boolean,
    /** The paragraph from the last successful call, for this thread. Null before one. */
    val paragraph: String?,
    /** The sentence [LlmError] carried, from the last attempt that failed. */
    val error: String?,
    val onSummarise: () -> Unit,
    /** Looking at the exact packet without it gating the button. Null while disabled. */
    val onViewPacket: (() -> Unit)?,
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
    /** The same parts as they arrived, for the engine, which takes bytes rather than a bitmap. */
    imageBytes: Map<String, ByteArray> = emptyMap(),
    /** Pictures fetched from the web once the reader said to, by the address they came from. */
    /**
     * The domains this reader actually deals with, so a lookalike of one is caught as well
     * as a lookalike of a household name.
     */
    knownDomains: Set<String> = emptySet(),
    /** The meeting this message is about, drawn above the body when there is one. */
    invitation: Invitation? = null,
    /** The answer currently being sent, so the buttons say so and cannot be pressed twice. */
    answering: Rsvp? = null,
    onAnswer: (Rsvp) -> Unit = {},
    /**
     * The address this copy was addressed to, which is the one on the guest list.
     *
     * Passed in rather than worked out here: which of several identities a message reached
     * is the composer's question and is already answered there, and asking it twice is how
     * the card and the answer end up disagreeing about who is replying.
     */
    me: String = "",
    /** Whether the reader has agreed to let this message fetch its pictures. */
    showRemote: Boolean = false,
    onShowImages: (always: Boolean) -> Unit = {},
    /** What happened to an unsubscribe that was pressed, when one was. */
    unsubscribed: String? = null,
    onUnsubscribe: (Unsubscribe) -> Unit = {},
    /** Answer the sender's read-receipt request, as a draft for review. */
    onReceipt: (String) -> Unit = {},
    /** Put this sender in the server's address book. Null where there is none. */
    onAddContact: ((Summary) -> Unit)? = null,
    /** Opens the details panel on first draw. For the screenshot harness, which cannot click. */
    showDetails: Boolean = false,
    /** Whether that sender is already there, so the entry is absent rather than a duplicate. */
    inContacts: Boolean = false,
    /** The message as it arrived, while somebody is looking at it. */
    source: String? = null,
    onSource: () -> Unit = {},
    onSaveSource: () -> Unit = {},
    /** Drawing this message on paper rather than in the theme. */
    paper: Boolean = false,
    /** "dark", "light", or empty to follow the window. See [Settings.messageMode]. */
    messageMode: String = "",
    /** How large the message is drawn. See [Settings.messageScale]. */
    messageScale: Float = 1.0f,
    onPaper: (Boolean) -> Unit = {},
    onTag: (keyword: String, on: Boolean) -> Unit = { _, _ -> },
    /** True while a field in here has focus, so a bare letter is not read as a shortcut. */
    onTyping: (Boolean) -> Unit = {},
    onForward: () -> Unit = {},
    actions: MessageActions = MessageActions(),
    summarise: SummariseActions? = null,
    attachments: List<Attachment> = emptyList(),
    savedTo: String? = null,
    onDownload: (Attachment) -> Unit = {},
    /**
     * Drawn above the avatar row. Off inside a thread stack, where the subject already
     * heads the whole conversation once and would otherwise repeat on every card.
     */
    showSubject: Boolean = true,
    /**
     * Collapses this card, when it is one of several. Null for a message on its own, which
     * has nothing to collapse to: see rule 9, a lone message must not grow a click that does
     * nothing useful.
     */
    onHeaderClick: (() -> Unit)? = null,
    /**
     * The scroll this card shares with the rest of its stack, when it has one.
     *
     * Null draws this card on its own scroll, filling the pane, exactly as a single message
     * always has. Given one, this card contributes its height to that scroll instead of
     * starting a scroll of its own: several cards each scrolling independently would mean a
     * reader hunting for which card's scrollbar is under the pointer, and the whole point of
     * the stack is that it reads as one page.
     */
    externalScroll: ScrollState? = null,
    onLink: (String) -> Unit,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val quoteColor = MaterialTheme.colorScheme.outline
    // What the body sits on, so a colour the sender chose can be checked against it before
    // it is used. Part of the remember key: the same message on a different theme is a
    // different answer about which of its colours can be read.
    val bodyPaper = MaterialTheme.colorScheme.surface
    /*
     * The same join, but as bytes rather than a decoded picture, because the engine wants
     * a `data:` URI where the block renderer wanted a bitmap. The bytes are already here:
     * these are parts of the message, fetched over the account's own connection, so putting
     * one in the page tells nobody anything.
     */
    val carriedData = remember(attachments, imageBytes) {
        attachments.mapNotNull { part ->
            val cid = cidKey(part.cid) ?: return@mapNotNull null
            imageBytes[part.blobId]?.let { raw ->
                // Written out again in a format the engine will draw. See [drawable]: the
                // one that broke this was a CMYK JPEG, which lays out and paints nothing.
                val (type, bytes) = drawable(part.type, raw)
                cid to dataUri(type, bytes)
            }
        }.toMap()
    }
    /*
     * The message is drawn as the sender built it, on its own page, whatever the window
     * around it is doing.
     *
     * Rampart used to invert a message in a dark window, which is the trick webmail uses.
     * It was wrong twice over on real mail. A reply with no colours in it at all came out
     * as white text on a black slab, because inverting an unpainted page inverts the white
     * the engine supplies. And a design that was inverted is a design nobody made: a
     * hotel's dark brown header came out pink. So the page is always light, the sender's
     * own colours are always what you see, and the dark window holds a light message the
     * way a webmail tab does.
     */
    /*
     * A message with no colours of its own follows the window; one with a design does not.
     *
     * This is not the old inversion and must not become it. Nothing is filtered and no
     * pixel is turned inside out: a message that stated no colours is drawn on a dark page
     * instead of a light one, and a message that stated any is drawn exactly as it was
     * built, in either window. The switch on the toolbar puts this one message back on
     * paper when the guess is wrong, which is what it is for.
     */
    /*
     * Which page a message is drawn on: the window's, or the one that was asked for.
     *
     * Following the window is the default and is right for most people. It is not right for
     * everybody: a dark application with mail on paper, and a light application with mail
     * that does not flash white at night, are both things somebody wants, and they were one
     * setting only because the window was the only thing that knew.
     */
    val darkWindow = when (messageMode) {
        "dark" -> true
        "light" -> false
        else -> MaterialTheme.colorScheme.surface.luminance() < 0.5f
    }
    /*
     * Built once per message, not once per colour.
     *
     * Light and dark are the same document now, so neither the toolbar's switch nor the
     * window changing theme rebuilds it or makes the engine load anything again.
     */
    val page = remember(body, carriedData, showRemote) {
        body?.html?.let { emailDocument(it, carriedData, showRemote) }
    }
    /*
     * The engine had this message and drew nothing, so the block renderer gets it.
     *
     * Reset with the document, because every reason for a blank page so far has been about
     * this particular document reaching this particular engine, not about the engine being
     * broken from then on. Showing the pictures builds a new document and it gets a fresh
     * try.
     *
     * The block renderer is not as good and is not meant to be. It is the difference
     * between a message that looks plainer than the sender intended and a white rectangle,
     * and there is no version of this where the white rectangle is the better answer.
     */
    var engineBlank by remember(page) { mutableStateOf(false) }
    val engineDraws = page != null && webEngineWorks && !engineBlank
    val rendered = remember(body, linkColor, bodyPaper, engineDraws) {
        body?.let {
            when {
                // Nothing to build: the engine is drawing this one. Still not null, because
                // null here means the message has no body at all rather than no blocks.
                engineDraws -> HtmlDoc(emptyList(), emptyList())
                it.html != null -> htmlBlocks(it.html, linkColor, quoteColor, bodyPaper, onLink)
                // Plain text has no structure to keep, so it is one block and the same
                // drawing code handles both rather than there being two ways down.
                it.text != null -> HtmlDoc(
                    listOf(Block.Words(renderText(it.text, linkColor, onLink).text)),
                    emptyList(),
                )
                else -> null
            }
        }
    }
    /** The pictures the body puts on screen itself. Everything else the message brought is a file. */
    val shown = remember(body) { citedCids(body?.html) }
    // The body refers to a picture it carries by its Content-ID, not by its blob, so the
    // two have to be joined up before anything can be drawn in place.
    val carried = remember(attachments, images) {
        attachments.mapNotNull { part ->
            val cid = cidKey(part.cid) ?: return@mapNotNull null
            images[part.blobId]?.let { cid to it }
        }.toMap()
    }

    // The background, the watermark and filling the whole pane are the reading pane's own,
    // drawn once around the pane. A card sharing [externalScroll] is one of several inside
    // that pane rather than the pane itself, so it draws only its own content and lets its
    // width, not the window's height, decide how tall it is.
    val ownsPane = externalScroll == null
    Box(
        if (ownsPane) Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface) else Modifier.fillMaxWidth(),
    ) {
        if (ownsPane) ThemeArt(Modifier.align(Alignment.BottomEnd))
        Column(if (ownsPane) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
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
                /*
                 * Move, for the folders that have no button of their own.
                 *
                 * The list rather than a dialog: filing is a thing people do to one message
                 * at a time, over and over, and a dialog turns a click into a click, a wait,
                 * a read and a second click. Nested folders are shown by their depth rather
                 * than by a submenu, for the same reason.
                 */
                if (actions.folders.isNotEmpty() && actions.moveInto != null) {
                    var picking by remember(summary.id) { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { picking = true }) { Text("Move") }
                        DropdownMenu(picking, onDismissRequest = { picking = false }) {
                            actions.folders.forEach { folder ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            folder.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(
                                                start = (depthOf(folder, actions.folders) * 12).dp,
                                            ),
                                        )
                                    },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    onClick = { picking = false; actions.moveInto.invoke(folder.id) },
                                )
                            }
                        }
                    }
                }
                actions.junk?.let { OutlinedButton(onClick = it) { Text("Spam") } }
                actions.notJunk?.let { OutlinedButton(onClick = it) { Text("Not spam") } }
                actions.trash?.let { OutlinedButton(onClick = it) { Text("Delete") } }
                // Unread is what people press to mean "come back to this", and it was only
                // ever reachable by right-clicking the row the message was opened from.
                actions.markUnread?.let {
                    OutlinedButton(onClick = it) { Text(if (summary.seen) "Unread" else "Read") }
                }
                // Only where an engine drew it. The block renderer has no page to hand a
                // printer, and a Print button that does nothing is worse than none.
                if (engineDraws && page != null) {
                    OutlinedButton(onClick = { printDocument(page.document) }) { Text("Print") }
                }
                // Everything past Delete is something people reach for occasionally, and a
                // row of eight buttons runs off the edge of the pane at any sensible width.
                /*
                 * The light in the room, for one message.
                 *
                 * A message with no colours of its own is drawn to match the window, which
                 * is right for a reply and wrong for the occasional one: an invoice or a
                 * statement was laid out against white by whoever sent it, and a sender who
                 * set a text colour but no background can land dark on dark. Lit means the
                 * message is following the window; unlit means it is on paper, the way the
                 * sender built it.
                 *
                 * In a light window there is nothing for it to switch between, so it is not
                 * offered rather than being a button that appears to do nothing.
                 */
                if (darkWindow) {
                    IconButton(onClick = { onPaper(!paper) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            if (paper) RampartIcons.Bulb else RampartIcons.BulbOn,
                            contentDescription =
                            if (paper) "Draw this message dark" else "Show it as the sender drew it",
                            tint = if (paper) LocalContentColor.current
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                var more by remember(summary.id) { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { more = true }, modifier = Modifier.size(34.dp)) {
                        Icon(RampartIcons.More, contentDescription = "More", modifier = Modifier.size(17.dp))
                    }
                    DropdownMenu(more, onDismissRequest = { more = false }) {
                        actions.snooze?.let { put ->
                            SnoozeUntil.entries.forEach { until ->
                                DropdownMenuItem(
                                    text = { Text(until.label) },
                                    onClick = { more = false; put(until) },
                                )
                            }
                            HorizontalDivider()
                        }
                        DropdownMenuItem(
                            text = { Text(if (source == null) "View source" else "Back to the message") },
                            onClick = { more = false; onSource() },
                        )
                        // Only where there is an address book to put them in, and only
                        // when they are not already in it. An entry that silently makes a
                        // second copy of somebody is how an address book stops being
                        // worth opening.
                        if (onAddContact != null && !inContacts) {
                            DropdownMenuItem(
                                text = { Text("Add to contacts") },
                                onClick = { more = false; onAddContact(summary) },
                            )
                        }
                        // Only when the sender said how. Every client that offers Unsubscribe
                        // on mail that has no List-Unsubscribe is really offering to send a
                        // reply saying "unsubscribe" to somebody who is not reading replies.
                        unsubscribeFrom(body?.listUnsubscribe, body?.listUnsubscribePost)?.let { off ->
                            DropdownMenuItem(
                                text = { Text(if (off.oneClick) "Unsubscribe" else "Unsubscribe...") },
                                onClick = { more = false; onUnsubscribe(off) },
                            )
                        }
                        /*
                         * The whole conversation, as one act, worded so it cannot be mistaken
                         * for the row above it acting on one message. This used to be its own
                         * "Whole conversation" menu, which named a view of the thread and was
                         * actually four bulk actions; folded in here rather than kept apart,
                         * because it is a menu of actions either way.
                         */
                        actions.conversation?.let { conv ->
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (conv.unread > 0) "Mark whole conversation read"
                                        else "Mark whole conversation unread",
                                    )
                                },
                                onClick = { more = false; conv.onRead(conv.unread > 0) },
                            )
                            DropdownMenuItem(
                                text = { Text("Archive whole conversation") },
                                onClick = { more = false; conv.onArchive() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete whole conversation") },
                                onClick = { more = false; conv.onTrash() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (conv.muted) "Stop muting it" else "Mute this conversation") },
                                onClick = { more = false; conv.onMute(!conv.muted) },
                            )
                            if (conv.muted) {
                                // Said plainly, because it is the one promise here the server
                                // cannot keep on its own and somebody would otherwise read a
                                // silent hour as the mute not working.
                                Text(
                                    "New messages in this conversation are marked read and " +
                                        "archived while Rampart is running.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.widthIn(max = 260.dp)
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Hoisted above the source branch as well as the body below it, because both
            // want the one scroll: a card on its own scrolls itself, and a card in a stack
            // shares [externalScroll] with the header and every other card, so switching a
            // card to its source and back does not also reset how far down it was.
            val bodyScroll = externalScroll ?: rememberScrollState()
            val bodyScope = rememberCoroutineScope()
            // Filling the pane and scrolling itself is only right for a card on its own.
            // One inside a stack sizes to its own content and lets the stack's shared
            // Column, already scrolling, carry it.
            val pageModifier = if (ownsPane) Modifier.fillMaxSize().verticalScroll(bodyScroll) else Modifier.fillMaxWidth()

            if (source != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        savedTo?.let { "Saved to $it" } ?: "As it arrived, headers and all.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSaveSource) { Text("Save as .eml") }
                }
                val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                SelectionContainer {
                    Column(pageModifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        headersOf(source).forEach { (name, value) ->
                            Row(Modifier.padding(bottom = 2.dp)) {
                                Text(
                                    name,
                                    style = mono,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.width(150.dp),
                                )
                                Text(value, style = mono, modifier = Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(14.dp))
                        Text(bodyOf(source), style = mono)
                    }
                }
                return@Column
            }

            Column(pageModifier.padding(horizontal = 20.dp, vertical = 26.dp)) {
                // The whole window, not a column down the middle of it. A capped measure is
                // easier to read a paragraph in, and it was capped at 660 for that reason,
                // but it wastes most of a wide window and a designed message brings its own
                // width anyway.
                Paper(paper) {
                Column(Modifier.fillMaxWidth()) {
                    // The subject heads the conversation rather than the message: in a
                    // thread every message carries the same one with more Re: in front.
                    // Off when a stack-level header is already showing it once, above every
                    // card, rather than here on each of them in turn.
                    if (showSubject) {
                        Text(summary.subject, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(14.dp))
                    }

                    val proof = remember(body) {
                        authenticityOf(body?.authenticationResults?.joinToString("\n"), body?.spamStatus)
                    }
                    /*
                     * What is wrong with the message, above what failed to vouch for it.
                     *
                     * Read from the message's own HTML rather than the cleaned copy, because
                     * the cleaner removes forms and password fields, which is right for
                     * drawing it and would hide exactly what this is looking for.
                     */
                    val warnings = remember(body, summary, knownDomains) {
                        warningsFor(
                            fromEmail = summary.fromEmail,
                            fromName = summary.from,
                            html = body?.html,
                            authenticationResults = body?.authenticationResults?.joinToString("\n"),
                            replyTo = body?.replyTo.orEmpty(),
                            known = knownDomains,
                        )
                    }
                    warnings.forEach { warning ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text(
                                warning.says,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                warning.because,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
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

                    // Only on the card the conversation opened on: this is a paragraph
                    // about the whole thread, not about any one message in it, and belongs
                    // above whichever card is telling that story rather than on every card.
                    summarise?.let { SummaryCard(it) }

                    Row(
                        Modifier.fillMaxWidth()
                            .let { row -> onHeaderClick?.let { row.clickable(onClick = it) } ?: row }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            summary.from,
                            summary.fromEmail.ifBlank { summary.from },
                            34.dp,
                            photo = photoFor(summary.fromEmail),
                        )
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    summary.from,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // Only when the message went out through somewhere other
                                // than the domain it claims, which is the ordinary
                                // explanation for mail that looks odd and is not.
                                sentVia(summary.fromEmail, body?.authenticationResults?.joinToString("\n"))
                                    ?.let { host ->
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "via $host",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.clip(MaterialTheme.shapes.small)
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .padding(horizontal = 7.dp, vertical = 2.dp),
                                        )
                                    }
                            }
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
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                summary.receivedAt.asLocalTime(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            humanBytes(body?.size ?: 0L).takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }

                    /*
                     * Who else got it, and everything else on request.
                     *
                     * Two names and a count rather than all of them: a message to nine
                     * people should not push the body off the screen, and a bare number
                     * with no names is no use either.
                     */
                    var details by remember(summary.id) { mutableStateOf(showDetails) }
                    val everyone = remember(body) {
                        (body?.to.orEmpty() + body?.cc.orEmpty()).filter { it.isNotBlank() }
                    }
                    if (everyone.isNotEmpty()) {
                        val (shown, more) = shownRecipients(everyone)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "To",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                shown.joinToString(", ") + if (more > 0) "  +$more more" else "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (details) "Hide details" else "Show details",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clip(MaterialTheme.shapes.small)
                                    .clickable { details = !details }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (details) {
                        Spacer(Modifier.height(10.dp))
                        MessageDetails(summary, body, proof.spamScore)
                    }
                    val colours = LocalTagColours.current
                    val tags = remember(summary.keywords, colours) { tagsOf(summary.keywords, colours) }
                    var adding by remember(summary.id) { mutableStateOf<String?>(null) }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tags.forEach { tag ->
                            Row(
                                Modifier.clip(CircleShape).background(Color(tag.color))
                                    .clickable { onTag(tag.keyword, false) }
                                    .padding(start = 9.dp, end = 7.dp, top = 3.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    tag.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                )
                                Spacer(Modifier.width(5.dp))
                                // The whole chip removes the tag; the cross is there to say so.
                                Text("\u00d7", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            }
                        }
                        val typed = adding
                        if (typed == null) {
                            Text(
                                "Add tag",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clip(CircleShape).clickable { adding = "" }
                                    .padding(horizontal = 9.dp, vertical = 3.dp),
                            )
                        } else {
                            val focus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            fun commit() {
                                validKeyword(typed)?.let { onTag(it, true) }
                                adding = null
                            }
                            BasicTextField(
                                value = typed,
                                onValueChange = { adding = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.labelMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.width(140.dp).focusRequester(focus)
                                    .onFocusChanged { onTyping(it.isFocused) }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (event.key) {
                                            Key.Enter, Key.NumPadEnter -> { commit(); true }
                                            Key.Escape -> { adding = null; true }
                                            else -> false
                                        }
                                    }
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.shapes.small,
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    invitation?.let { meeting ->
                        Spacer(Modifier.height(16.dp))
                        InvitationCard(
                            invitation = meeting,
                            // The address this copy was addressed to, which is the one on
                            // the guest list and the one the answer goes out as.
                            me = me,
                            onAnswer = if (answering == null) onAnswer else null,
                        )
                    }

                    /*
                     * The sign-in code, in a size somebody can read across a desk.
                     *
                     * Read from the message itself rather than the preview the list had,
                     * because a body is the whole message and a preview is its first two
                     * hundred characters, so this finds the ones that put the code lower
                     * down. A message whose code the list already found shows the same one.
                     */
                    val signInCode = remember(summary.id, body) {
                        body?.let { oneTimeCode(summary.subject, it.text, it.html) }
                    }
                    if (signInCode != null) {
                        val clipboard = LocalClipboardManager.current
                        var copied by remember(signInCode) { mutableStateOf(false) }
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Sign-in code",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    Text(
                                        signInCode,
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(signInCode))
                                    copied = true
                                }) {
                                    Text(if (copied) "Copied" else "Copy")
                                }
                            }
                        }
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
                            body == null -> Spinner()
                            else -> Text("This message has no readable body.")
                        }
                    } else {
                    // Asked, never answered on its own. A receipt that sends itself is
                    // read tracking with the sender's name on it, and the whole reason the
                    // header is ignored by default everywhere else.
                    var receiptAnswered by remember(summary.id) { mutableStateOf(false) }
                    val asked = if (receiptAnswered) {
                        null
                    } else {
                        receiptWanted(
                            mapOf(MDN_HEADER to body?.receiptTo.orEmpty()),
                            summary.fromEmail,
                        )
                    }
                    if (asked != null) {
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "The sender asked to be told when this was opened.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { receiptAnswered = true }) {
                                Text("No", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { receiptAnswered = true; onReceipt(asked) }) {
                                Text("Send a receipt", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if ((page?.blocked ?: rendered.blockedImages) > 0 && !showRemote) {
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            /*
                             * Who was asking, not how many were blocked.
                             *
                             * Rampart already knew which pictures were remote, because that
                             * is the decision it makes to block them. Naming the trackers
                             * among them is what turns a count into something worth reading:
                             * "3 held back" says something happened, "2 of them are trackers,
                             * Mailchimp and HubSpot" says who was watching.
                             */
                            val blocked = page?.blocked ?: rendered.blockedImages
                            val trackers = page?.trackers ?: 0
                            Text(
                                buildString {
                                    append(if (blocked == 1) "1 picture is held back." else "$blocked pictures are held back.")
                                    if (trackers > 0) {
                                        append(if (trackers == 1) " It is a tracker" else " $trackers of them are trackers")
                                        trackerLine(page?.held.orEmpty()).takeIf { it.isNotBlank() }
                                            ?.let { append(": $it") }
                                        append(".")
                                    }
                                },
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
                    /*
                     * The engine for HTML, the block renderer for everything else.
                     *
                     * Not a fallback: a plain text message has no layout to get right and
                     * drawing it in Compose keeps it selectable, themed and part of the
                     * same scroll as the rest of the pane, with no engine to start.
                     */
                    if (engineDraws) WebBody(
                        page.document,
                        onLink = onLink,
                        onScroll = { dy -> bodyScope.launch { bodyScroll.scrollBy(dy) } },
                        onBlank = { engineBlank = true },
                        dark = darkWindow && !paper,
                        scale = messageScale,
                    )
                    else {
                        /*
                         * Never silently.
                         *
                         * A message shown plainly because the engine would not draw it
                         * looks like a message the sender wrote plainly, and the difference
                         * matters when somebody is deciding whether to trust what they are
                         * reading. It also stops the next report of this being "it looks
                         * wrong" with nothing to go on.
                         */
                        if (engineBlank) {
                            Text(
                                "This message would not draw, so it is shown as plain text.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        HtmlBody(rendered, carried, emptyMap())
                    }
                    }

                    // Only the ones the body did not already put on screen, which is the ones
                    // it actually refers to rather than the ones that happen to have a
                    // Content-ID. See [citedCids]: Outlook gives one to every part.
                    val files = attachments
                        .filter { cidKey(it.cid) !in shown }
                        // The card above is the invitation. Listing invite.ics underneath it
                        // offers somebody a file whose entire content is already on screen.
                        .filter { invitation == null || !it.type.equals("text/calendar", ignoreCase = true) }
                    if (files.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(14.dp))
                        files.forEach { attachment ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                /*
                                 * A picture is shown, not just named. The bytes are already
                                 * here and already decoded, because a part with a Content-ID
                                 * is fetched with the body whether the body draws it or not,
                                 * so this costs a draw call. A screenshot somebody sent is
                                 * recognisable from it, and "Screenshot 2026-09-19 at
                                 * 10.44.51 AM.png" is not.
                                 */
                                images[attachment.blobId]?.let { picture ->
                                    Image(
                                        picture,
                                        contentDescription = attachment.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                    )
                                }
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
                    Spacer(Modifier.height(40.dp))
                }
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
 * The same instant written out in full, for the details panel.
 *
 * The short form beside a message is right there, where the year and the seconds are noise.
 * In the panel they are the point: the gap between when a message says it was sent and
 * when it actually landed is how you spot one that sat somewhere for an hour.
 */
internal fun String.asFullLocalTime(): String = runCatching {
    java.time.format.DateTimeFormatter
        .ofPattern("EEEE, d MMMM yyyy 'at' HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault())
        .format(Instant.parse(this))
}.getOrDefault(this)

/**
 * What the assistant said about this thread, or the button that would ask it to.
 *
 * Its own card rather than a line among the message buttons above, so a paragraph nobody
 * wrote cannot be mistaken for one somebody did: no name beside it, no date, a heading
 * that says plainly what it is.
 */
@Composable
private fun SummaryCard(summarise: SummariseActions) {
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Summary, from the assistant",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            if (summarise.running) Spinner(size = 15.dp, thickness = 2.dp)
        }
        summarise.paragraph?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        // Whichever of these is current, shown under whatever paragraph is already there
        // rather than instead of it: a paragraph from a moment ago is still worth having
        // even when the next attempt at a fresh one failed or has since become disabled.
        (summarise.error ?: summarise.disabledBecause)?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (summarise.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = summarise.onSummarise,
                enabled = !summarise.running && summarise.disabledBecause == null,
            ) {
                Text(
                    when {
                        summarise.running -> "Summarising"
                        summarise.paragraph != null -> "Summarise again"
                        else -> "Summarise this thread"
                    },
                )
            }
            summarise.onViewPacket?.let { view ->
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = view) {
                    Text(
                        if (summarise.paragraph != null) "What was sent" else "What would be sent",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
}

/**
 * One collapsed message in the thread stack: who, when, and a preview where there is one.
 * Clicking it opens the card in place, exactly where this row was.
 *
 * [Summary.preview] is empty on IMAP, which has no equivalent and no cheap way to build
 * one: getting it would mean fetching a body for every row, which is the download the list
 * exists to avoid. So the preview is left out rather than drawn as a gap, and the row is
 * just the name and the date, which is still a row worth reading.
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
        Avatar(
            message.from,
            message.fromEmail.ifBlank { message.from },
            24.dp,
            photo = photoFor(message.fromEmail),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            message.from,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (message.seen) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (message.preview.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                message.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            message.receivedAt.asLocalTime(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * A batch move, and where it came from.
 *
 * Kept so it can be put back. Undo for a move is only ever a move in the other direction,
 * which is the whole reason batch actions are moves and nothing else: filing fifty messages
 * by accident is recoverable, and a client that makes that unrecoverable is one people
 * stop using for anything but reading.
 */
/** One account's share of a batch that was moved, and where to put it back. */
internal data class Move(val accountKey: String, val ids: List<String>, val fromMailboxId: String)

/**
 * A list of moves rather than one, because a batch picked out of the merged inbox can span
 * accounts, and putting half of it back is worse than offering no undo at all.
 */
/**
 * The strip that offers to take back what just happened, and drains while it does.
 *
 * **A bar that never goes away stops being read.** It sat there until dismissed, so after
 * a few archives it was furniture: present, ignored, and covering the top of the list.
 * Auto-dismissing fixes that and raises a new question, which is how long is left, and the
 * answer belongs on the button rather than beside it. The fill recedes across Undo, so the
 * thing you would press is the thing showing how long you can press it for.
 *
 * [seconds] of zero means it stays until dismissed, which is the old behaviour kept for
 * anyone who wants it.
 */
@Composable
internal fun UndoBar(
    text: String,
    seconds: Int,
    /** Changes when a new thing happens, which is what restarts the drain. */
    restartOn: Any?,
    onUndo: () -> Unit,
    /** Called when the time runs out. Null where something else takes the bar away. */
    onExpire: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val left = remember(restartOn) { Animatable(1f) }
    LaunchedEffect(restartOn, seconds) {
        if (seconds <= 0) return@LaunchedEffect
        left.snapTo(1f)
        // Linear, because this is a clock. Any easing makes the last second look longer
        // than the first, which is the one thing a countdown must not do.
        left.animateTo(0f, tween(durationMillis = seconds * 1000, easing = LinearEasing))
        onExpire?.invoke()
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        val drain = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        TextButton(
            onClick = onUndo,
            modifier = Modifier.clip(MaterialTheme.shapes.small).drawBehind {
                if (seconds > 0) drawRect(drain, size = Size(size.width * left.value, size.height))
            },
        ) { Text("Undo", style = MaterialTheme.typography.bodySmall) }
        onDismiss?.let {
            TextButton(onClick = it) { Text("Dismiss", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

internal data class Undoable(
    val moves: List<Move>,
    /** What to call it on screen, already in the past tense. */
    val what: String,
) {
    val count: Int get() = moves.sumOf { it.ids.size }
}

/**
 * What the reading pane shows while more than one message is picked.
 *
 * Deliberately only the actions that can be undone. Nothing here sends, replies or deletes
 * outright: the mistake somebody makes with fifty messages selected is the one they cannot
 * take back, so the pane offers nothing of that kind.
 */
@Composable
private fun Picked(
    count: Int,
    onClear: () -> Unit,
    onFile: (role: String, what: String) -> Unit,
    onRead: () -> Unit,
    /** Whether the folder being looked at is Junk, which swaps Spam for Not spam. */
    inJunk: Boolean = false,
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$count messages picked", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onFile("archive", "archived") }) { Text("Archive") }
            if (inJunk) {
                OutlinedButton(onClick = { onFile("inbox", "moved to the inbox") }) { Text("Not spam") }
            } else {
                OutlinedButton(onClick = { onFile("junk", "marked as spam") }) { Text("Spam") }
            }
            OutlinedButton(onClick = { onFile("trash", "deleted") }) { Text("Delete") }
            OutlinedButton(onClick = onRead) { Text("Mark read") }
        }
        TextButton(onClick = onClear) { Text("Clear", style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * What is picked out after a click, given what was picked and where the anchor was.
 *
 * The rules are the ones every desktop list has had for thirty years, and the reason this
 * is a function rather than four lines in a lambda is that getting shift wrong quietly
 * files the wrong fifty messages.
 *
 * A plain click picks nothing out: one message is simply the one being read, and drawing a
 * selection around it would make every single click look like the start of a batch.
 */
internal fun pickedAfter(
    ids: List<String>,
    picked: Set<String>,
    anchor: String?,
    target: String,
    ctrl: Boolean,
    shift: Boolean,
): Set<String> {
    val from = ids.indexOf(anchor)
    val to = ids.indexOf(target)
    return when {
        shift && from >= 0 && to >= 0 -> ids.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
        // Shift with nothing to measure from is a plain click, not an empty selection.
        shift -> emptySet()
        ctrl -> if (target in picked) picked - target else picked + target
        else -> emptySet()
    }
}

/**
 * How many messages are fetched ahead of being asked for. See the read ahead in [Reader].
 *
 * About a screenful. Enough that the ones somebody is looking at are already here, few
 * enough that opening a folder does not quietly download it.
 */
private const val READ_AHEAD = 12
