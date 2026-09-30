package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * The layout in force, and the height of the message when it sits under the list.
 *
 * One object rather than a parameter threaded from Settings through the window:
 * the chooser and the panes are far apart, and both redraw the moment either
 * changes because each is snapshot state. The height is kept the way a side
 * panel's width is, on this computer, because it is about this screen.
 */
internal object MailLayoutState {
    var layout by mutableStateOf(runCatching { MailLayout.of(Settings.mailLayout()) }.getOrDefault(MailLayout.SPLIT))
        private set
    var readingHeight by mutableStateOf(
        runCatching { Settings.readingPaneHeight() }.getOrDefault(READING_DEFAULT),
    )
        private set

    fun choose(next: MailLayout) {
        layout = next
        Settings.setMailLayout(next.key)
    }

    /** Resizes as the pointer moves. Written to the file only when the drag ends. */
    fun dragReading(height: Float, available: Float?) {
        readingHeight = clampReadingHeight(height, available)
    }

    fun saveReading() = Settings.setReadingPaneHeight(readingHeight)

    /** Read again after something else wrote the file, such as a settings card from Rook. */
    fun reload() {
        layout = runCatching { MailLayout.of(Settings.mailLayout()) }.getOrDefault(MailLayout.SPLIT)
        readingHeight = runCatching { Settings.readingPaneHeight() }.getOrDefault(READING_DEFAULT)
    }
}

/**
 * The list and the message, in whichever arrangement [arrangementOf] names.
 *
 * [list] and [reading] are each called from one place per arrangement, so the
 * two panes are not copied out once per layout. [fillWidth] tells the list when
 * it should take the pane it was given instead of the fixed width the split
 * view has always used. [notice] is the undo card. It stays off the message,
 * because that pane is a native view and paints over anything drawn on top of it.
 */
@Composable
internal fun MailPanes(
    layout: MailLayout,
    messageOpen: Boolean,
    batch: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    list: @Composable (fillWidth: Boolean) -> Unit,
    reading: @Composable () -> Unit,
    notice: @Composable () -> Unit = {},
) {
    // List and message share one branch. Two branches would throw the list away
    // on the way from one to the other, and Back would open it again at the top.
    when (val arrangement = arrangementOf(layout, messageOpen, batch)) {
        PaneArrangement.SIDE_BY_SIDE -> SideBySide(modifier, list, reading, notice)
        PaneArrangement.STACKED -> Stacked(modifier, list, reading, notice)
        PaneArrangement.LIST, PaneArrangement.MESSAGE ->
            Focused(modifier, arrangement == PaneArrangement.MESSAGE, onBack, list, reading, notice)
    }
}

@Composable
private fun SideBySide(
    modifier: Modifier,
    list: @Composable (Boolean) -> Unit,
    reading: @Composable () -> Unit,
    notice: @Composable () -> Unit,
) {
    Row(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxHeight()) {
            list(false)
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) { notice() }
        }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) { reading() }
    }
}

@Composable
private fun Stacked(
    modifier: Modifier,
    list: @Composable (Boolean) -> Unit,
    reading: @Composable () -> Unit,
    notice: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var available by remember { mutableStateOf<Float?>(null) }
    Column(modifier.fillMaxSize().onSizeChanged { available = with(density) { it.height.toDp().value } }) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            list(true)
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) { notice() }
        }
        ReadingEdge(available)
        Box(
            Modifier.height(clampReadingHeight(MailLayoutState.readingHeight, available).dp).fillMaxWidth(),
        ) { reading() }
    }
}

/**
 * The focused layout: the list, or the message in its place.
 *
 * The list stays composed while the message covers it, drawn at full size with
 * nothing to see, so its scroll is the same one Back returns to. Taking it out
 * of the tree and putting it back would open the folder at the top.
 */
@Composable
private fun Focused(
    modifier: Modifier,
    messageOpen: Boolean,
    onBack: () -> Unit,
    list: @Composable (Boolean) -> Unit,
    reading: @Composable () -> Unit,
    notice: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (messageOpen) 0f else 1f }) {
            list(true)
        }
        if (messageOpen) {
            Column(Modifier.fillMaxSize()) {
                FocusedBackBar(onBack)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { notice() }
                Box(Modifier.weight(1f).fillMaxWidth()) { reading() }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { notice() }
        }
    }
}

@Composable
private fun FocusedBackBar(onBack: () -> Unit) {
    TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 8.dp)) {
        Icon(
            RampartIcons.Back,
            contentDescription = "Back to the list",
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("Back")
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The strip between the list and the message, dragged to give one of them more room. */
@Composable
private fun ReadingEdge(available: Float?) {
    val room by rememberUpdatedState(available)
    Box(
        Modifier.fillMaxWidth().height(6.dp)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { MailLayoutState.saveReading() },
                    onDragCancel = { MailLayoutState.saveReading() },
                ) { change, drag ->
                    change.consume()
                    // The message sits below the strip, so a pointer moving up, a negative
                    // step, is what gives it more of the window.
                    val drawn = clampReadingHeight(MailLayoutState.readingHeight, room)
                    MailLayoutState.dragReading(drawn - drag.y.toDp().value, room)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The layout choice for the Appearance page, with a wireframe of each arrangement. */
@Composable
internal fun MailLayoutChooser() {
    val current = MailLayoutState.layout
    MailLayout.entries.forEach { option ->
        Row(
            Modifier.fillMaxWidth().clickable { MailLayoutState.choose(option) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option == current, onClick = { MailLayoutState.choose(option) })
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    option.about,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.width(12.dp))
            MailLayoutPreview(option)
        }
    }
}

/** A small wireframe: a sidebar strip, list rows, and the reading pane's lines. */
@Composable
private fun MailLayoutPreview(layout: MailLayout) {
    val line = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)
    val bar = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier.size(88.dp, 54.dp)
            .border(1.dp, bar, RoundedCornerShape(3.dp))
            .padding(4.dp),
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(8.dp).fillMaxHeight().background(bar, RoundedCornerShape(1.dp)))
            Spacer(Modifier.width(4.dp))
            when (layout) {
                MailLayout.SPLIT -> {
                    WireLines(Modifier.width(26.dp).fillMaxHeight(), line, 4)
                    Spacer(Modifier.width(3.dp))
                    Box(Modifier.width(1.dp).fillMaxHeight().background(bar))
                    Spacer(Modifier.width(3.dp))
                    WireLines(Modifier.weight(1f).fillMaxHeight(), line, 5)
                }
                MailLayout.FOCUSED -> WireLines(Modifier.weight(1f).fillMaxHeight(), line, 4)
                MailLayout.BOTTOM -> Column(Modifier.weight(1f).fillMaxHeight()) {
                    WireLines(Modifier.weight(1f).fillMaxWidth(), line, 2)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(bar))
                    Spacer(Modifier.height(3.dp))
                    WireLines(Modifier.weight(1f).fillMaxWidth(), line, 2)
                }
            }
        }
    }
}

@Composable
private fun WireLines(modifier: Modifier, color: Color, count: Int) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(color, RoundedCornerShape(1.dp)))
        }
    }
}
