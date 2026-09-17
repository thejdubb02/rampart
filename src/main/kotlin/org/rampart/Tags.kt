package org.rampart

/** A keyword as a person sees it. */
internal data class Tag(
    /** The keyword exactly as it is stored on the server, eg "$label1" or "invoices". */
    val keyword: String,
    /** What to show, eg "Invoices". */
    val label: String,
    /** 0xAARRGGBB, stable for a given keyword. */
    val color: Long,
)

/**
 * Protocol state, not labels. Showing $seen as a chip is how a mailbox looks like it
 * has a tag named Seen, which no client put there.
 */
private val RESERVED = arrayOf(
    "\$seen", "\$flagged", "\$draft", "\$answered", "\$forwarded",
    "\$junk", "\$notjunk", "\$phishing", "\$deleted", "\$recent",
)

/** The user's own tags, in a stable order, from the keywords on a message. */
internal fun tagsOf(keywords: Collection<String>): List<Tag> {
    val tags = ArrayList<Tag>()
    for (keyword in keywords) {
        if (isReserved(keyword)) continue
        if (tags.any { it.keyword.equals(keyword, ignoreCase = true) }) continue
        tags.add(Tag(keyword, labelOf(keyword), colorOf(keyword)))
    }
    // Same message, same row. Sorting by keyword would reshuffle when another
    // client stored $label1 vs label1.
    return tags.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

/** Whether a keyword the user typed can be stored. */
internal fun validKeyword(text: String): String? {
    val keyword = text.trim()
    if (keyword.isEmpty() || keyword.length > 64) return null
    if (isReserved(keyword)) return null
    for (ch in keyword) {
        val code = ch.code
        // Printable ASCII only, and never space: a quote or a space in a keyword
        // is how a malformed IMAP command or a broken JSON key gets sent.
        if (code < 0x21 || code > 0x7E) return null
        if (ch == ',' || ch == '\\' || ch == '"' || ch == '\'') return null
    }
    return keyword
}

private fun isReserved(keyword: String): Boolean =
    RESERVED.any { keyword.equals(it, ignoreCase = true) }

private fun labelOf(keyword: String): String {
    val rest = if (keyword.startsWith('$')) keyword.substring(1) else keyword
    val readable = buildString(rest.length) {
        for (ch in rest) append(if (ch == '-' || ch == '_') ' ' else ch)
    }
    if (readable.isEmpty()) return readable
    // Only the first letter. Title-casing every word would turn iOS into Ios.
    return readable.replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecaseChar() else ch
    }
}

/**
 * A stable colour per keyword, from a palette chosen so white text clears 4.5:1 on
 * every one of them. Hashing the lowercased keyword, not the label, means Invoices
 * and invoices stay the same colour when another client stored a different spelling.
 */
private fun colorOf(keyword: String): Long {
    val palette = listOf(
        0xFFB23A48L, 0xFF8C5A2BL, 0xFF4F6D3AL, 0xFF2E6A6AL,
        0xFF2F5D96L, 0xFF5B4B94L, 0xFF8A3A72L, 0xFF6B5330L,
    )
    val key = keyword.lowercase().fold(0) { acc, ch -> acc * 31 + ch.code }
    return palette[((key % palette.size) + palette.size) % palette.size]
}
