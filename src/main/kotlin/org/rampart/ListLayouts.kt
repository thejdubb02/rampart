package org.rampart

/**
 * How the message list is drawn, beside how tightly it is packed.
 *
 * Kept apart from [Density] on purpose: density is the spacing of the ordinary list and
 * applies to it alone, while a layout is a different picture of the same messages. Normal
 * is the list as it has always been, and choosing it changes nothing about how that draws.
 */
internal enum class ListLayout(val key: String, val label: String, val about: String) {
    NORMAL("normal", "Normal", "The list as it has always been, with density below."),
    TABLE("table", "Table", "Columns you can sort and resize: star, sender, subject, date, size and tags."),
    CARDS("cards", "Cards", "Each message on its own card, with more of its preview showing."),
    ;

    companion object {
        fun of(key: String?): ListLayout = entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: NORMAL
    }
}

/**
 * One column of the table view.
 *
 * Widths are in density-independent pixels as plain numbers, so the arithmetic below can
 * be tested without a window. [min] is where a drag stops, so a column can be made small
 * but never dragged out of existence and lost.
 */
internal enum class TableColumn(val key: String, val label: String, val width: Float, val min: Float) {
    STAR("star", "", 30f, 30f),
    SENDER("sender", "From", 170f, 60f),
    SUBJECT("subject", "Subject", 300f, 80f),
    DATE("date", "Date", 110f, 60f),
    SIZE("size", "Size", 70f, 44f),
    TAGS("tags", "Tags", 120f, 44f),
    ;

    companion object {
        fun of(key: String?): TableColumn? = entries.firstOrNull { it.key == key }
    }
}

/** The widest a column can be dragged, so a stray drag cannot push the rest off screen. */
internal const val COLUMN_MAX = 900f

/** Which column the table is sorted by, and which way. */
internal data class TableSort(val column: TableColumn = TableColumn.DATE, val ascending: Boolean = false) {
    /** Stored as `date:desc`, which a person reading the settings file can follow. */
    fun encoded(): String = column.key + ":" + if (ascending) "asc" else "desc"

    /**
     * The sort after a click on [clicked]'s heading.
     *
     * The same heading again turns the order round. A new heading starts the way that
     * column is most often wanted: newest, largest and starred first, names from A.
     */
    fun clicked(clicked: TableColumn): TableSort =
        if (clicked == column) copy(ascending = !ascending)
        else TableSort(clicked, ascending = clicked == TableColumn.SENDER || clicked == TableColumn.SUBJECT)

    companion object {
        fun of(text: String?): TableSort {
            val column = TableColumn.of(text?.substringBefore(':')) ?: return TableSort()
            return TableSort(column, text?.substringAfter(':', "") == "asc")
        }
    }
}

/**
 * The rows in the table's order.
 *
 * Every column falls back to newest first inside a tie, for the reason [sorted] gives:
 * a list that reshuffles equal rows between refreshes looks broken even when it is not.
 * Sender and subject compare the way the ordinary list's sort does, the name before the
 * address and the subject without its Re:, so the two views agree on what "by sender"
 * means. Tags sort by the first tag's label, and untagged mail goes last either way,
 * because a column full of blanks at the top hides the thing being sorted for.
 */
internal fun tableSorted(emails: List<Summary>, sort: TableSort): List<Summary> {
    val newest = compareByDescending<Summary> { it.receivedAt }
    val primary: Comparator<Summary> = when (sort.column) {
        TableColumn.STAR -> compareBy { it.flagged }
        TableColumn.SENDER -> compareBy { it.from.ifBlank { it.fromEmail }.lowercase() }
        TableColumn.SUBJECT -> compareBy { bareSubject(it.subject) }
        TableColumn.DATE -> compareBy { it.receivedAt }
        TableColumn.SIZE -> compareBy { it.size }
        TableColumn.TAGS -> compareBy { tagsOf(it.keywords).firstOrNull()?.label?.lowercase().orEmpty() }
    }
    val ordered = if (sort.ascending) primary else primary.reversed()
    if (sort.column == TableColumn.TAGS) {
        val untaggedLast = compareBy<Summary> { tagsOf(it.keywords).isEmpty() }
        return emails.sortedWith(untaggedLast.then(ordered).then(newest))
    }
    return emails.sortedWith(ordered.then(newest))
}

/**
 * The table's column widths.
 *
 * Only the columns someone has dragged are stored; the rest take their default, so a
 * default changed in a later build still reaches everyone who never touched that column.
 */
internal data class ColumnWidths(val chosen: Map<TableColumn, Float> = emptyMap()) {
    fun of(column: TableColumn): Float = chosen[column] ?: column.width

    /** The widths after dragging [column]'s right edge by [delta], held between its minimum and [COLUMN_MAX]. */
    fun dragged(column: TableColumn, delta: Float): ColumnWidths =
        ColumnWidths(chosen + (column to (of(column) + delta).coerceIn(column.min, COLUMN_MAX)))

    /** Every column's width added up, which is how wide the table is. */
    fun total(): Float = TableColumn.entries.sumOf { of(it).toDouble() }.toFloat()

    /** Stored as `sender=170,subject=300`. */
    fun encoded(): String = chosen.entries.joinToString(",") { (column, width) -> column.key + "=" + width.toInt() }

    companion object {
        /**
         * Reads the stored widths back. A column this build does not know, or a width that
         * is not a number, is skipped rather than failing the rest.
         */
        fun of(text: String?): ColumnWidths = ColumnWidths(
            text.orEmpty().split(',').mapNotNull { pair ->
                val column = TableColumn.of(pair.substringBefore('=').trim()) ?: return@mapNotNull null
                val width = pair.substringAfter('=', "").trim().toFloatOrNull() ?: return@mapNotNull null
                column to width.coerceIn(column.min, COLUMN_MAX)
            }.toMap(),
        )
    }
}

/**
 * A size the way the table shows it, in the same words the attachment list uses.
 *
 * Blank for zero, because zero is "the server did not say", and a column of 0 bytes would
 * read as a folder of empty messages.
 */
internal fun tableSize(bytes: Long): String = if (bytes <= 0) "" else humanSize(bytes)
