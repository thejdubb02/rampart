package org.rampart

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RichTextTest {
    @Test
    fun `text with no markers is a div per line`() {
        assertEquals("<div>one</div><div>two</div>", markupToHtml("one\ntwo"))
    }

    @Test
    fun `a blank line is a break`() {
        assertEquals("<div>a</div><div><br></div><div>b</div>", markupToHtml("a\n\nb"))
    }

    @Test
    fun `bold and italic`() {
        assertEquals("<div>a <b>b</b> c</div>", markupToHtml("a **b** c"))
        assertEquals("<div>a <i>b</i> c</div>", markupToHtml("a *b* c"))
    }

    @Test
    fun `double asterisk wins over single`() {
        assertEquals("<div><b>loud</b></div>", markupToHtml("**loud**"))
    }

    @Test
    fun `an unclosed marker is literal`() {
        assertEquals("<div>2 * 3 = 6</div>", markupToHtml("2 * 3 = 6"))
        assertEquals("<div>**oops</div>", markupToHtml("**oops"))
    }

    @Test
    fun `markers do not span a line break`() {
        assertEquals("<div>*one</div><div>two*</div>", markupToHtml("*one\ntwo*"))
    }

    @Test
    fun `a link keeps its query string`() {
        assertEquals(
            """<div><a href="https://example.org/a?b=1&amp;c=2">site</a></div>""",
            markupToHtml("[site](https://example.org/a?b=1&c=2)"),
        )
    }

    /* The trust boundary. A composer is not a place a scheme can be smuggled through. */
    @Test
    fun `a javascript url is never a link`() {
        assertEquals(
            "<div>[click](javascript:alert(1))</div>",
            markupToHtml("[click](javascript:alert(1))"),
        )
        assertFalse(safeUrl("javascript:alert(1)"))
        assertFalse(safeUrl("data:text/html,<script>"))
        assertTrue(safeUrl("data:image/png;base64,AAAA"))
    }

    @Test
    fun `typed html arrives escaped`() {
        assertEquals(
            "<div>&lt;script&gt;alert(1)&lt;/script&gt;</div>",
            markupToHtml("<script>alert(1)</script>"),
        )
    }

    /*
     * The payload still appears in the output, and that is fine: what matters is that the
     * quote is escaped, so it stays inside the attribute value instead of ending it and
     * starting a new one. Asserted as the exact string, because "does not contain" is the
     * assertion that passes for the wrong reason.
     */
    @Test
    fun `a quote mark cannot break out of an href`() {
        assertEquals(
            """<div><a href="https://a.test/&quot;onmouseover=alert(1">x</a>)</div>""",
            markupToHtml("""[x](https://a.test/"onmouseover=alert(1))"""),
        )
    }

    @Test
    fun `consecutive bullets are one list`() {
        assertEquals("<ul><li>a</li><li>b</li></ul>", markupToHtml("- a\n- b"))
    }

    @Test
    fun `numbers make an ordered list`() {
        assertEquals("<ol><li>a</li><li>b</li></ol>", markupToHtml("1. a\n2. b"))
    }

    @Test
    fun `a list ends when the text does`() {
        assertEquals("<div>before</div><ul><li>a</li></ul><div>after</div>", markupToHtml("before\n- a\nafter"))
    }

    @Test
    fun `bold inside a list item`() {
        assertEquals("<ul><li>a <b>b</b></li></ul>", markupToHtml("- a **b**"))
    }

    @Test
    fun `an image becomes an img`() {
        assertEquals(
            """<div><img src="cid:x1" alt="a chart" style="max-width:100%"></div>""",
            markupToHtml("![a chart](cid:x1)"),
        )
    }

    @Test
    fun `empty in, empty out`() {
        assertEquals("", markupToHtml(""))
        assertEquals("", markupToHtml("   \n  "))
        assertEquals("", markupToPlain(""))
    }

    @Test
    fun `plain text loses the markers and keeps the address`() {
        assertEquals("a b c", markupToPlain("a **b** c"))
        assertEquals("a b c", markupToPlain("a *b* c"))
        assertEquals("site (https://example.org)", markupToPlain("[site](https://example.org)"))
        assertEquals("a chart", markupToPlain("![a chart](cid:x1)"))
    }

    @Test
    fun `plain text keeps list markers, which read fine`() {
        assertEquals("- a\n- b", markupToPlain("- a\n- b"))
    }

    /* The styling must never change the text, because the offset mapping is the identity. */
    @Test
    fun `styling leaves every character where it was`() {
        val source = "a **b** and [c](https://d.test)"
        assertEquals(source, styleMarkup(source).text)
    }

    @Test
    fun `bullets go on the line holding the caret`() {
        val value = TextFieldValue("one\ntwo", TextRange(5))
        assertEquals("one\n- two", prefixLines(value, "- ").text)
    }

    @Test
    fun `bullets on a selection prefix every line it touches`() {
        val value = TextFieldValue("one\ntwo\nthree", TextRange(1, 6))
        assertEquals("- one\n- two\nthree", prefixLines(value, "- ").text)
    }

    @Test
    fun `pressing bullets again takes them off`() {
        val value = TextFieldValue("- one\n- two", TextRange(0, 11))
        assertEquals("one\ntwo", prefixLines(value, "- ").text)
    }

    @Test
    fun `numbering counts up rather than repeating`() {
        val value = TextFieldValue("one\ntwo\nthree", TextRange(0, 13))
        assertEquals("1. one\n2. two\n3. three", prefixLines(value, "1. ").text)
    }

    @Test
    fun `pressing numbers again takes them off`() {
        val value = TextFieldValue("1. one\n2. two", TextRange(0, 13))
        assertEquals("one\ntwo", prefixLines(value, "1. ").text)
    }
}
