package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Getting shift wrong quietly files the wrong fifty messages, so these are the rules. */
class PickTest {
    private val ids = listOf("a", "b", "c", "d", "e")

    @Test
    fun aPlainClickPicksNothingOut() {
        assertTrue(pickedAfter(ids, setOf("a", "b"), "a", "c", ctrl = false, shift = false).isEmpty())
    }

    @Test
    fun controlAddsAndTakesAway() {
        assertEquals(setOf("c"), pickedAfter(ids, emptySet(), "c", "c", ctrl = true, shift = false))
        assertEquals(setOf("a", "c"), pickedAfter(ids, setOf("a"), "a", "c", ctrl = true, shift = false))
        assertEquals(setOf("a"), pickedAfter(ids, setOf("a", "c"), "a", "c", ctrl = true, shift = false))
    }

    @Test
    fun shiftTakesTheRunBetweenTheAnchorAndHere() {
        assertEquals(setOf("b", "c", "d"), pickedAfter(ids, emptySet(), "b", "d", ctrl = false, shift = true))
    }

    /** Dragging a selection upwards is the same run as dragging it down. */
    @Test
    fun shiftWorksInBothDirections() {
        assertEquals(
            pickedAfter(ids, emptySet(), "b", "d", ctrl = false, shift = true),
            pickedAfter(ids, emptySet(), "d", "b", ctrl = false, shift = true),
        )
    }

    @Test
    fun shiftOnItselfIsOneMessage() {
        assertEquals(setOf("c"), pickedAfter(ids, emptySet(), "c", "c", ctrl = false, shift = true))
    }

    /**
     * The anchor is gone when the folder changed under the selection, or when the first
     * thing somebody does is hold shift. Neither is a reason to pick out nothing forever.
     */
    @Test
    fun shiftWithNothingToMeasureFromIsAPlainClick() {
        assertTrue(pickedAfter(ids, setOf("a"), null, "c", ctrl = false, shift = true).isEmpty())
        assertTrue(pickedAfter(ids, setOf("a"), "gone", "c", ctrl = false, shift = true).isEmpty())
    }

    @Test
    fun anEmptyListIsNotACrash() {
        assertTrue(pickedAfter(emptyList(), emptySet(), "a", "b", ctrl = true, shift = true).isEmpty())
        assertEquals(setOf("b"), pickedAfter(emptyList(), emptySet(), "a", "b", ctrl = true, shift = false))
    }
}
