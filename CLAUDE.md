# rampart - a JMAP mail client for Stalwart and Bulwark

Android plus desktop, one codebase. Public repo eventually, so everything here is
written to be read by strangers.

## The rules that matter

- **JMAP first, IMAP second.** Not a preference, a consequence: IMAP cannot carry
  settings, filters, aliases or push, so an IMAP account can only ever be a plain
  inbox here. Read `docs/architecture.md` before arguing with this.
- **Do not fork Ltt.rs.** Its UI is Android-only Views and cannot reach the desktop.
  We keep its engine (`jmap-mua`) and write the UI in Compose Multiplatform. Ltt.rs
  is a reference to lift from, Apache 2.0, credit it.
- **Do not hand write settings screens.** Stalwart publishes 359 forms at
  `/api/schema`. One renderer, every screen, and it survives Stalwart releases.
- **No Firebase, ever.** F-Droid rejects it. Push is UnifiedPush against the
  server's VAPID support.
- **Red is `#DB2D54`**, from Bulwark's logo.

## Layout

Nothing built yet. When it is: `docs/` for decisions, the Gradle project at the
root, shared UI in `composeApp/`.

## Test server

Our own Stalwart on vps1. The JMAP session lives at
`https://mail.example.org/jmap/`, admin surface on the tailnet at
`http://10.0.0.1:8080/`. Helper: `a local helper script` on vps1.
Credentials in the password manager, never in this repo.

## Going public

It is private for now. Before it flips: check no host names, tailnet addresses or
account details are in the history, not just the working tree.
