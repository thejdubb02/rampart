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
}
