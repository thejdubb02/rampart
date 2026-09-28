package org.rampart

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The setup code has to scan. The only honest check of that short of a phone is to read
 * it back with a decoder, so the matrix is drawn into pixels and ZXing's own reader is
 * asked what it says.
 */
class QrCodeTest {
    @Test
    fun `the code for an otpauth address reads back as that address`() {
        val uri = Totp.uri(Totp.newSecret(), "jo@example.com", "example.com")
        val modules = qrModules(uri)
        assertTrue(modules.all { it.size == modules.size }, "A QR code is square.")

        val scale = 4
        val side = modules.size * scale
        val pixels = IntArray(side * side) { i ->
            val x = (i % side) / scale
            val y = (i / side) / scale
            if (modules[y][x]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        assertEquals(uri, QRCodeReader().decode(bitmap).text)
    }

    @Test
    fun `the quiet zone is there, four light modules on every side`() {
        val modules = qrModules("otpauth://totp/x?secret=MZXW6YTBOI")
        val n = modules.size
        for (i in 0 until n) {
            for (edge in 0 until 4) {
                assertTrue(!modules[edge][i] && !modules[n - 1 - edge][i], "row edge $edge at $i")
                assertTrue(!modules[i][edge] && !modules[i][n - 1 - edge], "column edge $edge at $i")
            }
        }
    }
}
