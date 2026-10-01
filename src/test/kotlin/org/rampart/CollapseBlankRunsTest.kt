package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The blank runs a sender's editor leaves between two paragraphs. Each case is the
 * whole of one rule: take that rule out and the case no longer holds.
 */
class CollapseBlankRunsTest {
    @Test
    fun `a run of three or more breaks becomes two`() {
        assertEquals("<br><br>", collapseBlankRuns("<br><br><br>"))
        assertEquals("<br><br>", collapseBlankRuns("<br><br><br><br>"))
        assertEquals("<p>Hi<br><br>there</p>", collapseBlankRuns("<p>Hi<br><br><br>there</p>"))
        // Spacing, a self-closing slash and attributes are still a break.
        assertEquals(" <br/><br /> ", collapseBlankRuns(" <br/>\n<br />\n<br class=\"x\"> "))
        assertEquals("<br clear=\"all\"><br>", collapseBlankRuns("<br clear=\"all\"><br><BR/>"))
    }

    @Test
    fun `a run of empty paragraphs or divs becomes one`() {
        assertEquals(
            "<p>Keep</p><p></p><p>Mid</p><p>&nbsp;</p><p>After</p>",
            collapseBlankRuns(
                "<p>Keep</p><p></p><p>Mid</p><p>&nbsp;</p><p> </p><div><br></div><div>&#160;</div><p>After</p>",
            ),
        )
        assertEquals("<p>&nbsp;</p>", collapseBlankRuns("<p>&nbsp;</p>\n<p>&#160;</p>"))
        assertEquals("<div><br/></div>", collapseBlankRuns("<div><br/></div><div><br class=\"x\"></div>"))
        assertEquals("<p> </p>After", collapseBlankRuns("<p> </p><div>&nbsp;</div>After"))
    }

    @Test
    fun `breaks and empty blocks inside pre textarea or style stay`() {
        val pre = "<pre class=\"x\"><br><br><br><br><p></p><p>&nbsp;</p></pre>"
        val area = "<textarea><br/><br /><br></textarea>"
        val style = "<style type=\"text/css\"><br><br><br><p></p><div></div></style>"
        assertEquals(pre + area + style + "<br><br>", collapseBlankRuns(pre + area + style + "<br><br><br>"))
    }

    @Test
    fun `ordinary paragraphs and a pair of breaks stay as they are`() {
        val html = "<p>Hello there</p><br><br><p>See you<br><br>tomorrow</p>"
        assertEquals(html, collapseBlankRuns(html))
    }
}
