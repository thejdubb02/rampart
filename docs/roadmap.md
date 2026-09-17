# What Rampart is building towards

The measuring stick is **Bulwark** (`bulwarkmail/webmail`, AGPL-3.0), the webmail that
already runs against this Stalwart. Matching it is the target for the mail half. It is
Next.js and React, about 883 files and 216,000 lines, with 27 locales whose English
bundle alone holds around 3,500 strings. That number is the honest scale of the job:
this is a roadmap, not a sprint.

## The gap Bulwark leaves, and it is the reason Rampart exists

**Bulwark's admin console administers Bulwark, not Stalwart.** It reads and writes
Bulwark's own JSON config on disk. It never calls Stalwart's management surface. You
cannot create a domain, an account, a principal, a group or a mailing list from it, and
you cannot see a DKIM key, a TLS certificate, the SMTP queue or a DMARC report.

Everything Stalwart-specific Bulwark does touch is **the logged in user's own account**:
change the mailbox password, TOTP, app passwords, API keys, public keys, encryption at
rest. Six small files' worth.

So the split is clean, and it is the shape of this project:

- **Mail:** catch up to Bulwark. It is ahead and it is good.
- **Server administration:** nobody has built it. That is ours, and it is what
  `/api/schema` is for. See `docs/architecture.md`.

## Build order

### Tier 1, a mail client you can live in

Done as of 2026-09-17. Everything below this line is now the work.

- Compose, reply, reply-all, forward, with correct `In-Reply-To` and `References`
- Drafts that save as you type, and reopen
- Threaded conversations
- Sending identities, and a signature per identity
- Search
- Move, delete, archive, mark spam, star
- Attachments: download, and attach on the way out
- New mail arriving without a restart, and a desktop notification when it does
- Remote images allowed per sender, and inline `cid:` images

### Tier 2, the rest of mail

- Multi-select and batch actions, with undo
- Sieve rules, a visual builder over a raw editor, and the vacation responder
- Tags, with colour and nesting
- Unified inbox across accounts
- Read receipts, both directions
- Unsubscribe via `List-Unsubscribe`
- SPF, DKIM, DMARC and spam-score display on a message
- Raw source view, `.eml` import and export
- Scheduled send, and an undo-send window
- Keyboard shortcuts throughout
- Templates

### Tier 3, the user's own account (Stalwart)

The `x:` data types under the `urn:stalwart:jmap` capability. Gate every one of these on
the session actually advertising that capability, never on a host name.

- Change the mailbox password, and the display name
- TOTP two-factor
- App passwords: create, name, expire, restrict by IP, revoke
- API keys
- Public keys, and encryption at rest
- Quota

### Tier 4, the Stalwart admin console

The part that does not exist anywhere else. Built from `/api/schema`, not hand written:
150 objects, 381 schemas, 359 forms, 72 list views. Domains, accounts, principals,
groups, mailing lists, DKIM, certificates, the queue, reports, tracing, the whole thing.

### Deliberately not doing

Carried from `docs/architecture.md`, plus what this comparison adds. Two entries left
this list on 2026-09-16: IMAP, which is now planned as a second backend once the JMAP
client is finished (`docs/architecture.md`), and themes, which shipped.

- POP3.
- Calendars, contacts and files. Bulwark does all three well and they are separate
  products wearing a mail client's clothes.
- OpenPGP or S/MIME in process. Bulwark moved S/MIME out to a plugin in 1.9 and has no
  PGP at all; there is no reason for us to carry it.
- Plugins, a marketplace, telemetry, an extension directory.
- Anything that is a Bulwark deployment concern rather than a mail concern: branding,
  org policy toggles, admin audit logs, PWA install.

## Where we actually are

Tier 1 is finished. Reading mail: mailboxes, conversations, HTML drawn without a browser
engine, links confirmed, pictures from the web held back until the reader allows that
sender, pictures the message carries drawn, a word above anything that cannot prove who
sent it, and one click off a mailing list where the sender said how. Several messages
picked out at once, filed together, and put back. Several accounts at once, with new mail
arriving on its own and a desktop notification when it does.

Writing: replies, reply all, forwards, attachments, drafts that save as you type, and a
signature per sending address that is HTML, can hold a picture, and lives on the server so
the webmail uses the same one.

Eighteen themes ported from Clique, picked on a settings screen. Passwords in the operating
system's own credential store.
