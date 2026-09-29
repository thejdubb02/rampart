# Settings that follow you between computers

Written 2026-09-29 with the code, card RAM-103. Not released, and not yet run against a
live server.

Rampart's own settings (the theme, custom themes, list density, saved searches, how the
reader behaves) live in `settings.json` on each computer, because JMAP has no store for a
client's settings. Stalwart's file storage does have somewhere to put a file, and a file is
all this needs. So each account's Files gets a folder named `Rampart` at the top level with
`settings.json` in it, read when the account signs in and written a few seconds after a
setting changes.

Code: `SettingsSyncMerge.kt` (the allowlist, the file format, the merge, all plain
functions), `SettingsSync.kt` (finding, reading and writing the file, and the background
thread), `SettingsSyncUi.kt` (the section on Settings, Accounts, and following the signed-in
accounts). Tests: `SettingsSyncTest.kt`. The hooks elsewhere are one line each: the write
path in `Settings.kt` and `Assistant.kt` stamps what changed, `Main.kt` redraws what
another computer changed, and `SettingsPane.kt` shows the section.

## Which class of feature this is

Class 3 in `CLAUDE.md`: it needs the account's own server to offer JMAP file storage
(`urn:ietf:params:jmap:filenode`), which Stalwart does. Everything is done as the signed-in
user over the same mail session the Files page uses. No admin token is involved, and none
could make it work anywhere it does not already.

An IMAP account, or a JMAP server without file storage, keeps the local file only, and its
line on the settings page says so in one sentence: "This account is IMAP, which has nowhere
to keep Rampart's settings. Settings stay in the file on this computer."

## What syncs, and what never does

An explicit allowlist, `SYNCED_SETTINGS` and `SYNCED_ASSISTANT` in `SettingsSyncMerge.kt`.
**A key that is not on it never leaves the machine**, including every key added to
`Settings.kt` from now on until somebody adds it to the list on purpose.

Synced, from `settings.json`: `theme`, `customThemes`, `iconPack`, `loader`, `density`,
`tintRowsByTag`, `tagColours`, `tintRowsByAccount`, `hoverActions`, `savedSearches`,
`order`, `markReadDelay`, `archiveBy`,
`messageMode`, `messageScale`, `undoSeconds`, `undoBarSeconds`, `signatureAboveQuote`,
`confirmBeforeSend`, `defaultReplyAll`, `exactIdentitiesOnly`, `subAddressDelimiter`,
`attachmentPosition`, `attachmentClickBehavior`, `trackedDomains`, `changelogSuppressed`.

Synced, from `assistant.json`: `deniedFolders`, the folders Rook must never read. A
boundary drawn on one computer is one its owner expects on the next.

Never synced, and why:

- **Secrets.** Passwords and Rook's key are in the operating system's store, not in either
  file, so they were never candidates; the allowlist makes sure of it anyway.
- **Anything about this screen or this computer:** the window, the composer's size, side
  panel widths, the collapsed sidebar and folded sections, notifications and close to tray.
- **Anything that decides where data goes:** the open tracking and diagnostics companions,
  ntfy and Gotify, and Rook's mode, model and endpoint. A file on the server must never be
  able to change where this machine sends anything. Rook's endpoint can also be a model on
  `localhost` that exists on one computer only.
- **Consent:** sending diagnostics, and each Rook feature agreed to. Agreeing on one
  computer is not agreeing on another.
- **Spending:** Rook's ledger and ceiling are about what this computer has spent.
- **Senders whose remote images load.** That widens what the hostile-input reader fetches,
  and widening it is a decision made in front of a message.
- **Machine state:** the tracking cursor, the last changelog seen, the stamps below, and the
  sync switch itself.
- **Account colours.** They are keyed by account, and this file is written to every
  account's own storage, so syncing them would put each account's address and server into
  the others. An account's colour starts as one worked out from its key, so it is the same
  on every computer until somebody changes it on one.

## The file

```json
{
  "format": 1,
  "about": "Rampart's settings, synced between computers.",
  "settings": {
    "settings.density": { "updatedAt": 1790000000000, "value": "compact" },
    "settings.theme":   { "updatedAt": 1790000500000, "reset": true },
    "assistant.deniedFolders": { "updatedAt": 1790000100000, "value": { "work": ["Legal"] } }
  }
}
```

Each setting carries its own `updatedAt`, in milliseconds. A name is prefixed with the local
file it belongs to, so the two files can never collide. Nothing else goes in: no machine
name, no host, no account.

## The merge: newest wins, per setting

Never per file. Two computers that changed different settings both keep their change,
because each setting is decided on its own.

- **Resets are entries, not absences.** Putting a setting back to its default writes
  `"reset": true` with a stamp. Were it simply dropped, the file would say nothing about it
  and the old value on the next computer would win and come back.
- **Ties** (two stamps exactly equal, which happens mainly for values set before sync
  existed, which count as stamped at zero) go to the value that sorts higher as JSON text.
  Arbitrary, but every computer makes the same arbitrary choice, so the file settles.
- **Every change is stamped where it is made**, inside the same locked write that saves it
  (`stampChanges`), so a value and its stamp are never on disk apart. Changes are stamped
  whether or not sync is on, so turning it on later still knows which is newer.

## Clock skew: the choice

Stamps are wall-clock time from the computer that made the change, with one correction: a
new stamp is never lower than one past the newest stamp that computer has already seen
(`nextStamp`). So a change made after taking in another computer's change always beats it,
even when this computer's clock is behind. That covers the case people actually notice,
"I changed it back and it did not stick".

What it does not cover is two computers changing the same setting before either has heard
of the other. Then the one with the later clock wins, whichever was really later. For
display preferences that is an acceptable loss, and the alternatives (vector clocks, asking
the person) cost more than the problem.

A stamp more than a year ahead of this computer's clock is not believed and that entry is
ignored, so one machine with its clock set years ahead cannot win every argument until then.

## Concurrent writes

One sync of one account is: find the folder and the file (one request: a query and a get for
each, filtered by name on the server and checked by exact name and place here), download the
file, merge it into the local files, and write back only if the server's copy is missing
anything.

- **The write names the state it read.** `FileNode/set` carries `ifInState` with the
  FileNode state from the read, and a new file is created with `onExists` left at its
  default, which refuses rather than renames. Losing either race (`stateMismatch`,
  `alreadyExists`, or `notFound` because the file went) means read again, merge again, and
  try again, up to four times. The FileNode state is the account's whole file tree, so an
  unrelated upload also forces a retry; that costs one extra round trip and nothing else.
- **Replacing the contents is a `blobId` update** on the existing node, not a new file, so
  the node keeps its id and nothing piles up.
- **A new folder and file go in one request**: two `FileNode/set` calls, the second naming
  the folder by its creation id (`#f`).

## A failure never costs local settings

- The merge is written into each local file inside that file's own lock, from its current
  contents, so a setting changed while the download was in flight is merged, not lost.
- If that local write fails, nothing is sent.
- Nothing from the server reaches a local file without passing its shape check
  (`SyncShape.fits`). Several accessors in `Settings.kt` call `jsonPrimitive` on what they
  find, so a value of the wrong shape would otherwise throw on every read. A bad entry is
  dropped on its own; the rest of the file still applies.
- **A corrupt file** (not JSON, no format, no settings, over 512 KiB) is ignored and replaced
  with this computer's settings, and the line on the settings page says so.
- **A file from a newer Rampart** (a higher `format`) is neither applied nor overwritten. The
  line says to update Rampart.
- Entries under names this version does not sync are carried through untouched, so an older
  Rampart writing the file does not erase a newer one's settings. They are never applied.

## When it runs

At sign-in (which is the read), four seconds after the last change on this computer (so
dragging a slider is one write), and every thirty minutes for changes made elsewhere. One
background thread, one account at a time. With more than one account, a sync that brought
something in from the second account's file runs once more so the first account's file gets
it too. What another computer changed is redrawn at once for the settings the window holds
in memory (theme, icons, loader, density, tag colours and tinting, tinting by account, hover
buttons, undo strip, sort order,
message page and size, saved searches).

## The settings page

Settings, Accounts, "Settings on your other computers": a switch, "Sync settings through the
server", which is for this computer only and is itself never synced, and one line per
signed-in account: "Synced with the server at 3:42 PM.", "Syncing with the server now.",
"Sync is off on this computer, so settings stay in the file here.", or why not, as a
`FaultText` with the reason under it.

## To check against a live server

None of this has run against Stalwart yet. In order:

1. That `FileNode/query` honours `isTopLevel` and `name` together, and that `name` is an
   exact match (the answer is checked here either way, so a looser match only costs size).
2. That `FileNode/set` honours `ifInState` and answers a stale one with a `stateMismatch`
   method error, which `Jmap.call` turns into a `JmapError` naming it.
3. That a `blobId` update replaces a file's contents in place (`docs/files.md` says it is
   updatable; Rampart has not done it before).
4. That `parentId: "#f"` resolves across two `FileNode/set` calls in one request.
5. That an upload of `settings.json` from a temporary file is typed `application/json`, or
   at least accepted.
6. Two computers, two servers, as `CLAUDE.md` asks: change the theme on one, sign in on the
   other, and see it arrive; reset it on the second and see the first follow.
