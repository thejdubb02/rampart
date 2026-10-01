# Brief: Reply bar pinned under an open conversation (RAM-137)

Rampart is a Kotlin Compose Desktop mail client. Read CLAUDE.md first and match the
surrounding code.

## The problem

A conversation with several messages is drawn as a stack (Main.kt ~6560-6640, `stacked`,
one shared `stackScroll`). Each open card draws its own ReadingToolbar (Reply, Reply all,
Forward) at its top, inside the scroll. To answer the newest message the reader has to
scroll past a long earlier message to find it.

## What to build

- Only when `stacked`: under the scrolling Column (outside it, so it never scrolls), a
  slim bar: a top HorizontalDivider, then a Row with three ToolText-style buttons
  "Reply", "Reply all", "Forward" (reuse `ToolText` from ReadingToolbar.kt; make it
  `internal` if it is private). Same look as the toolbar buttons. Left aligned, padding 20dp
  horizontal, 8dp vertical, background the same surface the pane uses so mail does not show
  through.
- They act on the newest message in `thread` that was not written by one of the account's
  own identities, falling back to the newest message. Wire them to exactly the same handlers
  a card's toolbar Reply / Reply all / Forward use for that message (find how renderCard
  passes `onReply` / `onForward`; reuse, do not duplicate the reply logic). "Reply all" is
  enabled under the same condition the card's toolbar uses.
- A short label before the buttons naming who it replies to, eg "Reply to Dana Smith",
  in bodySmall, outline colour, so it is clear which message the bar answers. Keep it on
  one line with ellipsis.
- Not shown in the single-message view (its toolbar is already outside the scroll), and not
  shown while the composer panel is replying to this conversation if that is easy to know;
  otherwise always shown.

## Rules

- Do not run gradle. Do not commit. Touch only Main.kt and ReadingToolbar.kt unless
  unavoidable.
- No em dashes or en dashes in comments or strings.
- List every file changed with one line on each.
