package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/*
 * An invitation answered through the account's own calendar, on a server that keeps one.
 *
 * On Stalwart an answer is a change to the event, not an email. Setting this account's own
 * participant to accepted with `sendSchedulingMessages` makes the server write the iTIP
 * REPLY itself and send it to the organiser, and the meeting is in the calendar because the
 * change was made there. docs/invitations.md has what Stalwart 0.16 does with each request
 * and the source files it was read from.
 *
 * Everything in this file is the part with no screen, so it can be tested without a window
 * or a server. InvitationCalendarCard.kt draws it, and the email reply in InvitationReply.kt
 * stays the answer for IMAP, for servers without calendars, and for every case below where
 * the server would not or could not send one.
 */

/** What the account's calendar holds for the meeting an invitation is about. */
internal data class CalendarMatch(
    val eventId: String,
    val sequence: Int,
    /** The JSCalendar status, lowercased: confirmed, tentative, cancelled, or empty. */
    val status: String,
    /** True when this account organises the meeting. Stalwart works it out from the organiser's address. */
    val isOrigin: Boolean,
    /** The key of this account's own entry in participants, or null when it is not on the list. */
    val myParticipantId: String?,
    /** That entry's participationStatus, lowercased, or empty when it has none. */
    val myStatus: String,
    /**
     * True when that entry says its answers are sent by the client rather than the server.
     *
     * Rampart writes this on any event it adds to the calendar itself, because Stalwart only
     * sends answers for an event its own inbound scheduling put there: see [importedEvent].
     */
    val clientScheduled: Boolean,
    /** The event as the calendar page reads it, for the day to jump to. Null when it has no start. */
    val event: CalendarEvent?,
    val raw: JsonObject,
)

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

/**
 * The address a JSCalendar participant stands for, lowercased and without `mailto:`.
 *
 * Stalwart 0.16 writes `calendarAddress`, from the JSCalendar revision its calcard library
 * follows. RFC 8984 as first published wrote `sendTo` with an `imip` entry instead, and
 * `email` is on both, so all three are read and a server of either vintage is understood.
 */
internal fun participantAddress(participant: JsonObject): String {
    val raw = participant["calendarAddress"].text()
        ?: (participant["sendTo"] as? JsonObject)?.get("imip").text()
        ?: participant["email"].text()
        ?: return ""
    return raw.trim().removePrefix("mailto:").removePrefix("MAILTO:").trim().lowercase()
}

/** Reads one CalendarEvent/get object as a match, or null when it has no id. */
internal fun calendarMatchOf(o: JsonObject, addresses: Collection<String>): CalendarMatch? {
    val id = o["id"].text() ?: return null
    val mine = addresses.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    val participants = (o["participants"] as? JsonObject).orEmpty()
    val me = participants.entries.firstOrNull { (_, value) ->
        (value as? JsonObject)?.let { participantAddress(it) in mine } == true
    }
    val entry = me?.value as? JsonObject
    return CalendarMatch(
        eventId = id,
        sequence = (o["sequence"] as? JsonPrimitive)?.intOrNull ?: 0,
        status = o["status"].text().orEmpty().lowercase(),
        isOrigin = (o["isOrigin"] as? JsonPrimitive)?.booleanOrNull == true,
        myParticipantId = me?.key,
        myStatus = entry?.get("participationStatus").text().orEmpty().lowercase(),
        clientScheduled = entry?.get("scheduleAgent").text().equals("client", ignoreCase = true),
        event = calendarEventOf(o),
        raw = o,
    )
}

/**
 * The event carrying [uid] among [list], or null when there is none.
 *
 * A UID is compared exactly, because RFC 5545 makes it case sensitive. The query already
 * asked for this UID, and the check is repeated here because the fallback reads the whole
 * calendar and because a server that ignored the filter should not produce a wrong answer.
 */
internal fun matchFor(list: List<JsonObject>, uid: String, addresses: Collection<String>): CalendarMatch? =
    list.firstOrNull { it["uid"].text() == uid }?.let { calendarMatchOf(it, addresses) }

/** The participationStatus value JSCalendar uses for an answer. */
internal fun participationStatusOf(answer: Rsvp): String = answer.partstat.lowercase()

/** A key escaped for a JSON Pointer, as RFC 6901 says: the tilde first, then the slash. */
private fun pointerKey(key: String) = key.replace("~", "~0").replace("/", "~1")

/**
 * The patch that records an answer on the event, for CalendarEvent/set update.
 *
 * A path to the one property, not the whole participants map. Stalwart compares the event
 * before and after and refuses an attendee who changes anything the organiser owns, so the
 * smallest possible change is also the one least likely to be refused.
 */
internal fun participationPatch(participantId: String, answer: Rsvp): JsonObject = buildJsonObject {
    put("participants/${pointerKey(participantId)}/participationStatus", participationStatusOf(answer))
}

/** Properties a parsed invitation carries that a created event may not, because they belong to the server. */
private val SERVER_OWNED = setOf("id", "method", "isOrigin", "baseEventId", "calendarIds", "utcStart", "utcEnd", "blobId")

/**
 * The event CalendarEvent/parse made of the invitation, ready for CalendarEvent/set create.
 *
 * `method` is taken off because Stalwart treats it as immutable and refuses a create that
 * names it. The event goes into [calendarId], and this account's own participant, when it is
 * on the list, gets the answer and `scheduleAgent: client`.
 *
 * **Why client.** Stalwart sends an answer for an event only when its own inbound scheduling
 * put that event there: one created over JMAP has no schedule tag, so a later change with
 * `sendSchedulingMessages` goes through its organiser path, finds this account is not the
 * organiser, and sends nothing without saying so. Marking the participant as scheduled by the
 * client is what RFC 6638 says in that situation, and it is how Rampart knows next time that
 * an answer to this event has to go by email. An update from the organiser that Stalwart
 * merges later replaces the participant, and the event is then the server's to answer.
 */
internal fun importedEvent(parsed: JsonObject, calendarId: String, addresses: Collection<String>, answer: Rsvp?): JsonObject {
    val mine = addresses.map { it.trim().lowercase() }.toSet()
    return buildJsonObject {
        parsed.forEach { (key, value) ->
            if (key in SERVER_OWNED) return@forEach
            if (key == "participants" && value is JsonObject && answer != null) {
                putJsonObject("participants") {
                    value.forEach { (pid, participant) ->
                        val entry = participant as? JsonObject
                        if (entry != null && participantAddress(entry) in mine) {
                            put(
                                pid,
                                buildJsonObject {
                                    entry.forEach { (k, v) -> if (k != "participationStatus" && k != "scheduleAgent") put(k, v) }
                                    put("participationStatus", participationStatusOf(answer))
                                    put("scheduleAgent", "client")
                                },
                            )
                        } else {
                            put(pid, participant)
                        }
                    }
                }
            } else {
                put(key, value)
            }
        }
        putJsonObject("calendarIds") { put(calendarId, true) }
    }
}

/**
 * Properties an organiser's update may change, which are the ones copied from it.
 *
 * Everything else on the stored event, alerts and calendarIds among them, is this account's
 * own and stays as it is. `recurrenceRule` is here in both spellings for the reason
 * CalendarEvents.kt gives.
 */
private val ORGANISER_OWNED = listOf(
    "title", "description", "start", "duration", "timeZone", "showWithoutTime", "locations",
    "virtualLocations", "recurrenceRule", "recurrenceRules", "excludedRecurrenceRules",
    "recurrenceOverrides", "participants", "organizerCalendarAddress", "sequence", "status",
    "priority", "privacy", "links",
)

/**
 * The patch that brings a stored event up to the organiser's newer version of it.
 *
 * Each property is replaced whole, and one the organiser dropped is removed, which is what
 * Stalwart itself does when it merges an update that arrives by mail. The participants come
 * across as the organiser sent them, so an answer the organiser reset by moving the meeting
 * reads as unanswered again, which is the organiser asking the question again on purpose.
 */
internal fun organiserUpdatePatch(stored: JsonObject, parsed: JsonObject): JsonObject = buildJsonObject {
    ORGANISER_OWNED.forEach { key ->
        val next = parsed[key]
        when {
            next != null && next != stored[key] -> put(key, next)
            next == null && stored[key] != null && stored[key] !is JsonNull -> put(key, JsonNull)
        }
    }
}

/**
 * The recurrence id an occurrence is keyed by on [event], for the time an invitation names.
 *
 * Overrides are keyed by the local start in the event's own zone, so a RECURRENCE-ID sent in
 * UTC or in another zone is moved into that zone first. A date is kept as a date.
 */
internal fun recurrenceKey(at: EventTime, event: CalendarEvent): LocalDateTime? {
    val instant = at.instant ?: return null
    if (at.allDay) return instant.toLocalDate().atStartOfDay()
    val zone = event.timeZone ?: return instant.toLocalDateTime()
    return instant.withZoneSameInstant(zone).toLocalDateTime()
}

/** The day the calendar page should open on for this meeting, in the reader's zone. */
internal fun jumpDate(invitation: Invitation, match: CalendarMatch, viewer: ZoneId = ZoneId.systemDefault()): LocalDate? {
    val at = invitation.recurrenceId ?: invitation.starts
    val instant = at?.instant
    if (instant != null) {
        return if (at.allDay) instant.toLocalDate() else instant.withZoneSameInstant(viewer).toLocalDate()
    }
    val event = match.event ?: return null
    return if (event.allDay || event.timeZone == null) event.start.toLocalDate()
    else event.start.atZone(event.timeZone).withZoneSameInstant(viewer).toLocalDate()
}

/** What the answer buttons will do, decided before anything is sent. */
internal sealed interface AnswerPlan {
    /** Record the answer on the event and let Stalwart send the reply. */
    data class ServerReply(val eventId: String, val participantId: String, val outdated: Boolean) : AnswerPlan

    /** Record the answer on the event without sending, and send the reply by email. */
    data class RecordThenMail(val eventId: String, val participantId: String?, val changed: Boolean) : AnswerPlan

    /** Put the meeting in the default calendar with the answer on it, and send the reply by email. */
    data object AddThenMail : AnswerPlan

    /** Leave the calendar alone and send the reply by email, as on any other server. */
    data object MailOnly : AnswerPlan
}

/**
 * Whether the meeting is over, as Stalwart judges it for scheduling.
 *
 * Stalwart sends nothing for an event whose last occurrence has ended, and says so only in
 * its own log, so a repeating meeting is never judged over here: its range is the server's
 * to work out, and the reply falls to the server.
 */
internal fun meetingIsOver(invitation: Invitation, now: ZonedDateTime): Boolean {
    if (invitation.repeats.isNotBlank()) return false
    val end = invitation.ends?.instant ?: invitation.starts?.instant ?: return false
    return !end.isAfter(now)
}

/**
 * Which way an answer goes, from what the invitation says and what the calendar holds.
 *
 * Decided in one place so every case has a reason written next to it. The server sends the
 * reply only when it will actually send one: the event is in the calendar, this account is on
 * its guest list, the server is the one scheduling that participant, the answer changes
 * something, and the meeting is not over. Anything else goes by email, because an answer the
 * server silently does not send is worse than one sent the old way.
 */
internal fun planAnswer(invitation: Invitation, answer: Rsvp, match: CalendarMatch?, now: ZonedDateTime): AnswerPlan {
    val method = invitation.method.ifBlank { "REQUEST" }
    // Only a request is answered. The card offers no buttons on anything else, and this is
    // the backstop if one ever does.
    if (!method.equals("REQUEST", ignoreCase = true)) return AnswerPlan.MailOnly
    if (match == null) {
        // A declined meeting that was never in the calendar stays out of it.
        return if (answer == Rsvp.DECLINE) AnswerPlan.MailOnly else AnswerPlan.AddThenMail
    }
    // This account organises it, so there is nobody for the server to reply to.
    if (match.isOrigin) return AnswerPlan.MailOnly
    val pid = match.myParticipantId ?: return AnswerPlan.MailOnly
    val changed = match.myStatus != participationStatusOf(answer)
    return when {
        match.clientScheduled -> AnswerPlan.RecordThenMail(match.eventId, pid, changed)
        meetingIsOver(invitation, now) -> AnswerPlan.RecordThenMail(match.eventId, pid, changed)
        // The same answer again changes nothing on the event, so the server would send
        // nothing. Pressing the button again is asking for it to be sent again.
        !changed -> AnswerPlan.RecordThenMail(match.eventId, pid, changed = false)
        else -> AnswerPlan.ServerReply(match.eventId, pid, outdated = invitation.sequence > match.sequence)
    }
}

/** One thing the invitation card can offer to do to the calendar. */
internal enum class CalendarAction(val label: String) {
    SHOW("Show in calendar"),
    UPDATE("Update calendar"),
    REMOVE("Remove from calendar"),
    REMOVE_ONE("Remove this time"),
}

/** The sentence the card shows about the calendar, and what it offers to do. */
internal data class CalendarLine(val text: String, val actions: List<CalendarAction>)

private fun answeredText(status: String): String = when (status) {
    "accepted" -> " You accepted."
    "tentative" -> " You answered maybe."
    "declined" -> " You declined."
    else -> ""
}

/**
 * What the card says about the calendar, for an invitation and what the calendar holds.
 *
 * An update from the organiser is usually already applied by the time the message is read,
 * because Stalwart merges one that arrives from an authenticated sender. So a newer message
 * than the event is the exception, and it is offered as a button rather than done on open:
 * Rampart cannot tell as well as the server can whether the sender was who it says, and a
 * forged update moving somebody's meeting is exactly what that check is for.
 */
internal fun calendarLine(invitation: Invitation, match: CalendarMatch?): CalendarLine {
    val method = invitation.method.ifBlank { "REQUEST" }.uppercase()
    val one = invitation.recurrenceId != null
    return when (method) {
        "CANCEL" -> when {
            match == null -> CalendarLine("Not in your calendar, so there is nothing to remove.", emptyList())
            one -> CalendarLine("One time of this repeating meeting is called off. The rest stay in your calendar.", listOf(CalendarAction.SHOW, CalendarAction.REMOVE_ONE))
            match.status == "cancelled" -> CalendarLine("Marked cancelled in your calendar.", listOf(CalendarAction.SHOW, CalendarAction.REMOVE))
            else -> CalendarLine("Still in your calendar.", listOf(CalendarAction.SHOW, CalendarAction.REMOVE))
        }
        "REPLY" -> if (match == null) {
            CalendarLine("This meeting is not in your calendar.", emptyList())
        } else {
            CalendarLine("In your calendar.", listOf(CalendarAction.SHOW))
        }
        else -> when {
            match == null -> CalendarLine("Not in your calendar yet. Accept or Maybe adds it.", emptyList())
            // An update to one time of a series is a change inside the event's overrides,
            // and working out which override is a calendar's job, not a mail client's.
            one -> CalendarLine("In your calendar." + answeredText(match.myStatus), listOf(CalendarAction.SHOW))
            invitation.sequence > match.sequence -> CalendarLine(
                "Your calendar has an older version of this meeting than this message.",
                listOf(CalendarAction.SHOW, CalendarAction.UPDATE),
            )
            invitation.sequence < match.sequence -> CalendarLine(
                "Your calendar already has a newer version of this meeting than this message.",
                listOf(CalendarAction.SHOW),
            )
            else -> CalendarLine("In your calendar." + answeredText(match.myStatus), listOf(CalendarAction.SHOW))
        }
    }
}

/** A refused item of a /set, in words, and whether it was the server refusing to schedule. */
internal data class Refusal(val sentence: String, val forbidden: Boolean)

/**
 * The refusal for [id] in a CalendarEvent/set response, or null when it went through.
 *
 * `forbidden` is how Stalwart says scheduling is off on the server, or that this account may
 * not send scheduling messages, and it comes with a description saying which.
 */
internal fun refusalIn(response: JsonObject, id: String, done: String, refused: String, prefix: String): Refusal? {
    val ok = when (val field = response[done]) {
        is JsonObject -> field.containsKey(id)
        is JsonArray -> id in field.idList()
        else -> false
    }
    if (ok) return null
    val reason = (response[refused] as? JsonObject)?.get(id) as? JsonObject
    // Stalwart ends its descriptions with a full stop, and calendarRefusal adds one of its own.
    val sentence = calendarRefusal(buildJsonObject { reason?.let { put(refused, buildJsonObject { put(id, it) }) } }, refused, prefix)
    return Refusal(
        sentence = if (sentence.endsWith("..")) sentence.dropLast(1) else sentence,
        forbidden = reason?.get("type").text() == "forbidden",
    )
}

/**
 * The calendar calls an invitation needs, as the signed-in user.
 *
 * The same shape as [CalendarClient], and on the same [Jmap.request] path, with the mail
 * session's own credential: an answer to a meeting is the user's own business and never the
 * admin token's. [send] is the round trip, taken as a function so the tests can answer as a
 * server would.
 */
internal class InvitationCalendarClient(
    private val accountId: String,
    private val send: (List<JsonArray>) -> List<JsonArray>,
) {
    constructor(jmap: Jmap) : this(jmap.accountId, { calls -> jmap.request(CALENDARS, *calls.toTypedArray()) })

    private fun invocation(name: String, id: String, args: Map<String, JsonElement>): JsonArray = buildJsonArray {
        add(JsonPrimitive(name))
        add(JsonObject(mapOf("accountId" to JsonPrimitive(accountId)) + args))
        add(JsonPrimitive(id))
    }

    /** CalendarEvent/query by UID with the matching events fetched in the same round trip. */
    internal fun lookupCalls(uid: String): List<JsonArray> = listOf(
        invocation("CalendarEvent/query", "u", mapOf("filter" to buildJsonObject { put("uid", uid) })),
        invocation(
            "CalendarEvent/get",
            "g",
            mapOf(
                "#ids" to buildJsonObject {
                    put("resultOf", "u")
                    put("name", "CalendarEvent/query")
                    put("path", "/ids")
                },
            ),
        ),
    )

    /**
     * The event carrying [uid], or null when the calendar has none.
     *
     * By query first. A refused query falls back to reading every event and matching here,
     * the same fallback CalendarClient.events has and for the same reason: a server that
     * does not implement a filter should not make the answer impossible.
     */
    fun find(uid: String, addresses: Collection<String>): CalendarMatch? {
        if (uid.isBlank()) return null
        val list = try {
            send(lookupCalls(uid))[1][1].jsonObject["list"]
        } catch (first: JmapError) {
            try {
                send(listOf(invocation("CalendarEvent/get", "g", mapOf("ids" to JsonNull))))[0][1].jsonObject["list"]
            } catch (e: JmapError) {
                throw JmapError("Could not look for this meeting in your calendar: ${e.message}")
            }
        }
        return matchFor(list.objects(), uid, addresses)
    }

    internal fun parseCall(blobId: String): JsonArray =
        invocation("CalendarEvent/parse", "p", mapOf("blobIds" to buildJsonArray { add(JsonPrimitive(blobId)) }))

    /**
     * The invitation's calendar part as a JSCalendar event, read by the server.
     *
     * The server's own parser rather than this file's, because what it makes of the part is
     * exactly what it would have stored had it added the meeting itself, recurrence and time
     * zones included, and the small reader in Calendar.kt reads only what a card shows.
     */
    fun parse(blobId: String, uid: String): JsonObject {
        val response = try {
            send(listOf(parseCall(blobId)))[0][1].jsonObject
        } catch (e: JmapError) {
            throw JmapError("The server could not read the invitation: ${e.message}")
        }
        return parsedEvent(response, blobId, uid)
            ?: throw JmapError("The server could not read the invitation: there is no event in it.")
    }

    /** The account's default calendar, or the first it may write to when none is marked. */
    fun defaultCalendar(): CalendarInfo? {
        val list = try {
            send(listOf(invocation("Calendar/get", "c", mapOf("ids" to JsonNull))))[0][1].jsonObject["list"]
        } catch (e: JmapError) {
            throw JmapError("Could not read your calendars: ${e.message}")
        }
        return pickDefault(list.objects().mapNotNull(::calendarInfoOf))
    }

    internal fun setCall(args: Map<String, JsonElement>, schedule: Boolean): JsonArray =
        invocation("CalendarEvent/set", "s", args + ("sendSchedulingMessages" to JsonPrimitive(schedule)))

    /** Creates [event], sending nothing, and returns its id. */
    fun create(event: JsonObject): String {
        val response = set(setCall(mapOf("create" to buildJsonObject { put("invite", event) }), schedule = false))
        return ((response["created"] as? JsonObject)?.get("invite") as? JsonObject)?.get("id").text()
            ?: throw JmapError(calendarRefusal(response, "notCreated", "The server would not add the meeting to your calendar"))
    }

    /** Changes the event, and asks the server to send what that change means when [schedule] is true. */
    fun update(eventId: String, patch: JsonObject, schedule: Boolean): Refusal? {
        val response = set(setCall(mapOf("update" to buildJsonObject { put(eventId, patch) }), schedule))
        return refusalIn(response, eventId, "updated", "notUpdated", "The server would not change the event")
    }

    /**
     * Removes the event, sending nothing.
     *
     * Never with scheduling: for an attendee Stalwart turns a scheduled delete into a
     * decline, and the organiser who cancelled the meeting has no use for one.
     */
    fun remove(eventId: String): Refusal? {
        val response = set(setCall(mapOf("destroy" to buildJsonArray { add(JsonPrimitive(eventId)) }), schedule = false))
        return refusalIn(response, eventId, "destroyed", "notDestroyed", "The server would not remove the event")
    }

    private fun set(call: JsonArray): JsonObject = try {
        send(listOf(call))[0][1].jsonObject
    } catch (e: JmapError) {
        throw JmapError("The calendar did not save: ${e.message}")
    }
}

/**
 * The event for [uid] in a CalendarEvent/parse response.
 *
 * `parsed` maps each blob id to a list of events, one per UID in the file. An invitation
 * normally holds one; when it holds more, the one with the invitation's own UID is it.
 */
internal fun parsedEvent(response: JsonObject, blobId: String, uid: String): JsonObject? {
    val parsed = (response["parsed"] as? JsonObject)?.get(blobId)
    val events = when (parsed) {
        is JsonArray -> parsed.objects()
        is JsonObject -> listOf(parsed)
        else -> emptyList()
    }
    return events.firstOrNull { it["uid"].text() == uid } ?: events.firstOrNull()
}

/** The calendar a new event goes into: the default, else the first writable one. */
internal fun pickDefault(calendars: List<CalendarInfo>): CalendarInfo? =
    calendars.firstOrNull { it.isDefault && it.mayWrite } ?: calendars.firstOrNull { it.mayWrite }

/**
 * How an answer went on the server's side.
 *
 * [replySent] true means the server sent the reply and no email is needed. Otherwise the
 * email reply goes as it always has, and [note] with [detail] say what the server did not do,
 * when that is something the reader should know.
 */
internal data class CalendarAnswer(val replySent: Boolean, val note: String? = null, val detail: String? = null)

/**
 * Answers [invitation] through the calendar, as far as the server will take it.
 *
 * Blocking: call it off the window's thread. Never throws a [JmapError]: every failure
 * becomes a [CalendarAnswer] that falls back to email and says why in one sentence, so a
 * server that refuses is never the reason an organiser hears nothing.
 */
internal fun answerInCalendar(
    client: InvitationCalendarClient?,
    invitation: Invitation,
    answer: Rsvp,
    addresses: Collection<String>,
    calendarBlobId: String?,
    now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),
): CalendarAnswer {
    client ?: return CalendarAnswer(replySent = false)
    val match = try {
        client.find(invitation.uid, addresses)
    } catch (e: JmapError) {
        return CalendarAnswer(false, "Your calendar could not be checked, so the answer went by email and the calendar is unchanged.", e.message)
    }
    return when (val plan = planAnswer(invitation, answer, match, now)) {
        AnswerPlan.MailOnly -> CalendarAnswer(false)
        AnswerPlan.AddThenMail -> {
            if (calendarBlobId == null) {
                return CalendarAnswer(
                    false,
                    "The answer went by email, but the meeting could not be added to your calendar.",
                    "Rampart could not find the invitation's calendar part in this message.",
                )
            }
            try {
                val parsed = client.parse(calendarBlobId, invitation.uid)
                val calendar = client.defaultCalendar() ?: throw JmapError("This account has no calendar Rampart may write to.")
                client.create(importedEvent(parsed, calendar.id, addresses, answer))
                CalendarAnswer(false)
            } catch (e: JmapError) {
                CalendarAnswer(false, "The answer went by email, but the meeting could not be added to your calendar.", e.message)
            }
        }
        is AnswerPlan.RecordThenMail -> {
            if (plan.participantId == null || !plan.changed) return CalendarAnswer(false)
            val refused = try {
                client.update(plan.eventId, participationPatch(plan.participantId, answer), schedule = false)
            } catch (e: JmapError) {
                Refusal(e.message.orEmpty(), forbidden = false)
            }
            if (refused == null) CalendarAnswer(false)
            else CalendarAnswer(false, "The answer went by email, but your calendar still shows the old one.", refused.sentence)
        }
        is AnswerPlan.ServerReply -> serverReply(client, invitation, answer, addresses, calendarBlobId, plan)
    }
}

/**
 * The answer as a change to the event, with the server sending the reply.
 *
 * When the calendar holds an older version than the message, the organiser's version goes in
 * first, so the reply answers the meeting as it now is. That replaces the participants, so
 * this account's own entry is looked up again before the answer is written to it.
 */
private fun serverReply(
    client: InvitationCalendarClient,
    invitation: Invitation,
    answer: Rsvp,
    addresses: Collection<String>,
    calendarBlobId: String?,
    plan: AnswerPlan.ServerReply,
): CalendarAnswer {
    var participant = plan.participantId
    if (plan.outdated && calendarBlobId != null) {
        try {
            val stored = client.find(invitation.uid, addresses)
            val parsed = client.parse(calendarBlobId, invitation.uid)
            if (stored != null) {
                client.update(plan.eventId, organiserUpdatePatch(stored.raw, parsed), schedule = false)
                participant = client.find(invitation.uid, addresses)?.myParticipantId ?: participant
            }
        } catch (_: JmapError) {
            // The answer still matters more than the update. It is sent against the version
            // the calendar has, and the card goes on offering the update.
        }
    }
    val patch = participationPatch(participant, answer)
    val refused = try {
        client.update(plan.eventId, patch, schedule = true)
    } catch (e: JmapError) {
        Refusal(e.message.orEmpty(), forbidden = false)
    } ?: return CalendarAnswer(replySent = true)
    // The server would not send it. When that was a refusal to schedule, the answer is still
    // written to the calendar without scheduling; any other refusal was of the change itself,
    // and asking again would only be refused again. The email reply goes as it always did.
    val recorded = refused.forbidden && try {
        client.update(plan.eventId, patch, schedule = false) == null
    } catch (_: JmapError) {
        false
    }
    val note = if (refused.forbidden) {
        "The server would not send the answer, so it went by email instead."
    } else {
        "The calendar would not take the answer, so it went by email instead."
    }
    return CalendarAnswer(false, note, refused.sentence + if (recorded) "" else " Your calendar still shows the old answer.")
}
