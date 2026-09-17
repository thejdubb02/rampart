package org.rampart

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/** The out of office reply, as the server keeps it. One per account, never more. */
internal data class Vacation(
    val enabled: Boolean = false,
    /** "2026-09-20T00:00:00Z", or null for starting now. */
    val from: String? = null,
    /** Null for no end, which means it runs until it is switched off by hand. */
    val to: String? = null,
    val subject: String? = null,
    val text: String = "",
    val html: String? = null,
)

/**
 * Every field here can be missing or JSON null, and the two mean the same thing: nobody has
 * set one. Reading them with `as? JsonPrimitive` rather than `.jsonPrimitive` is what keeps
 * a null from throwing, which is the bug that once made every message in the app unopenable.
 */
internal fun vacationOf(o: JsonObject): Vacation {
    val enabled = (o["isEnabled"] as? JsonPrimitive)?.booleanOrNull ?: false
    val from = (o["fromDate"] as? JsonPrimitive)?.contentOrNull
    val to = (o["toDate"] as? JsonPrimitive)?.contentOrNull
    val subject = (o["subject"] as? JsonPrimitive)?.contentOrNull
    val text = (o["textBody"] as? JsonPrimitive)?.contentOrNull ?: ""
    val html = (o["htmlBody"] as? JsonPrimitive)?.contentOrNull
    return Vacation(
        enabled = enabled,
        from = from,
        to = to,
        subject = subject,
        text = text,
        html = html,
    )
}

/**
 * Every field, always, even the empty ones. Leaving a field out of a JMAP update means
 * "do not change it", so an omitted date would survive being cleared and the responder
 * would keep switching itself on at the start of a holiday that has already finished.
 */
internal fun vacationPatch(v: Vacation): JsonObject = buildJsonObject {
    put("isEnabled", JsonPrimitive(v.enabled))
    put("fromDate", v.from?.let { JsonPrimitive(it) } ?: JsonNull)
    put("toDate", v.to?.let { JsonPrimitive(it) } ?: JsonNull)
    put("subject", v.subject?.let { JsonPrimitive(it) } ?: JsonNull)
    put("textBody", JsonPrimitive(v.text))
    put("htmlBody", v.html?.let { JsonPrimitive(it) } ?: JsonNull)
}

/**
 * Null when this is safe to save, otherwise the one sentence to put in front of the user.
 *
 * A responder is the one setting nobody watches after they save it, so this is checked
 * before it can be turned on rather than left to be noticed by whoever gets the empty
 * reply.
 */
internal fun vacationProblem(v: Vacation): String? {
    if (v.enabled && v.text.isBlank()) {
        return "An auto-reply with no message in it will send an empty email."
    }

    val fromInstant = v.from?.let {
        runCatching { Instant.parse(it) }.getOrElse {
            return "That date could not be read."
        }
    }

    val toInstant = v.to?.let {
        runCatching { Instant.parse(it) }.getOrElse {
            return "That date could not be read."
        }
    }

    if (fromInstant != null && toInstant != null && !toInstant.isAfter(fromInstant)) {
        return "The end date has to be after the start date."
    }

    return null
}
