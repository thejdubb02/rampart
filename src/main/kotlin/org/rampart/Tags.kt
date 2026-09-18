package org.rampart

/** A keyword as a person sees it. */
internal data class Tag(
    /** The keyword exactly as it is stored on the server, eg "$label1" or "invoices". */
    val keyword: String,
    /** What to show, eg "Invoices" or "Clients/Acme". */
    val label: String,
    /** 0xAARRGGBB. The one that was chosen for it, or the one derived from the name. */
    val color: Long,
)

/**
 * How a tag says it is inside another one.
 *
 * A slash, which is what every client that nests tags uses and what IMAP folder names have
 * always used for the same job. It is a convention rather than a protocol feature: to the
 * server `Clients/Acme` is one keyword and nothing knows it has a parent.
 */
internal const val NEST = '/'

/**
 * One line in the sidebar's tag tree.
 *
 * [real] is false for a level that only exists because something below it does. Tagging a
 * message `Clients/Acme` without ever making `Clients` is the normal way this happens, and
 * a tree that skipped the middle would draw Acme at the top with nothing saying whose it
 * is. A level like that is a heading: it can be collapsed and it cannot be applied.
 */
internal data class TagRow(
    val keyword: String,
    val label: String,
    val color: Long,
    val depth: Int,
    val real: Boolean,
)

/**
 * The account's tags as a tree, in the order they should be drawn.
 *
 * Alphabetical inside each level, parents before their children, and a level invented
 * wherever one is missing. Case-insensitive throughout: another client storing `invoices`
 * where this one wrote `Invoices` must not produce two branches.
 */
internal fun tagRows(keywords: Collection<String>, chosen: Map<String, Long> = emptyMap()): List<TagRow> {
    val real = keywords.filterNot(::isReserved).associateBy { it.lowercase() }
    // Every level that has to exist, whether or not anything is tagged with it.
    val levels = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
    real.values.forEach { keyword ->
        val parts = keyword.split(NEST).filter { it.isNotBlank() }
        for (at in parts.indices) levels.add(parts.take(at + 1).joinToString(NEST.toString()))
    }
    return levels.map { path ->
        val exact = real[path.lowercase()]
        TagRow(
            keyword = exact ?: path,
            label = labelOf(path),
            color = colorOf(exact ?: path, chosen),
            depth = path.count { it == NEST },
            real = exact != null,
        )
    }
}

/**
 * Protocol state, not labels. Showing $seen as a chip is how a mailbox looks like it
 * has a tag named Seen, which no client put there.
 */
private val RESERVED = arrayOf(
    "\$seen", "\$flagged", "\$draft", "\$answered", "\$forwarded",
    "\$junk", "\$notjunk", "\$phishing", "\$deleted", "\$recent",
)

/** The user's own tags, in a stable order, from the keywords on a message. */
internal fun tagsOf(keywords: Collection<String>, chosen: Map<String, Long> = emptyMap()): List<Tag> {
    val tags = ArrayList<Tag>()
    for (keyword in keywords) {
        if (isReserved(keyword)) continue
        if (tags.any { it.keyword.equals(keyword, ignoreCase = true) }) continue
        tags.add(Tag(keyword, labelOf(keyword), colorOf(keyword, chosen)))
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
    // A path with an empty level in it is not a path, and a leading or trailing slash is
    // the ordinary way one gets typed by mistake.
    if (keyword.split(NEST).any { it.isBlank() }) return null
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

/** Each level of the path made readable, the separator kept. */
private fun labelOf(keyword: String): String =
    keyword.split(NEST).joinToString(NEST.toString(), transform = ::readable)

private fun readable(part: String): String {
    val rest = if (part.startsWith('$')) part.substring(1) else part
    val text = buildString(rest.length) {
        for (ch in rest) append(if (ch == '-' || ch == '_') ' ' else ch)
    }
    if (text.isEmpty()) return text
    // Only the first letter. Title-casing every word would turn iOS into Ios.
    return text.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecaseChar() else ch }
}

/**
 * The colour chosen for a keyword, or the one derived from its name.
 *
 * Chosen colours are Rampart's own and are kept on this machine. Webmail keeps its tag
 * colours in per-user encrypted files on the server that only it can open, so there is
 * nothing for a second client to read, and inventing a place in the mailbox to write them
 * would put Rampart's furniture in somebody's mail. The derived colour is what everyone
 * starts with and it is stable, so two people looking at the same mailbox still agree
 * until one of them changes something.
 *
 * The palette is chosen so white text clears 4.5:1 on every one of them. Hashing the
 * lowercased keyword, not the label, means Invoices and invoices stay the same colour when
 * another client stored a different spelling.
 */
internal fun colorOf(keyword: String, chosen: Map<String, Long> = emptyMap()): Long {
    chosen[keyword.lowercase()]?.let { return it }
    val palette = listOf(
        0xFFB23A48L, 0xFF8C5A2BL, 0xFF4F6D3AL, 0xFF2E6A6AL,
        0xFF2F5D96L, 0xFF5B4B94L, 0xFF8A3A72L, 0xFF6B5330L,
    )
    val key = keyword.lowercase().fold(0) { acc, ch -> acc * 31 + ch.code }
    return palette[((key % palette.size) + palette.size) % palette.size]
}

/** The colours a tag can be set to, in the order the picker shows them. */
internal val TAG_COLOURS = listOf(
    0xFFB23A48L, 0xFF8C5A2BL, 0xFF4F6D3AL, 0xFF2E6A6AL,
    0xFF2F5D96L, 0xFF5B4B94L, 0xFF8A3A72L, 0xFF6B5330L,
    0xFF7A2E2EL, 0xFF3F7A4FL, 0xFF35507AL, 0xFF6E6E6EL,
)
