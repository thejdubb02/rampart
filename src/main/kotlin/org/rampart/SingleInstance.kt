package org.rampart

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * One Rampart at a time, however many things try to start one.
 *
 * Windows gives an MSIX several ways to be launched at once: the update restarts it while
 * Windows is separately allowed to install and relaunch it in the background, and the app
 * sits in the tray rather than quitting, so a second start does not look to the shell like
 * a second copy. The result was three windows in the taskbar after a morning's updates.
 *
 * The lock is a loopback socket rather than a lock file, because a lock file has to be
 * cleaned up and a process that is killed never cleans anything up: a socket is released by
 * the operating system the moment the process ends, crash included. It also gives the second
 * copy somewhere to say "you are already running" before it exits.
 *
 * Bound to 127.0.0.1 only, so nothing off this machine can reach it. The one thing it does
 * carry is a word saying what it is, because some other program owning this port has to
 * look different from Rampart owning it. Without that, anything else listening here would
 * stop Rampart starting at all, which is a far worse fault than the one being fixed.
 */
object SingleInstance {
    /** Nothing standard lives here, and a wrong guess only means no lock, never a fault. */
    private const val PORT = 51873

    private const val HELLO = "rampart\n"

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Volatile
    private var raise: (() -> Unit)? = null

    /**
     * True when this copy should carry on starting. False when another one is already up and
     * has been asked to come forward, in which case this process should exit quietly.
     *
     * Every doubtful case starts the copy: a port held by something else, a connection that
     * fails, a reply that never comes. Two windows is a much smaller problem than an app
     * that will not open.
     */
    fun claim(port: Int = PORT): Boolean {
        val server = runCatching { ServerSocket(port, 4, loopback) }.getOrNull() ?: return !rampartIsUp(port)
        thread(isDaemon = true, name = "rampart-single-instance") {
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                // Said before the window is raised, so a second copy can stop waiting and
                // exit whether or not there is a window up yet to raise.
                runCatching { client.use { it.getOutputStream().write(HELLO.toByteArray()) } }
                raise?.invoke()
            }
        }
        return true
    }

    /**
     * Whether the thing holding the port answers like Rampart. Anything else, including a
     * connection that fails or times out, is not Rampart, and this copy carries on starting.
     */
    private fun rampartIsUp(port: Int): Boolean = runCatching {
        Socket(loopback, port).use { socket ->
            socket.soTimeout = 2000
            val said = ByteArray(HELLO.length)
            var got = 0
            while (got < said.size) {
                val read = socket.getInputStream().read(said, got, said.size - got)
                if (read < 0) break
                got += read
            }
            String(said, 0, got) == HELLO
        }
    }.getOrDefault(false)

    /** What to do when a second copy tries to start. Set once the window exists. */
    fun bringToFront(action: () -> Unit) {
        raise = action
    }
}
