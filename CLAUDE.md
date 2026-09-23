# rampart - a desktop client for Stalwart

Mail plus the whole server admin surface, on Windows, Linux and macOS. **The repo is
public**, so everything here is written to be read, and run, by strangers.

## The rules that matter

- **Desktop only. Do not add an Android target.** [Sterna Mail](https://sternamail.org/)
  already built that app and builds it faster than we could. Rampart exists for the
  two things Sterna does not do: desktop, and real server administration. Read
  `docs/architecture.md` before arguing with this.
- **JMAP first, IMAP and SMTP second, and both ship.** This said "JMAP only" until
  2026-09-17, on the reasoning that IMAP cannot carry settings, filters, aliases or
  admin. All true, and beside the point: a client that only talks to Stalwart is a
  client almost nobody can install. IMAP accounts get the mail and lose the admin,
  each missing capability degrading with a visible reason rather than an error.
  Roadmap 2.7 has the list of what IMAP cannot do and what we do instead.
- **Do not hand write settings screens.** Stalwart publishes 359 forms at
  `/api/schema`. One renderer, every screen, and it survives Stalwart releases.
  Gate the menu on `GET /api/account` permissions, not on the schema.
- **Two credentials, never one.** The mail token and the admin token are separate
  and separately scoped. A renderer bug must not reach `x:Directory/set`.
- **The HTML reader is hostile input.** No JavaScript, remote content blocked by
  default, every link confirmed. There is no browser engine in the process and there
  is not going to be one: jsoup cleans the HTML, Compose draws it. Do not widen the
  `Safelist` without a test in `HtmlTest.kt` showing what the new tag cannot do.
- **No certificate pinning.** Self-hosters use Let's Encrypt, Tailscale and private
  CAs; pinning locks them out.
- **Red is `#DB2D54`**, from Bulwark's logo.

## Layout

The Gradle project is at the root, decisions are in `docs/`, and the optional
companion server lives in `server/` with its own compose file.

## Built for somebody who is not us

The person installing this has a mailbox and nothing else: no VPS, no notification
router, no admin rights on their mail server, and no interest in acquiring any of
them to read their email. Our own deployment is one install among others, never the
platform. So every feature is one of three things, and which one is a design decision
made up front rather than discovered at the end:

1. **Works on its own.** The mail, the reader, the dashboard, search, filters. Needs
   a mailbox and nothing else. **Always prefer this shape.** The dashboard counts what
   is already in the mailbox, which is why it ships ahead of open tracking.
2. **Needs the companion server**, the optional thing in `server/`. **One companion,
   not one per feature**: a single container, a single hostname, a single setting in
   Rampart, and every feature that needs it lights up together. Five separate addons
   means nobody runs any of them. A feature in this class is visibly unavailable and
   says why in one sentence until the setting is filled in, never silently missing.
3. **Needs their own mail server.** The admin console, and anything Stalwart-specific.
   Hidden on an account that cannot do it.

**Never ask for admin rights on the mail server to deliver a mail feature.** Rampart
is a client: what it does to a mailbox it does as the signed-in user, with that user's
own session. Open tracking's first draft set a keyword through our Stalwart admin
token, which worked for exactly one person and was more dangerous besides.

`docs/self-hosting.md` is the contract and lists which features sit in which class.

## Test servers

Two Stalwart servers, and use both: one server is not a compatibility test. Their
addresses, the admin surface and the credentials are not in this repo and are not
going in it. They live in the operator's own notes and password manager. A real
server to test against goes in a git-ignored local file, never in a commit.

Check a UI change by looking at it, not only by its tests: skill `see-the-app` runs the
app under Xvfb on this box and screenshots it.

## This repo is public

Host names, tailnet addresses, account details and credentials stay out of the
working tree and out of the history. Check both before every push, not just the
working tree: a scrubbed file with the original still one commit back is not
scrubbed.

## Releasing is deliberate, not automatic

Every push runs the tests. Only a manual run packages and publishes:

    gh workflow run build.yml --field release=true

It used to release on every push, which gave a version number to every commit and
asked an installed copy to restart for a one line change. Packaging is also the
slowest and least reliable step by a wide margin: on 2026-09-17 three of five
runs died partway through uploading to GitHub, each time with a different file
and nothing wrong with the code.

When a release does fail halfway, the release object already exists and Conveyor
refuses to publish over one, so a re-run fails for a second, different reason.
Clear it first, then re-run:

    gh release delete 0.1.<n> --yes --cleanup-tag

The version is the commit count either way, so it keeps climbing between
releases and a released version is simply the count at the moment it went out.

## The board is the status, and it is kept current as we go

**Kaneo, workspace Willhite Strategy Group, project RAM.**
Justin reads it to see where things are and what is coming, so a card that is out of date
is worse than no card: it is a wrong answer to the question he opened it to ask.

So, in the same change as the work, never as a tidy-up afterwards:

- Starting something moves its card to **In Progress**.
- Finishing it moves the card to **Done**, and the description gains one line: the
  version it shipped in and the date.
- Anything he throws out in passing gets a card at **no-priority** with the date, and
  `docs/roadmap.md` gets the same line under "Ideas". Logged, not built.
- Something found mid-work that is real but not now gets its own card rather than a
  mention in a commit message nobody will search for.

The next ten in `docs/roadmap.md` and the cards are the same ten. When they disagree the
file is the one to fix, because a card is easy to drag and an argument is not.
