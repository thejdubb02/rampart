# The Rampart companion server

Optional. Rampart is a mail client and works with a mailbox and nothing else; this is for
the one thing a desktop app genuinely cannot do by itself, which is be somewhere on the web
that a picture can be fetched from.

Today that means **open tracking** and **diagnostics**. If you do not want either, you
never need this and Rampart will not ask you for it again: both are visibly unavailable
in Rampart's own settings until a companion is configured.

**There is one companion, not one per feature.** Anything Rampart adds later that needs a
server goes in this same container, behind the same hostname and the same setting, so
running it once is the whole cost.

## What it knows about you, which is close to nothing

For open tracking, it stores four things per fetch: a random id, a timestamp, the user
agent that asked, and the requesting network reduced to a /24 or a /48. It never sees a
message, a subject, a recipient, an address or your password. The ids are random and are
minted in Rampart, which keeps the mapping from id to message locally, so this database is
worth nothing to anybody who takes a copy of it. That is deliberate: it is what makes it
reasonable to run on a cheap box with a public hostname.

For diagnostics, it stores a metric name and an outcome category, both from a closed list
Rampart's own code enforces, plus a count and the shape of a duration (a minimum, a maximum
and a total), aggregated by the client over a few minutes before it is ever sent. Never a
message, a subject, a recipient, a folder's name, a search term, or which account any of it
happened on. See `Diagnostics.kt` in the main repository for the full catalog of what gets
measured and why an error's own text never leaves the machine it happened on.

## Running it

```bash
echo "RAMPART_TRACKER_TOKEN=$(openssl rand -base64 32)" > .env
docker compose up -d
```

Then put a hostname with TLS in front of it and give Rampart the URL and the same token.

It **refuses to start without a token**, and that is on purpose. A tracking log with no
token on the read-back is readable by anyone who finds the hostname, and it fails silently:
everything works, and it leaks.

### The proxy in front

The compose file binds to loopback, so nothing is exposed until you put a proxy in front of
it. A minimal nginx:

```nginx
server {
    server_name  pixel.example.com;
    listen 443 ssl;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header X-Forwarded-For $remote_addr;
    }
}
```

Three things worth getting right:

- **Never put it behind an SSO gate.** A gate answers a machine caller with a 302 to a
  login page, which is a 200 to anything that is not a browser: the recipient's mail client
  fetches your login page instead of the pixel, and every check passes while nothing works.
- **TLS, not plain HTTP.** A pixel fetched over HTTP is one that every network between the
  recipient and you can read, which tells them who is mailing whom.
- **A neutral hostname.** Some spam filters score a hostname with "track", "mail" or
  "click" in it. `img.example.com` is a better name than `tracker.example.com`. That is
  advice, not something the server checks.

### Settings

| Variable | Default | What it does |
|---|---|---|
| `RAMPART_TRACKER_TOKEN` | none, required | The shared secret on `/opens` and `/diag`. Give the same one to Rampart |
| `RAMPART_DIAG_TOKEN` | none, optional | A second secret, valid on `/diag` only, never `/opens`. See below |
| `RAMPART_TRACKER_KEEP_DAYS` | `400` | How long a fetch or a diagnostics batch is kept before it is thrown away. One knob for both |
| `RAMPART_TRACKER_DB` | `/data/tracker.db` | Where the database lives |
| `PORT` | `8080` | The port inside the container |

The server is a handover buffer, not the record. Rampart keeps its own copy of everything
it has read, so a short `KEEP_DAYS` loses nothing as long as Rampart runs occasionally.

**Why there are two tokens.** `RAMPART_TRACKER_TOKEN` is the one thing worth protecting on
this server: whose mail was opened, and when, on `/opens`. A build of Rampart that ships an
inbound point baked in for every install of a public, open-source app cannot carry that
token, because anyone who reads the source could then read that log. `RAMPART_DIAG_TOKEN`
is what a build like that carries instead: it can post to `/diag` and do nothing else, so
the source being public costs this server nothing worse than somebody posting junk numbers.
Self-hosting your own copy for your own diagnostics never needs it; `RAMPART_TRACKER_TOKEN`
alone still works on `/diag` the same as it always did.

## What it serves

| Route | Auth | What |
|---|---|---|
| `GET /o/<id>.gif` | none, by necessity | A 1x1 transparent GIF, 42 bytes, `no-store`. Records the fetch |
| `GET /opens?since=<ms>` | `Authorization: Bearer <token>` | Everything fetched since that moment, oldest first |
| `POST /diag` | `Authorization: Bearer <token>` or `<diag-token>` | Aggregated diagnostics, a batch at a time. See below |
| `GET /health` | none, and never gate it | `ok` |

`/o/` always answers 200 with an image, whatever the id. A 404 for an unknown id would tell
anyone who asked which ids exist, and a mail client that gets an error draws a broken image
in the middle of somebody's message.

`/diag` takes a small JSON body: `{"items":[{"metric":"...", "category":"...", "count":N,
"sum":N, "min":N, "max":N}, ...]}`, `category`, `sum`, `min` and `max` all optional. Each
`metric` has to be one of a fixed list this server checks for itself, mirrored from
Rampart's own closed allowlist: an item naming anything else is silently not stored, the
same way a malformed body is. There is no read-back route for this table today, unlike
`/opens`: nothing in Rampart asks for one yet, and querying the SQLite file directly is
enough for now. Answers `{"stored":N}`, always 200 once the token is right, whatever N is.

## Building it without Docker

It is a plain Gradle build with one dependency and its own settings file, so it builds on
its own without the desktop project:

```bash
./gradlew jar
RAMPART_TRACKER_TOKEN=... RAMPART_TRACKER_DB=./tracker.db java -jar build/libs/rampart-tracker.jar
```

## Why it is Kotlin and not something smaller

The rest of Rampart is Kotlin, and this reuses the SQLite driver the client already
depends on. Everything else is the JDK: `com.sun.net.httpserver` serves the routes above,
and a small hand-rolled JSON reader parses `/diag`'s body, the same reasoning `quoted()`
already applies to writing JSON out. A framework would have been a larger dependency than
the program.
