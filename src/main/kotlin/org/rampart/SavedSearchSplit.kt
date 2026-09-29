package org.rampart

/**
 * How a saved search divides into child folders, if it does.
 *
 * The children are worked out from whatever the search matches, every time it is counted,
 * so a sender who writes for the first time appears as a folder and one whose last message
 * was deleted goes away. Nothing is stored for them and nothing moves on the server: a child
 * is the parent's conditions plus one more.
 */
internal enum class Split(val key: String, val label: String, val field: SearchField?) {
    NONE("none", "Do not split", null),
    SENDER("sender", "By sender", SearchField.FROM),
    LIST("list", "By mailing list", SearchField.LIST),
    TAG("tag", "By tag", SearchField.TAG),
    ;

    companion object {
        fun of(key: String?): Split = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/** One child folder of a split search: what it matches, what to call it, and its counts. */
internal data class SplitGroup(val key: String, val label: String, val total: Int, val unread: Int)

/**
 * The most children one search shows.
 *
 * A search split by sender over a busy inbox has hundreds of senders, and a sidebar with
 * hundreds of rows under one heading is not a sidebar. The largest groups are kept; the
 * rest are still in the parent, which is where they were anyway.
 */
internal const val SPLIT_LIMIT = 25

/**
 * The child folders [rows] divide into under [split], largest first.
 *
 * Sender groups by address, ignoring case, and is labelled with the name the newest
 * message gave. List groups by the identifier half of List-Id, so a list renaming itself
 * stays one folder. Tag puts a message in every tag it carries, the same way the sidebar's
 * tag list counts it. A message with no list or no tag is in no child, only the parent.
 *
 * Ties are broken by label, so two refreshes with the same mail draw the same order.
 */
internal fun splitGroups(
    rows: List<Summary>,
    split: Split,
    limit: Int = SPLIT_LIMIT,
): List<SplitGroup> {
    if (split == Split.NONE) return emptyList()
    data class Tally(var label: String, var newest: String, var total: Int, var unread: Int)
    val tallies = LinkedHashMap<String, Tally>()
    fun count(key: String, label: String, row: Summary) {
        val tally = tallies.getOrPut(key) { Tally(label, row.receivedAt, 0, 0) }
        tally.total++
        if (!row.seen) tally.unread++
        if (row.receivedAt > tally.newest) {
            tally.newest = row.receivedAt
            tally.label = label
        }
    }
    for (row in rows) {
        when (split) {
            Split.SENDER -> {
                val address = row.fromEmail.trim().lowercase()
                if (address.isEmpty()) continue
                count(address, row.from.trim().ifBlank { address }, row)
            }
            Split.LIST -> {
                val list = listIdOf(row.listId)
                if (list.isEmpty()) continue
                count(list, list, row)
            }
            Split.TAG -> tagsOf(row.keywords).forEach { tag ->
                count(tag.keyword.lowercase(), tag.label, row)
            }
            Split.NONE -> Unit
        }
    }
    return tallies.map { (key, tally) -> SplitGroup(key, tally.label, tally.total, tally.unread) }
        .sortedWith(compareByDescending<SplitGroup> { it.total }.thenBy { it.label.lowercase() }.thenBy { it.key })
        .take(limit)
}

/**
 * The saved search a child folder stands for.
 *
 * The parent's conditions and one more, in an "all of", so opening a child runs through
 * exactly the same path as opening any saved search. The id is derived from the parent and
 * the group, so the same child keeps the same id between counts and stays selected while
 * mail arrives.
 */
internal fun childSearch(parent: SavedSearch, group: SplitGroup): SavedSearch {
    val field = parent.split.field ?: return parent
    val base = parent.condition ?: legacyCondition(parent.query, parent.filters)
    return parent.copy(
        id = parent.id + "/" + parent.split.key + "/" + group.key,
        name = group.label,
        query = "",
        condition = Condition.Group(Joiner.ALL, listOf(base, Condition.Match(field, group.key))),
        split = Split.NONE,
        parentId = parent.id,
    )
}

/**
 * The sidebar's list: every saved search, each split one followed by its children.
 *
 * Children come from [groups], keyed by the parent id, which is whatever the last count
 * found. A parent with no entry has no children drawn yet, rather than stale ones.
 */
internal fun withSplitChildren(
    searches: List<SavedSearch>,
    groups: Map<String, List<SplitGroup>>,
): List<SavedSearch> = searches.flatMap { search ->
    if (search.split == Split.NONE) listOf(search)
    else listOf(search) + groups[search.id].orEmpty().map { childSearch(search, it) }
}
