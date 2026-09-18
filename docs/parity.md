# Parity with Bulwark

Read from Bulwark 1.9.2 (`FEATURES.md` plus its `app/`, `lib/` and `components/` trees)
on 2026-09-17, against Rampart at 0.1.50. **Reviewed against Rampart 0.1.104 on
2026-09-18**, by which point everything in the original order of work through item 7 had
shipped. What follows says which, and what is genuinely left.

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

Read, compose, reply, reply-all and forward, answering as the address the message was
sent to. Threaded conversations. A unified inbox across accounts. Drafts that save
themselves. Attachments up and down, with the pictures a message carries drawn where the
sender put them. Multi-select with batch archive, delete, spam, not-spam and mark-read,
all undoable, and single messages undoable too. Starring. Tags as JMAP keywords, so they
are the same tags the webmail shows. Search. Keyboard shortcuts with the list on `?`. A
vacation responder. View source and save as `.eml`. One-click unsubscribe. SPF, DKIM and
DMARC, shown only when it is worth saying, with the full working behind Show details.
Remote images blocked and allowed per sender. Push over the JMAP WebSocket. HTML
signatures held on the server, shared with the webmail. Eighteen themes. Self-updating.

Added since, through 2026-09-18: a rich-text composer. Sieve filters in Bulwark's own
format, so a rule made in either opens in the other. A local store, encrypted, with
offline reading and search. Folder management with a real tree. The message-list pass:
right-click menus, hover actions, five sort orders, a mark-as-read delay, paging. A
command palette. Recipient autocomplete from your own mail. Templates, read receipts and
undo-send. Contacts from the server's own address book. Sender marks and contact photos.
The message header with the routing, authentication and identifiers behind it. Reply-To
answered rather than the From address, and a `+tag` address recognised as yours. A choice
of where the sign-off sits. **And the message body drawn by a real engine**, which is the
reversal recorded in `architecture.md` and the end of the run of hand-drawn HTML work
listed in the paragraph this one replaced.

**And a second backend.** IMAP and SMTP, with the server found from the email address
alone, so Rampart is no longer a client for one server. Everything IMAP cannot do is
absent with a reason rather than broken.

---

## 2. The gaps that matter most

In the order they will be missed.

### 2.1 to 2.7, all shipped

Built between 2026-09-17 and 2026-09-18. Kept here as a list rather than deleted, because
the reasoning behind several of them is still the reason they are the shape they are, and
that lives in `roadmap.md` under the matching section number.

- **2.1 Rich-text composer.** Have. Markers in the buffer, real HTML on send.
- **2.2 Filters and rules.** Have. Bulwark's own JSON-in-a-comment format, read and
  written, so neither client destroys the other's rules. Anything a builder did not write
  is kept verbatim.
- **2.3 IMAP alongside JMAP.** Have. Reading, writing, sending, folders, attachments,
  search, conversations through THREAD, and IDLE for push. Discovery from the address.
  OAuth2 is deliberately not being done, so Gmail and Microsoft are out until somebody
  wants them enough to register us as an application with both.
- **2.4 Calendar, contacts and files.** Contacts have. Calendar and files still open, and
  still three applications rather than three features. See 2.12 below.
- **2.5 Folder management.** Have. Create, rename, nest, move, delete, with the tree shown
  and a role-bearing folder protected from both.
- **2.6 Templates, read receipts, scheduled send.** Templates and receipts have.
  Undo-send have. Scheduled send remains impossible on this server: Stalwart advertises
  submission with no `maxDelayedSend`, which per RFC 8621 means zero.
- **2.7 The message list.** Have, all of it, plus paging and sender marks.

---

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

### 2.12 Calendar, and files

**Calendar: need, and the useful half is small.** Stalwart advertises the JMAP calendar
capabilities, checked against the live session rather than assumed, so a calendar view is
possible. It is also a second application. The half that belongs in a mail client is the
invitation: a `text/calendar` part drawn as who, when and where with Accept, Tentative and
Decline, and an RSVP going back to the organiser. That works against any server and
against IMAP, which has no calendar at all.

**Files: need, low.** Bulwark has it, Stalwart serves it, and nothing about mail wants it
yet.

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
- **Anything that needs its own login.** Tinyauth or the mailbox, nothing else.

---

## 4. Order of work

Items 1 to 7 of the original order shipped between 2026-09-17 and 2026-09-18, and
identities and sending finished on 2026-09-18 with Reply-To, sub-addressing and the
sign-off position. What is left, in the order it will be missed:

1. **Tags, finished** (2.8). A colour you choose rather than one derived from the name,
   nesting, and dragging a message onto a tag. Bulwark keeps its tag colours in per-user
   files on the server rather than in the mailbox, so matching it means reading those,
   and the result is the same colours in both clients.
2. **Calendar invitations** (2.12). A `text/calendar` part drawn as an event with Accept,
   Tentative and Decline. Works against any server and against IMAP, and it is the part of
   a calendar that actually happens inside a mail client.
3. **The mailbox dashboard.** Not a Bulwark feature at all, and the first place Rampart
   goes past it. Every number comes out of the local store, so it needs no server, no
   setting and no model.
4. **Encryption** (2.10). S/MIME and PGP. Last on purpose, and the one place where a
   half-built implementation is worse than none.

The original note about IMAP sitting at 5 rather than 1 held: every feature above it was
written once, against what became `MailBackend`, and satisfying it took adding the word
`override` to thirty-five methods rather than rewriting any of them.
