package org.rampart

import org.apache.poi.hmef.HMEFMessage
import org.apache.poi.hmef.attribute.TNEFProperty
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A winmail.dat is a real TNEF stream, built here because the library reads the
 * format and does not write it. The bytes are what [readTnef] is given.
 */
class TnefTest {
    @Test
    fun `a wrapper yields the text and each file that was packed in it`() {
        val bytes = winmail(
            body = "See the notes.",
            files = listOf(
                "notes.txt" to "hello file".toByteArray(),
                "pic.bin" to "XYZ".toByteArray(),
            ),
        )
        val contents = readTnef(bytes) ?: error("a valid wrapper came back unreadable")
        assertEquals("See the notes.", contents.body)
        assertEquals(listOf("notes.txt", "pic.bin"), contents.files.map { it.first })
        assertEquals("hello file", contents.files[0].second.decodeToString())
        assertEquals("XYZ", contents.files[1].second.decodeToString())
    }

    @Test
    fun `a wrapper with only text has no files`() {
        val contents = readTnef(winmail(body = "Just the note.")) ?: error("a valid wrapper came back unreadable")
        assertEquals("Just the note.", contents.body)
        assertTrue(contents.files.isEmpty())
    }

    @Test
    fun `a file with no name of its own is still listed`() {
        val contents = readTnef(winmail(files = listOf(null to "abc".toByteArray())))
            ?: error("a valid wrapper came back unreadable")
        assertNull(contents.body)
        assertEquals("attachment", contents.files.single().first)
        assertEquals("abc", contents.files.single().second.decodeToString())
    }

    @Test
    fun `bytes that are not tnef come back as nothing`() {
        assertNull(readTnef(ByteArray(0)))
        assertNull(readTnef("this is not a winmail.dat".toByteArray()))
        // A signature, then an attribute that claims far more bytes than it has.
        // That has to fail closed, not try to allocate the claim.
        val hostile = ByteArrayOutputStream()
        writeInt(hostile, HMEFMessage.HEADER_SIGNATURE)
        writeShort(hostile, 1)
        hostile.write(TNEFProperty.LEVEL_MESSAGE)
        writeShort(hostile, TNEFProperty.ID_BODY.id)
        writeShort(hostile, TNEFProperty.TYPE_STRING)
        writeInt(hostile, 100_000_000)
        assertNull(readTnef(hostile.toByteArray()))
    }
}

/** A minimal TNEF stream: the signature, then the body, then one attribute group per file. */
private fun winmail(body: String? = null, files: List<Pair<String?, ByteArray>> = emptyList()): ByteArray {
    val out = ByteArrayOutputStream()
    writeInt(out, HMEFMessage.HEADER_SIGNATURE)
    writeShort(out, 1)
    if (body != null) {
        attribute(out, TNEFProperty.LEVEL_MESSAGE, TNEFProperty.ID_BODY.id, TNEFProperty.TYPE_STRING, cString(body))
    }
    for ((name, bytes) in files) {
        // Outlook starts each file with this. Without it the next file is glued onto the previous one.
        attribute(
            out,
            TNEFProperty.LEVEL_ATTACHMENT,
            TNEFProperty.ID_ATTACHRENDERDATA.id,
            TNEFProperty.TYPE_BYTE,
            byteArrayOf(1, 0, 0, 0),
        )
        if (!name.isNullOrEmpty()) {
            attribute(out, TNEFProperty.LEVEL_ATTACHMENT, TNEFProperty.ID_ATTACHTITLE.id, TNEFProperty.TYPE_STRING, cString(name))
        }
        attribute(out, TNEFProperty.LEVEL_ATTACHMENT, TNEFProperty.ID_ATTACHDATA.id, TNEFProperty.TYPE_BYTE, bytes)
    }
    return out.toByteArray()
}

private fun cString(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1) + 0.toByte()

private fun attribute(out: ByteArrayOutputStream, level: Int, id: Int, type: Int, data: ByteArray) {
    out.write(level)
    writeShort(out, id)
    writeShort(out, type)
    writeInt(out, data.size)
    out.write(data)
    var sum = 0
    for (b in data) sum = (sum + (b.toInt() and 0xff)) and 0xffff
    writeShort(out, sum)
}

private fun writeShort(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xff)
    out.write((value shr 8) and 0xff)
}

private fun writeInt(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xff)
    out.write((value shr 8) and 0xff)
    out.write((value shr 16) and 0xff)
    out.write((value shr 24) and 0xff)
}
