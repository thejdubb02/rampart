package org.rampart

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import org.jsoup.Jsoup

/**
 * Knowing whether a message was read, and being honest about what that means.
 *
 * **This is a tracker and a tracker blocker in one application, and that is the right way
 * round.** Blocking is the default for everybody who receives mail; tracking is switched on
 * per message by somebody who knows what it is. Nothing here is ever on by default and
 * there is deliberately no global "track everything": tracking every message you write to
 * your family is a different act from tracking a sales email, and one switch would pretend
 * they are the same.
 *
 * The whole mechanism is a 1x1 image at an address unique to the message. When it is
 * fetched, the companion server ([docs/open-tracking.md]) writes down that it was, and
 * Rampart asks it what has been fetched since last time. The id is random and means nothing
 * without this client, so the server never learns who anything was sent to. A real read
 * can also raise one desktop notification, and that uses the same [classify] as the count:
 * a scanner popping up is worse than silence, for the same reason a scanner in the count
 * is worse than no count.
 */
internal data class Tracked(
    /** The random id in the pixel's address. */
    val id: String,
    /** Rampart's id for the message in Sent, so a row can be marked. Empty until known. */
    val messageId: String,
    val account: String,
    val recipient: String,
    val subject: String,
    val sentAt: Instant,
    val repliedAt: Instant? = null,
)

internal data class TrackingTimeline(
    val recipient: String,
    val firstRead: Instant?,
    val lastRead: Instant?,
    val reads: Int,
    val clicked: Boolean,
    val repliedAt: Instant?,
    val events: List<Fetch>,
)

internal fun trackingTimeline(tracked: Tracked, events: List<Fetch>): TrackingTimeline {
    val reads = events.filter { it.event == "open" && classify(it, tracked.sentAt) == Opened.READ }
    return TrackingTimeline(
        recipient = tracked.recipient,
        firstRead = reads.minOfOrNull { it.at },
        lastRead = reads.maxOfOrNull { it.at },
        reads = reads.size,
        clicked = events.any { it.event == "click" && classify(it, tracked.sentAt) == Opened.READ },
        repliedAt = tracked.repliedAt,
        events = events.sortedByDescending { it.at },
    )
}

internal fun trackingBadgesOf(rows: List<Pair<Tracked, List<Fetch>>>): Map<String, String> =
    rows.groupBy { it.first.messageId }.mapValues { (_, grouped) ->
        val timelines = grouped.map { trackingTimeline(it.first, it.second) }
        val reads = timelines.sumOf { it.reads }
        when {
            timelines.any { it.repliedAt != null } -> "Replied"
            timelines.any { it.clicked } -> "Clicked"
            reads > 0 -> "Read ${reads}x"
            else -> "Tracked"
        }
    }

/** One fetch of a tracked pixel, as the companion recorded it. */
internal data class Fetch(
    val id: String,
    val at: Instant,
    val userAgent: String,
    val network: String,
    val classification: String = "",
    val event: String = "open",
    val url: String = "",
)

internal fun classificationText(fetch: Fetch): String = when (fetch.classification) {
    "person" -> if (fetch.event == "click") "Clicked by a person" else "Read"
    "apple_privacy" -> "Apple privacy download, may not have been read"
    "security_scanner" -> "Security scanner, not a person"
    "gmail_proxy" -> "Gmail, first open only"
    "repeat" -> "Repeated download"
    else -> "Unclear"
}

/**
 * What a fetch actually was.
 *
 * **The whole value of the feature is in this distinction.** An open count that includes
 * scanners and image proxies is a number that makes somebody chase a lead who never read
 * anything, which is worse than having no number: it is a number that lies in the
 * direction you want to believe.
 */
internal enum class Opened {
    /** A person, as far as can be told. */
    READ,

    /** A machine: a proxy, a prefetch, a scanner. Recorded, never counted as a read. */
    AUTOMATIC,

    /** Genuinely cannot tell. Shown as such rather than guessed either way. */
    UNCLEAR,
}

/**
 * Which of the three a fetch is.
 *
 * Conservative on purpose: where a signal says machine, it is machine. The cost of
 * under-counting is a read you did not hear about; the cost of over-counting is acting on
 * one that never happened.
 */
internal fun classify(fetch: Fetch, sentAt: Instant): Opened {
    when (fetch.classification) {
        "person" -> return Opened.READ
        "apple_privacy", "gmail_proxy", "security_scanner", "repeat" -> return Opened.AUTOMATIC
    }
    val agent = fetch.userAgent.lowercase()
    if (MACHINE_AGENTS.any { it in agent }) return Opened.AUTOMATIC
    /*
     * Within a few seconds of sending, nobody has read anything.
     *
     * This is delivery: the receiving server, a filter, or an image proxy fetching
     * everything on the way in. Apple Mail Privacy Protection does exactly this for every
     * Apple user who has it on, which is most of them, and it is why a raw open count from
     * any provider is inflated.
     */
    if (Duration.between(sentAt, fetch.at) < Duration.ofSeconds(30)) return Opened.AUTOMATIC
    // A browser that named itself is the one case that reads as a person.
    if (HUMAN_AGENTS.any { it in agent }) return Opened.READ
    return if (fetch.userAgent.isBlank()) Opened.UNCLEAR else Opened.UNCLEAR
}

/**
 * Agents that are definitely not a person reading.
 *
 * Google's proxy is the big one: Gmail fetches every image through it at delivery, so on a
 * Gmail recipient the first fetch is always the proxy and never the reader.
 */
private val MACHINE_AGENTS = listOf(
    "googleimageproxy", "google-read-aloud", "feedfetcher", "apis-google",
    "yahoomailproxy", "yahoo! slurp",
    "microsoft office", "ms-office", "outlook-ios-android", "skypeuripreview",
    "bingpreview", "msnbot", "bingbot",
    "proofpoint", "barracuda", "mimecast", "symantec", "forcepoint", "trend micro",
    "python-requests", "curl/", "wget/", "go-http-client", "java/", "okhttp",
    "headlesschrome", "phantomjs", "slackbot", "discordbot", "whatsapp", "telegrambot",
    "bot", "crawler", "spider", "scanner", "monitor", "preview",
)

/** Agents that name a real browser or mail client, which is as close to a person as this gets. */
private val HUMAN_AGENTS = listOf("mozilla", "applewebkit", "safari", "chrome", "firefox", "edge/", "thunderbird")

/**
 * How many real reads, and when the first one was.
 *
 * Counted rather than stored, because the classification can improve and a stored verdict
 * would keep the old answer forever.
 */
internal data class Opens(val reads: Int, val automatic: Int, val firstRead: Instant?) {
    val wasRead: Boolean get() = reads > 0
}

internal fun opensOf(fetches: List<Fetch>, sentAt: Instant): Opens {
    val judged = fetches.filter { it.event == "open" }.map { it to classify(it, sentAt) }
    val reads = judged.filter { it.second == Opened.READ }
    return Opens(
        reads = reads.size,
        automatic = judged.count { it.second == Opened.AUTOMATIC },
        firstRead = reads.minByOrNull { it.first.at }?.first?.at,
    )
}

/**
 * Which of [fetches] are worth a notification.
 *
 * Only [Opened.READ] gets through, and [classify] is the only thing that decides that.
 * A second opinion here would be how a scanner starts popping up while the count on the
 * message stays still, and the two disagreeing is worse than either one alone.
 *
 * A fetch with no row in [tracked] is dropped. There is no sent time to classify against
 * and no recipient or subject to put in the notification, so announcing it would be a
 * popup that says nothing.
 *
 * One message fetched twice in the same poll is one row. The notification is about the
 * message, not about each time its pixel was asked for. Rows come back in the order the
 * fetches happened, so the earliest open is the one named first.
 */
internal fun opensToAnnounce(fetches: List<Fetch>, tracked: Map<String, Tracked>): List<Tracked> {
    val seen = HashSet<String>()
    return fetches.sortedBy { it.at }.mapNotNull { fetch ->
        if (fetch.event != "open") return@mapNotNull null
        val row = tracked[fetch.id] ?: return@mapNotNull null
        if (row.repliedAt != null) return@mapNotNull null
        if (classify(fetch, row.sentAt) != Opened.READ) return@mapNotNull null
        if (!seen.add(row.id)) return@mapNotNull null
        row
    }
}

/**
 * One line for the notification. Several opens at once become one notification rather
 * than a stack of them, because a stack is what makes people turn notifications off.
 *
 * The title and the body between them say who opened it and which message. That is the
 * part a badge on the message itself cannot say while you are doing something else,
 * the same compromise [arrivalText] makes for new mail.
 */
internal fun openText(opened: List<Tracked>): Pair<String, String>? = when (opened.size) {
    0 -> null
    1 -> "Opened by ${opened[0].recipient}" to opened[0].subject.ifBlank { "(no subject)" }
    else -> "${opened.size} messages opened" to opened.take(3).joinToString(", ") {
        "${it.recipient}: ${it.subject.ifBlank { "(no subject)" }}"
    }
}

/**
 * A new tracking id.
 *
 * 128 bits from [SecureRandom], and **not derived from the recipient, the subject or the
 * time**. An id that encodes who a message was for is a leak to everyone who sees the URL,
 * which includes every mail server it passes through on the way.
 *
 * URL-safe base64 with the padding off, so it survives being a path segment untouched.
 */
internal fun newTrackingId(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * The pixel, as it goes into the message.
 *
 * Width and height stated so a client that respects them draws nothing rather than a
 * broken-image box, and `alt` empty so a screen reader does not announce it. There is no
 * way to make this invisible to a determined reader and no attempt is made to: Rampart
 * itself names this exact shape as a tracker when it arrives in somebody else's mail.
 */
internal fun pixelHtml(base: String, id: String): String =
    """<img src="${pixelUrl(base, id)}" width="1" height="1" alt="" style="display:none" />"""

/** The address the pixel is fetched from. */
internal fun pixelUrl(base: String, id: String): String =
    base.trim().trimEnd('/') + "/o/" + id + ".gif"

internal data class RewrittenLinks(val html: String, val originals: List<String>)

/** Rewrites web destinations outside quoted replies and leaves every other href alone. */
internal fun rewriteTrackedLinks(html: String, base: String, id: String): RewrittenLinks {
    val document = Jsoup.parseBodyFragment(html)
    document.outputSettings().prettyPrint(false)
    val originals = mutableListOf<String>()
    document.select("a[href]").forEach { anchor ->
        if (anchor.parents().any { it.tagName().equals("blockquote", ignoreCase = true) }) return@forEach
        val original = anchor.attr("href").trim()
        val scheme = runCatching { java.net.URI(original).scheme?.lowercase() }.getOrNull()
        if (scheme !in setOf("http", "https")) return@forEach
        val number = originals.size
        originals += original
        anchor.attr("href", base.trim().trimEnd('/') + "/c/$id/$number")
    }
    return RewrittenLinks(document.body().html(), originals)
}

/** Restores redirects made by this client while leaving every unfamiliar address alone. */
internal fun restoreTrackedLinks(
    html: String,
    base: String,
    originals: Map<Pair<String, Int>, String>,
): String {
    if (base.isBlank() || originals.isEmpty()) return html
    val server = runCatching { java.net.URI(base.trim().trimEnd('/')) }.getOrNull() ?: return html
    val serverPath = server.path.orEmpty().trimEnd('/')
    val document = Jsoup.parseBodyFragment(html)
    document.outputSettings().prettyPrint(false)
    document.select("a[href]").forEach { anchor ->
        val target = runCatching { java.net.URI(anchor.attr("href").trim()) }.getOrNull() ?: return@forEach
        if (
            !target.scheme.equals(server.scheme, ignoreCase = true) ||
            !target.host.equals(server.host, ignoreCase = true) ||
            target.port != server.port || target.userInfo != server.userInfo
        ) return@forEach
        val route = target.path.orEmpty().removePrefix("$serverPath/c/")
        if (route == target.path) return@forEach
        val parts = route.split('/')
        if (parts.size != 2) return@forEach
        val number = parts[1].toIntOrNull() ?: return@forEach
        originals[parts[0] to number]?.let { anchor.attr("href", it) }
    }
    return document.body().html()
}

/** Restores both representations used by the reader, Rook, replies, and forwards. */
internal fun restoreTrackedLinks(
    body: Body,
    base: String,
    originals: Map<Pair<String, Int>, String>,
): Body {
    if (base.isBlank() || originals.isEmpty()) return body
    val prefix = base.trim().trimEnd('/')
    val text = originals.entries.fold(body.text) { current, (key, original) ->
        current?.replace("$prefix/c/${key.first}/${key.second}", original)
    }
    return body.copy(
        html = body.html?.let { restoreTrackedLinks(it, base, originals) },
        text = text,
    )
}

/**
 * Whether a base URL can carry a pixel at all.
 *
 * **HTTPS only, and said out loud rather than quietly corrected.** A pixel fetched over
 * plain HTTP tells every network between the recipient and the server who is mailing whom,
 * which is a worse leak than the one the feature is for. Loopback is allowed because that
 * is how somebody tests one.
 */
internal fun trackingProblem(base: String): String? {
    val url = base.trim()
    if (url.isBlank()) return "Give Rampart the address of your companion server."
    val parsed = runCatching { java.net.URI(url) }.getOrNull()
        ?: return "That is not a web address."
    val host = parsed.host ?: return "That address has no hostname in it."
    val local = host == "localhost" || host == "127.0.0.1" || host == "::1"
    if (parsed.scheme?.lowercase() != "https" && !local) {
        return "The address has to be https. Over plain http, every network between your " +
            "reader and your server can see who you are mailing."
    }
    return null
}

/**
 * Whether tracking is on for this recipient.
 *
 * **Remembered by domain rather than by address.** Somebody deciding to track outreach has
 * decided about a company, not about one person there, and being asked again for every new
 * contact at the same place is how a per-message switch becomes a nuisance that gets turned
 * on globally.
 */
internal fun trackingDomain(address: String): String = domainOf(address)
