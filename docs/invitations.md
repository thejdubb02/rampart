# Invitations and the calendar

Card RAM-94: accepting an invitation puts it in the calendar. Written 2026-09-28 against
Stalwart 0.16.24 (`crates/main/Cargo.toml`, commit `af37a23` on `main`), read from its
source. **Nothing here has been checked against a live server yet**; the last section is
the list of what to check.

## What Rampart does now

On an account whose server advertises `urn:ietf:params:jmap:calendars`, the invitation card
looks the meeting up in the calendar by UID and says where it stands: not in the calendar
yet, in it with your answer, in it at an older or newer version than the message, or marked
cancelled. It offers **Show in calendar**, which opens the Calendar page on the meeting's
day with the meeting open, and, where it fits, **Update calendar**, **Remove from
calendar** or **Remove this time**.

The three answer buttons go through the calendar first:

| The calendar holds | What an answer does |
|---|---|
| The meeting, with you on the guest list, put there by Stalwart | Sets your `participationStatus` with `sendSchedulingMessages: true`. Stalwart writes the iTIP REPLY and sends it. No email from Rampart. |
| The meeting, but an older version than this message | Brings the event up to the organiser's version first, then as above, so the reply answers the meeting as it now is. |
| Nothing, and you accepted or said maybe | Asks the server to parse the message's calendar part, creates it in the default calendar with your answer on it, and sends the reply by email. |
| Nothing, and you declined | Adds nothing, and sends the reply by email. |
| The meeting, put there by Rampart (see below) | Writes your answer to the event without scheduling, and sends the reply by email. |
| The meeting, already carrying the same answer | Sends the reply by email again. Stalwart would see no change and send nothing. |
| The meeting, but it is over | Writes your answer without scheduling and replies by email. Stalwart sends nothing for past events. |
| The meeting, but you are not on its guest list, or you organise it | Leaves the event alone and replies by email, as before. |

Every server-side failure falls back to the email reply and says so in one sentence, with
the server's own reason underneath: the calendar could not be reached, the server would not
send, the meeting could not be added. An IMAP account, or a JMAP server without calendars,
gets exactly the behaviour it had before: `InvitationReply.kt` builds the `METHOD:REPLY` and
mails it, and the card shows no calendar line at all.

Updates and cancellations from the organiser are normally applied by Stalwart before the
message is even read (next section). When the calendar is behind the message, the card
offers **Update calendar**, which copies the organiser's properties from the server's own
parse of the message and removes the ones the organiser dropped. A cancellation offers
**Remove from calendar**, which deletes the event without scheduling, so no decline goes back
to the organiser who cancelled it; a cancellation of one time of a series offers **Remove
this time**, which excludes that occurrence and leaves the rest.

These two are buttons rather than something done on opening the message, deliberately.
Stalwart only applies an update from a sender it authenticated (see below). Rampart cannot
make that judgement as well as the server can, and a forged update that quietly moves or
deletes somebody's meeting is exactly what that check exists to stop.

The code: `InvitationCalendar.kt` is the logic and holds no Compose, `InvitationCalendarTest.kt`
the check. `InvitationCalendarCard.kt` draws the line on the card and carries the jump to the
Calendar page. `answerInvitation` in `Main.kt` calls `answerInCalendar` first and sends the
email only when that did not send the reply itself.

## What Stalwart 0.16 does, and where

Paths are in the Stalwart repository. The JSCalendar conversion is the `calcard` crate,
version 0.3.14 per `Cargo.lock` (its repository at commit `9529198`).

### It adds invitations to the calendar on delivery

`crates/email/src/message/ingest.rs`, around line 363. When a message is delivered over SMTP
and scheduling is enabled, the message is not spam, the sender is authenticated, and the
account has the `CalendarSchedulingReceive` permission, every `text/calendar` part with a
`method` parameter goes to `itip_ingest`.

"Authenticated" is `crates/smtp/src/outbound/local.rs` line 48: the message was submitted by
a signed-in user of this server, or it passed DMARC. An invitation from a domain without
DMARC, or one that fails it, is never processed and stays a plain email. Rampart's own card
still handles that one.

`itip_ingest` is `crates/groupware/src/calendar/itip.rs`, from line 96:

- It looks the event up by UID in the recipient's account.
- **Not found:** only a REQUEST is imported, and only when the server setting
  `auto_add_invitations` is on, or the sender is a user of the same server, or the sender is
  in the recipient's contacts (`ContactCard` with that email). Otherwise it stops with
  `AutoAddDisabled`. An imported event goes into the default calendar
  (`get_or_create_default_calendar`, created if the account has none) with a schedule tag
  of 1, and a `CalendarEventNotification` is written alongside it.
- **Found:** `itip_process_message` in `crates/groupware/src/scheduling/inbound.rs`, line 51,
  merges it. A REQUEST replaces the changed properties and the attendee list when its
  SEQUENCE is at least the stored one, and refuses an older one with `OutOfSequence`. A
  CANCEL does **not** delete the event: it sets `STATUS:CANCELLED` on the event, or on the
  one occurrence it names, and keeps it. A REPLY to a meeting this account organises updates
  that attendee's answer.

So on Stalwart the usual picture is that the meeting is already in the calendar, answered
"needs action", before Rampart opens the message, and an update or cancellation has already
been applied. That is why the card reads the calendar rather than assuming.

### It sends the reply when the attendee's status changes

`crates/jmap/src/calendar_event/set.rs`. `sendSchedulingMessages` is a CalendarEvent/set
argument, default false (line 130). On an update (from line 430):

1. `ItipSendStatus::resolve` (`crates/groupware/src/calendar/itip.rs`, line 1048) decides
   whether anything may be sent. Scheduling disabled, no calendar address, or no
   `CalendarSchedulingSend` permission refuse the whole update as `forbidden` with a
   sentence. **An event that has ended is not refused; nothing is sent, and only the
   server's log says so.**
2. If the stored event has a schedule tag, `itip_update`
   (`crates/groupware/src/scheduling/event_update.rs`) runs. The organiser is not local, so
   `attendee_handle_update` (`crates/groupware/src/scheduling/attendee.rs`) compares the old
   and new event: an attendee may change only their own entry, and when their
   `PARTSTAT` changed (or `SCHEDULE-FORCE-SEND` is set) it builds a `METHOD:REPLY` carrying
   that attendee and queues it. Changing anything the organiser owns is refused with
   `CannotModifyProperty`.
3. **If the stored event has no schedule tag, `itip_create` runs instead**, finds this
   account is not the organiser, returns `NotOrganizer`, and that error is not one reported
   to the client (`is_jmap_error` in `crates/groupware/src/scheduling/mod.rs`, line 365). The
   update succeeds and nothing is sent.

The schedule tag is set only by Stalwart's own inbound import (`itip.rs` line 291), by an
organiser's update merged from mail (`itip.rs` around line 794), or by creating an event you
organise with scheduling. **An event created over JMAP by an attendee never gets one**, and
JMAP has no property that shows it.

That is the one real trap here, and it decides the design. When Rampart adds a meeting to
the calendar itself, it writes `scheduleAgent: "client"` on your own participant. RFC 6638
means exactly that: the client handles this attendee's scheduling. Stalwart then treats
that attendee as not server scheduled, and Rampart reads the marker next time and replies by
email rather than trusting a server that would send nothing. A later update from the
organiser that Stalwart merges replaces the attendee list, taking the marker with it, and
gives the event a schedule tag, so from then on the server answers.

### Other things read along the way

- **Lookup by UID.** `CalendarEvent/query` takes a `uid` filter
  (`crates/jmap/src/calendar_event/query.rs`, line 92). It goes through the search index,
  which is written by a background task, so a just-created event can briefly be missing.
  Rampart falls back to reading every event if the query is refused, as CalendarClient does.
- **Parsing the invitation.** `CalendarEvent/parse` (`crates/jmap/src/calendar_event/parse.rs`)
  takes blob ids and returns `parsed: { blobId: [events] }`, one entry per UID, in the same
  JSCalendar shape `CalendarEvent/get` returns. The calendar's `METHOD` is copied onto each
  entry as `method`, which must be taken off before creating: `update_calendar_event` in
  `set.rs` (around line 1057) treats `method`, `isOrigin` and `baseEventId` as immutable.
- **Participants.** calcard 0.3.14 writes `calendarAddress: "mailto:..."` on each participant
  and `organizerCalendarAddress` on the event (`src/jscalendar/import/convert.rs`, from line
  486), following the JSCalendar revision rather than RFC 8984's `sendTo`. Rampart reads both.
  Participant ids are generated unless the iCalendar carried a `JSID`, so an organiser update
  can change them; Rampart looks its own entry up again after applying one.
- **The default calendar.** `Calendar/get` reports `isDefault` for the account's stored
  default, or the lowest calendar id when none is stored
  (`crates/jmap/src/calendar/get.rs`, line 80). Reading an account's calendars creates a
  default one if it has none (`crates/groupware/src/cache/calcard.rs`, line 116).
- **isOrigin** is true when the event's organiser is one of this account's addresses
  (`crates/jmap/src/calendar_event/get.rs`, line 493). Rampart replies by email for those.
- **Deleting.** A delete with `sendSchedulingMessages: true` of an event with a schedule
  tag sends a cancel if you organise it, and a decline if you are a guest
  (`crates/groupware/src/calendar/storage.rs`, line 478). Rampart always deletes without it.
- **Outbound delivery.** Queued iMIP messages are sent by the task manager
  (`crates/services/src/task_manager/imip.rs`) through the server's own SMTP, from the
  account's address, so the reply arrives at the organiser as mail from you.
- **Stalwart also offers an HTTP RSVP link** in the invitations it sends
  (`http_rsvp_url`, `itip.rs`), for guests without a calendar. Not used here.
- **`recurrenceRule`, singular.** Already known from the Calendar page, and it holds here:
  the parse output uses the same singular form, so an event created from it round-trips.

## Known gaps

- **An event another client added over CalDAV or JMAP** has no schedule tag and no
  `scheduleAgent: client` marker, so an answer to it goes through the server path and
  Stalwart sends nothing, silently. Rampart cannot see the difference. The fix belongs in
  Stalwart (report the `NotOrganizer` case, or expose the schedule tag); until then, this is
  the case to check first if an organiser says an answer never arrived.
- **An update to one time of a repeating meeting** is not applied by Rampart. The card says
  the meeting is in the calendar and offers to show it; Stalwart merges these itself.
- **A declined meeting stays in the calendar** with your answer on it, as Stalwart keeps it.
  The Calendar page does not yet draw a declined event any differently.
- **The same answer twice** goes by email the second time, which is a re-send rather than a
  change. Setting `scheduleForceSend` would make the server resend instead, but it persists
  on the event and has not been checked.

## To check against a live server

Both test servers, as ever.

1. An invitation from an external DMARC-passing organiser, with and without
   `auto_add_invitations`: does the card say it is in the calendar, and does Accept send
   exactly one REPLY (from Stalwart, none from Rampart)?
2. The same from a sender that fails DMARC: not in the calendar; Accept adds it, the event
   has `scheduleAgent: client` on your participant, and one REPLY arrives by email.
3. Change the answer on that event: Rampart should reply by email, and Stalwart send nothing.
4. The organiser moves the meeting: Stalwart merges it; the card says in the calendar;
   answering again goes through the server.
5. Scheduling disabled on the server: Accept reports "The server would not send the
   answer" with Stalwart's sentence, the answer is still on the event, and the email goes.
6. A cancellation: the event is marked cancelled; Remove deletes it and nothing goes back.
   A cancellation of one occurrence: Remove this time excludes only that one.
7. Show in calendar opens the right account's calendar on the right day, with the meeting's
   details open, for an invitation in the unified inbox from the second account.
