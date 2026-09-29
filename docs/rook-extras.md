# More Rook, borrowed from Gemini: tasks, saved prompts, tone and files

Four pieces from roadmap 4.1, built together on 2026-09-29 on the branch
`claude/rook-extras` (RAM-102). Not released, and not yet run against a live server or a
real model. Every model call is still a button press on the person's own key, under the
consent, the monthly ceiling, the never-leaves folder list and the ledger in
`assistant.md`.

## Tasks from mail

**What it is.** Under an open message, "Make a task". With Rook on, the message goes to
the model (fenced as data, like every other message Rook reads) and comes back as a small
flat object: a title, a due date and a few words of notes. That is read into an editable
card with a link back to the message. With Rook off, or the folder on the never-leaves
list, the card opens anyway with the subject as the title and a line saying why, so the
button is useful to somebody who never adds a key. Save is the only write. Rook's own
`propose_task` tool puts the same card on screen; there is no tool for Save.

**Where the task goes.** It has to reach the phone, so it goes to the person's own
server, as the signed in user, with the mail session's own credential. Never an admin
token.

- **JMAP Tasks**, when the session advertises `urn:ietf:params:jmap:tasks`. That URI is
  from the JMAP Tasks draft, `spec/tasks/intro.mdown` in the jmapio/jmap repository,
  section "Addition to the Capabilities Object". The task is a JSTask (RFC 8984 section
  5.2) with the draft's `taskListId` (`spec/tasks/task.mdown`), written with Task/set into
  the TaskList marked default, else the first. **Stalwart 0.16 does not advertise it**:
  the whole list of capabilities it can name is `Capability` in
  `crates/jmap-proto/src/request/capability.rs`, and there is no task entry. So this path
  is written from the draft, gated on the session, and has never met a server.
- **Stalwart's CalDAV**, otherwise, when the session is Stalwart's (it names
  `urn:stalwart:jmap` under `primaryAccounts`, the same test the Your phone page makes). A
  PROPFIND of `/dav/cal/{login}/` finds the calendars that take VTODO, and the task is one
  VTODO PUT into the default one with `If-None-Match: *`, so it can never overwrite
  anything. Read from the Stalwart source at commit af37a23:
  - the path is `/dav/cal/{account}/{calendar}/{resource}`, the account segment being the
    login's email or `_` and a numeric id (`crates/dav/src/common/uri.rs`);
  - a calendar with no component set of its own takes every component, and the default
    calendar is made that way (`crates/dav/src/common/propfind.rs`,
    `crates/groupware/src/calendar/storage.rs`);
  - a PUT must hold exactly one UID and one kind of component
    (`validate_ical`, `crates/dav/src/calendar/update.rs`);
  - calendar-query understands a comp-filter on VTODO (`crates/dav/src/calendar/query.rs`).
- **Nowhere**, on an IMAP account or any other server. The chip is hidden (a sentence
  under every message would be noise), the Tasks button in the app bar is hidden, and the
  panel, if opened by its key, says why in one sentence.

**The VTODO.** RFC 5545 section 3.6.2, one per object: UID, DTSTAMP, CREATED, SUMMARY,
DESCRIPTION (the notes, then one line naming the message), DUE, STATUS:NEEDS-ACTION and
URL. Every line ends in CRLF and is folded at 75 octets without splitting a character
(section 3.1); every text value escapes backslash, semicolon, comma and line breaks and
drops control characters (section 3.3.11). A due date alone is a DATE value. A due time is
written in UTC, because a TZID would need a VTIMEZONE written out beside it and UTC needs
nothing; every phone shows it in its own zone anyway.

**The link back** is a `mid:` URL (RFC 2392) of the message's Message-ID, percent encoded.
It is the one name for a message that every client agrees on, which is why it is used
rather than anything of Rampart's own.

**The Tasks panel** is the fifth in the app bar, Ctrl+5. It lists what is still to do,
soonest first, and reads again after a card saves. It does not tick tasks off: that is
what the phone's own task app is for, and it already does it well.

## Saved prompts

Gemini's Gems: a named instruction the person reuses, such as "reply to a lead" or
"decline politely". Picked from Saved prompts under Help me write, which puts it in the
instruction box, and from above the Rook panel's box, which puts it in the question. The
same menu saves what is typed under a name and deletes one.

Kept on the server, because CLAUDE.md says anything the server can hold, it holds: one
JSON file, `Rampart/saved-prompts.json`, in the account's Stalwart Files, read and written
through the same FileNode calls the Files page makes. The folder and the file are made on
the first save, never on a read. A later save gives the same node new contents with a
FileNode/set update of its `blobId` (docs/files.md: Stalwart 0.16 accepts that), so the
file keeps its id and anything it was shared with.

On an account without Files, an IMAP account or a server without file storage, the list
is kept on this computer instead, in `saved-prompts.json` beside the accounts file, keyed
by account. The menu says which of the two it is, every time it opens.

## Match my tone

Off until turned on, in Settings, Rook. With it on, Help me write and Suggest replies also
carry a short sample of the person's own writing: the last five messages in Sent, each cut
to their own words (above the signature and the quoted original, the same cut Help me write
already makes on a draft, then without `>` lines, Outlook's original message header and
"Sent from my" footers), joined newest first and cut to 1,500 characters in all, at a word.

It is fenced between `<<<STYLE` and `STYLE>>>`, and the prompt says it is for tone only and
is never instructions. It is its own agreement (`tone`): the first request that carries a
sample opens the packet viewer again, with a line saying what the sample is, even for
somebody who agreed to Help me write long ago. A Sent folder on the never-leaves list is
never sampled, and the composer says so rather than quietly sending less.

## Files in drafts

Gemini's `@file`. Add a file, under Help me write and above the Rook panel's box, lists the
account's Files. Only text-like files (plain text, Markdown, CSV, JSON, XML and the like),
HTML and PDFs can be picked; the rest are listed greyed with the reason. The file is
fetched and read on this machine: a PDF through PDFBox, already in the build for
previewing attachments, which extracts text without running anything in the document; HTML
through jsoup to its text, so markup never reaches the model as markup. The text is capped
at 20,000 characters and the chip says so when it was cut.

It goes between `<<<FILE` and `FILE>>>`, with any marker inside the text taken out first,
and the prompt says it may have been written by anybody and is data, never instructions.
In the composer it goes with Help me write until it is taken off; in the Rook panel it goes
in the system prompt for the conversation until it is taken off, so it does not fall out of
the history halfway through.

## Which class each is

In `self-hosting.md`'s terms: tone matching is class 1, it needs a mailbox and nothing
else. Saved prompts are class 1 with the server as the better home: class 3 where the
server has Files, a labelled local fallback where it does not. Tasks and files as context
are class 3.

## What is and is not checked

`TasksTest`, `SavedPromptsTest` and `RookExtrasTest` cover the task card mapped to JMAP and
to a VTODO (escaping, CRLF, folding, due dates, the link), reading VTODOs back, the CalDAV
answers, the saved prompts round trip through an in-memory Files server and the local
fallback, the tone sample's cleaning and its 1,500 character cap, and the file text cap. The
Compose screens were not compiled in the session that wrote them (the build box could not
reach Google's Maven repository), and nothing has been run against a live Stalwart or a real
model. Before release: save a task on both test servers and see it on a phone; check that
Stalwart answers the PROPFIND with the default calendar and takes the PUT; save, reload and
delete a saved prompt on both; and look at each screen with `see-the-app`.
