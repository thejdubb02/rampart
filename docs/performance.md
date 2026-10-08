# Performance

What Rampart costs on a large mailbox, measured, with the line each number has to stay
under. A slowdown is a number that crossed its line, not a feeling. Card RAM-44.

## Running it

The measurements are ordinary tests that only print their numbers when asked:

    ./gradlew test -Drampart.bench=true --rerun-tasks \
      --tests org.rampart.RoundTripTest --tests org.rampart.OpenTimingTest \
      --tests org.rampart.ListPagingTest --tests org.rampart.ListWindowTest \
      --tests org.rampart.SoakTest --tests org.rampart.StoreBenchmarkTest

Or on CI: run the build workflow by hand with `bench` ticked. Every mailbox is generated in
a temporary directory and thrown away. Nothing here reads real mail.

Timings move by tens of percent between runs and between machines. Read a number against
its line, and treat a doubling as a regression and a 20% wobble as noise.

## The numbers

Measured 2026-10-06 on a Linux workstation, 50,000 messages in one folder.

| What | Measured | Line |
|---|---|---|
| Folder, first page from the local copy | 6 ms | 50 ms |
| Folder, page 20 | 11 ms | 50 ms |
| One page from the copy, anywhere in the folder | 1 to 2 ms | 20 ms |
| First scroll to the bottom, local work for 499 pages | 5.6 s (11 ms a page) | 25 ms a page |
| Second scroll to the bottom, all from the copy | 0.65 s | 2 s |
| Search, a common word | 37 ms | 100 ms |
| Search, a rare word | under 1 ms | 20 ms |
| Filters (unread, starred, tagged, known sender, combined) | 2 to 11 ms | 50 ms |
| Saved search count | 36 ms | 100 ms |
| Unified inbox merge | 15 ms | 50 ms |
| Writing 500 messages to the copy | 26 ms | 100 ms |
| Sort 50,000 rows by sender / subject | 52 / 70 ms | 200 ms |
| 50,000 rows held in memory | 33.5 MB | 60 MB |
| Message list first frame, 50,000 rows | 1.7 s, 10 rows composed | rows composed stays at what fits on screen |
| Opening a message at 40 ms a request | about 170 ms in all | 300 ms |
| 500 messages opened in a row | heap grows 0.1 MB | 5 MB |

Round trips are counted rather than timed, because the network is the cost there and a
count does not depend on the machine: start-up is one request an account, a cold open is
five, and a kept copy is one. `RoundTripTest` prints the full table.

## What was found, 2026-10-06

The first run said scrolling a 50,000 message folder to the bottom the first time cost
**64 seconds** of local work, and the cost per page grew the further down it went. Two
causes, both in writing each page to the local copy:

1. **The search index was scanned once per page.** Its `id` column is `UNINDEXED` in FTS5,
   so `DELETE FROM search WHERE id IN (...)` read every row in the index to replace a
   hundred. Search rows now share the message row's `rowid` and are deleted by it, which
   is a lookup. Older files are rekeyed once on opening (`PRAGMA user_version` 1). The
   file must never be `VACUUM`ed, which renumbers rowids.
2. **Every commit waited for the disk.** Two commits a page, each a full sync. The store
   now uses a write-ahead log with `synchronous = NORMAL`. A power cut can lose the last
   few writes to a copy the server refills anyway.

64 s became 27 s after the first fix and 5.6 s after the second.

## Not measured yet

- **A full sync from empty against a real server.** The tests above stand in for the
  server. The page count and the local work are known, the wire time is not.
- **Memory after a day.** The soak opens 500 messages and checks the heap, which catches
  a leak per message. A window left open overnight is still unmeasured.

## Memory with the app left open (2026-10-08)

The real app ran against the test mailbox under a virtual display for 37 minutes, sampled
every minute. Process memory (including the embedded browser engine, which lives outside
the Java heap) moved between 1.1 and 1.7 GB, the Java heap between 170 and 655 MB, and the
thread count held at 125 to 126. Both rose and fell with garbage collection and showed no
steady climb. The run was cut short at 37 of the planned 60 minutes when the session ended.
