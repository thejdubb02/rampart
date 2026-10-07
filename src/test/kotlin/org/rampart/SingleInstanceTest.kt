package org.rampart

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceTest {
    /** A port the operating system has just told us is free, so the test cannot collide. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    // SingleInstance is one object for the whole process, so a handler installed for one
    // test would still be there for the next.
    @AfterTest
    fun resetHandlers() {
        SingleInstance.onLink { false }
        SingleInstance.bringToFront { }
    }

    @Test
    fun `the first copy claims the port and a second one is turned away`() {
        val port = freePort()
        assertTrue(SingleInstance.claim(port), "the first copy should start")
        assertFalse(SingleInstance.claim(port), "a second copy should not")
    }

    @Test
    fun `something else on the port is not mistaken for Rampart`() {
        val port = freePort()
        // Accepts the connection and says nothing, which is what most things do.
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use {
            assertTrue(SingleInstance.claim(port), "a silent stranger must not block a start")
        }
    }

    @Test
    fun `a second copy hands its link to the one already running`() {
        val port = freePort()
        val got = AtomicReference<String>()
        val heard = CountDownLatch(1)
        val raised = CountDownLatch(1)
        SingleInstance.onLink { line ->
            got.set(line)
            heard.countDown()
            true
        }
        SingleInstance.bringToFront { raised.countDown() }
        assertTrue(SingleInstance.claim(port))
        assertFalse(SingleInstance.claim(port, link = "x:y"), "the second copy should leave the first one running")
        assertTrue(heard.await(5, TimeUnit.SECONDS), "the running copy should receive the link")
        assertEquals("x:y", got.get())
        assertFalse(raised.await(300, TimeUnit.MILLISECONDS), "a handled link should not also raise the window")
    }

    @Test
    fun `a second copy with no link raises the window and does not hand one over`() {
        val port = freePort()
        val raised = CountDownLatch(1)
        val links = AtomicInteger(0)
        SingleInstance.onLink {
            links.incrementAndGet()
            false
        }
        SingleInstance.bringToFront { raised.countDown() }
        assertTrue(SingleInstance.claim(port))
        assertFalse(SingleInstance.claim(port))
        assertTrue(raised.await(5, TimeUnit.SECONDS), "the running copy should come forward")
        assertEquals(0, links.get(), "a plain second start is not a notification link")
    }
}
