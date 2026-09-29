package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals

class ListLayoutsTest {
    private fun mail(
        id: String,
        from: String,
        subject: String,
        at: String,
        flagged: Boolean = false,
        size: Long = 0,
        keywords: Set<String> = emptySet(),
    ) = Summary(id, from, "${from.lowercase()}@example.org", subject, at, "", true, flagged, keywords, size = size)

    private val rows = listOf(
        mail("a", "Dana", "Re: Quote", "2026-09-03T09:00:00Z", size = 2_000, keywords = setOf("work")),
        mail("b", "alex", "Invoice", "2026-09-01T09:00:00Z", flagged = true, size = 90_000),
        mail("c", "Cass", "Quote", "2026-09-02T09:00:00Z", size = 500, keywords = setOf("billing", "\$seen")),
        mail("d", "Alex", "agenda", "2026-09-04T09:00:00Z", flagged = true, size = 2_000),
    )

    private fun ids(sort: TableSort) = tableSorted(rows, sort).map { it.id }

    @Test
    fun `every column sorts both ways`() {
        assertEquals(listOf("d", "a", "c", "b"), ids(TableSort(TableColumn.DATE, ascending = false)))
        assertEquals(listOf("b", "c", "a", "d"), ids(TableSort(TableColumn.DATE, ascending = true)))
        assertEquals(listOf("d", "b", "a", "c"), ids(TableSort(TableColumn.STAR, ascending = false)))
        assertEquals(listOf("d", "b", "c", "a"), ids(TableSort(TableColumn.SENDER, ascending = true)))
        assertEquals(listOf("a", "c", "d", "b"), ids(TableSort(TableColumn.SENDER, ascending = false)))
    }

    @Test
    fun `equal values fall back to newest first`() {
        // a and d are both 2,000 bytes; d is newer, whichever way size is sorted.
        assertEquals(listOf("b", "d", "a", "c"), ids(TableSort(TableColumn.SIZE, ascending = false)))
        assertEquals(listOf("c", "d", "a", "b"), ids(TableSort(TableColumn.SIZE, ascending = true)))
    }

    @Test
    fun `subject ignores Re and case, the way the list's own sort does`() {
        assertEquals(listOf("d", "b", "a", "c"), ids(TableSort(TableColumn.SUBJECT, ascending = true)))
    }

    @Test
    fun `untagged mail goes last whichever way tags are sorted`() {
        assertEquals(listOf("c", "a", "d", "b"), ids(TableSort(TableColumn.TAGS, ascending = true)))
        assertEquals(listOf("a", "c", "d", "b"), ids(TableSort(TableColumn.TAGS, ascending = false)))
    }

    @Test
    fun `clicking a heading turns it round or starts it the useful way`() {
        val byDate = TableSort()
        assertEquals(TableSort(TableColumn.DATE, ascending = true), byDate.clicked(TableColumn.DATE))
        assertEquals(TableSort(TableColumn.SENDER, ascending = true), byDate.clicked(TableColumn.SENDER))
        assertEquals(TableSort(TableColumn.SIZE, ascending = false), byDate.clicked(TableColumn.SIZE))
        assertEquals(TableSort(TableColumn.SIZE, ascending = true), TableSort.of(TableSort(TableColumn.SIZE, true).encoded()))
        assertEquals(TableSort(), TableSort.of("nonsense"))
        assertEquals(TableSort(), TableSort.of(null))
    }

    @Test
    fun `a column resizes within its limits and the rest keep their defaults`() {
        val widths = ColumnWidths().dragged(TableColumn.SENDER, 40f)
        assertEquals(TableColumn.SENDER.width + 40f, widths.of(TableColumn.SENDER))
        assertEquals(TableColumn.SUBJECT.width, widths.of(TableColumn.SUBJECT))
        assertEquals(TableColumn.SIZE.min, widths.dragged(TableColumn.SIZE, -1000f).of(TableColumn.SIZE))
        assertEquals(COLUMN_MAX, widths.dragged(TableColumn.SUBJECT, 5000f).of(TableColumn.SUBJECT))
        assertEquals(TableColumn.entries.sumOf { it.width.toDouble() }.toFloat() + 40f, widths.total())
    }

    @Test
    fun `widths survive the settings file and bad entries are skipped`() {
        val widths = ColumnWidths().dragged(TableColumn.SENDER, 30f).dragged(TableColumn.TAGS, -10f)
        assertEquals(widths, ColumnWidths.of(widths.encoded()))
        assertEquals(
            ColumnWidths(mapOf(TableColumn.DATE to 90f, TableColumn.STAR to TableColumn.STAR.min)),
            ColumnWidths.of("date=90,colour=5,size=wide,star=1"),
        )
        assertEquals(ColumnWidths(), ColumnWidths.of(null))
    }

    @Test
    fun `layouts read back from their key and fall back to normal`() {
        assertEquals(ListLayout.TABLE, ListLayout.of("table"))
        assertEquals(ListLayout.CARDS, ListLayout.of("CARDS"))
        assertEquals(ListLayout.NORMAL, ListLayout.of(null))
        assertEquals(ListLayout.NORMAL, ListLayout.of("grid"))
    }

    @Test
    fun `a size of zero is blank rather than zero bytes`() {
        assertEquals("", tableSize(0))
        assertEquals("512 bytes", tableSize(512))
        assertEquals("1.5 KB", tableSize(1536))
    }
}
