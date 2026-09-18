# The Rampart companion server

Optional. Rampart is a mail client and works with a mailbox and nothing else; this is for
the one thing a desktop app genuinely cannot do by itself, which is be somewhere on the web
that a picture can be fetched from.

Today that means **open tracking**. If you do not want to know whether your mail was read,
you never need this and Rampart will not ask you for it again.

**There is one companion, not one per feature.** Anything Rampart adds later that needs a
server goes in this same container, behind the same hostname and the same setting, so
running it once is the whole cost.

## What it knows about you, which is close to nothing

It stores four things per fetch: a random id, a timestamp, the user agent that asked, and
the requesting network reduced to a /24 or a /48. That is the whole database.

It never sees a message, a subject, a recipient, an address or your password. The ids are
random and are minted in Rampart, which keeps the mapping from id to message locally, so
this database is worth nothing to anybody who takes a copy of it. That is deliberate: it is
what makes it reasonable to run on a cheap box with a public hostname.

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
| `RAMPART_TRACKER_TOKEN` | none, required | The shared secret on the read-back. Give the same one to Rampart |
| `RAMPART_TRACKER_KEEP_DAYS` | `400` | How long a fetch is kept before it is thrown away |
| `RAMPART_TRACKER_DB` | `/data/tracker.db` | Where the database lives |
| `PORT` | `8080` | The port inside the container |

The server is a handover buffer, not the record. Rampart keeps its own copy of everything
it has read, so a short `KEEP_DAYS` loses nothing as long as Rampart runs occasionally.

## What it serves

| Route | Auth | What |
|---|---|---|
| `GET /o/<id>.gif` | none, by necessity | A 1x1 transparent GIF, 42 bytes, `no-store`. Records the fetch |
| `GET /opens?since=<ms>` | `Authorization: Bearer <token>` | Everything fetched since that moment, oldest first |
| `GET /health` | none, and never gate it | `ok` |

`/o/` always answers 200 with an image, whatever the id. A 404 for an unknown id would tell
anyone who asked which ids exist, and a mail client that gets an error draws a broken image
in the middle of somebody's message.

## Building it without Docker

It is a plain Gradle build with one dependency and its own settings file, so it builds on
its own without the desktop project:

```bash
./gradlew jar
RAMPART_TRACKER_TOKEN=... RAMPART_TRACKER_DB=./tracker.db java -jar build/libs/rampart-tracker.jar
```

## Why it is Kotlin and not something smaller

The rest of Rampart is Kotlin, and this reuses the SQLite driver the client already
depends on. Everything else is the JDK: `com.sun.net.httpserver` serves the three routes
above. A framework would have been a larger dependency than the program.
