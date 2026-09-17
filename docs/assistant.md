# An assistant in the mail, and a way in from outside

Three asks, written down on 2026-09-17, in the order they were made:

1. **Bring your own key.** A model, through OpenRouter or anything like it, doing
   summaries, helping draft replies, and helping with spam.
2. **An API**, so an assistant outside Rampart can set somebody's mail up for them:
   filters, tags, folders, identities.
3. **MCP support**, so that assistant is any MCP client, not one we wrote.

None of this is built. This is the design, written before the code so the decisions are
arguable while they are still cheap.

---

## The one rule that shapes all three

**Nothing client-facing sends itself.** That is an estate-wide rule and it is not
negotiable here. A model may draft, summarise, sort, suggest and explain. It may not put
a message in the outbox, and it may not be one approval away from doing so by accident.
The API in section 3 is deliberately allowed to write filters and tags and deliberately
not allowed to send.

The second rule, from the same place: **anything we give an LLM gets its own key, a
registry entry with a daily budget, and a monitor.** Section 2 says what that means when
the key belongs to the user rather than to us, because the usual answer does not fit.

---

## 1. What the model is actually for

Four jobs, in the order they earn their keep.

**Summarise a long thread.** Twenty messages, one paragraph, before you decide whether to
read it. This is the one people ask for and the one with the least that can go wrong: it
is read-only, it is per-thread, and a bad summary costs a few seconds.

**Draft a reply.** The model proposes, the composer opens with it, and nothing leaves
until a person presses Send. The draft carries a mark saying it was drafted by a model,
in the window, not in the message.

**Triage.** "Is this a lead, an invoice, a newsletter or a scam." The output is a
suggested tag, never an automatic file. Sorting mail out of sight on a model's say-so is
how somebody misses the one that mattered.

**Help with spam.** Deliberately last, and deliberately narrow. Not a second spam filter:
Stalwart has one and it is trained. What a model is good for here is the single message
that already looks wrong, explaining *why* in a sentence a person can act on. It pairs
with the SPF/DKIM/DMARC badge rather than replacing it.

Nothing here runs by itself. Every one of these is a button.

---

## 2. Bring your own key

**Where the key lives.** Windows Credential Manager, the same store the mail password
already uses (`Secrets.kt`). Never `settings.json`, never the accounts file, never in
this repo.

**Provider.** OpenAI-compatible chat completions, with the base URL configurable.
OpenRouter is the default because that is what the rest of the estate uses and it fronts
every model worth having, but pointing it at a local Ollama has to work on the same code
path. One client, one interface.

**What gets sent, and the thing to be honest about.** The body of the message. That is
the whole point and it cannot be avoided, so it must be visible: the first time a model
feature is used, Rampart says plainly which provider the text is about to go to and asks.
Per feature, not once globally, because summarising a thread and triaging every message
are different appetites.

**The estate rule, and why BYOK bends it.** The standing rule is that anything we give an
LLM gets an `orkeys` child key capped by `llm_map.py`, an entry in
`agent-infra/llm-registry.yaml` with a daily budget, and an Uptime Kuma monitor on vps1.
That rule exists so no service can quietly run up a bill against a shared key. A key the
user typed in is not ours to cap, so instead:

- **A running total in the app.** Tokens and estimated cost, per feature, this month,
  visible in Settings. If it cannot be attributed it should not be spent.
- **A hard monthly ceiling, set by the user, enforced client-side**, that stops the
  feature rather than warning about it.
- **On Justin's own install this is not BYOK.** It gets a proper `orkeys` child key,
  a registry entry and a Kuma push monitor like every other service, because there the
  key *is* ours. The BYOK path is for everybody else.

**What must not happen:** no model call on a timer, no model call while scrolling, no
model call on message arrival. Every one is a button, because a bill nobody pressed is
the failure mode here.

---

## 3. The API: letting something else set the mail up

The ask is an assistant that configures somebody's email for them. Filters, tags,
folders, identities.

**The important observation: most of this already has an API, and it is JMAP.** Filters
are Sieve scripts over JMAP. Tags are keywords. Folders are mailboxes. Identities are
identities. Anything holding a mailbox password or a Stalwart API key can already do all
of it without Rampart's help, and Vaultwarden already holds exactly such a key scoped to
Justin's mailbox.

So Rampart's API is not a second way to reach the server. It is worth building only
where it adds something the raw protocol does not:

- **The account is already signed in.** No key handed to a third thing, no second
  credential to rotate. The thing outside talks to the running Rampart, and Rampart talks
  to the server as the user who is already there.
- **It knows what a good filter looks like.** "Send anything from this client to that
  folder" is one sentence and a dozen lines of Sieve. The builder in section 2.2 of
  `parity.md` is where that knowledge lives, and the API should call the same code, not
  a second implementation of it.
- **It is where the approval prompt can be.** A request to change a rule can surface in
  the window and wait for a person. The raw protocol has nowhere to put that.

**Shape.** Loopback HTTP only, on a port Rampart already owns the machinery for (see
`SingleInstance.kt`, which is the same idea with less on it). Never a public listener,
never a tailnet listener, no exceptions: an API that can rewrite somebody's mail rules is
not a thing to expose to a network, and "it is only on the tailnet" is the sentence that
precedes an incident.

**Authentication.** A token generated in Settings, shown once, stored in the credential
manager, sent as a bearer. Loopback alone is not authentication: every process on the
machine can reach loopback.

**The write boundary, which is the whole design:**

| Allowed | Refused |
|---|---|
| List and create tags, folders, filters, identities | Sending anything |
| Read message headers, summaries, one message's body by id | Bulk-exporting the mailbox |
| Move, tag, archive and mark read, message by message | Deleting anything permanently |
| Read the account list | Reading the mail password or the model key |

Every write is logged in-app with what asked for it and when, and that log is readable
from the Settings screen. A write nobody can see afterwards is a write nobody agreed to.

---

## 4. MCP

Once section 3 exists, MCP is a thin server in front of it, and that is the right way
round: the boundary and the audit log belong to Rampart, and MCP is one of possibly
several ways to reach them.

**Shape.** A small server, stdio by default so the client starts it as a child process
and nothing listens anywhere, speaking to the loopback API above with the same token.
Tools that map one to one onto section 3's allowed column: `list_folders`, `create_tag`,
`add_filter`, `search_mail`, `read_message`, `tag_message`, `archive_message`. No
`send_mail` tool, for the reason in the first section, and its absence is a feature to
state in the server's own description rather than an omission to explain later.

**Read-only by default.** The write tools come on with a flag the person sets once, in
Rampart, not in the MCP client's config. The gate belongs on our side of the boundary.

**What the estate already has, so this is not from nothing.** There are ten MCP servers
on the gateway, two of them already mailbox servers (`wsg-mailbox`, `skybox7-mailbox`).
Those talk to the server directly with stored credentials, which is the model section 3
says Rampart's API should *not* replace for machine callers. Rampart's MCP server is for
the case those cannot serve: acting as the signed-in human, with a person in the window
to approve the change.

---

## 5. Order, and what gates what

1. **The Sieve rule builder** (`parity.md` 2.2). Everything in section 3 that is worth
   having depends on there being one place that knows how to write a good filter.
2. **The loopback API and the audit log** (section 3). Read-only first.
3. **Model support** (sections 1 and 2), starting with thread summaries, which is the
   one job with nothing to go wrong.
4. **Writes on the API**, with the approval prompt.
5. **MCP** (section 4) over the finished API.
6. **Draft replies and triage**, once summaries have been in daily use long enough to
   know whether the output is worth reading.

Spam help is last of the model features, not first, because Stalwart's filter is already
there and doing the job, and a second opinion that disagrees with it is worse than no
second opinion at all.
