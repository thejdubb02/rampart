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
 *
 * The socket also carries one line back. A Windows toast button starts a new copy of
 * the app and hands it a link, and that copy has already lost the race for the port, so
 * the link has to reach the copy that is running. The line is read after the greeting.
 * It is capped, because it comes from this machine but it is still just bytes another
 * process chose to write.
 */
object SingleInstance {
    /** Nothing standard lives here, and a wrong guess only means no lock, never a fault. */
    private const val PORT = 51873

    private const val HELLO = "rampart\n"

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Volatile
    private var raise: (() -> Unit)? = null

    @Volatile
    private var link: ((String) -> Boolean)? = null

    /** How long a line may be. Longer is ignored, the same as no line at all. */
    private const val MAX_LINK = 2048

    /**
     * True when this copy should carry on starting. False when another one is already up,
     * in which case this process should exit quietly. [link], when Windows started this
     * copy from a toast button, is written to the running copy before this one goes.
     *
     * Every doubtful case starts the copy: a port held by something else, a connection that
     * fails, a reply that never comes. Two windows is a much smaller problem than an app
     * that will not open.
     */
    fun claim(port: Int = PORT, link: String? = null): Boolean {
        val server = runCatching { ServerSocket(port, 4, loopback) }.getOrNull() ?: return !rampartIsUp(port, link)
        thread(isDaemon = true, name = "rampart-single-instance") {
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                // The greeting is flushed before anything is read, so a second copy can stop
                // waiting and exit whether or not a window is up yet. It may then write one
                // line, the link a toast button carried. A copy that writes nothing, including
                // every copy from before links existed, just closes: readLine returns null
                // and the window comes forward as it used to.
                val line = runCatching {
                    client.use { socket ->
                        val out = socket.getOutputStream()
                        out.write(HELLO.toByteArray())
                        out.flush()
                        socket.soTimeout = 2000
                        val text = socket.getInputStream().bufferedReader().readLine()
                        if (text != null && text.length > MAX_LINK) null else text
                    }
                }.getOrNull()
                // Blank, missing, too long, refused, or not understood: bring the window up.
                // A link the shell knows is the button itself, and the window stays put.
                val handler = this.link
                val understood = !line.isNullOrBlank() && handler != null && runCatching { handler(line) }.getOrDefault(false)
                if (!understood) raise?.invoke()
            }
        }
        return true
    }

    /**
     * Whether the thing holding the port answers like Rampart. Anything else, including a
     * connection that fails or times out, is not Rampart, and this copy carries on starting.
     *
     * When it does answer, [link] goes back down the same socket before it closes. Nothing
     * is written when [link] is null, which is every ordinary second start: the running
     * copy sees the close and raises its window.
     */
    private fun rampartIsUp(port: Int, link: String?): Boolean = runCatching {
        Socket(loopback, port).use { socket ->
            socket.soTimeout = 2000
            val said = ByteArray(HELLO.length)
            var got = 0
            while (got < said.size) {
                val read = socket.getInputStream().read(said, got, said.size - got)
                if (read < 0) break
                got += read
            }
            if (String(said, 0, got) != HELLO) return@use false
            if (link != null) {
                socket.getOutputStream().run {
                    write((link + "\n").toByteArray())
                    flush()
                }
            }
            true
        }
    }.getOrDefault(false)

    /** What to do when a second copy tries to start. Set once the window exists. */
    fun bringToFront(action: () -> Unit) {
        raise = action
    }

    /**
     * What to do with a link a second copy handed over. True means the link was understood
     * and the window should stay where it is. False, or no handler yet, brings the window
     * forward instead, which is also what a plain second start does.
     */
    fun onLink(handler: (String) -> Boolean) {
        link = handler
    }
}
