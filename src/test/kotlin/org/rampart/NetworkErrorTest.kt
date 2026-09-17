package org.rampart

import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sign-in screen is the first thing anyone sees, and it was showing the words
 * "java.net.ConnectException" to someone who had mistyped a hostname.
 */
class NetworkErrorTest {
    private val server = "mail.example.org"

    /**
     * The exact chain java.net.http produces for a name that does not resolve, taken from a
     * real lookup: two ConnectExceptions around an UnresolvedAddressException, every one of
     * them with a null message. That is what put the words "java.net.ConnectException" on
     * the sign-in screen.
     */
    @Test
    fun aMistypedHostnameSaysSo() {
        val thrown = ConnectException().apply {
            initCause(ConnectException().apply { initCause(UnresolvedAddressException()) })
        }
        assertTrue(thrown.message == null, "this is the case that produced the bare class name")
        val said = plainNetworkError(thrown, server)
        assertTrue(said.contains("no server called $server"), said)
        assertFalse(said.contains("Exception"), "no class names reach the screen")
    }

    /** The one every other JVM API throws for the same thing. */
    @Test
    fun theOtherNameForTheSameFault() {
        assertTrue(plainNetworkError(UnknownHostException(server), server).contains("no server called"))
    }

    @Test
    fun aServerThatIsNotAnsweringIsNotTheSameAsOneThatDoesNotExist() {
        assertTrue(plainNetworkError(ConnectException("Connection refused"), server).contains("Could not reach"))
    }

    @Test
    fun anUntrustedCertificateIsNamedAsOne() {
        val said = plainNetworkError(SSLHandshakeException("PKIX path building failed"), server)
        assertTrue(said.contains("certificate"), said)
    }

    /** Anything unrecognised still says something, and never an empty string. */
    @Test
    fun anythingElseFallsBackToWordsRatherThanNothing() {
        assertEquals("disk on fire", plainNetworkError(IllegalStateException("disk on fire"), server))
        assertTrue(plainNetworkError(IllegalStateException(), server).isNotBlank())
        // A cause chain that loops must not hang the sign-in screen.
        val a = RuntimeException()
        val b = RuntimeException(a)
        assertTrue(plainNetworkError(b, server).isNotBlank())
    }
}
