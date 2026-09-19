package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The document handed to the engine is the security boundary, so these are mostly about
 * what does not survive being cleaned rather than what does.
 */
class EmailDocumentTest {

    private fun page(html: String, remote: Boolean = false) =
        emailDocument(html, remoteImages = remote)

    @Test
    fun `nothing that executes gets through`() {
        val hostile = """
            <p onclick="steal()" onmouseover="steal()">Hello</p>
            <script>steal()</script>
            <iframe src="https://example.org/"></iframe>
            <object data="x.swf"></object><embed src="x.swf">
            <form action="https://example.org/"><input name="password"></form>
            <a href="javascript:steal()">click</a>
            <svg><script>steal()</script></svg>
            <base href="https://example.org/">
        """.trimIndent()
        val out = page(hostile).document

        listOf("<script", "onclick", "onmouseover", "<iframe", "<object", "<embed", "<form",
            "<input", "javascript:", "<svg", "<base").forEach {
            assertFalse(out.contains(it, ignoreCase = true), "$it survived the clean")
        }
        // The words are still there: cleaning is not the same as deleting the message.
        assertTrue(out.contains("Hello"))
        assertTrue(out.contains("click"))
    }

    @Test
    fun `what a designed message is actually built out of survives`() {
        val designed = """
            <style>@media (max-width:600px){.col{width:100%}}</style>
            <table width="600" bgcolor="#ffffff" cellpadding="0" cellspacing="0" align="center">
              <tr><td class="col" align="center" style="padding:36px 0;font-size:28px">Heading</td></tr>
            </table>
        """.trimIndent()
        val out = page(designed).document

        // Every one of these was dropped by the old safelist, and each one dropped is a
        // piece of the layout the sender built.
        listOf("<style", "max-width:600px", "width=\"600\"", "bgcolor", "cellpadding",
            "align=\"center\"", "class=\"col\"", "padding:36px 0").forEach {
            assertTrue(out.contains(it), "$it did not survive")
        }
    }

    @Test
    fun `a picture the message carries is drawn, one on the web is held back and counted`() {
        val html = """
            <img src="cid:logo@example.org">
            <img src="https://tracker.example.org/open.gif">
            <img src="https://tracker.example.org/hero.png">
        """.trimIndent()
        val out = emailDocument(
            html,
            carried = mapOf("logo@example.org" to dataUri("image/png", byteArrayOf(0, 0, 0))),
            remoteImages = false,
        )

        assertEquals(2, out.blocked)
        assertTrue(out.document.contains("data:image/png;base64,AAAA"))
        // Parsed rather than matched as text, because data-blocked-src ends in "src" and a
        // string search for one finds the other.
        org.jsoup.Jsoup.parse(out.document).select("img").forEach {
            assertFalse(it.attr("src").startsWith("http"), "a blocked address is still the src")
        }
        // Kept on the element, because Show pictures has to know what to ask for.
        assertTrue(out.document.contains("data-blocked-src=\"https://tracker.example.org/hero.png\""))
        assertTrue(out.document.contains("img-src data:;"), "the policy must not allow a fetch")
    }

    @Test
    fun `agreeing to pictures lets them through, and only then`() {
        val html = """<img src="https://tracker.example.org/hero.png">"""
        val allowed = emailDocument(html, remoteImages = true)

        assertEquals(0, allowed.blocked)
        assertTrue(allowed.document.contains("src=\"https://tracker.example.org/hero.png\""))
        assertTrue(allowed.document.contains("img-src data: http: https:"))
        // Still no script and no frame, however the reader answered about pictures.
        assertTrue(allowed.document.contains("script-src 'none'"))
    }

    @Test
    fun `a background picture is a fetch too`() {
        val html = """<td background="https://t.example.org/bg.png" style="background:url('https://t.example.org/b.png')">x</td>"""
        val out = page(html).document

        assertFalse(out.contains("bg.png"), "a background attribute is a request like any other")
        assertFalse(out.contains("b.png"), "so is one in CSS")
    }

    @Test
    fun `a data URI in CSS is the message's own bytes and stays`() {
        val html = """<div style="background:url(data:image/gif;base64,R0lGOD)">x</div>"""
        assertTrue(page(html).document.contains("data:image/gif;base64,R0lGOD"))
    }

    @Test
    fun `a cid with no matching part leaves no broken picture behind`() {
        val out = page("""<p>text</p><img src="cid:missing@example.org">""").document
        assertFalse(out.contains("cid:"))
        assertTrue(out.contains("text"))
    }

    @Test
    fun `bytes become a data URI the engine can draw`() {
        assertEquals("data:image/png;base64,AAAA", dataUri("image/png", byteArrayOf(0, 0, 0)))
        // A part that does not say what it is still has to be addressable.
        assertTrue(dataUri("", byteArrayOf(0)).startsWith("data:application/octet-stream;base64,"))
        // Parameters are not part of the type in a data URI.
        assertTrue(dataUri("image/png; name=logo.png", byteArrayOf(0)).startsWith("data:image/png;base64,"))
    }

    // ---- what a message gets given, and what it must be left to do itself ---------------

    /**
     * Whether the message was handed the plain-mail stylesheet.
     *
     * The old version of this section asked whether a message was inverted for a dark
     * window. Inversion is gone: it turned an uncoloured reply into white text on a black
     * slab, and it turned a design into one nobody made. The question underneath it is
     * still exactly as live, because the same test now decides whether Rampart supplies a
     * font, a padding and a wrapping rule, so every case below is kept.
     */
    private fun styled(html: String): Boolean =
        emailDocument(html).document.contains("word-break: break-word")

    @Test
    fun `a message with no design of its own is given one, because Times at the pane edge is not a design`() {
        assertTrue(styled("<p>Tuesday works.</p>"))
        // The shape this was found on: a reply typed into a webmail box, with nothing in it.
        assertTrue(styled("""<div dir="ltr"><div>Mark,</div><div><br></div><div>We sent a letter.</div></div>"""))
        // Explicit white is defensiveness about other clients, not a design.
        assertTrue(styled("""<table width="100%" bgcolor="#ffffff"><tr><td>Hello</td></tr></table>"""))
    }

    @Test
    fun `a message that painted its own page is left exactly as it was sent`() {
        // The real one this was found on: a hotel's alert, a cream page and a dark brown
        // header bar. Padding round it would frame a full-bleed header in white.
        val duchamp =
            """<table width="100%" bgcolor="#F4F1EC"><tr><td>""" +
                """<table width="100%" bgcolor="#3B1F0B"><tr><td align="center">""" +
                """<h2>DUCHAMP</h2></td></tr></table></td></tr></table>"""
        assertFalse(styled(duchamp))
    }

    @Test
    fun `the background counts wherever the sender put it`() {
        assertFalse(styled("""<body bgcolor="#102030"><p>Hello</p></body>"""))
        assertFalse(styled("""<body style="background-color:#102030"><p>Hello</p></body>"""))
        assertFalse(styled("""<style>body { background: #102030; }</style><p>Hello</p>"""))
        // A fixed-width body, which is the other half of how every template is built.
        assertFalse(styled("""<table width="600" bgcolor="#102030"><tr><td>Hi</td></tr></table>"""))
        assertFalse(styled("""<div style="width:100%;background:#102030">Hi</div>"""))
    }

    @Test
    fun `a sender who wrote a dark mode of their own has a design`() {
        assertFalse(
            styled("<style>@media (prefers-color-scheme: dark) { body { background: #000 } }</style><p>Hi</p>"),
        )
    }

    @Test
    fun `a tinted box inside an otherwise plain message is not a design`() {
        // Only a full-width wrapper counts. A quote block or a callout must not stop the
        // styling, or an ordinary reply is back to Times at the edge of the pane.
        assertTrue(styled("""<p>See below.</p><table width="300" bgcolor="#eeeeee"><tr><td>Quoted</td></tr></table>"""))
        assertTrue(styled("""<p>See below.</p><td bgcolor="#eeeeee">Quoted</td>"""))
        // A max-width on a wrapper is a responsive hint, not a stated width.
        assertTrue(styled("""<div style="max-width:600px">Hello</div>"""))
    }

    @Test
    fun `a message with no colours follows the window, and one with a design never does`() {
        fun drawnDark(html: String) = emailDocument(html, dark = true).document.contains("background: #191417")
        // A reply with nothing in it: the ordinary case, and the one this is for.
        assertTrue(drawnDark("""<div dir="ltr">Mark,</div>"""))
        assertTrue(drawnDark("<p>Tuesday works.</p>"))
        // A design is never touched, in either window. Inverting one was the pink header.
        assertFalse(drawnDark("""<table width="100%" bgcolor="#F4F1EC"><tr><td>Hello</td></tr></table>"""))
        assertFalse(drawnDark("""<body bgcolor="#102030"><p>Hello</p></body>"""))
        // And a light window draws nothing dark, whatever the message is.
        assertFalse(emailDocument("<p>Tuesday works.</p>").document.contains("background: #191417"))
    }

    @Test
    fun `black text a sender stated is given the page's colour back`() {
        // Outlook writes color:black on nearly every span it produces, and on a dark page
        // that is an empty message. The handful of ways of writing it are covered; anything
        // missed is what the switch on the toolbar is for.
        val dark = emailDocument("""<span style="color:black">Hi</span>""", dark = true).document
        listOf("""[style*="color:black"]""", """[style*="color:#000"]""", """font[color="black"]""")
            .forEach { assertTrue(dark.contains(it), "no rule for $it") }
        // Not imposed on a light page, where black on white is exactly right.
        assertFalse(emailDocument("""<span style="color:black">Hi</span>""").document.contains("""[style*="color:black"]"""))
    }

    @Test
    fun `nothing is ever inverted, in either window`() {
        /*
         * The whole of what replaced inversion, and the reason it is one line.
         *
         * `color-scheme: dark` tells the engine to use dark defaults, which is white text
         * on a dark canvas, and there is no message for which that is right: one with a
         * design supplies its own colours, and one without gets ours. The two ways this
         * went wrong both came from the page and the window disagreeing about which it was.
         */
        listOf(
            "<p>plain</p>",
            """<div dir="ltr">a reply with nothing in it</div>""",
            """<table width="100%" bgcolor="#eeeeee"><tr><td>painted</td></tr></table>""",
            """<body bgcolor="#123456">painted on body</body>""",
            "<style>@media (prefers-color-scheme: dark) { body { background: #000 } }</style><p>own</p>",
        ).forEach { html ->
            listOf(false, true).forEach { dark ->
                val document = emailDocument(html, dark = dark).document
                assertFalse(document.contains("invert("), "something was inverted: $html")
                // The scheme is stated by the stylesheet above, so the engine is never asked
                // to supply defaults of its own. That disagreement was the 0.1.114 bug.
                assertEquals(
                    "light",
                    Regex("""color-scheme" content="([^"]+)"""").find(document)?.groupValues?.get(1),
                    "the engine must never pick its own colours: $html",
                )
            }
        }
    }

    @Test
    fun `the page always paints, so the window never shows through it`() {
        // The panel behind the engine is transparent, so a message that painted nothing
        // showed the dark window between its own tables.
        listOf(
            "<p>plain</p>" to false,
            """<table width="100%" bgcolor="#eeeeee"><tr><td>x</td></tr></table>""" to false,
            """<table width="100%" bgcolor="#eeeeee"><tr><td>x</td></tr></table>""" to true,
        ).forEach { (html, dark) ->
            assertTrue(emailDocument(html, dark = dark).document.contains("background: #ffffff"), html)
        }
        assertTrue(emailDocument("<p>plain</p>", dark = true).document.contains("background: #191417"))
    }
}
