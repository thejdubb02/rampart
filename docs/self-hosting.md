# What you need to run, and what you do not

Rampart is a desktop mail client. You install it, you sign in to a mailbox, and it works.
This page exists because two of the features on the roadmap genuinely cannot work that way,
and the honest thing is to say so on one page rather than let somebody discover it after
installing.

Three classes. Which class a feature is in is decided when it is designed, not found out at
the end, and the first class is always preferred where there is a choice.

---

## Class 1: works with a mailbox and nothing else

The whole client. No server of ours, no server of yours, no account with anybody, no key,
nothing to configure beyond your email address and password.

Reading, sending, replying, forwarding, filing, starring, tagging, threads. The HTML reader
and its image blocking. Attachments. The authenticity pane, because SPF, DKIM and DMARC
results arrive in the message headers and are read from there. Search. The local store,
which means offline. The mailbox dashboard: volume in and out, how much junk you get,
who you talk to, who you never reply to, how fast you answer and what is still waiting on
you. Folder management. The command palette and the shortcuts. Templates. Undo-send, which
is held in the client. Read receipts, which are a standard mail header and not a tracker.
Recipient autocomplete.

**This is the default shape and the one to aim for.** The dashboard is the worked example:
every number on it is counted from mail already in the mailbox, so it needs no pixel, no
endpoint, no DNS and no configuration, and it works on Stalwart, on Gmail and on plain
IMAP. That is why it ships ahead of open tracking rather than behind it.

## Class 2: needs the companion server

One optional service, in `server/` in this repo, with a Dockerfile and a compose file. One
container, one hostname, one setting in Rampart. **There is one companion, not one per
feature**, and everything that needs it lights up together when you point Rampart at it.
Five separate addons would mean nobody runs any of them.

Leave the setting empty and the features below are visibly unavailable, with one sentence
saying why and a link here. They are never silently missing: a feature that is quietly
absent reads as a broken app.

What needs it today:

- **Open tracking.** A tracking pixel has to be fetched from somewhere, and a desktop app
  is not somewhere. The companion serves a 1x1 GIF at an id Rampart minted, keeps the log
  of what was fetched and when, and answers one authenticated question: what has opened
  since I last asked. `open-tracking.md` has the design.

What the companion is not, and will not become:

- **It never holds your mail.** No message, no body, no subject, no recipient. The
  tracking ids are random and mean nothing without the client that minted them.
- **It never holds your password**, and it is not a login for anything.
- **Nothing in class 1 ever starts depending on it.** If that is ever the shortest path to
  a feature, the feature is wrong.

## Class 3: depends on what your mail server supports

Not about self-hosting at all, this is your provider's capabilities. Rampart asks the
server what it can do and hides what it cannot.

- **Filters**, which need Sieve. Stalwart has it. Most IMAP providers expose it over
  ManageSieve. Gmail does not, and Gmail's own filters are not Sieve.
- **Vacation replies**, which need the JMAP vacation response or Sieve.
- **Instant new-mail push**, which needs JMAP over WebSocket. Without it Rampart polls, so
  the mail still arrives, a little later.
- **Server-side settings, identities, aliases and the admin console**, which are Stalwart's
  and appear only on a Stalwart account with the rights for them.

A capability your server lacks degrades with a reason on screen. It is never an error and
never a silent nothing.

---

## Bringing your own key, which is a fourth thing and not a server

The assistant features (thread summaries, draft help, triage) use a model you pay for, with
a key you supply, held in your operating system's credential store. Any OpenAI-compatible
endpoint, so a local Ollama works on the same path. No key, no assistant, and no account
with us either way. `assistant.md` has the rules, including that every call is a button
rather than a timer.

---

## The rule this page is really about

The person installing Rampart has a mailbox and nothing else: no VPS, no notification
router, no admin rights on their mail server, and no interest in acquiring any of them to
read their email. Our own deployment is one install among others rather than the platform,
and a feature that quietly assumes otherwise is a bug found late.

Open tracking is the worked example of getting it wrong first. Its first draft put the
pixel on our own box and marked the message by way of our mail server's admin token. Both
worked, for one person, and the second one asked a mail client to hold an administrator
credential, which is a thing a mail client should never do. What ships instead: a companion
anyone can run, and a keyword Rampart sets as the signed-in user with that user's own
session.
