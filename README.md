# Rampart

A mail client for people running their own server.

Rampart speaks [JMAP](https://jmap.io), not IMAP. That is the whole point. IMAP can
move messages and nothing else: it has no way to carry your filter rules, your
aliases, your vacation responder, your app passwords or your server's own settings.
So every IMAP client, however good, leaves nine tenths of a modern mail server
invisible and makes you open a web console for the rest.

Rampart is built for [Stalwart](https://stalw.art) and [Bulwark](https://github.com/bulwarkmail),
and works with any JMAP server (Fastmail, Cyrus, Apache James).

## Status

Early. Nothing to install yet.

## What it will do

- **Mail**, from the JMAP engine, with push that does not need Google.
- **Your account settings**, on the phone: password, app passwords, API keys,
  Sieve filter rules, vacation responder, masked addresses, spam training.
- **Your server**, if you administer one: accounts, domains, DKIM, queues, certificates.
- **The same app on the desktop**, Windows first, then Linux and macOS.

Settings screens are rendered from the server's own published schema rather than
hand written, so they stay correct when the server adds a field. See
[docs/architecture.md](docs/architecture.md).

## Platforms

Android (F-Droid), Windows, Linux, macOS. One codebase.

## Licence

Apache 2.0. See [LICENSE](LICENSE).

Rampart builds on [jmap-mua](https://codeberg.org/iNPUTmice/jmap) and takes
reference from [Ltt.rs](https://codeberg.org/iNPUTmice/lttrs-android), both by
Daniel Gultsch, both Apache 2.0.
