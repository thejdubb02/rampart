package org.rampart

import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opening one message, from click to a page ready to draw, split into its parts.
 *
 * Against [FakeJmapServer] at 40 ms a request, with a newsletter shaped message: about
 * 60 KB of nested tables and three inline pictures. The parts are the ones loadCard in
 * Main.kt runs in order: the fetch, the pictures, and the local work of building the page.
 * The render itself happens in the engine and is recorded by the app as
 * `message.open.render` (see WebBody.kt), because it needs a window.
 *
 * Also times what was cut from the local work, decoding every picture to a bitmap nobody
 * drew, so the saving stays measured rather than remembered.
 */
class OpenTimingTest {
    private fun median(samples: List<Double>) = samples.sorted()[samples.size / 2]

    @Test
    fun `opening a message, split into its parts`() {
        FakeJmapServer(latencyMs = 40).use { server ->
            val jmap = server.client()
            val fetch = mutableListOf<Double>()
            val pictures = mutableListOf<Double>()
            val local = mutableListOf<Double>()
            val cut = mutableListOf<Double>()
            fun ms(from: Long) = (System.nanoTime() - from) / 1_000_000.0
            (0 until 25).forEach { n ->
                val id = "m${300 + n * 3}"
                var at = System.nanoTime()
                val opened = jmap.open(id)
                val fetched = ms(at)
                at = System.nanoTime()
                val bytes = cidBytes(jmap, opened.body, opened.attachments)
                val got = ms(at)
                at = System.nanoTime()
                val reading = prepareReading("sender@example.org", "Sender", opened.body, opened.attachments, bytes, false, false, emptySet())
                val built = ms(at)
                at = System.nanoTime()
                val decoded = bytes.values.map { Image.makeFromEncoded(it).toComposeImageBitmap() }
                val decoding = ms(at)
                assertNotNull(reading.page)
                assertNotNull(opened.state, "the state rides on the open, so no second request stamps the copy")
                assertTrue(reading.images.isEmpty(), "pictures are decoded only where they are drawn")
                assertTrue(decoded.size == 3)
                // The first few warm the JVM and are not counted.
                if (n >= 5) { fetch += fetched; pictures += got; local += built; cut += decoding }
            }
            println("\nOpening a message at 40 ms a request, median of 20")
            println("fetch, one Email/get:                     %6.1f ms".format(median(fetch)))
            println("pictures, 3 of %,d bytes, side by side:   %6.1f ms".format(server.picture.size, median(pictures)))
            println("local work, building the page:            %6.1f ms".format(median(local)))
            println("cut: decoding the 3 pictures to bitmaps:  %6.1f ms".format(median(cut)))
        }
    }
}
