package org.rampart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfPreviewTest {
    /**
     * Creates a minimal valid PDF document in memory using PDFBox.
     */
    private fun createSamplePdf(
        width: Float = 200f,
        height: Float = 300f,
        pageCount: Int = 1,
    ): ByteArray {
        val document = PDDocument()
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        for (pageIndex in 0 until pageCount) {
            val page = PDPage(PDRectangle(width, height))
            document.addPage(page)
            val stream = PDPageContentStream(document, page)
            stream.beginText()
            stream.setFont(font, 12f)
            stream.newLineAtOffset(20f, height - 30f)
            stream.showText("Page ${pageIndex + 1} content.")
            stream.endText()
            stream.close()
        }
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    @Test
    fun `renders page 1 of a generated PDF and checks page count and image size`() {
        val pdfBytes = createSamplePdf(width = 200f, height = 300f, pageCount = 1)
        val document = loadPdfDocument(pdfBytes)
        assertNotNull(document, "Document failed to parse.")

        try {
            assertEquals(1, document.numberOfPages)

            val pageSizes = readPdfPageSizes(document)
            assertEquals(1, pageSizes.size)
            assertEquals(200f, pageSizes[0].width)
            assertEquals(300f, pageSizes[0].height)

            val image = renderPdfPage(document, 0, dpi = 72f)
            assertNotNull(image, "Page 1 rendering returned null.")
            assertEquals(200, image.width)
            assertEquals(300, image.height)
        } finally {
            document.close()
        }
    }

    @Test
    fun `renders multi page documents lazily`() {
        val pdfBytes = createSamplePdf(width = 150f, height = 250f, pageCount = 3)
        val document = loadPdfDocument(pdfBytes)
        assertNotNull(document)

        try {
            assertEquals(3, document.numberOfPages)
            val pageSizes = readPdfPageSizes(document)
            assertEquals(3, pageSizes.size)

            val pageTwo = renderPdfPage(document, 1, dpi = 72f)
            assertNotNull(pageTwo)
            assertEquals(150, pageTwo.width)
            assertEquals(250, pageTwo.height)

            val outOfBounds = renderPdfPage(document, 5, dpi = 72f)
            assertNull(outOfBounds)
        } finally {
            document.close()
        }
    }

    @Test
    fun `the preview opens from a card in a thread, inside the stack's scrolling column`() {
        val bytes = createSamplePdf(pageCount = 2)
        val scene = ImageComposeScene(900, 700) {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PdfPreviewModal(Attachment("b", "a.pdf", "application/pdf", bytes.size.toLong()), bytes, {}, {})
                }
            }
        }
        try {
            repeat(3) { scene.render(it * 16_000_000L) }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `invalid bytes fail to load safely`() {
        val garbage = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val document = loadPdfDocument(garbage)
        assertNull(document)
    }

    @Test
    fun `isPdf correctly detects pdf files`() {
        assertTrue(isPdf("application/pdf"))
        assertTrue(isPdf("application/x-pdf"))
        assertTrue(isPdf("APPLICATION/PDF; charset=utf-8"))
        assertTrue(isPdf("application/octet-stream", "statement.pdf"))
        assertTrue(isPdf("application/octet-stream", "report.PDF"))
        assertFalse(isPdf("image/png", "picture.png"))
        assertFalse(isPdf("text/plain", "notes.txt"))
    }
}
