package org.rampart

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The half of a signature picture that decides whether anybody else can see it.
 *
 * Gmail and Outlook both refuse to draw a `data:` URI in a received message, so a logo
 * that renders perfectly in Rampart is a broken image for most of the people it is sent
 * to. These check the pictures come out of the signature and go back in as Content-IDs.
 */
class SignaturePictureTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val b64 = Base64.getEncoder().encodeToString(png)

    @Test
    fun `a picture is found and decoded`() {
        val html = """<div><img src="data:image/png;base64,$b64" height="36"></div>"""
        val found = signaturePictures(html)
        assertEquals(1, found.size)
        assertEquals("image/png", found.first().type)
        assertContentEquals(png, found.first().bytes)
    }

    @Test
    fun `the same picture twice is uploaded once`() {
        val one = """<img src="data:image/png;base64,$b64">"""
        assertEquals(1, signaturePictures(one + one).size)
    }

    @Test
    fun `two different pictures are both found`() {
        val other = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val html = """<img src="data:image/png;base64,$b64"><img src="data:image/gif;base64,$other">"""
        assertEquals(listOf("image/png", "image/gif"), signaturePictures(html).map { it.type })
    }

    @Test
    fun `a signature with no picture costs nothing`() {
        assertTrue(signaturePictures("<div>Justin Willhite</div>").isEmpty())
    }

    @Test
    fun `rubbish where the base64 should be is skipped rather than thrown`() {
        assertTrue(signaturePictures("""<img src="data:image/png;base64,">""").isEmpty())
    }

    @Test
    fun `rewriting leaves a cid reference and no base64 behind`() {
        val html = """<div><img src="data:image/png;base64,$b64" height="36"></div>"""
        val picture = signaturePictures(html).first()
        val out = withCids(html, mapOf(picture.src to "abc@rampart.invalid"))
        assertTrue("""src="cid:abc@rampart.invalid"""" in out)
        assertFalse("base64" in out, "the picture was still carried inline")
    }

    @Test
    fun `the surrounding signature survives the rewrite`() {
        val html = """<div style="color:#222"><img src="data:image/png;base64,$b64"><b>Justin Willhite</b></div>"""
        val out = withCids(html, mapOf(signaturePictures(html).first().src to "x@rampart.invalid"))
        assertTrue("<b>Justin Willhite</b>" in out)
        assertTrue("""style="color:#222"""" in out)
    }

    @Test
    fun `an attached picture is named after its type`() {
        assertEquals("signature.png", signaturePictureName("image/png"))
        assertEquals("signature.jpeg", signaturePictureName("image/jpeg"))
        assertEquals("signature.svg", signaturePictureName("image/svg+xml"))
    }

    @Test
    fun `a wrapped base64 run still decodes`() {
        val wrapped = b64.chunked(4).joinToString("\n")
        val found = signaturePictures("""<img src="data:image/png;base64,$wrapped">""")
        assertContentEquals(png, found.first().bytes)
    }
}
