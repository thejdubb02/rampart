package org.rampart

import java.util.Base64
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
        assertTrue(url.startsWith("data:text/html;charset=utf-8;base64,"), url.take(50))
        return String(Base64.getDecoder().decode(url.substringAfter("base64,")), Charsets.UTF_8)
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

    @Test
    fun `the url carries nothing but the document`() {
        // base64 of the bytes and nothing else, so there is no escaping step to get wrong.
        val document = "<p>plain</p>"
        assertEquals(
            "data:text/html;charset=utf-8;base64," +
                Base64.getEncoder().encodeToString(document.toByteArray(Charsets.UTF_8)),
            asUrl(document),
        )
    }
}
