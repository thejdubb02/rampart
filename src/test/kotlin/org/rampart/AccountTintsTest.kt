package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Rows coloured by account in the merged inbox. */
class AccountTintsTest {
    private val work = "you@example.org@mail.example.org"
    private val home = "me@example.com@mail.example.com"
    private val club = "sec@example.net@mail.example.net"

    @Test
    fun `off by default and off with one account`() {
        assertEquals(emptyMap(), accountTints(listOf(work, home), emptyMap(), enabled = false))
        assertEquals(emptyMap(), accountTints(listOf(work), emptyMap(), enabled = true))
        assertEquals(emptyMap(), accountTints(emptyList(), emptyMap(), enabled = true))
    }

    @Test
    fun `each account gets a colour, and two accounts never share one`() {
        val tints = accountTints(listOf(work, home, club), emptyMap(), enabled = true)
        assertEquals(setOf(work, home, club), tints.keys)
        assertEquals(3, tints.values.toSet().size)
        assertTrue(tints.values.all { it in TAG_COLOURS })
    }

    /** The same account is the same colour on every computer, and in any order. */
    @Test
    fun `an account's colour is stable`() {
        val once = accountTints(listOf(work, home), emptyMap(), enabled = true)
        val again = accountTints(listOf(home, work), emptyMap(), enabled = true)
        assertEquals(once, again)
        assertEquals(once, accountTints(listOf(work, home), emptyMap(), enabled = true))
    }

    @Test
    fun `adding an account does not recolour the others unless they collide`() {
        val two = accountTints(listOf(work, home), emptyMap(), enabled = true)
        val three = accountTints(listOf(work, home, club), emptyMap(), enabled = true)
        // Whichever of the first two did not have to move keeps its colour.
        assertTrue(two[work] == three[work] || two[home] == three[home])
    }

    @Test
    fun `two accounts that hash to one colour are told apart`() {
        val palette = listOf(0xFF111111L, 0xFF222222L)
        // Two colours make a collision likely, and the two accounts must still differ.
        val tints = accountTints(listOf(work, home), emptyMap(), enabled = true, palette = palette)
        assertNotEquals(tints[work], tints[home])
    }

    @Test
    fun `a chosen colour wins, and nobody else is given it`() {
        val first = accountTints(listOf(work, home), emptyMap(), enabled = true)
        val chosen = mapOf(home to first.getValue(work))
        val tints = accountTints(listOf(work, home), chosen, enabled = true)
        assertEquals(first[work], tints[home])
        assertNotEquals(tints[work], tints[home])
    }

    @Test
    fun `the settings page shows the colour an account will have`() {
        val tints = accountTints(listOf(work, home), emptyMap(), enabled = true)
        assertEquals(tints[work], accountColour(work, emptyMap(), listOf(work, home)))
        // With one account there is no tint, but the colour it would start with is shown.
        assertTrue(accountColour(work, emptyMap(), listOf(work)) in TAG_COLOURS)
    }

    /** A tag is about this one message; the account is where every message there came from. */
    @Test
    fun `a tag's colour wins over the account's`() {
        assertEquals(1L, rowTint(1L, 2L))
        assertEquals(2L, rowTint(null, 2L))
        assertEquals(null, rowTint(null, null))
    }
}
