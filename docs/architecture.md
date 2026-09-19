# Architecture, and why

Decisions recorded as they were made, with the reason. Change them by all means,
but read the reason first.

## Rampart is a desktop application, not an Android one

It started as an Android app. On 2026-09-16 we found [Sterna Mail](https://sternamail.org/),
which had already built it: native Android, JMAP first, IMAP as well, on F-Droid,
GPLv3, reproducible builds. Unified inbox, offline cache, threading, search, OAuth,
autodiscovery from an address alone, OpenPGP, UnifiedPush without Google, biometric
lock, vacation responder, Sieve rules, quota, shared and delegated mailboxes.

It began on 21 June 2026 and reached 1,317 commits and 73 releases in under three
months. The Stalwart team endorsed it on their own forum.

That is the whole of our version 1 and most of version 2, shipped and moving faster
than we could. Competing would cost years and win nothing.

Two things it does not do, and they are the two we actually wanted:

1. **Desktop.** Sterna is Android only. There is no native client for this stack on
   Windows, Linux or macOS. The alternative today is a browser tab.
2. **Server administration.** It manages vacation and filters. It does not manage
   domains, accounts, DKIM, the mail queue, certificates, TLS, listeners or logs.

So Rampart is the desktop client: mail plus the whole admin surface. On a phone we
recommend Sterna and do not duplicate it.

## Compose for Desktop, Kotlin, no Android target

With Android dropped, the multiplatform requirement goes with it. Compose for
Desktop alone covers Windows, Linux and macOS from one Kotlin codebase, and an
Android target can be added later as a module rather than a rewrite.

Admin is forms, tables and text. That is exactly what Compose is good at, and it is
the half of the product nobody else has built. Mail is the half where Compose on the
desktop is weakest, which is the next section.

## Rendering HTML mail has no free answer on the desktop

Android hands every mail client a WebView. The JVM does not. The options:

| Option | Cost |
|---|---|
| JCEF or KCEF (embedded Chromium) | Real browser, real isolation. Adds roughly 100 MB to the download and a Chromium update burden that is now ours. |
| JavaFX WebView (WebKit) | Smaller, ships with some JDKs, an older engine, and still a browser to keep patched. |
| Sanitise, then render in Compose | No script engine in the process at all, so the entire class of HTML mail exploits disappears. Newsletters look wrong. |

**Decided: the third, and reversed on 2026-09-18. See below.** The original reasoning
was that sanitising to a restricted subset and drawing it ourselves puts no script
engine in the process, so the whole class of HTML mail exploit does not apply, and the
cost is only that a marketing email loses its layout. That was a fair trade on paper.
It did not survive contact with actual mail.

The parser is jsoup, cleaned through a `Safelist` that drops every tag and attribute
we did not name, restricts `a[href]` to ftp/http/https/mailto (which is what kills
`javascript:`), and removes `script`, `style` and `noscript` outright. Parsing hostile
HTML is not something to hand roll. The cleaned tree is then walked once into a
Compose `AnnotatedString`. `src/test/kotlin/org/rampart/HtmlTest.kt` is the check that
has to bite: script stripped, `javascript:` not clickable, images counted and never
drawn.

Non-negotiable: no JavaScript, remote images blocked by default and allowed per
sender, `cid:` images served from the local blob, every link click confirmed, and the
renderer treated as hostile input at all times.

The per-sender allow list and `cid:` images are built. So is the block renderer
below, which is what made a picture drawable at all, and which is now the fallback
rather than the answer.

### Reversed: the engine, after all (2026-09-18)

"Revisit only if it turns out to be unusable" was the escape clause, and it was
reached. Every round of this went the same way: a screenshot of a real message beside
webmail's version of it, one more CSS feature implemented, a better but still wrong
result. Widths, then centring, then `display:none`, then buttons, then background
colours. A designed email is nested tables carrying inline CSS, media queries,
background images and fixed widths, and matching it is writing a browser. The renderer
was always one property short of the next newsletter, and the list of properties does
not end.

Webmail does not have this problem because it hands the HTML to the browser it is
already inside. Bulwark sanitises with DOMPurify and drops the result into a sandboxed
`srcdoc` iframe under a strict content security policy. That is the whole trick.

So Rampart does the same thing with the engine it has to bring itself: **JavaFX's
WebKit**, in a `JFXPanel` inside the Compose window. Chromium through JCEF was the
other candidate and is three times the download for accuracy no email needs. The `web`
module is 31 MB on Windows and the rest are a few MB together, so the package goes from
roughly 84 MB to roughly 120 MB.

What did not change is where the security boundary is. **The clean still happens
first, in jsoup, before the engine sees a byte**, and it is still the thing that has to
be right. `EmailDocument.kt` is that step and `EmailDocumentTest.kt` is the check that
has to bite: script, iframe, object, embed, form, input, base and svg dropped; every
`on*` handler gone with them; `javascript:` unreachable; remote pictures replaced by a
blank and counted; background pictures in attributes and in CSS neutralised the same
way.

Three things back it up rather than replace it:

- A content security policy of `default-src 'none'` in the document itself. No script,
  no frame, no font, no fetch, no form post, whatever the cleaner may have missed. With
  pictures allowed it becomes `img-src data: http: https:` and nothing else moves.
- The engine gets no navigation. A click on a link is caught in the page and handed
  back to Rampart to open in the reader's own browser: this view has no address bar, no
  back button and no profile, so a message that could navigate it is a message that
  could show you a login page.
- Nothing script-shaped is ever part of the message's own HTML. The one thing the page
  still does, handing back link clicks and wheel turns, is injected after it loads.

`<style>` is now carried over, which the old safelist could not do: jsoup's cleaner
keeps a `<style>` element and throws away everything inside it, because a safelist
describes tags and attributes and a stylesheet is neither. It is copied across by hand
with `@import`, `expression(`, `behavior:`, `-moz-binding`, `javascript:` and `</style`
taken out. The message's own `<body>` styling is carried the same way and for the same
reason: the cleaner builds its own document, so without this every message lost the
sender's page background and font.

Two things the engine does not get to decide. It is sized to its content and put in the
pane's scroll rather than scrolling itself, so the header, the tags and the attachments
scroll with the message; the page hands its wheel events back for that, because a real
component swallows its own input and the pane would otherwise stop scrolling wherever
the pointer happened to be. And the page is always light, whatever the window is doing:
see below.

### Handing the message to the engine, and four ways it went wrong (2026-09-19)

All four produced the same thing on screen, which is why they took a day: a message that
was there, drawn, and blank. None of them logged anything.

**The document reaches the engine as a file, not as a string or a URL.** `loadContent`
re-encodes the string somewhere before WebKit sees it, and nothing outside the Basic
Multilingual Plane survives: a ticket emoji arrived as six replacement characters, and so
did every en dash and em dash in every newsletter, which is in far more mail. Passing
`text/html; charset=UTF-8` as the content type does not correct it, it stops the page
loading at all, because the type is matched exactly. A `data:` URL fixed the encoding and
brought a ceiling with it: a signature carrying a 700 KB logo makes a document of nearly a
megabyte and a data URL of one and a quarter, and at that size the picture came out as a
blank white rectangle with the text around it intact. So the document is written out as
UTF-8 bytes and the engine is pointed at the file, which has neither problem. The file is
deleted when the next message is opened.

**The height is read from here, not reported by the page.** It used to come back through
the JavaScript bridge, which needs the bridge attached, the injected script to run, and
the page allowed to call back out. When any of those did not happen there was no error
anywhere and the panel stayed at the height it started with, which is a blank strip. It is
now one `executeScript` on the thread the engine already runs on, asked repeatedly for a
few seconds because the answer changes as the pane is laid out and pictures decode.

**The height is multiplied by the zoom.** `scrollHeight` is in the page's own pixels and
the panel is measured in the window's, and those are the same number only at zoom 1. On a
scaled display, which is most of them, a message asked for a panel a fraction of the size
it was about to draw itself at.

**And if none of that works, the message still shows.** After three seconds with no
measurement the panel takes the pane and the page scrolls itself, which is what webmail
does and is always readable. The measuring is worth doing, because a message sized to its
content scrolls with the rest of the pane rather than inside a box within it. It is not
worth a blank message when it fails, and the remaining ways it can fail are in a browser
engine, a toolkit and a display scale, which should not all have to agree for mail to
appear.

**A message is drawn as it was sent, and only what it left out is filled in.** Rampart
inverted the page in a dark window until 2026-09-19, which is the trick webmail uses, and
it was wrong twice on real mail. A reply with no colours in it came out as white text on a
black slab, because inverting a page nobody painted inverts the white the engine supplies.
And a design that has been inverted is one nobody made: a hotel's dark brown header came
out pink. So the page is light, the sender's colours are what you see, and a dark window
holds a light message the way a webmail tab does.

What is supplied is a stylesheet for the message that brought none, which is most mail: a
reply typed into a webmail box is a bare `<div dir="ltr">` with no font, no colour, no
width and no margin, and handed to an engine untouched it arrives in the engine's Times at
the very edge of the pane, with a pasted URL setting the width of the page and every
paragraph above it clipped. It is four declarations, on `html` so that anything the sender
did state wins, and it is applied only where the message has no background of its own, so
a newsletter keeps its full-bleed header and gets no padding it did not ask for.

The block renderer stays. Plain text still goes through it, because a written message
has no layout to get right and drawing it in Compose keeps it selectable, themed and
part of the same scroll with no engine to start. It is also what runs if the engine
cannot start at all, which is asked once by starting one and seeing.

### The renderer returns blocks, not one string (2026-09-17)

The first version walked the cleaned tree into a single `AnnotatedString`. One string
can only be laid out one way, so every picture had to be drawn in a heap underneath
the message rather than where the sender put it, a quote could only be a colour, and
a table came out as its cells run together. `Blocks.kt` keeps the tree as a short
list of things to draw: text, heading, quote, list, picture, table, rule. `HtmlBody.kt`
draws them.

The cleaner is unchanged and is still the whole security boundary. What changed is
that `img` is now allowed through it, with `src` restricted to `http`, `https`, `cid`
and `data`, which is what lets a picture be drawn in place. `data:` is included
because it is the safest kind there is, no request leaves the machine, and because
our own signatures use it.

A table is a grid only when it is at least two rows by two columns. Mail uses tables
for layout far more often than for data, and a one cell table is usually the wrapper
round the entire message: drawing that as a grid puts a border round the whole email,
and reading it as text loses the pictures inside it. Anything that does not look like
data is unwrapped and read as ordinary blocks.

Still not a browser, and still not becoming one. No CSS, no positioning, no floats,
no script engine. What this buys is the structure a person reads by, which is the
part that was actually missing.

## The reader speaks JMAP directly, and jmap-mua is off the path

`rs.ltt:jmap-mua` (Apache 2.0, Java 8) was the planned engine: protocol, sync,
caching and threading in one library. The reader needed none of it. Listing mailboxes,
listing a mailbox's messages and fetching one body is three JMAP method calls, and
`Email/query` feeds `Email/get` through a back reference so the ids never make the
round trip through us. `Jmap.kt` is that and nothing else, on the JDK's own HTTP
client.

That leaves no dependency to audit, no fork to budget and no library version to track.
Revisit when we want the part jmap-mua actually provides: an offline cache and
change-driven sync. Writing that ourselves is the point at which the library would
earn its keep.

## Settings and admin screens are generated, not written

Stalwart publishes its entire management interface as data at `/api/schema`:
150 objects, 381 schemas, 316 field sets, 359 forms, 72 list views, 154 enums and
three navigation trees (Management, Settings, Account). Its own web console is not
hand built, it draws itself from that document.

So Rampart writes one renderer and gets every page at once, including pages Stalwart
has not shipped yet, and a Stalwart release does not break us. This is the difference
between the admin half being a year of work and a few weeks.

Four guardrails, each of which the naive version gets wrong:

- **Gate on permissions, not on the schema.** `GET /api/account` returns the token's
  effective permissions. A menu built from the schema alone will show pages that
  answer 401.
- **Unknown widget types open in the browser.** Stalwart will add a field type the
  renderer does not know. That must degrade to one page, not to a broken section.
- **Destructive operations are not text fields.** Directory, TLS, listeners and queue
  writes get a confirmation showing what changes, not a Save button.
- **Some things are tasks, not field sets.** Certificate issue, DNS checks and
  anything needing a reload are multi-step. Hand write those few.

Non-Stalwart JMAP servers publish no schema. Check the capability, hide the section,
give them mail.

## Two credentials, never one

The mail session and the admin session use separate tokens with separate scopes. A
hole in the mail reader must not reach `x:Directory/set`. Storing one all-powerful
credential turns any renderer bug into a full server compromise.

Tokens live in the OS credential store: DPAPI on Windows, libsecret on Linux,
Keychain on macOS. Not in a file beside the database.

## Storage

Metadata for everything, bodies on demand. Store ids, threadId, keywords, size,
preview and receivedAt; fetch a body when a message is opened and cache it under an
LRU with a size cap. Never download a 100,000 message mailbox.

Encrypt the message database from the first release. Migrating a plaintext cache
after people have data in it is miserable.

Decide the sync window before writing the sync code, not under pressure afterwards.

## No certificate pinning

Self-hosters use Let's Encrypt, Tailscale, private CAs and debug proxies. Pinning
locks them out on renewal. System PKI, plus an explicit option to trust a custom CA.

## Distribution

F-Droid no longer applies. Windows first: a jpackage MSI, unsigned to begin with,
which means a SmartScreen warning that the README has to be honest about. Then Linux
(Flatpak, plus an AppImage for people who refuse Flatpak), then macOS, which needs a
99 dollar a year Apple account for notarisation before it is worth shipping.

Auto-update is ours to build. Compose does not provide one.

## IMAP is back on, as a second backend

This reverses what this file said until 2026-09-16. JMAP only was the right call while
the only mailbox was ours; it stops being right the moment anyone else wants to use
Rampart, because almost nobody runs a JMAP server. Justin's direction: a dedicated mail
client for anyone, with the Stalwart management screens appearing only when the server
turns out to be Stalwart.

The plan is deliberately not "add IMAP now". It is:

1. Finish the JMAP client to the point where it is the one he actually uses all day.
2. While doing that, keep every mail operation behind one interface, with `Jmap` as its
   first implementation, so the reader never learns which protocol it is talking to.
3. Add IMAP as a second implementation of that interface.

Doing it in that order costs nothing, because step 2 is a shape rather than a feature.
Doing it in the other order means writing two half clients and finishing neither.

What does not carry over: JMAP does server side search, threading and push in one round
trip, and IMAP does not. Those become capabilities a backend declares rather than things
the reader assumes, which is the real work in step 2.

## What we are deliberately not doing

Android (Sterna). Calendars, contacts and file storage. OpenPGP implemented in
process. Certificate pinning. Any app store. Admin on a phone.

## Brand

Red `#DB2D54`, taken from Bulwark's logo, so the pieces match.
