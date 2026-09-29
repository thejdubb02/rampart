package org.rampart.tracker

/** The small set of signals used to decide whether one pixel fetch represents a person. */
data class OpenSignals(
    val userAgent: String,
    val network: String,
    val at: Long,
    val previous: List<Fetch>,
)

private val securityNames = listOf(
    "proofpoint", "mimecast", "barracuda", "microsoft office", "microsoft outlook safe links",
    "microsoft defender", "safelinks", "urlscan", "virustotal", "security scanner", "link scanner",
)

private val cloudScannerNames = listOf(
    "amazonaws", "googlecloud", "azure", "cloudflare", "headlesschrome", "phantomjs", "python-requests", "curl/",
)

/**
 * Classifies one fetch from its request signals and this token's earlier fetches.
 *
 * Order matters. Named privacy proxies and scanners stay named even when they repeat.
 */
fun classifyOpen(signals: OpenSignals): OpenClassification {
    val ua = signals.userAgent.lowercase()
    val first = signals.previous.firstOrNull()
    return when {
        "googleimageproxy" in ua || "google image proxy" in ua -> OpenClassification.GMAIL_PROXY
        "applewebkit" in ua && ("apple mail" in ua || "mailprivacy" in ua) -> OpenClassification.APPLE_PRIVACY
        "apple-mail" in ua || "icloud mail" in ua -> OpenClassification.APPLE_PRIVACY
        securityNames.any { it in ua } || cloudScannerNames.any { it in ua } -> OpenClassification.SECURITY_SCANNER
        first != null && signals.at - first.at in 0..15_000 && !looksLikeBrowser(ua) ->
            OpenClassification.SECURITY_SCANNER
        signals.previous.any { signals.at - it.at in 0..60_000 } -> OpenClassification.REPEAT
        else -> OpenClassification.PERSON
    }
}

private fun looksLikeBrowser(ua: String): Boolean =
    "mozilla/" in ua && listOf("chrome/", "safari/", "firefox/", "edg/").any { it in ua }
