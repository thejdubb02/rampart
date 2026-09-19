package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one thing worth a test here: what the engine is handed is what was built.
 *
 * `loadContent` corrupted it, and corrupted it silently and only for characters most mail
 * does not contain, so it shipped and was found by a ticket emoji arriving as six
 * replacement characters. A round trip is the whole check.
 */
class WebBodyTest {

    private fun roundTrip(document: String): String {
        val url = asUrl(document)
        assertTrue(url.startsWith("file:"), url.take(60))
        // Read back as bytes and decoded here, which is exactly what the engine does with
        // the charset the document states.
        return String(
            java.nio.file.Files.readAllBytes(java.nio.file.Path.of(java.net.URI.create(url))),
            Charsets.UTF_8,
        )
    }

    @Test
    fun `a character outside the Basic Multilingual Plane survives`() {
        // U+1F3AB is a surrogate pair in a Java string, which is what did not survive.
        val document = "<html><body><h3>🎫 New help ticket #4</h3></body></html>"
        assertEquals(document, roundTrip(document))
    }

    @Test
    fun `the punctuation every newsletter uses survives`() {
        // These went the same way as the emoji and are in far more mail: an en dash in a
        // date range and an em dash in a sentence are in most designed email.
        val document = "<p>Week of August 17–21 — your progress page   café ’</p>"
        assertEquals(document, roundTrip(document))
    }

    @Test
    fun `a whole message round trips unchanged`() {
        val page = emailDocument("""<div dir="ltr">Hi 🎫, the quote is 640,00 €.</div>""")
        assertEquals(page.document, roundTrip(page.document))
    }

    // ---- how big the page comes out ---------------------------------------------------

    @Test
    fun `an engine that scales the same as the window changes nothing`() {
        // The ordinary case, and the one where a correction would be the bug.
        assertEquals(1.0, zoomFor(density = 1f, scale = 1f, engineScale = 1f))
        assertEquals(1.0, zoomFor(density = 1.5f, scale = 1f, engineScale = 1.5f))
        assertEquals(1.0, zoomFor(density = 2f, scale = 1f, engineScale = 2f))
    }

    @Test
    fun `an engine that ignores the display scale is corrected back up`() {
        // The fault: the window laid out at 150% and the page laid out at 100%, so the
        // message is drawn two thirds the size of the interface around it.
        assertEquals(1.5, zoomFor(density = 1.5f, scale = 1f, engineScale = 1f))
        assertEquals(2.0, zoomFor(density = 2f, scale = 1f, engineScale = 1f))
    }

    @Test
    fun `the reader's own preference multiplies whatever the correction was`() {
        assertEquals(1.15, zoomFor(density = 1f, scale = 1.15f, engineScale = 1f), 0.001)
        // And it still means the same thing on a scaled display, which is the point of
        // correcting first: "larger" is larger than matching, not larger than 100 percent.
        assertEquals(1.725, zoomFor(density = 1.5f, scale = 1.15f, engineScale = 1f), 0.001)
        assertEquals(1.15, zoomFor(density = 1.5f, scale = 1.15f, engineScale = 1.5f), 0.001)
    }

    @Test
    fun `nothing can make a message unreadable in either direction`() {
        assertEquals(0.5, zoomFor(density = 0.1f, scale = 0.1f, engineScale = 4f))
        assertEquals(3.0, zoomFor(density = 4f, scale = 4f, engineScale = 1f))
        // An engine that reports nonsense must not divide by it.
        assertEquals(3.0, zoomFor(density = 1f, scale = 1f, engineScale = 0f))
    }

    @Test
    fun `a document of any size is handed over whole`() {
        // The ceiling a data URL had, and the reason this is a file: a signature with a
        // 700 KB picture in it makes a document of nearly a megabyte, and at that size the
        // picture came out blank while the text around it was fine.
        val big = "<p>x</p>" + "<span>padding</span>".repeat(60_000)
        assertEquals(big, roundTrip(big))
    }

    @Test
    fun `the file goes away when the next message is opened`() {
        val first = java.nio.file.Path.of(java.net.URI.create(asUrl("<p>one</p>")))
        assertTrue(java.nio.file.Files.exists(first))
        asUrl("<p>two</p>")
        assertTrue(!java.nio.file.Files.exists(first), "the previous message was left behind")
    }
}
