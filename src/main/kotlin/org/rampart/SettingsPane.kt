package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    signatureFor: (String) -> String,
    onSignature: (String, String) -> Unit,
    update: String?,
    notifyOnArrival: Boolean,
    onNotifyOnArrival: (Boolean) -> Unit,
    onTheme: (Theme) -> Unit,
    onAddAccount: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val current = LocalRampartTheme.current
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

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 860.dp).fillMaxWidth()) {
                Section("Theme", "Ported from Clique, so the ones you already picked there are here.")
                // Not lazy in any useful sense: there are eighteen of these and the column
                // above already scrolls. The grid is here for the wrapping, so the height
                // has to be given, and a fixed row height times the number of rows is it.
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

                Spacer(Modifier.height(30.dp))
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

                Spacer(Modifier.height(30.dp))
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

                Spacer(Modifier.height(30.dp))
                Section("Signatures", "One per sending address, so a work reply does not go out under a personal sign-off.")
                accounts.forEach { account ->
                    var text by remember(account.email) { mutableStateOf(signatureFor(account.email)) }
                    Text(
                        account.email,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    // Built rather than borrowed: Material's outlined field has a minimum
                    // height of its own, and a 90dp box is the size a sign-off actually needs.
                    Box(
                        Modifier.fillMaxWidth().height(90.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                onSignature(account.email, it)
                            },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Spacer(Modifier.height(30.dp))
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
            }
        }
    }
}

@Composable
private fun Section(title: String, note: String) {
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
