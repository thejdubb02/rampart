package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals

class ShortcutTest {
    @Test
    fun `j and k walk the list`() {
        assertEquals(1, nextIndex(current = 0, size = 5, delta = 1))
        assertEquals(0, nextIndex(current = 1, size = 5, delta = -1))
    }

    @Test
    fun `stepping off either end stays put rather than wrapping`() {
        // A keystroke that silently jumps from the newest message to the oldest is one
        // people stop trusting.
        assertEquals(4, nextIndex(current = 4, size = 5, delta = 1))
        assertEquals(0, nextIndex(current = 0, size = 5, delta = -1))
    }

    @Test
    fun `with nothing selected, down takes the first and up the last`() {
        assertEquals(0, nextIndex(current = -1, size = 5, delta = 1))
        assertEquals(4, nextIndex(current = -1, size = 5, delta = -1))
    }

    @Test
    fun `an empty list is not an index out of bounds`() {
        assertEquals(0, nextIndex(current = -1, size = 0, delta = 1))
        assertEquals(0, nextIndex(current = 3, size = 0, delta = -1))
    }
}
