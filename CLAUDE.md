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

Two, both Stalwart, and use both because one server is not a compatibility test:

- `https://mail.example.org/jmap/` (ours, vps1). Admin surface on the
  tailnet at `http://10.0.0.1:8080/`. Helper: `a local helper script` on vps1.
- `https://mail.example.net/jmap/` (Mark's).

Credentials in the password manager, never in this repo.

## Going public

It is private for now. Before it flips: check no host names, tailnet addresses or
account details are in the history, not just the working tree.
