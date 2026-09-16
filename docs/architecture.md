# Architecture, and why

Decisions recorded as they were made, with the reason. Change them by all means,
but read the reason first.

## JMAP only to begin with, IMAP later

IMAP carries messages. It does not carry filter rules, aliases, vacation
responders, app passwords, quotas, push registration or any server setting. Every
feature that makes Rampart worth installing over K-9 or FairEmail exists only on
the JMAP side.

So an IMAP account in Rampart can never be more than a plain inbox. IMAP support
is therefore the most work for the least differentiation, and it goes second, as
"it also handles your old Gmail", not as the foundation. Building both at once
means shipping neither.

JMAP first also covers Fastmail, Cyrus and Apache James users, not only Stalwart.

## The engine is jmap-mua, not ours

`rs.ltt:jmap-mua` (Apache 2.0, Java 8) is a headless mail client: protocol, sync,
caching, threading, everything except storage and UI. Writing that ourselves would
take years and produce something worse. Ltt.rs is the same library with an Android
UI on top, and is the worked reference for driving it correctly.

Java 8 and JVM means the same engine runs on Android and on the desktop.

## The UI is Compose Multiplatform, so we do not fork Ltt.rs

The obvious move was to fork Ltt.rs and finish it. Desktop support kills that:
its UI is Android XML layouts and Views, which cannot run on Windows.

Compose Multiplatform is stable for Android, desktop (Windows, Linux, macOS) and
iOS. One Kotlin UI codebase covers every target we want. So we keep the engine,
write the UI once, and lift from Ltt.rs where it helps (the licence allows it).

The cost: writing a UI is more work than forking one. The offset: we were
replacing that UI anyway, and this buys three more platforms for roughly the
effort of doing Android alone.

Known asterisks: desktop packaging, tray icons, notifications and registering as
the system mail handler are per-platform work. Compose desktop apps bundle a JVM,
so downloads are around 80 to 100 MB.

## Settings screens are generated, not written

Stalwart publishes its entire management interface as data at `/api/schema`:
150 objects, 381 schemas, 316 field sets, 359 forms, 72 list views, 154 enums and
three ready made navigation trees (Management, Settings, Account). Its own web
console is not hand built, it draws itself from that document.

So Rampart writes one renderer that turns a schema form into a screen, and gets
every settings page at once, including pages Stalwart has not shipped yet. This is
the difference between "manage your server from your phone" being a year of work
and a few weeks, and it means a Stalwart release does not break us.

The three trees map cleanly onto the product:

| Tree | Who sees it |
|---|---|
| Account | every user: password, app passwords, API keys, Sieve scripts, vacation response, masked addresses, mailboxes, spam samples |
| Management | admins: accounts, domains, DKIM, queues, reports, logs, cluster |
| Settings | admins: network, storage, TLS, MTA, spam filter, telemetry |

Non-Stalwart JMAP servers do not publish a schema. They get mail and the standard
JMAP objects, and no settings tree. Check the capability, hide the section.

## Push without Google

F-Droid does not accept Firebase. Stalwart advertises `urn:ietf:params:jmap:emailpush`,
`urn:ietf:params:jmap:webpush-vapid` and `urn:ietf:params:jmap:websocket`, so push
goes over UnifiedPush against the server's own VAPID support. Ltt.rs already pulls
in Tink's webpush library, which is the encryption half of that.

## Brand

Red `#DB2D54`, taken from Bulwark's logo, so the three pieces match.
