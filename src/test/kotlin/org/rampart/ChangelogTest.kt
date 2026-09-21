package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangelogTest {
    @Test
    fun `a line is version, date and what changed`() {
        val change = changeOf("0.1.42\t2026-09-17\tmail lands the moment it arrives")
        assertEquals(Change("0.1.42", "2026-09-17", "mail lands the moment it arrives"), change)
    }

    @Test
    fun `a subject holding a tab keeps all of it`() {
        // Split on tabs, so a subject with one in it would otherwise lose everything after.
        assertEquals("a\tb", changeOf("0.1.1\t2026-01-01\ta\tb")?.what)
    }

    @Test
    fun `a malformed line is skipped rather than throwing`() {
        assertNull(changeOf(""))
        assertNull(changeOf("0.1.1"))
        assertNull(changeOf("0.1.1\t2026-01-01"))
        assertNull(changeOf("0.1.1\t2026-01-01\t   "))
        assertNull(changeOf("\t2026-01-01\tsomething"))
    }

    @Test
    fun `only the version actually running is marked`() {
        val change = Change("0.1.42", "2026-09-17", "something")
        assertTrue(isRunning(change, "0.1.42"))
        assertFalse(isRunning(change, "0.1.43"))
        // Outside a packaged build nothing is marked, rather than guessing the newest.
        assertFalse(isRunning(change, null))
    }

    @Test
    fun `the build put a changelog in the jar`() {
        // Catches the resource being generated into the wrong place, which looks like an
        // empty section rather than an error.
        val all = changelog()
        assertTrue(all.isNotEmpty(), "no changelog resource in the build")
        assertTrue(all.first().version.startsWith("0.1."))
    }

    @Test
    fun `only versions newer than what was last seen come back`() {
        val all = listOf(
            Change("0.1.44", "2026-09-21", "third"),
            Change("0.1.43", "2026-09-20", "second"),
            Change("0.1.42", "2026-09-19", "first"),
        )
        assertEquals(all.take(2), unseenChanges(all, "0.1.42"))
    }

    @Test
    fun `nothing is unseen once the newest has already been shown`() {
        val all = listOf(Change("0.1.44", "2026-09-21", "third"), Change("0.1.43", "2026-09-20", "second"))
        assertTrue(unseenChanges(all, "0.1.44").isEmpty())
    }

    @Test
    fun `skipping several releases surfaces every one missed, not just the latest`() {
        val all = listOf(
            Change("0.1.44", "2026-09-21", "third"),
            Change("0.1.43", "2026-09-20", "second"),
            Change("0.1.42", "2026-09-19", "first"),
            Change("0.1.41", "2026-09-18", "already seen"),
        )
        assertEquals(all.take(3), unseenChanges(all, "0.1.41"))
    }
}
