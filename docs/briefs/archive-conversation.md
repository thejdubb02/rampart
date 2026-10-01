# Brief: Archive files the whole conversation in this folder (RAM-136)

Rampart is a Kotlin Compose Desktop mail client. Read CLAUDE.md first and match the
surrounding code: comment density, naming, and the long explanatory comments that say why.

## The bug

The reader reports that Archive "does not always work". Cause:

- The inbox lists conversations (JMAP `collapseThreads`, see Jmap.kt ~565), one row per
  thread.
- The toolbar Archive (`MessageActions.archive` = `moveTo("archive")` = `fileAway` ->
  `carryOut` in Main.kt ~2936-2990) moves only the one message whose card it sits on.
  If any other message of that conversation is still in the folder (very often the
  reader's own reply, which is what the row shows), the row stays and the click looks
  like it did nothing.
- `fileConversation` (Main.kt ~4024) builds its ids from the on-screen `thread` state.
  Before the thread has loaded that list is empty and the function silently returns.
  It also skips the reader's own messages (`conversationIds` in Threads.kt) even when one
  is in the inbox, leaving the row behind.
- Several early returns (`?: return`) give no feedback at all.

## What to build

1. `MailBackend` (MailBackend.kt): add
   `fun conversationIn(threadId: String, mailboxId: String): List<String>?` with a default
   body returning null (meaning: this backend cannot say). Add
   `fun moveFrom(ids: List<String>, fromMailboxId: String, toMailboxId: String): Applied`
   with a default body of `move(ids, toMailboxId)`.
2. JMAP (Jmap.kt): implement both.
   - `conversationIn`: one request, `Thread/get` for the id, then `Email/get` with a
     back-reference to `#ids` path `/list/*/emailIds`, properties `mailboxIds` and
     `keywords`. Return the ids whose `mailboxIds` contain `mailboxId` and which are not
     `$draft`.
   - `moveFrom`: `Email/set` update per id with patch keys
     `"mailboxIds/<from>": null` and `"mailboxIds/<to>": true`, so a message that is also
     in another folder (Sent) keeps that membership. Same `requireApplied` handling as
     `move`.
   - Shared backends and IMAP keep the defaults unless trivial (SharedBackend delegates;
     follow what it does for `move`).
3. Main.kt: one function files a conversation out of the folder on screen, used by the
   toolbar Archive, Delete (trash) and Spam (junk), by the conversation menu's Archive and
   Delete, and by mute:
   - Folder = `sourceFolder(key)`. Ids = `conversationIn(message.threadId, folder)` run on
     IO; if that is null (backend cannot say) or the message has no threadId, fall back to
     the ids of `thread` rows if `thread` contains this message, else just `message.id`.
     Always include `message.id`.
   - Keep the archive-by-year/month bucket logic of `fileAway`.
   - Keep `sayJunk`, `advancePast`, the `emails`/`thread`/`expanded`/`cards`/`filed`
     bookkeeping that `carryOut` does now, applied to every id moved. Remove rows from
     `emails` by thread as well (any row with the same account and threadId), so the
     conversation row goes.
   - Use `moveFrom(ids, folder, target)` when folder is known, else `move`.
   - Undo moves the same ids back with `moveFrom(ids, target, folder)`. Look at how
     `Undoable` / `Move` are applied and extend them minimally (eg an optional `from` on
     `Move`) rather than inventing a parallel undo.
   - No silent failure: if there is no folder for the role, `report(...)` a plain sentence
     ("This account has no Archive folder."). Keep the existing `changed` error path.
   - Ignore a second click on the same conversation while its move is in flight (a set of
     thread ids being filed), so a double click does not send two moves.
   - The single-message move menu ("Move to" a chosen folder) stays one message.
4. Delete the now-unused code paths rather than leaving two ways to archive. Keep
   `conversationIds` only if something still uses it.
5. Tests (src/test/kotlin/org/rampart): a JMAP request-shape test for `conversationIn` and
   `moveFrom` in the style of the existing Jmap tests (find them), asserting the patch keys
   and the back-reference, and a test for any pure helper you add for picking ids
   (fallback order). Each test must fail if the logic breaks.

## Rules

- Do not run gradle. Do not commit. Do not touch files outside src/ and this brief.
- No new dependencies. No em dashes or en dashes in comments or strings.
- When done, list every file changed and one line on each.
