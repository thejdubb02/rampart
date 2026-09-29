package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * Mailboxes that belong to somebody else and that you can read: a group's, or a folder a
 * colleague shared with you (RAM-45).
 *
 * JMAP models both as a second account in your own session, with its own id, and every
 * request about it names that id. So the whole of keeping a shared mailbox's actions out of
 * your own comes down to one rule this file exists to hold: **every request for a shared
 * account is built here with that account's id written into it**, never inherited from the
 * session it rode in on. The builders take the id as an argument and the tests check it
 * comes out the other end.
 *
 * Nothing here needs a Stalwart admin token or any server setting. It is read as the
 * signed-in person, and the server decides what they see. docs/stalwart-inventory.md,
 * "Sharing in Stalwart 0.16", has what the server does and where it says so.
 */

internal const val MAIL_CAPABILITY = "urn:ietf:params:jmap:mail"
internal const val MAIL_SHARE = "urn:ietf:params:jmap:mail:share"
internal const val PRINCIPALS = "urn:ietf:params:jmap:principals"
private const val CONTACTS_CAPABILITY = "urn:ietf:params:jmap:contacts"
private const val CALENDARS_CAPABILITY = "urn:ietf:params:jmap:calendars"
private const val FILES_CAPABILITY = "urn:ietf:params:jmap:filenode"

/**
 * One account listed in a JMAP session.
 *
 * [owner] is a guess at whether the signed-in person is treated as the account's owner,
 * which on Stalwart means a member of the group it belongs to. Stalwart does not say so
 * directly: it sets the `may create` flag on calendars, address books and files from
 * exactly that, so any of the three being true is read as ownership. A server that sends
 * none of them is read as not owning it, which only costs a top-level folder being refused
 * here rather than by the server.
 */
internal data class SessionAccount(
    val id: String,
    val name: String,
    val personal: Boolean,
    val capabilities: Set<String>,
    val owner: Boolean,
) {
    val hasMail: Boolean get() = MAIL_CAPABILITY in capabilities
    val canShare: Boolean get() = MAIL_SHARE in capabilities
}

/** Every account the session lists, in the order it lists them. Never throws. */
internal fun sessionAccountsIn(session: JsonObject): List<SessionAccount> {
    val accounts = session["accounts"] as? JsonObject ?: return emptyList()
    return accounts.entries.mapNotNull { (id, value) ->
        val o = value as? JsonObject ?: return@mapNotNull null
        val caps = o["accountCapabilities"] as? JsonObject ?: JsonObject(emptyMap())
        fun flag(capability: String, name: String): Boolean =
            ((caps[capability] as? JsonObject)?.get(name) as? JsonPrimitive)?.booleanOrNull == true
        SessionAccount(
            id = id,
            name = (o["name"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null } ?: id,
            personal = (o["isPersonal"] as? JsonPrimitive)?.booleanOrNull == true,
            capabilities = caps.keys,
            owner = flag(CONTACTS_CAPABILITY, "mayCreateAddressBook") ||
                flag(CALENDARS_CAPABILITY, "mayCreateCalendar") ||
                flag(FILES_CAPABILITY, "mayCreateTopLevelFileNode"),
        )
    }
}

/**
 * The accounts worth drawing as shared mailboxes: every one with mail that is not the
 * login's own. [primaryId] is the login's mail account, excluded by id as well as by
 * `isPersonal`, so a server that forgets the flag cannot show your own mailbox twice.
 */
internal fun sharedMailAccounts(accounts: List<SessionAccount>, primaryId: String): List<SessionAccount> =
    accounts.filter { it.id != primaryId && !it.personal && it.hasMail }

/**
 * The key a shared account is filed under in the window: every map of mailboxes, cards,
 * stores and settings that is keyed by account.
 *
 * Built from the login it came through and the account's own id, so the same group seen
 * through two logins is two entries (each acts as a different person), and so it can never
 * equal a real key, which is `address@server` and has no `#shared/` in it. It is shaped
 * `address#shared/id@server` because a signed-in mailbox's key is always its account's
 * address, an `@` and its server ([Session.key]); [sharedSavedAccount] is what makes the two
 * agree. A JMAP id is letters, digits, `-` and `_`, so it cannot carry an `@` of its own.
 */
internal fun sharedKey(loginEmail: String, server: String, accountId: String): String =
    "$loginEmail#shared/$accountId@$server"

internal fun isSharedKey(key: String): Boolean = "#shared/" in key

/** The login a shared key came through, or [key] itself when it is not a shared one. */
internal fun loginKeyOf(key: String): String =
    if (!isSharedKey(key)) key else key.substringBefore("#shared/") + "@" + key.substringAfterLast('@')

/** The JMAP account id a shared key stands for, or null when [key] is not one. */
internal fun sharedAccountIdOf(key: String): String? =
    key.substringAfter("#shared/", "").substringBeforeLast('@').ifBlank { null }

/**
 * The account record a shared mailbox is opened under. Never written to the accounts file
 * and never given a password: it is the login's own session speaking for another account.
 * Its name is the owner's, which is what the sidebar heads it with.
 */
internal fun sharedSavedAccount(login: SavedAccount, account: SessionAccount): SavedAccount =
    SavedAccount(
        name = account.name,
        server = login.server,
        email = "${login.email}#shared/${account.id}",
        protocol = login.protocol,
    )

// ---- requests, each with the account id written in ------------------------------------

/** One JMAP method call for [accountId]. The only way this file builds one. */
internal fun sharedInvocation(
    method: String,
    accountId: String,
    callId: String,
    args: JsonObjectBuilder.() -> Unit = {},
): JsonArray = buildJsonArray {
    add(JsonPrimitive(method))
    add(buildJsonObject { put("accountId", accountId); args() })
    add(JsonPrimitive(callId))
}

/**
 * Every folder in [accountId] with what the signed-in person may do in it.
 *
 * `shareWith` is asked for by name because Stalwart leaves it out otherwise, and it comes
 * back null on a folder you may not share, which is the answer the share dialog needs.
 */
internal fun mailboxesWithRights(accountId: String, ids: List<String>? = null): JsonArray =
    sharedInvocation("Mailbox/get", accountId, "m") {
        if (ids == null) put("ids", JsonNull) else putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("properties") {
            listOf(
                "id", "name", "parentId", "role", "sortOrder", "totalEmails",
                "unreadEmails", "unreadThreads",
                "myRights", "isSubscribed", "shareWith",
            ).forEach { add(JsonPrimitive(it)) }
        }
    }

/** Which folders each of [ids] sits in, which is what a right is checked against. */
internal fun whereMessagesAre(accountId: String, ids: List<String>): JsonArray =
    sharedInvocation("Email/get", accountId, "w") {
        putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("properties") { add(JsonPrimitive("id")); add(JsonPrimitive("mailboxIds")) }
    }

/**
 * Grants [level] on folder [mailboxId] to [grantee], or takes their access away when
 * [level] is null. A patch of that one grantee's entry, so anybody else the folder is
 * shared with is left exactly as it was.
 */
internal fun shareFolder(accountId: String, mailboxId: String, grantee: String, level: ShareLevel?): JsonArray =
    sharedInvocation("Mailbox/set", accountId, "s") {
        putJsonObject("update") {
            putJsonObject(mailboxId) {
                put("shareWith/$grantee", level?.shareWithValue() ?: JsonNull)
            }
        }
    }

/** The principal behind an address, by the exact-address filter Stalwart implements. */
internal fun principalByAddress(accountId: String, address: String): JsonArray =
    sharedInvocation("Principal/query", accountId, "p") {
        putJsonObject("filter") { put("email", address.trim()) }
        put("limit", 2)
    }

/** Names and addresses for [ids], so a share list shows people rather than account ids. */
internal fun principalsNamed(accountId: String, ids: Collection<String>): JsonArray =
    sharedInvocation("Principal/get", accountId, "n") {
        putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("properties") {
            add(JsonPrimitive("id")); add(JsonPrimitive("name")); add(JsonPrimitive("email")); add(JsonPrimitive("type"))
        }
    }

// ---- answers ---------------------------------------------------------------------------

/** A folder in any account, with the rights and grants [mailboxesWithRights] asked for. */
internal data class RightsFolder(
    val mailbox: Mailbox,
    val rights: MailboxRights,
    /** Who else it is shared with, by account id, or null where you may not see that. */
    val shareWith: Map<String, MailboxRights>?,
)

/**
 * Reads a Mailbox/get answer.
 *
 * A folder whose parent is not in the list gets no parent. Stalwart lists only the folders
 * of a shared account you can read, so a shared subfolder of an unshared folder would
 * otherwise point at nothing and fall out of the sidebar's tree.
 */
internal fun rightsFoldersIn(response: JsonArray): List<RightsFolder> {
    val list = (response.getOrNull(1) as? JsonObject)?.get("list") as? JsonArray ?: return emptyList()
    val rows = list.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        if ((o["id"] as? JsonPrimitive)?.contentOrNull == null) return@mapNotNull null
        RightsFolder(
            mailbox = mailboxFrom(o),
            rights = MailboxRights.from(o["myRights"]),
            shareWith = (o["shareWith"] as? JsonObject)?.mapValues { (_, v) -> MailboxRights.from(v) },
        )
    }
    val ids = rows.map { it.mailbox.id }.toSet()
    return rows.map { row ->
        val parent = row.mailbox.parentId
        if (parent != null && parent !in ids) row.copy(mailbox = row.mailbox.copy(parentId = null)) else row
    }.sortedWith(compareBy({ if (it.mailbox.role == "inbox") 0 else 1 }, { it.mailbox.name.lowercase() }))
}

/** Message id to the folders it is in, from a [whereMessagesAre] answer. */
internal fun messageFoldersIn(response: JsonArray): Map<String, Set<String>> {
    val list = (response.getOrNull(1) as? JsonObject)?.get("list") as? JsonArray ?: return emptyMap()
    return list.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        id to (o["mailboxIds"] as? JsonObject)?.filterValues {
            (it as? JsonPrimitive)?.booleanOrNull == true
        }?.keys.orEmpty()
    }.toMap()
}

/** Somebody a folder can be shared with. */
internal data class Principal(val id: String, val name: String, val email: String)

internal fun principalIdsIn(response: JsonArray): List<String> =
    ((response.getOrNull(1) as? JsonObject)?.get("ids") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

internal fun principalsIn(response: JsonArray): List<Principal> {
    val list = (response.getOrNull(1) as? JsonObject)?.get("list") as? JsonArray ?: return emptyList()
    return list.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val email = (o["email"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        Principal(id, (o["name"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null } ?: email.ifBlank { id }, email)
    }
}

/**
 * Whether a Mailbox/set answer shows [mailboxId] as changed, or the server's own sentence
 * for why not. Null means it worked.
 */
internal fun shareRefusal(response: JsonArray, mailboxId: String): String? {
    val body = response.getOrNull(1) as? JsonObject ?: return "The server's reply could not be read."
    if ((body["updated"] as? JsonObject)?.containsKey(mailboxId) == true) return null
    val why = ((body["notUpdated"] as? JsonObject)?.get(mailboxId) as? JsonObject)
    val description = (why?.get("description") as? JsonPrimitive)?.contentOrNull
    val type = (why?.get("type") as? JsonPrimitive)?.contentOrNull
    return when {
        !description.isNullOrBlank() -> description
        type == "forbidden" -> "The server did not let you share this folder."
        else -> "The server did not change who this folder is shared with."
    }
}

/**
 * The sentence for a Principal call the server refused, from the error [Jmap] raised.
 *
 * The one worth a sentence of its own is `forbidden`: Stalwart's directory is closed unless
 * an administrator opens it, and that is a setting on their server rather than something
 * wrong with this one.
 */
internal fun principalLookupRefusal(error: String): String =
    if ("forbidden" in error) {
        "This server does not let accounts look each other up, so Rampart cannot find who to share with. Its administrator can turn on directory queries."
    } else {
        "The server could not look that address up. $error"
    }
