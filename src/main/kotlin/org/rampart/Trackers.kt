package org.rampart

/**
 * A remote picture, and whether it is a picture.
 *
 * Rampart has always blocked these and counted them. It already knows which are remote,
 * because that is the decision it makes to block them, so naming the ones that are tracking
 * pixels is a short step from where it stood, and it changes what the blocking is worth: a
 * number tells you something was held back, a name tells you who was asking.
 *
 * **This is a tracker blocker and a tracker in one application, and that is the right way
 * round.** Blocking is the default for everybody; sending a tracked message is switched on
 * per message by somebody who knows what it is.
 */
internal data class RemotePicture(
    val url: String,
    /** The company behind it, when it is one we can name. Empty when it is not. */
    val tracker: String,
    /** Why it was called a tracker, in words. Empty when it was not called one. */
    val because: String,
) {
    val isTracker: Boolean get() = because.isNotEmpty()
    /** The host, which is what to show when nothing else is known about it. */
    val host: String get() = hostOf(url)
}

/**
 * Every remote picture in a message, said plainly.
 *
 * [sizes] is the width and height the HTML gave each one, by url, because a picture the
 * sender declared as one pixel by one pixel is not a picture. Absent sizes are fine: the
 * host and the shape of the address carry most of it.
 */
internal fun picturesIn(urls: List<String>, sizes: Map<String, Pair<Int?, Int?>> = emptyMap()): List<RemotePicture> =
    urls.distinct().map { url ->
        val named = NAMED.entries.firstOrNull { (domain, _) -> matchesHost(hostOf(url), domain) }
        when {
            named != null -> RemotePicture(url, named.value, "${named.value} tracks opens from this address")
            tiny(sizes[url]) -> RemotePicture(url, "", "it is one pixel across, so there is nothing to see")
            looksLikeABeacon(url) -> RemotePicture(url, "", "the address is an open-tracking one")
            else -> RemotePicture(url, "", "")
        }
    }

/** How many of them are trackers, which is the number the banner shows. */
internal fun trackerCount(pictures: List<RemotePicture>): Int = pictures.count { it.isTracker }

/**
 * One line naming who is asking, for the banner.
 *
 * Names rather than a count where there are names, because "2 trackers" is a statistic and
 * "Mailchimp and HubSpot" is a fact about who is watching. An unnamed one is listed by its
 * host, which is still more than a number.
 */
internal fun trackerLine(pictures: List<RemotePicture>): String {
    val names = pictures.filter { it.isTracker }
        .map { it.tracker.ifBlank { it.host } }
        .distinct()
    return when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}

/** One pixel by one pixel, or zero, which is the oldest tracking pixel there is. */
private fun tiny(size: Pair<Int?, Int?>?): Boolean {
    val (width, height) = size ?: return false
    if (width == null && height == null) return false
    return (width ?: 1) <= 1 && (height ?: 1) <= 1
}

/**
 * An address that is an open-tracking one even though the host is not on the list.
 *
 * Every sending platform builds these the same way: a path that says what it is followed by
 * a long opaque token identifying the recipient. Deliberately requires both, because `/o/`
 * on its own is a directory name somebody could have for any reason, and a warning that
 * fires on an ordinary picture is the warning nobody believes next time.
 */
private fun looksLikeABeacon(url: String): Boolean {
    val lower = url.lowercase()
    val says = BEACON_WORDS.any { lower.contains(it) }
    // Sixteen characters or more of unbroken base16, base64 or a UUID: an identifier for a
    // person, not a filename anybody typed.
    val token = Regex("[a-f0-9]{16,}|[A-Za-z0-9_-]{22,}").containsMatchIn(url.substringAfter("://"))
    return says && token
}

private val BEACON_WORDS = listOf(
    "/open", "open.gif", "open.png", "/o/", "/pixel", "pixel.gif", "pixel.png",
    "/track", "/trk", "/beacon", "beacon.gif", "/t.gif", "/o.gif", "/e/o/", "/wf/open",
    "utm_", "/imp?", "/impression",
)

/**
 * The hosts we can put a name to.
 *
 * A list, not a heuristic, because a name has to be right: telling somebody Mailchimp is
 * watching when it is not is worse than saying nothing. Matched on the registrable tail so
 * a per-customer subdomain still resolves, which is how every one of these is deployed.
 *
 * It is not exhaustive and does not need to be. Anything not on it still gets caught by the
 * shape of the address or by being one pixel across, and is named by its host.
 */
private val NAMED = linkedMapOf(
    "list-manage.com" to "Mailchimp",
    "mailchimp.com" to "Mailchimp",
    "mcusercontent.com" to "Mailchimp",
    "hubspot.com" to "HubSpot",
    "hubspotemail.net" to "HubSpot",
    "hs-analytics.net" to "HubSpot",
    "sendgrid.net" to "SendGrid",
    "sendgrid.com" to "SendGrid",
    "mailgun.org" to "Mailgun",
    "mailgun.net" to "Mailgun",
    "resend.com" to "Resend",
    "klaviyo.com" to "Klaviyo",
    "klaviyomail.com" to "Klaviyo",
    "activehosted.com" to "ActiveCampaign",
    "constantcontact.com" to "Constant Contact",
    "rs6.net" to "Constant Contact",
    "braze.com" to "Braze",
    "braze.eu" to "Braze",
    "iterable.com" to "Iterable",
    "customeriomail.com" to "Customer.io",
    "customer.io" to "Customer.io",
    "exct.net" to "Salesforce Marketing Cloud",
    "exacttarget.com" to "Salesforce Marketing Cloud",
    "mktoresp.com" to "Marketo",
    "marketo.com" to "Marketo",
    "intercom-mail.com" to "Intercom",
    "intercomcdn.com" to "Intercom",
    "mixpanel.com" to "Mixpanel",
    "segment.com" to "Segment",
    "google-analytics.com" to "Google Analytics",
    "googletagmanager.com" to "Google Tag Manager",
    "doubleclick.net" to "Google",
    "facebook.com" to "Facebook",
    "emltrk.com" to "Email Tracker",
    "mailtrack.io" to "Mailtrack",
    "streak.com" to "Streak",
    "yesware.com" to "Yesware",
    "mixmax.com" to "Mixmax",
    "bananatag.com" to "Bananatag",
    "postmarkapp.com" to "Postmark",
    "mandrillapp.com" to "Mandrill",
    "sparkpostmail.com" to "SparkPost",
    "amazonses.com" to "Amazon SES",
    "salesloft.com" to "SalesLoft",
    "outreach.io" to "Outreach",
    "apollo.io" to "Apollo",
    "getresponse.com" to "GetResponse",
    "aweber.com" to "AWeber",
    "convertkit-mail.com" to "ConvertKit",
    "beehiiv.com" to "beehiiv",
    "substack.com" to "Substack",
    "sailthru.com" to "Sailthru",
)

/** The host of a URL, lowercased, without a port. Empty when it has none. */
internal fun hostOf(url: String): String =
    url.substringAfter("://", "").substringBefore('/').substringBefore('?')
        .substringAfterLast('@').substringBefore(':').lowercase()

/**
 * Whether [host] is [domain] or something under it.
 *
 * The suffix has to fall on a dot. Without that, `notmailchimp.com` matches `mailchimp.com`
 * and the name shown is a lie, which is the one thing this must not do.
 */
internal fun matchesHost(host: String, domain: String): Boolean =
    host == domain || host.endsWith(".$domain")
