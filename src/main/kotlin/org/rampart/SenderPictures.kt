package org.rampart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Pictures of the people who write, fetched through the companion server.
 *
 * A picture looked up by the address that mailed you is a tracking pixel with a
 * friendlier name. Asking the sender's site would tell them you opened the message,
 * and it would tell them when. Rampart asks its own companion instead. The sender's
 * site sees the companion, on a cache miss, and not this computer. A picture kept
 * from the last 7 days is not asked for again.
 *
 * No companion means the feature is off. Junk stays on initials unless that is
 * switched on, because a logo makes a phishing message look like the company it
 * pretends to be. A contact card's own picture wins over any of this, and it never
 * leaves the machine.
 */

/** How long a picture, or the fact there is not one, is reused. */
internal const val SENDER_PICTURE_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/** A few thousand files. Past this, the oldest are dropped. */
internal const val SENDER_PICTURE_MAX_ENTRIES: Int = 4000

private const val SENDER_PICTURE_MAX_BYTES: Int = 256 * 1024
private const val SENDER_PICTURE_MEMORY: Int = 200
private const val SENDER_PICTURE_AT_ONCE: Int = 3
private const val SENDER_PICTURE_QUEUE: Int = 48
private const val SENDER_PICTURE_SETTLE_MILLIS: Long = 150

/**
 * The address pictures are keyed by.
 *
 * Trimmed and lowercased, because case is not part of an address and a space copied
 * in from a header must not become a second cache entry.
 */
internal fun senderPictureKey(email: String): String = email.trim().lowercase()

/** SHA-256 of [senderPictureKey], which is what Libravatar and `/icon` both expect. */
internal fun senderPictureHash(email: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(senderPictureKey(email).toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * The domain of [email], or null when it is not a name we will ask about.
 *
 * Checked here so a bad value never becomes a request. The companion checks again,
 * including after DNS, which is the check that actually stops a private address.
 */
internal fun senderDomain(email: String): String? {
    val key = senderPictureKey(email)
    val at = key.lastIndexOf('@')
    if (at <= 0 || at >= key.lastIndex) return null
    val host = key.substring(at + 1).trimEnd('.')
    if (host.isEmpty() || host.length > 253) return null
    if (host.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' }) return null
    if (host.startsWith('.') || host.contains("..")) return null
    if (host.none { it in 'a'..'z' }) return null
    val labels = host.split('.')
    if (labels.size < 2) return null
    if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return null
    if (host == "localhost" || host.endsWith(".localhost") || host == "localhost.localdomain") return null
    if (host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localdomain") ||
        host.endsWith(".lan") || host.endsWith(".home") || host.endsWith(".intranet")
    ) {
        return null
    }
    return host
}

/**
 * Whether a picture may be fetched for the folder on screen.
 *
 * [enabled] is the Appearance switch, already combined with "a companion is set".
 * Junk is off unless [showInJunk] is on: a logo there makes a phishing message look
 * like the company it pretends to be.
 */
internal fun senderPictureAllowed(enabled: Boolean, inJunk: Boolean, showInJunk: Boolean): Boolean =
    enabled && (!inJunk || showInJunk)

/**
 * What a stored choice means.
 *
 * Missing follows whether a companion is configured, so the feature is on for someone
 * who has set one up and off for someone who has not. An explicit choice is kept either
 * way. Fetching still requires the companion: a saved "on" with no server does not ask
 * the sender's site.
 */
internal fun senderPicturesOn(stored: Boolean?, companionConfigured: Boolean): Boolean =
    stored ?: companionConfigured

/**
 * Whether the folder on screen is Junk.
 *
 * The role is what a server calls it. The id is the fallback for a folder our own
 * names list recognised, which is how a mailbox named "Junk Mail" still counts.
 */
internal fun folderIsJunk(role: String?, folderId: String?, junkFolderId: String?): Boolean =
    role == "junk" || (junkFolderId != null && folderId != null && folderId == junkFolderId)

/** Where pictures are kept: next to the other files Rampart already keeps on this computer. */
internal fun senderPictureDir(): Path {
    val parent = Accounts.file().parent ?: Path.of(".")
    return parent.resolve("sender-pictures")
}

internal fun senderPictureUrl(base: String, domain: String, hash: String): String {
    val root = base.trim().trimEnd('/')
    val name = URLEncoder.encode(domain, Charsets.UTF_8)
    return "$root/icon?domain=$name&email=$hash"
}

/** What a lookup produced. [Retry] is not stored: a timeout is not the same as "no picture". */
internal sealed class IconAnswer {
    data class Image(val bytes: ByteArray) : IconAnswer()
    data object Miss : IconAnswer()
    data object Retry : IconAnswer()
}

/**
 * A response from the companion, judged before anything is drawn or stored.
 *
 * 404 is a miss. Anything else that is not a small real image is a retry, including a
 * page that calls itself an image. SVG is refused: it does not sniff, and it can carry
 * a script.
 */
internal fun classifyIconResponse(
    status: Int,
    contentType: String,
    bytes: ByteArray,
    truncated: Boolean,
): IconAnswer {
    if (status == 404) return IconAnswer.Miss
    if (status != 200 || truncated || bytes.isEmpty()) return IconAnswer.Retry
    val declared = contentType.substringBefore(';').trim().lowercase()
    if (!declared.startsWith("image/") || declared == "image/svg+xml") return IconAnswer.Retry
    if (sniffSenderPicture(bytes) == null) return IconAnswer.Retry
    return IconAnswer.Image(bytes)
}

private fun sniffSenderPicture(bytes: ByteArray): String? {
    if (bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    ) {
        return "image/png"
    }
    if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
        return "image/jpeg"
    }
    if (bytes.size >= 6) {
        val head = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (head == "GIF87a" || head == "GIF89a") return "image/gif"
    }
    if (bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
        bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
    ) {
        return "image/webp"
    }
    if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte() && bytes[3] == 0.toByte()) {
        return "image/x-icon"
    }
    return null
}

/** A picture decoded far enough to draw, or null when the bytes are not one. */
internal fun decodeSenderPicture(bytes: ByteArray): ImageBitmap? = runCatching {
    val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
    if (image.width <= 0 || image.height <= 0) return@runCatching null
    val bitmap = image.toComposeImageBitmap()
    if (bitmap.width <= 0 || bitmap.height <= 0) null else bitmap
}.getOrNull()

internal sealed class StoredPicture {
    data class Image(val bytes: ByteArray) : StoredPicture()
    data object Miss : StoredPicture()
}

/**
 * Pictures and misses, on disk, for 7 days.
 *
 * A miss is stored on purpose. Most senders have no picture, and asking again for
 * every such row, on every open, is how a quiet inbox becomes a queue of requests.
 * A failure is not stored.
 */
internal class SenderPictureStore(
    private val root: Path,
    private val maxEntries: Int = SENDER_PICTURE_MAX_ENTRIES,
    private val ttlMillis: Long = SENDER_PICTURE_TTL_MILLIS,
) {
    @Synchronized
    fun read(email: String, now: Long = System.currentTimeMillis()): StoredPicture? {
        val path = file(email)
        if (!Files.isRegularFile(path)) return null
        val parsed = runCatching { readEntry(path, now) }.getOrNull()
        if (parsed == null) {
            runCatching { Files.deleteIfExists(path) }
            return null
        }
        return parsed
    }

    @Synchronized
    fun save(email: String, bytes: ByteArray, now: Long = System.currentTimeMillis()) {
        if (bytes.isEmpty() || bytes.size > SENDER_PICTURE_MAX_BYTES) return
        runCatching {
            writeFile(senderPictureKey(email), hit = true, bytes = bytes, now = now)
            evict()
        }
    }

    @Synchronized
    fun saveMiss(email: String, now: Long = System.currentTimeMillis()) {
        runCatching {
            writeFile(senderPictureKey(email), hit = false, bytes = null, now = now)
            evict()
        }
    }

    private fun readEntry(path: Path, now: Long): StoredPicture? {
        DataInputStream(Files.newInputStream(path)).use { input ->
            if (input.readInt() != 1) return null
            val at = input.readLong()
            if (now - at >= ttlMillis) return null
            if (!input.readBoolean()) return StoredPicture.Miss
            val len = input.readInt()
            if (len <= 0 || len > SENDER_PICTURE_MAX_BYTES) return null
            val bytes = ByteArray(len)
            input.readFully(bytes)
            return StoredPicture.Image(bytes)
        }
    }

    private fun writeFile(key: String, hit: Boolean, bytes: ByteArray?, now: Long) {
        Files.createDirectories(root)
        val path = root.resolve(senderPictureHash(key))
        val tmp = Files.createTempFile(root, ".pic", ".tmp")
        try {
            DataOutputStream(Files.newOutputStream(tmp)).use { out ->
                out.writeInt(1)
                out.writeLong(now)
                out.writeBoolean(hit)
                if (hit && bytes != null) {
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
            }
            runCatching {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun evict() {
        if (!Files.isDirectory(root)) return
        val files = Files.list(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".tmp") }.toList()
        }
        if (files.size <= maxEntries) return
        val ranked = files.map { path ->
            val at = runCatching {
                DataInputStream(Files.newInputStream(path)).use { input -> input.readInt(); input.readLong() }
            }.getOrDefault(0L)
            path to at
        }.sortedBy { it.second }
        val keep = (maxEntries * 3) / 4
        val drop = (files.size - keep).coerceAtLeast(1)
        ranked.take(drop).forEach { runCatching { Files.deleteIfExists(it.first) } }
    }

    private fun file(email: String): Path = root.resolve(senderPictureHash(email))
}

/** Absent means we have not looked. Held with null bytes means we looked and there is not one. */
internal sealed class MemorySlot {
    data object Absent : MemorySlot()
    data class Held(val bytes: ByteArray?) : MemorySlot()
}

/**
 * The few pictures currently on screen, so a scroll back up does not read the disk again.
 *
 * Access order: the one that was used least recently is the one dropped. Bytes, not
 * bitmaps, so a test can prove the cap without opening a window.
 */
internal class PictureMemory(private val maxEntries: Int = SENDER_PICTURE_MEMORY) {
    private val map = LinkedHashMap<String, ByteArray?>(16, 0.75f, true)

    @Synchronized
    fun get(key: String): MemorySlot {
        if (!map.containsKey(key)) return MemorySlot.Absent
        return MemorySlot.Held(map[key])
    }

    /** Puts [bytes] and returns the keys that fell off the end, oldest first. */
    @Synchronized
    fun put(key: String, bytes: ByteArray?): List<String> {
        map[key] = bytes
        val dropped = ArrayList<String>()
        while (map.size > maxEntries && map.isNotEmpty()) {
            val eldest = map.entries.iterator().next().key
            map.remove(eldest)
            dropped.add(eldest)
        }
        return dropped
    }
}

internal data class PictureResult(val answer: IconAnswer, val dropped: List<String> = emptyList())

/**
 * Memory, then disk, then [fetch].
 *
 * [fetch] is not called when either cache already knows the answer, which is what keeps
 * a long list from asking again for every row it has already drawn. A [IconAnswer.Retry]
 * is returned as it arrived and stored nowhere.
 */
internal suspend fun pictureFromCaches(
    email: String,
    memory: PictureMemory,
    store: SenderPictureStore,
    now: Long = System.currentTimeMillis(),
    fetch: suspend () -> IconAnswer,
): PictureResult {
    val key = senderPictureKey(email)
    when (val held = memory.get(key)) {
        is MemorySlot.Held -> {
            val bytes = held.bytes
            return if (bytes == null) PictureResult(IconAnswer.Miss) else PictureResult(IconAnswer.Image(bytes))
        }
        MemorySlot.Absent -> Unit
    }
    when (val disk = store.read(key, now)) {
        is StoredPicture.Image -> return PictureResult(IconAnswer.Image(disk.bytes), memory.put(key, disk.bytes))
        StoredPicture.Miss -> return PictureResult(IconAnswer.Miss, memory.put(key, null))
        null -> Unit
    }
    return when (val got = fetch()) {
        is IconAnswer.Image -> {
            store.save(key, got.bytes, now)
            PictureResult(got, memory.put(key, got.bytes))
        }
        IconAnswer.Miss -> {
            store.saveMiss(key, now)
            PictureResult(got, memory.put(key, null))
        }
        IconAnswer.Retry -> PictureResult(got)
    }
}

/**
 * One GET to the companion. The address in [base] is used as it was saved, including a
 * local `http` one: that hop is the reader's own server, not the sender's.
 */
internal object SenderPictureHttp {
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun download(base: String, token: String, domain: String, hash: String): IconAnswer {
        if (base.isBlank() || token.isBlank()) return IconAnswer.Retry
        if (senderDomain("a@$domain") == null) return IconAnswer.Retry
        if (hash.length != 64 || hash.any { it !in '0'..'9' && it !in 'a'..'f' }) return IconAnswer.Retry
        return try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(senderPictureUrl(base, domain, hash)))
                    .header("Authorization", "Bearer $token")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            response.body().use { input ->
                val read = readAtMost(input, SENDER_PICTURE_MAX_BYTES)
                val type = response.headers().firstValue("content-type").orElse("")
                classifyIconResponse(response.statusCode(), type, read.first, read.second)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            IconAnswer.Retry
        }
    }
}

private fun readAtMost(input: InputStream, max: Int): Pair<ByteArray, Boolean> {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) return out.toByteArray() to false
        if (total + n > max) {
            val room = max - total
            if (room > 0) out.write(buf, 0, room)
            return out.toByteArray() to true
        }
        out.write(buf, 0, n)
        total += n
    }
}

/**
 * Bumped when the picture settings change, so the window does not re-read the settings
 * file for every avatar on every frame. Sync writes the file directly, so it bumps too.
 */
internal object SenderPictureSignals {
    val revision = mutableStateOf(0)

    fun bump() {
        // On the window's thread, like every other write here from a background job.
        javax.swing.SwingUtilities.invokeLater { revision.value = revision.value + 1 }
    }
}

/** The choice the window is drawing with. [enabled] is already false when no companion is set. */
internal data class SenderPicturePolicy(val enabled: Boolean, val showInJunk: Boolean)

internal val LocalSenderPicturePolicy = staticCompositionLocalOf {
    SenderPicturePolicy(enabled = false, showInJunk = false)
}

/**
 * Whether the folder on screen is Junk.
 *
 * Set around the mail, and nowhere else. The dashboard and the settings page are not a
 * Junk folder, and a picture there is not a logo sitting on a phishing message.
 */
internal val LocalViewingJunk = staticCompositionLocalOf { false }

/**
 * Loads pictures off the row that asked, a few at a time.
 *
 * The row waits a moment first. Scrolling a long list composes a row and then throws it
 * away, and the wait is long enough that a row which has already gone never starts a
 * request. Once a request has started it finishes, so leaving the row does not abandon
 * a download that another row for the same address can use. The bitmap is published
 * after it decodes. Until then the row keeps its initials, which is also what a broken
 * file becomes.
 */
internal object SenderPictureLoads {
    val pictures = mutableStateMapOf<String, ImageBitmap>()
    val misses = mutableStateMapOf<String, Boolean>()

    private val shown = ConcurrentHashMap<String, ImageBitmap>()
    private val missedKeys = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val queued = AtomicInteger(0)
    private val permits = Semaphore(SENDER_PICTURE_AT_ONCE)
    private val memory = PictureMemory(SENDER_PICTURE_MEMORY)
    private val store by lazy { SenderPictureStore(senderPictureDir()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(email: String) {
        val key = senderPictureKey(email)
        if (senderDomain(key) == null) return
        if (shown.containsKey(key) || missedKeys.contains(key)) return
        val gate = CompletableDeferred<Unit>()
        if (inFlight.putIfAbsent(key, gate) != null) return
        if (queued.get() >= SENDER_PICTURE_QUEUE) {
            inFlight.remove(key, gate)
            return
        }
        queued.incrementAndGet()
        scope.launch {
            try {
                val domain = senderDomain(key) ?: return@launch
                val result = try {
                    pictureFromCaches(key, memory, store) {
                        val base = Settings.trackingServer()
                        val token = runCatching { Secrets.trackingToken().orEmpty() }.getOrDefault("")
                        var took = false
                        try {
                            permits.acquire()
                            took = true
                            SenderPictureHttp.download(base, token, domain, senderPictureHash(key))
                        } finally {
                            if (took) permits.release()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PictureResult(IconAnswer.Retry)
                }
                when (val answer = result.answer) {
                    is IconAnswer.Image -> {
                        val bitmap = decodeSenderPicture(answer.bytes)
                        if (bitmap == null) {
                            store.saveMiss(key)
                            val dropped = (result.dropped + memory.put(key, null)).distinct()
                            publish(key, null, dropped)
                        } else {
                            publish(key, bitmap, result.dropped)
                        }
                    }
                    IconAnswer.Miss -> publish(key, null, result.dropped)
                    IconAnswer.Retry -> Unit
                }
            } finally {
                queued.decrementAndGet()
                inFlight.remove(key)
                gate.complete(Unit)
            }
        }
    }

    private fun publish(key: String, bitmap: ImageBitmap?, dropped: List<String>) {
        for (old in dropped) {
            if (old == key) continue
            shown.remove(old)
            missedKeys.remove(old)
        }
        if (bitmap == null) {
            shown.remove(key)
            missedKeys.add(key)
        } else {
            missedKeys.remove(key)
            shown[key] = bitmap
        }
        // On the window's thread. Several downloads finish at once, and each one applying
        // its own snapshot to the same map conflicted, threw on a pool thread, and closed
        // the app (0.1.443, SnapshotApplyConflictException).
        javax.swing.SwingUtilities.invokeLater {
            for (old in dropped) {
                if (old == key) continue
                pictures.remove(old)
                misses.remove(old)
            }
            if (bitmap == null) {
                pictures.remove(key)
                misses[key] = true
            } else {
                misses.remove(key)
                pictures[key] = bitmap
            }
        }
    }
}

/**
 * The sender picture for [email], or null when there is not one to draw.
 *
 * Null keeps the initials. It is also what Junk returns, unless that folder was
 * switched on, and what a missing companion returns. The lookup itself waits, and it
 * never runs on the thread that is drawing the list.
 */
@Composable
internal fun senderPictureOrNull(email: String): ImageBitmap? {
    val policy = LocalSenderPicturePolicy.current
    if (!senderPictureAllowed(policy.enabled, LocalViewingJunk.current, policy.showInJunk)) return null
    val key = senderPictureKey(email)
    if (senderDomain(key) == null) return null
    val picture = SenderPictureLoads.pictures[key]
    val missed = SenderPictureLoads.misses[key] == true
    if (picture == null && !missed) {
        LaunchedEffect(key) {
            delay(SENDER_PICTURE_SETTLE_MILLIS)
            SenderPictureLoads.enqueue(key)
        }
    }
    return picture
}
