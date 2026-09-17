package org.rampart

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `a table of figures stays a table`() {
        val grid = cut(
            "<table><tr><th>Item</th><th>Rate</th></tr>" +
                "<tr><td>Digging</td><td>640</td></tr></table>",
        ).blocks.filterIsInstance<Block.Grid>().single()
        assertEquals(2, grid.rows.size)
        assertEquals(listOf("Digging", "640"), grid.rows[1].map { it.text })
    }

    @Test
    fun `a layout table is unwrapped, and the picture inside it survives`() {
        // Mail uses one cell tables as a wrapper far more often than as data. Drawing that
        // as a grid puts a border round the whole email, and reading it as text loses the
        // picture, which is usually the only thing in there.
        val doc = cut("""<table><tr><td><img src="cid:logo"><br>Dana</td></tr></table>""")
        assertTrue(doc.blocks.none { it is Block.Grid })
        assertEquals("cid:logo", doc.blocks.filterIsInstance<Block.Picture>().single().src)
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
