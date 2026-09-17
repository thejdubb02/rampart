package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two halves of a message have to say the same thing. A formatted sign-off in the HTML
 * part and a plain copy of it still sitting in the text part means anyone reading the HTML
 * sees the signature twice.
 */
class HtmlSignatureTest {
    private val text = "Justin Willhite\nWillhite Strategy Group"
    private val html = """<div style="color:#555"><b>Justin Willhite</b><br>Willhite Strategy Group</div>"""

    @Test
    fun theSignOffGoesAfterTheQuote() {
        val draft = Draft(from = "me@example.org", body = "Sounds good.\n\nOn Tue, Dana wrote:\n> hello")
        val out = signed(draft, text, html)
        assertTrue(out.body.endsWith("\n\n-- \n$text"), out.body)
        assertTrue(out.body.indexOf("> hello") < out.body.indexOf("-- "))
        assertEquals(text, out.textSignature)
        assertEquals(html, out.htmlSignature)
    }

    @Test
    fun theTextSignOffIsSwappedForTheHtmlOneRatherThanBothAppearing() {
        val draft = signed(Draft(from = "me@example.org", body = "Sounds good."), text, html)
        val built = htmlBodyOf(draft.body, draft.textSignature, draft.htmlSignature)!!
        assertTrue(built.endsWith(html), built)
        assertTrue("Willhite Strategy Group</div>" !in built.removeSuffix(html), "the plain copy is still in there")
        assertEquals(1, Regex("Willhite Strategy Group").findAll(built).count())
    }

    @Test
    fun noHtmlSignOffMeansNoHtmlPart() {
        assertNull(htmlBodyOf("Sounds good.", text, ""))
        assertNull(htmlBodyOf("Sounds good.", "", "   "))
    }

    /** What somebody typed must arrive as itself, not as the start of a tag. */
    @Test
    fun whatWasTypedIsEscaped() {
        val built = htmlBodyOf("2 < 3 & <b>not bold</b>", "", html)!!
        assertTrue("2 &lt; 3 &amp; &lt;b&gt;not bold&lt;/b&gt;" in built, built)
        assertTrue("<b>not bold" !in built.removeSuffix(html))
    }

    @Test
    fun blankLinesSurviveAsBlankLines() {
        assertEquals("<div>one</div><div><br></div><div>two</div>", htmlOf("one\n\ntwo"))
        assertEquals("", htmlOf("   \n  "))
    }

    /** The sign-off is HTML we wrote, so it is the one thing that is not escaped. */
    @Test
    fun theSignOffItselfIsNotEscaped() {
        val built = htmlBodyOf("Hi", "", html)!!
        assertTrue(built.endsWith(html))
        assertTrue("&lt;div" !in built.substring(built.length - html.length))
    }

    @Test
    fun aDraftThatIsAlreadySignedIsLeftAlone() {
        val once = signed(Draft(from = "me@example.org", body = "Hi"), text, html)
        assertEquals(once, signed(once, text, html))
    }
}
