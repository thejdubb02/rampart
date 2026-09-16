# rampart - a desktop client for Stalwart

Mail plus the whole server admin surface, on Windows, Linux and macOS. Public repo
eventually, so everything here is written to be read by strangers.

## The rules that matter

- **Desktop only. Do not add an Android target.** [Sterna Mail](https://sternamail.org/)
  already built that app and builds it faster than we could. Rampart exists for the
  two things Sterna does not do: desktop, and real server administration. Read
  `docs/architecture.md` before arguing with this.
- **JMAP only.** IMAP cannot carry settings, filters, aliases or admin, so an IMAP
  account here could only ever be a plain inbox. Not on the roadmap.
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

Nothing built yet. When it is: `docs/` for decisions, the Gradle project at the root.

## Test servers

Two Stalwart servers, and use both: one server is not a compatibility test. Their
addresses, the admin surface and the credentials are not in this repo and are not
going in it. They live in the operator's own notes and password manager. A real
server to test against goes in a git-ignored local file, never in a commit.

## This repo is public

Host names, tailnet addresses, account details and credentials stay out of the
working tree and out of the history. Check both before every push, not just the
working tree: a scrubbed file with the original still one commit back is not
scrubbed.
