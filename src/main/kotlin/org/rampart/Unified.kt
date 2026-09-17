package org.rampart

/**
 * The account key that stands for every account at once.
 *
 * A real key is `you@example.org@mail.example.org`, so no account can collide with this and
 * nothing has to be nullable to say "not one account in particular". It is never sent to a
 * server: every call that reaches one goes through the account of the message it is about.
 */
internal const val ALL_ACCOUNTS = "*all*"

/** The sidebar row for it. Not a mailbox on any server, and never asked for by id. */
internal fun allInboxes(unread: Int) =
    Mailbox(id = ALL_ACCOUNTS, name = "All inboxes", role = "inbox", unread = unread)

/**
 * Every account's inbox as one list, newest first, each message remembering where it came
 * from so that replying to it, filing it or starring it goes to the right server.
 *
 * Sorted on the timestamp as text rather than by parsing it. JMAP dates are always
 * `2026-09-15T17:40:00Z`, fixed width and always UTC, so they sort as strings in the same
 * order they sort as times, and parsing several hundred of them on every refresh to learn
 * what the string already says would be work for nothing.
 *
 * Capped, because two full inboxes merged is twice as long a list as anyone scrolls, and
 * the list is re-sorted every time mail arrives.
 */
internal fun merged(byAccount: Map<String, List<Summary>>, limit: Int = 100): List<Summary> =
    byAccount.flatMap { (key, list) -> list.map { it.copy(account = key) } }
        .sortedByDescending { it.receivedAt }
        .take(limit)
