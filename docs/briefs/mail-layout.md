Repo: Rampart, a Kotlin Compose Desktop mail client (read CLAUDE.md first). The main window arranges sidebar, message list (MessageList.kt) and reading pane (ReadingPane.kt) side by side from Main.kt; side panel widths are in Settings.sidePanelWidths. Settings.messageMode() exists: find what it controls first and reuse it if it is already a layout switch rather than adding a second one.

Add a "Mail layout" choice to Settings > Appearance with three options, each with a one-line explanation and a small drawn preview (boxes and lines in Compose, like a wireframe: sidebar strip, list rows, reading pane lines):

1. Split pane (default, today's layout): list and reading pane side by side.
2. Focused list: the list takes the full width. Opening a message replaces the list with the message full width, with a Back control (and Escape / Alt+Left) returning to the list at the same scroll position and selection. Keyboard next/previous message keeps working while a message is open.
3. Reading pane at bottom: list on top, message below, with a draggable divider whose height is remembered like the side panel widths.

Rules:
- Switching layout applies immediately without a restart and keeps the selected message.
- Rook's side panel, the composer and the calendar keep working in every layout.
- The layout decision lives in one place (one enum plus one composable that arranges the panes); do not duplicate the list or reading pane code per layout.
- New setting with a safe default in Settings.kt, added to the synced list in SettingsSyncMerge.kt.
- One test for the pure parts (reading the setting with unknown values falling back to Split pane, and the back-navigation state if it is a plain function).
Match surrounding style: KDoc explaining why, plain English. No em dashes, en dashes or the ellipsis character. Do not run gradle. Do not commit. Only edit files.
