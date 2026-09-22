package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Where the window was and how big, so reopening puts it back. */
data class SavedWindow(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean)

/**
 * Preferences, kept apart from [Accounts] on purpose: that file is meant to be written by
 * someone else and handed over, and one person's theme and window size have no business
 * travelling with it.
 *
 * Every write reads the file first and changes one key, so saving the window size cannot
 * lose the theme.
 */
object Settings {
    private val store = JsonStore("settings.json")

    private fun read(): JsonObject = store.read()

    private fun write(change: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit) =
        store.write(change)

    /** The chosen theme's key, or null before anyone has chosen one. */
    fun theme(): String? = read()["theme"]?.jsonPrimitive?.contentOrNull

    fun setTheme(key: String) = write { put("theme", JsonPrimitive(key)) }

    /**
     * What the light/dark switch was set to before themes existed, and nothing writes it any
     * more. It is still read so that an existing install that had been switched to dark opens
     * dark rather than snapping back to whatever the operating system says.
     */
    fun dark(): Boolean? = read()["dark"]?.jsonPrimitive?.booleanOrNull

    /** On by default: a mail client that does not tell you about mail is a folder browser. */
    fun notifyOnArrival(): Boolean = read()["notify"]?.jsonPrimitive?.booleanOrNull ?: true

    fun setNotifyOnArrival(value: Boolean) = write { put("notify", JsonPrimitive(value)) }

    /**
     * On by default. Tracking is already a deliberate per-message choice, so somebody who
     * turned it on for a message is choosing to know when it is read. A notification that
     * started off would mean the person who wants this most has to find the switch first.
     */
    fun notifyOnOpen(): Boolean = read()["notifyOpen"]?.jsonPrimitive?.booleanOrNull ?: true

    fun setNotifyOnOpen(value: Boolean) = write { put("notifyOpen", JsonPrimitive(value)) }

    /**
     * Whether closing the window leaves Rampart running in the tray.
     *
     * Off by default, and deliberately. An application that ignores the close button is a
     * thing people have to be told about, and being told about it by noticing it is still
     * running is how it reads as a bug rather than a feature.
     */
    fun closeToTray(): Boolean = read()["closeToTray"]?.jsonPrimitive?.booleanOrNull ?: false

    fun setCloseToTray(value: Boolean) = write { put("closeToTray", JsonPrimitive(value)) }

    /** An order that is no longer in the enum reads as the default rather than crashing. */
    internal fun order(): Order = read()["order"]?.jsonPrimitive?.contentOrNull
        ?.let { name -> Order.entries.firstOrNull { it.name == name } } ?: Order.NEWEST

    internal fun setOrder(value: Order) = write { put("order", JsonPrimitive(value.name)) }

    /**
     * How long an open message waits before it counts as read, in milliseconds.
     *
     * Zero means at once, which is the default and what Rampart has always done. The reason
     * to want anything else is arrow-keying down a list: without a pause, every message you
     * pass through is marked read, which is how a morning's unread mail disappears.
     */
    fun markReadDelay(): Long = read()["markReadDelay"]?.jsonPrimitive?.longOrNull ?: 0L

    fun setMarkReadDelay(value: Long) = write { put("markReadDelay", JsonPrimitive(value)) }

    /**
     * Where Archive puts things: "" the Archive folder itself, "year", or "month".
     *
     * A folder with fifteen years of mail in it is a folder nobody opens. The subfolder is
     * made on the first message of a new year or month and never otherwise.
     */
    fun archiveBy(): String = read()["archiveBy"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setArchiveBy(value: String) = write { put("archiveBy", JsonPrimitive(value)) }

    /**
     * Whether the sign-off goes above the quoted original rather than under all of it.
     *
     * Off by default, which is where every sign-off has been until now and where webmail
     * has it set. See [signed] for what each of the two actually looks like.
     */
    fun signatureAboveQuote(): Boolean = read()["signatureAboveQuote"]?.jsonPrimitive?.booleanOrNull ?: false

    fun setSignatureAboveQuote(value: Boolean) =
        write { put("signatureAboveQuote", JsonPrimitive(value)) }

    /**
     * The colour chosen for each tag, by lowercased keyword.
     *
     * Kept here rather than on the server because there is nowhere on the server to keep
     * it: webmail's colours are in per-user encrypted files only it can open, and the
     * mailbox itself has no place for a client's furniture. A tag with nothing stored uses
     * the colour derived from its name, which is what everyone starts with.
     */
    fun tagColours(): Map<String, Long> =
        (read()["tagColours"] as? JsonObject)?.mapNotNull { (key, value) ->
            value.jsonPrimitive.longOrNull?.let { key to it }
        }?.toMap() ?: emptyMap()

    /** [colour] null puts a tag back to the colour derived from its name. */
    fun setTagColour(keyword: String, colour: Long?) = write {
        val current = (this["tagColours"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val key = keyword.lowercase()
        if (colour == null) current.remove(key) else current[key] = JsonPrimitive(colour)
        put("tagColours", JsonObject(current))
    }

    /**
     * The sidebar sections that are folded away.
     *
     * Kept as the set that is closed rather than the set that is open, so a tag or an
     * account that appears later is open by default. The other way round, every new tag
     * would arrive folded and look like it had not arrived at all.
     */
    fun collapsedSections(): Set<String> =
        (read()["collapsedSections"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()

    fun setCollapsedSections(sections: Set<String>) = write {
        put("collapsedSections", JsonArray(sections.sorted().map(::JsonPrimitive)))
    }

    /**
     * Whether a tagged row in the message list carries its tag's colour.
     *
     * Off by default, which is the one place this deliberately differs from Bulwark. Its
     * list is roomier; Rampart's already tints an unread row, and two tints on one row read
     * as a rendering fault rather than as two facts.
     */
    fun tintRowsByTag(): Boolean = read()["tintRowsByTag"]?.jsonPrimitive?.booleanOrNull == true

    fun setTintRowsByTag(value: Boolean) = write { put("tintRowsByTag", JsonPrimitive(value)) }

    /**
     * Where the companion server is, or empty when there is not one.
     *
     * Empty is the normal case and the whole feature is then visibly unavailable rather
     * than silently missing: a tracking toggle that does nothing is worse than no toggle.
     * The token that goes with it is in the credential store, never here.
     */
    fun trackingServer(): String = read()["trackingServer"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setTrackingServer(value: String) = write { put("trackingServer", JsonPrimitive(value.trim())) }

    /**
     * The recipient domains tracking was last switched on for.
     *
     * By domain rather than by address: deciding to track outreach is a decision about a
     * company, and being asked again for every new contact at the same place is how a
     * per-message switch turns into somebody reaching for a global one.
     */
    fun trackedDomains(): Set<String> =
        (read()["trackedDomains"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()

    fun rememberTracking(domain: String, on: Boolean) = write {
        val now = trackedDomains().toMutableSet()
        if (on) now.add(domain.lowercase()) else now.remove(domain.lowercase())
        put("trackedDomains", JsonArray(now.sorted().map(::JsonPrimitive)))
    }

    /** The moment the companion was last asked what had been fetched. */
    fun trackingCursor(): Long = read()["trackingCursor"]?.jsonPrimitive?.longOrNull ?: 0L

    fun setTrackingCursor(value: Long) = write { put("trackingCursor", JsonPrimitive(value)) }

    /**
     * Where the diagnostics companion is, or empty when there is not one.
     *
     * The same shape as [trackingServer]: empty is the normal case for a stranger who has
     * never run one, and the Diagnostics settings page's reporting half is then visibly
     * unavailable rather than silently missing.
     */
    fun diagnosticsServer(): String = read()["diagnosticsServer"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setDiagnosticsServer(value: String) = write { put("diagnosticsServer", JsonPrimitive(value.trim())) }

    /**
     * Whether the numbers [Diagnostics] gathers locally get aggregated and sent on, to
     * [diagnosticsServer] when it is set or to the Rampart project's own server otherwise:
     * see [Diagnostics.targetFor]. Never the raw events, never a message, never anything
     * that names a folder, a sender or a subject: see `Diagnostics.kt` for what actually
     * goes and why nothing else can.
     *
     * True unless somebody has said otherwise, the same logic open tracking's own setting
     * already settled: the whole reason to build this is to see the data, and every build
     * now has somewhere for it to go. Explicitly written once touched, so turning it off
     * stays off.
     */
    fun diagnosticsReporting(): Boolean =
        read()["diagnosticsReporting"]?.jsonPrimitive?.booleanOrNull ?: true

    fun setDiagnosticsReporting(value: Boolean) = write { put("diagnosticsReporting", JsonPrimitive(value)) }

    /** Which loader is drawn while Rampart waits. Kept apart from the theme, like the icons. */
    fun loader(): String = read()["loader"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setLoader(value: String) = write { put("loader", JsonPrimitive(value)) }

    /** Which icon pack, kept apart from the theme so the two can be chosen separately. */
    fun iconPack(): String = read()["iconPack"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setIconPack(value: String) = write { put("iconPack", JsonPrimitive(value)) }

    /**
     * Whether Send asks before every message, not only when something looks wrong.
     *
     * Off by default. A missing subject or a promised attachment is already asked about,
     * and a second confirmation on every ordinary message is the one people turn off
     * after a week. On, those still come first: one question per send, and the plain
     * "Send this message?" only when nothing else was worth asking.
     */
    fun confirmBeforeSend(): Boolean =
        read()["confirmBeforeSend"]?.jsonPrimitive?.booleanOrNull ?: false

    fun setConfirmBeforeSend(value: Boolean) =
        write { put("confirmBeforeSend", JsonPrimitive(value)) }

    /**
     * Whether a bare Reply addresses everyone.
     *
     * Off by default, which is what Reply has always done: the sender only, with Reply
     * all offered beside it when the message has other people on it. On, Reply itself
     * addresses everyone. The Reply all button is unchanged.
     */
    fun defaultReplyAll(): Boolean =
        read()["defaultReplyAll"]?.jsonPrimitive?.booleanOrNull ?: false

    fun setDefaultReplyAll(value: Boolean) =
        write { put("defaultReplyAll", JsonPrimitive(value)) }

    /**
     * The character that separates a tag from the mailbox, as in `you+invoices@`.
     *
     * Plus is what most systems use, and it is what Rampart assumed before this was a
     * choice. A single character, and never `@`, which is the address itself: anything
     * else is read back as plus, so a mangled setting cannot stop an address matching.
     */
    fun subAddressDelimiter(): Char {
        val raw = read()["subAddressDelimiter"]?.jsonPrimitive?.contentOrNull
        val one = raw?.singleOrNull()
        return if (one != null && one != '@') one else '+'
    }

    fun setSubAddressDelimiter(value: Char) = write {
        put("subAddressDelimiter", JsonPrimitive(if (value == '@') "+" else value.toString()))
    }

    /**
     * Whether only configured identities count as you.
     *
     * Off by default, which is the catch-all: an address on a domain you already send as
     * is treated as yours even when that exact address was never set up. On, an address
     * counts only when it matches an identity. Reply all and the address a reply goes out
     * as both follow this.
     */
    fun exactIdentitiesOnly(): Boolean =
        read()["exactIdentitiesOnly"]?.jsonPrimitive?.booleanOrNull ?: false

    fun setExactIdentitiesOnly(value: Boolean) =
        write { put("exactIdentitiesOnly", JsonPrimitive(value)) }

    /**
     * Where a message's files are listed.
     *
     * "below" is under the body, which is where they have always been. "beside" puts the
     * same list under the sender's name. Anything else is read as "below".
     */
    fun attachmentPosition(): String {
        val raw = read()["attachmentPosition"]?.jsonPrimitive?.contentOrNull
        return if (raw == "beside") "beside" else "below"
    }

    fun setAttachmentPosition(value: String) = write {
        put("attachmentPosition", JsonPrimitive(if (value == "beside") "beside" else "below"))
    }

    /**
     * What a click on a file does.
     *
     * "download" saves it, which is what a click has always done. "preview" opens an image
     * in the window instead, and only an image: every other kind of file still saves,
     * because there is nothing to show for it. Anything else is read as "download".
     */
    fun attachmentClickBehavior(): String {
        val raw = read()["attachmentClickBehavior"]?.jsonPrimitive?.contentOrNull
        return if (raw == "preview") "preview" else "download"
    }

    fun setAttachmentClickBehavior(value: String) = write {
        put("attachmentClickBehavior", JsonPrimitive(if (value == "preview") "preview" else "download"))
    }

    /**
     * How long a sent message waits before it actually goes, in seconds.
     *
     * Five by default, which is long enough to notice the wrong recipient and short enough
     * that nobody waits for it. Zero turns it off for anyone who finds the pause worse than
     * the mistake.
     */
    fun undoSeconds(): Int = read()["undoSeconds"]?.jsonPrimitive?.intOrNull ?: 5

    fun setUndoSeconds(value: Int) = write { put("undoSeconds", JsonPrimitive(value)) }

    /**
     * How long the "archived" strip stays up offering to take it back, in seconds.
     *
     * A different number from [undoSeconds] and deliberately so. That one is how long a
     * message is physically held before it goes, and it costs something: every send waits.
     * This one is only how long an offer stays on screen, and the move it offers to reverse
     * can still be reversed by hand afterwards, so it can be generous.
     *
     * Zero means it stays until dismissed, which is what it did before there was a choice.
     */
    fun undoBarSeconds(): Int = read()["undoBarSeconds"]?.jsonPrimitive?.intOrNull ?: 8

    fun setUndoBarSeconds(value: Int) = write { put("undoBarSeconds", JsonPrimitive(value)) }

    /**
     * How large a message is drawn, as a multiple of the size everything else is drawn at.
     *
     * **There is a correction underneath this and it is the part that matters.** The
     * message is drawn by a separate engine inside the window, and that engine has its own
     * idea of how big a pixel is. Where it disagrees with the rest of the application, a
     * message comes out visibly smaller than the interface around it on exactly the
     * displays where it matters most, which is every scaled one. [WebBody] divides one by
     * the other, so 1.0 here means "the same size as everything else" on any display rather
     * than "whatever the engine felt like".
     *
     * On top of that correction, this is a preference. Reading comfort is personal and
     * every mail client has this control; ours starts at matching and goes either way.
     */
    /**
     * Whether a message is drawn dark, light, or the way the window is.
     *
     * Separate from the theme on purpose. Somebody can want a dark application and mail on
     * paper, or a light application and mail that does not flash white at night, and those
     * are two different preferences that were one setting because the window happened to
     * be the only thing that knew.
     *
     * It decides the page a message with no colours of its own is drawn on. A message that
     * brought a design is still drawn as it was built, in every mode: that is what the
     * switch on the toolbar is for, one message at a time.
     */
    fun messageMode(): String = read()["messageMode"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setMessageMode(value: String) = write { put("messageMode", JsonPrimitive(value)) }

    fun messageScale(): Float = read()["messageScale"]?.jsonPrimitive?.floatOrNull ?: 1.0f

    fun setMessageScale(value: Float) = write { put("messageScale", JsonPrimitive(value)) }

    /**
     * Senders whose pictures may be fetched from the web. Domains, not addresses: see
     * [imageSenderKey].
     */
    fun imageSenders(): Set<String> =
        (read()["imageSenders"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
            ?: emptySet()

    fun allowImagesFrom(key: String) = write {
        val current = (this["imageSenders"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        if (current.none { it.jsonPrimitive.contentOrNull == key }) current.add(JsonPrimitive(key))
        put("imageSenders", JsonArray(current))
    }

    fun sidebarCollapsed(): Boolean = read()["sidebarCollapsed"]?.jsonPrimitive?.booleanOrNull ?: false

    fun setSidebarCollapsed(value: Boolean) =
        write { put("sidebarCollapsed", JsonPrimitive(value)) }

    /**
     * null when nothing is stored, or when what is stored would put the window somewhere
     * nobody can reach it. A monitor that has been unplugged since the last run would
     * otherwise reopen Rampart off the edge of the screen, where it looks like it failed
     * to start.
     */
    fun window(): SavedWindow? {
        val saved = read()["window"]?.jsonObject ?: return null
        fun int(key: String) = saved[key]?.jsonPrimitive?.intOrNull
        val width = int("width") ?: return null
        val height = int("height") ?: return null
        val x = int("x") ?: return null
        val y = int("y") ?: return null
        if (width < 640 || height < 480 || width > 20_000 || height > 20_000) return null
        if (x < -width / 2 || y < -64 || x > 20_000 || y > 20_000) return null
        return SavedWindow(x, y, width, height, saved["maximized"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    fun setWindow(value: SavedWindow) = write {
        put(
            "window",
            buildJsonObject {
                put("x", value.x)
                put("y", value.y)
                put("width", value.width)
                put("height", value.height)
                put("maximized", value.maximized)
            },
        )
    }

    /**
     * The compose panel's size when it is not full screen, dragged from its corner handle.
     * Defaults to today's fixed 620 by 620 so an install that has never touched the handle
     * looks exactly as it did before there was one to drag.
     */
    fun composeWidth(): Float = read()["composeWidth"]?.jsonPrimitive?.floatOrNull ?: 620f

    fun setComposeWidth(value: Float) = write { put("composeWidth", JsonPrimitive(value)) }

    fun composeHeight(): Float = read()["composeHeight"]?.jsonPrimitive?.floatOrNull ?: 620f

    fun setComposeHeight(value: Float) = write { put("composeHeight", JsonPrimitive(value)) }

    /**
     * The version the changelog dialog last showed, or empty before it ever has.
     *
     * Empty is also what an install made before this feature existed carries, and that is
     * deliberate: there is nothing to compare it against, so the first run after upgrading
     * to a build with this feature records the running version silently rather than
     * showing a dialog for however many releases came before anyone could see one.
     */
    fun changelogSeen(): String = read()["changelogSeen"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setChangelogSeen(version: String) = write { put("changelogSeen", JsonPrimitive(version)) }

    /**
     * Whether the changelog dialog has been switched off. Off (shown) by default; once
     * somebody ticks "Don't show this again" this stays true across every future update
     * until they turn it back on themselves.
     */
    fun changelogSuppressed(): Boolean = read()["changelogSuppressed"]?.jsonPrimitive?.booleanOrNull ?: false

    fun setChangelogSuppressed(value: Boolean) = write { put("changelogSuppressed", JsonPrimitive(value)) }

}
