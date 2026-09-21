package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.isTraySupported
import androidx.compose.foundation.Image
import kotlin.math.roundToInt

/**
 * Settings, as a pane rather than a dialog.
 *
 * A dialog would have been less work, but this is where everything that is not a message is
 * going to end up, and a modal box is the wrong container for a screen that grows. It
 * replaces the three panes and leaves the sidebar alone, so getting back out is the same
 * click as any other change of folder.
 */
@Composable
internal fun SettingsPane(
    accounts: List<AccountMailboxes>,
    identities: List<Identity>,
    /** Null while it has not been read yet, or on a server with no responder. */
    vacation: Vacation?,
    vacationError: String?,
    onVacation: (Vacation) -> Unit,
    signatureError: String?,
    onSignature: (Identity, String) -> Unit,
    onPickSignatureImage: () -> String?,
    update: String?,
    notifyOnArrival: Boolean,
    onNotifyOnArrival: (Boolean) -> Unit,
    /** Defaults so the screenshot harness, which never opens the real settings file, still compiles. */
    notifyOnOpen: Boolean = true,
    onNotifyOnOpen: (Boolean) -> Unit = {},
    onTheme: (Theme) -> Unit,
    iconPack: IconPack = LineIcons,
    onIconPack: (IconPack) -> Unit = {},
    onAddAccount: () -> Unit,
    /** How full each account is, by account key. Empty until settings is open. */
    quotas: Map<String, List<MailQuota>> = emptyMap(),
    /** Told when the row tinting is switched, so the list redraws without reopening. */
    onTintRowsByTag: (Boolean) -> Unit = {},
    /** Told when the undo strip's lifetime changes, so the next one uses it. */
    onUndoBarSeconds: (Int) -> Unit = {},
    /** Told when the loader changes, so every spinner in the app switches at once. */
    onLoader: (Loader) -> Unit = {},
    /** Told when the page messages are drawn on changes, so the open one changes with it. */
    onMessageMode: (String) -> Unit = {},
    /** Told when the message text size changes, for the same reason. */
    onMessageScale: (Float) -> Unit = {},
    /** Told when the tracking server changes, so the composer's toggle appears or goes. */
    onTrackingServer: (String) -> Unit = {},
    onRestart: () -> Unit,
    /** Null while the server's filter script is still being read. */
    filters: Script?,
    filtersSupported: Boolean,
    filtersSaving: Boolean,
    filtersError: String?,
    /** Which account's filters are showing, or null for the set kept for every account. */
    filterAccount: String?,
    onFilterAccount: (String?) -> Unit,
    globalFilters: GlobalFilters,
    onGlobalFilters: (GlobalFilters) -> Unit,
    onFilters: (Script) -> Unit,
    onClose: () -> Unit,
    /** Which page opens first. Only ever passed by the screenshot tests. */
    initialPage: String = SettingsPages.first().first,
) {
    var page by remember { mutableStateOf(initialPage) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(RampartIcons.Back, contentDescription = "Back to the mail", modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(Modifier.fillMaxSize()) {
            SettingsNav(page) { page = it }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            ) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    when (page) {
                        "accounts" -> AccountsPage(accounts, onAddAccount, quotas)
                        "notifications" -> NotificationsPage(
                            notifyOnArrival, onNotifyOnArrival, notifyOnOpen, onNotifyOnOpen,
                        )
                        "reading" -> ReadingPage(onUndoBarSeconds, onMessageMode, onMessageScale)
                        "filters" -> FiltersPage(
                            script = filters,
                            accounts = accounts,
                            chosen = filterAccount,
                            onChoose = onFilterAccount,
                            globals = globalFilters,
                            onGlobals = onGlobalFilters,
                            /*
                             * Only real folders, so nobody files into one that does not
                             * exist. The chosen account's own, because a rule filing into
                             * another account's folder is one the server will refuse.
                             * Rules kept for every account offer what they all have.
                             */
                            folders = accounts
                                .filter { filterAccount == null || it.key == filterAccount }
                                .map { account -> account.mailboxes.map { it.name }.toSet() }
                                .reduceOrNull { all, next -> all intersect next }
                                .orEmpty().sorted(),
                            saving = filtersSaving,
                            error = filtersError,
                            supported = filtersSupported,
                            onSave = onFilters,
                        )
                        "themes" -> ThemesPage(onTheme, iconPack, onIconPack, onTintRowsByTag, onLoader)
                        "identities" -> IdentitiesPage(
                            identities, signatureError, onSignature, onPickSignatureImage,
                        )
                        "away" -> AwayPage(vacation, vacationError, onVacation)
                        "tracking" -> TrackingPage(onTrackingServer)
                        "assistant" -> AssistantPage(accounts)
                        "diagnostics" -> DiagnosticsPage()
                        "about" -> AboutPage(update, onRestart)
                    }
                }
            }
        }
    }
}

/**
 * The pages, in the order they appear, each under a heading.
 *
 * A flat list rather than a nested structure: the heading is just the third element, and
 * a run of pages sharing one is drawn under it. Two levels of data for a menu with six
 * entries in it would be more machinery than the menu.
 */
private val SettingsPages: List<Triple<String, String, String>> = listOf(
    Triple("accounts", "Accounts", "General"),
    Triple("notifications", "Notifications", "General"),
    Triple("themes", "Themes", "Appearance"),
    // Kept with the other Mail pages. The nav groups in list order, so a page filed out of
    // sequence makes its heading appear twice.
    Triple("reading", "Reading and archiving", "Mail"),
    Triple("filters", "Filters", "Mail"),
    Triple("identities", "Identities and signatures", "Mail"),
    Triple("away", "Away reply", "Mail"),
    Triple("tracking", "Open tracking", "Mail"),
    Triple("assistant", "Assistant", "Mail"),
    Triple("diagnostics", "Diagnostics", "Rampart"),
    Triple("about", "About", "Rampart"),
)

@Composable
private fun SettingsNav(current: String, onPick: (String) -> Unit) {
    Column(
        Modifier.width(232.dp).fillMaxHeight().verticalScroll(rememberScrollState())
            .padding(vertical = 14.dp, horizontal = 10.dp),
    ) {
        var heading: String? = null
        SettingsPages.forEach { (key, label, group) ->
            if (group != heading) {
                heading = group
                Text(
                    group.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 5.dp),
                )
            }
            val here = key == current
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal,
                color = if (here) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .background(
                        if (here) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                    )
                    .clickable { onPick(key) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun ThemesPage(
    onTheme: (Theme) -> Unit,
    iconPack: IconPack,
    onIconPack: (IconPack) -> Unit,
    onTintRowsByTag: (Boolean) -> Unit = {},
    onLoader: (Loader) -> Unit = {},
    /** Told when the tracking server changes, so the composer's toggle appears or goes. */
    onTrackingServer: (String) -> Unit = {},
) {
    val current = LocalRampartTheme.current
    Section("Theme", "Ported from Clique, so the ones you already picked there are here.")
    // Not lazy in any useful sense: there are eighteen of these and the column above
    // already scrolls. The grid is here for the wrapping, so the height has to be given,
    // and a fixed row height times the number of rows is it.
    val columns = 3
    val rows = (THEMES.size + columns - 1) / columns
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().height((rows * 104).dp),
    ) {
        items(THEMES, key = { it.key }) { theme ->
            ThemeCard(theme, selected = theme.key == current.key) { onTheme(theme) }
        }
    }

    Spacer(Modifier.height(22.dp))
    Section(
        "Icons",
        "Kept apart from the palette, so one can be changed without the other.",
    )
    ICON_PACKS.forEach { option ->
        Row(
            Modifier.fillMaxWidth().clickable { onIconPack(option) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option.key == iconPack.key, onClick = { onIconPack(option) })
            Spacer(Modifier.width(8.dp))
            Text(option.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(80.dp))
            // The pack drawn in its own glyphs, which is the only description that is
            // actually about what changes.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(option.Inbox, option.Archive, option.Sent, option.Trash, option.Star).forEach {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    Section(
        "While it is loading",
        "Each one is drawn from your theme's own colour, so it matches whatever you picked above.",
    )
    var loader by remember { mutableStateOf(Loader.of(Settings.loader())) }
    // Every one running at once, because a loader is motion and a still picture of one
    // tells you nothing about whether you want it.
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Loader.entries.forEach { each ->
            val picked = each == loader
            Column(
                Modifier.weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (picked) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    )
                    .clickable {
                        loader = each
                        Settings.setLoader(each.name)
                        onLoader(each)
                    }
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spinner(which = each, size = 30.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    each.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (picked) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    var tint by remember { mutableStateOf(Settings.tintRowsByTag()) }
    Section(
        "Tags in the list",
        "Bulwark colours a tagged row. Off here by default, because Rampart already tints " +
            "an unread row and two tints on one row read as a fault rather than two facts.",
    )
    Row(
        Modifier.fillMaxWidth().clickable {
            tint = !tint
            Settings.setTintRowsByTag(tint)
            onTintRowsByTag(tint)
        }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(
            checked = tint,
            onCheckedChange = {
                tint = it
                Settings.setTintRowsByTag(it)
                onTintRowsByTag(it)
            },
        )
        Spacer(Modifier.width(12.dp))
        Text("Colour a tagged row with its tag", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * How long an open message waits before it counts as read.
 *
 * Offered as a few choices rather than a number box: the useful values are "at once" and
 * "long enough to pass over it", and asking somebody for milliseconds is asking them to
 * guess at something they can only judge by feel.
 */
@Composable
private fun ReadingPage(
    onUndoBarSeconds: (Int) -> Unit = {},
    onMessageMode: (String) -> Unit = {},
    onMessageScale: (Float) -> Unit = {},
) {
    var delay by remember { mutableStateOf(Settings.markReadDelay()) }
    Section(
        "Marking as read",
        "Arrow-keying down a list with no pause marks every message you pass through.",
    )
    listOf(
        0L to "At once",
        2000L to "After 2 seconds",
        5000L to "After 5 seconds",
        -1L to "Never, unless I say so",
    ).forEach { (value, label) ->
        Row(
            Modifier.fillMaxWidth().clickable {
                delay = value
                Settings.setMarkReadDelay(value)
            }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = delay == value, onClick = {
                delay = value
                Settings.setMarkReadDelay(value)
            })
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }

    Spacer(Modifier.height(18.dp))
    var mode by remember { mutableStateOf(Settings.messageMode()) }
    Section(
        "The page a message is drawn on",
        "A message that brought a design of its own is always drawn the way it was built. " +
            "This is for the rest: a reply with no colours in it.",
    )
    listOf(
        "" to "The same as the window",
        "light" to "Always on paper",
        "dark" to "Always dark",
    ).forEach { (value, label) ->
        Row(
            Modifier.fillMaxWidth().clickable {
                mode = value
                Settings.setMessageMode(value)
                onMessageMode(value)
            }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = mode == value, onClick = {
                mode = value
                Settings.setMessageMode(value)
                onMessageMode(value)
            })
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text(
        "The switch on the message toolbar still puts one message the other way, whichever " +
            "of these is chosen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )

    Spacer(Modifier.height(18.dp))
    Section(
        "How large a message is drawn",
        "Normal is the same size as everything else in the window. The message is drawn by " +
            "a separate engine, so on some displays it comes out smaller than the rest of " +
            "the window; that is corrected before this applies.",
    )
    var messageScale by remember { mutableStateOf(Settings.messageScale()) }
    listOf(0.9f to "Smaller", 1.0f to "Normal", 1.15f to "Larger", 1.3f to "Largest")
        .forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    messageScale = value
                    Settings.setMessageScale(value)
                    onMessageScale(value)
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = messageScale == value, onClick = {
                    messageScale = value
                    Settings.setMessageScale(value)
                    onMessageScale(value)
                })
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }

    Spacer(Modifier.height(18.dp))
    Section(
        "Taking a send back",
        "A message waits this long before it actually goes, so a wrong recipient can be caught.",
    )
    var undo by remember { mutableStateOf(Settings.undoSeconds()) }
    listOf(0 to "Send straight away", 5 to "Wait 5 seconds", 10 to "Wait 10 seconds", 30 to "Wait 30 seconds")
        .forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    undo = value
                    Settings.setUndoSeconds(value)
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = undo == value, onClick = {
                    undo = value
                    Settings.setUndoSeconds(value)
                })
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }

    Spacer(Modifier.height(18.dp))
    Section(
        "How long the undo strip stays",
        "After archiving or moving something. Different from the number above: that one " +
            "holds every message you send, this one is only how long the offer is on screen.",
    )
    var undoBar by remember { mutableStateOf(Settings.undoBarSeconds()) }
    listOf(5 to "5 seconds", 8 to "8 seconds", 15 to "15 seconds", 0 to "Until I dismiss it")
        .forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    undoBar = value
                    Settings.setUndoBarSeconds(value)
                    onUndoBarSeconds(value)
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = undoBar == value, onClick = {
                    undoBar = value
                    Settings.setUndoBarSeconds(value)
                    onUndoBarSeconds(value)
                })
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }

    Spacer(Modifier.height(18.dp))
    Section(
        "Where your sign-off goes",
        "On a reply or a forward. A new message has no quote, so it makes no difference there.",
    )
    var aboveQuote by remember { mutableStateOf(Settings.signatureAboveQuote()) }
    listOf(
        false to "Under everything, below the quoted message",
        true to "Under what I wrote, above the quoted message",
    ).forEach { (value, label) ->
        Row(
            Modifier.fillMaxWidth().clickable {
                aboveQuote = value
                Settings.setSignatureAboveQuote(value)
            }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = aboveQuote == value, onClick = {
                aboveQuote = value
                Settings.setSignatureAboveQuote(value)
            })
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }

    Spacer(Modifier.height(18.dp))
    Section(
        "Archiving",
        "A folder with fifteen years of mail in it is a folder nobody opens.",
    )
    var archiveBy by remember { mutableStateOf(Settings.archiveBy()) }
    listOf(
        "" to "Straight into Archive",
        "year" to "Into Archive, by year",
        "month" to "Into Archive, by month",
    ).forEach { (value, label) ->
        Row(
            Modifier.fillMaxWidth().clickable {
                archiveBy = value
                Settings.setArchiveBy(value)
            }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = archiveBy == value, onClick = {
                archiveBy = value
                Settings.setArchiveBy(value)
            })
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NotificationsPage(
    notifyOnArrival: Boolean,
    onNotifyOnArrival: (Boolean) -> Unit,
    notifyOnOpen: Boolean,
    onNotifyOnOpen: (Boolean) -> Unit,
) {
    Section("Notifications", "Rampart checks for new mail every minute while it is open.")
    Row(
        Modifier.fillMaxWidth().clickable { onNotifyOnArrival(!notifyOnArrival) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Tell me when mail arrives", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (isTraySupported) "One notification per batch, not one per message."
                // Worth saying rather than leaving a switch that does nothing.
                else "This desktop has no notification area, so nothing will appear.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = notifyOnArrival, onCheckedChange = onNotifyOnArrival, enabled = isTraySupported)
    }
    Row(
        Modifier.fillMaxWidth().clickable { onNotifyOnOpen(!notifyOnOpen) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Tell me when a tracked message is opened", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (isTraySupported) "One notification per batch, not one per open."
                else "This desktop has no notification area, so nothing will appear.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = notifyOnOpen, onCheckedChange = onNotifyOnOpen, enabled = isTraySupported)
    }

    // Kept next to notifications rather than under a Window heading of its own: both are
    // about what Rampart does when nobody is looking at it, and one switch does not earn
    // a page.
    var toTray by remember { mutableStateOf(Settings.closeToTray()) }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = isTraySupported) { toTray = !toTray; Settings.setCloseToTray(toTray) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Keep running when I close the window", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (isTraySupported) {
                    "Mail keeps arriving and the count stays on the tray icon. Quit from the tray menu."
                } else {
                    "This desktop has no notification area, so there is nowhere to close to."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(
            checked = toTray,
            onCheckedChange = { toTray = it; Settings.setCloseToTray(it) },
            enabled = isTraySupported,
        )
    }
}

@Composable
private fun AccountsPage(
    accounts: List<AccountMailboxes>,
    onAddAccount: () -> Unit,
    /** How full each account is, by account key. Absent where the server keeps no limit. */
    quotas: Map<String, List<MailQuota>> = emptyMap(),
) {
    Section("Accounts", "Signed in on this computer. Passwords stay in Windows, never in a file.")
    accounts.forEach { account ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(account.name, account.email, 32.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    shortAccountName(account.name, account.email),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    account.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                mainQuota(quotas[account.key].orEmpty())?.let { quota ->
                    Spacer(Modifier.height(5.dp))
                    quotaShare(quota)?.let { share ->
                        LinearProgressIndicator(
                            progress = { share },
                            color = if (quotaIsTight(quota)) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        quotaText(quota),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (quotaIsTight(quota)) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onAddAccount) { Text("Add account") }
    // Said once, under the list, rather than as a line under every account. A mailbox with
    // no limit is the normal case and repeating it would read as something being wrong.
    if (accounts.isNotEmpty() && accounts.none { mainQuota(quotas[it.key].orEmpty()) != null }) {
        Spacer(Modifier.height(10.dp))
        Text(
            "No storage limit is set on " + (if (accounts.size > 1) "these mailboxes" else "this mailbox") +
                ", so there is nothing to run out of. If one is set later it appears here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun IdentitiesPage(
    identities: List<Identity>,
    signatureError: String?,
    onSignature: (Identity, String) -> Unit,
    onPickSignatureImage: () -> String?,
) {
    Section(
        "Identities and signatures",
        "Kept on the server against each sending address, so one written here is the " +
            "one the webmail uses too.",
    )
    signatureError?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
    if (identities.isEmpty()) {
        Text(
            "This account has no sending address, so there is nothing to sign.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    identities.forEach { identity ->
        SignatureEditor(
            address = identity.email,
            html = identity.htmlSignature,
            onHtml = { onSignature(identity, it) },
            onAddImage = onPickSignatureImage,
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun AwayPage(vacation: Vacation?, vacationError: String?, onVacation: (Vacation) -> Unit) {
    Section(
        "When you are away",
        "The server sends this on your behalf, so it keeps working when Rampart " +
            "is closed. One reply per person, not one per message.",
    )
    if (vacation == null) {
        Text(
            "This server does not do away replies, or it has not answered yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    AwayReply(vacation, vacationError, onVacation)
}

/**
 * Where the companion server is, and whether Rampart can reach it.
 *
 * **Empty is the normal case and the page says so plainly.** The rule this app is built
 * around is that a feature needing infrastructure is visibly unavailable with a sentence
 * saying why, never quietly missing, because a toggle that silently does nothing means
 * every message goes out tracked and nothing is ever recorded.
 */
@Composable
private fun TrackingPage(onTrackingServer: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(Settings.trackingServer()) }
    var token by remember { mutableStateOf(Secrets.trackingToken().orEmpty()) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var worked by remember { mutableStateOf(false) }

    Section(
        "Open tracking",
        "A 1x1 image in a message you send, at an address unique to it. When the recipient's " +
            "mail client fetches that image, your own server writes down that it did. That is " +
            "the whole mechanism, and it is what every sales tool does.",
    )
    Text(
        "It is off unless you switch it on for a particular message, and there is deliberately " +
            "no way to turn it on for everything. Rampart blocks other people's tracking pixels " +
            "by default and names who sent them, which is the same feature pointed the other way.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(14.dp))

    OutlinedTextField(
        value = server,
        onValueChange = { server = it; result = null },
        label = { Text("Your companion server") },
        placeholder = { Text("https://img.example.com") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = token,
        onValueChange = { token = it; result = null },
        label = { Text("The token you started it with") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !checking,
            onClick = {
                checking = true
                result = null
                scope.launch {
                    val problem = withContext(Dispatchers.IO) { TrackingClient.check(server, token) }
                    if (problem == null) {
                        Settings.setTrackingServer(server)
                        onTrackingServer(server)
                        // The reason a save can fail is worth showing: without a credential
                        // store the token is not kept and tracking stops working on restart.
                        result = Secrets.setTrackingToken(token) ?: "Saved. Rampart can reach it."
                        worked = true
                    } else {
                        result = problem
                        worked = false
                    }
                    checking = false
                }
            },
        ) { Text(if (checking) "Checking" else "Check and save") }
        if (checking) {
            Spacer(Modifier.width(12.dp))
            Spinner(size = 20.dp, thickness = 2.dp)
        }
        if (server.isNotBlank() || token.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                server = ""
                token = ""
                Settings.setTrackingServer("")
                Secrets.setTrackingToken("")
                onTrackingServer("")
                result = "Turned off. The toggle will not appear on the composer."
                worked = false
            }) { Text("Turn it off") }
        }
    }
    result?.let {
        Spacer(Modifier.height(10.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (worked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(18.dp))
    Section(
        "You have to run the server yourself",
        "Rampart is a desktop app, and a tracking pixel has to be fetched from somewhere on " +
            "the web, which a desktop app is not. The server is in the Rampart repository " +
            "under server/, with a Dockerfile and a compose file: it is one container and one " +
            "hostname. It never sees a message, a subject, a recipient or an address.",
    )

    Spacer(Modifier.height(18.dp))
    Section(
        "What it cannot tell you",
        "An open is a picture being fetched, which is not the same as somebody reading. " +
            "Gmail fetches every image through its own proxy on delivery, and Apple Mail " +
            "Privacy Protection pre-fetches everything for everyone who has it on. Rampart " +
            "records those as automatic rather than counting them, because an open count " +
            "that includes robots is a number that makes you chase somebody who never read " +
            "anything. A recipient who blocks images, as Rampart does by default, never " +
            "registers at all.",
    )
}

/**
 * What Rampart has measured about itself, and whether any of it leaves the machine.
 *
 * Two halves, deliberately drawn in that order. What is being measured works with nothing
 * configured below and no toggle at all, because it answers "why did that message feel
 * slow just now" from what is already on this computer. Sending anything on is the second,
 * separate decision, and it is worded here in terms of what actually goes: numbers and a
 * short list of named categories, never a message. See `Diagnostics.kt` for the allowlist
 * this page can only ever be a window onto, never a way around.
 */
@Composable
private fun DiagnosticsPage() {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(Settings.diagnosticsServer()) }
    var token by remember { mutableStateOf(Secrets.loadNamed(Diagnostics.TOKEN_NAME).orEmpty()) }
    var reporting by remember { mutableStateOf(Settings.diagnosticsReporting()) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var worked by remember { mutableStateOf(false) }
    // Read once, when the page opens, the same way the settings fields below are: this is a
    // read-out of what already happened, not a live feed that has to keep redrawing itself.
    val events = remember { Diagnostics.recent() }

    Section(
        "What is being measured, right now",
        "How long messages took to open, how many folder loads needed the server rather " +
            "than the local copy, and what kind of trouble a send or a sync ran into. This " +
            "works with nothing set up below: it is read from this computer alone.",
    )

    val totals = events.filter { it.metric == Metric.MESSAGE_OPEN_TOTAL.key }.take(8)
    val fetches = events.filter { it.metric == Metric.MESSAGE_OPEN_FETCH.key }.take(8)
    val renders = events.filter { it.metric == Metric.MESSAGE_OPEN_RENDER.key }.take(8)
    val commands = events.filter { it.metric == Metric.LIST_COMMANDS.key }
    val troubles = events.filter { it.category != null }.take(8)

    if (totals.isEmpty() && commands.isEmpty() && troubles.isEmpty()) {
        Text(
            "Nothing yet this session. Open a few messages and a folder or two, then come " +
                "back here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    if (totals.isNotEmpty()) {
        Text("Message open, most recent first", style = MaterialTheme.typography.labelLarge)
        Text(totals.joinToString("   ") { msText(it.value) }, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
    }
    if (fetches.isNotEmpty()) {
        Text("Fetch, of the same", style = MaterialTheme.typography.labelLarge)
        Text(fetches.joinToString("   ") { msText(it.value) }, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
    }
    if (renders.isNotEmpty()) {
        Text("Render, of the same", style = MaterialTheme.typography.labelLarge)
        Text(renders.joinToString("   ") { msText(it.value) }, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
    }
    if (commands.isNotEmpty()) {
        Text(
            "${commands.size} IMAP or JMAP commands issued, across the folder loads kept here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(6.dp))
    }
    if (troubles.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Recent trouble, by category", style = MaterialTheme.typography.labelLarge)
        troubles.forEach { row ->
            Text(
                row.metric + ": " + row.category + (row.detail?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Spacer(Modifier.height(18.dp))
    Section(
        "Sending it on",
        "Aggregated counts and durations only, every few minutes: never a subject, a " +
            "sender, a recipient, a folder's name or a search term, and never the text of " +
            "an error, only which of a short list of categories it was.",
    )
    Row(
        Modifier.fillMaxWidth()
            .clickable { reporting = !reporting; Settings.setDiagnosticsReporting(reporting) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Send this on to the server below", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (server.isBlank()) "There is nowhere to send it until a server is saved below."
                else "On by default once a server is saved. This is what turns it back off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = reporting, onCheckedChange = { reporting = it; Settings.setDiagnosticsReporting(it) })
    }
    Spacer(Modifier.height(14.dp))

    OutlinedTextField(
        value = server,
        onValueChange = { server = it; result = null },
        label = { Text("Your diagnostics server") },
        placeholder = { Text("https://img.example.com") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = token,
        onValueChange = { token = it; result = null },
        label = { Text("The token you started it with") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !checking,
            onClick = {
                checking = true
                result = null
                scope.launch {
                    val problem = withContext(Dispatchers.IO) { Diagnostics.checkServer(server, token) }
                    if (problem == null) {
                        Settings.setDiagnosticsServer(server)
                        result = Secrets.storeNamed(Diagnostics.TOKEN_NAME, token)
                            ?: "Saved. Rampart can reach it."
                        // The default depends on a server now being configured, so it is
                        // re-read rather than assumed, the same as the tracking page's own
                        // check-and-save does not need to: that one has no separate toggle.
                        reporting = Settings.diagnosticsReporting()
                        worked = true
                    } else {
                        result = problem
                        worked = false
                    }
                    checking = false
                }
            },
        ) { Text(if (checking) "Checking" else "Check and save") }
        if (checking) {
            Spacer(Modifier.width(12.dp))
            Spinner(size = 20.dp, thickness = 2.dp)
        }
        if (server.isNotBlank() || token.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                server = ""
                token = ""
                reporting = false
                Settings.setDiagnosticsServer("")
                Settings.setDiagnosticsReporting(false)
                Secrets.storeNamed(Diagnostics.TOKEN_NAME, "")
                result = "Removed. Nothing will be sent."
                worked = false
            }) { Text("Remove the server") }
        }
    }
    result?.let {
        Spacer(Modifier.height(10.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (worked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

/** "182 ms", or "" for the rare event with no duration at all. */
private fun msText(value: Double?): String = if (value == null) "" else "${value.roundToInt()} ms"

/**
 * The model, if there is to be one.
 *
 * Off, and explained rather than defended. Everything about this feature is a decision
 * somebody makes on purpose: whether there is a model at all, whether the text leaves the
 * machine, what it may cost before it stops, and separately, for each feature, whether it
 * is worth it. See `docs/assistant.md` for why each of those is its own question.
 */
@Composable
private fun AssistantPage(accounts: List<AccountMailboxes>) {
    var config by remember { mutableStateOf(Assistant.config()) }
    var key by remember { mutableStateOf(Secrets.loadNamed(Assistant.KEY).orEmpty()) }
    var saved by remember { mutableStateOf<String?>(null) }

    fun change(next: AssistantConfig) {
        config = next
        Assistant.setConfig(next)
        saved = null
    }

    Section(
        "Assistant",
        "A model can summarise a long thread, draft a reply or say what a message looks " +
            "like. It is off, and every one of those is a button somebody presses: nothing " +
            "here runs on a timer, on arrival, or while you scroll.",
    )
    Text(
        "To summarise a message, its text has to be sent to whatever model you point this " +
            "at. That is the whole mechanism and it cannot be avoided, so Rampart shows you " +
            "the exact words before they go, and asks the first time you use each feature.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )

    Spacer(Modifier.height(20.dp))
    Section(
        "Folders it may never read",
        "Checked here, on any account, a folder's mail is refused before a packet for it " +
            "is ever built, whether or not the assistant is switched on. Nothing is picked " +
            "by default.",
    )
    if (accounts.isEmpty()) {
        Text(
            "No account is signed in yet, so there is nothing to list folders for.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    accounts.forEach { account ->
        if (accounts.size > 1) {
            Text(
                account.email,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
        }
        var denied by remember(account.key) { mutableStateOf(Assistant.deniedFolders(account.key)) }
        account.mailboxes.forEach { folder ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        denied = if (folder.name in denied) denied - folder.name else denied + folder.name
                        Assistant.setDeniedFolders(account.key, denied)
                    }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = folder.name in denied,
                    onCheckedChange = {
                        denied = if (it) denied + folder.name else denied - folder.name
                        Assistant.setDeniedFolders(account.key, denied)
                    },
                )
                Spacer(Modifier.width(10.dp))
                Text(folder.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Spacer(Modifier.height(16.dp))

    AssistantMode.entries.forEach { mode ->
        Row(
            Modifier.fillMaxWidth()
                .clickable {
                    change(config.copy(mode = mode))
                    // Turning it off is turning it off: an agreement given for one setup is
                    // not an agreement that survives being switched off and on again with a
                    // different provider in the box.
                    if (mode == AssistantMode.OFF) Assistant.forgetAgreements()
                }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = config.mode == mode, onClick = null)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    when (mode) {
                        AssistantMode.OFF -> "Off"
                        AssistantMode.LOCAL -> "A model on this machine"
                        AssistantMode.BYOK -> "A model somewhere else, with my own key"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    when (mode) {
                        AssistantMode.OFF -> "No model, no key, nothing sent anywhere."
                        AssistantMode.LOCAL ->
                            "Ollama or anything else that speaks the same shape. Nothing leaves this machine."
                        AssistantMode.BYOK ->
                            "Your key, your account, your bill. Rampart never sees it after you type it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }

    if (config.mode != AssistantMode.OFF) {
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = config.baseUrl,
            onValueChange = { change(config.copy(baseUrl = it)) },
            label = { Text("Where to ask") },
            placeholder = { Text("https://openrouter.ai/api/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = config.model,
            onValueChange = { change(config.copy(model = it)) },
            label = { Text("Which model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (config.mode == AssistantMode.BYOK) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; saved = null },
                label = { Text("Your key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    // The reason a save can fail is worth showing. Without a credential
                    // store the key is not kept, and the failure would otherwise turn up as
                    // "why does it ask me again every morning".
                    saved = Secrets.storeNamed(Assistant.KEY, key) ?: "Kept in this machine's credential store."
                }) { Text("Save the key") }
                if (key.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        key = ""
                        Secrets.storeNamed(Assistant.KEY, "")
                        saved = "Forgotten."
                    }) { Text("Forget it") }
                }
            }
            saved?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        Spacer(Modifier.height(20.dp))
        Section(
            "What it may cost",
            "Rampart cannot know what your provider charges, so it asks you. The two prices " +
                "are per million tokens, the way every provider quotes them, and the total " +
                "below is an estimate built from them rather than a bill.",
        )
        Row(Modifier.fillMaxWidth()) {
            Dollars("Per million in", config.dollarsIn, Modifier.weight(1f)) {
                change(config.copy(dollarsIn = it))
            }
            Spacer(Modifier.width(10.dp))
            Dollars("Per million out", config.dollarsOut, Modifier.weight(1f)) {
                change(config.copy(dollarsOut = it))
            }
            Spacer(Modifier.width(10.dp))
            Dollars("Stop at, per month", config.ceiling, Modifier.weight(1f)) {
                change(config.copy(ceiling = it))
            }
        }
        Text(
            "The limit stops the feature rather than warning about it. Zero means no limit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(18.dp))
        val breakdown = Assistant.breakdown()
        Section("This month", if (breakdown.isEmpty()) "Nothing yet." else "")
        breakdown.forEach { (feature, spend) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(feature.replaceFirstChar { it.uppercase() }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${spend.calls} so far, ${spend.tokensIn + spend.tokensOut} tokens, " +
                        Assistant.money(spend.dollars),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** A money field that refuses to become something that is not a number. */
@Composable
private fun Dollars(label: String, value: Double, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == 0.0) "0" else value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            // Half-typed is not a saved value. "0." is on the way to "0.5" and writing zero
            // in the middle of that would silently turn the limit off.
            typed.toDoubleOrNull()?.let(onChange)
        },
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        prefix = { Text("$") },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun AboutPage(update: String?, onRestart: () -> Unit) {
    Section("Version", "Rampart updates itself in the background and asks before restarting.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            Updates.current?.let { "You are on $it." } ?: "Running from source.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (update != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                "$update is ready.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onRestart) { Text("Restart now") }
        }
    }

    Spacer(Modifier.height(30.dp))
    Section("What changed", "Every version, newest first.")
    Changes()
}

@Composable
internal fun Section(title: String, note: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(
        note,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
    )
}

/**
 * A theme drawn in its own colours, which is the only honest way to show one. The card is
 * the background, the chips are what sits on it, and a theme with a character shows theirs,
 * because that is most of why you would pick it.
 */
@Composable
private fun ThemeCard(theme: Theme, selected: Boolean, onPick: () -> Unit) {
    val edge = if (selected) theme.accent else theme.line
    Box(
        Modifier.height(92.dp).fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(theme.background)
            .border(if (selected) 2.dp else 1.dp, edge, RoundedCornerShape(10.dp))
            .clickable(onClick = onPick)
            .padding(12.dp),
    ) {
        theme.art?.let { file ->
            artImage(file)?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alpha = 0.5f,
                    modifier = Modifier.align(Alignment.BottomEnd).size(52.dp),
                )
            }
        }
        Column {
            Text(
                theme.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(theme.accent, theme.surface, theme.surfaceVariant, theme.selection).forEach { swatch ->
                    Box(
                        Modifier.size(14.dp).clip(CircleShape).background(swatch)
                            .border(1.dp, theme.line, CircleShape),
                    )
                }
            }
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(theme.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    RampartIcons.Check,
                    contentDescription = "In use",
                    tint = theme.onAccent,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

/**
 * The out of office reply.
 *
 * Saved on a button rather than as it is typed, which is the opposite of the signature
 * above it on purpose: half a sign-off is a cosmetic problem and half an auto-reply is one
 * that goes to everybody who writes in while it is wrong.
 */
@Composable
private fun AwayReply(current: Vacation, error: String?, onSave: (Vacation) -> Unit) {
    var draft by remember(current) { mutableStateOf(current) }
    Row(
        Modifier.fillMaxWidth().clickable { draft = draft.copy(enabled = !draft.enabled) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Send an automatic reply", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) })
    }
    if (draft.enabled) {
        Spacer(Modifier.height(10.dp))
        Labelled("Subject") {
            Entry(draft.subject.orEmpty(), "Out of office") { draft = draft.copy(subject = it.ifBlank { null }) }
        }
        Spacer(Modifier.height(8.dp))
        Labelled("Message") {
            Entry(draft.text, "Back on Monday.", lines = 4) { draft = draft.copy(text = it) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Labelled("Starts") {
                    Entry(dayOf(draft.from), "today") { draft = draft.copy(from = utcDay(it)) }
                }
            }
            Column(Modifier.weight(1f)) {
                Labelled("Ends") {
                    Entry(dayOf(draft.to), "when I turn it off") { draft = draft.copy(to = utcDay(it)) }
                }
            }
        }
        Text(
            "Dates are optional, and are written the year first: 2026-12-24.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    error?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = { onSave(draft) }, enabled = draft != current) { Text("Save") }
}

/** The date part only. The server keeps a whole timestamp; nobody wants to type one. */
private fun dayOf(utc: String?): String = utc.orEmpty().substringBefore('T')

/**
 * Midnight UTC on that day, or null for a blank box. A half typed date is passed through
 * as it stands so that [vacationProblem] is the one place that says it cannot be read,
 * rather than the box silently refusing the third keystroke.
 */
private fun utcDay(day: String): String? {
    val trimmed = day.trim()
    return if (trimmed.isEmpty()) null else "${trimmed}T00:00:00Z"
}

@Composable
private fun Labelled(label: String, content: @Composable () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(bottom = 3.dp),
    )
    content()
}

@Composable
private fun Entry(value: String, hint: String, lines: Int = 1, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height((22 * lines + 16).dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = lines == 1,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The changelog.
 *
 * Ten at a time, because the useful question is almost always "what changed since I last
 * looked" and the rest is history somebody can ask for.
 */
@Composable
private fun Changes() {
    val all = remember { changelog() }
    var showAll by remember { mutableStateOf(false) }
    if (all.isEmpty()) {
        Text(
            "This build has no changelog in it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    val shown = if (showAll) all else all.take(10)
    shown.forEach { change ->
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
                // Said once, next to the version it refers to, rather than as a legend
                // somewhere else that has to be found and understood.
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
    if (!showAll && all.size > shown.size) {
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { showAll = true }) { Text("Show all ${all.size}") }
    }
}
