package org.rampart

import kotlinx.serialization.json.JsonObject

/*
 * The settings map wired to the running app: the real themes and icon packs, the signed-in
 * mail session, and the admin console's own connection.
 *
 * Kept out of `Main.kt` so the window only has to ask for a map. Everything that decides what
 * Rook may read or change is in the files this one hands things to; this one only connects.
 */

/**
 * The map as it stands right now. Blocking in parts (the mailbox and server places reach the
 * network when asked for a value), so it is built and used on a background thread.
 *
 * [session] is the account the Settings pages are about, the same one Rook's mail tools act
 * on. [identities] is the list the window already holds, so listing signatures costs nothing.
 */
internal fun liveSettingsMap(session: Session?, identities: List<Identity>, accounts: List<String>): SettingsMap {
    val computer = ComputerPlace(
        ComputerChoices(
            themes = THEMES.map { SettingOption(it.key, it.label) },
            darkThemes = THEMES.filter { it.dark }.map { it.key }.toSet(),
            iconPacks = ICON_PACKS.map { SettingOption(it.key, it.label) },
            loaders = Loader.entries.map { SettingOption(it.name, it.label) },
            assistant = {
                val config = Assistant.config()
                "Mode ${config.mode.name.lowercase()}, model ${config.model}, " +
                    "monthly ceiling ${Assistant.money(config.ceiling)}"
            },
            accounts = { accounts },
            adminSaved = { AdminLogin.saved().first.isNotBlank() },
        ),
    )
    val mailbox = MailboxPlace(session?.let { LiveMailbox(it, identities) })
    val server = ServerPlace(AdminConsole.connection?.let(::LiveAdmin))
    return SettingsMap(listOf(computer, mailbox, server))
}

/** The mailbox's settings through the person's own session, the calls the Settings pages make. */
private class LiveMailbox(private val session: Session, private val known: List<Identity>) : MailboxAccess {
    private val backend get() = session.jmap

    override fun vacation(): Vacation? = backend.vacation()

    override fun setVacation(value: Vacation) = backend.setVacation(value)

    override fun identities(): List<Identity> = known.ifEmpty { runCatching { backend.identities() }.getOrDefault(emptyList()) }

    override fun setSignature(identityId: String, text: String, html: String) = backend.setSignature(identityId, text, html)

    override fun filters(): String {
        val running = theOneRunning(backend.sieveScripts()) ?: return "No filters yet."
        val rules = scriptOf(backend.sieveText(running)).rules
        if (rules.isEmpty()) return "No filters written by a builder; the script is edited by hand."
        return rules.joinToString("; ") { (if (it.enabled) "" else "(off) ") + it.name }
    }

    override fun security(): String {
        val jmap = backend as? Jmap
        securityUnavailable(session.account.protocol, jmap?.managementAccountId)?.let { return it }
        val security = AccountSecurity(jmap ?: return "This account cannot be managed from here.")
        val state = security.passwordState()
        val apps = runCatching { security.appPasswords().size }.getOrNull()
        return "Two-step sign-in is ${if (state.twoStepOn) "on" else "off"}" +
            (apps?.let { "; $it app password${if (it == 1) "" else "s"}" } ?: "") + "."
    }

    override fun phone(): String {
        val jmap = backend as? Jmap
        val has = jmap?.let { it.hasCalendars() || it.hasContacts() } ?: false
        return phoneSetupUnavailable(session.account.protocol, jmap?.managementAccountId, has)
            ?: "This account's calendars and contacts can be set up on a phone from Settings, Your phone."
    }
}

/**
 * The server's settings through the admin console's own client, so every request goes past
 * the same [adminMethodRefusal] check and the same permissions the console's pages use.
 */
private class LiveAdmin(private val connection: AdminConnection) : AdminReach {
    override val schema: AdminSchema get() = connection.schema
    override val access: AdminAccess get() = connection.access

    override fun rows(obj: AdminObject, properties: List<String>): List<JsonObject> =
        connection.client.page(obj, schema.listFor(obj.viewName), properties, 0, 25).rows

    override fun one(obj: AdminObject, id: String): JsonObject = connection.client.one(obj, id)

    override fun save(obj: AdminObject, id: String, changes: JsonObject): AdminSaved = connection.client.save(obj, id, changes)
}
