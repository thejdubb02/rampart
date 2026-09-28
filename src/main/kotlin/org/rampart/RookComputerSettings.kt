package org.rampart

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * Rampart's own preferences, as the settings map sees them.
 *
 * Every one is read and written through the accessor in `Settings.kt` that the Settings
 * pages already use, so a value Rook writes is normalised exactly as one picked on the page
 * would be, and there is still only one place that knows how the file is laid out.
 */

/**
 * The lists of choices that live beside the drawing code (themes, icon packs, loaders), and
 * the few readings that belong to other files. Handed in rather than reached for, so this
 * file needs nothing from the window and can be tested on its own.
 */
internal data class ComputerChoices(
    val themes: List<SettingOption>,
    /** Keys of the themes drawn dark, so a question about dark mode can be answered from the map. */
    val darkThemes: Set<String>,
    val iconPacks: List<SettingOption>,
    val loaders: List<SettingOption>,
    /** The model, where it is reached and the monthly ceiling, in words. */
    val assistant: () -> String = { "" },
    /** The accounts signed in on this computer, by address. */
    val accounts: () -> List<String> = { emptyList() },
    /** Whether an admin sign-in is saved. Never the server or user, which are not the model's business. */
    val adminSaved: () -> Boolean = { false },
)

/** How one preference is read and written. [write] null means Rook may read it and not change it. */
private class Local(
    val entry: SettingEntry,
    val read: () -> JsonElement,
    val write: ((JsonElement) -> Unit)?,
    val refusal: String? = null,
    val problem: ((JsonElement) -> String?)? = null,
)

private const val WHERE_DATA_GOES =
    "Rook does not change where Rampart sends anything, so a message cannot talk it into " +
        "pointing your data somewhere else. Change it yourself in Settings."

internal class ComputerPlace(private val choices: ComputerChoices) : SettingPlace {
    private val locals: List<Local> = buildLocals()
    private val byId = locals.associateBy { it.entry.id }

    override fun entries(): List<SettingEntry> = locals.map { it.entry }

    override fun refusal(entry: SettingEntry): String? = byId[entry.id]?.let { it.refusal ?: if (it.write == null) WHERE_DATA_GOES else null }

    override fun current(entry: SettingEntry): JsonElement =
        byId[entry.id]?.read?.invoke() ?: throw SettingTrouble("Rampart has no setting called ${entry.id}.")

    override fun cheap(entry: SettingEntry): Boolean = true

    override fun group(entry: SettingEntry): String = "computer"

    override fun title(group: String): String = "Rampart on this computer"

    override fun problem(entry: SettingEntry, value: JsonElement): String? = byId[entry.id]?.problem?.invoke(value)

    override fun more(entry: SettingEntry): String? =
        if (entry.id == "rampart.theme") "Dark themes: " + choices.themes.filter { it.value in choices.darkThemes }.joinToString(", ") { it.label } + "." else null

    /**
     * Writes each one through its accessor, then reads it back. A settings file that could
     * not be written loses the change without a word from the store, and a card that says
     * Done over a change that did not happen is the one outcome this must not have.
     */
    override fun apply(changes: List<Pair<SettingEntry, JsonElement>>): String? {
        for ((entry, value) in changes) {
            val local = byId[entry.id] ?: return "Rampart has no setting called ${entry.id}."
            val write = local.write ?: return refusal(entry)
            runCatching { write(value) }.onFailure { return "Rampart could not save ${entry.name}: ${it.message ?: "the settings file would not take it"}." }
            if (!sameSetting(local.read(), value)) return "Rampart could not save ${entry.name} to its settings file."
        }
        return null
    }

    private fun buildLocals(): List<Local> {
        fun entry(id: String, name: String, description: String, kind: SettingKind, page: String, keywords: String = "") =
            SettingEntry("rampart.$id", name, description, kind, SettingHome.COMPUTER, "Settings, $page", keywords)
        fun onOff(id: String, name: String, description: String, page: String, keywords: String, read: () -> Boolean, write: (Boolean) -> Unit) =
            Local(entry(id, name, description, SettingKind.OnOff, page, keywords), { JsonPrimitive(read()) }, { v -> write(v.bool()) })
        fun oneOf(id: String, name: String, description: String, page: String, keywords: String, options: List<SettingOption>, read: () -> String, write: (String) -> Unit) =
            Local(entry(id, name, description, SettingKind.OneOf(options), page, keywords), { JsonPrimitive(read()) }, { v -> write(v.text()) })
        fun opts(vararg pairs: Pair<String, String>) = pairs.map { SettingOption(it.first, it.second) }

        return listOf(
            Local(
                entry(
                    "theme", "Theme",
                    "The colours of the whole window. Some are light and some are dark; there is no separate dark mode switch, a dark theme is the dark mode.",
                    SettingKind.OneOf(choices.themes), "Themes",
                    "dark mode light mode night appearance colours colors palette look",
                ),
                { Settings.theme()?.let { JsonPrimitive(it) } ?: JsonNull },
                { v -> Settings.setTheme(v.text()) },
            ),
            oneOf(
                "icons", "Icons", "The icon pack, kept apart from the theme so either can change without the other.",
                "Themes", "icon pack glyphs appearance", choices.iconPacks,
                { Settings.iconPack().ifBlank { choices.iconPacks.firstOrNull()?.value.orEmpty() } }, Settings::setIconPack,
            ),
            oneOf(
                "loader", "Loader", "What is drawn while Rampart waits for something.",
                "Themes", "spinner loading animation appearance", choices.loaders,
                {
                    val saved = Settings.loader()
                    choices.loaders.firstOrNull { it.value.equals(saved, ignoreCase = true) }?.value
                        ?: choices.loaders.firstOrNull()?.value.orEmpty()
                },
                Settings::setLoader,
            ),
            onOff(
                "tintRowsByTag", "Tint rows by tag", "Whether a tagged row in the message list carries its tag's colour.",
                "Themes", "tag colour colour rows list appearance", Settings::tintRowsByTag, Settings::setTintRowsByTag,
            ),
            onOff(
                "notifyOnArrival", "Notify when mail arrives", "A desktop notification for new mail while Rampart is open.",
                "Notifications", "notification alert new mail desktop popup", Settings::notifyOnArrival, Settings::setNotifyOnArrival,
            ),
            onOff(
                "notifyOnOpen", "Notify when a tracked message is opened", "A desktop notification when somebody opens a message you sent with tracking on.",
                "Notifications", "notification tracking opened read", Settings::notifyOnOpen, Settings::setNotifyOnOpen,
            ),
            onOff(
                "closeToTray", "Close to the tray", "Whether closing the window leaves Rampart running in the system tray.",
                "Notifications", "tray minimise minimize background close window", Settings::closeToTray, Settings::setCloseToTray,
            ),
            oneOf(
                "order", "Sort order", "The order of the message list.",
                "Reading and archiving", "sort order list newest oldest", Order.entries.map { SettingOption(it.name, it.label) },
                { Settings.order().name }, { v -> Order.entries.firstOrNull { it.name == v }?.let(Settings::setOrder) },
            ),
            Local(
                entry(
                    "markReadDelay", "Mark as read", "How long an open message waits before it counts as read.",
                    SettingKind.OneOf(opts("0" to "At once", "2000" to "After 2 seconds", "5000" to "After 5 seconds", "-1" to "Never, unless I say so")),
                    "Reading and archiving", "read unread seen delay",
                ),
                { JsonPrimitive(Settings.markReadDelay()) },
                { v -> Settings.setMarkReadDelay(v.text().toLong()) },
            ),
            oneOf(
                "messageMode", "The page a message is drawn on",
                "Whether a message with no colours of its own is drawn light, dark, or the way the window is. A message with its own design is always drawn as it was built.",
                "Reading and archiving", "dark mode message reading page background paper",
                opts("" to "The same as the window", "light" to "Always on paper", "dark" to "Always dark"),
                Settings::messageMode, Settings::setMessageMode,
            ),
            Local(
                entry(
                    "messageScale", "Message text size", "How large a message is drawn, next to the rest of the window.",
                    SettingKind.OneOf(opts("0.9" to "Smaller", "1.0" to "Normal", "1.15" to "Larger", "1.3" to "Largest")),
                    "Reading and archiving", "font size zoom bigger smaller text scale",
                ),
                { JsonPrimitive(Settings.messageScale()) },
                { v -> Settings.setMessageScale(v.text().toFloat()) },
            ),
            Local(
                entry(
                    "undoSeconds", "Taking a send back", "How long a sent message waits before it actually goes, so a wrong recipient can be caught.",
                    SettingKind.OneOf(opts("0" to "Send straight away", "5" to "Wait 5 seconds", "10" to "Wait 10 seconds", "30" to "Wait 30 seconds")),
                    "Reading and archiving", "undo send delay seconds",
                ),
                { JsonPrimitive(Settings.undoSeconds()) },
                { v -> Settings.setUndoSeconds(v.text().toInt()) },
            ),
            Local(
                entry(
                    "undoBarSeconds", "How long the undo strip stays", "How long the offer to undo an archive or a move stays on screen.",
                    SettingKind.OneOf(opts("5" to "5 seconds", "8" to "8 seconds", "15" to "15 seconds", "0" to "Until I dismiss it")),
                    "Reading and archiving", "undo bar strip archive move",
                ),
                { JsonPrimitive(Settings.undoBarSeconds()) },
                { v -> Settings.setUndoBarSeconds(v.text().toInt()) },
            ),
            onOff(
                "signatureAboveQuote", "Sign-off above the quoted message", "On a reply or a forward, whether the signature goes under what you wrote rather than under the whole quote.",
                "Reading and archiving", "signature sign-off reply quote placement", Settings::signatureAboveQuote, Settings::setSignatureAboveQuote,
            ),
            onOff(
                "confirmBeforeSend", "Always confirm before sending", "Ask once before every message goes.",
                "Reading and archiving", "send confirm ask", Settings::confirmBeforeSend, Settings::setConfirmBeforeSend,
            ),
            onOff(
                "defaultReplyAll", "Default to Reply all", "Whether a bare Reply addresses everyone on the message.",
                "Reading and archiving", "reply all everyone", Settings::defaultReplyAll, Settings::setDefaultReplyAll,
            ),
            onOff(
                "exactIdentitiesOnly", "Only use configured identities", "Off, mail to any address on a domain you send as is treated as yours. On, only the addresses you set up count.",
                "Reading and archiving", "identity address domain alias", Settings::exactIdentitiesOnly, Settings::setExactIdentitiesOnly,
            ),
            Local(
                entry(
                    "subAddressDelimiter", "Sub-address character", "The character before a tag in an address, as in you+invoices@. Some systems use a hyphen.",
                    SettingKind.Words(1), "Reading and archiving", "plus address tag delimiter subaddress",
                ),
                { JsonPrimitive(Settings.subAddressDelimiter().toString()) },
                { v -> Settings.setSubAddressDelimiter(v.text().single()) },
                problem = { v ->
                    val c = v.text().singleOrNull()
                    if (c == null || c == '@' || c.isWhitespace()) "This is one character, and not @ or a space." else null
                },
            ),
            oneOf(
                "attachmentPosition", "Where files are listed", "Where a message's attachments are shown.",
                "Reading and archiving", "attachment files position", opts("below" to "Under the message", "beside" to "Next to the sender"),
                Settings::attachmentPosition, Settings::setAttachmentPosition,
            ),
            oneOf(
                "attachmentClick", "Clicking a file", "What a click on an attachment does.",
                "Reading and archiving", "attachment download preview open", opts("download" to "Save it", "preview" to "Preview images, save everything else"),
                Settings::attachmentClickBehavior, Settings::setAttachmentClickBehavior,
            ),
            oneOf(
                "archiveBy", "Archiving", "Where Archive puts things: straight into Archive, or into a folder per year or per month.",
                "Reading and archiving", "archive folder year month", opts("" to "Straight into Archive", "year" to "Into Archive, by year", "month" to "Into Archive, by month"),
                Settings::archiveBy, Settings::setArchiveBy,
            ),
            Local(
                entry(
                    "trackingServer", "Open tracking companion", "Where the optional companion server for open tracking is, or empty when there is none.",
                    SettingKind.Words(300), "Open tracking", "tracking companion server pixel",
                ),
                { JsonPrimitive(Settings.trackingServer()) }, null,
            ),
            onOff(
                "diagnosticsReporting", "Send diagnostics", "Whether the aggregate timing numbers Rampart gathers are sent on. Never a message, a folder name, a sender or a subject.",
                "Diagnostics", "diagnostics telemetry reporting privacy", Settings::diagnosticsReporting, Settings::setDiagnosticsReporting,
            ),
            Local(
                entry(
                    "diagnosticsServer", "Diagnostics companion", "Where diagnostics go when you run your own companion, or empty for the Rampart project's own.",
                    SettingKind.Words(300), "Diagnostics", "diagnostics companion server",
                ),
                { JsonPrimitive(Settings.diagnosticsServer()) }, null,
            ),
            onOff(
                "changelogSuppressed", "Hide what changed after an update", "Whether the list of changes stays hidden after Rampart updates itself.",
                "About", "changelog update release notes", Settings::changelogSuppressed, Settings::setChangelogSuppressed,
            ),
            Local(
                entry(
                    "assistant", "Rook's model and budget", "Which model Rook uses, where it is reached, what it may spend in a month and which folders it may never read.",
                    SettingKind.Summary, "Rook", "assistant model provider key budget ceiling ai",
                ),
                { JsonPrimitive(choices.assistant()) }, null,
                refusal = "Rook does not change its own model, where it sends your words, its budget or the folders it may read. Those are yours to set in Settings, Rook.",
            ),
            Local(
                entry("accounts", "Accounts", "The mail accounts signed in on this computer.", SettingKind.Summary, "Accounts", "account sign in login mailbox"),
                { JsonPrimitive(choices.accounts().joinToString(", ").ifEmpty { "None" }) }, null,
                refusal = "Adding or removing an account needs a password, so it is done in Settings, Accounts.",
            ),
            Local(
                entry("adminSignIn", "Server admin sign-in", "Whether a separate admin sign-in for the Stalwart server is saved.", SettingKind.Summary, "Server admin", "admin administrator server stalwart login"),
                { JsonPrimitive(if (choices.adminSaved()) "Saved" else "Not set") }, null,
                refusal = "An admin sign-in is a password or a key, which Rook never handles. Set it in Settings, Server admin.",
            ),
        )
    }
}

private fun JsonElement.text(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonElement.bool(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false
