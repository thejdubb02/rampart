package org.rampart

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * The mailbox's own settings on the server: the away reply, the signature on each identity,
 * and, to read only, the filters, the security page and the phone page.
 *
 * All of it goes through the person's own mail session, the same calls the Settings pages
 * make. Nothing here touches the admin credential, which is the rule in CLAUDE.md: a mail
 * feature never needs admin rights on the mail server.
 */

/**
 * What the mailbox side can reach, supplied by the window that holds the session.
 *
 * Every method may block on the network and may throw; the place turns a throw into a
 * sentence. [identities] is the list already in memory, because list_settings has to be
 * answerable without a round trip.
 */
internal interface MailboxAccess {
    fun vacation(): Vacation?
    fun setVacation(value: Vacation)
    fun identities(): List<Identity>
    fun setSignature(identityId: String, text: String, html: String)

    /** The filter rules, as words, or a sentence saying why there are none to read. */
    fun filters(): String

    /** Whether two-step sign-in is on and how many app passwords there are, in words. */
    fun security(): String

    /** Whether a phone can be set up from here, in words. */
    fun phone(): String
}

private const val AWAY = "mailbox.away."
private const val SIGNATURE = "mailbox.signature."

internal class MailboxPlace(private val access: MailboxAccess?) : SettingPlace {
    override fun entries(): List<SettingEntry> {
        val access = access ?: return listOf(
            SettingEntry(
                "mailbox", "Mailbox settings", "The away reply, signatures and filters kept on the server for this account.",
                SettingKind.Summary, SettingHome.MAILBOX, "Settings", "away vacation signature filters",
            ),
        )
        fun away(id: String, name: String, description: String, kind: SettingKind, keywords: String) =
            SettingEntry(AWAY + id, name, description, kind, SettingHome.MAILBOX, "Settings, Away reply", "away reply vacation out of office auto-reply autoreply holiday $keywords")
        val fixed = listOf(
            away("enabled", "Away reply", "Whether the server answers incoming mail with an automatic reply.", SettingKind.OnOff, "on off enable"),
            away("subject", "Away reply subject", "The subject of the automatic reply. Empty uses the server's own.", SettingKind.Words(200), "title"),
            away("message", "Away reply message", "The text of the automatic reply.", SettingKind.Words(4000, multiline = true), "text body"),
            away("from", "Away reply starts", "The day the automatic reply starts, at the start of that day (UTC). No date starts it at once.", SettingKind.Day, "begin start date from"),
            away(
                "until", "Away reply ends",
                "The first day it no longer replies, at the start of that day (UTC). To reply through Friday, end on Saturday. No date runs it until it is switched off.",
                SettingKind.Day, "end stop until date back return",
            ),
        )
        val signatures = runCatching { access.identities() }.getOrDefault(emptyList()).map { identity ->
            SettingEntry(
                SIGNATURE + identity.id, "Signature for ${identity.email}",
                "The sign-off added to mail sent as ${identity.email}, kept on the server. Rook writes it as plain text, which replaces a formatted signature and any picture in it.",
                SettingKind.Words(1500, multiline = true), SettingHome.MAILBOX, "Settings, Identities and signatures",
                "signature sign-off signoff identity ${identity.name}",
            )
        }
        val reading = listOf(
            SettingEntry(
                "mailbox.filters", "Filters", "The rules the server runs on incoming mail.",
                SettingKind.Summary, SettingHome.MAILBOX, "Settings, Filters", "filter rules sieve sort spam folder",
            ),
            SettingEntry(
                "mailbox.security", "Security", "Two-step sign-in and app passwords for this account.",
                SettingKind.Summary, SettingHome.MAILBOX, "Settings, Security", "password two-step 2fa totp app password security",
            ),
            SettingEntry(
                "mailbox.phone", "Your phone", "Whether this account's calendars and contacts can be set up on a phone.",
                SettingKind.Summary, SettingHome.MAILBOX, "Settings, Your phone", "phone caldav carddav calendar contacts mobile",
            ),
        )
        return fixed + signatures + reading
    }

    override fun refusal(entry: SettingEntry): String? = when {
        access == null -> "There is no mail account signed in, so there is no mailbox to change."
        entry.id == "mailbox.filters" ->
            "Filters are rules rather than settings. Describe one in Settings, Filters, and it is built from your words for you to check."
        entry.id == "mailbox.security" ->
            "Anything that needs your current password, such as the password itself, two-step sign-in or app passwords, is changed in Settings, Security and never through Rook."
        entry.id == "mailbox.phone" -> "Setting up a phone is done in Settings, Your phone."
        entry.kind == SettingKind.Summary -> "This one is for reading, not for changing."
        else -> null
    }

    override fun current(entry: SettingEntry): JsonElement {
        val access = access ?: throw SettingTrouble("There is no mail account signed in.")
        return when {
            entry.id.startsWith(AWAY) -> awayValue(readVacation(access), entry.id.removePrefix(AWAY))
            entry.id.startsWith(SIGNATURE) -> JsonPrimitive(identity(access, entry.id).textSignature)
            entry.id == "mailbox.filters" -> JsonPrimitive(trying { access.filters() })
            entry.id == "mailbox.security" -> JsonPrimitive(trying { access.security() })
            entry.id == "mailbox.phone" -> JsonPrimitive(trying { access.phone() })
            else -> throw SettingTrouble("The mailbox has no setting called ${entry.id}.")
        }
    }

    override fun group(entry: SettingEntry): String = when {
        entry.id.startsWith(AWAY) -> "mailbox.away"
        else -> entry.id
    }

    override fun title(group: String): String = when {
        group == "mailbox.away" -> "Away reply, on the server"
        group.startsWith(SIGNATURE) -> "Signature, on the server"
        else -> "Your mailbox, on the server"
    }

    /**
     * The whole away reply as it would be after these changes, checked the way the Settings
     * page checks it. Asked before the card is made, so a reply that would go out empty is
     * refused with a reason the model can pass on, rather than failing after Confirm.
     */
    override fun groupProblem(changes: List<Pair<SettingEntry, JsonElement>>): String? {
        val away = changes.filter { it.first.id.startsWith(AWAY) }
        if (away.isEmpty()) return null
        val access = access ?: return "There is no mail account signed in."
        val now = runCatching { readVacation(access) }.getOrElse { return it.message }
        return vacationProblem(withAway(now, away))
    }

    override fun apply(changes: List<Pair<SettingEntry, JsonElement>>): String? {
        val access = access ?: return "There is no mail account signed in."
        val first = changes.firstOrNull()?.first ?: return null
        return runCatching {
            when {
                first.id.startsWith(AWAY) -> {
                    val next = withAway(readVacation(access), changes)
                    vacationProblem(next)?.let { return it }
                    access.setVacation(next)
                }
                first.id.startsWith(SIGNATURE) -> {
                    val identity = identity(access, first.id)
                    val text = (changes.last().second as? JsonPrimitive)?.contentOrNull.orEmpty()
                    access.setSignature(identity.id, text, signatureHtml(text))
                }
                else -> return refusal(first) ?: "Rook cannot change ${first.name}."
            }
            null
        }.getOrElse { it.message ?: "The server did not take the change." }
    }

    private fun readVacation(access: MailboxAccess): Vacation =
        trying { access.vacation() } ?: throw SettingTrouble("This account's server keeps no away reply, so there is nothing to read or change.")

    private fun identity(access: MailboxAccess, id: String): Identity {
        val wanted = id.removePrefix(SIGNATURE)
        return access.identities().firstOrNull { it.id == wanted }
            ?: throw SettingTrouble("This account has no identity with that id any more.")
    }

    private fun <T> trying(block: () -> T): T = try {
        block()
    } catch (e: SettingTrouble) {
        throw e
    } catch (e: Exception) {
        throw SettingTrouble(e.message ?: "The server could not be asked.")
    }
}

private fun awayValue(v: Vacation, field: String): JsonElement = when (field) {
    "enabled" -> JsonPrimitive(v.enabled)
    "subject" -> JsonPrimitive(v.subject.orEmpty())
    "message" -> JsonPrimitive(v.text)
    "from" -> v.from?.substringBefore('T')?.let { JsonPrimitive(it) } ?: JsonNull
    "until" -> v.to?.substringBefore('T')?.let { JsonPrimitive(it) } ?: JsonNull
    else -> JsonNull
}

/** The away reply with these changes laid over it. Days become midnight UTC, as the Settings page writes them. */
internal fun withAway(v: Vacation, changes: List<Pair<SettingEntry, JsonElement>>): Vacation =
    changes.fold(v) { acc, (entry, value) ->
        val p = value as? JsonPrimitive
        val text = if (value is JsonNull) null else p?.contentOrNull
        when (entry.id.removePrefix(AWAY)) {
            "enabled" -> acc.copy(enabled = p?.booleanOrNull ?: acc.enabled)
            "subject" -> acc.copy(subject = text?.ifBlank { null })
            "message" -> acc.copy(text = text.orEmpty())
            "from" -> acc.copy(from = text?.ifBlank { null }?.let { "${it}T00:00:00Z" })
            "until" -> acc.copy(to = text?.ifBlank { null }?.let { "${it}T00:00:00Z" })
            else -> acc
        }
    }

/**
 * Plain text as the HTML half of a signature: escaped, with its line breaks kept.
 *
 * Both halves are written together because the HTML one is what goes out in formatted mail.
 * Changing only the text would leave the old sign-off on every formatted message, which
 * would look exactly like the change had not worked.
 */
internal fun signatureHtml(text: String): String =
    if (text.isBlank()) ""
    else text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .lines().joinToString("<br>")
