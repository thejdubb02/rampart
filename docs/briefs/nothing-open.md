# Brief: something useful in the reading pane when no message is open

Today the reading pane with nothing selected is a blank page that says "Pick a message."
(ReadingPane.kt, the `summary == null` branch near line 530). On a wide window that is a
huge empty screen. Replace it with a calm "at a glance" page.

## What it shows, top to bottom

1. Today's date as a heading, e.g. "Thursday, 1 October", with a short line under it:
   "N unread in Inbox" (or "Inbox is read" at zero). Use the unread count the window
   already has for the current account or merged inbox; do not add a new query if one exists.
2. "Waiting on you": up to five conversations from `MailStats.waiting` (Dashboard.kt), each
   a clickable row (sender, subject) that opens the message exactly like DashboardPane's
   `onOpen` does. Hide the section when the list is empty or stats are null; never show
   "Counting." forever.
3. "Still today": what is left of today's calendar, reusing whatever AgendaPanel (Main.kt
   ~5831, Agenda.kt) already feeds itself with. Time and title per line. Hide the section
   when there is no calendar or nothing left today.
4. A row of buttons: "Write" (opens a new message the same way the existing compose
   button does), "Today, from Rook" (same as DashboardPane's onToday; hidden when the
   assistant is off), "Dashboard" (opens the dashboard).

Keep `ThemeArt` drawn at the bottom end as now. Use MaterialTheme typography and colours
only, matching DashboardPane's look (Heading/Empty helpers there can be reused or shared).
Content is a scrollable Column, max width about 560dp, left-aligned with generous padding,
not centred in the middle of the screen.

## Wiring

- ReadingPane gains one optional parameter, `nothingOpen: (@Composable () -> Unit)? = null`.
  When `summary == null` and it is set, draw it; otherwise keep "Pick a message." so every
  other caller is unchanged.
- The new composable lives in a new file `NothingOpen.kt`, taking plain data and callbacks
  (no Settings reads, no store access inside it).
- Main.kt: the stats LaunchedEffect (~line 2190) currently runs only when `dashboardOpen`.
  Let it also run when no message is selected, so "Waiting on you" has data. Same keys,
  same cheap local queries.
- Only the main reading pane passes `nothingOpen`. Pop-out windows and previews do not.

## Rules

- Rampart is public and built for strangers: no names, hosts or addresses in code.
- Everything must work offline and with no local copy (stats null): the page still shows
  the date, buttons and calendar.
- No new dependencies. No gradle runs. Do not commit.
- Add a small test for any pure helper you write (for example the date heading or the
  unread line wording) in src/test/kotlin/org/rampart/.
- At the end, list every file you changed.
