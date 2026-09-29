package org.rampart

/*
 * Scrolling a long folder, from the copy on disk where the copy can answer.
 *
 * The first page of a folder has always come from the copy first. Every page after it went
 * to the server, even for a folder read to the bottom yesterday and not changed since, so
 * scrolling thirty thousand messages was three hundred round trips each time. These two
 * functions let a page come from the copy when it is known to be exactly what the server
 * would say, and teach the copy each new page the server hands over.
 *
 * "Known" means the store's paging mark (see [Store.pagedThrough]) was written at the same
 * state as the folder's cursor, which is the state the list was last read at. The state is
 * account-wide, so a match means nothing has moved. When something has, the next reload
 * reads the first page again and the mark starts over from there. Until that reload runs,
 * which a push or the next poll brings within seconds, a page from the copy can be one
 * change behind; the list already drops ids it is showing, so a shift at a page seam
 * cannot show a message twice.
 *
 * Only for the plain folder, newest first and unfiltered, because that is the order the
 * mark describes. A filtered list is always asked of the server.
 */

internal const val LIST_PAGE = 100

/** A page from the copy, or null when only the server can say what is there. */
internal fun pageFromCopy(store: Store?, mailbox: String, offset: Int, limit: Int = LIST_PAGE): List<Summary>? {
    store ?: return null
    val state = store.cursor(mailbox) ?: return null
    val (markedAt, count) = store.pagedThrough(mailbox) ?: return null
    if (markedAt != state || offset + limit > count) return null
    // By the ids the server gave, at their positions, fetched by primary key. Measured at
    // fifty thousand messages, a page by LIMIT and OFFSET over the folder was 30 ms at the
    // top and 90 ms at the bottom, because each one sorted the whole folder again and
    // stepped over every row before it.
    val ids = store.pagedIds(mailbox, offset, limit)
    if (ids.size != limit) return null
    // A row the copy has lost since, which only a cleared cache does, sends it to the server.
    return store.rows(ids).takeIf { it.size == limit }
}

/**
 * The first page, just read from the server at [state]. The copy now matches the top of
 * the folder that far, and nothing further down is vouched for.
 */
internal fun markFirstPage(store: Store?, mailbox: String, state: String?, page: List<Summary>) {
    if (store == null || state == null) return
    store.markPaged(mailbox, state, 0, page.map { it.id })
}

/**
 * A page the server just handed over at [offset]. Kept, and the mark moved down past it,
 * only when it carries straight on from the mark at the same state: a page read after
 * something moved, or one that skipped ahead, would vouch for rows nobody checked.
 */
internal fun extendCopy(store: Store?, mailbox: String, offset: Int, page: List<Summary>) {
    store ?: return
    if (page.isEmpty()) return
    val state = store.cursor(mailbox) ?: return
    val (markedAt, count) = store.pagedThrough(mailbox) ?: return
    if (markedAt != state || offset != count) return
    store.put(mailbox, page)
    store.markPaged(mailbox, state, offset, page.map { it.id })
}
