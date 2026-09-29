# Stalwart 0.16 capability inventory, mapped to Rampart

Built 2026-09-28. Sources: stalw.art/docs (fetched live, sitemap at
`https://stalw.art/sitemap-0.xml` lists the full `/docs/ref/object/*` schema, which is
the authoritative list of `urn:stalwart:jmap` management types), the stalwartlabs/stalwart
GitHub README, a live read-only JMAP session against `rampart-test@willhitestrategy.org`
on `mail.willhitestrategy.org`, and Rampart's own `docs/architecture.md`, `docs/parity.md`,
`docs/roadmap.md`, `docs/self-hosting.md`, `CLAUDE.md`, and `grep` over
`src/main/kotlin/org/rampart/*.kt` (not full reads; `Main.kt` is 8,600 lines).

**Live session facts that matter below** (`.well-known/jmap`, account `d`):

- Capabilities advertised: `core`, `mail`, `calendars`, `calendars:parse`, `contacts`,
  `contacts:parse`, `filenode`, `principals`, `principals:availability`, `submission`,
  `vacationresponse`, `sieve` (Stalwart v1.0.0), `blob`, `quota`, `emailpush`,
  `webpush-vapid`, `websocket`, and account-level `mail:share` and `urn:stalwart:jmap`.
- `submission.maxDelayedSend` is **2,592,000 seconds (30 days)**, with
  `submissionExtensions` including `FUTURERELEASE`, `SIZE`, `DSN`, `DELIVERYBY`,
  `MT-PRIORITY` (MIXER), `REQUIRETLS`.
- Sieve extensions advertised (48 of them) include `spamtest`, `spamtestplus`,
  `virustest`, `editheader`, `vacation-seconds`, `regex`, `imap4flags`, `mailbox`,
  `mailboxid`, `special-use`, `subaddress`, `variables`, `extracttext`, `enotify`.
- `GET /api/schema` on the public webmail host returned Bulwark's own 404 page, not
  Stalwart's schema: the admin schema lives behind the admin console
  (`mail.willhitestrategy.org/admin`, Tinyauth-gated, or the tailnet-only port 8080), not
  the mail domain's root, and this test mailbox has no admin rights. `architecture.md`
  already recorded the schema's shape from an earlier authenticated look: **150 objects,
  381 schemas, 316 field sets, 359 forms, 72 list views, 154 enums, 3 navigation trees.**
  The sitemap's `/docs/ref/object/*` list (about 100 entries) is consistent with that and
  is what the tables below use for object names.
- **Finding worth flagging on its own:** `parity.md` 2.6 says Stalwart "advertises
  submission with no `maxDelayedSend`, which per RFC 8621 means zero." That was true when
  written and is not true now: the live server offers 30 days. The code has already moved
  past the doc, not the other way round (see Sending, "Scheduled send" below). This is the
  exact trap the estate's knowledge index warns about, aged into Rampart's own docs rather
  than the wider knowledge base: a capability check that was correct on the day it was
  written and silently went stale as Stalwart shipped a new release.

Legend for the "Rampart today" column: **server-backed** (Rampart calls Stalwart for
this), **client-only duplicate** (Rampart re-implements what the server already offers),
**partial**, **none**. Every "none" was reached by grepping, not assumed; the grep is
named in the evidence column.

---

## Mail

| Capability | What it does | Rampart today | Action |
|---|---|---|---|
| `Email`/`Mailbox`/`Thread` (JMAP mail core) | Store, list, and thread messages | Server-backed. `Jmap.kt:396` (`Mailbox/get`), `Email/query`+`Email/get` per `architecture.md` | done |
| Folder management, `Mailbox/set` + `role` | Create, rename, nest, move, delete folders; protects role-bearing folders | Server-backed. `Jmap.kt:1427,1449,1472`; `Folders.kt:30,107` | done |
| Keywords (tags) | Per-message labels, shared with any JMAP client | Server-backed. `Tags.kt` (per `parity.md` 2.8) | done |
| `Email/query` search | Full-text and field search | Server-backed, per `architecture.md` "The reader speaks JMAP directly" | done |
| `urn:ietf:params:jmap:quota` (RFC 9425) | Reports mailbox usage against a limit, when one is set | Server-backed. `Jmap.kt:940-951`; UI in `SettingsPane.kt:96,800-847` | done |
| `urn:ietf:params:jmap:emailpush` + WebSocket | Live push of new mail and changes | Server-backed. `Jmap.kt:71,657-1697` (`WebSocketPushEnable`, `StateChange`) | done |
| `webpush-vapid` (Web Push) | Push to a browser/service-worker endpoint | None; not applicable to a desktop app with its own WebSocket connection | skip (desktop app already gets push over the JMAP WebSocket; VAPID is for browsers) |
| `urn:ietf:params:jmap:vacationresponse` | Away/vacation autoresponder | Server-backed. `Jmap.kt:45,687,695`; `Vacation.kt` | done |
| Data retention: `expungeTrashAfter`/`expungeSubmissionsAfter` (`data-retention` object) | Server auto-empties Trash/Junk (default 30 days) and old Submissions (default 3 days) | None. Grepped `retention`, `expunge`, `archived-item` across `*.kt`: no hits | skip (this is a server admin setting under `system-settings`, not exposed to a non-admin JMAP session; covered by the admin console item below, not a mail-client feature) |
| `archived-item` / undelete window (Enterprise) | Grace period before a deleted item is gone for good | None | skip (Enterprise admin feature, same reasoning as above) |
| Email management: masked email (`/docs/email/management/masked-email`) | Server-generated disposable/aliased addresses per contact | None. Grepped `masked`, `maskedemail`, `burner`: no hits | build (S) - a Fastmail-style "hide my email" button in the composer/identity picker; low priority, nobody has asked for it |
| Email management: mailing lists, subaddressing, catch-all | Domain-level routing features | None, and rightly so | skip (admin/domain configuration, not a mailbox feature; folds into the admin console) |
| IMAP4rev2/IMAP4rev1 + POP3 fallback | Non-JMAP protocol access, for servers that are not Stalwart | Server-backed as a second `MailBackend`. `Imap.kt` (1,418 lines), `MailBackend.kt` (344 lines), discovery from the address alone per `architecture.md` "IMAP is back on" | done |

## Sending

| Capability | What it does | Rampart today | Action |
|---|---|---|---|
| `EmailSubmission` (core send) | Submits mail for delivery | Server-backed. `Jmap.kt:1292-1322` (`submit`, one round trip with `Email/set`) | done |
| `FUTURERELEASE` / `HOLDUNTIL` (submission extension, `maxDelayedSend` now 30 days) | Server holds a message and sends it later, no client needed to be running | **Server-backed, and capability-gated correctly.** `Jmap.kt:1236-1344` (`sendDelayed`, `holdEnvelope`, `holdUntilText`), gated in `Main.kt:4685` via `serverCanHold(account.jmap.maxDelayedSend, secondsAhead)`; falls back to client-held firing (`Scheduled.kt`) only when the backend can't hold it (IMAP, or a Stalwart window shorter than the wait) | done - and note this already **contradicts** `parity.md` 2.6's "still client-held" claim, which is stale (see note above) |
| `undoStatus: canceled` (EmailSubmission cancel) | Calls back a message already submitted | Server-backed. `Jmap.kt:1260-1266` (`cancelDelayed`) | done |
| `DSN` (delivery status notification, submission extension) | Server can report bounce/delay/delivery per RFC 3461 | Server-backed (2026-09-28). A "Confirm delivery" composer toggle, shown only when `DSN` is advertised, sends `RET=HDRS` and `NOTIFY=SUCCESS,FAILURE,DELAY` on the envelope (`Delivery.kt`, `submissionEnvelope`). A sent message shows what its `EmailSubmission.deliveryStatus` says (`Jmap.delivery`, `DeliveryLine.kt`), and nothing once Stalwart has expunged the submission or reports every recipient as `unknown` | done |
| `REQUIRETLS` (submission extension) | Refuses to relay a message unless the whole path stays on TLS | Server-backed (2026-09-28). "Require secure delivery" in the composer, shown only when advertised, puts a bare `REQUIRETLS` on `mailFrom`; a draft that asks for it on a server without it is refused rather than sent in the clear (`Delivery.kt`, `refusedOption`) | done |
| `DELIVERYBY` (submission extension) | Requests a delivery deadline, sender notified if missed | None | skip (real but niche; nobody has asked for a "must arrive by" send option) |
| `MT-PRIORITY` (MIXER) | Priority hint to the queue | None | skip (server/queue tuning, not user-facing) |
| `Identity/get`, `Identity/set` | Multiple send-as identities, each with its own HTML signature stored on the server | Server-backed. `Jmap.kt:165,801,820`; signature at `Jmap.kt:161,170,808,824` | done |
| `SIZE` (submission extension) | Advertises max message size up front rather than failing mid-send | Implicit; no client gap to close | skip |

## Filtering and spam

| Capability | What it does | Rampart today | Action |
|---|---|---|---|
| Sieve via JMAP `SieveScript` + ManageSieve | User-editable filter rules stored and run on the server | Server-backed, Bulwark-compatible format. `Filters.kt` (133 lines), `FiltersPage.kt` (705 lines), `Sieve.kt` (377 lines), `onSuccessActivateScript` in `Jmap.kt:1080,1085` | done |
| Sieve extension breadth (48 advertised: `spamtest`, `regex`, `imap4flags`, `variables`, `editheader`, `enotify`, `mailboxid`, `subaddress`, `extracttext`...) | The rule engine Rampart's builder targets | Partial. `Sieve.kt:67-72` (`Act` sealed interface) only emits `fileinto`, a keyword tag, mark-read, star, delete - a small slice of the 48 the server can run | partial - build (M) if richer conditions (regex match, header rewrite, notify) turn out to be wanted; the current slice covers what a mail client's own "if this, do that" screen actually needs |
| `X-Spam-Status`/`X-Spam-Result` header, spam score | The verdict from Stalwart's classifier, DNSBL, Pyzor, and LLM checks combined | Server-backed, already read. `Authenticity.kt:19,32,41,77,82,137` (`parseSpamScore`), shown in `DetailsPane.kt:74` | done |
| Spam classifier training loop (`SpamTrainingSample`; per Stalwart docs, samples come from "user interaction... explicitly tag messages as spam or ham") | Feeds the statistical classifier from real mailbox corrections | Server-backed implicitly: Rampart's Spam/Not-spam buttons move the message between Junk and Inbox via ordinary `Email/set` `mailboxIds`, which is the standard trigger. `Main.kt:8890` ("Spam" button), `Main.kt:3886` ("Spam and Not spam are the same move in opposite directions") | done (nothing more to build; Stalwart's docs do not describe a separate JMAP training call to wire up) |
| DNSBL, greylisting, spam traps, Pyzor digests | Admin-configured heuristics that feed the score above | N/A to a mail client | skip (admin/server tuning; the client only ever sees the resulting score, already surfaced) |
| Phishing protection: homograph URLs, sender spoofing (server-side, folds into the spam score) | Server-side detection of look-alike domains and spoofed senders | Server-backed first (2026-09-28). `ServerPhishing.kt` reads Stalwart's `X-Spam-Result` findings (`SPOOF_DISPLAY_NAME`, `SPOOF_REPLYTO`, `HOMOGRAPH_URL`, `MIXED_CHARSET_URL`, `PHISHING`) and skips the local checks those cover; `Phishing.kt` runs in full only when the header is absent. The password-field and lookalike-sender checks have no server counterpart and always run | done |
| `virustest` (Sieve extension, e.g. ClamAV integration) | Server-side attachment/message virus scan verdict | Server-backed where a scanner exists (2026-09-28). Stalwart 0.16.24 has no scanner of its own and writes no virus header; `VirusVerdict.kt` reads the ClamAV, Amavis and Rspamd headers a milter adds, and shows a chip in Show details and a banner on a hit | done |

## Calendars

| Capability | What it does | Rampart today | Action |
|---|---|---|---|
| `urn:ietf:params:jmap:calendars` + `:parse`, `Calendar`/`CalendarEvent` (also CalDAV) | Full server-side calendar: create, list, query events, recurrence, alarms | None used. `Calendar.kt` (369 lines) only parses `VEVENT`/`VALARM` text out of an email's own ICS attachment; no `Calendar/get`, `Calendar/query`, or `CalendarEvent/set` call anywhere in `Jmap.kt` | build (L) - tier 5 on the roadmap; a real calendar view is a second application, correctly deferred behind mail |
| Calendar scheduling (iTIP over JMAP, `calendar-scheduling` object) | Accepting/declining a meeting talks to the organizer's calendar directly, not by emailing a `.ics` back | **Moved to the server where the server will send it** (RAM-94, 2026-09-28, not yet checked live). `InvitationCalendar.kt` sets the participant's status with `sendSchedulingMessages` and Stalwart sends the REPLY; `InvitationReply.kt`'s hand-built email stays the fallback for IMAP, for servers without calendars, and for the cases Stalwart silently sends nothing. `docs/invitations.md` has the detail | done, pending a live check |
| `principals:availability` (free/busy) | Server can answer "is this person free" for a proposed time | None | skip for now (needs a scheduling UI that doesn't exist yet; revisit alongside the calendar view) |
| Invitation reading from a message | The half of "calendar" that belongs in a mail client: what, when, where, who, RSVP | Done, entirely client-side by design (works on IMAP too). `InvitationCard.kt`, `Calendar.kt` | done |

## Contacts

| Capability | What it does | Rampart today | Action |
|---|---|---|---|
| `urn:ietf:params:jmap:contacts` + `:parse`, `AddressBook`/`ContactCard` (JSContact) | Server-side address books, multiple books per account | Server-backed. `Jmap.kt:933,945,969,983,993,1001,1025` (`AddressBook/get`, `ContactCard/get`/`set`); every address book read, not just the default one, per `parity.md` 2.8b | done |
| CardDAV | Non-JMAP contacts access | N/A; JMAP path already covers this account | skip |
| Contact sharing (`mail:share` extended to address books) | Share a book with another account | None | build (S) - folds into the general Sharing gap below; nobody has asked for it specifically |

## Files

| Capability | What it does | Rampart today | Action |
|---|---|---|---|
| `urn:ietf:params:jmap:filenode` (JMAP File Storage) + WebDAV | Server-side file storage: folders, nodes, quota, sort options - the session shows `maxSizeFileNodeName`, `fileNodeQuerySortOptions`, etc. all live | Started on the branch `claude/files`, unreleased: `Files.kt`, `FilesPane.kt`, and `docs/files.md` for what the server does | build (L) - tier 5, "three more applications wearing a mail client's clothes" per `roadmap.md`; correctly behind mail and calendar |

## Sharing

| Capability | What it does | Rampart today | Action |
|---|---|---|---|
| `urn:ietf:params:jmap:mail:share` (JMAP Sharing) | Share a mailbox, calendar, address book, or file folder with another account, fine-grained read-through-full-access | None. Grepped `jmap:mail:share`, `ShareNotification`, `Sharing`: no real hits (only unrelated comments in `Imap.kt`) | build (M) - card 45 on the roadmap; "a different idea of what an account is," correctly scoped as its own gap rather than three small ones |
| WebDAV ACL (CalDAV/CardDAV/WebDAV) | Same sharing model, for non-JMAP clients | N/A until calendars/files exist | skip for now |
| `allowDirectoryQueries` / principal lookup | Lets a user search for another account to share with | None (no sharing UI to need it) | skip for now |

## Account security

| Capability | What it does | Rampart today | Action |
|---|---|---|---|
| `account-password` object, password change | Change the mailbox's own login password | None. Grepped `AccountPassword`, `changePassword`: no hits; `Accounts.kt` (211 lines) has no password-change flow | build (S) - the first and cheapest slice of the "six small files" self-service set `roadmap.md` calls out for tier 6 |
| TOTP 2FA (`otpUrl` on `AccountPassword`, internal directory only) | Time-based one-time password for the account | None | build (S) - enroll/show/remove, gated on the session advertising it, per `roadmap.md`'s tier-6 note |
| App passwords (`app-password` object, self-service portal) | Scoped credentials for legacy clients that can't do OAUTHBEARER/XOAUTH2, user-created and user-revoked, admin can only view/revoke | None. Grepped `app.?password`: no hits | build (S) - directly useful once Rampart itself supports OIDC login (see below), so a user isn't stuck typing their real password into a third client |
| API keys (`api-key` object) | Scoped tokens for scripts/integrations | None | skip for a v1 self-service page; low demand until someone asks |
| Public keys (`public-key` object) for encryption at rest | Upload a PGP public key so the server can encrypt at rest | Started 2026-09-29 on branch `claude/encryption`, not released: `x:PublicKey/set` as the signed-in user, in `EncryptionAtRest.kt` | built with encryption at rest below; `encryption.md` |
| Encryption at rest (S/MIME or OpenPGP, server-managed) | Server encrypts stored mail so an operator with disk access can't read it | Started 2026-09-29 on branch `claude/encryption`, not released: `x:AccountSettings` `encryptionAtRest`, set as the signed-in user (the default user role has `sysAccountSettings*` and `sysPublicKey*`). The encryption in `Secrets.kt`/`Store.kt` is still Rampart's own local cache encryption, a separate thing | `encryption.md` has the objects, what it covers and the live checks |
| S/MIME sign/encrypt/verify, PGP compose | Client-side message encryption (separate from encryption at rest) | Started 2026-09-29 on branch `claude/encryption`, not released: OpenPGP and S/MIME both, on Bouncy Castle | `encryption.md` |
| OAuth 2.0 / OIDC login **to your own Stalwart account** | Sign in without Rampart ever holding the raw password | None; Rampart currently authenticates with Basic auth (email + password) over JMAP/IMAP per `architecture.md`. Grepped `oauth`, `oidc`, `authorization_code`: no hits in `Accounts.kt`/`Secrets.kt`/`Discover.kt` | build (M) - distinct from the already-decided "no OAuth for Gmail/Microsoft" call in `architecture.md`, which was about interoperating with providers that require app registration. Stalwart's own OIDC provider needs no such registration and would remove the one credential Rampart currently has to store at all |
| SCIM v2 provisioning | Bulk account creation/sync from Entra ID, Okta, Keycloak | N/A to a mail client | skip (admin-side identity provisioning) |
| LDAP/SQL/OIDC directory backends | Where the server looks up accounts | N/A to a mail client | skip |

## Admin (the `urn:stalwart:jmap` management surface, `/api/schema`)

Rampart has built **none of this yet.** Grepped every management-shaped term
(`urn:stalwart:jmap`, `x:Domain`, `x:Account`, `x:Certificate`, `x:WebHook`, `admin`,
`schema`) across `src/main/kotlin/org/rampart/*.kt`: the only two `admin` hits are an
unrelated address-parsing comparison in `Main.kt:6212` and a comment in `Theme.kt:150`.
`architecture.md`'s "Settings and admin screens are generated, not written" section and
`roadmap.md`'s "Tier 6, the Stalwart admin console" ("still the thing nobody else has
built... still not built") describe the plan; there is no code against it yet. One row
per cluster of the roughly 100 `/docs/ref/object/*` types rather than one per object:

| Capability cluster | Represents | Rampart today | Action |
|---|---|---|---|
| Domains, DKIM rotation, DNS records, TLS certificates (`domain`, `dkim-signature`, `dns-server`, `certificate`, `acme-provider`) | Add a domain, rotate/inspect DKIM, see the DNS it needs, issue/renew TLS | None | build (L) - part of the schema-driven renderer |
| Accounts, principals, groups, tenants, roles, permissions (`account`, `account-settings`, `role`, `tenant`) | Create/edit mailboxes and who can do what | None | build (L) |
| Mail queue (`queued-message`, `mta-virtual-queue`, `mta-route`) | Inspect/retry/cancel queued outbound mail | None | build (L) |
| Blocked/allowed IPs, auto-ban (`blocked-ip`, `allowed-ip`, auto-ban) | See and manage the server's own abuse blocking | None | build (M) |
| Directory backends, cluster config, storage backends (`directory`, `cluster-node`, `data-store`, `blob-store`, `search-store`) | Deep server configuration | None | build (L), and lowest priority - this is infrastructure config, not day-to-day admin |
| Webhooks (`web-hook` object, Settings > Telemetry > Webhooks in Stalwart's own web UI) | Fire events at an external URL | None; admin-only per Stalwart's own docs, no JMAP path even in principle | build (M) as part of the schema renderer, no separate client-side design needed |
| Telemetry: tracing, logs, metrics, alerts, live delivery/tracing endpoints (`/api/live/*`) | Observability | None | build (M), lowest priority of the admin cluster |
| DMARC/TLS report **generation** settings (`dmarc-report-settings`, `tls-report-settings`) | Configure Stalwart's own outbound aggregate reporting to other domains | None; this is admin config, not a report a mail client reads | build (S), folds into the schema renderer - not a standalone feature |

One general note rather than 100 rows: **the entire admin cluster is a single build item
in practice**, per `architecture.md`'s own plan (one schema-driven renderer, gated on
`GET /api/account` permissions, degrading unknown widget types to a browser link,
hand-writing only the handful of multi-step tasks like certificate issuance). Listing
each of the ~100 object types as a separate row would overstate the work: it is one
renderer plus a few dozen hand-written task flows, not 100 features.

## Reports and monitoring

| Capability | What it does | Rampart today | Action |
|---|---|---|---|
| Per-message authenticity (SPF/DKIM/DMARC + spam score, read from headers the server already stamped) | The one report an end user actually needs | Server-backed. `Authenticity.kt` (full file) | done |
| DMARC aggregate report generation (server sends *to* other domains about mail claiming to be from us) | Admin/domain-owner concern, server-to-server | N/A to a mail client; Stalwart's own docs describe outbound generation only, no client-facing read path | skip (folds into the admin cluster above if ever built, not a mail-client feature) |
| SMTP TLS reporting (TLS-RPT) | Same shape as DMARC reporting, for TLS delivery problems | N/A to a mail client | skip, same reasoning |
| Webhooks, live tracing, metrics, alerts | Operational observability | N/A to a mail client | skip, covered under Admin above |

---

## MOVE TO SERVER

1. **Phishing banner should defer to the server's own verdict (M).** `Phishing.kt` reimplements homograph/spoofing detection from scratch on every message; Stalwart's spam filter already scores the same signals into the header Rampart already reads for the spam badge.
2. **Calendar invitation replies should use JMAP scheduling on Stalwart, not a hand-built `.ics` email (M).** `InvitationReply.kt` composes its own `METHOD:REPLY` and mails it; correct as the universal fallback, but a real request when the account is Stalwart. *Done on 2026-09-28 (RAM-94), see `docs/invitations.md`.*

*(Scheduled send, the obvious third candidate, is not in this list: it already made the move - `Jmap.kt`'s `sendDelayed`/`holdEnvelope` already use Stalwart's `FUTURERELEASE`/`HOLDUNTIL`, gated on `maxDelayedSend`. `parity.md` 2.6 just hasn't caught up to the code yet.)*

## SURFACE IT (cheap wins)

1. **REQUIRETLS composer toggle (S).** Stalwart's submission already advertises the extension; needs one checkbox and one envelope parameter, no server work.
2. **Virus-scan verdict badge (S).** Same shape as the existing spam-score badge; read the header, show one line.
3. **DSN delivery confirmation (S).** Read back what the server already offers instead of only inferring delivery from silence or a bounce.

## BUILD

1. **The Stalwart admin console (L).** ~100 management object types behind one schema-driven renderer, per Rampart's own architecture plan; the project's stated reason to exist and still entirely unbuilt.
2. **Files and a real calendar view (L each).** JMAP FileNode and JMAP Calendars are both fully live on the server (confirmed in the session) with zero Rampart code against either; correctly sequenced behind mail on the roadmap.
3. **Account self-service: password change, TOTP, app passwords (S each).** The cheapest slice of tier 6, and the natural companion to OIDC login (M) - removing the one password Rampart currently has to store.
4. **JMAP Sharing (M).** Roadmap card 45; a real gap ("a different idea of what an account is"), not yet started.
5. **Encryption at rest and S/MIME/PGP (L).** Deliberately last - "a half-built implementation is worse than none" - and still true.
