# Open tracking, the way Mailsuite does it

Asked for on 2026-09-17. This reverses a line in `roadmap.md` that said we were not doing
it, so the reversal is recorded here rather than quietly edited: the reason for not doing
it was that the feature spec warned against copying competitors blindly, and "the business
runs on knowing whether outreach was read" is a better reason than that was.

Not built. This is the design.

---

## What it is, said plainly

A 1x1 transparent image in a message you send, at an address unique to that message. When
the recipient's mail client fetches that image, our server records it, and Rampart marks
the message in Sent as opened.

That is the whole mechanism, and it is what Mailsuite, HubSpot, Mailchimp and every sales
tool do. It is worth saying in those words inside the app, because a feature that is coy
about how it works is a feature nobody can make an informed decision about.

---

## Why not just use Resend, which we already have

Resend's open tracking is on for `willhitestrategy.com` and `.org`, and
`wsg-mail-open-label` on vps1 already turns its webhooks into a `$label:opened` keyword on
the matching message in Sent. That is real and it works. Two reasons it does not answer
this:

**It only covers mail sent through Resend.** Rampart submits through Stalwart, so nothing
it sends is tracked today. Routing Rampart's outbound through Resend to get tracking would
be a much larger change than adding a pixel, and it would put every hand-written reply
through a bulk sender.

**Resend rewrites the Message-ID**, which `wsg-mail/docs/open-tracking.md` records as
verified rather than assumed. There is no shared key between the open event and the copy
in Sent, so matching is a heuristic on recipient, subject and time, and it mis-tags when
two identical subjects go to the same person minutes apart. A pixel we mint ourselves
carries an id we chose, so the match is exact. Our own is the more accurate of the two.

---

## Design

### The id

One opaque random id per tracked message, 128 bits, generated in the client at send time.
Not derived from the recipient, the subject or the time, because an id that encodes who it
was for is a leak to anyone who sees the URL, including every mail server it passes
through.

The client keeps `id -> (message id, recipient, subject, sent at)` locally. The server
keeps only the id and when it was fetched. Nothing on the server needs to know who the
message was to, so it does not get to.

### The endpoint

A tiny service on vps1, behind the same nginx that fronts the mail host.

| | |
|---|---|
| URL | `https://<a short host>/o/<id>.gif` |
| Answers | a 1x1 transparent GIF, 43 bytes, `Cache-Control: no-store` |
| Records | the id, the time, the user agent, the requesting IP's network, nothing else |
| Gated | **never.** A gate answers a machine caller with a 302 that reads as success |

Not on `mail.willhitestrategy.org`. A hostname that says "mail" in a tracking URL is a
tell, and some filters score it. A short neutral host on a domain we already own.

### Getting the open back to the client

The same shape as the existing bounce and open labelling, and for the same reason: a
keyword on the message in Sent is the one place both Rampart and Bulwark can see it, and
it survives Rampart being closed.

The service sets `$label:opened` on the Sent copy through the management token that
`/root/stalwart_jmap.py` already holds, which was verified to work on any account before
`wsg-mail-open-label` was built. So this adds no new secret.

Rampart shows it as a tag on the row and a line on the message: "Opened 14:32 today", and
on hover the count. It does not need to poll anything; the keyword arrives with the push
that is already running.

### In the composer

**Off by default, and per message.** A toggle next to Send, remembered per recipient
domain so outreach can be on and your accountant can be off without thinking about it
every time. Never a global "track everything": tracking every message you write to your
family is a different thing from tracking a sales email, and a single switch pretends
they are the same.

The composer says it in one line when it is on, so there is no chance of sending a tracked
message without knowing.

---

## What it cannot tell you, which the UI has to say

This is the part most tools are quiet about, and being quiet about it is how somebody
chases a lead who never read anything.

- **Gmail fetches every image through Google's proxy**, often at delivery rather than when
  a person looks. An open can mean "Gmail received it".
- **Apple Mail Privacy Protection pre-fetches everything** for everyone who has it on,
  which is most iPhone users. Those opens are close to meaningless.
- **Outlook blocks remote images by default.** No open ever fires, however carefully it
  was read.
- **Rampart itself blocks them**, which is worth noticing rather than hiding: we hold back
  remote images until the reader allows that sender, so a message we send to another
  Rampart user registers nothing. That is not a bug in either half.

So: **an open is evidence, not proof, and no open is not evidence of anything.** The UI
says "Opened" where it happened and says nothing at all where it did not, rather than
"Not opened", which is a claim we cannot support.

---

## What it will not do

- **No tracking of incoming mail.** Rampart blocks pixels aimed at its own reader and that
  does not change. This feature is about mail we send.
- **No click tracking.** Rewriting the links in somebody's message to route through our
  server breaks them when the server is down, makes every link look like a redirector to a
  spam filter, and is a much bigger promise than a pixel.
- **No tracking on a message to a list**, and no per-recipient pixels on one message. One
  message, one id.
- **Nothing reaches a third party.** The endpoint is ours, on our own box.

---

## Order

Sits behind the rich-text composer and the filters, because the composer is where the
toggle goes and building the toggle before the composer it lives in is backwards. It is
independent of everything else, so it can move if it turns out to matter more than the
list of ten says.

---

## Mailsuite's actual feature list, gone through

Justin sent over the full breakdown of what Mailtrack (Mailsuite's tracking product) sells.
This is every item in it with a verdict, because "similar to Mailsuite" covers a document
analytics product and a bulk sender as well as a pixel, and those are three different
decisions.

The thing that shapes most of the answers: **Mailtrack is a Gmail extension.** It has no
server of its own between you and the recipient, so several of its features exist to work
around not owning the stack. We own the stack, so some of this is easier for us and some
of it is somebody else's job here already.

### Doing, with the tracking itself

| Theirs | Ours |
|---|---|
| Unlimited open tracking | Yes. No plan, no cap, our own endpoint |
| Open time and date | Yes |
| Open count | Yes |
| Full open history | Yes, all of it. They meter history by plan; we have no reason to |
| First-open timestamp | Yes |
| One check sent, two checks opened, in the list | Yes, and it is a good idea worth taking |
| Hover for detail, click through to the full history | Yes |
| Real-time open notification on the desktop | Yes |
| Self-open prevention, their "debeaconizer" | Yes, and more simply: **the copy in Sent never contains the pixel at all.** We write that copy ourselves, so there is nothing to strip and nothing to get wrong |
| Bot and prefetch filtering | Yes, and it is the difference between useful and misleading. See below |
| Tracking on by default | **No. Off, per message, remembered per recipient domain.** Theirs is on by default on desktop. Say the word and it flips |
| "Sent with Mailtrack" footer on the free plan | No |

An open notification also goes to Justin's phone through Herald on the `mail` tag, which
already carries the bounce alerts, so that part is wiring rather than building.

### Bot filtering, since it is the whole difference

An open that fires two seconds after you sent it was not a person. Neither was one from
Google's image proxy at delivery time, nor Apple Mail Privacy Protection, which pre-fetches
everything for everyone who has it on, nor a corporate filter opening every link and image
to check them.

So the log records every fetch, and the UI sorts them into **opened** and **fetched by
something automatic**, by user agent, by network, and by how soon after the send it
happened. Where it cannot tell, it says so. This is the one part where being conservative
matters more than being generous: an open count that includes scanners is a number that
makes somebody chase a lead who never read anything.

### Doing, once there is somewhere to keep the log

These all need the local store (roadmap 2.3), so they come after it rather than with the
pixel:

- **A tracking view**: everything sent tracked, opened or not, open rate, filterable, CSV
  out. Their "activity dashboard" and "campaign report" collapse into one screen for us,
  because we are not running campaigns.
- **Not-opened follow-up reminders** at 24, 48 or 72 hours, and **no-reply reminders**.
  A reminder to Justin, never an auto-send. Nothing client-facing sends itself.
- **Revival alerts**, an old message reopened after a long gap, and **open spikes**. Cheap
  once the log exists, genuinely useful on outreach, worth nothing before then.

### Doing, and it is a trust feature rather than a tracking one

**Flagging trackers in mail we receive.** Mailtrack marks incoming tracked mail with a
green dot. We are most of the way there already without meaning to be: Rampart blocks
remote images until you allow a sender, and it knows which images are remote. Naming the
ones that are a tracking pixel, and who they report to, is a short step from there and
belongs in the authenticity pane with SPF and DKIM.

Worth being straight about the asymmetry: we are building a tracker and a tracker-blocker
in the same application. That is the right way round. Blocking is the default for everyone
who receives mail from us or through us; tracking is a thing you switch on per message,
knowing what it is.

### Not doing, each for a reason

- **Link and click tracking.** Rewriting the links inside somebody's message to route
  through our box breaks every one of them when the box is down, makes our mail look like a
  redirector to a spam filter, and is the part recipients resent most. If it ever matters,
  the compromise is **one** link you deliberately mark, never "track all links by default".
- **Document analytics**: pages viewed, time spent, percent read, expiry, revoke, block
  download, confidential watermarks, eSignatures. This is a document hosting product with a
  viewer, not a mail feature, and it only works by replacing the attachment with a link to
  our server, which changes what the email is. Out of scope, and if it is ever wanted it is
  its own application.
- **Campaigns and mail merge**, their ten thousand recipients at a time. **Meridian already
  does this**, through Resend, where the deliverability, the bounce handling and the
  suppression list live. A desktop client firing bulk mail through a personal mailbox is
  how a sending domain gets burned. Rampart tracks mail a person wrote.
- **Who opened, inside one group send.** One message carries one pixel, so it can only ever
  say *somebody* opened it. Mailtrack does this by sending a separate copy per recipient,
  which is a campaign, which is the point above. The UI says "someone in this thread",
  because the honest version of this number is the only version worth having.
- **Delivery certificates.** We can print what our log holds. We cannot certify that mail
  was delivered, and a document that looks legal and is not is worse than no document.
- **CRM, Zapier, Salesforce sync.** The MCP server is the integration story: one boundary,
  outside the process, already planned.
- **Mobile, and a browser extension.** Rampart is a desktop client.

### Order

Two pieces, and they land in different places.

**The pixel itself** goes with templates, read receipts and undo-send (roadmap 2.8), and
after the composer, because the toggle lives next to Send. Opens, counts, history, the two
check marks, the notification and the bot filtering are all in that piece.

**The tracking view, the reminders and the revival alerts** come after the local store
(2.3), because they are queries over a log and there is nowhere to keep one before that.

**Flagging incoming trackers** goes with the trust work in tier 3, next to the authenticity
pane, and is independent of both.
