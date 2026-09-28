package org.rampart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The dark and light squares of a QR code for [text], one row per list entry, with the
 * four module quiet zone the standard asks for already around the edge.
 *
 * Medium error correction, which is what authenticator setup codes normally use: enough
 * to survive a glare on a laptop screen without making the code so dense a phone camera
 * has to be held still to read it.
 */
internal fun qrModules(text: String): List<BooleanArray> {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 4,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    // Asking for size zero gets one pixel per module, so the matrix is the code itself
    // rather than a scaled picture of it, and drawing it at any size stays sharp.
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    return (0 until matrix.height).map { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
}

/**
 * A QR code, always black on white.
 *
 * Never themed. A phone camera reads dark squares on a light ground, and a code drawn in
 * a dark theme's colours, light on dark, is one that many authenticator apps will not
 * scan at all.
 */
@Composable
internal fun QrCode(text: String, description: String, size: Dp = 196.dp, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) }
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        drawRect(Color.White)
        val count = modules.size
        if (count == 0) return@Canvas
        val cell = this.size.minDimension / count
        modules.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                // A hair of overlap so neighbouring squares never show a seam of white
                // between them when the cell size is not a whole number of pixels.
                if (dark) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
