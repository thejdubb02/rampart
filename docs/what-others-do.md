# What the others do, and which of it Rampart should

Started 2026-09-19. Two clients are worth measuring against and they are worth measuring
against for opposite reasons.

- **Bulwark** is the webmail we use every day, so it is the bar for "does this feel
  finished". `parity.md` already tracks it feature by feature.
- **Thunderbird** is the free desktop client with twenty years of accumulated behaviour, so
  it is the bar for "is anything obviously missing from a desktop mail client".

**This is not a list of things to copy.** Both of them carry decisions that were right for
their shape and are wrong for ours, and a feature list adopted wholesale is how an
application ends up with three ways to do everything and no opinion about any of it. Each
entry below says build, do not build, or already have, and the ones that say do not build
say why, because that is the half that is easy to lose.

---

## 1. The plugin marketplace, and why Rampart should not have one

Bulwark ships a plugin marketplace. The plugins on it are real and useful: PGP true
end-to-end, OpenPGP, S/MIME, a spam score panel, Libravatar avatars, a calendar agenda in
the sidebar. Each declares permissions (2 to 20 of them), has a download count, and
installs in one click.

**The recommendation is to build the modules and not the marketplace.** Three reasons, and
the first one is the whole argument.

**A plugin in a web app is a script in a page. A plugin in a desktop app is code in your
process.** Bulwark is a web application, so a plugin is JavaScript running in a page
Bulwark already controls, in a browser that has spent twenty years learning how to contain
JavaScript. Rampart is a JVM application. A plugin here is a jar, loaded into the same
process as the mailbox, with the same access to the filesystem, the network and the
credential store as Rampart itself, unless we build a sandbox. Building that sandbox
means a separate process, a declared API, a permission model, a signing story and a review
process, and it means every one of those has to hold on the day somebody publishes a
plugin that wants to read mail. For a mail client that is not a feature, it is a second
product.

**Every one of those plugins is something a mail client should have.** PGP, S/MIME and a
spam score are not extensions, they are mail. Encryption is already the last parity gap on
the roadmap. Avatars from a federated source and a calendar agenda in the sidebar are two
afternoons each. A marketplace here would mostly be a place to distribute the features we
should have shipped, with an install step in front of them.

**A marketplace is a promise about an API.** The moment somebody else's plugin depends on
a function, that function is frozen, and Rampart is at version 0.1 and rearranging its
internals weekly. An API published now is a decision about the shape of an application
that does not have one yet.

### What to do instead

- **Build the modules.** Encryption first, because it is the parity gap and because it is
  the one nobody can add themselves. Then the small ones: avatars, a spam score panel, a
  calendar agenda in the sidebar.
- **Make themes user-creatable**, because a theme is data and not code. Eighteen ship
  today and they are colour values; the work is a documented file format, a folder Rampart
  reads from, and a way to share one. Nothing loaded from a theme can do anything, which
  is the whole difference from a plugin.
- **Leave the door open, do not build the door.** If a plugin system ever earns its keep,
  the shape it has to have is already known: a separate process, talking JSON over a pipe,
  with permissions declared up front and enforced on our side of the boundary. That is the
  same shape `assistant.md` specifies for the model features and for the same reason. It
  is a note, not a task.

---

## 2. Thunderbird, sorted

Measured against Release 156 and ESR 153 as of September 2026. Thunderbird is a local PIM:
mail, calendar, contacts and tasks, with the mail stored on disk and no server of its own.
That is the same shape Rampart is, which is why the list is useful and why most of it is
already either done or deliberately not.

### Already have

Multiple accounts in one window, unified inbox, threaded conversations, offline reading
and search, identities per account, filters as rules, tags, stars, archive, junk controls,
remote content held per sender, phishing and authenticity warnings, attachment handling,
templates, drafts, signatures, read receipts, HTML and plain text composing, global search
across accounts, keyboard shortcuts, dark mode, themes, contacts, calendar invitations, a
local store, and passwords in the operating system's credential store.

### Build, and roughly in this order

| What | Why it earns its place |
|---|---|
| **OpenPGP and S/MIME** | The last parity gap against Bulwark and a non-negotiable for the audience most likely to install a self-hosted mail client. Thunderbird made OpenPGP first-class and stopped needing Enigmail; that is the model. |
| **POP3** | A protocol Rampart does not speak, and the one people on old hosting still have. Small, bounded, and the kind of thing that decides whether somebody can use this at all. |
| **OAuth2, and so Gmail** | Off the IMAP bar on purpose as of 2026-09-18, but it is the single biggest widening of who can install this. |
| **Saved searches as folders** | A search that persists is how a large mailbox stays navigable. Rampart has the search and the store; this is a folder that runs a query. |
| **Quick filter bar** | Narrow the open folder by unread, starred, tagged or sender without leaving it. Small, and hit constantly. |
| **A tray icon and unread badge** | A mail client that has to be on screen to tell you anything is a mail client people close. |
| **Notification actions** | Mark read, archive, delete from the notification. Rampart already has the actions. |
| **Import and export** | Nobody adopts a mail client they cannot leave, and nobody arrives without history. |
| **Cards and table layouts** | Thunderbird's two message-list shapes. Rampart has one. Worth having when the list work is next touched. |
| **CalDAV and CardDAV** | Rampart reads contacts and invitations from the mail server. A calendar that is not the mail server's is the gap. |
| **Per-message print** | On the toolbar. The engine can already print. |

### Do not build

- **NNTP and RSS.** Thunderbird carries both because it is twenty years old. Neither is
  mail, and a feed reader in a mail client is a feed reader nobody uses in an application
  that is harder to explain.
- **Exchange and Microsoft Graph.** Thunderbird's own biggest complaint is that its
  Exchange support is mail-only and the calendar and address book are not done. That is a
  years-long project against a moving target, for an audience that is not ours: Rampart is
  built for Stalwart and for anything that speaks IMAP.
- **A plugin ecosystem.** See section 1. Thunderbird's add-ons are also its most common
  complaint: they break on every major version.
- **LDAP address completion.** Corporate directory integration, for the same audience as
  Exchange.
- **about:config.** A settings screen that lists every internal value is what an
  application builds instead of deciding. Rampart has a settings screen with opinions in it.

### The two things people dislike about Thunderbird, which are warnings

1. **Performance on large mailboxes.** Freezes during sync, high memory after days of
   uptime, a single-threaded feel. Rampart is one process with a local store and a
   heavyweight browser engine embedded in it, which is the same failure waiting to happen.
   Every list and every sync should be measured against a mailbox with tens of thousands of
   messages in it before it is called done.
2. **It looks dated.** Said about Thunderbird in every review since 2015, and it is the
   thing that sends people to Spark and Mailbird. Rampart's answer so far is eighteen
   themes and a title bar that matches them. Keep spending on that; it is not decoration,
   it is the reason somebody tries the application at all.

---

## 3. The menu crawl

Neither `parity.md` nor this file was written by going through the other application's
menus one at a time, so both are built from what we happened to notice. The gap between
"what we noticed" and "what is there" is exactly where the missing features are.

The method, when it happens: open Bulwark, walk every menu, toolbar, context menu, settings
page and keyboard shortcut, and write down every command. Then mark each one against
Rampart: have it, do not have it, deliberately do not want it. The output goes in
`parity.md`, which is already the file for it, and anything that turns out to be worth
building gets a card.

It is a crawl of the interface, not of the marketing pages, because the features that
decide whether an application feels finished are never the ones on the front page.
