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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    onTheme: (Theme) -> Unit,
    iconPack: IconPack = LineIcons,
    onIconPack: (IconPack) -> Unit = {},
    onAddAccount: () -> Unit,
    onRestart: () -> Unit,
    /** Null while the server's filter script is still being read. */
    filters: Script?,
    filtersSupported: Boolean,
    filtersSaving: Boolean,
    filtersError: String?,
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
                        "accounts" -> AccountsPage(accounts, onAddAccount)
                        "notifications" -> NotificationsPage(notifyOnArrival, onNotifyOnArrival)
                        "reading" -> ReadingPage()
                        "filters" -> FiltersPage(
                            script = filters,
                            // Only real folders, so nobody files into one that does not exist.
                            folders = accounts.flatMap { it.mailboxes }.map { it.name }.distinct().sorted(),
                            saving = filtersSaving,
                            error = filtersError,
                            supported = filtersSupported,
                            onSave = onFilters,
                        )
                        "themes" -> ThemesPage(onTheme, iconPack, onIconPack)
                        "identities" -> IdentitiesPage(
                            identities, signatureError, onSignature, onPickSignatureImage,
                        )
                        "away" -> AwayPage(vacation, vacationError, onVacation)
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
private fun ThemesPage(onTheme: (Theme) -> Unit, iconPack: IconPack, onIconPack: (IconPack) -> Unit) {
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

}

/**
 * How long an open message waits before it counts as read.
 *
 * Offered as a few choices rather than a number box: the useful values are "at once" and
 * "long enough to pass over it", and asking somebody for milliseconds is asking them to
 * guess at something they can only judge by feel.
 */
@Composable
private fun ReadingPage() {
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
private fun NotificationsPage(notifyOnArrival: Boolean, onNotifyOnArrival: (Boolean) -> Unit) {
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
}

@Composable
private fun AccountsPage(accounts: List<AccountMailboxes>, onAddAccount: () -> Unit) {
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
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onAddAccount) { Text("Add account") }
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
