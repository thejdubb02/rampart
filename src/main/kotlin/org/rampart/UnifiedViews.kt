package org.rampart

/*
 * Lists that cut across every account: all unread, all starred, all mail (RAM-45).
 *
 * "All inboxes" was the only one, built in Unified.kt. These three sit under it in the
 * sidebar and are put together the same way, one account at a time with each failure
 * caught on its own, but from whichever folders the person chose rather than from Inbox
 * alone. Pure, apart from the fetch it is handed, so what goes into a view can be tested
 * without a server.
 */

/**
 * One cross-account view. [id] is the pseudo folder id it travels under, next to
 * [ALL_ACCOUNTS] as the account, exactly the way "All inboxes" does; none of these is ever
 * sent to a server as a folder.
 *
 * [defaultFeed] is what feeds it until somebody chooses: unread mail is what arrived, so
 * Inbox; a star or the whole of your mail can be anywhere you file things, so Inbox,
 * Archive and Sent. Junk and Trash are in no default, because nobody wants either in a
 * list that means "my mail".
 */
internal enum class UnifiedView(
    val id: String,
    val label: String,
    val filters: QuickFilters,
    val defaultFeed: List<String>,
) {
    UNREAD("*all-unread*", "All unread", QuickFilters(unread = true), listOf("role:inbox")),
    STARRED("*all-starred*", "All starred", QuickFilters(starred = true), listOf("role:inbox", "role:archive", "role:sent")),
    ALL_MAIL("*all-mail*", "All mail", QuickFilters(), listOf("role:inbox", "role:archive", "role:sent")),
    ;

    /** The sidebar row. Not a folder on any server; see [id]. */
    fun mailbox(unread: Int = 0) = Mailbox(id = id, name = label, role = null, unread = unread)

    companion object {
        fun of(mailboxId: String?): UnifiedView? = entries.firstOrNull { it.id == mailboxId }
    }
}

/**
 * Which folders feed a view, as the person chose them: `role:inbox` for a folder the
 * server gave a role, `name:projects` for one of their own, matched by name so one choice
 * means the same folder on every account that has one.
 */
internal fun feedFolderIds(feed: List<String>, boxes: List<Mailbox>): List<String> =
    feed.mapNotNull { selector ->
        when {
            selector.startsWith("role:") -> folderFor(selector.removePrefix("role:"), boxes)?.id
            selector.startsWith("name:") -> {
                val wanted = selector.removePrefix("name:")
                boxes.firstOrNull { it.name.trim().lowercase() == wanted }?.id
            }
            else -> null
        }
    }.distinct()

/** The selector a folder is chosen by. See [feedFolderIds]. */
internal fun feedSelector(mailbox: Mailbox): String =
    if (!mailbox.role.isNullOrBlank()) "role:${mailbox.role}" else "name:${mailbox.name.trim().lowercase()}"

/** Every folder the chooser can offer, one per selector, across every account in [accounts]. */
internal fun feedChoices(accounts: Collection<List<Mailbox>>): List<Pair<String, String>> =
    accounts.flatten()
        .filter { it.role != "junk" && it.role != "trash" }
        .groupBy { feedSelector(it) }
        .map { (selector, boxes) -> selector to boxes.first().name }
        .sortedWith(compareBy({ !it.first.startsWith("role:") }, { it.second.lowercase() }))

/** One account's part in a view: its key and its folders. */
internal data class UnifiedSource(val key: String, val mailboxes: List<Mailbox>)

/** What a view came to, and how many folders could not be read for it. */
internal data class UnifiedResult(val messages: List<Summary>, val unreadable: Int) {
    /** One sentence for the line above the list, or null when nothing was missed. */
    val note: String?
        get() = when (unreadable) {
            0 -> null
            1 -> "One folder could not be read, so this list is missing its mail."
            else -> "$unreadable folders could not be read, so this list is missing their mail."
        }
}

/**
 * Puts a view together.
 *
 * [fetch] reads one folder of one account with the view's filters and answers null when it
 * could not, so one unreachable server loses its share rather than the whole list. Every
 * message is stamped with the account it came from, a message filed in two folders shows
 * once, and the view's own filter is applied again here, so a backend that ignored it
 * cannot put read mail into All unread.
 */
internal fun composeUnified(
    view: UnifiedView,
    sources: List<UnifiedSource>,
    feed: List<String>,
    limit: Int = 200,
    fetch: (key: String, mailboxId: String, filters: QuickFilters) -> List<Summary>?,
): UnifiedResult {
    var unreadable = 0
    val seen = HashSet<Pair<String, String>>()
    val all = ArrayList<Summary>()
    for (source in sources) {
        for (folder in feedFolderIds(feed, source.mailboxes)) {
            val page = fetch(source.key, folder, view.filters)
            if (page == null) {
                unreadable++
                continue
            }
            page.forEach { message ->
                if (seen.add(source.key to message.id)) all += message.copy(account = source.key)
            }
        }
    }
    val kept = all.filter { message ->
        (!view.filters.unread || !message.seen) && (!view.filters.starred || message.flagged)
    }
    return UnifiedResult(kept.sortedByDescending { it.receivedAt }.take(limit), unreadable)
}

/*
 * The preferences behind the views, as one map of text so they travel with the rest of
 * Rampart's settings (Settings.kt, "sharing", synced by SettingsSync.kt).
 *
 *   include/<account key>  "no" keeps a shared account out of every cross-account view
 *   feed/<VIEW>            the view's selectors, one per line
 *
 * A key for a shared account is [sharedKey], which carries both the login it came through
 * and the shared account's own id, so a switch set on one shared account can never land on
 * another, or on the login itself.
 */

internal fun includeKey(accountKey: String) = "include/$accountKey"

internal fun feedKey(view: UnifiedView) = "feed/${view.name}"

/** Whether an account's mail goes into the cross-account views. Your own always does. */
internal fun includedInUnified(accountKey: String, prefs: Map<String, String>): Boolean =
    !isSharedKey(accountKey) || prefs[includeKey(accountKey)] != "no"

/** The change that sets [accountKey]'s switch: the key, and its new value or null to forget it. */
internal fun includeChange(accountKey: String, included: Boolean): Pair<String, String?> =
    includeKey(accountKey) to (if (included) null else "no")

internal fun feedOf(view: UnifiedView, prefs: Map<String, String>): List<String> =
    prefs[feedKey(view)]?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { null }
        ?: view.defaultFeed

/** An empty choice goes back to the default rather than leaving a view that can show nothing. */
internal fun feedChange(view: UnifiedView, selectors: List<String>): Pair<String, String?> =
    feedKey(view) to selectors.distinct().joinToString("\n").ifBlank { null }

/** The accounts that feed the cross-account views, in the order given. */
internal fun unifiedKeys(keys: List<String>, prefs: Map<String, String>): List<String> =
    keys.filter { includedInUnified(it, prefs) }

/**
 * Unread conversations across the inboxes that feed All inboxes.
 *
 * A shared mailbox switched out of that view still has its own row. This total is what
 * the tray badge and the All inboxes row both show, so the two cannot disagree. Your own
 * accounts always count: [includedInUnified] does not let them be switched out.
 */
internal fun unifiedInboxUnread(mailboxes: Map<String, List<Mailbox>>, prefs: Map<String, String>): Int =
    mailboxes.entries.sumOf { (key, boxes) ->
        if (!includedInUnified(key, prefs)) 0
        else folderFor("inbox", boxes)?.unreadThreads ?: 0
    }
