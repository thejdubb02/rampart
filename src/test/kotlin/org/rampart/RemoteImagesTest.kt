package org.rampart

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fetching a picture from a sender's web server tells them the message was opened, when,
 * and roughly from where. That is what a tracking pixel is, so the rule that nothing is
 * fetched without being asked is the feature, not an optimisation.
 */
class RemoteImagesTest {
    private fun render(html: String) = renderHtml(html, Color.Unspecified, Color.Unspecified) {}

    @Test
    fun whatTheBodyWantsFromTheWebIsListedInOrder() {
        val found = render(
            """<p>Hi</p><img src="https://a.test/1.png"><img src="cid:logo"><img src="https://b.test/2.gif">""",
        )
        assertEquals(listOf("https://a.test/1.png", "https://b.test/2.gif"), found.remoteImages)
        assertEquals(2, found.blockedImages, "a picture the message carries is not held back")
    }

    @Test
    fun aMessageWithNoPicturesHoldsNothingBack() {
        assertEquals(0, render("<p>Hi</p>").blockedImages)
        assertTrue(render("""<img src="cid:logo">""").remoteImages.isEmpty())
    }

    /**
     * https only. Plain http tells the sender the same thing and tells everyone between
     * here and there as well, which for a picture in an email is not a trade worth making.
     */
    @Test
    fun onlyHttpsIsEverFetched() {
        assertTrue(RemoteImages.fetchable("https://a.test/x.png"))
        assertFalse(RemoteImages.fetchable("http://a.test/x.png"))
        assertFalse(RemoteImages.fetchable("data:image/gif;base64,R0lGOD"))
        assertFalse(RemoteImages.fetchable("file:///etc/passwd"))
        assertFalse(RemoteImages.fetchable("javascript:alert(1)"))
        assertFalse(RemoteImages.fetchable("a.test/x.png"))
        assertFalse(RemoteImages.fetchable(""))
        assertFalse(RemoteImages.fetchable("https://"), "a scheme with no host is not an address")
        assertFalse(RemoteImages.fetchable("not a url at all <>"))
    }

    /**
     * The domain, not the address. A newsletter arrives from a different local part every
     * time, so remembering each one would mean answering the same question forever.
     */
    @Test
    fun theAnswerIsRememberedAgainstTheDomain() {
        assertEquals("news.example.com", imageSenderKey("bounce-93471@news.example.com"))
        assertEquals("news.example.com", imageSenderKey("  Bounce-1@News.Example.COM "))
        assertEquals(
            imageSenderKey("a@x.test"),
            imageSenderKey("b@x.test"),
            "two addresses at one sender are one decision",
        )
    }

    /** A sender with no domain is remembered as itself, never as everyone. */
    @Test
    fun aMalformedSenderIsNotTreatedAsEveryone() {
        assertEquals("nonsense", imageSenderKey("nonsense"))
        assertEquals("", imageSenderKey(""))
        assertTrue(imageSenderKey("weird@") != "", "an empty key would match every sender")
    }
}
