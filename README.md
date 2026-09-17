# Rampart

A desktop application for [Stalwart](https://stalw.art). Your mail and your whole
server, in one native window, on Windows, Linux and macOS.

Rampart speaks [JMAP](https://jmap.io), not IMAP. That is the whole point. IMAP can
move messages and nothing else: it has no way to carry your filter rules, your
aliases, your vacation responder, your app passwords or your server's own settings.
So every IMAP client, however good, leaves nine tenths of a modern mail server
invisible and sends you to a browser for the rest.

## Install it

On Windows, paste this into PowerShell:

```powershell
irm https://github.com/thejdubb02/rampart/releases/latest/download/install.ps1 | iex
```

It trusts our signing certificate, installs Rampart and starts it. One admin prompt, the
first time on each machine, and every update after that is silent.

Do not double-click the `.msix` file. Rampart is signed with our own key rather than a
certificate from a public authority, and Windows will not install a package whose signer
it does not know yet, so App Installer opens, cannot finish, and its Cancel button does
nothing. A real code signing certificate removes that step and costs a few hundred dollars
a year, which is worth it when Rampart is something other people install.

On Linux, take the `.deb` from the
[latest release](https://github.com/thejdubb02/rampart/releases/latest).

macOS is not built yet: an unnotarised build is worse than none, and notarising needs a
paid Apple account.

## Status

Early, but in daily use. Reading, searching, writing, replying and sending work, with
several accounts at once. The account and server screens described below are not built
yet.

## What it will do

- **Mail.** Read, search, write, send, offline.
- **Your account.** App passwords, API keys, Sieve filter rules, vacation
  responder, aliases, quota.
- **Your server.** Accounts, domains, DKIM, the mail queue, certificates, TLS,
  listeners, logs. The things that currently mean opening the admin console.

Settings and admin screens are rendered from the server's own published schema
rather than hand written, so they stay correct when the server adds a field. See
[docs/architecture.md](docs/architecture.md).

## Setting it up for someone else

Rampart reads a short file listing which servers to offer, so signing in is a click and a
password rather than a conversation about JMAP. The file holds a name, a server and an
address, and cannot hold a password: Rampart reads those three fields and drops everything
else. There is a template, and a block you can hand to an assistant to fill in for you, in
[docs/connecting.md](docs/connecting.md).

## On your phone

Use [Sterna Mail](https://sternamail.org/). It is a native Android JMAP client,
free software, on F-Droid, and it is good. Rampart does not duplicate it.

## Platforms

Windows first, then Linux, then macOS. One Kotlin codebase.

## Licence

Apache 2.0. See [LICENSE](LICENSE).

Rampart builds on [jmap-mua](https://codeberg.org/iNPUTmice/jmap) by Daniel
Gultsch, Apache 2.0, and takes reference from his [Ltt.rs](https://codeberg.org/iNPUTmice/lttrs-android).
