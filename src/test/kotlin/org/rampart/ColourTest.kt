package org.rampart

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ColourTest {

    private val white = Color.White
    private val dark = Color(0xFF121216)

    @Test
    fun `a brand colour written for white is used on a light background`() {
        // The heading colour from a real newsletter. Bulwark draws it green and so should
        // this: it is not Rampart's place to overrule a designer on their own brand.
        assertEquals(Color(0xFF2FD2A8), readableColour("color: #2fd2a8; font-size: 24px;", white))
    }

    @Test
    fun `the same colour is dropped where it would be unreadable`() {
        // Body text written near black for a white page, drawn on a dark theme, is a
        // paragraph nobody can see. The theme's own ink takes over.
        assertNull(readableColour("color: #333333;", dark))
        assertNotNull(readableColour("color: #333333;", white))
    }

    @Test
    fun `white on white is never used`() {
        assertNull(readableColour("color: #ffffff;", white))
        assertNull(readableColour("color: rgb(255, 255, 255);", white))
    }

    @Test
    fun `the spellings mail actually uses all parse`() {
        assertEquals(Color(0xFFAABBCC), parseCssColour("#abc"))
        assertEquals(Color(0xFFAABBCC), parseCssColour("#aabbcc"))
        assertEquals(Color(0xFF010203), parseCssColour("rgb(1, 2, 3)"))
        assertEquals(Color(0xFF010203), parseCssColour("rgba(1, 2, 3, 0.5)"))
    }

    @Test
    fun `nonsense is no colour rather than a wrong one`() {
        assertNull(parseCssColour("chartreuse"))
        assertNull(parseCssColour("#12345"))
        assertNull(parseCssColour(""))
        assertNull(readableColour("font-size: 24px;", white))
        // No background means the caller only wants the characters.
        assertNull(readableColour("color: #2fd2a8;", Color.Unspecified))
    }

    @Test
    fun `background-color is not read as color`() {
        // The property name has to match on its own, or every band in a newsletter paints
        // its text the colour of the band it sits on.
        assertNull(readableColour("background-color: #2fd2a8;", white))
    }

    @Test
    fun `contrast is the perceived kind, not an average of channels`() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.1)
        assertEquals(1.0, contrast(Color.White, Color.White), 0.001)
    }
}
