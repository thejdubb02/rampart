package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/*
 * The signed-in person's own password, app passwords and two-step login, on Stalwart.
 *
 * Everything here runs as that person with their own mail session, never with an admin
 * token: Stalwart 0.16 lets any account manage its own credentials through its JMAP
 * management objects, and a mail client that asked for administrator rights to change a
 * password would be asking for far more than it needs. `docs/account-security.md` has the
 * research behind every name in this file, with the Stalwart source it came from.
 *
 * The request builders and the response readers are plain functions so they can be tested
 * without a server. [AccountSecurity] is the thin part that sends them.
 */

/** Stalwart's own JMAP capability. Its management objects are the `x:` methods. */
internal const val STALWART_CAPABILITY = "urn:stalwart:jmap"

/**
 * The id of a singleton object. Stalwart encodes the number 20080258862541 in its own
 * base32 alphabet, and it happens to spell this word, so the word is what goes on the wire.
 */
internal const val SINGLETON = "singleton"

/** What Stalwart writes in place of a secret it will not show. */
internal const val MASKED = "****"

/** Whether the account has a password at all, and whether two-step login is on for it. */
internal data class PasswordState(val hasPassword: Boolean, val twoStepOn: Boolean)

/** One app password as the server lists it. The secret itself is never readable again. */
internal data class AppPasswordInfo(
    val id: String,
    val description: String,
    /** The date part only, `2026-09-28`, or blank when the server did not say. */
    val created: String,
    /** Likewise, and blank for one that never expires. */
    val expires: String,
)

/** A new app password, the one time its secret can be seen. */
internal data class NewAppPassword(val id: String, val secret: String)

/** How long a new app password lasts. Null days means it does not expire on its own. */
internal enum class AppPasswordLife(val label: String, val days: Long?) {
    NEVER("Never expires", null),
    MONTH("30 days", 30),
    QUARTER("90 days", 90),
    YEAR("One year", 365),
}

/** One JMAP method call. No `accountId` is needed, but the one the session named is sent to be explicit. */
private fun method(name: String, accountId: String?, args: JsonObjectBuilder.() -> Unit): JsonArray =
    buildJsonArray {
        add(name)
        add(buildJsonObject {
            // Stalwart resolves a management call without an account to the caller's own,
            // so leaving it out would work too. Sending the id the session named means a
            // server that did it differently would refuse rather than guess.
            if (accountId != null) put("accountId", accountId)
            args()
        })
        add("0")
    }

internal fun passwordStateCall(accountId: String?): JsonArray =
    method("x:AccountPassword/get", accountId) { putJsonArray("ids") { add(SINGLETON) } }

internal fun appPasswordsCall(accountId: String?): JsonArray =
    method("x:AppPassword/get", accountId) { put("ids", JsonNull) }

/**
 * Changes the password.
 *
 * [otpCode] is required by the server when two-step login is on, and refused as
 * meaningless otherwise, so it is only sent when there is one. Patch paths are used for
 * the nested field because the server applies an update key by key and a whole `otpAuth`
 * object would have to carry the stored URL it will not show us.
 */
internal fun changePasswordCall(accountId: String?, current: String, new: String, otpCode: String?): JsonArray =
    method("x:AccountPassword/set", accountId) {
        putJsonObject("update") {
            putJsonObject(SINGLETON) {
                put("secret", new)
                put("currentSecret", current)
                otpCode?.takeIf { it.isNotBlank() }?.let { put("otpAuth/otpCode", it.filter(Char::isDigit)) }
            }
        }
    }

/** Turns two-step login on with a URL whose code Rampart has already checked. */
internal fun enableTwoStepCall(accountId: String?, current: String, otpUrl: String): JsonArray =
    method("x:AccountPassword/set", accountId) {
        putJsonObject("update") {
            putJsonObject(SINGLETON) {
                put("currentSecret", current)
                put("otpAuth/otpUrl", otpUrl)
            }
        }
    }

/** Turns it off. The server wants the current code as well as the password to do that. */
internal fun disableTwoStepCall(accountId: String?, current: String, otpCode: String): JsonArray =
    method("x:AccountPassword/set", accountId) {
        putJsonObject("update") {
            putJsonObject(SINGLETON) {
                put("currentSecret", current)
                put("otpAuth/otpCode", otpCode.filter(Char::isDigit))
                put("otpAuth/otpUrl", JsonNull)
            }
        }
    }

/**
 * Makes an app password. The server makes the secret; nothing here chooses it.
 *
 * Permissions are sent as `Inherit`, the same as the account, because a narrower set is a
 * list of several hundred server permissions and the person asking for an app password
 * almost always wants one that works for mail. Choosing them is the admin console's job.
 */
internal fun createAppPasswordCall(accountId: String?, description: String, expiresAt: String?): JsonArray =
    method("x:AppPassword/set", accountId) {
        putJsonObject("create") {
            putJsonObject("new") {
                put("description", description.trim())
                putJsonObject("permissions") { put("@type", "Inherit") }
                if (expiresAt != null) put("expiresAt", expiresAt)
            }
        }
    }

internal fun revokeAppPasswordCall(accountId: String?, id: String): JsonArray =
    method("x:AppPassword/set", accountId) { putJsonArray("destroy") { add(id) } }

/** The moment an app password made now should stop working, as the UTC date-time Stalwart reads. */
internal fun expiryFor(life: AppPasswordLife, now: Instant = Instant.now()): String? =
    life.days?.let { now.truncatedTo(ChronoUnit.SECONDS).plus(it, ChronoUnit.DAYS).toString() }

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

/** The arguments object of a method response, which is always the second element. */
private fun args(response: JsonArray): JsonObject? = response.getOrNull(1) as? JsonObject

/**
 * Reads `x:AccountPassword/get`. The server answers with masks, never the values, so all
 * this can tell is whether there is a password and whether an OTP URL is stored.
 */
internal fun readPasswordState(response: JsonArray): PasswordState {
    val item = (args(response)?.get("list") as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { it["id"].text() == SINGLETON }
        ?: return PasswordState(hasPassword = false, twoStepOn = false)
    val otp = item["otpAuth"] as? JsonObject
    return PasswordState(
        hasPassword = true,
        twoStepOn = !otp?.get("otpUrl").text().isNullOrEmpty(),
    )
}

/** Reads `x:AppPassword/get`, oldest first so a new one appears at the bottom where it was made. */
internal fun readAppPasswords(response: JsonArray): List<AppPasswordInfo> =
    (args(response)?.get("list") as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .mapNotNull { o ->
            val id = o["id"].text() ?: return@mapNotNull null
            AppPasswordInfo(
                id = id,
                description = o["description"].text().orEmpty(),
                created = o["createdAt"].text().orEmpty().substringBefore('T'),
                expires = o["expiresAt"].text().orEmpty().substringBefore('T'),
            )
        }
        .sortedWith(compareBy({ it.created }, { it.id }))

/** Reads a create, or throws with the server's reason in a sentence. */
internal fun readCreatedAppPassword(response: JsonArray): NewAppPassword {
    val a = args(response) ?: throw JmapError("The server's reply to the new app password was empty.")
    val made = (a["created"] as? JsonObject)?.get("new") as? JsonObject
    val id = made?.get("id").text()
    val secret = made?.get("secret").text()
    if (id != null && !secret.isNullOrEmpty() && secret != MASKED) return NewAppPassword(id, secret)
    val refused = (a["notCreated"] as? JsonObject)?.get("new") as? JsonObject
    throw JmapError(
        if (refused != null) refusalSentence(refused, "The app password was not made")
        else "The server made no app password and did not say why.",
    )
}

/** Null when the singleton was updated, otherwise the reason it was not, as a sentence. */
internal fun readPasswordUpdate(response: JsonArray, action: String): String? {
    val a = args(response) ?: return "$action: the server's reply was empty."
    if ((a["updated"] as? JsonObject)?.containsKey(SINGLETON) == true) return null
    val refused = (a["notUpdated"] as? JsonObject)?.get(SINGLETON) as? JsonObject
    return if (refused != null) refusalSentence(refused, action) else "$action: the server did not say why."
}

/** Null when the app password is gone, otherwise why not. */
internal fun readRevoke(response: JsonArray, id: String): String? {
    val a = args(response) ?: return "The app password was not revoked: the server's reply was empty."
    if ((a["destroyed"] as? JsonArray)?.any { it.text() == id } == true) return null
    val refused = (a["notDestroyed"] as? JsonObject)?.get(id) as? JsonObject
    return if (refused != null) refusalSentence(refused, "The app password was not revoked")
    else "The app password was not revoked, and the server did not say why."
}

/**
 * A JMAP SetError as one sentence.
 *
 * Stalwart's own descriptions are already plain ("Current secret is incorrect.") and are
 * used as they are, with the two that name its internals reworded. A bare type with no
 * description gets a sentence of ours rather than the type name on screen.
 */
internal fun refusalSentence(error: JsonObject, action: String): String {
    val type = error["type"].text().orEmpty()
    val description = error["description"].text()?.trim().orEmpty()
    val said = when {
        description.equals("Current secret is incorrect.", ignoreCase = true) ->
            "the current password is not right"
        description.startsWith("Current OTP code is required", ignoreCase = true) ->
            "the server wants the six digit code from your authenticator app as well"
        description.startsWith("Current secret must be provided", ignoreCase = true) ->
            "the server wants your current password"
        description.equals("Operation not allowed.", ignoreCase = true) ->
            "this account's password is kept in another directory, so it has to be changed there"
        description.isNotEmpty() -> lowerFirst(description.removeSuffix("."))
        type == "forbidden" -> "the server does not allow it for this account"
        type == "overQuota" -> "the account already has as many as the server allows"
        type == "invalidProperties" -> "the server did not accept what was sent"
        type == "notFound" -> "it no longer exists on the server"
        type.isNotEmpty() -> "the server said $type"
        else -> "the server did not say why"
    }
    return "$action: $said."
}

/**
 * Why a JMAP call failed, as one sentence for this page.
 *
 * A method-level `forbidden` is the one worth rewording: it is how Stalwart says the
 * account lacks the permission for its own credentials, which an administrator can
 * remove, and "forbidden" alone reads as though the person did something wrong.
 */
internal fun securityFailure(e: Throwable, action: String): String {
    val said = whyFailed(e).trim()
    return when {
        said.contains("refused the request: forbidden", ignoreCase = true) ->
            "$action: this server does not let the account manage that itself. An administrator can allow it."
        said.contains("HTTP 401") ->
            "$action: the server no longer accepts the password Rampart signed in with. Sign in to this account again."
        said.contains("HTTP 429") || said.contains("authenticationBan", ignoreCase = true) ->
            "$action: the server has paused attempts from this computer after too many wrong passwords. Wait and try again."
        said.isEmpty() -> "$action: the server could not be reached."
        else -> "$action: ${lowerFirst(said.removeSuffix("."))}."
    }
}

/**
 * A server's sentence placed after a colon in ours, so its capital goes, unless it is the
 * start of a name or an acronym such as IMAP, where the second letter is a capital too.
 */
internal fun lowerFirst(text: String): String =
    if (text.length > 1 && text[0].isUpperCase() && text[1].isLowerCase()) text[0].lowercase() + text.substring(1) else text

/**
 * Why the Security page cannot be used for this account, or null when it can.
 *
 * Asked of the account's own protocol and session rather than its host name: an IMAP
 * account may well be on a Stalwart server, but IMAP has no way to reach these objects,
 * and a JMAP server that is not Stalwart does not name the capability in its session.
 */
internal fun securityUnavailable(protocol: String?, managementAccountId: String?): String? = when {
    protocol == null -> "Sign in to an account first, and its password and app passwords can be managed here."
    protocol == "imap" ->
        "This account is signed in over IMAP, which has no way to change a password or make app passwords, so do it in your provider's own settings."
    managementAccountId == null ->
        "This server is not Stalwart, so Rampart cannot manage its passwords, and your provider's own settings are the place for that."
    else -> null
}

/** Why a new password cannot be sent yet, or null when it can. Checked here so a typo never reaches the server. */
internal fun newPasswordProblem(current: String, new: String, repeat: String): String? = when {
    current.isEmpty() -> "Type your current password."
    new.isEmpty() -> "Type a new password."
    new != repeat -> "The two new passwords are not the same."
    new == current -> "The new password is the same as the current one."
    else -> null
}

/**
 * The calls themselves, on one live JMAP session.
 *
 * Every method throws [JmapError] with a sentence a person can read, and the page shows
 * that sentence. None of them retries: a password that was wrong once will be wrong again,
 * and a retry is how an account ends up banned for too many attempts.
 */
internal class AccountSecurity(private val jmap: Jmap) {
    private val accountId: String? get() = jmap.managementAccountId

    fun passwordState(): PasswordState = try {
        readPasswordState(jmap.manage(passwordStateCall(accountId)).first())
    } catch (e: Exception) {
        throw JmapError(securityFailure(e, "Could not read this account's sign-in settings"))
    }

    fun appPasswords(): List<AppPasswordInfo> = try {
        readAppPasswords(jmap.manage(appPasswordsCall(accountId)).first())
    } catch (e: Exception) {
        throw JmapError(securityFailure(e, "Could not list app passwords"))
    }

    fun createAppPassword(description: String, life: AppPasswordLife): NewAppPassword {
        val response = try {
            jmap.manage(createAppPasswordCall(accountId, description, expiryFor(life))).first()
        } catch (e: Exception) {
            throw JmapError(securityFailure(e, "The app password was not made"))
        }
        return readCreatedAppPassword(response)
    }

    fun revokeAppPassword(id: String) {
        val response = try {
            jmap.manage(revokeAppPasswordCall(accountId, id)).first()
        } catch (e: Exception) {
            throw JmapError(securityFailure(e, "The app password was not revoked"))
        }
        readRevoke(response, id)?.let { throw JmapError(it) }
    }

    fun changePassword(current: String, new: String, otpCode: String?) =
        update(changePasswordCall(accountId, current, new, otpCode), "The password was not changed")

    fun enableTwoStep(current: String, otpUrl: String) =
        update(enableTwoStepCall(accountId, current, otpUrl), "Two-step login was not turned on")

    fun disableTwoStep(current: String, otpCode: String) =
        update(disableTwoStepCall(accountId, current, otpCode), "Two-step login was not turned off")

    private fun update(call: JsonArray, action: String) {
        val response = try {
            jmap.manage(call).first()
        } catch (e: Exception) {
            throw JmapError(securityFailure(e, action))
        }
        readPasswordUpdate(response, action)?.let { throw JmapError(it) }
    }
}
