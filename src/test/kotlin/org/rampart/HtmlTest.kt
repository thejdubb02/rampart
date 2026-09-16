package org.rampart

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The renderer is the part that reads hostile input, so this is the check that has to bite. */
class HtmlTest {
    private fun render(html: String): Rendered {
        val clicked = mutableListOf<String>()
        return renderHtml(html, Color.Blue) { clicked += it }
    }

    private fun links(r: Rendered) = r.text.getLinkAnnotations(0, r.text.length)

    private fun AnnotatedString.Range<LinkAnnotation>.url() = (item as LinkAnnotation.Clickable).tag

    @Test
    fun `script and style never reach the page`() {
        val r = render("<style>body{color:red}</style><script>alert(1)</script><p>Hello</p>")
        assertEquals("Hello", r.text.text)
    }

    @Test
    fun `a javascript href is not a link`() {
        val r = render("""<a href="javascript:alert(1)">Click me</a>""")
        assertTrue(links(r).isEmpty(), "javascript: survived as a clickable link")
        assertEquals("Click me", r.text.text)
    }

    @Test
    fun `an http href is a link, and carries the url`() {
        val r = render("""<p>Go <a href="https://example.com/x?a=1">here</a>.</p>""")
        assertEquals("Go here.", r.text.text)
        assertEquals("https://example.com/x?a=1", links(r).single().url())
    }

    @Test
    fun `remote images are counted and never drawn`() {
        val r = render("""<p>Hi</p><img src="https://tracker.example/pixel.gif"><img src="cid:logo">""")
        assertEquals(2, r.blockedImages)
        assertFalse(r.text.text.contains("tracker"))
    }

    @Test
    fun `nested empty divs do not become a page of whitespace`() {
        val r = render("<div><div><div><p>One</p></div></div></div><div><p>Two</p></div>")
        assertEquals("One\n\nTwo", r.text.text)
    }

    @Test
    fun `plain text keeps its urls clickable`() {
        val r = renderText("See https://example.com/a, then stop.", Color.Blue) {}
        assertEquals("https://example.com/a", links(r).single().url())
        assertEquals("See https://example.com/a, then stop.", r.text.text)
    }
}
