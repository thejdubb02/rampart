# Rook and settings

Written 2026-09-28 with the code, card RAM-97. Rook, the assistant panel, can find every
setting in Rampart, in the mailbox on the server and on the Stalwart server itself, say what
it is set to, and propose a change. **A change happens only when a person presses Confirm on
a card that shows it before and after.** Nothing the model writes can press that button.

"Turn on dark mode", "set my away reply until Friday" and "what is my spam threshold" are the
three questions it was built against, and each one lands somewhere different in the map below.

## The three tools

The same one-JSON-object protocol as the rest of Rook (`Chat.kt`):

| Tool | What it does |
|---|---|
| `list_settings {"search": "dark mode"}` | Every setting whose name, keywords or description has all the words, best first, 20 at most. A Rampart setting shows its value; the others are read one at a time. |
| `get_setting {"id": "rampart.theme"}` | What it is, where it is kept, what it takes, its value now, and either that Rook can propose a change or why it cannot. |
| `change_setting {"id": ..., "value": ...}` or `{"changes": [...]}` | Checks every value and, only if all of them pass, puts one card per place on screen. Writes nothing. |

Code: `RookSettings.kt` (the map, the value checks, the search), `RookComputerSettings.kt`,
`RookMailboxSettings.kt`, `RookServerSettings.kt` (the three places), `RookChanges.kt` (the
tools, the cards, the prompt), `RookSettingsLive.kt` (wiring to the running app),
`SettingChangeCard.kt` (the card). Tests: `RookSettingsTest`, `RookChangesTest`.

## The map

Every setting is an entry with an id, a name, a description, what it takes, where it is kept
and where a person finds it in the app. Three homes, because each is changed with a different
credential:

### Rampart on this computer (`rampart.*`)

Read and written through the accessors in `Settings.kt` that the Settings pages already use,
then read back, so a card never says Done over a write the settings file did not take.

| Id | Setting | Takes | In the app |
|---|---|---|---|
| `rampart.theme` | Theme | one of the eighteen theme keys, or its label | Themes |
| `rampart.icons` | Icons | `line`, `heavy` | Themes |
| `rampart.loader` | Loader | `RING`, `PULSE`, `ORBIT`, `BARS`, `WAVE` | Themes |
| `rampart.tintRowsByTag` | Tint rows by tag | on or off | Themes |
| `rampart.notifyOnArrival` | Notify when mail arrives | on or off | Notifications |
| `rampart.notifyOnOpen` | Notify when a tracked message is opened | on or off | Notifications |
| `rampart.closeToTray` | Close to the tray | on or off | Notifications |
| `rampart.order` | Sort order | `NEWEST`, `OLDEST`, `UNREAD`, `SENDER`, `SUBJECT` | Reading and archiving |
| `rampart.markReadDelay` | Mark as read | `0`, `2000`, `5000`, `-1` (never) | Reading and archiving |
| `rampart.messageMode` | The page a message is drawn on | `""` (as the window), `light`, `dark` | Reading and archiving |
| `rampart.messageScale` | Message text size | `0.9`, `1.0`, `1.15`, `1.3` | Reading and archiving |
| `rampart.undoSeconds` | Taking a send back | `0`, `5`, `10`, `30` | Reading and archiving |
| `rampart.undoBarSeconds` | How long the undo strip stays | `5`, `8`, `15`, `0` (until dismissed) | Reading and archiving |
| `rampart.signatureAboveQuote` | Sign-off above the quoted message | on or off | Reading and archiving |
| `rampart.confirmBeforeSend` | Always confirm before sending | on or off | Reading and archiving |
| `rampart.defaultReplyAll` | Default to Reply all | on or off | Reading and archiving |
| `rampart.exactIdentitiesOnly` | Only use configured identities | on or off | Reading and archiving |
| `rampart.subAddressDelimiter` | Sub-address character | one character, not `@` or a space | Reading and archiving |
| `rampart.attachmentPosition` | Where files are listed | `below`, `beside` | Reading and archiving |
| `rampart.attachmentClick` | Clicking a file | `download`, `preview` | Reading and archiving |
| `rampart.archiveBy` | Archiving | `""`, `year`, `month` | Reading and archiving |
| `rampart.diagnosticsReporting` | Send diagnostics | on or off | Diagnostics |
| `rampart.changelogSuppressed` | Hide what changed after an update | on or off | About |
| `rampart.trackingServer` | Open tracking companion | read only | Open tracking |
| `rampart.diagnosticsServer` | Diagnostics companion | read only | Diagnostics |
| `rampart.assistant` | Rook's model and budget | read only | Rook |
| `rampart.accounts` | Accounts | read only | Accounts |
| `rampart.adminSignIn` | Server admin sign-in (saved or not, never the host or user) | read only | Server admin |

There is no dark mode switch: a dark theme is dark mode, and the theme's own description says
so, which is what lets "turn on dark mode" find `rampart.theme` and pick a dark one.

**Read only on purpose:** anything that decides where Rampart sends data (the two companion
addresses, Rook's own model and provider), Rook's own budget and folder deny list, and
anything behind a password. A message Rook has read can try to talk it into proposing a
change; these are the changes where one careless Confirm would do real harm, so they are not
on offer at all.

### Your mailbox on the server (`mailbox.*`)

Through the person's own mail session, the same calls the Settings pages make. Never the
admin credential: a mail feature does not get admin rights (CLAUDE.md).

| Id | Setting | Takes |
|---|---|---|
| `mailbox.away.enabled` | Away reply | on or off |
| `mailbox.away.subject` | Away reply subject | one line, 200 characters |
| `mailbox.away.message` | Away reply message | text, 4000 characters |
| `mailbox.away.from` | Away reply starts | a day, `2026-10-02`, or none |
| `mailbox.away.until` | Away reply ends | a day, or none. The first day it stops: through Friday means Saturday, as on the Away reply page |
| `mailbox.signature.<identity id>` | Signature for an address | plain text, 1500 characters. Written to both halves, so it replaces a formatted signature |
| `mailbox.filters` | Filters | read only: the rule names, and which are off |
| `mailbox.security` | Security | read only: two-step on or off, number of app passwords |
| `mailbox.phone` | Your phone | read only: whether a phone can be set up |

Away reply changes go on one card and are written in one `VacationResponse/set`, checked with
`vacationProblem` before the card is made, so "turn it on until Friday" with no message is
refused with the reason rather than failing after Confirm. On an IMAP account every away
field answers with the sentence that there is no away reply to change.

### The Stalwart server (`server.*`)

Taken from the server's own `/api/schema` through the admin console's connection
(`AdminConsole.connection`). Without a saved admin sign-in the map holds one entry saying
where to add one.

- **Every field of every object the schema describes is in the map**, as
  `server.<Object>.<field>`, with the server's own description and, for a choice, its labels.
  That is what answers "what is my spam threshold" with the right field and what it means,
  even where Rampart cannot show the value.
- **Reading and changing are no wider than the console.** Only objects in
  `AdminWritableObjects` (today Domain and Account) are ever read or written; everything else
  is described and pointed at the server's own web console. Reads need `Query` and `Get` in
  `/api/account`, changes need `Update`, the field must be mutable, and every request still
  goes through `AdminClient`, so `adminMethodRefusal` checks it as it checks the console's.
- **A field is kept per domain or per account**, so a change names one:
  `server.Domain.catchAllAddress@<id>`. `get_setting` on the bare id lists each with its value
  and the id to use.
- Values are checked with the console's own `parseInput`. Secrets, nested objects, sets and
  lists are refused: the console edits those.
- A save sends only the fields that changed, through the console's `changedFields`.

## The card

`ChangeDesk` in `RookChanges.kt` is a plain value with four moves, and the tests pin each:

1. `change_setting` checks every value against the map (type, allowed values, length, a real
   day, the place's own checks). One bad value and no card is made; the model is told why.
2. The card waits. **Confirm** moves it to applying, once. **Leave it** dismisses it for good.
   At most four cards wait at once, and at most eight settings go in one request.
3. On Confirm, `applyCard` looks every line up again, checks the refusal and the value again,
   and reads each setting again. If any no longer reads what the card said it was, nothing is
   written and the card says so: a change made elsewhere in the meantime is not overwritten.
4. The outcome goes on the card and into the transcript, so Rook knows on its next turn.

Clearing the conversation clears the cards, except one being written.

## What to check against a live server

- An away reply turned on and off from the card, on both test servers, and read back on the
  Away reply page.
- A signature changed from the card and seen in Bulwark.
- A domain's catch-all changed from the card with an admin sign-in that has `sysDomainUpdate`,
  and refused with one that does not.
- Whether real Stalwart schemas name fields that should be refused and are not.
