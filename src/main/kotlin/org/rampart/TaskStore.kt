package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.w3c.dom.Element
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/*
 * Where a task is kept on the server, and the two ways Rampart gets it there.
 *
 * A task has to reach the person's phone, so it is kept on their own mail server, as the
 * signed in user, over the same credential the mail uses. Never an admin token: this is a
 * mail feature, and CLAUDE.md is plain that a mail feature never asks for admin rights.
 *
 * - JMAP Tasks, when the session advertises [TASKS_CAPABILITY]. Stalwart 0.16 does not.
 * - Stalwart's own CalDAV at /dav/cal, otherwise. A VTODO PUT into one of the person's
 *   calendars is what every phone's reminders and tasks app syncs, through DAVx5 on
 *   Android or the built in CalDAV account on an iPhone, which is how the Your phone page
 *   in Settings already sets them up.
 *
 * What Stalwart's CalDAV does, read from its source (crates/dav, at the commit this was
 * written against, af37a23):
 *
 * - A path is /dav/cal/{account}/{calendar}/{resource}. The account segment is the login's
 *   email, or an underscore and a numeric id (`validate_uri_with_status` in
 *   crates/dav/src/common/uri.rs). Rampart uses the login name the session signed in with.
 * - A calendar with no supported-calendar-component-set of its own takes every component,
 *   VTODO included (the property is answered as all components when the stored set is
 *   empty, in crates/dav/src/common/propfind.rs). The default calendar is created that way.
 * - A PUT must hold exactly one UID and one kind of component (`validate_ical` in
 *   crates/dav/src/calendar/update.rs). A new resource answers 201 Created.
 * - calendar-query understands a comp-filter on VTODO (crates/dav/src/calendar/query.rs).
 *
 * Everything that builds a request or reads an answer is a plain function here, tested in
 * TasksTest without a server. The transports are the only parts that talk.
 */

/** A task list, or for CalDAV a calendar that takes tasks. [id] is the JMAP id or the calendar's path. */
internal data class TaskListInfo(val id: String, val name: String, val isDefault: Boolean = false)

/** The server side of tasks, whichever protocol it speaks. Every method blocks and throws [JmapError] with a sentence. */
internal interface TaskBackend {
    /** How the card says where the task went, such as "your task list" or "the calendar Personal". */
    val protocol: String

    fun lists(): List<TaskListInfo>

    /** Writes [draft] into [listId]. Returns what the server calls it. */
    fun create(draft: TaskDraft, listId: String): String

    /** Tasks not yet done, across every list, soonest due first and undated last. */
    fun open(zone: ZoneId): List<TaskItem>
}

/** The list a new task goes into: the one marked default, else the first. */
internal fun defaultList(lists: List<TaskListInfo>): TaskListInfo? = lists.firstOrNull { it.isDefault } ?: lists.firstOrNull()

/** Soonest first, undated at the end, then by title, which is how a to-do list is read. */
internal fun sortedTasks(items: List<TaskItem>): List<TaskItem> =
    items.sortedWith(compareBy<TaskItem>({ it.due.isBlank() }, { it.due }, { it.title.lowercase() }))

// ---- JMAP Tasks ----------------------------------------------------------------------

/**
 * Tasks over JMAP, through [send], which is the session's own round trip with
 * [TASKS_CAPABILITY] named in `using`.
 *
 * Untried against a live server: Stalwart 0.16 does not offer it, and this is written from
 * the draft (`spec/tasks/task.mdown` in jmapio/jmap), where Task/get, Task/set and
 * Task/query are CalendarEvent's methods with a TaskList in place of a Calendar. So it asks
 * for as little as it can, and every refusal is a sentence.
 */
internal class JmapTasks(
    private val accountId: String,
    private val send: (List<JsonArray>) -> List<JsonArray>,
) : TaskBackend {
    override val protocol: String = "JMAP Tasks"

    override fun lists(): List<TaskListInfo> {
        val answer = send(listOf(taskCall("TaskList/get", "l", accountId) { put("ids", JsonNull) }))[0]
        return listIn(answer).mapNotNull { o ->
            val id = o.text("id") ?: return@mapNotNull null
            TaskListInfo(id, o.text("name") ?: "Tasks", (o["isDefault"] as? JsonPrimitive)?.booleanOrNull == true)
        }
    }

    override fun create(draft: TaskDraft, listId: String): String {
        val answer = send(listOf(taskCreateCall(accountId, draft, listId)))[0]
        return createdTaskId(answer)
    }

    override fun open(zone: ZoneId): List<TaskItem> {
        val answer = send(
            listOf(
                taskCall("Task/get", "g", accountId) {
                    put("ids", JsonNull)
                    putJsonArray("properties") { listOf("id", "uid", "title", "due", "progress", "showWithoutTime").forEach { add(it) } }
                },
            ),
        )[0]
        return sortedTasks(jmapTaskItems(answer).filter { !it.done })
    }
}

/** The Task/set that creates one task, under the creation id "t". */
internal fun taskCreateCall(accountId: String, draft: TaskDraft, listId: String): JsonArray =
    taskCall("Task/set", "s", accountId) {
        putJsonObject("create") { put("t", jmapTask(draft, listId)) }
    }

/** The server's id for the task a [taskCreateCall] made, or the refusal as a sentence. */
internal fun createdTaskId(setResponse: JsonArray): String {
    val body = setResponse.getOrNull(1) as? JsonObject ?: throw JmapError("The server's reply about the task was empty.")
    ((body["created"] as? JsonObject)?.get("t") as? JsonObject)?.text("id")?.let { return it }
    val refused = (body["notCreated"] as? JsonObject)?.get("t") as? JsonObject
    if (refused != null) throw JmapError(setErrorSentence(refused, "the task"))
    throw JmapError("The server did not say whether it saved the task.")
}

/** The tasks in a Task/get answer, as the panel lists them. */
internal fun jmapTaskItems(getResponse: JsonArray): List<TaskItem> = listIn(getResponse).map { o ->
    val due = o.text("due").orEmpty()
    val dateOnly = (o["showWithoutTime"] as? JsonPrimitive)?.booleanOrNull == true
    TaskItem(
        uid = o.text("uid") ?: o.text("id").orEmpty(),
        title = o.text("title") ?: "(no title)",
        due = when {
            due.isBlank() -> ""
            dateOnly -> due.take(10)
            else -> due.take(16).replace('T', ' ')
        },
        done = o.text("progress")?.lowercase() in setOf("completed", "cancelled", "failed"),
    )
}

private fun taskCall(name: String, callId: String, accountId: String, args: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonArray =
    buildJsonArray {
        add(name)
        add(buildJsonObject { put("accountId", accountId); args() })
        add(callId)
    }

private fun listIn(getResponse: JsonArray): List<JsonObject> =
    ((getResponse.getOrNull(1) as? JsonObject)?.get("list") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.ifBlank { null }

// ---- CalDAV ----------------------------------------------------------------------------

/** One answer from the server's WebDAV tree. */
internal data class DavReply(val status: Int, val body: String)

/**
 * What [CalDavTasks] needs from the session: its login name, and one signed request to the
 * same server. [Jmap] is the real one; the tests answer as Stalwart would.
 */
internal interface DavTransport {
    /** The name the session signed in with, which Stalwart takes as the account segment of a DAV path. */
    val user: String

    /** [path] is absolute on the server, starting /dav/. */
    fun send(method: String, path: String, body: String?, headers: Map<String, String>): DavReply
}

/** The account's calendar home on Stalwart, with the login name encoded as one path segment. */
internal fun calendarHome(user: String): String = "/dav/cal/" + pathSegment(user) + "/"

/** A string as one percent encoded path segment. URLEncoder is for forms, so its + for a space is put right. */
internal fun pathSegment(text: String): String =
    URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20").replace("%40", "@")

/** Every calendar in the home, with its name and the components it takes. Depth 1. */
internal val CALENDARS_PROPFIND: String = """
    <?xml version="1.0" encoding="utf-8"?>
    <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
      <d:prop>
        <d:resourcetype/>
        <d:displayname/>
        <c:supported-calendar-component-set/>
      </d:prop>
    </d:propfind>
""".trimIndent()

/** Every VTODO in one calendar, with its data. Depth 1. Done ones are left out here rather than by the server, which is simpler to trust. */
internal val TODO_REPORT: String = """
    <?xml version="1.0" encoding="utf-8"?>
    <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
      <d:prop>
        <c:calendar-data/>
      </d:prop>
      <c:filter>
        <c:comp-filter name="VCALENDAR">
          <c:comp-filter name="VTODO"/>
        </c:comp-filter>
      </c:filter>
    </c:calendar-query>
""".trimIndent()

private const val DAV = "DAV:"
private const val CALDAV = "urn:ietf:params:xml:ns:caldav"

/**
 * The calendars in a PROPFIND answer that will take a task.
 *
 * A collection counts when its resourcetype says calendar and its component set either
 * names VTODO or is absent, which RFC 4791 section 5.2.3 says means every component. The
 * home itself is not a calendar and is left out by the same test.
 */
internal fun taskCalendarsIn(multistatus: String): List<TaskListInfo> =
    responsesIn(multistatus).mapNotNull { response ->
        val href = response.first(DAV, "href")?.textContent?.trim()?.ifBlank { null } ?: return@mapNotNull null
        val props = response.all(DAV, "propstat")
            .filter { stat -> stat.first(DAV, "status")?.textContent?.contains(" 200") != false }
            .mapNotNull { it.first(DAV, "prop") }
        val isCalendar = props.any { p -> p.first(DAV, "resourcetype")?.first(CALDAV, "calendar") != null }
        if (!isCalendar) return@mapNotNull null
        val set = props.firstNotNullOfOrNull { it.first(CALDAV, "supported-calendar-component-set") }
        val comps = set?.all(CALDAV, "comp")?.map { it.getAttribute("name").uppercase() }.orEmpty()
        if (set != null && comps.isNotEmpty() && "VTODO" !in comps) return@mapNotNull null
        val name = props.firstNotNullOfOrNull { it.first(DAV, "displayname")?.textContent?.trim()?.ifBlank { null } }
            ?: href.trimEnd('/').substringAfterLast('/')
        TaskListInfo(id = hrefPath(href), name = name, isDefault = href.trimEnd('/').endsWith("/default"))
    }

/** The calendar-data of every response in a REPORT answer. */
internal fun calendarDataIn(multistatus: String): List<String> =
    responsesIn(multistatus).mapNotNull { it.first(CALDAV, "calendar-data")?.textContent }

/** An href as a path on the server. Stalwart answers with paths; a full URL is cut down to one. */
private fun hrefPath(href: String): String =
    if (href.startsWith("http://") || href.startsWith("https://")) runCatching { URI.create(href).rawPath }.getOrDefault(href) else href

/**
 * The response elements of a multistatus, read with a parser that will not fetch or expand
 * anything. The server is the person's own, but a reply is still input, and an XML parser
 * left at its defaults will follow an external entity wherever it points.
 */
private fun responsesIn(xml: String): List<Element> {
    if (xml.isBlank()) return emptyList()
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    }
    val document = runCatching { factory.newDocumentBuilder().parse(InputSource(StringReader(xml))) }.getOrNull()
        ?: throw JmapError("The server's calendar answer was not something Rampart could read.")
    return document.documentElement.all(DAV, "response")
}

private fun Element.all(ns: String, name: String): List<Element> {
    val nodes = getElementsByTagNameNS(ns, name)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

private fun Element.first(ns: String, name: String): Element? = all(ns, name).firstOrNull()

/**
 * Tasks as VTODOs in the person's own Stalwart calendars, over [dav].
 *
 * Signed with the mail session's own credential, so it can reach exactly what the person's
 * own phone can and nothing else.
 */
internal class CalDavTasks(private val dav: DavTransport, private val now: () -> Instant = Instant::now) : TaskBackend {
    override val protocol: String = "CalDAV"

    override fun lists(): List<TaskListInfo> {
        val reply = dav.send(
            "PROPFIND",
            calendarHome(dav.user),
            CALENDARS_PROPFIND,
            mapOf("Depth" to "1", "Content-Type" to "application/xml; charset=utf-8"),
        )
        if (reply.status != 207) throw JmapError(davSentence(reply.status, "read your calendars"))
        return taskCalendarsIn(reply.body)
    }

    override fun create(draft: TaskDraft, listId: String): String {
        val path = listId.trimEnd('/') + "/" + pathSegment(draft.uid) + ".ics"
        val reply = dav.send(
            "PUT",
            path,
            vtodo(draft, now()),
            // If-None-Match makes a create a create: it can never overwrite a task that
            // happens to have the same name.
            mapOf("Content-Type" to "text/calendar; charset=utf-8", "If-None-Match" to "*"),
        )
        if (reply.status !in setOf(200, 201, 204)) throw JmapError(davSentence(reply.status, "save the task"))
        return path
    }

    override fun open(zone: ZoneId): List<TaskItem> {
        val items = lists().flatMap { list ->
            val reply = dav.send(
                "REPORT",
                list.id,
                TODO_REPORT,
                mapOf("Depth" to "1", "Content-Type" to "application/xml; charset=utf-8"),
            )
            if (reply.status != 207) throw JmapError(davSentence(reply.status, "read your tasks"))
            calendarDataIn(reply.body).flatMap { todosIn(it, zone) }
        }
        return sortedTasks(items.filter { !it.done }.distinctBy { it.uid.ifBlank { it.title } })
    }
}

/** An HTTP status from the DAV tree as a sentence about what could not be done. */
internal fun davSentence(status: Int, doing: String): String = when (status) {
    401 -> "The server did not accept this account's password, so Rampart could not $doing."
    403 -> "This account is not allowed to $doing on its server."
    404 -> "The server has no calendar there, so Rampart could not $doing."
    409, 412 -> "The server already holds a task by that name, so Rampart could not $doing."
    413, 507 -> "The server has no room for it, so Rampart could not $doing."
    else -> "The server answered HTTP $status, so Rampart could not $doing."
}
