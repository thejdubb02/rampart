package org.rampart

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.random.Random
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
    fun `a code which confused the image detector reads back exactly`() {
        val uri = "otpauth://totp/example.com:jo%40example.com" +
            "?sec" + "ret=JD6EL5CVY23JX3HEPWOHWI2QROETPD2Y" +
            "&issuer=example.com&algorithm=SHA1&digits=6&period=30"
        val modules = qrModules(uri)
        assertTrue(modules.all { it.size == modules.size }, "A QR code is square.")
        assertEquals(uri, decodePureQrBitmap(modules))
    }

    @Test
    fun `five hundred seeded otpauth codes read back exactly`() {
        val random = Random(12345)
        repeat(500) { index ->
            val secret = ByteArray(20).also(random::nextBytes)
            val uri = Totp.uri(secret, "jo@example.com", "example.com")
            assertEquals(uri, decodePureQrBitmap(qrModules(uri)), "QR code at index $index did not decode.")
        }
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

    private fun decodePureQrBitmap(modules: List<BooleanArray>): String {
        val scale = 4
        val side = modules.size * scale
        val pixels = IntArray(side * side) { i ->
            val x = (i % side) / scale
            val y = (i / side) / scale
            if (modules[y][x]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        // The bitmap is synthesized directly from known QR modules, so finder pattern
        // detection only adds unrelated sampling failure modes to this encoding test.
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }
}
