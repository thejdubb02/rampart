package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The overlay is Compose; these checks are that the table it draws cannot contradict
 * itself.
 */
class ShortcutsTest {

    private val groups = listOf(
        "Moving around",
        "Acting on a message",
        "Writing",
        "Everything else",
    )

    @Test
    fun theListIsNotEmptyAndEveryFieldIsFilledIn() {
        assertEquals(false, SHORTCUTS.isEmpty())
        for (row in SHORTCUTS) {
            assertEquals(false, row.keys.isBlank(), row.toString())
            assertEquals(false, row.does.isBlank(), row.toString())
            assertEquals(false, row.group.isBlank(), row.toString())
        }
    }

    @Test
    fun everyGroupIsOneOfTheFour() {
        for (row in SHORTCUTS) {
            assertEquals(true, row.group in groups, row.group)
        }
    }

    @Test
    fun keysAreUniqueInsideAGroup() {
        for ((group, rows) in SHORTCUTS.groupBy { it.group }) {
            val keys = rows.map { it.keys }
            assertEquals(keys.distinct(), keys, group)
        }
    }
}
