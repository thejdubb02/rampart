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

/** Missing or not a boolean means on: a new install keeps the motion it has always had. */
internal fun animationsOn(stored: Boolean?): Boolean = stored ?: true

/**
 * Missing means on, which is one order for every folder, as the list has always worked.
 * Anything that is not a boolean is treated the same as missing.
 */
internal fun orderAppliesEverywhere(stored: Boolean?): Boolean = stored ?: true

/**
 * Preferences, kept apart from [Accounts] on purpose: that file is meant to be written by
 * someone else and handed over, and one person's theme and window size have no business
 * travelling with it.
 *
 * Every write reads the file first and changes one key, so saving the window size cannot
 * lose the theme.
 */
object Settings {
    /** Internal so settings sync (SettingsSync.kt) merges under the same lock every write takes. */
    internal val store = JsonStore("settings.json")

    private fun read(): JsonObject = store.read()

    /** Stamps whatever a synced key the change touched, so it can win or lose on its own. */
    private fun write(change: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit) =
        store.write {
            val before = toMap()
            change()
            if (stampChanges(SYNCED_SETTINGS, before, this, System.currentTimeMillis())) SettingsSync.localChanged()
        }

    /** The chosen theme's key, or null before anyone has chosen one. */
    fun theme(): String? = read()["theme"]?.jsonPrimitive?.contentOrNull

    fun setTheme(key: String) = write { put("theme", JsonPrimitive(key)) }

    /**
     * Custom themes stay in the local settings file beside the chosen theme. JMAP and
     * Stalwart expose no per-user store for arbitrary client appearance preferences, so
     * there is no server-side home that another mail client could safely share.
     */
    fun customThemes(): List<Theme> = (read()["customThemes"] as? JsonArray)?.mapNotNull { value ->
        runCatching { ThemeJson.decode(value.toString()) }.getOrNull()
    } ?: emptyList()

    fun setCustomThemes(themes: List<Theme>) = write {
        put("customThemes", JsonArray(themes.map(ThemeJson::objectOf)))
    }

    /**
     * Saved search queries stored locally.
     *
     * Kept in the local settings file alongside other preferences.
     */
    internal fun savedSearches(): List<SavedSearch> = (read()["savedSearches"] as? JsonArray)?.mapNotNull { value ->
        runCatching { SavedSearchJson.decode(value) }.getOrNull()
    } ?: emptyList()

    internal fun setSavedSearches(searches: List<SavedSearch>) = write {
        put("savedSearches", JsonArray(searches.map(SavedSearchJson::encode)))
    }

    /**
     * How tightly packed the message list is: "compact", "extra-compact", "normal", or "spacious".
     */
    fun density(): String? = read()["density"]?.jsonPrimitive?.contentOrNull

    fun setDensity(key: String) = write { put("density", JsonPrimitive(key)) }

    /** The message list layout beside density: "normal", "table" or "cards". See [ListLayout]. */
    fun listLayout(): String? = read()["listLayout"]?.jsonPrimitive?.contentOrNull

    fun setListLayout(key: String) = write { put("listLayout", JsonPrimitive(key)) }

    /** The table view's dragged column widths, as [ColumnWidths.encoded] writes them. */
    fun tableColumns(): String? = read()["tableColumns"]?.jsonPrimitive?.contentOrNull

    fun setTableColumns(value: String) = write { put("tableColumns", JsonPrimitive(value)) }

    /** The table view's sort, as [TableSort.encoded] writes it. */
    fun tableSort(): String? = read()["tableSort"]?.jsonPrimitive?.contentOrNull

    fun setTableSort(value: String) = write { put("tableSort", JsonPrimitive(value)) }

    /**
     * Light, dark, or follow the computer.
     *
     * Null means System: nothing was chosen, so the window follows the operating system and
     * changes when it does. True is dark, false is light. A named theme still wins until
     * System is chosen on purpose, which clears both this and the theme.
     */
    fun dark(): Boolean? = read()["dark"]?.jsonPrimitive?.booleanOrNull

    /**
     * The named theme and the light/dark choice, written together.
     *
     * A null theme or a null dark value removes that key. System is both removed: nothing
     * stored, so the window follows the computer. Writing them separately could leave a
     * theme without a choice, or a choice without the theme it belongs to.
     */
    fun setAppearance(themeKey: String?, dark: Boolean?) = write {
        if (themeKey == null) remove("theme") else put("theme", JsonPrimitive(themeKey))
        if (dark == null) remove("dark") else put("dark", JsonPrimitive(dark))
    }

    /** On by default: a mail client that does not tell you about mail is a folder browser. */
    fun notifyOnArrival(): Boolean = read()["notify"]?.jsonPrimitive?.booleanOrNull ?: true

    fun setNotifyOnArrival(value: Boolean) = write { put("notify", JsonPrimitive(value)) }

    /**
     * Accounts whose new mail raises no notification, by account key.
     *
     * Kept on this computer rather than synced: the key names an account as this machine
     * signed in to it, and "the work account is quiet on my home computer" is a choice about
     * one computer anyway.
     */
    fun silencedAccounts(): Set<String> =
        (read()["notifySilenced"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()

    fun setAccountNotifies(key: String, on: Boolean) = write {
        val now = (this["notifySilenced"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()
        put("notifySilenced", JsonArray((if (on) now - key else now + key).sorted().map { JsonPrimitive(it) }))
    }

    /**
     * Quiet hours as "22:00-07:00", or null when off. See [QuietHours].
     *
     * Synced, unlike the per-account switches: a person's night is the same night on every
     * computer they own, and setting it twice is the kind of chore that gets skipped.
     */
    fun quietHours(): String? = read()["quietHours"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    fun setQuietHours(value: String?) = write { put("quietHours", JsonPrimitive(value.orEmpty())) }

    /**
     * On by default. Tracking is already a deliberate per-message choice, so somebody who
     * turned it on for a message is choosing to know when it is read. A notification that
     * started off would mean the person who wants this most has to find the switch first.
     */
    fun notifyOnOpen(): Boolean = read()["notifyOpen"]?.jsonPrimitive?.booleanOrNull ?: true

    fun setNotifyOnOpen(value: Boolean) = write { put("notifyOpen", JsonPrimitive(value)) }

    fun phoneAlertProvider(): String = read()["phoneAlertProvider"]?.jsonPrimitive?.contentOrNull?.ifBlank { "ntfy" } ?: "ntfy"

    fun setPhoneAlertProvider(value: String) = write { put("phoneAlertProvider", JsonPrimitive(value.trim())) }

    fun gotifyServer(): String = read()["gotifyServer"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setGotifyServer(value: String) = write { put("gotifyServer", JsonPrimitive(value.trim())) }

    fun ntfyServer(): String = read()["ntfyServer"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setNtfyServer(value: String) = write { put("ntfyServer", JsonPrimitive(value.trim())) }

    fun ntfyImportantMail(): Boolean = read()["ntfyImportantMail"]?.jsonPrimitive?.booleanOrNull ?: true
    fun setNtfyImportantMail(value: Boolean) = write { put("ntfyImportantMail", JsonPrimitive(value)) }
    fun ntfyScheduledSend(): Boolean = read()["ntfyScheduledSend"]?.jsonPrimitive?.booleanOrNull ?: true
    fun setNtfyScheduledSend(value: Boolean) = write { put("ntfyScheduledSend", JsonPrimitive(value)) }
    fun ntfyBounce(): Boolean = read()["ntfyBounce"]?.jsonPrimitive?.booleanOrNull ?: true
    fun setNtfyBounce(value: Boolean) = write { put("ntfyBounce", JsonPrimitive(value)) }
    fun ntfyTrackedOpen(): Boolean = read()["ntfyTrackedOpen"]?.jsonPrimitive?.booleanOrNull ?: true
    fun setNtfyTrackedOpen(value: Boolean) = write { put("ntfyTrackedOpen", JsonPrimitive(value)) }
    fun ntfyOpenLabels(): Boolean = read()["ntfyOpenLabels"]?.jsonPrimitive?.booleanOrNull ?: true
    fun setNtfyOpenLabels(value: Boolean) = write { put("ntfyOpenLabels", JsonPrimitive(value)) }

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
     * Whether the chosen order applies to every folder.
     *
     * On by default, which is how the list has always worked. Off, the choice is for the
     * Inbox only and every other folder stays newest first. A missing value is on, so an
     * older settings file does not suddenly sort Sent a different way.
     */
    fun orderAppliesToAll(): Boolean = orderAppliesEverywhere(read()["orderAppliesToAll"]?.jsonPrimitive?.booleanOrNull)

    fun setOrderAppliesToAll(value: Boolean) = write { put("orderAppliesToAll", JsonPrimitive(value)) }

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
     * Which language the interface will use once translations exist.
     *
     * Translations are RAM-49, and this selector is where they will appear. Today the
     * interface is English only. "auto" follows the computer, which already decides the
     * month names, day names and number formats of the dates below. Anything that is not
     * "en" is read as "auto".
     */
    fun language(): String = languageToken(text("language"))

    fun setLanguage(value: String) = write { put("language", JsonPrimitive(value)) }.also { Regional.invalidate() }

    /** "smart" or "full". Anything else is smart, which is how the message list already reads. */
    fun listDate(): String = listDateToken(text("listDate"))

    fun setListDate(value: String) = write { put("listDate", JsonPrimitive(value)) }.also { Regional.invalidate() }

    /** "auto", "mdy", "dmy" or "ymd". Anything else is automatic, which follows the language. */
    fun dateOrder(): String = dateOrderToken(text("dateOrder"))

    fun setDateOrder(value: String) = write { put("dateOrder", JsonPrimitive(value)) }.also { Regional.invalidate() }

    /** "auto", "12" or "24". Anything else is automatic, which follows the language. */
    fun timeFormat(): String = timeFormatToken(text("timeFormat"))

    fun setTimeFormat(value: String) = write { put("timeFormat", JsonPrimitive(value)) }.also { Regional.invalidate() }

    /**
     * "auto", or a zone id. An id this Java does not know is read as automatic, and the
     * id is not lowercased: zone ids are case sensitive.
     */
    fun timeZone(): String = timeZoneToken(text("timeZone"))

    fun setTimeZone(value: String) = write { put("timeZone", JsonPrimitive(value)) }.also { Regional.invalidate() }

    /** "auto", "sunday", "monday" or "saturday". Anything else is automatic. */
    fun weekStart(): String = weekStartToken(text("weekStart"))

    fun setWeekStart(value: String) = write { put("weekStart", JsonPrimitive(value)) }.also { Regional.invalidate() }

    /** The six regional choices in one read, still raw, so an unknown value can fall back. */
    internal fun regionalRaw(): RegionalRaw {
        val saved = read()
        fun value(key: String) = saved[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        return RegionalRaw(
            value("language"), value("listDate"), value("dateOrder"),
            value("timeFormat"), value("timeZone"), value("weekStart"),
        )
    }

    private fun text(key: String): String? = read()[key]?.jsonPrimitive?.contentOrNull

    /**
     * Whether the sign-off goes above the quoted original rather than under all of it.
     *
     * On by default, which is where Gmail and Outlook put it: a reply's sign-off sits under
     * the reply, not under the whole quoted thread. Anyone who chose a placement keeps it.
     * See [signed] for what each of the two actually looks like.
     */
    fun signatureAboveQuote(): Boolean = read()["signatureAboveQuote"]?.jsonPrimitive?.booleanOrNull ?: true

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

    /** Shared mailboxes in the cross-account views, and what feeds each view. The keys are in UnifiedViews.kt. */
    internal fun sharingPrefs(): Map<String, String> =
        (read()["sharing"] as? JsonObject)?.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }?.toMap() ?: emptyMap()

    /** [change] is a key and its new value, or null to forget it, as UnifiedViews.kt builds them. */
    internal fun setSharingPref(change: Pair<String, String?>) = write {
        val current = (this["sharing"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val value = change.second
        if (value == null) current.remove(change.first) else current[change.first] = JsonPrimitive(value)
        put("sharing", JsonObject(current))
    }

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

    /** Whether a row in the merged inbox carries its account's colour. Off by default. See [accountTints]. */
    fun tintRowsByAccount(): Boolean = read()["tintRowsByAccount"]?.jsonPrimitive?.booleanOrNull == true

    fun setTintRowsByAccount(value: Boolean) = write { put("tintRowsByAccount", JsonPrimitive(value)) }

    /**
     * The colour chosen for each account, by account key. An account with nothing stored
     * uses the colour worked out from its key. Kept on this computer only, see [SYNCED_SETTINGS].
     */
    fun accountColours(): Map<String, Long> =
        (read()["accountColours"] as? JsonObject)?.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.longOrNull?.let { key to it }
        }?.toMap() ?: emptyMap()

    /** [colour] null puts an account back to the colour worked out from its key. */
    fun setAccountColour(key: String, colour: Long?) = write {
        val current = (this["accountColours"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (colour == null) current.remove(key) else current[key] = JsonPrimitive(colour)
        put("accountColours", JsonObject(current))
    }

    /** The buttons a row shows under the pointer, as [HoverAction] keys. Null when never chosen. */
    fun hoverActions(): List<String>? =
        (read()["hoverActions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    fun setHoverActions(keys: List<String>) = write { put("hoverActions", JsonArray(keys.map(::JsonPrimitive))) }

    /**
     * Where the companion server is, or empty when there is not one.
     *
     * Empty is the normal case and the whole feature is then visibly unavailable rather
     * than silently missing: a tracking toggle that does nothing is worse than no toggle.
     * The token that goes with it is in the credential store, never here.
     */
    fun trackingServer(): String = read()["trackingServer"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setTrackingServer(value: String) = write { put("trackingServer", JsonPrimitive(value.trim())) }

    /** The all-account default for tracking newly composed messages. */
    fun trackNewMail(): Boolean = read()["trackNewMail"]?.jsonPrimitive?.booleanOrNull ?: false

    fun setTrackNewMail(value: Boolean) = write { put("trackNewMail", JsonPrimitive(value)) }

    /** An account override, with the master default used until one is chosen. */
    fun trackNewMail(account: String): Boolean =
        (read()["trackNewMailAccounts"] as? JsonObject)?.get(account)?.jsonPrimitive?.booleanOrNull ?: trackNewMail()

    fun setTrackNewMail(account: String, value: Boolean) = write {
        val current = (read()["trackNewMailAccounts"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        current[account] = JsonPrimitive(value)
        put("trackNewMailAccounts", JsonObject(current))
    }

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
     * Whether sidebar folder icons take a colour per role: "colour" or "plain".
     *
     * Nothing stored means colour, so a new install is coloured. Plain is every
     * folder icon in the one ordinary colour. Kept apart from [iconPack], which
     * only chooses the shapes.
     */
    fun sidebarIcons(): String? = read()["sidebarIcons"]?.jsonPrimitive?.contentOrNull

    fun setSidebarIcons(value: String) = write { put("sidebarIcons", JsonPrimitive(value)) }

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
     * Whether the composer marks misspellings while typing.
     *
     * On unless somebody turns it off. LanguageTool has no home on the mail server, so
     * the check runs on this computer and nothing is sent. Grammar is part of the same
     * check, so turning this off leaves the draft unmarked.
     */
    fun checkSpelling(): Boolean = read()["checkSpelling"]?.jsonPrimitive?.booleanOrNull ?: true

    fun setCheckSpelling(value: Boolean) = write { put("checkSpelling", JsonPrimitive(value)) }

    /**
     * Whether grammar, punctuation and confused words are marked as well as spelling.
     *
     * On by default. Spelling is the switch that turns the whole check off.
     */
    fun checkGrammar(): Boolean = read()["checkGrammar"]?.jsonPrimitive?.booleanOrNull ?: true

    fun setCheckGrammar(value: Boolean) = write { put("checkGrammar", JsonPrimitive(value)) }

    /**
     * Words and phrases that are not mistakes for this person.
     *
     * Stored in one order, ignoring capitals, so two computers do not keep rewriting the
     * list just because the words were added in a different order. A phrase is one entry,
     * the way it was added, not the separate words inside it.
     */
    fun personalDictionary(): List<String> = canonicalDictionary(
        (read()["personalDictionary"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
    )

    fun setPersonalDictionary(words: List<String>) = write {
        put("personalDictionary", JsonArray(canonicalDictionary(words).map(::JsonPrimitive)))
    }

    fun addToDictionary(word: String) = setPersonalDictionary(personalDictionary() + word)

    fun removeFromDictionary(word: String) = setPersonalDictionary(
        personalDictionary().filterNot { it.equals(word, ignoreCase = true) },
    )

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
     * On by default, which is what Rampart has always done: an address counts only when
     * it matches an identity you actually set up. Off, mail to any address sharing a
     * domain with one of your identities is treated as yours too, tag or no tag. That is
     * a real behaviour change for anyone else on that domain, not just an alias of your
     * own, so it stays opt-in rather than becoming what every existing install wakes up
     * to. Reply all and the address a reply goes out as both follow this.
     */
    fun exactIdentitiesOnly(): Boolean =
        read()["exactIdentitiesOnly"]?.jsonPrimitive?.booleanOrNull ?: true

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
     * "preview", the default, opens an image
     * or a PDF in the window instead: every other kind of file still saves,
     * because there is nothing to show for it. "download" saves every file, as a click used to.
     */
    fun attachmentClickBehavior(): String {
        val raw = read()["attachmentClickBehavior"]?.jsonPrimitive?.contentOrNull
        return if (raw == "download") "download" else "preview"
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
     * How large the whole window is: "small", "medium", or "large".
     *
     * Separate from [messageScale], which only scales the message. Missing or unknown reads
     * as medium, the size the window already had.
     */
    fun fontSize(): String? = text("fontSize")

    fun setFontSize(key: String) = write { put("fontSize", JsonPrimitive(UiFont.of(key).key)) }

    /**
     * Whether panels, menus and the list animate.
     *
     * On by default. A missing or non-boolean value is on, so an older file keeps the
     * motion it has always had.
     */
    fun animations(): Boolean = animationsOn(read()["animations"]?.jsonPrimitive?.booleanOrNull)

    fun setAnimations(value: Boolean) = write { put("animations", JsonPrimitive(value)) }

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
     * How wide each of the app bar's panels was last dragged, keyed by [SideTool.key].
     *
     * Per tool, because the calendar's agenda and Rook's conversation want different widths
     * and dragging one should not move the other. A tool missing here opens at its default.
     */
    internal fun sidePanelWidths(): Map<String, Float> =
        (read()["sidePanelWidths"] as? JsonObject)?.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.floatOrNull?.let { key to it }
        }?.toMap() ?: emptyMap()

    internal fun setSidePanelWidths(value: Map<String, Float>) = write {
        put("sidePanelWidths", buildJsonObject { value.forEach { (key, width) -> put(key, width) } })
    }

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

    /** The millisecond timestamp when the app was last started. */
    fun lastStart(): Long = read()["lastStart"]?.jsonPrimitive?.longOrNull ?: 0L

    fun setLastStart(time: Long) = write { put("lastStart", JsonPrimitive(time)) }
}
