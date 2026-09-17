package org.rampart

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceTest {
    /** A port the operating system has just told us is free, so the test cannot collide. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

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
}
