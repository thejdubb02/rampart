package org.rampart

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Duration

/**
 * The address the browser comes back to after signing in: a socket on 127.0.0.1, on a port
 * the operating system picks, open for one sign-in and no longer.
 *
 * RFC 8252 section 7.3. It is the way a desktop application receives the answer without
 * registering a URL scheme with the operating system, which would be a per-platform
 * installer change and one more thing another application could claim first.
 *
 * Deliberately small. It is not a web server: it reads one request line, answers with one
 * fixed page, and closes. Things it refuses to be talked into:
 *
 * - **Listening beyond this machine.** Bound to 127.0.0.1 by address, never to a name,
 *   and never to every interface, so nothing on the network can reach it.
 * - **Accepting a code it did not ask for.** The `state` it was given must come back,
 *   compared in constant time. A request without it is answered and ignored, and the wait
 *   goes on for the real one, because a page elsewhere in the browser can make a request to
 *   a loopback port and must not be able to end or hijack the sign-in by doing it.
 * - **Staying open.** One valid callback, a timeout, or [close], whichever is first, and
 *   the socket is gone.
 */
internal class LoopbackRedirect private constructor(
    private val socket: ServerSocket,
    /** The only path a callback is accepted on. */
    val path: String,
) : AutoCloseable {

    val port: Int get() = socket.localPort

    /** Where it is listening, which is 127.0.0.1 and nothing else. */
    val address: InetAddress get() = socket.inetAddress

    /**
     * The redirect URI to send the provider, for [host].
     *
     * The path is left off when it is the root, which is the form both providers match a
     * registered loopback address against: Google takes `http://127.0.0.1:<port>` and
     * Microsoft matches `http://localhost` with the port ignored.
     */
    fun redirectUri(host: String): String = "http://$host:$port" + if (path == "/") "" else path

    /**
     * Waits for the browser, and returns the authorization code.
     *
     * Throws [OAuthFailure.Refused] when the provider sent an error back (the person
     * declined, say), [OAuthFailure.TimedOut] when nothing valid arrived in [timeout], and
     * [OAuthFailure.Cancelled] when [close] was called from elsewhere. The socket is closed
     * on every one of those paths and on success.
     */
    fun await(state: String, providerName: String, timeout: Duration): String {
        val deadline = System.nanoTime() + timeout.toNanos()
        try {
            while (true) {
                val left = Duration.ofNanos(deadline - System.nanoTime())
                if (left.isNegative || left.isZero) throw timedOut()
                socket.soTimeout = left.toMillis().coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                val connection = try {
                    socket.accept()
                } catch (e: SocketTimeoutException) {
                    throw timedOut()
                } catch (e: SocketException) {
                    throw OAuthFailure.Cancelled()
                }
                connection.use { client ->
                    when (val answer = answer(client, state, providerName)) {
                        is RedirectResult.Code -> return answer.code
                        is RedirectResult.Refused -> throw OAuthFailure.Refused(answer.sentence)
                        RedirectResult.WrongState, RedirectResult.NotOurs -> Unit
                    }
                }
            }
        } finally {
            close()
        }
    }

    /** Reads one request, answers it, and says what it was. Never throws for a bad request. */
    private fun answer(client: Socket, state: String, providerName: String): RedirectResult {
        // A connection that opens and says nothing must not hold the only listener hostage.
        // Browsers do open those, as a guess at the next request, and a browser that means to
        // ask something asks at once, so two seconds is generous and still not noticed.
        client.soTimeout = 2_000
        val line = runCatching { requestHead(client.getInputStream()).substringBefore("\r\n") }.getOrDefault("")
        val result = readRedirect(line, path, state, providerName)
        val (status, message) = when (result) {
            is RedirectResult.Code -> "200 OK" to "You are signed in. You can close this tab and go back to Rampart."
            is RedirectResult.Refused -> "200 OK" to "${result.sentence} You can close this tab and go back to Rampart."
            RedirectResult.WrongState -> "400 Bad Request" to
                "This sign-in did not come from the window Rampart is waiting on, so it was ignored."
            RedirectResult.NotOurs -> "404 Not Found" to "There is nothing here."
        }
        runCatching {
            client.getOutputStream().apply {
                write(redirectPage(status, message))
                flush()
            }
        }
        return result
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** Bound now, so the port is known before the browser is sent anywhere. */
        fun open(path: String = "/"): LoopbackRedirect {
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            return LoopbackRedirect(ServerSocket(0, 8, loopback), path)
        }

        private fun timedOut() = OAuthFailure.TimedOut(
            "Nothing came back from the browser within five minutes, so the sign-in was stopped. Try again when you are ready.",
        )
    }
}

internal sealed interface RedirectResult {
    data class Code(val code: String) : RedirectResult

    /** The provider sent an error rather than a code, with the right state. */
    data class Refused(val sentence: String) : RedirectResult

    /** Looked like a callback, but did not carry the state this sign-in sent out. */
    data object WrongState : RedirectResult

    /** Not a callback at all: another path, another method, or a bare visit. */
    data object NotOurs : RedirectResult
}

/**
 * One request line, `GET /?code=...&state=... HTTP/1.1`, as what it means for the sign-in.
 *
 * Split from the socket so every way it can be wrong is a test rather than a browser.
 */
internal fun readRedirect(requestLine: String, expectedPath: String, expectedState: String, providerName: String): RedirectResult {
    val parts = requestLine.trim().split(' ')
    if (parts.size < 2 || parts[0] != "GET") return RedirectResult.NotOurs
    val target = parts[1]
    if (target.substringBefore('?') != expectedPath) return RedirectResult.NotOurs
    val query = queryParams(target.substringAfter('?', ""))
    val code = query["code"]
    val error = query["error"]
    if (code.isNullOrBlank() && error.isNullOrBlank()) return RedirectResult.NotOurs
    if (!sameState(query["state"], expectedState)) return RedirectResult.WrongState
    if (!error.isNullOrBlank()) return RedirectResult.Refused(redirectErrorSentence(providerName, error, query["error_description"]))
    return RedirectResult.Code(code!!)
}

/** Constant time, so how much of a guess was right cannot be read off how long it took. */
private fun sameState(received: String?, expected: String): Boolean {
    if (received.isNullOrEmpty() || expected.isEmpty()) return false
    return MessageDigest.isEqual(received.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
}

/** A query string, decoded. A repeated name keeps its first value, which is the honest one. */
internal fun queryParams(query: String): Map<String, String> {
    val out = linkedMapOf<String, String>()
    query.split('&').filter { it.isNotEmpty() }.forEach { pair ->
        val name = runCatching { URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8) }.getOrNull() ?: return@forEach
        val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8) }.getOrNull() ?: return@forEach
        out.putIfAbsent(name, value)
    }
    return out
}

/**
 * The error a provider redirected back with, as a sentence.
 *
 * `access_denied` is the common one and is not a fault at all: it is the person pressing
 * Cancel on the consent page, and it is said that way.
 */
internal fun redirectErrorSentence(providerName: String, error: String, description: String?): String {
    val said = description?.let(::plainProviderText)?.takeIf { it.isNotBlank() }
    return when (error.trim().lowercase()) {
        "access_denied" -> "The sign-in was declined at $providerName, so nothing was connected."
        "consent_required", "interaction_required", "login_required" ->
            "$providerName needs you to finish signing in on its page before Rampart can connect."
        else -> "$providerName did not finish the sign-in" + (said?.let { ": $it" } ?: " (${plainProviderText(error)}).")
    }
}

/** Everything up to the blank line after the headers, capped: a browser's request is small. */
private fun requestHead(input: InputStream, limit: Int = 16 * 1024): String {
    val bytes = java.io.ByteArrayOutputStream()
    var matched = 0
    val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    while (bytes.size() < limit) {
        val next = input.read()
        if (next < 0) break
        bytes.write(next)
        matched = if (next.toByte() == end[matched]) matched + 1 else if (next.toByte() == end[0]) 1 else 0
        if (matched == end.size) break
    }
    return String(bytes.toByteArray(), Charsets.ISO_8859_1)
}

/**
 * The page the browser is left on: one sentence, no script, nothing fetched.
 *
 * The content security policy says so even though there is nothing on the page to stop,
 * and no-referrer means the address with the code in it is not passed on if the person
 * follows a link from the tab afterwards.
 */
internal fun redirectPage(status: String, message: String): ByteArray {
    val body = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Rampart</title></head>" +
        "<body><p>${pageEscape(message)}</p></body></html>"
    val bytes = body.toByteArray(Charsets.UTF_8)
    val head = "HTTP/1.1 $status\r\n" +
        "Content-Type: text/html; charset=utf-8\r\n" +
        "Content-Length: ${bytes.size}\r\n" +
        "Content-Security-Policy: default-src 'none'\r\n" +
        "Referrer-Policy: no-referrer\r\n" +
        "X-Content-Type-Options: nosniff\r\n" +
        "Cache-Control: no-store\r\n" +
        "Connection: close\r\n\r\n"
    return head.toByteArray(Charsets.US_ASCII) + bytes
}

private fun pageEscape(text: String): String = buildString {
    text.forEach {
        when (it) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(it)
        }
    }
}
