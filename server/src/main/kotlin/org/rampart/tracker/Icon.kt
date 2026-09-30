package org.rampart.tracker

import com.sun.net.httpserver.HttpExchange
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Semaphore

/**
 * Sender pictures, fetched by this server so a sender never sees the reader.
 *
 * A picture looked up by the address that mailed you is a tracking pixel wearing a
 * friendlier name: the request says who you correspond with, and when you looked.
 * Rampart therefore never asks the sender's site itself. It asks here, over the same
 * token as [opens], and this server asks. The sender's site sees this server's address,
 * on a cache miss, and not the reader's. A picture already fetched is kept for a week,
 * so opening the message again does not ask again.
 *
 * Libravatar can name a per-domain avatar host in DNS. Following that host would mean
 * connecting to a server the sender's domain chose, which is the SSRF this route exists
 * to refuse, and it would hand that server the moment of the lookup. The central
 * service is one known host. Federation is not worth either cost.
 */

/** How long a picture, or the fact that there is not one, is reused. */
internal const val ICON_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/** A few thousand. Past this, the oldest entries are dropped. */
internal const val ICON_MAX_ENTRIES: Int = 4000

/** Homepage and picture alike. An icon larger than this is not an icon. */
internal const val ICON_MAX_BYTES: Int = 256 * 1024

private const val ICON_TIMEOUT_SECONDS: Long = 5
private const val ICON_MAX_REDIRECTS: Int = 3

/**
 * Mail providers whose own logo says nothing about the person who wrote.
 *
 * Matched on the domain and on anything under it (`mail.google.com` is still Google).
 * Libravatar is still asked: a person can have published a picture there. The domain's
 * icon is not, because it would be Gmail's logo on every Gmail sender.
 */
internal val FREE_MAIL_DOMAINS: Set<String> = setOf(
    "gmail.com", "googlemail.com",
    "outlook.com", "hotmail.com", "live.com", "msn.com",
    "outlook.co.uk", "hotmail.co.uk", "live.co.uk",
    "yahoo.com", "yahoo.co.uk", "yahoo.fr", "yahoo.de", "ymail.com", "rocketmail.com",
    "icloud.com", "me.com", "mac.com",
    "proton.me", "protonmail.com", "pm.me", "protonmail.ch",
    "aol.com",
    "gmx.com", "gmx.net", "gmx.de", "web.de",
    "mail.com",
    "zoho.com",
    "fastmail.com", "fastmail.fm",
    "tutanota.com", "tuta.com",
    "yandex.com", "yandex.ru",
    "qq.com", "163.com", "126.com",
    "hey.com",
    "duck.com",
    "mailbox.org",
    "posteo.de", "posteo.net",
    "runbox.com",
    "hushmail.com",
    "mail.ru",
    "comcast.net", "verizon.net", "att.net", "sbcglobal.net",
    "cox.net", "charter.net",
)

internal fun skipsDomainIcon(domain: String): Boolean {
    val host = domain.trim().lowercase().trimEnd('.')
    return FREE_MAIL_DOMAINS.any { host == it || host.endsWith(".$it") }
}

/**
 * Whether [domain] is a name we are willing to connect to, before DNS.
 *
 * Letters, digits, dots and hyphens only, and a real hostname rather than an address.
 * An IP, `localhost`, or a single label is how this route would be pointed at the
 * machine it is running on. Returns null when the name is acceptable.
 */
internal fun domainSyntaxProblem(domain: String): String? {
    val host = domain.trim().lowercase().trimEnd('.')
    if (host.isEmpty() || host.length > 253) return "empty or too long"
    if (host.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' }) return "odd character"
    if (host.startsWith('.') || host.contains("..")) return "empty label"
    // All digits and dots is an address, including the short forms (`127.1`). A name has a letter.
    if (host.all { it.isDigit() || it == '.' }) return "ip"
    if (host == "localhost" || host.endsWith(".localhost") || host == "localhost.localdomain") return "localhost"
    val labels = host.split('.')
    if (labels.size < 2) return "not a domain"
    if (labels.any { label ->
            label.isEmpty() || label.length > 63 || label.startsWith('-') || label.endsWith('-')
        }
    ) {
        return "bad label"
    }
    // Names that resolve on the machine itself, or are defined to. `.internal` is what
    // cloud metadata uses. Connecting to one of these is the bug this check exists for.
    if (host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localdomain") ||
        host.endsWith(".lan") || host.endsWith(".home") || host.endsWith(".intranet")
    ) {
        return "local name"
    }
    return null
}

internal fun normalizeDomain(domain: String): String? {
    val host = domain.trim().lowercase().trimEnd('.')
    return if (domainSyntaxProblem(host) == null) host else null
}

/** The 64 hex characters of a SHA-256, which is all this route accepts as an address. */
internal fun normalizeEmailHash(raw: String): String? {
    val hash = raw.trim().lowercase()
    if (hash.length != 64 || hash.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    return hash
}

/**
 * Whether [address] is somewhere on the public internet.
 *
 * Checked after DNS, on every address the name returned. One private address is enough
 * to refuse the name: a host that answers with both a public address and `10.x.x.x` is
 * how a lookup gets steered at the network behind this server. Mapped IPv4 (`::ffff:10.0.0.1`)
 * is judged as the IPv4 address inside it, because the JDK does not always report that
 * form as site-local.
 */
internal fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) {
        return false
    }
    return isPublicAddressBytes(address.address)
}

internal fun isPublicAddressBytes(raw: ByteArray): Boolean {
    val bytes = if (isV4Mapped(raw)) raw.copyOfRange(12, 16) else raw
    if (bytes.size == 4) {
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        val b2 = bytes[2].toInt() and 0xff
        if (b0 == 0 || b0 == 10 || b0 == 127) return false
        if (b0 == 100 && b1 in 64..127) return false
        if (b0 == 169 && b1 == 254) return false
        if (b0 == 172 && b1 in 16..31) return false
        if (b0 == 192 && b1 == 168) return false
        if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) return false
        if (b0 == 198 && b1 in 18..19) return false
        if (b0 == 198 && b1 == 51 && b2 == 100) return false
        if (b0 == 203 && b1 == 0 && b2 == 113) return false
        if (b0 >= 224) return false
        return true
    }
    if (bytes.size == 16) {
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        if (bytes.all { it == 0.toByte() }) return false
        if (bytes[15] == 1.toByte() && (0 until 15).all { bytes[it] == 0.toByte() }) return false
        if (b0 and 0xfe == 0xfc) return false
        if (b0 == 0xfe && (b1 and 0xc0) == 0x80) return false
        if (b0 == 0xfe && (b1 and 0xc0) == 0xc0) return false
        if (b0 == 0xff) return false
        if (b0 == 0x20 && b1 == 0x01 && bytes[2] == 0x0d.toByte() && bytes[3] == 0xb8.toByte()) return false
        return true
    }
    return false
}

private fun isV4Mapped(raw: ByteArray): Boolean =
    raw.size == 16 &&
        (0 until 10).all { raw[it] == 0.toByte() } &&
        raw[10] == 0xff.toByte() &&
        raw[11] == 0xff.toByte()

/** What a name became after DNS. Empty or a thrown lookup is [Unknown], not a miss to cache. */
internal enum class DomainGate { Public, Private, Unknown }

internal fun domainGate(domain: String, resolve: (String) -> List<InetAddress>): DomainGate {
    val addresses = try {
        resolve(domain)
    } catch (e: Exception) {
        return DomainGate.Unknown
    }
    if (addresses.isEmpty()) return DomainGate.Unknown
    if (addresses.any { !isPublicAddress(it) }) return DomainGate.Private
    return DomainGate.Public
}

internal fun libravatarUrl(emailHash: String): String =
    "https://seccdn.libravatar.org/avatar/${emailHash.lowercase()}?s=96&d=404"

internal data class IconImage(val bytes: ByteArray, val type: String)

internal sealed interface IconOutcome {
    data class Found(val image: IconImage) : IconOutcome
    data object Missing : IconOutcome
    data object Unavailable : IconOutcome
    data object BadRequest : IconOutcome
}

internal data class IconBody(
    val status: Int,
    val bytes: ByteArray,
    val contentType: String,
    val finalUrl: String,
    val truncated: Boolean = false,
)

internal fun interface IconTransport {
    fun get(url: String): IconBody?
}

/**
 * The picture for one address, or the reason there isn't one.
 *
 * Libravatar first, by the hash the client already computed, so this server never sees
 * the address. Then the domain's own icon, unless [domain] is a mail provider whose logo
 * would be the same on every sender. [resolve] is how the domain step is refused when the
 * name points at a private network. A null [resolve] skips that gate; the transport still
 * has to refuse the connection itself.
 */
internal fun lookupIcon(
    domain: String,
    emailHash: String,
    transport: IconTransport,
    resolve: ((String) -> List<InetAddress>)? = null,
): IconOutcome {
    val steps = IconSteps(transport)
    steps.imageAt(libravatarUrl(emailHash))?.let { return IconOutcome.Found(it) }
    if (skipsDomainIcon(domain)) {
        return if (steps.uncertain) IconOutcome.Unavailable else IconOutcome.Missing
    }
    if (resolve != null) {
        when (domainGate(domain, resolve)) {
            DomainGate.Private -> return IconOutcome.BadRequest
            DomainGate.Unknown -> return IconOutcome.Unavailable
            DomainGate.Public -> Unit
        }
    }
    val pageUrl = "https://$domain/"
    val page = steps.pageAt(pageUrl)
    val tried = LinkedHashSet<String>()
    if (page != null) {
        for (url in iconUrls(page.html, page.finalUrl)) {
            tried.add(url)
            steps.imageAt(url)?.let { return IconOutcome.Found(it) }
        }
    }
    val favicon = "https://$domain/favicon.ico"
    if (favicon !in tried) {
        steps.imageAt(favicon)?.let { return IconOutcome.Found(it) }
    }
    return if (steps.uncertain) IconOutcome.Unavailable else IconOutcome.Missing
}

private class IconSteps(private val transport: IconTransport) {
    var uncertain: Boolean = false

    fun imageAt(url: String): IconImage? {
        val body = fetch(url) ?: return null
        if (body.status == 404 || body.status == 410) return null
        if (body.status != 200) {
            uncertain = true
            return null
        }
        if (body.truncated) {
            uncertain = true
            return null
        }
        val type = imageContentType(body.contentType, body.bytes) ?: return null
        return IconImage(body.bytes, type)
    }

    fun pageAt(url: String): PageBody? {
        val body = fetch(url) ?: return null
        if (body.status == 404 || body.status == 410) return null
        if (body.status != 200) {
            uncertain = true
            return null
        }
        // The part we got can still name an icon. It cannot prove there is not one:
        // the link may have been past the byte cap, so a miss here is not stored.
        if (body.truncated) uncertain = true
        val declared = headerType(body.contentType)
        if (declared.startsWith("image/")) return null
        return PageBody(body.bytes.toString(Charsets.UTF_8), body.finalUrl.ifBlank { url })
    }

    private fun fetch(url: String): IconBody? {
        val body = try {
            transport.get(url)
        } catch (e: Exception) {
            uncertain = true
            null
        }
        if (body == null) uncertain = true
        return body
    }
}

private data class PageBody(val html: String, val finalUrl: String)

/**
 * Icon links in [html], largest first, as absolute https URLs.
 *
 * `rel` has to contain `icon` or `apple-touch-icon` (the `shortcut icon` spelling counts,
 * because that is how a favicon is usually written). The size is the `sizes` attribute
 * when it has one, with `any` treated as large because it scales, and an apple touch icon
 * without a size treated as the 180px one those are. Plain http is dropped: this server
 * does not fetch a picture over a connection anyone in between can read.
 */
internal fun iconUrls(html: String, pageUrl: String): List<String> {
    val found = ArrayList<Pair<String, Int>>()
    val lower = html.lowercase()
    var from = 0
    while (from < lower.length) {
        val start = lower.indexOf("<link", from)
        if (start < 0) break
        val after = start + 5
        if (after < lower.length) {
            val next = lower[after]
            if (next != ' ' && next != '\t' && next != '\n' && next != '\r' && next != '>' && next != '/') {
                from = after
                continue
            }
        }
        val end = lower.indexOf('>', start)
        if (end < 0) break
        val tag = html.substring(start, end + 1)
        from = end + 1
        val rel = htmlAttr(tag, "rel") ?: continue
        val tokens = rel.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val apple = tokens.any { it == "apple-touch-icon" || it == "apple-touch-icon-precomposed" }
        if ("icon" !in tokens && !apple) continue
        val href = htmlAttr(tag, "href")?.trim().orEmpty()
        if (href.isEmpty()) continue
        val absolute = httpsUrl(pageUrl, href) ?: continue
        var area = declaredIconArea(htmlAttr(tag, "sizes").orEmpty())
        if (area == 0) area = if (apple) 180 * 180 else 16 * 16
        found.add(absolute to area)
    }
    val ordered = found.sortedByDescending { it.second }
    val urls = LinkedHashSet<String>()
    ordered.forEach { urls.add(it.first) }
    return urls.toList()
}

internal fun pickIconUrl(html: String, pageUrl: String): String? = iconUrls(html, pageUrl).firstOrNull()

internal fun declaredIconArea(sizes: String): Int {
    val areas = Regex("(\\d+)\\s*x\\s*(\\d+)", RegexOption.IGNORE_CASE).findAll(sizes).mapNotNull {
        val w = it.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val h = it.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        if (w <= 0 || h <= 0) null else w * h
    }.toList()
    if (areas.isNotEmpty()) return areas.max().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    if (sizes.trim().equals("any", true)) return 512 * 512
    return 0
}

internal fun htmlAttr(tag: String, name: String): String? {
    val match = Regex(
        """(?i)\b${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""",
    ).find(tag) ?: return null
    return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
}

/** An absolute https URL for [href] as written on [page], or null when it is not one we will fetch. */
internal fun httpsUrl(page: String, href: String): String? {
    val raw = href.trim()
    if (raw.isEmpty() || raw.any { it.isISOControl() || it == ' ' || it == '\\' }) return null
    if (raw.startsWith("data:", true) || raw.startsWith("javascript:", true) || raw.startsWith("http://", true)) {
        return null
    }
    val absolute = when {
        raw.startsWith("https://", true) -> raw
        raw.startsWith("//") -> "https:$raw"
        else -> {
            val base = runCatching { URI(page) }.getOrNull() ?: return null
            runCatching { base.resolve(raw).toString() }.getOrNull() ?: return null
        }
    }
    val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", true)) return null
    if (!uri.userInfo.isNullOrEmpty()) return null
    if (uri.port != -1 && uri.port != 443) return null
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
    if (domainSyntaxProblem(host) != null) return null
    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "https://$host$path$query"
}

/**
 * A picture the client can draw, or null.
 *
 * The header is not trusted on its own: a soft 404 is often `image/png` full of HTML, and
 * drawing that would be the broken image this feature is not allowed to show. SVG is
 * refused too. It does not sniff, and a file that says it is an icon has no business
 * carrying a script.
 */
internal fun imageContentType(header: String, bytes: ByteArray): String? {
    val declared = headerType(header)
    if (declared.startsWith("text/") || declared == "application/xhtml+xml") return null
    if (declared == "image/svg+xml" || declared == "text/xml" || declared == "application/xml") return null
    val sniffed = sniffImage(bytes) ?: return null
    return if (declared.startsWith("image/")) declared else sniffed
}

private fun headerType(header: String): String = header.substringBefore(';').trim().lowercase()

internal fun sniffImage(bytes: ByteArray): String? {
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

/**
 * One GET, https only, at most [ICON_MAX_REDIRECTS] redirects, each of which is checked
 * again. A redirect at an internal address is how a public page becomes an SSRF, so the
 * check is on the hop that is about to be fetched, not only on the URL we were given.
 */
internal class HttpsIconTransport(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(ICON_TIMEOUT_SECONDS))
        .build(),
) : IconTransport {
    override fun get(url: String): IconBody? {
        var current = url
        var redirects = 0
        while (true) {
            if (!iconTargetAllowed(current, resolve)) return null
            val request = HttpRequest.newBuilder(URI(current))
                .timeout(Duration.ofSeconds(ICON_TIMEOUT_SECONDS))
                .header("User-Agent", "RampartCompanion")
                .header("Accept", "text/html,image/*;q=0.8,*/*;q=0.1")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { input ->
                val code = response.statusCode()
                if (code in 300..399) {
                    if (redirects >= ICON_MAX_REDIRECTS) return null
                    val location = response.headers().firstValue("location").orElse(null) ?: return null
                    current = httpsUrl(current, location) ?: return null
                    redirects++
                    return@use
                }
                val read = readAtMost(input, ICON_MAX_BYTES)
                val type = response.headers().firstValue("content-type").orElse("")
                return IconBody(code, read.first, type, current, read.second)
            }
        }
    }
}

/**
 * The connection resolves the name again rather than using the address checked here, so a
 * name that changes its answer in between could point the second lookup inward. That is
 * accepted: only https on 443 is allowed and the certificate must match the sender's own
 * name, so an internal service cannot complete the handshake and no request is ever sent.
 */
internal fun iconTargetAllowed(url: String, resolve: (String) -> List<InetAddress>): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", true)) return false
    if (!uri.userInfo.isNullOrEmpty()) return false
    if (uri.port != -1 && uri.port != 443) return false
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
    if (domainSyntaxProblem(host) != null) return false
    return domainGate(host, resolve) == DomainGate.Public
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
 * Pictures and misses, on disk, for a week.
 *
 * A miss is stored on purpose. The interesting case is a sender with no picture, and
 * asking again for every such row, on every open, is how a quiet inbox becomes a queue
 * of outbound requests. Failures are not stored: a timeout is not the same as "no picture".
 */
internal class IconCache(
    private val root: Path,
    private val maxEntries: Int = ICON_MAX_ENTRIES,
    private val ttlMillis: Long = ICON_TTL_MILLIS,
) {
    @Synchronized
    fun get(emailHash: String, domain: String, now: Long = System.currentTimeMillis()): IconOutcome? {
        val path = file(emailHash, domain)
        if (!Files.isRegularFile(path)) return null
        val read = runCatching { readEntry(path, now) }.getOrNull()
        if (read == null) {
            runCatching { Files.deleteIfExists(path) }
            return null
        }
        return read
    }

    @Synchronized
    fun put(emailHash: String, domain: String, outcome: IconOutcome, now: Long = System.currentTimeMillis()) {
        val image = when (outcome) {
            is IconOutcome.Found -> outcome.image
            IconOutcome.Missing -> null
            else -> return
        }
        runCatching {
            Files.createDirectories(root)
            val path = file(emailHash, domain)
            val tmp = Files.createTempFile(root, ".icon", ".tmp")
            try {
                DataOutputStream(Files.newOutputStream(tmp)).use { out ->
                    out.writeInt(1)
                    out.writeLong(now)
                    out.writeBoolean(image != null)
                    out.writeUTF(image?.type.orEmpty())
                    if (image != null) out.write(image.bytes)
                }
                runCatching {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }.getOrElse {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
            evict()
        }
    }

    private fun readEntry(path: Path, now: Long): IconOutcome? {
        DataInputStream(Files.newInputStream(path)).use { input ->
            if (input.readInt() != 1) return null
            val at = input.readLong()
            if (now - at >= ttlMillis) return null
            val hit = input.readBoolean()
            val type = input.readUTF()
            if (!hit) return IconOutcome.Missing
            val bytes = input.readBytes()
            if (bytes.isEmpty() || type.isBlank()) return null
            return IconOutcome.Found(IconImage(bytes, type))
        }
    }

    private fun evict() {
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

    private fun file(emailHash: String, domain: String): Path =
        root.resolve(sha256Hex("$emailHash\n$domain"))
}

internal fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

internal class IconService(
    private val cache: IconCache = IconCache(
        Path.of(System.getenv("RAMPART_ICON_CACHE") ?: "/data/icons"),
    ),
    private val transport: IconTransport = HttpsIconTransport(),
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    fun lookup(domainRaw: String, emailRaw: String): IconOutcome {
        val domain = normalizeDomain(domainRaw) ?: return IconOutcome.BadRequest
        val email = normalizeEmailHash(emailRaw) ?: return IconOutcome.BadRequest
        cache.get(email, domain)?.let { return it }
        val outcome = lookupIcon(domain, email, transport, resolve)
        if (outcome is IconOutcome.Found || outcome is IconOutcome.Missing) cache.put(email, domain, outcome)
        return outcome
    }
}

private val iconPermits = Semaphore(4)

/**
 * `GET /icon?domain=&email=`, with the same token as [opens].
 *
 * The token is what stops this being an open proxy. Without it, anyone who found the
 * hostname could ask this server to fetch a URL shaped like a homepage. The diag token
 * is not accepted: that one is allowed to post numbers and nothing else.
 */
internal fun handleIcon(exchange: HttpExchange, token: String, service: IconService) {
    if (exchange.requestMethod != "GET") {
        reply(exchange, 405, """{"error":"use GET"}""".toByteArray(), "application/json")
        return
    }
    val given = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ").trim()
    if (!sameToken(given, token)) {
        reply(exchange, 401, """{"error":"unauthorised"}""".toByteArray(), "application/json")
        return
    }
    val query = queryMap(exchange.requestURI.rawQuery)
    val domain = query["domain"].orEmpty()
    val email = query["email"].orEmpty()
    if (normalizeDomain(domain) == null || normalizeEmailHash(email) == null) {
        reply(exchange, 400, """{"error":"bad request"}""".toByteArray(), "application/json")
        return
    }
    // A few at a time. Each one may wait on another server, and the pixel must not wait with it.
    if (!iconPermits.tryAcquire()) {
        reply(exchange, 503, ByteArray(0), "text/plain")
        return
    }
    try {
        when (val outcome = service.lookup(domain, email)) {
            is IconOutcome.Found -> {
                exchange.responseHeaders.add("Cache-Control", "private, max-age=604800")
                reply(exchange, 200, outcome.image.bytes, outcome.image.type)
            }
            IconOutcome.Missing -> {
                exchange.responseHeaders.add("Cache-Control", "private, max-age=604800")
                reply(exchange, 404, ByteArray(0), "text/plain")
            }
            IconOutcome.BadRequest ->
                reply(exchange, 400, """{"error":"bad request"}""".toByteArray(), "application/json")
            IconOutcome.Unavailable -> reply(exchange, 502, ByteArray(0), "text/plain")
        }
    } catch (e: Exception) {
        System.err.println("icon lookup failed: ${e.javaClass.simpleName}")
        if (!exchange.responseHeaders.containsKey("Date")) {
            runCatching { reply(exchange, 502, ByteArray(0), "text/plain") }
        }
    } finally {
        iconPermits.release()
    }
}

internal fun queryMap(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in raw.split('&')) {
        if (part.isEmpty()) continue
        val cut = part.indexOf('=')
        val key = urlDecode(if (cut < 0) part else part.substring(0, cut))
        val value = urlDecode(if (cut < 0) "" else part.substring(cut + 1))
        if (key.isNotEmpty()) out.putIfAbsent(key, value)
    }
    return out
}

private fun urlDecode(value: String): String =
    runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)
