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

    // --- Underline and strikethrough --------------------------------------------------

    @Test
    fun `underline and strikethrough`() {
        assertEquals("<div>a <u>b</u> c</div>", markupToHtml("a __b__ c"))
        assertEquals("<div>a <s>b</s> c</div>", markupToHtml("a ~~b~~ c"))
    }

    @Test
    fun `underline does not steal a single underscore in a name`() {
        // max_retries must stay ordinary text, not become styled at the first underscore
        // and then close on some later, unrelated one.
        assertEquals("<div>max_retries is set</div>", markupToHtml("max_retries is set"))
    }

    @Test
    fun `underline and strikethrough lose their markers in plain text`() {
        assertEquals("a b c", markupToPlain("a __b__ c"))
        assertEquals("a b c", markupToPlain("a ~~b~~ c"))
    }

    @Test
    fun `all four inline markers coexist on one line`() {
        assertEquals(
            "<div><b>a</b> <i>b</i> <u>c</u> <s>d</s></div>",
            markupToHtml("**a** *b* __c__ ~~d~~"),
        )
    }

    // --- Headings -----------------------------------------------------------------------

    @Test
    fun `headings become h1 and h2`() {
        assertEquals("<h1>Title</h1>", markupToHtml("# Title"))
        assertEquals("<h2>Subtitle</h2>", markupToHtml("## Subtitle"))
    }

    @Test
    fun `a heading needs the space, and a hash without one is just a hash`() {
        // "## " does not start with "# ", so the two never collide in either direction, and
        // "#plain" with no space after it is not a heading at all.
        assertEquals("<h2>Sub</h2>", markupToHtml("## Sub"))
        assertEquals("<div>#plain</div>", markupToHtml("#plain"))
    }

    @Test
    fun `a heading keeps its text and loses the hashes in plain text`() {
        assertEquals("Title", markupToPlain("# Title"))
        assertEquals("Subtitle", markupToPlain("## Subtitle"))
    }

    // --- Quote and code -------------------------------------------------------------------

    @Test
    fun `a quoted line becomes a blockquote`() {
        assertEquals("<blockquote><div>hi</div></blockquote>", markupToHtml("> hi"))
    }

    @Test
    fun `consecutive quoted lines are one blockquote`() {
        assertEquals(
            "<blockquote><div>a</div><div>b</div></blockquote><div>after</div>",
            markupToHtml("> a\n> b\nafter"),
        )
    }

    @Test
    fun `a quote keeps its marker in plain text, and bold inside it still strips`() {
        assertEquals("> hi", markupToPlain("> hi"))
        assertEquals("> a b", markupToPlain("> a **b**"))
    }

    @Test
    fun `inline code becomes a code tag`() {
        assertEquals("<div>a <code>b()</code> c</div>", markupToHtml("a `b()` c"))
    }

    @Test
    fun `inline code keeps its backticks in plain text`() {
        assertEquals("a `b()` c", markupToPlain("a `b()` c"))
    }

    @Test
    fun `a fenced block becomes pre code`() {
        assertEquals("<pre><code>foo()\nbar()</code></pre>", markupToHtml("```\nfoo()\nbar()\n```"))
    }

    @Test
    fun `a fence is never read as markdown inside it`() {
        assertEquals("<pre><code>**not bold**</code></pre>", markupToHtml("```\n**not bold**\n```"))
    }

    @Test
    fun `a fenced block is untouched in plain text, backticks and all`() {
        assertEquals("```\nfoo()\n```", markupToPlain("```\nfoo()\n```"))
    }

    // --- Alignment ----------------------------------------------------------------------

    @Test
    fun `alignment renders as a styled div`() {
        assertEquals("""<div style="text-align:center">hi</div>""", markupToHtml("::center:: hi"))
        assertEquals("""<div style="text-align:right">hi</div>""", markupToHtml("::right:: hi"))
    }

    @Test
    fun `alignment has no plain text spelling, so only the marker goes`() {
        assertEquals("hi", markupToPlain("::center:: hi"))
        assertEquals("hi", markupToPlain("::right:: hi"))
    }

    @Test
    fun `align sets the marker on the touched lines`() {
        val value = TextFieldValue("one\ntwo", TextRange(1))
        assertEquals("::center:: one\ntwo", alignLines(value, "center").text)
    }

    @Test
    fun `pressing the same alignment again clears it`() {
        val value = TextFieldValue("::center:: one", TextRange(0, 14))
        assertEquals("one", alignLines(value, "center").text)
    }

    @Test
    fun `switching alignment replaces the marker rather than stacking it`() {
        val value = TextFieldValue("::right:: one", TextRange(0, 13))
        assertEquals("::center:: one", alignLines(value, "center").text)
    }

    @Test
    fun `align left clears whichever alignment was set`() {
        val value = TextFieldValue("::right:: one", TextRange(0, 13))
        assertEquals("one", alignLines(value, null).text)
    }

    // --- Clear formatting -----------------------------------------------------------------

    @Test
    fun `clear formatting strips every inline marker from the selection`() {
        val value = TextFieldValue("**a** and __b__", TextRange(0, 15))
        assertEquals("a and b", clearFormatting(value).text)
    }

    @Test
    fun `clear formatting strips a line marker and its inline markers together`() {
        val value = TextFieldValue("- **hi**", TextRange(4))
        assertEquals("hi", clearFormatting(value).text)
    }

    @Test
    fun `clear formatting with nothing selected acts on the current line only`() {
        val value = TextFieldValue("# Title\nordinary *text*", TextRange(20))
        assertEquals("# Title\nordinary text", clearFormatting(value).text)
    }

    @Test
    fun `clear formatting drops a link's address, keeping only the label`() {
        val text = "[site](https://example.org)"
        val value = TextFieldValue(text, TextRange(0, text.length))
        assertEquals("site", clearFormatting(value).text)
    }

    // --- Active marks, for the toolbar -----------------------------------------------------

    @Test
    fun `the caret inside a bold word reads as bold`() {
        val value = TextFieldValue("a **loud** b", TextRange(5))
        assertTrue("bold" in activeMarks(value))
    }

    @Test
    fun `the caret outside any marker is not bold`() {
        val value = TextFieldValue("a **loud** b", TextRange(0))
        assertFalse("bold" in activeMarks(value))
    }

    @Test
    fun `a heading line reads as h1, and an ordinary line reads as align-left`() {
        val heading = TextFieldValue("# Title", TextRange(2))
        assertTrue("h1" in activeMarks(heading))
        val ordinary = TextFieldValue("just text", TextRange(2))
        assertTrue("align-left" in activeMarks(ordinary))
        assertFalse("align-center" in activeMarks(ordinary))
    }

    // --- Styling never changes the text, even with every new marker in play ---------------

    @Test
    fun `styling leaves every character where it was, with the new markers too`() {
        val source = "# Title\n> quoted __underline__ ~~strike~~ `code`\n::center:: hi\n```\nfence\n```"
        assertEquals(source, styleMarkup(source).text)
    }

    // --- Undo and redo --------------------------------------------------------------------

    @Test
    fun `undo returns the value pushed before the edit, redo returns after it`() {
        val history = UndoHistory()
        val before = TextFieldValue("a")
        val after = TextFieldValue("ab")
        history.push(before)
        assertEquals(before, history.undo(after))
        assertEquals(after, history.redo(before))
    }

    @Test
    fun `undo with nothing pushed is null`() {
        assertEquals(null, UndoHistory().undo(TextFieldValue("x")))
    }

    @Test
    fun `a run of typing within the coalesce window is one step to undo`() {
        val history = UndoHistory(coalesceMs = 1000)
        val v0 = TextFieldValue("a")
        val v1 = TextFieldValue("ab")
        val v2 = TextFieldValue("abc")
        history.type(v0, v1, now = 0)
        history.type(v1, v2, now = 200)
        // One undo from v2 goes all the way back to v0: the run was never split.
        assertEquals(v0, history.undo(v2))
    }

    @Test
    fun `a pause past the coalesce window starts a new undo step`() {
        val history = UndoHistory(coalesceMs = 500)
        val v0 = TextFieldValue("a")
        val v1 = TextFieldValue("ab")
        val v2 = TextFieldValue("abc")
        history.type(v0, v1, now = 0)
        history.type(v1, v2, now = 900) // past the window: a new step
        assertEquals(v1, history.undo(v2))
        assertEquals(v0, history.undo(v1))
    }

    @Test
    fun `history is capped, oldest dropped first`() {
        val history = UndoHistory(limit = 3)
        var current = TextFieldValue("0")
        for (n in 1..5) {
            history.push(current)
            current = TextFieldValue(n.toString())
        }
        // Five pushes, capped at three: undo three times gets back to the value pushed on
        // the third-from-last edit, and a fourth undo has nothing left.
        var v: TextFieldValue? = current
        repeat(3) { v = history.undo(v!!) }
        assertEquals(null, history.undo(v!!))
    }

    @Test
    fun `a toolbar edit is always its own step, however close to the last one`() {
        val history = UndoHistory(coalesceMs = 1000)
        val v0 = TextFieldValue("a")
        val v1 = TextFieldValue("ab")
        val v2 = TextFieldValue("**ab**")
        history.type(v0, v1, now = 0)
        history.push(v1) // a toolbar button, right after typing: still its own step
        assertEquals(v1, history.undo(v2))
        assertEquals(v0, history.undo(v1))
    }

    @Test
    fun `text typed and then deleted is still one undo away`() {
        // The fault this guards: coalescing on time alone folded the deletion into the run
        // of typing before it, so one Undo went straight past the words that were typed to
        // the empty field they started from. That text is exactly what Undo is reached for.
        val history = UndoHistory(coalesceMs = 1000)
        val empty = TextFieldValue("")
        val typed = TextFieldValue("hello")
        val cleared = TextFieldValue("")
        history.type(empty, typed, now = 0)
        history.type(typed, cleared, now = 100)
        assertEquals(typed, history.undo(cleared))
    }

    @Test
    fun `replacing a selection is its own step even inside the window`() {
        val history = UndoHistory(coalesceMs = 1000)
        val before = TextFieldValue("hello", TextRange(0, 5))
        val after = TextFieldValue("hey")
        history.type(TextFieldValue(""), before, now = 0)
        history.type(before, after, now = 100)
        assertEquals(before, history.undo(after))
    }
}
