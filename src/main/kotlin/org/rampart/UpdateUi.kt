package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

/**
 * The update, as a line in the window bottom bar rather than a card over the mail.
 *
 * Shown only when an update attempt has failed, so there are not two prompts on screen
 * at once while the icon beside the version label in the right bar is showing.
 */
@Composable
internal fun UpdateBar(state: UpdateBarState, onClick: () -> Unit) {
    if (state !is UpdateBarState.Failed) return
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                enabled = true,
                onClickLabel = "Try installing Rampart ${state.version} again",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            RampartIcons.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(state.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
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
internal fun ChangelogDialog(changes: List<Change>, onClose: (suppress: Boolean) -> Unit) {
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
internal fun stageOutcome(staged: Boolean): UpdateStageCategory = when {
    staged -> UpdateStageCategory.STAGED
    Updates.lastProblem == Updates.NOT_READY -> UpdateStageCategory.NOT_READY
    else -> UpdateStageCategory.FAILED
}
