package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/*
 * Sending with conditions, and finding out afterwards what happened.
 *
 * Both halves are things the server already does. JMAP's EmailSubmission carries an SMTP
 * envelope with parameters on it (RFC 8621 7.5), and a Stalwart server that advertises
 * REQUIRETLS and DSN in its submissionExtensions takes them from there exactly as it would
 * from an SMTP client. Afterwards the same submission reports, per recipient, whether the
 * next server along accepted the message. Rampart holds none of this itself.
 *
 * Everything here is a plain function of its arguments, so the shapes can be checked
 * without a server; [Jmap] does the calling.
 */

/** RFC 8689: refuse to relay the message anywhere the connection is not encrypted. */
internal const val REQUIRETLS = "REQUIRETLS"

/** RFC 3461: delivery status notifications, asked for per recipient. */
internal const val DSN = "DSN"

/** RFC 4865: the server holds the message until a given time. */
internal const val FUTURERELEASE = "FUTURERELEASE"

/**
 * The extension names a JMAP session advertises for submission, upper-cased.
 *
 * RFC 8621 makes `submissionExtensions` an object from extension name to its arguments.
 * An array of names is accepted as well, because reading the names is all this needs and a
 * server that gets the shape slightly wrong still means what it says.
 */
internal fun submissionExtensionsIn(capability: JsonElement?): Set<String> {
    val extensions = (capability as? JsonObject)?.get("submissionExtensions") ?: return emptySet()
    return when (extensions) {
        is JsonObject -> extensions.keys
        is JsonArray -> extensions.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
}

/**
 * Why a message must not go out as asked, or null when it can.
 *
 * Only secure delivery refuses. Somebody who asked for the message to travel encrypted or
 * not at all has made a security decision, and sending it anyway because this server
 * cannot promise that would quietly undo it. A delivery confirmation that cannot be asked
 * for is different: the message still goes, and the status on the sent copy still says
 * whether it arrived, so that one is simply left off.
 */
internal fun refusedOption(draft: Draft, extensions: Set<String>): String? =
    if (draft.requireTls && REQUIRETLS !in extensions) {
        "This server cannot promise secure delivery, so the message was not sent. " +
            "Turn off Require secure delivery to send it anyway."
    } else {
        null
    }

/**
 * The `envelope` for an EmailSubmission, or null when the server's own will do.
 *
 * Null is the ordinary case and the one every message took before this existed: with no
 * envelope, the server takes the sender from the identity and the recipients from the
 * headers. An envelope is only built when there is a parameter to carry, because once
 * there is one the recipients have to be listed by hand, and a list built here is one
 * more thing that could disagree with the headers.
 *
 * [from] has to be the identity's own address: Stalwart refuses an envelope sender that
 * is not. [recipients] are listed once each, in the order written.
 *
 * [holdUntil] is RFC 4865 FUTURERELEASE, for a server that holds a scheduled message
 * itself. Nothing passes it yet: scheduled send is still held by Rampart in
 * `Scheduled.kt`, and the parameter is here so that change is one argument rather than a
 * second envelope builder.
 */
internal fun submissionEnvelope(
    from: String,
    recipients: List<String>,
    requireTls: Boolean,
    confirmDelivery: Boolean,
    extensions: Set<String>,
    holdUntil: Instant? = null,
): JsonObject? {
    val tls = requireTls && REQUIRETLS in extensions
    val dsn = confirmDelivery && DSN in extensions
    val hold = holdUntil?.takeIf { FUTURERELEASE in extensions }
    if (!tls && !dsn && hold == null) return null
    return buildJsonObject {
        putJsonObject("mailFrom") {
            put("email", from.trim())
            putJsonObject("parameters") {
                // A parameter with no value is written as null, which Stalwart turns back
                // into the bare keyword on the MAIL FROM line.
                if (tls) put(REQUIRETLS, JsonNull)
                // Headers only. A bounce that carries the whole message back doubles its
                // size in the Inbox and adds nothing somebody needs to know why it failed.
                if (dsn) put("RET", "HDRS")
                if (hold != null) put("HOLDUNTIL", hold.truncatedTo(ChronoUnit.SECONDS).toString())
            }
        }
        putJsonArray("rcptTo") {
            val seen = mutableSetOf<String>()
            recipients.map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }.forEach { address ->
                add(
                    buildJsonObject {
                        put("email", address)
                        if (dsn) {
                            // SUCCESS is the one this is for; FAILURE and DELAY are what a
                            // sender gets anyway, and naming SUCCESS alone would turn them off.
                            putJsonObject("parameters") { put("NOTIFY", "SUCCESS,FAILURE,DELAY") }
                        } else {
                            put("parameters", JsonNull)
                        }
                    },
                )
            }
        }
    }
}

/** RFC 8621's four answers to "did it get there", per recipient. */
internal enum class Delivered { YES, NO, QUEUED, UNKNOWN }

internal data class RecipientDelivery(val address: String, val delivered: Delivered, val smtpReply: String)

/** One EmailSubmission, reduced to what saying whether it arrived needs. */
internal data class SubmissionRecord(
    val sendAt: String,
    val undoStatus: String,
    val recipients: List<RecipientDelivery>,
)

/** An EmailSubmission from EmailSubmission/get. Anything missing reads as not known. */
internal fun submissionRecordOf(submission: JsonObject): SubmissionRecord {
    fun text(element: JsonElement?) = (element as? JsonPrimitive)?.contentOrNull.orEmpty()
    val status = submission["deliveryStatus"] as? JsonObject
    return SubmissionRecord(
        sendAt = text(submission["sendAt"]),
        undoStatus = text(submission["undoStatus"]),
        recipients = status.orEmpty().map { (address, value) ->
            val entry = value as? JsonObject
            RecipientDelivery(
                address = address,
                delivered = when (text(entry?.get("delivered")).lowercase()) {
                    "yes" -> Delivered.YES
                    "no" -> Delivered.NO
                    "queued" -> Delivered.QUEUED
                    else -> Delivered.UNKNOWN
                },
                smtpReply = text(entry?.get("smtpReply")).replace(Regex("\\s+"), " ").trim(),
            )
        },
    )
}

/**
 * What to say on a sent message about whether it arrived.
 *
 * [says] is the line, [because] is the server's own words when they add anything, and
 * [failed] draws it as a failure.
 */
internal data class DeliveryReport(val says: String, val because: String? = null, val failed: Boolean = false)

/**
 * The line for a sent message, from its submissions, or null to say nothing.
 *
 * **Null is the common answer and it is not an error.** Stalwart expunges a submission a
 * few days after it went (three by default), so an older sent message has none; a message
 * filed in Sent by another client has none; a message that was not sent from this account
 * has none. None of those is something to warn about.
 *
 * Null too when every recipient is `unknown`, which on Stalwart is most mail after it has
 * left: it reports from its own delivery queue, and once the queue is done with a message
 * the record of the last hop goes with it. A line on every sent message saying "we do not
 * know" would be read by nobody and would crowd out the one that says a message bounced.
 *
 * The latest submission is the one read, because a message sent twice is what the second
 * send did to it.
 */
internal fun deliveryReport(submissions: List<SubmissionRecord>): DeliveryReport? {
    val latest = submissions.filter { it.undoStatus != "canceled" }.maxByOrNull { it.sendAt } ?: return null
    val all = latest.recipients
    if (all.isEmpty()) return null
    val refused = all.filter { it.delivered == Delivered.NO }
    val waiting = all.filter { it.delivered == Delivered.QUEUED }
    val arrived = all.filter { it.delivered == Delivered.YES }
    return when {
        refused.isNotEmpty() -> {
            val first = refused.first()
            val who = if (refused.size == 1) first.address else "${first.address} and ${refused.size - 1} more"
            DeliveryReport(
                "Not delivered to $who. ${reasonFor(first.smtpReply) ?: "The receiving server refused it."}",
                first.smtpReply.ifEmpty { null },
                failed = true,
            )
        }
        waiting.isNotEmpty() -> {
            // A 4xx reply on a queued recipient is a delivery attempt that failed and will be
            // tried again, which is worth saying; the ordinary "Queued" reply is not.
            val retrying = waiting.firstOrNull { it.smtpReply.startsWith("4") }
            if (retrying != null) {
                DeliveryReport(
                    "Not delivered yet to ${retrying.address}. Your server is still trying.",
                    retrying.smtpReply,
                )
            } else {
                DeliveryReport("Waiting to be delivered.")
            }
        }
        arrived.size == all.size -> DeliveryReport("Delivered.")
        arrived.isNotEmpty() -> DeliveryReport("Delivered to ${arrived.size} of ${all.size} recipients.")
        else -> null
    }
}

/**
 * The reply's enhanced status code (RFC 3463), in words, for the handful that say
 * something a person can act on. Null for the rest, and the raw reply is shown instead.
 */
internal fun reasonFor(smtpReply: String): String? {
    val code = Regex("""\b[245]\.(\d{1,3})\.(\d{1,3})\b""").find(smtpReply) ?: return null
    val subject = code.groupValues[1]
    val detail = code.groupValues[2]
    return when {
        subject == "1" && (detail == "1" || detail == "10") -> "That address does not exist."
        subject == "2" && detail == "2" -> "That mailbox is full."
        // RFC 8689's own code for a message that asked for encryption the path could not give.
        subject == "7" && detail == "30" ->
            "The receiving server could not promise an encrypted connection, which this message required."
        subject == "7" -> "The receiving server refused it."
        else -> null
    }
}
