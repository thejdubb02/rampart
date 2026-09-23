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

/**
 * Which account a card, a selection or a removal belongs to, plus the message.
 *
 * Stalwart ids are short and start again for each account, so the id on its own
 * is not an identity. Two accounts, even on two servers, can share one.
 */
internal data class CardKey(val account: String, val id: String)

/**
 * A stable token for a row in a list that may mix accounts.
 *
 * A folder that belongs to one account leaves the id as it was, so a selection
 * made there still matches. A merged row carries the account, because the same
 * id in two accounts is two messages.
 */
internal fun rowToken(message: Summary): String =
    if (message.account.isBlank()) message.id else message.account + "\u0000" + message.id

/** Same message. A blank account matches, because a folder row does not carry one. */
internal fun Summary.sameMail(other: Summary): Boolean =
    id == other.id && (account.isBlank() || other.account.isBlank() || account == other.account)

internal fun Summary.sameMail(account: String, id: String): Boolean =
    this.id == id && (this.account.isBlank() || this.account == account)

internal fun Summary.sameMail(account: String, ids: Set<String>): Boolean =
    this.id in ids && (this.account.isBlank() || this.account == account)
