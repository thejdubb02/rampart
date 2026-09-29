package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which buttons a row shows under the pointer, and the default that must not move. */
class HoverActionsTest {
    /** Somebody who never opens the setting must see exactly the row they had. */
    @Test
    fun `the default is read, archive and delete, in that order`() {
        assertEquals(listOf(HoverAction.READ, HoverAction.ARCHIVE, HoverAction.DELETE), DEFAULT_HOVER_ACTIONS)
        assertEquals(DEFAULT_HOVER_ACTIONS, hoverActionsOf(null))
    }

    @Test
    fun `a choice is read back in the order it was made`() {
        assertEquals(listOf(HoverAction.STAR, HoverAction.ARCHIVE), hoverActionsOf(listOf("star", "archive")))
    }

    @Test
    fun `no buttons at all is a real choice`() {
        assertEquals(emptyList(), hoverActionsOf(emptyList()))
    }

    @Test
    fun `unknown keys and repeats are dropped and the rest kept`() {
        assertEquals(
            listOf(HoverAction.MOVE, HoverAction.READ),
            hoverActionsOf(listOf("move", "teleport", "move", "READ")),
        )
    }

    @Test
    fun `no more than fit in the slot are kept`() {
        val all = HoverAction.entries.map { it.key }
        assertEquals(HOVER_ACTIONS_MAX, hoverActionsOf(all).size)
    }

    @Test
    fun `switching one on puts it at the end and switching it off takes it out`() {
        val on = toggledHoverAction(DEFAULT_HOVER_ACTIONS, HoverAction.SNOOZE)
        assertEquals(DEFAULT_HOVER_ACTIONS + HoverAction.SNOOZE, on)
        assertEquals(listOf(HoverAction.READ, HoverAction.DELETE, HoverAction.SNOOZE), toggledHoverAction(on, HoverAction.ARCHIVE))
    }

    @Test
    fun `a fifth is refused rather than dropping one somebody chose`() {
        val four = DEFAULT_HOVER_ACTIONS + HoverAction.STAR
        assertEquals(four, toggledHoverAction(four, HoverAction.MOVE))
    }

    @Test
    fun `the stored keys read back as the same choice`() {
        val chosen = listOf(HoverAction.SNOOZE, HoverAction.MOVE, HoverAction.READ)
        assertEquals(chosen, hoverActionsOf(hoverActionKeys(chosen)))
    }
}
