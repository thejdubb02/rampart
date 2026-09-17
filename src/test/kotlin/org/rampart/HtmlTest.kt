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
        return renderHtml(html, Color.Blue, Color.Gray) { clicked += it }
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
    fun `the space before a link is not part of the link`() {
        val r = render("""<p>on our site at <a href="https://example.com/rates">example.com/rates</a>, yes</p>""")
        assertEquals("on our site at example.com/rates, yes", r.text.text)
        val link = links(r).single()
        assertEquals("example.com/rates", r.text.text.substring(link.start, link.end))
    }

    @Test
    fun `remote images are counted and never drawn`() {
        val r = render("""<p>Hi</p><img src="https://tracker.example/pixel.gif"><img src="//x/p.gif">""")
        assertEquals(2, r.blockedImages)
        assertFalse(r.text.text.contains("tracker"))
    }

    /**
     * An image the message carries with it is drawn from the message's own parts and tells
     * the sender nothing, so it is not something that was blocked. Counting it here put
     * "1 image was not loaded" above an image that was right there on screen.
     */
    @Test
    fun `an image the message carries is not a blocked one`() {
        val r = render("""<p>Hi</p><img src="cid:logo"><img src="CID:UPPER">""")
        assertEquals(0, r.blockedImages)
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
