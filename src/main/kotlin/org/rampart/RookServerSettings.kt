package org.rampart

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * The Stalwart server's own settings, read from the schema it publishes at `/api/schema`.
 *
 * Every field of every object the server describes is in the map, so a question about any
 * of them can be answered with the server's own description. Reading a value and changing
 * one are narrower, and deliberately no wider than the admin console: only the objects in
 * [AdminWritableObjects], only with the separate admin sign-in, only where `/api/account`
 * says that sign-in may, and only through [AdminClient], whose every request is checked by
 * [adminMethodRefusal]. This file adds no way round any of those.
 */

/**
 * What the server side can reach, supplied from the admin console's own connection.
 *
 * An interface so the tests can use the schema fixture and a fake server. The live one is a
 * thin wrapper over [AdminClient], so every call it makes passes the same gates the console's
 * do.
 */
internal interface AdminReach {
    val schema: AdminSchema
    val access: AdminAccess

    /** A page of objects of this type, with these properties, for listing instances. */
    fun rows(obj: AdminObject, properties: List<String>): List<JsonObject>

    fun one(obj: AdminObject, id: String): JsonObject

    fun save(obj: AdminObject, id: String, changes: JsonObject): AdminSaved
}

/** Which object and field an entry is, kept beside the entry rather than parsed back out of its id. */
private data class ServerField(val obj: AdminObject, val field: AdminField)

private const val NO_ADMIN =
    "There is no admin sign-in, so Rook cannot see the server's settings. Add one in Settings, Server admin."

internal class ServerPlace(private val reach: AdminReach?) : SettingPlace {
    private val fields: Map<String, ServerField> = reach?.let(::fieldsOf).orEmpty()

    private val entryList: List<SettingEntry> = if (reach == null) {
        listOf(
            SettingEntry(
                "server", "Stalwart server settings",
                "Every setting the Stalwart server publishes in its schema: domains, accounts, spam filtering, TLS, queues and the rest.",
                SettingKind.Summary, SettingHome.SERVER, "Settings, Server admin",
                "server stalwart admin spam threshold domain dkim tls queue",
            ),
        )
    } else {
        fields.map { (id, f) -> entryFor(id, f) }
    }

    override fun entries(): List<SettingEntry> = entryList

    /** An id with @ and an object id on the end names one domain's or one account's copy of the field. */
    override fun resolve(id: String): SettingEntry? {
        val base = id.substringBefore('@')
        val entry = entryList.firstOrNull { it.id == base } ?: return null
        val instance = id.substringAfter('@', "")
        if ('@' in id && !Regex("[A-Za-z0-9_-]{1,64}").matches(instance)) return null
        return if (instance.isEmpty()) entry else entry.copy(id = "$base@$instance")
    }

    override fun refusal(entry: SettingEntry): String? {
        val reach = reach ?: return NO_ADMIN
        val f = fields[entry.id.substringBefore('@')] ?: return "The server's schema has no setting called ${entry.id}."
        val short = f.obj.objectName.removePrefix("x:")
        return when {
            f.obj.objectName !in AdminWritableObjects ->
                "Rampart does not read or change $short settings from here. The server's own web console does."
            !reach.access.can(f.obj, "Update") -> "This admin sign-in is not allowed to change $short settings."
            f.field.update == Update.SERVER_SET -> "The server sets this itself."
            f.field.update == Update.IMMUTABLE -> "This can only be given a value when the ${short.lowercase()} is made."
            '@' !in entry.id -> "This is kept per ${short.lowercase()}. get_setting on ${entry.id} lists them, with the id to use for each."
            else -> null
        }
    }

    override fun current(entry: SettingEntry): JsonElement {
        val reach = reach ?: throw SettingTrouble(NO_ADMIN)
        val f = fields[entry.id.substringBefore('@')] ?: throw SettingTrouble("The server's schema has no setting called ${entry.id}.")
        readable(f)?.let { throw SettingTrouble(it) }
        val instance = entry.id.substringAfter('@', "")
        if (instance.isEmpty()) {
            throw SettingTrouble("This is kept per ${f.obj.objectName.removePrefix("x:").lowercase()}, so there is no single value.")
        }
        return try {
            reach.one(f.obj, instance)[f.field.name] ?: JsonNull
        } catch (e: AdminError) {
            throw SettingTrouble(e.message ?: "The server did not answer.")
        }
    }

    /**
     * For a field kept per domain or per account, each one with its value and the id that
     * names it. One request for all of them rather than one each, through the same paged
     * read the console's list pages use.
     */
    override fun more(entry: SettingEntry): String? {
        val reach = reach ?: return null
        val f = fields[entry.id.substringBefore('@')] ?: return null
        if ('@' in entry.id) return null
        readable(f)?.let { return it }
        val list = reach.schema.listFor(f.obj.viewName)
        val label = list?.labelProperty ?: "name"
        val rows = try {
            reach.rows(f.obj, listOf(label, f.field.name))
        } catch (e: AdminError) {
            return e.message
        }
        if (rows.isEmpty()) return "The server has none of these yet."
        val kind = entry.kind
        return "Kept per ${f.obj.objectName.removePrefix("x:").lowercase()}:\n" + rows.take(25).joinToString("\n") { row ->
            val id = (row["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val name = (row[label] as? JsonPrimitive)?.contentOrNull ?: id
            "${entry.id}@$id  $name: ${describeValue(kind, row[f.field.name])}"
        }
    }

    override fun group(entry: SettingEntry): String {
        val f = fields[entry.id.substringBefore('@')]
        return "server:${f?.obj?.objectName}@${entry.id.substringAfter('@', "")}"
    }

    override fun title(group: String): String {
        val obj = group.removePrefix("server:").substringBefore('@').removePrefix("x:")
        return "$obj ${group.substringAfter('@')}, on the server"
    }

    /**
     * Sends only what changed, through the console's own save.
     *
     * The object is read again first so the change is laid over what the server has now, and
     * [changedFields] then keeps only fields the form offers and this operation may set. A
     * field that does not apply to this object's kind (a Group has no password) drops out
     * there and is reported rather than sent.
     */
    override fun apply(changes: List<Pair<SettingEntry, JsonElement>>): String? {
        val reach = reach ?: return NO_ADMIN
        val first = changes.firstOrNull()?.first ?: return null
        val f = fields[first.id.substringBefore('@')] ?: return "The server's schema has no setting called ${first.id}."
        changes.forEach { (entry, _) -> refusal(entry)?.let { return it } }
        val instance = first.id.substringAfter('@', "")
        return try {
            val before = reach.one(f.obj, instance)
            val after = JsonObject(before + changes.associate { (entry, value) -> fields.getValue(entry.id.substringBefore('@')).field.name to value })
            val fieldSet = reach.schema.fieldSetFor(f.obj.objectName, before)
                ?: return "The server's schema does not say which fields this ${f.obj.objectName.removePrefix("x:").lowercase()} has."
            val form = reach.schema.form(fieldSet, reach.access.enterprise)
            val send = changedFields(form, before, after, creating = false)
            if (send.isEmpty()) return "That does not apply to this ${f.obj.objectName.removePrefix("x:").lowercase()}, so nothing was sent."
            reach.save(f.obj, instance, send).refusal
        } catch (e: AdminError) {
            e.message ?: "The server did not take the change."
        }
    }

    /** Null when the value may be read, otherwise why not. The same gates as the console's list pages. */
    private fun readable(f: ServerField): String? {
        val reach = reach ?: return NO_ADMIN
        val short = f.obj.objectName.removePrefix("x:")
        return when {
            f.obj.objectName !in AdminWritableObjects ->
                "Rampart does not read $short settings from the server, so this is described but not shown. The server's own web console shows it."
            !reach.access.canList(f.obj) -> "This admin sign-in is not allowed to read $short settings."
            else -> null
        }
    }

    private fun entryFor(id: String, f: ServerField): SettingEntry {
        val short = f.obj.objectName.removePrefix("x:")
        val choices = when (val t = f.field.type) {
            is AdminType.Choice -> reach?.schema?.choices(t.enumName).orEmpty()
            else -> emptyList()
        }
        val shown = f.obj.objectName in AdminWritableObjects
        return SettingEntry(
            id = id,
            name = "${humanise(short)}: ${f.field.label}",
            description = f.field.description.ifBlank { f.obj.description },
            kind = SettingKind.Server(f.field.type, choices),
            home = SettingHome.SERVER,
            page = if (shown) "Server admin" else "the server's own web console",
            keywords = "server stalwart admin ${short.lowercase()} ${f.field.name} ${f.obj.description}",
        )
    }
}

/**
 * Every field of every object the schema describes, by id.
 *
 * An object with several kinds (an account is a User or a Group) contributes the fields of
 * each, once. Views are skipped because they are windows onto an object already walked.
 */
private fun fieldsOf(reach: AdminReach): Map<String, ServerField> {
    val schema = reach.schema
    val out = LinkedHashMap<String, ServerField>()
    for (name in schema.objectNames.sorted()) {
        val obj = schema.objectFor(name) ?: continue
        if (obj.objectName != name) continue
        val sets = when (val shape = schema.shapeOf(name)) {
            is AdminShape.Single -> listOf(shape.schemaName)
            is AdminShape.Multiple -> shape.variants.mapNotNull { it.schemaName }
            null -> emptyList()
        }
        for (set in sets.distinct()) {
            for (field in schema.form(set, reach.access.enterprise).fields) {
                val id = "server.${name.removePrefix("x:")}.${field.name}"
                if (id !in out) out[id] = ServerField(obj, field)
            }
        }
    }
    return out
}
