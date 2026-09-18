package org.rampart

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

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
 * without this client, so the server never learns who anything was sent to.
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
)

/** One fetch of a tracked pixel, as the companion recorded it. */
internal data class Fetch(
    val id: String,
    val at: Instant,
    val userAgent: String,
    val network: String,
)

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
    val judged = fetches.map { it to classify(it, sentAt) }
    val reads = judged.filter { it.second == Opened.READ }
    return Opens(
        reads = reads.size,
        automatic = judged.count { it.second == Opened.AUTOMATIC },
        firstRead = reads.minByOrNull { it.first.at }?.first?.at,
    )
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
