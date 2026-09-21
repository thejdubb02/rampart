package org.rampart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The exact JSON a feature is about to post, and the two buttons that decide whether it
 * goes: send it, or cancel.
 *
 * Built from [Llm.packet] / [Llm.packetOf] by whoever opens this and handed in as text,
 * never rebuilt here. The promise this dialog makes is that what is shown is what is
 * sent, and that is only true while the two are the same string rather than two
 * renderings of the same intent.
 *
 * On the first use of a feature this dialog **is** the consent: pressing Send it also
 * calls [onAgree], the same shape [ChatPane]'s own `agreed` / `onAgree` already uses
 * rather than a second way of asking. Once [agreed] is true, a caller can still open this
 * on demand to look, without it gating the button that got them here: the request itself
 * goes through when Send it is pressed either way, and the only thing [agreed] changes is
 * whether pressing it also counts as agreeing for the first time.
 */
@Composable
internal fun PacketViewer(
    packet: String,
    agreed: Boolean,
    onSend: () -> Unit,
    onAgree: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (agreed) "What would be sent" else "Before the first time") },
        text = {
            Column {
                if (!agreed) {
                    Text(
                        "The exact request, pretty printed. Nothing goes anywhere until Send " +
                            "it is pressed, and this only asks once: the feature will not ask " +
                            "again, though this stays reachable to look at whenever you want.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // Read only and scrollable within its own bounds, the same way the filter
                // builder shows its packet: a box that grows to a point and then scrolls
                // rather than pushing Send it off the bottom of the window.
                OutlinedTextField(
                    value = packet,
                    onValueChange = {},
                    readOnly = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!agreed) onAgree()
                    onSend()
                },
            ) { Text("Send it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
