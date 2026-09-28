package org.rampart

import java.util.UUID

/*
 * What a phone needs to point its own calendar and contacts apps at Stalwart, and the Apple
 * configuration profile that can hand that over to an iPhone without a password ever
 * touching disk.
 *
 * There is no server call in this file. Stalwart already serves CalDAV at `/dav/cal/`,
 * CardDAV at `/dav/card/`, and redirects the two well-known paths there, so all that is
 * needed is the account's own host, worked out the same way the sign-in screen already
 * does it. [PhonePage] is the thin part that draws this; everything testable lives here so
 * it can be checked without a server, the same split [AccountSecurity] uses.
 */

/** Where this account's calendars live, as CalDAV, on the server it signed in to. */
internal fun caldavUrl(host: String): String = "https://$host/dav/cal/"

/** Where this account's address books live, as CardDAV, on the same server. */
internal fun carddavUrl(host: String): String = "https://$host/dav/card/"

/** The page a phone's camera should land on when it scans the Android QR code. */
internal const val DAVX5_DOWNLOAD_URL = "https://www.davx5.com/download"

/**
 * Why the phone setup page cannot be used for this account, or null when it can.
 *
 * The same shape as [securityUnavailable], because it is gating the same kind of thing: the
 * CalDAV and CardDAV addresses this page shows are Stalwart's own paths, and the app
 * password button uses Stalwart's own management objects, so an account that is not on
 * Stalwart, over JMAP, with calendars or contacts advertised has nothing here for a phone to
 * sync with.
 */
internal fun phoneSetupUnavailable(
    protocol: String?,
    managementAccountId: String?,
    hasCalendarsOrContacts: Boolean,
): String? = when {
    protocol == null -> "Sign in to an account first, and this page can help you set it up on a phone."
    protocol == "imap" ->
        "This account is signed in over IMAP, which keeps no calendars or contacts on the server, " +
            "so there is nothing here for a phone to sync with."
    !hasCalendarsOrContacts ->
        "This server does not advertise calendars or contacts, so there is nothing here for a phone to sync with."
    managementAccountId == null ->
        "This server is not Stalwart, so Rampart cannot make it a password for this from here; " +
            "use your provider's own settings instead."
    else -> null
}

/**
 * An Apple configuration profile with one CalDAV and one CardDAV account payload, for [host]
 * and [username].
 *
 * No password is written into it. iOS asks for one itself, over its own prompt, the moment
 * the profile is installed, so the only place the app password to type into that prompt is
 * ever written down is the one-time "Copy" button the phone page shows, never this file.
 * Every id below is a fresh random UUID, because a profile a phone has already installed is
 * only replaced by one carrying the same [PayloadUUID], and two different accounts must
 * never collide on that by chance.
 */
internal fun mobileConfig(host: String, username: String, accountLabel: String = "Rampart"): String {
    val topUuid = UUID.randomUUID().toString()
    val calUuid = UUID.randomUUID().toString()
    val cardUuid = UUID.randomUUID().toString()
    val label = xmlEscape(accountLabel)
    val safeHost = xmlEscape(host)
    val safeUser = xmlEscape(username)
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
        <key>PayloadContent</key>
        <array>
        <dict>
        <key>CalDAVAccountDescription</key>
        <string>$label calendar</string>
        <key>CalDAVHostName</key>
        <string>$safeHost</string>
        <key>CalDAVPort</key>
        <integer>443</integer>
        <key>CalDAVUseSSL</key>
        <true/>
        <key>CalDAVUsername</key>
        <string>$safeUser</string>
        <key>PayloadDescription</key>
        <string>Calendar sync with this mailbox</string>
        <key>PayloadDisplayName</key>
        <string>$label calendar</string>
        <key>PayloadIdentifier</key>
        <string>org.rampart.phone.caldav.$calUuid</string>
        <key>PayloadType</key>
        <string>com.apple.caldav.account</string>
        <key>PayloadUUID</key>
        <string>$calUuid</string>
        <key>PayloadVersion</key>
        <integer>1</integer>
        </dict>
        <dict>
        <key>CardDAVAccountDescription</key>
        <string>$label contacts</string>
        <key>CardDAVHostName</key>
        <string>$safeHost</string>
        <key>CardDAVPort</key>
        <integer>443</integer>
        <key>CardDAVUseSSL</key>
        <true/>
        <key>CardDAVUsername</key>
        <string>$safeUser</string>
        <key>PayloadDescription</key>
        <string>Contacts sync with this mailbox</string>
        <key>PayloadDisplayName</key>
        <string>$label contacts</string>
        <key>PayloadIdentifier</key>
        <string>org.rampart.phone.carddav.$cardUuid</string>
        <key>PayloadType</key>
        <string>com.apple.carddav.account</string>
        <key>PayloadUUID</key>
        <string>$cardUuid</string>
        <key>PayloadVersion</key>
        <integer>1</integer>
        </dict>
        </array>
        <key>PayloadDescription</key>
        <string>Adds this mailbox's calendar and contacts to your phone.</string>
        <key>PayloadDisplayName</key>
        <string>$label phone setup</string>
        <key>PayloadIdentifier</key>
        <string>org.rampart.phone.$topUuid</string>
        <key>PayloadRemovalDisallowed</key>
        <false/>
        <key>PayloadType</key>
        <string>Configuration</string>
        <key>PayloadUUID</key>
        <string>$topUuid</string>
        <key>PayloadVersion</key>
        <integer>1</integer>
        </dict>
        </plist>

    """.trimIndent()
}

/** Escapes the five characters XML gives meaning to, so a stray one in a name cannot break the plist. */
private fun xmlEscape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
