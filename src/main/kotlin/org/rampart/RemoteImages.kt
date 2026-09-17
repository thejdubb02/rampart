package org.rampart

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.jetbrains.skia.Image

/**
 * Pictures a message wants to fetch from the sender's own web server.
 *
 * Fetching one tells the sender that this message was opened, when, and roughly from where,
 * which is what a tracking pixel is for. So nothing here happens until the reader says so,
 * and the answer is remembered per sender rather than asked on every message.
 *
 * Deliberately nothing like a browser: one GET, no cookies, no referrer, no redirect chain
 * off to somewhere else, a short timeout and a size cap.
 */
object RemoteImages {
    private val http: HttpClient = HttpClient.newBuilder()
        // A redirect is how a blocked host gets loaded anyway, and following one silently
        // would undo the check below. Refusing is the honest answer.
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    /**
     * Whether this is an address worth asking a server for at all.
     *
     * https only. Plain http tells the sender the same thing and tells everyone between
     * here and there as well, and for a picture in an email that is not a trade worth
     * making. A bare host with no scheme, a data URI, anything else: no.
     */
    internal fun fetchable(url: String): Boolean = runCatching {
        val uri = URI.create(url.trim())
        uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    /** The picture, or null if it could not be had. A picture that fails is not an error. */
    fun fetch(url: String, limit: Long = 8L * 1024 * 1024): ImageBitmap? = runCatching {
        if (!fetchable(url)) return null
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url.trim()))
                // Enough to be served the image and no more. No cookies, no referrer, so
                // the fetch says nothing beyond the request itself.
                .header("Accept", "image/*")
                .header("User-Agent", "Rampart")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (response.statusCode() != 200) return null
        val bytes = response.body()
        if (bytes.size > limit) return null
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

/**
 * The address a decision about pictures is remembered against.
 *
 * The domain, not the exact address, because a newsletter arrives from a different local
 * part every time (`bounce-93471@news.example.com`) and remembering each one would mean
 * answering the same question forever. Falls back to the whole string when there is no @
 * in it, so a malformed sender is still remembered as itself rather than as everyone.
 */
internal fun imageSenderKey(fromEmail: String): String {
    val at = fromEmail.trim().lowercase().substringAfterLast('@', "")
    return at.ifBlank { fromEmail.trim().lowercase() }
}

/**
 * Every picture the body wants from the web, fetched.
 *
 * Keyed by the address each came from, so the body can draw each one where it names it.
 *
 * One at a time rather than in parallel: this is a handful of images on one message, and
 * opening six connections to a sender's server to read their newsletter is not a thing to
 * do to them or to a slow link. The ones that fail are simply missing.
 */
internal suspend fun fetchRemote(body: Body?): Map<String, ImageBitmap> {
    val html = body?.html ?: return emptyMap()
    // Deduplicated: a newsletter that uses the same spacer forty times is forty requests to
    // the same address for the same bytes, and it is drawn from this map by address anyway.
    val urls = htmlBlocks(html, Color.Unspecified, Color.Unspecified) {}.remoteImages.distinct()
    if (urls.isEmpty()) return emptyMap()
    // Capped, because a message is allowed to name a thousand pictures and asking for all
    // of them is a thing somebody could be made to do to a server they do not own.
    return withContext(Dispatchers.IO) {
        urls.take(20).mapNotNull { url -> RemoteImages.fetch(url)?.let { url to it } }.toMap()
    }
}
