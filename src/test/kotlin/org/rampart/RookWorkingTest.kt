package org.rampart

import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The two things that would actually break the animation and not just look wrong in a
 * screenshot: picking the wrong strip for the size being drawn, and reading a frame index
 * that falls outside the strip once the loop wraps back to 0.
 */
class RookWorkingTest {
    @Test
    fun `a small avatar draws from the small strip`() {
        assertEquals("rook-working-96.png", rookWorkingStripFile(16f))
        assertEquals("rook-working-96.png", rookWorkingStripFile(96f))
    }

    @Test
    fun `an avatar bigger than the small strip draws from the big one`() {
        assertEquals("rook-working-192.png", rookWorkingStripFile(97f))
        assertEquals("rook-working-192.png", rookWorkingStripFile(192f))
    }

    @Test
    fun `the frame index never reaches the strip's width`() {
        assertEquals(0, rookWorkingFrame(0f))
        assertEquals(23, rookWorkingFrame(23.9f))
        // The animation's own target value, hit for one instant right before it loops.
        // Reading it literally would ask for frame 48 of a strip that only has 48 (0..47).
        assertEquals(47, rookWorkingFrame(48f))
    }

    @Test
    fun `both strips are actually 48 square frames`() {
        listOf(96, 192).forEach { frame ->
            val file = "rook-working-$frame.png"
            val url = javaClass.classLoader.getResource("art/$file")
            assertNotNull(url, "$file is not bundled in resources/art")
            val image = ImageIO.read(url)
            assertEquals(frame, image.height, "$file should be $frame px tall")
            assertEquals(frame * 48, image.width, "$file should hold 48 frames of ${frame}px each")
        }
    }
}
