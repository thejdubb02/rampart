# Performance

What Rampart costs to run, measured, with the target each number is held to and how to
measure it again. Kaneo RAM-44. The local store half (50,000 messages) was measured first
and is in `roadmap.md` under 2.5; this is the rest: talking to the server, the message
list, opening a message and memory.

Measured on 2026-09-29, on a GitHub Actions `ubuntu-latest` runner, against a fake JMAP
server on the same machine (`FakeJmapServer.kt` in the tests) that adds 40 ms to every
request, which is a realistic round trip to a server in the same country. Real servers
also take time to answer; these numbers are what Rampart itself adds on top.

## How to rerun

On any machine that can build Rampart:

    ./gradlew test -Drampart.bench=true --rerun-tasks \
      --tests org.rampart.RoundTripTest --tests org.rampart.OpenTimingTest \
      --tests org.rampart.ListPagingTest --tests org.rampart.ListWindowTest \
      --tests org.rampart.SoakTest --tests org.rampart.StoreBenchmarkTest

The numbers are printed with the test output. Or on GitHub, without a build machine:

    gh workflow run build.yml --field release=false --field bench=true

and read them in the log of the step that runs the measurements.

Without `-Drampart.bench=true` the round trip, open, list window and soak tests still run
on every build and hold their budgets, and only the 50,000 message timings are skipped.

## Round trips per action

The requests one action sends to the server. A round trip is the thing a slow connection
makes you wait for, so this is the number that matters most away from home. "Requests" are
JMAP API calls; "trips" also counts picture downloads. Before is main at `b401cd8`,
measured the same way before any of this changed.

| Action | Before | After | Held to |
|---|---|---|---|
| Start-up, 3 accounts: folders and identities | 6 requests, one after another, 279 ms | 3 requests at once, 45 ms | 1 request an account, and 3 accounts in under twice the time of 1 |
| Open a message, cold: message, thread, pictures | 4 requests and 3 pictures one at a time, 265 ms | 2 requests and 3 pictures at once, 91 ms | 2 requests |
| Open a message with a kept copy, after the account moved | 2 requests, 90 ms | 1 request, 42 ms | 1 request |
| New mail arrives: the poll, then the list reloading | 5 requests, 229 ms | 4 requests, 172 ms | 4 requests |
| Unified inbox, 3 accounts | 3 requests one after another, 145 ms | 3 requests at once, 48 ms | 3 accounts in under twice the time of 1 |
| Contacts page | 2 requests, 90 ms | 1 request, 45 ms | 1 request |
| Signing in to saved accounts at launch | one account after another | all at once | not measured here: the fake server has no session endpoint |

What changed, and where:

- **Batched with back references** (`Jmap.kt`): a conversation's Thread/get and the
  Email/get of its messages; Mailbox/get with Identity/get; a folder page with Mailbox/get
  for the poll; the account state with a message's stamp; AddressBook/get with
  ContactCard/get. `MailBackend` names each pair (`startup`, `pageAndFolders`,
  `stateAndStamp`, `booksAndContacts`) and IMAP keeps asking them one after the other.
- **State from the answer that already carried it**: every Email/get response has the
  account's state on it, so opening a message no longer asks for it again to stamp the
  copy kept on disk. The read ahead now keeps that state too, which lets its copies be
  trusted on the first click.
- **Side by side** (`Main.kt`, `Pictures.kt`): accounts at start, the unified inbox, and a
  message's inline pictures when each declares its size.

`RoundTripTest` fails if any of these grows by a single request.

## The message list

| What | Measured | Target |
|---|---|---|
| Rows composed with 50,000 held, 900 px tall | 10 | under 60 |
| A page of 100 from the copy, at row 0, 10,000, 25,000 and 49,900 | 0.6 ms at each | under 5 ms |
| A page of 100 from the copy before, at row 0 and row 49,900 | 30 ms and 90 ms | |
| Scrolling a 50,000 message folder to the bottom a second time | 0 server requests, 403 ms in all | no server requests while nothing changed |
| Scrolling it the first time | 499 pages from the server | |
| Sorting 50,000 loaded rows by sender or subject | 46 to 49 ms, once per change | once per change, not per frame |
| Memory held once scrolled to the bottom | 29 MB, 581 bytes a row | see below |

The list is a `LazyColumn` keyed per message, so only the rows on screen are composed
(`ListWindowTest` draws the real `MessageList` offscreen with 50,000 rows and counts).

Pages after the first used to come from the server every time, even for a folder read to
the bottom yesterday and unchanged since. The copy now records the ids the server sent for
each page, in its order, at the state they were read (`paged` and `paged_id` in
`Store.kt`, used by `ListPaging.kt`). While the account's state is the same, scrolling
reads pages back by those ids. Anything that moves the state sends the next reload to the
server for the first page, and the record starts again from there. Recording ids rather
than trusting the copy's own order means a message the copy still holds but the server has
deleted can never be paged in (`ListPagingTest` plants one to check).

Two things were costing frames. Every redraw of the list sorted every loaded row again,
and sorting by subject runs a regular expression per comparison; the sorted list is now
remembered per change of rows or order. And a page from the copy was LIMIT and OFFSET over
the folder, which sorted all of it and then stepped over every earlier row; by ids it is a
handful of primary key lookups at any depth.

**Not done: the rows already scrolled past stay in memory.** Composition holds only what
is on screen, but the list's data grows by a page each time it asks for more, so a folder
scrolled to row 50,000 holds all 50,000 summaries, 29 MB. Most sessions never scroll that
far. Holding a sliding window instead touches selection, keyboard movement and select-all,
which all work by position in that list, and is its own piece of work.

## Opening a message

A newsletter shaped message: about 60 KB of nested tables and three inline 450 KB
pictures. Median of 20 opens after 5 to warm up, at 40 ms a request.

| Part | Measured | Target |
|---|---|---|
| Fetch: one Email/get | 44 ms | one round trip |
| Pictures: three, side by side | 49 ms | one round trip, not three |
| Local work: building the page | 36 ms | under 50 ms |
| Cut from local work: decoding the pictures to bitmaps | 8 ms | 0 |
| Render: the engine loading and laying out the page | recorded live as `message.open.render` | |

Click to on screen in the app is also recorded, as `message.open.total` and
`message.open.fetch`, and all three show on the Diagnostics settings page from real use.
The render time was defined there before and never recorded; `WebBody.kt` now records it
from the document being handed over to the engine reporting it loaded, because it needs a
window and cannot be measured on a build machine.

Taken off the path to the page appearing:

- The second request for the account's state after every open (see round trips).
- Decoding every inline picture to a bitmap. The engine draws from the bytes in the
  document; only the plain renderer and the click-to-preview used a bitmap, and both now
  decode the bytes themselves when they are the ones drawing. It also stopped each open
  card holding four bytes a pixel per picture.
- Marking tracked messages as replied, and telling the companion to stop alerting, which
  waited on the local store and a network call before the rest of the conversation could
  appear. It now runs beside it.
- The thread's second request.

## Memory

| What | Measured | Target |
|---|---|---|
| Heap growth over 500 messages opened in a row, after warming up | 0.1 MB | under 12 MB |
| Heap every 100 opens | 20.1, 20.1, 20.2, 20.2, 20.2 MB | flat |
| Remembered message heights after 500 opens | 200 | capped at 200 |

`SoakTest` does everything an open does outside the window: the fetch, the pictures, the
page, the copy kept with its pictures (capped at 5 MB a message, and on disk), the height
remembered. The window itself, its web views and the card state, needs a display, and this
environment could not start one against a full build. What was checked by reading instead:

- The cards are one conversation at a time and are reset on every switch.
- Their load counters were not: one entry per message opened, for the whole session. They
  are now cleared with the cards.
- Each message's page is written to a temporary file that is kept until exit, on purpose
  (`WebBody.kt` explains why: pictures load from beside it while it is on screen), and
  each one adds a path to the JDK's delete-on-exit list. A few hundred bytes of heap and
  the page's size on disk per message opened. Not changed here; worth a bounded sweep that
  never touches the page on screen.

## Also found, not changed

- New mail still costs 4 requests: the list reload asks for the state the poll read a
  moment before. Passing it through would make it 3.
- The poll checks accounts one after another. With several accounts that is one state
  request each, in turn, every 30 seconds.
- Drawing the first frame of a 50,000 row list took 851 ms on the build machine, most of
  it the JVM and Compose starting up inside the test. Worth measuring in the running app.
