package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * Rows coloured by account in the merged inbox, and the colours in Settings, Accounts. The
 * rules are in AccountTints.kt.
 */

/**
 * Tinting by account and each account's colour, held once for the whole window, the same
 * way [HoverChoice] is and for the same reason.
 */
internal object AccountTintState {
    var tintByAccount by mutableStateOf(runCatching { Settings.tintRowsByAccount() }.getOrDefault(false))
        private set
    var colours by mutableStateOf(runCatching { Settings.accountColours() }.getOrDefault(emptyMap()))
        private set

    fun chooseTintByAccount(on: Boolean) {
        tintByAccount = on
        Settings.setTintRowsByAccount(on)
    }

    fun setColour(account: String, colour: Long?) {
        Settings.setAccountColour(account, colour)
        colours = Settings.accountColours()
    }

    /** Read again after something else wrote the file, such as settings sync. */
    fun reload() {
        tintByAccount = Settings.tintRowsByAccount()
        colours = Settings.accountColours()
    }

    /** Each account's row colour, for [LocalAccountTints]. Empty unless it applies. */
    fun tints(accounts: List<String>): Map<String, Long> = accountTints(accounts, colours, tintByAccount)
}

/**
 * The colour each account's rows carry, by account key. Empty, which is every row
 * untinted, unless tinting by account is on and more than one account is signed in.
 */
internal val LocalAccountTints = staticCompositionLocalOf { emptyMap<String, Long>() }

/**
 * Settings, Accounts: a colour for each account, and whether the merged inbox uses them.
 *
 * The colours are shown and can be changed with one account signed in, but the switch
 * says it does nothing until there are two, rather than being hidden: a missing switch
 * reads as a feature that is not there.
 */
@Composable
internal fun AccountColoursSection(accounts: List<AccountMailboxes>) {
    Spacer(Modifier.height(18.dp))
    Section(
        "Colour by account",
        "In All inboxes, each row can carry its account's colour. A tag's colour wins where a row has both, " +
            "because a tag is about that one message.",
    )
    val keys = accounts.map { it.key }
    val several = keys.size > 1
    Row(
        Modifier.fillMaxWidth().clickable(enabled = several) { AccountTintState.chooseTintByAccount(!AccountTintState.tintByAccount) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(
            checked = AccountTintState.tintByAccount && several,
            enabled = several,
            onCheckedChange = { AccountTintState.chooseTintByAccount(it) },
        )
        Spacer(Modifier.width(12.dp))
        Text("Colour rows by account in All inboxes", style = MaterialTheme.typography.bodyMedium)
    }
    if (!several) {
        Text(
            "With one account every row would be the same colour, so this starts working when a second account is added.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    val chosen = AccountTintState.colours
    accounts.forEach { account ->
        var menu by remember(account.key) { mutableStateOf(false) }
        val colour = accountColour(account.key, chosen, keys)
        Row(
            Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Box(Modifier.size(16.dp).background(Color(colour), CircleShape))
                MenuLayer(expanded = menu, onDismissRequest = { menu = false }) {
                    Text(
                        "Colour",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp),
                    )
                    // The tag colours, six to a row, the same picker the sidebar's tags use.
                    TAG_COLOURS.chunked(6).forEach { chunk ->
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
                            chunk.forEach { option ->
                                Box(
                                    Modifier.padding(3.dp).size(20.dp)
                                        .background(Color(option), CircleShape)
                                        .clickable { menu = false; AccountTintState.setColour(account.key, option) },
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Back to the colour it started with") },
                        onClick = { menu = false; AccountTintState.setColour(account.key, null) },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(shortAccountName(account.name, account.email), style = MaterialTheme.typography.bodyMedium)
                Text(account.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
