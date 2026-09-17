# Parity with Bulwark

Read from Bulwark 1.9.2 (`FEATURES.md` plus its `app/`, `lib/` and `components/` trees)
on 2026-09-17, against Rampart at 0.1.50.

Bulwark is the webmail we already run on the same Stalwart. It is the yardstick because
it is what the mail looks like today, not because Rampart should become a copy of it.
Some of what is below we will never build, and that is written down too, with the reason,
so nobody re-opens it every six months.

Three columns of judgement are used throughout:

- **Have** - working in Rampart today.
- **Need** - a real gap. Somebody will hit it.
- **Not doing** - decided against, with the reason.

---

## 1. What Rampart already has

Worth stating plainly, because the list below is long and it is easy to read it as
"nothing works yet".

Read, compose, reply, reply-all and forward. Threaded conversations. A unified inbox
across accounts. Drafts that save themselves. Attachments up and down, with the pictures
a message carries drawn where the sender put them. Multi-select with batch archive,
delete, spam and mark-read, all undoable, and single messages undoable too. Starring.
Tags as JMAP keywords, so they are the same tags the webmail shows. Search. Keyboard
shortcuts with the list on `?`. A vacation responder. View source and save as `.eml`.
One-click unsubscribe. SPF, DKIM and DMARC, shown only when it is worth saying. Remote
images blocked and allowed per sender. Push over the JMAP WebSocket. HTML signatures
held on the server, shared with the webmail. Eighteen themes. Self-updating.

---

## 2. The gaps that matter most

In the order they will be missed.

### 2.1 A rich-text composer

**Need.** Rampart composes plain text. It appends an HTML signature, so a sent message
does have an HTML part, but there is no way to make a word bold in the body, no inline
image, no list, no table. Bulwark has a full editor.

This is the largest single gap and the most visible one. The reader now renders blocks;
the composer has to produce them. The safe shape is the one the signature editor already
uses: edit something simple, show a live preview drawn by the same renderer as received
mail, and never round-trip somebody else's HTML through an editor that will mangle it.

### 2.2 Filters and rules

**Need.** Stalwart advertises `urn:ietf:params:jmap:sieve` ("Stalwart v1.0.0"), so this
is available to us. Bulwark has a visual builder over a raw editor, and rules written in
other clients survive the round trip.

Rules are how a mailbox stays usable without anyone tending it, and Justin's runs on
inbound leads. This is the gap with the most leverage.

### 2.3 IMAP, alongside JMAP

**Need, and now a stated requirement.** Rampart speaks JMAP only, which means it works
with Stalwart and almost nothing else. To be a mail client somebody else can use it has
to speak IMAP too.

Two things follow that are easy to get wrong:

- **Folder roles.** IMAP has no JMAP `role`. It has SPECIAL-USE flags (RFC 6154):
  `\Archive`, `\Drafts`, `\Junk`, `\Sent`, `\Trash`, `\All`. The name fallback in
  `Folders.kt` already covers a server that sets neither, and SPECIAL-USE slots in
  as a third source ahead of the name. The rule stays the same: the server's own
  declaration wins, the name is a last resort, and a folder already claimed for
  something else is never taken by name.
- **What IMAP cannot do.** No threading (needs client-side `References` walking or the
  THREAD extension), no keywords on every server, no push without IDLE, no blob upload,
  no Sieve without ManageSieve on a separate port. Each of those is a feature that has
  to degrade rather than break. The poll under the push already exists for exactly this
  reason and must not be removed.

### 2.4 Calendar, contacts and files

**Need, eventually. Not next.** Bulwark has all three, and Stalwart serves all three
over JMAP. They are three more applications, not three more features.

The honest position: Rampart is a mail client until mail is finished. Contacts is the
one that leaks into mail, through recipient autocomplete, so a read-only contacts fetch
for autocomplete is worth doing long before a contacts application is.

### 2.5 Folder management

**Need.** Rampart lists folders and cannot create, rename, move or delete one. Bulwark
nests them, gives them icons and shows counts.

### 2.6 Templates, read receipts, scheduled send

- **Templates: need.** With placeholder fill, they are most of what a reply-drafting
  feature would do, without an LLM anywhere near it.
- **Read receipts (MDN, RFC 8098): need, both directions.**
- **Scheduled send: cannot, on this server.** Stalwart advertises
  `urn:ietf:params:jmap:submission` with no `maxDelayedSend`, which per RFC 8621 means
  zero, so a future `sendAt` is refused. What we can build is an undo-send window held
  in the client, which is the half people actually use.

### 2.7 The message list

**Need, in small pieces.** Sort order beyond newest-first. Archive by year or month.
Configurable mark-as-read delay. Right-click menus. Hover actions and quick reply.
Virtual scrolling for a large mailbox. A "you said attached and did not attach" warning.
Confirming a send with no subject. None of these is hard; together they are most of what
makes a client feel finished.

### 2.8 Tags

**Have the substance, need the trimmings.** Tags work and share keywords with Bulwark.
Missing: choosing a colour rather than deriving one, nesting, drag a message onto a tag,
and per-tag visibility rules.

### 2.9 Identities and sending

**Partly have.** Multiple identities with their own signatures work. Missing: Reply-To,
overriding the From header, sub-addressing (`user+tag@domain`), and the choice of
signature above or below the quoted text.

### 2.10 Encryption

**Need, low.** Bulwark does S/MIME sign, encrypt, decrypt and verify, plus PGP public
keys and Stalwart's encryption at rest. Nothing in Rampart.

Worth saying: this is the one area where a half-built implementation is worse than
none. A client that says a message is signed when it has not really checked is a lie
with consequences.

### 2.11 Accounts beyond your own

**Need, low for now.** Shared folders, group and delegated accounts. Relevant the day
somebody other than Justin uses this.

---

## 3. Not doing, and why

- **Internationalization (27 languages).** Not until there is a second user who needs
  one. The strings are written in plain English on purpose and a translation layer over
  them now is work with no reader.
- **A plugin system, an extension marketplace, uploadable themes.** Bulwark is a product
  with an ecosystem. Rampart is a mail client. Every plugin hook is an API we have to
  keep, and a plugin that can read the email body is a security boundary we would then
  own. The MCP server in `assistant.md` is the extension story instead: one boundary,
  outside the process.
- **Progressive Web App, service workers, web push, permalinks.** Rampart is a desktop
  application. These are answers to problems a web page has.
- **Demo mode on fixture data.** The screenshot tests already render every screen from
  fixtures, which is the part that was actually useful.
- **Anonymous telemetry.** No.
- **An embedded browser engine for HTML mail.** Settled in `architecture.md` and not
  reopened by this comparison. No script engine in the process is the single largest
  security decision in this application, and a newsletter that looks plain is the price.

---

## 4. Order of work

1. Rich-text composer.
2. Sieve filters and the rule builder.
3. Folder management.
4. The message-list trimmings in 2.7, as one pass.
5. IMAP as a second backend.
6. Templates, read receipts, undo-send.
7. Contacts, read-only, for recipient autocomplete.
8. The assistant work in `assistant.md`.
9. Calendar, contacts and files as applications.
10. Encryption.

IMAP sits at 5 rather than 1 because every feature above it has to be written once
against an interface that IMAP can also satisfy, and writing them JMAP-first with that
in mind costs nothing. Writing IMAP first and then the features costs twice.
