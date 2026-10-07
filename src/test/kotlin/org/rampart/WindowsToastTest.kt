package org.rampart

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsToastTest {
    @Test
    fun `toast xml escapes text and omits actions when there are no buttons`() {
        assertEquals(
            "<toast launch=\"io.github.thejdubb02.rampart:open/4\" activationType=\"protocol\">" +
                "<visual><binding template=\"ToastGeneric\">" +
                "<text>&lt;b&gt;&amp;&quot;&apos;</text><text>ok</text>" +
                "</binding></visual></toast>",
            toastXml(4, """<b>&"'""", "ok", emptyList()),
        )
        assertEquals(
            "<toast launch=\"io.github.thejdubb02.rampart:open/4\" activationType=\"protocol\">" +
                "<visual><binding template=\"ToastGeneric\">" +
                "<text>Hi</text><text>There</text>" +
                "</binding></visual>" +
                "<actions>" +
                "<action content=\"A&amp;B\" activationType=\"protocol\" arguments=\"io.github.thejdubb02.rampart:act/4/0\"/>" +
                "<action content=\"Mark read\" activationType=\"protocol\" arguments=\"io.github.thejdubb02.rampart:act/4/1\"/>" +
                "</actions></toast>",
            toastXml(4, "Hi", "There", listOf("A&B", "Mark read")),
        )
    }

    @Test
    fun `the second button runs once and a repeated press does not`() {
        val xml = AtomicReference<String>()
        val shown = CountDownLatch(1)
        var first = 0
        var second = 0
        val toast = WindowsToast("aumid", NoShell) { text ->
            xml.set(text)
            shown.countDown()
            true
        }
        toast.notify(
            "Dana",
            "Hello",
            listOf(NoticeAction("Archive") { first++ }, NoticeAction("Mark read") { second++ }),
        ) {}
        assertTrue(shown.await(5, TimeUnit.SECONDS), "the toast should be handed to Windows")
        val link = assertNotNull(
            Regex("""arguments="([^"]+/1)"""").find(xml.get() ?: "")?.groupValues?.get(1),
            xml.get(),
        )
        assertTrue(toast.opened(link))
        assertEquals(0, first, "the first button was not pressed")
        assertEquals(1, second)
        assertFalse(toast.opened(link), "the notice is already gone")
        assertEquals(1, second, "a second press must not run the button again")
    }

    @Test
    fun `opening the toast runs its click`() {
        val xml = AtomicReference<String>()
        val shown = CountDownLatch(1)
        var clicks = 0
        val toast = WindowsToast("aumid", NoShell) { text ->
            xml.set(text)
            shown.countDown()
            true
        }
        toast.notify("Dana", "Hello") { clicks++ }
        assertTrue(shown.await(5, TimeUnit.SECONDS), "the toast should be handed to Windows")
        val link = assertNotNull(
            Regex("""launch="([^"]+)"""").find(xml.get() ?: "")?.groupValues?.get(1),
            xml.get(),
        )
        assertTrue(toast.opened(link))
        assertEquals(1, clicks)
        assertFalse(toast.opened(link))
        assertEquals(1, clicks, "opening the same toast twice must not open the message twice")
    }

    @Test
    fun `a link this shell does not know is refused`() {
        var clicks = 0
        var acted = 0
        val toast = WindowsToast("aumid", NoShell) { true }
        toast.notify("Dana", "Hello", listOf(NoticeAction("Archive") { acted++ })) { clicks++ }
        assertFalse(toast.opened("garbage"))
        assertFalse(toast.opened(""))
        assertFalse(toast.opened(WindowsToast.SCHEME))
        assertFalse(toast.opened("${WindowsToast.SCHEME}:open/nope"))
        assertFalse(toast.opened("${WindowsToast.SCHEME}:act/1/nope"))
        assertFalse(toast.opened("${WindowsToast.SCHEME}:open/999999"))
        assertFalse(toast.opened("${WindowsToast.SCHEME}:gone/1"))
        assertEquals(0, clicks, "an unknown link must not open the message")
        assertEquals(0, acted, "an unknown link must not press a button")
    }

    @Test
    fun `a toast that cannot be shown is handed to the fallback`() {
        val attempted = AtomicReference<String>()
        var gotTitle = ""
        var gotBody = ""
        var gotLabel = ""
        var gotClick: (() -> Unit)? = null
        var gotRun: (() -> Unit)? = null
        val ready = CountDownLatch(1)
        val fallback = object : DesktopShell {
            override fun notify(title: String, body: String, actions: List<NoticeAction>, onClick: () -> Unit) {
                gotTitle = title
                gotBody = body
                gotLabel = actions.single().label
                gotRun = actions.single().run
                gotClick = onClick
                ready.countDown()
            }
        }
        val toast = WindowsToast("aumid", fallback) { xml ->
            attempted.set(xml)
            false
        }
        var clicks = 0
        var archives = 0
        toast.notify("Dana", "Hello", listOf(NoticeAction("Archive") { archives++ })) { clicks++ }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "the notice should reach the fallback")
        assertEquals("Dana", gotTitle)
        assertEquals("Hello", gotBody)
        assertEquals("Archive", gotLabel)
        assertTrue(attempted.get()?.contains("Dana") == true, "the toast was attempted before the fallback")
        gotClick!!.invoke()
        gotRun!!.invoke()
        assertEquals(1, clicks)
        assertEquals(1, archives)
    }
}
