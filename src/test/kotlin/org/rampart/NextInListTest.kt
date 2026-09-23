package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** After archive, delete, spam or move, the open row moves to a neighbour. */
class NextInListTest {
    @Test
    fun `the next row is chosen when one leaves`() {
        assertEquals("c", nextInList(listOf("a", "b", "c"), 1) { it == "b" })
    }

    @Test
    fun `the previous row is chosen when the last one leaves`() {
        assertEquals("b", nextInList(listOf("a", "b", "c"), 2) { it == "c" })
    }

    @Test
    fun `nothing is chosen when the only row leaves`() {
        assertNull(nextInList(listOf("a"), 0) { true })
    }

    @Test
    fun `a row that is also leaving is skipped`() {
        assertEquals("d", nextInList(listOf("a", "b", "c", "d"), 1) { it == "b" || it == "c" })
    }

    @Test
    fun `the previous row is chosen when everything below is leaving`() {
        assertEquals("a", nextInList(listOf("a", "b", "c"), 1) { it != "a" })
    }

    @Test
    fun `an index that is not in the list chooses nothing`() {
        assertNull(nextInList(listOf("a", "b"), -1) { true })
    }
}
