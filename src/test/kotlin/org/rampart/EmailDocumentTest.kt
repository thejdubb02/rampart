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

    private fun page(html: String, remote: Boolean = false, dark: Boolean = false) =
        emailDocument(html, remoteImages = remote, dark = dark)

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
    fun `a dark window inverts, unless the sender did it themselves`() {
        assertTrue(page("<p>hi</p>", dark = true).document.contains("invert(1)"))
        assertFalse(page("<p>hi</p>", dark = false).document.contains("invert(1)"))

        val own = """<style>@media (prefers-color-scheme: dark){body{background:#111}}</style><p>hi</p>"""
        assertFalse(
            page(own, dark = true).document.contains("invert(1)"),
            "a message that chose its own dark colours must not be turned inside out",
        )
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

    // ---- dark mode, and what it must not touch -----------------------------------------

    private fun inverts(html: String): Boolean =
        emailDocument(html, dark = true).document.contains("invert(1)")

    @Test
    fun `a plain message is turned inside out, because a white sheet in a dark window is worse`() {
        assertTrue(inverts("<p>Tuesday works.</p>"))
        // Explicit white is defensiveness about other clients, not a design, so it inverts.
        assertTrue(inverts("""<table width="100%" bgcolor="#ffffff"><tr><td>Hello</td></tr></table>"""))
    }

    @Test
    fun `a message that painted its own page is left exactly as it was sent`() {
        // The real one this was found on: a hotel's alert, a cream page and a dark brown
        // header bar. Inverted, the brown header came out pink.
        val duchamp =
            """<table width="100%" bgcolor="#F4F1EC"><tr><td>""" +
                """<table width="100%" bgcolor="#3B1F0B"><tr><td align="center">""" +
                """<h2>DUCHAMP</h2></td></tr></table></td></tr></table>"""
        assertFalse(inverts(duchamp))
    }

    @Test
    fun `the background counts wherever the sender put it`() {
        assertFalse(inverts("""<body bgcolor="#102030"><p>Hello</p></body>"""))
        assertFalse(inverts("""<body style="background-color:#102030"><p>Hello</p></body>"""))
        assertFalse(inverts("""<style>body { background: #102030; }</style><p>Hello</p>"""))
        // A fixed-width body, which is the other half of how every template is built.
        assertFalse(inverts("""<table width="600" bgcolor="#102030"><tr><td>Hi</td></tr></table>"""))
        assertFalse(inverts("""<div style="width:100%;background:#102030">Hi</div>"""))
    }

    @Test
    fun `a sender who did dark mode themselves is still left alone`() {
        assertFalse(
            inverts("<style>@media (prefers-color-scheme: dark) { body { background: #000 } }</style><p>Hi</p>"),
        )
    }

    @Test
    fun `a tinted box inside an otherwise plain message is not a design`() {
        // Only a full-width wrapper counts. A quote block or a callout must not stop the
        // inversion, or an ordinary message becomes a white sheet in a dark window.
        assertTrue(inverts("""<p>See below.</p><table width="300" bgcolor="#eeeeee"><tr><td>Quoted</td></tr></table>"""))
        assertTrue(inverts("""<p>See below.</p><td bgcolor="#eeeeee">Quoted</td>"""))
        // A max-width on a wrapper is a responsive hint, not a stated width.
        assertTrue(inverts("""<div style="max-width:600px">Hello</div>"""))
    }

    @Test
    fun `a light window never inverts anything, whatever the sender did`() {
        val page = emailDocument("""<body bgcolor="#102030"><p>Hi</p></body>""", dark = false)
        assertFalse(page.document.contains("invert(1)"))
    }

    private fun scheme(html: String, dark: Boolean): String =
        Regex("""color-scheme" content="([^"]+)"""").find(emailDocument(html, dark = dark).document)
            ?.groupValues?.get(1).orEmpty()

    @Test
    fun `a message left uninverted is never told to use dark defaults`() {
        // The 0.1.114 bug, and the worst kind: the message renders, with the sender's own
        // light background and the engine's white text on top of it, so the body is an
        // empty grey box and nothing anywhere reports a fault.
        val painted = """<table width="100%" bgcolor="#F4F1EC"><tr><td>Hello</td></tr></table>"""
        assertEquals("light", scheme(painted, dark = true))
        // A reply with a background and no colour of its own is the shape it happened on.
        assertEquals("light", scheme("""<div style="width:100%;background:#f5f5f5">Hi</div>""", dark = true))
    }

    @Test
    fun `a message that is being inverted still gets the dark defaults`() {
        assertEquals("dark light", scheme("<p>Tuesday works.</p>", dark = true))
    }

    @Test
    fun `a light window always asks for light, whatever the message did`() {
        assertEquals("light", scheme("<p>Hi</p>", dark = false))
        assertEquals("light", scheme("""<body bgcolor="#102030">Hi</body>""", dark = false))
    }

    @Test
    fun `the scheme and the inversion never disagree`() {
        // The invariant behind the bug: dark defaults are only ever correct when the whole
        // page is about to be turned inside out.
        listOf(
            "<p>plain</p>",
            """<table width="100%" bgcolor="#eeeeee"><tr><td>painted</td></tr></table>""",
            """<body bgcolor="#123456">painted on body</body>""",
            "<style>@media (prefers-color-scheme: dark) { body { background: #000 } }</style><p>own</p>",
        ).forEach { html ->
            val page = emailDocument(html, dark = true).document
            val inverted = page.contains("invert(1)")
            val darkScheme = """content="dark light"""" in page
            assertEquals(inverted, darkScheme, "scheme and inversion disagree for: $html")
        }
    }
}
