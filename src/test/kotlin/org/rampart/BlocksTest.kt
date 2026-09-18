package org.rampart

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.luminance

class BlocksTest {
    private fun cut(html: String): HtmlDoc =
        htmlBlocks(html, Color.Unspecified, Color.Unspecified) {}

    private fun words(doc: HtmlDoc) = doc.blocks.filterIsInstance<Block.Words>().map { it.text.text }

    @Test
    fun `plain paragraphs stay in one run, with the blank line between them`() {
        // Cutting on every paragraph would gain nothing: they draw the same either way, and
        // the blank line already comes from the inline walker. Blocks exist for the things
        // that cannot be drawn as text, which a paragraph can.
        val doc = cut("<p>First one.</p><p>Second one.</p>")
        assertEquals("First one.\n\nSecond one.", words(doc).single())
    }

    @Test
    fun `a div wrapped round a few words does not become its own paragraph`() {
        // A sentence broken across three divs is one sentence, and a mail client that puts
        // each on its own line is unreadable on exactly the mail people send by hand.
        val doc = cut("<p>The crew <div>will be</div> there by eight.</p>")
        assertEquals(1, doc.blocks.size)
        assertTrue(words(doc).single().contains("will be"))
    }

    @Test
    fun `a heading keeps its level`() {
        val doc = cut("<h2>What is included</h2><p>Everything.</p>")
        val heading = doc.blocks.filterIsInstance<Block.Words>().first()
        assertEquals(2, heading.level)
        assertEquals(0, doc.blocks.filterIsInstance<Block.Words>()[1].level)
    }

    @Test
    fun `a list keeps its items and whether it is numbered`() {
        val doc = cut("<ol><li>One</li><li>Two</li></ol>")
        val listing = doc.blocks.filterIsInstance<Block.Listing>().single()
        assertTrue(listing.ordered)
        assertEquals(listOf("One", "Two"), listing.items.map { it.text })
    }

    @Test
    fun `a quote nests rather than flattening`() {
        val quote = cut("<blockquote><p>Said before.</p></blockquote>")
            .blocks.filterIsInstance<Block.Quote>().single()
        assertEquals(listOf("Said before."), quote.inner.filterIsInstance<Block.Words>().map { it.text.text })
    }

    /** The text of a cell, for a test that does not care how it was nested. */
    private fun Block.Layout.Cell.words(): String =
        blocks.filterIsInstance<Block.Words>().joinToString(" ") { it.text.text }

    @Test
    fun `a table of figures stays a table, and is ruled`() {
        val grid = cut(
            "<table><tr><th>Item</th><th>Rate</th></tr>" +
                "<tr><td>Digging</td><td>640</td></tr></table>",
        ).blocks.filterIsInstance<Block.Layout>().single()
        assertEquals(2, grid.rows.size)
        assertEquals(listOf("Digging", "640"), grid.rows[1].cells.map { it.words() })
        assertTrue(grid.ruled, "a table of data should have lines between its rows")
    }

    @Test
    fun `a one cell wrapper is unwrapped, and the picture inside it survives`() {
        // Mail uses one cell tables as a wrapper far more often than as data. Drawing that
        // as a table puts a border and a column round the whole email, and reading it as
        // text loses the picture, which is usually the only thing in there.
        val doc = cut("""<table><tr><td><img src="cid:logo"><br>Dana</td></tr></table>""")
        assertTrue(doc.blocks.none { it is Block.Layout })
        assertEquals("cid:logo", doc.blocks.filterIsInstance<Block.Picture>().single().src)
    }

    @Test
    fun `a single row of figures stays side by side rather than stacking`() {
        // The morning report: six figures across, each with its label under it. Stacked,
        // it reads as a column of orphaned numbers, which is what it did.
        val doc = cut(
            "<table><tr>" +
                "<td><b>95.0%</b><div>Occupancy</div></td>" +
                "<td><b>19/20</b><div>Rooms</div></td>" +
                "<td><b>37</b><div>Guests</div></td>" +
                "</tr></table>",
        ).blocks.filterIsInstance<Block.Layout>().single()
        assertEquals(1, doc.rows.size)
        assertEquals(3, doc.rows[0].cells.size, "the figures were stacked instead of laid out")
        assertTrue(doc.rows[0].cells[0].words().contains("Occupancy"))
        assertTrue(!doc.ruled, "a layout table should not draw lines")
    }

    @Test
    fun `a two cell signature puts the logo beside the words`() {
        val row = cut(
            """<table><tr><td><img src="cid:logo"></td><td>Justin Willhite</td></tr></table>""",
        ).blocks.filterIsInstance<Block.Layout>().single().rows.single()
        assertEquals(2, row.cells.size)
        assertEquals("cid:logo", row.cells[0].blocks.filterIsInstance<Block.Picture>().single().src)
        assertEquals("Justin Willhite", row.cells[1].words())
    }

    @Test
    fun `a cell keeps the background it asks for, by attribute or by style`() {
        val row = cut(
            """<table><tr><td bgcolor="#3B1F0B">Duchamp</td>""" +
                """<td style="background-color:#fff">Healdsburg</td></tr></table>""",
        ).blocks.filterIsInstance<Block.Layout>().single().rows.single()
        assertEquals(0xFF3B1F0B.toInt(), row.cells[0].background)
        assertEquals(0xFFFFFFFF.toInt(), row.cells[1].background)
    }

    @Test
    fun `a stated width becomes the share of the row that cell takes`() {
        val row = cut(
            """<table><tr><td width="25%">a</td><td width="75%">b</td></tr></table>""",
        ).blocks.filterIsInstance<Block.Layout>().single().rows.single()
        assertEquals(25f, row.cells[0].weight)
        assertEquals(75f, row.cells[1].weight)
    }

    @Test
    fun `a table with no widths shares the row evenly`() {
        val row = cut("<table><tr><td>a</td><td>b</td></tr></table>")
            .blocks.filterIsInstance<Block.Layout>().single().rows.single()
        assertEquals(listOf(1f, 1f), row.cells.map { it.weight })
    }

    @Test
    fun `a table inside a cell draws itself rather than being stolen`() {
        val outer = cut(
            "<table><tr><td>Left</td><td><table><tr><td>x</td><td>y</td></tr></table></td></tr></table>",
        ).blocks.filterIsInstance<Block.Layout>().single()
        assertEquals(1, outer.rows.size, "the nested table's row was pulled into the outer one")
        val inner = outer.rows[0].cells[1].blocks.filterIsInstance<Block.Layout>().single()
        assertEquals(listOf("x", "y"), inner.rows[0].cells.map { it.words() })
    }

    @Test
    fun `a colour nobody can parse is no colour rather than a wrong one`() {
        val row = cut("""<table><tr><td bgcolor="rebeccapurple">a</td><td>b</td></tr></table>""")
            .blocks.filterIsInstance<Block.Layout>().single().rows.single()
        assertEquals(null, row.cells[0].background)
    }

    @Test
    fun `a picture keeps its place in the order`() {
        val doc = cut("""<p>Above.</p><img src="cid:x"><p>Below.</p>""")
        assertTrue(doc.blocks[0] is Block.Words)
        assertTrue(doc.blocks[1] is Block.Picture)
        assertTrue(doc.blocks[2] is Block.Words)
    }

    @Test
    fun `a script is gone before anything is drawn`() {
        val doc = cut("<p>Hello</p><script>alert(1)</script>")
        assertTrue(words(doc).none { it.contains("alert") })
    }

    @Test
    fun `a javascript src is dropped rather than carried into a picture`() {
        val src = cut("""<img src="javascript:alert(1)">""").blocks
            .filterIsInstance<Block.Picture>().firstOrNull()?.src
        assertTrue(src == null || src.isBlank(), "a javascript: src survived as $src")
    }

    @Test
    fun `an onerror handler does not survive the cleaner`() {
        val doc = cut("""<img src="cid:x" onerror="alert(1)">""")
        assertEquals("cid:x", doc.blocks.filterIsInstance<Block.Picture>().single().src)
    }

    @Test
    fun `remote pictures are listed and a carried one is not`() {
        val doc = cut("""<img src="https://a.example/1.png"><img src="cid:logo">""")
        assertEquals(listOf("https://a.example/1.png"), doc.remoteImages)
        assertEquals(1, doc.blockedImages)
    }

    @Test
    fun `a scheme relative src is read as https, because a message has no scheme`() {
        assertEquals(listOf("https://a.example/1.png"), cut("""<img src="//a.example/1.png">""").remoteImages)
    }

    @Test
    fun `a silly width is ignored rather than trusted`() {
        val wide = cut("""<img src="cid:a" width="9000">""").blocks.filterIsInstance<Block.Picture>().single()
        val pixel = cut("""<img src="cid:b" width="1">""").blocks.filterIsInstance<Block.Picture>().single()
        assertNull(wide.width)
        assertNull(pixel.width)
        assertEquals(120, cut("""<img src="cid:c" width="120">""").blocks
            .filterIsInstance<Block.Picture>().single().width)
    }

    @Test
    fun `an empty body is no blocks rather than one blank one`() {
        assertTrue(cut("<html><body>   </body></html>").blocks.isEmpty())
    }

    @Test
    fun `a data picture that is too big is refused without decoding it`() {
        val huge = "data:image/png;base64," + "A".repeat(20_000_000)
        assertNull(embeddedImage(huge))
    }

    @Test
    fun `a data uri that is not base64 is refused`() {
        assertNull(embeddedImage("data:image/png,notbase64"))
    }
}

/**
 * A coloured band is the one place Rampart has to choose a text colour, because it does
 * not read the one the mail states and the theme's own would be written onto whatever the
 * sender picked.
 */
class CellInkTest {
    @Test
    fun `dark backgrounds get light text and light ones get dark`() {
        assertTrue(inkFor(0xFF3B1F0B.toInt()).luminance() > 0.5f, "the hotel banner was unreadable")
        assertTrue(inkFor(0xFFFFFFFF.toInt()).luminance() < 0.5f)
        assertTrue(inkFor(0xFF000000.toInt()).luminance() > 0.5f)
    }

    @Test
    fun `mid blue counts as dark, which a flat average gets wrong`() {
        // The eye takes almost nothing from the blue channel, so #0000FF is dark however
        // high that channel reads. Averaging the three calls it mid and puts black on it.
        assertTrue(inkFor(0xFF0000FF.toInt()).luminance() > 0.5f)
    }

    @Test
    fun `mid yellow counts as light`() {
        assertTrue(inkFor(0xFFFFFF00.toInt()).luminance() < 0.5f)
    }
}

class TableColourTest {
    private fun cut(html: String) = htmlBlocks(html, Color.Unspecified, Color.Unspecified) {}

    @Test
    fun `a one cell table with a colour is a band, not a wrapper`() {
        // Unwrapped, the band loses its colour and its centring and the hotel's name is
        // written in the theme's near black onto dark brown.
        val row = cut("""<table bgcolor="#3B1F0B"><tr><td align="center">DUCHAMP</td></tr></table>""")
            .blocks.filterIsInstance<Block.Layout>().single().rows.single()
        assertEquals(0xFF3B1F0B.toInt(), row.cells.single().background)
        assertTrue(row.cells.single().centred)
    }

    @Test
    fun `a one cell table with no colour is still unwrapped`() {
        assertTrue(cut("<table><tr><td>Just a wrapper</td></tr></table>").blocks.none { it is Block.Layout })
    }

    @Test
    fun `a colour on the table reaches the cells that state none`() {
        val row = cut("""<table bgcolor="#112233"><tr><td>a</td><td>b</td></tr></table>""")
            .blocks.filterIsInstance<Block.Layout>().single().rows.single()
        assertEquals(listOf(0xFF112233.toInt(), 0xFF112233.toInt()), row.cells.map { it.background })
    }

    @Test
    fun `three digit hex and rgb both parse`() {
        assertEquals(0xFFFFFFFF.toInt(), hexColour("#fff"))
        assertEquals(0xFF3B1F0B.toInt(), hexColour("rgb(59, 31, 11)"))
        assertEquals(null, hexColour("papayawhip"))
        assertEquals(null, hexColour(""))
    }

    // Patterns taken from a real newsletter that rendered as prose with everything missing.
    // Shapes only: no third party markup is kept in this repo.

    @Test
    fun `a picture inside a link is still a picture`() {
        // Eight of the nine images in the newsletter that prompted this were wrapped in a
        // link, because that is what a newsletter is. An `a` was read as a run of text, an
        // image contributes no characters, and every logo, banner and button disappeared.
        val doc = cut("""<a href="https://example.org"><img src="https://example.org/logo.png" width="421"></a>""")
        val pictures = doc.blocks.filterIsInstance<Block.Picture>()
        assertEquals(1, pictures.size)
        assertEquals("https://example.org/logo.png", pictures.first().src)
        assertEquals(421, pictures.first().width)
    }

    @Test
    fun `a picture inside a span or a font is still a picture`() {
        assertEquals(1, cut("""<span><img src="https://example.org/a.png"></span>""").blocks.filterIsInstance<Block.Picture>().size)
        assertEquals(1, cut("""<font><img src="https://example.org/b.png"></font>""").blocks.filterIsInstance<Block.Picture>().size)
    }

    @Test
    fun `a wrapper cell that centres its contents does not lose that when it is unwrapped`() {
        // A one cell table is a wrapper and is unwrapped, which is right. Its alignment is
        // not part of the wrapper: it is what the sender said about what is inside.
        val html = """<table><tr><td align="center"><h1>UrTips</h1></td></tr></table>"""
        val words = cut(html).blocks.filterIsInstance<Block.Words>()
        assertEquals(1, words.size)
        assertTrue(words.first().centred, "a centred wrapper should centre what it holds")
    }

    @Test
    fun `text-align counts as much as the align attribute`() {
        val html = """<table><tr><td style="text-align: center; padding: 10px;"><h2>Centred</h2></td></tr></table>"""
        assertTrue(cut(html).blocks.filterIsInstance<Block.Words>().first().centred)
    }

    @Test
    fun `a picture in a centred wrapper is centred too`() {
        val html = """<div align="center"><a href="https://example.org"><img src="https://example.org/logo.png"></a></div>"""
        assertTrue(cut(html).blocks.filterIsInstance<Block.Picture>().first().centred)
    }

    @Test
    fun `an uncentred message stays where it is`() {
        val html = """<table><tr><td><h1>Left</h1></td></tr></table>"""
        assertFalse(cut(html).blocks.filterIsInstance<Block.Words>().first().centred)
    }

    @Test
    fun `a link the sender filled with a colour is a button`() {
        // Every transactional message ends in one of these: confirm your address, open the
        // ticket, view the invoice. Read as text it is a line of blue words in the middle of
        // a sentence, and it is the thing the whole message is asking you to press.
        val html = """<p>Here you go.</p><p><a href="https://example.org/x" style="background:#2b1a0e;color:#fff;padding:9px 16px;border-radius:4px">Open in FreeScout</a></p>"""
        val buttons = cut(html).blocks.filterIsInstance<Block.Button>()
        assertEquals(1, buttons.size)
        assertEquals("Open in FreeScout", buttons.first().label.text)
        assertEquals(0xFF2B1A0E.toInt(), buttons.first().background)
    }


    @Test
    fun `an ordinary link is left as a link`() {
        val html = """<p>Read the <a href="https://example.org">notes</a> first.</p>"""
        assertEquals(0, cut(html).blocks.filterIsInstance<Block.Button>().size)
        assertEquals(1, cut(html).blocks.filterIsInstance<Block.Words>().size)
    }

    @Test
    fun `a link wrapped round a banner is a linked picture, not a button with a picture in it`() {
        val html = """<a href="https://example.org" style="background:#336699"><img src="https://example.org/banner.png"></a>"""
        val blocks = cut(html).blocks
        assertEquals(1, blocks.filterIsInstance<Block.Picture>().size)
        assertEquals(0, blocks.filterIsInstance<Block.Button>().size)
    }

    @Test
    fun `a button escapes the paragraph it is written in`() {
        // jsoup's select includes the element itself, so the anchor satisfies the button
        // test on its own account. Asked with that test rather than one about real block
        // content, an anchor is forever "holding a block" and can never become a button.
        val html = """<div>Some words <a href="https://example.org" style="background:#112233">Press</a> after them.</div>"""
        assertEquals(1, cut(html).blocks.filterIsInstance<Block.Button>().size)
    }

    @Test
    fun `a button in a centred wrapper is centred`() {
        val html = """<div align="center"><a href="https://example.org" style="background:#112233">Press</a></div>"""
        assertTrue(cut(html).blocks.filterIsInstance<Block.Button>().first().centred)
    }
}
