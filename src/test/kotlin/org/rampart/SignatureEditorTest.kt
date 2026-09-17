package org.rampart

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.io.path.createTempFile
import kotlin.io.path.writeBytes

/**
 * The buttons are the only way somebody who does not write HTML puts a tag in the right
 * place, so where the tags land and where the caret ends up afterwards is the feature.
 */
class SignatureEditorTest {
    private fun at(text: String, start: Int, end: Int = start) =
        TextFieldValue(text, TextRange(start, end))

    @Test
    fun tagsGoRoundTheSelection() {
        val out = wrapSelection(at("Justin Willhite", 0, 6), "<b>", "</b>")
        assertEquals("<b>Justin</b> Willhite", out.text)
    }

    /**
     * Select a word, press Bold, press Italic. Without the selection moving with the text,
     * the second pair lands wherever the caret happened to fall.
     */
    @Test
    fun twoPressesWrapTheSameWordTwice() {
        val bold = wrapSelection(at("Justin Willhite", 0, 6), "<b>", "</b>")
        val both = wrapSelection(bold, "<i>", "</i>")
        assertEquals("<b><i>Justin</i></b> Willhite", both.text)
    }

    @Test
    fun withNoSelectionTheCaretEndsUpBetweenTheTags() {
        val out = wrapSelection(at("ab", 1), "<b>", "</b>")
        assertEquals("a<b></b>b", out.text)
        assertEquals(TextRange(4), out.selection, "typing has to continue inside the tags")
    }

    @Test
    fun aSelectionDraggedBackwardsIsNotACrash() {
        val out = wrapSelection(at("Justin Willhite", 6, 0), "<b>", "</b>")
        assertEquals("<b>Justin</b> Willhite", out.text)
    }

    @Test
    fun theTextOutsideTheSelectionIsUntouched() {
        val out = wrapSelection(at("one two three", 4, 7), "<i>", "</i>")
        assertTrue(out.text.startsWith("one "))
        assertTrue(out.text.endsWith(" three"))
    }

    @Test
    fun aPictureReplacesTheSelectionAndLeavesTheCaretAfterIt() {
        val out = insertAt(at("before after", 6, 7), "<img>")
        assertEquals("before<img>after", out.text)
        assertEquals(TextRange(11), out.selection)
    }

    /** Rides along on every message, so there is a limit and it is small. */
    @Test
    fun anOversizePictureIsRefusedByNameAndSize() {
        val file = createTempFile("logo", ".png")
        file.writeBytes(ByteArray(200 * 1024))
        val refusal = assertFailsWith<IllegalArgumentException> { imageDataUri(file) }
        assertTrue(file.fileName.toString() in refusal.message!!, refusal.message!!)
        assertTrue("under" in refusal.message!!)
    }

    @Test
    fun somethingThatIsNotAPictureIsRefused() {
        val file = createTempFile("notes", ".txt")
        file.writeBytes("hello".toByteArray())
        assertFailsWith<IllegalArgumentException> { imageDataUri(file) }
    }

    /**
     * The plain half is derived, not typed twice. Two boxes is two things to keep in step,
     * and the one people forget is the plain one.
     */
    @Test
    fun thePlainSignOffComesOutOfTheHtmlOne() {
        val plain = plainOf(
            """<div style="color:#555"><b>Justin Willhite</b><br>Willhite Strategy Group<br>""" +
                """<a href="https://willhitestrategy.com">willhitestrategy.com</a></div>""",
        )
        assertEquals("Justin Willhite\nWillhite Strategy Group\nwillhitestrategy.com", plain)
    }
}
