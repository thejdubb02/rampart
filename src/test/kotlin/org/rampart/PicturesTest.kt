package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PicturesTest {
    /**
     * Four colour components rather than three, which is what a JPEG exported for print
     * has. The engine parses its header, lays the picture out at full size, and paints
     * nothing into it: a blank rectangle exactly where the picture goes. The one that
     * found this was a hotel's logo in a signature, 702 KB of which six hundred was
     * colour profile; the fixture here is the same shape without anybody's artwork in it.
     */
    @Test
    fun `a CMYK JPEG becomes one anything can draw`() {
        val source = javaClass.getResourceAsStream("/cmyk-block.jpg")?.readBytes()
            ?: error("the fixture is missing")
        assertEquals(4, componentsOf(source), "the fixture is not the kind of picture this is about")

        val (type, bytes) = drawable("image/jpeg", source)

        assertEquals("image/jpeg", type)
        assertEquals(3, componentsOf(bytes), "still not something the engine will paint")
    }

    @Test
    fun `something that cannot be decoded is handed over exactly as it arrived`() {
        val nonsense = byteArrayOf(1, 2, 3, 4, 5)
        val (type, bytes) = drawable("image/tiff", nonsense)
        assertEquals("image/tiff", type)
        assertTrue(nonsense.contentEquals(bytes))
    }

    @Test
    fun `a picture with transparency keeps it`() {
        val png = javaClass.getResourceAsStream("/clear.png")?.readBytes() ?: error("fixture missing")
        val (type, _) = drawable("image/png", png)
        assertEquals("image/png", type, "transparency was thrown away")
    }

    @Test
    fun `the spaces that come out as empty boxes are spaces again`() {
        assertEquals("From: Mark", withoutTofu("From: Mark"))
        assertEquals("10.44 AM", withoutTofu("10.44 AM"))
        assertEquals("word", withoutTofu("wo​rd"), "a zero width one leaves nothing behind")
        assertEquals("plain text", withoutTofu("plain text"))
    }

    /** The number of colour components a JPEG declares, read out of its frame header. */
    private fun componentsOf(jpeg: ByteArray): Int {
        var i = 2
        while (i < jpeg.size - 1) {
            if (jpeg[i] != 0xFF.toByte()) return -1
            val marker = jpeg[i + 1].toInt() and 0xFF
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            // SOF0 through SOF3: the frame header, whose last byte before the components is
            // the count of them.
            if (marker in 0xC0..0xC3) return jpeg[i + 9].toInt() and 0xFF
            if (marker == 0xDA) return -1
            i += 2 + length
        }
        return -1
    }
}
