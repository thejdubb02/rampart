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

**Have.** Tags are JMAP keywords, so they are the same tags webmail shows. They nest with
a slash and the sidebar draws the tree, inventing a level wherever one is missing, because
tagging a message `Clients/Acme` without ever making `Clients` is the normal way a branch
starts. Clicking one lists everything carrying it, across every folder, which is the point
of a tag rather than a folder. A message can be dragged onto one.

**The colour is chosen here and kept here, and that is the one place this is not parity.**
Webmail keeps its tag colours in per-user encrypted files on the server that only it can
open, so there is nothing for a second client to read, and inventing a place in the mailbox
to write them would put Rampart's furniture in somebody's mail. The colour derived from
the tag's name is still the default, and it is stable, so two clients agree until somebody
changes one.

Still missing: per-tag visibility rules.

### 2.9 Identities and sending

**Have.** Multiple identities with their own signatures, the From header chosen on the
composer, Reply-To answered rather than the From address, `user+tag@domain` recognised as
yours, and the sign-off above or below the quoted text.

### 2.10 Encryption

**Need, low.** Bulwark does S/MIME sign, encrypt, decrypt and verify, plus PGP public
keys and Stalwart's encryption at rest. Nothing in Rampart.

Worth saying: this is the one area where a half-built implementation is worse than
none. A client that says a message is signed when it has not really checked is a lie
with consequences.

### 2.10b The mailbox dashboard

**Rampart only.** Not a webmail feature at all, and the first screen here that has no
counterpart over there. Thirty days of arrivals against answers on one scale, the junk
share, how fast you reply as a median rather than a mean, who writes to you most, unread
sorted by how long it has been ignored, and the conversations whose last word is somebody
else's, oldest first.

**Every number comes out of the local copy**, so it needs no server, no endpoint, no
setting and no model, and it works on Stalwart, on Gmail, on plain IMAP and offline. It
also says on screen how many messages it counted, because it describes what has been
fetched rather than what exists on the server, and a month's figures read off a week's mail
would be a lie told with a straight face.

### 2.11 Accounts beyond your own

**Need, low for now.** Shared folders, group and delegated accounts. Relevant the day
somebody other than Justin uses this.

### 2.12 Calendar, and files

**Calendar: the half that belongs in a mail client is done.** An invitation is drawn as
the meeting rather than as a file called invite.ics: what it is, when, where, who called
it, who is coming, and Accept, Maybe or Decline. A cancellation says so and offers nothing
to accept. Somebody else's reply says who answered what. The answer goes back to the
organiser as a METHOD:REPLY calendar file carrying one attendee, which is the whole
protocol: nobody has standing to answer for anybody else.

It works against any server, IMAP included, because everything shown came in the message.
That is why it comes before a calendar view rather than after one.

The traps, each one a real invitation from a real sender: the part carries no filename and
no Content-ID, so the walk that decides what is an attachment was throwing away exactly the
part that carries the meeting; a VALARM lives inside the event and has its own SUMMARY, so
a reminder was becoming the title; Exchange writes timezone names that are not in the IANA
database; DTEND on an all-day event is the day after the last one; and an all-day date has
no timezone at all, so converting one puts a birthday on the wrong day.

**A calendar view is still a second application** and stays in tier 5 with contacts and
files. Stalwart advertises the JMAP calendar capabilities, checked against the live session
rather than assumed, so it is possible when it is wanted.

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

Items 1 to 7 of the original order shipped between 2026-09-17 and 2026-09-18. On
2026-09-18 the message body moved to a real engine, identities and sending, tags and
calendar invitations all finished, the mailbox dashboard shipped, which is the first
screen Rampart has that webmail has no answer to, and then the trust signals and snooze.
What is left, in the order it will be missed:

1. **Encryption** (2.10). S/MIME and PGP. Last on purpose, and the one place where a
   half-built implementation is worse than none.

The original note about IMAP sitting at 5 rather than 1 held: every feature above it was
written once, against what became `MailBackend`, and satisfying it took adding the word
`override` to thirty-five methods rather than rewriting any of them.
