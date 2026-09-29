package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import javax.swing.JColorChooser
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** All editing UI lives here so the settings pane only decides when the editor is open. */
@Composable
internal fun ThemeEditor(
    starting: Theme,
    existing: Boolean,
    onPreview: (Theme) -> Unit,
    onSave: (Theme) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember(starting.key) { mutableStateOf(starting.copy(art = null)) }
    var message by remember(starting.key) { mutableStateOf<String?>(null) }

    fun update(next: Theme) {
        draft = next
        onPreview(next)
    }

    Section("Theme editor", "Changes preview across Rampart immediately. Save keeps them, and Cancel restores the previous theme.")
    OutlinedTextField(
        value = draft.label,
        onValueChange = { update(draft.copy(label = it, key = customThemeKey(it))) },
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = draft.dark, onCheckedChange = { update(draft.copy(dark = it)) })
        Text("Dark theme", style = MaterialTheme.typography.bodyMedium)
    }

    val roles = listOf(
        "Background" to draft.background,
        "Surface" to draft.surface,
        "Surface variant" to draft.surfaceVariant,
        "Selection" to draft.selection,
        "Text" to draft.text,
        "Muted text" to draft.muted,
        "Line" to draft.line,
        "Accent" to draft.accent,
        "Text on accent" to draft.onAccent,
    )
    roles.forEachIndexed { index, (label, colour) ->
        ColourRole(label, colour) { chosen ->
            update(
                when (index) {
                    0 -> draft.copy(background = chosen)
                    1 -> draft.copy(surface = chosen)
                    2 -> draft.copy(surfaceVariant = chosen)
                    3 -> draft.copy(selection = chosen)
                    4 -> draft.copy(text = chosen)
                    5 -> draft.copy(muted = chosen)
                    6 -> draft.copy(line = chosen)
                    7 -> draft.copy(accent = chosen)
                    else -> draft.copy(onAccent = chosen)
                },
            )
        }
    }

    val backgroundRatio = contrastRatio(draft.text, draft.background)
    val surfaceRatio = contrastRatio(draft.text, draft.surface)
    val lowContrast = backgroundRatio < 4.5 || surfaceRatio < 4.5
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            enabled = draft.label.isNotBlank(),
            onClick = { onSave(draft.copy(label = draft.label.trim(), key = customThemeKey(draft.label))) },
        ) { Text("Save") }
        if (lowContrast) {
            Text(
                "Contrast warning: text is %.2f:1 on background and %.2f:1 on surface. Body text needs 4.5:1."
                    .format(backgroundRatio, surfaceRatio),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        TextButton(onClick = {
            runCatching { exportTheme(draft) }
                .onFailure { message = it.message ?: "The theme could not be exported." }
        }) { Text("Export") }
        if (existing) TextButton(onClick = onDelete) { Text("Delete") }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun ColourRole(label: String, colour: Color, onChange: (Color) -> Unit) {
    var value by remember(colour) { mutableStateOf(themeColourString(colour)) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).background(colour, RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .clickable {
                    chooseColour(label, colour)?.let {
                        value = themeColourString(it)
                        onChange(it)
                    }
                },
        )
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                value = next
                runCatching { parseThemeColour(next) }.getOrNull()?.let(onChange)
            },
            singleLine = true,
            modifier = Modifier.width(132.dp),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = {
            chooseColour(label, colour)?.let {
                value = themeColourString(it)
                onChange(it)
            }
        }) { Text("Pick") }
    }
}

private fun chooseColour(label: String, colour: Color): Color? {
    val initial = java.awt.Color(colour.red, colour.green, colour.blue)
    val chosen = JColorChooser.showDialog(null, "Choose $label", initial) ?: return null
    return Color(chosen.red, chosen.green, chosen.blue)
}

internal fun importTheme(): Theme? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Import theme"
        fileFilter = FileNameExtensionFilter("Rampart theme (*.json)", "json")
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val path = chooser.selectedFile.toPath()
    // A theme is a few hundred bytes. Anything far bigger is not one, and reading it whole could stall the app.
    require(Files.size(path) <= 64 * 1024) { "That file is too large to be a theme." }
    return ThemeJson.decode(Files.readString(path))
}

private fun exportTheme(theme: Theme) {
    val chooser = JFileChooser().apply {
        dialogTitle = "Export theme"
        fileFilter = FileNameExtensionFilter("Rampart theme (*.json)", "json")
        selectedFile = java.io.File(customThemeKey(theme.label).removePrefix("custom:") + ".json")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return
    val selected = chooser.selectedFile.toPath()
    val target = if (selected.fileName.toString().endsWith(".json", ignoreCase = true)) selected
    else selected.resolveSibling(selected.fileName.toString() + ".json")
    Files.writeString(target, ThemeJson.encode(theme))
}
