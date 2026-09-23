package org.rampart

/**
 * A cached list row reduced to the two fields a page prune needs.
 *
 * The date is the JMAP timestamp, which is fixed width and UTC, so it compares
 * as text in the same order it compares as a time.
 */
internal data class DatedId(val id: String, val receivedAt: String)

/**
 * Cached rows a fresh first page has shown are no longer in the folder.
 *
 * Only a date inside the page, inclusive. A row older than the oldest message
 * on the page was not part of this fetch, and deleting it would drop mail the
 * server still has, further down the folder. An empty page has no range, so it
 * drops nothing: the caller decides what an empty folder means.
 */
internal fun idsMissingFromPage(cached: List<DatedId>, page: List<DatedId>): List<String> {
    if (page.isEmpty()) return emptyList()
    val oldest = page.minOf { it.receivedAt }
    val newest = page.maxOf { it.receivedAt }
    val present = page.mapTo(HashSet()) { it.id }
    return cached.filter { it.receivedAt in oldest..newest && it.id !in present }.map { it.id }
}

/**
 * One account's store, and the real folder the rows belong in.
 *
 * [ids] are the messages to write, not the whole list on screen.
 */
internal data class MailWrite(val account: String, val mailbox: String, val ids: List<String>)

/**
 * Where a read or unread change is written back.
 *
 * The merged list is not a mailbox. Writing it under "*all*" puts every
 * account's rows into one store and, because a saved row's folder is replaced
 * on write, moves real inbox mail onto a folder that does not exist. Each
 * changed row goes to the account it came from, under that account's own inbox.
 * A normal folder writes to itself.
 */
internal fun seenCacheWrites(
    unified: Boolean,
    account: String,
    folderId: String?,
    changed: List<Summary>,
    inboxId: (String) -> String?,
): List<MailWrite> {
    if (changed.isEmpty()) return emptyList()
    if (!unified) {
        val box = folderId ?: return emptyList()
        return listOf(MailWrite(account, box, changed.map { it.id }))
    }
    return changed.groupBy { it.account }.mapNotNull { (key, rows) ->
        if (key.isBlank()) return@mapNotNull null
        val box = inboxId(key) ?: return@mapNotNull null
        MailWrite(key, box, rows.map { it.id })
    }
}

/**
 * Junk and Deleted stay out of a search of everything, unless the folder on
 * screen is one of them.
 *
 * Searching from the inbox should not turn up spam. Searching from Junk should
 * still find the spam, which is the only place it is.
 */
internal fun foldersToSkip(currentRole: String?, junkId: String?, trashId: String?): List<String> =
    listOfNotNull(
        junkId?.takeIf { currentRole != "junk" },
        trashId?.takeIf { currentRole != "trash" },
    )
