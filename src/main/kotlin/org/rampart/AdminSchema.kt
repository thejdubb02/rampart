package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Stalwart's management interface, read from the document it publishes at `/api/schema`.
 *
 * Nothing on an admin screen is written by hand. Stalwart 0.16 describes every object it
 * can manage (its fields, their types, which form groups them, which columns a list shows
 * and where each page sits in the menu) as one JSON document, and its own web console
 * draws itself from that. Reading the same document means one renderer covers every page,
 * including pages a later Stalwart adds, and a Stalwart release does not break us.
 *
 * The shape, as the 0.16 source writes it:
 *
 * - `objects` maps a name such as `x:Domain` to what it is. A `view` such as
 *   `x:Account/User` is a filtered window onto another object.
 * - `schemas` says whether an object has one set of fields (`single`) or several
 *   (`multiple`, chosen by the `@type` property on the value, as an account is a User or a
 *   Group).
 * - `fields` holds each field set's properties and their types; `forms` groups them into
 *   titled sections with labels; `lists` names the columns for a list page.
 * - `enums` holds the choices for every enum type, with labels.
 * - `layouts` are the menus. The first is Management.
 *
 * Kept as the parsed tree rather than mapped onto classes up front, for the same reason the
 * mail side reads JMAP that way: a field this version of Rampart has never heard of must
 * not stop the page it sits on from opening.
 */
internal class AdminSchema(private val root: JsonObject) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Throws [AdminError] with a sentence when the text is not a schema at all. */
        fun parse(text: String): AdminSchema {
            val tree = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw AdminError("The server's schema was not readable JSON.")
            if (tree["fields"] !is JsonObject || tree["objects"] !is JsonObject) {
                throw AdminError("The server's schema is missing its objects or fields, so it is not a Stalwart 0.16 schema.")
            }
            return AdminSchema(tree)
        }
    }

    private fun section(name: String): JsonObject = root[name] as? JsonObject ?: JsonObject(emptyMap())

    /** Every object and view the server described, by name. */
    val objectNames: Set<String> get() = section("objects").keys

    /**
     * What a menu entry or a list refers to, with a view resolved to the object behind it.
     * Null when the server does not describe the name at all.
     */
    fun objectFor(name: String): AdminObject? {
        val entry = section("objects")[name] as? JsonObject ?: return null
        val backing = if (entry.str("type") == "view") entry.str("objectName") ?: return null else name
        val real = section("objects")[backing] as? JsonObject ?: return null
        return AdminObject(
            viewName = name,
            objectName = backing,
            description = real.str("description").orEmpty(),
            permissionPrefix = real.str("permissionPrefix"),
        )
    }

    /** One set of fields, or a choice of several. Null when the server did not describe it. */
    fun shapeOf(objectName: String): AdminShape? {
        val entry = section("schemas")[objectName] as? JsonObject ?: return null
        return when (entry.str("type")) {
            "single" -> entry.str("schemaName")?.let { AdminShape.Single(it) }
            "multiple" -> AdminShape.Multiple(
                (entry["variants"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val v = element as? JsonObject ?: return@mapNotNull null
                    val variant = v.str("name") ?: return@mapNotNull null
                    AdminVariant(variant, v.str("label") ?: variant, v.str("schemaName"))
                },
            )
            else -> null
        }
    }

    /**
     * Which field set applies to [value], given the object it belongs to.
     *
     * A single shape always has the one. A multiple shape is chosen by the value's `@type`,
     * and a variant with no fields of its own (DKIM management set to Manual, say) answers
     * null, which the renderer draws as the choice alone.
     */
    fun fieldSetFor(objectName: String, value: JsonObject?): String? = when (val shape = shapeOf(objectName)) {
        is AdminShape.Single -> shape.schemaName
        is AdminShape.Multiple -> {
            val chosen = (value?.get("@type") as? JsonPrimitive)?.contentOrNull
            shape.variants.firstOrNull { it.name == chosen }?.schemaName
        }
        null -> null
    }

    /**
     * The form for a field set: its sections, in the server's order, with the server's
     * labels. Fields the form does not mention are left off, as the web console leaves
     * them off. A field set with no form at all gets every field in one section, sorted,
     * because an unlabelled page is better than a missing one.
     *
     * Enterprise-only fields are dropped on any other edition. The server would refuse
     * them anyway, and a field that can only ever be refused is noise.
     */
    fun form(fieldSet: String, enterprise: Boolean): AdminForm {
        val properties = (section("fields")[fieldSet] as? JsonObject)?.get("properties") as? JsonObject
            ?: return AdminForm(fieldSet, emptyList(), JsonObject(emptyMap()))
        fun field(name: String, label: String?, extras: JsonObject?): AdminField? {
            val p = properties[name] as? JsonObject ?: return null
            val enterpriseOnly = (p["enterprise"] as? JsonPrimitive)?.booleanOrNull == true
            if (enterpriseOnly && !enterprise) return null
            return AdminField(
                name = name,
                label = label ?: humanise(name),
                description = p.str("description").orEmpty(),
                type = fieldType(p["type"]),
                update = when (p.str("update")) {
                    "mutable" -> Update.MUTABLE
                    "immutable" -> Update.IMMUTABLE
                    else -> Update.SERVER_SET
                },
                placeholder = extras?.str("placeholder"),
            )
        }
        val formEntry = section("forms")[fieldSet] as? JsonObject
        val sections = if (formEntry != null) {
            (formEntry["sections"] as? JsonArray).orEmpty().mapNotNull { element ->
                val s = element as? JsonObject ?: return@mapNotNull null
                val fields = (s["fields"] as? JsonArray).orEmpty().mapNotNull { f ->
                    val o = f as? JsonObject ?: return@mapNotNull null
                    // The `@type` pseudo field is the variant chooser, which the renderer
                    // draws from the shape rather than as a property.
                    val name = o.str("name")?.takeIf { it != "@type" } ?: return@mapNotNull null
                    field(name, o.str("label"), o)
                }
                if (fields.isEmpty()) null else AdminSection(s.str("title"), fields)
            }
        } else {
            listOf(AdminSection(null, properties.keys.sorted().mapNotNull { field(it, null, null) }))
        }
        val defaults = (section("fields")[fieldSet] as? JsonObject)?.get("defaults") as? JsonObject
        return AdminForm(fieldSet, sections, defaults.orNone())
    }

    /** The choices for an enum, with labels. Empty when the server named an enum it did not describe. */
    fun choices(enumName: String): List<AdminChoice> =
        (section("enums")[enumName] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            AdminChoice(name, o.str("label") ?: name)
        }

    /** The list page for an object or view, or null when the server has none for it. */
    fun listFor(viewName: String): AdminList? {
        val o = section("lists")[viewName] as? JsonObject ?: return null
        return AdminList(
            title = o.str("title") ?: viewName,
            subtitle = o.str("subtitle").orEmpty(),
            labelProperty = o.str("labelProperty"),
            singular = o.str("singularName") ?: "item",
            plural = o.str("pluralName") ?: "items",
            columns = (o["columns"] as? JsonArray).orEmpty().mapNotNull { element ->
                val c = element as? JsonObject ?: return@mapNotNull null
                val name = c.str("name") ?: return@mapNotNull null
                AdminColumn(name, c.str("label") ?: humanise(name))
            },
            staticFilter = o["filtersStatic"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    /**
     * Every page link in a menu, flattened, with the group it sits under.
     *
     * The server nests containers inside containers (Emails, then History, then Inbound
     * Delivery). A group heading per top-level container is as deep as a sidebar needs,
     * so a nested container's name is folded into the label instead.
     */
    fun menu(layout: String = "Management"): List<AdminMenuEntry> {
        val tree = (root["layouts"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("name") == layout } ?: return emptyList()
        val out = ArrayList<AdminMenuEntry>()
        fun walk(items: JsonArray?, group: String, prefix: String) {
            items.orEmpty().forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                // Two spellings in the same document: a top-level item wraps itself in a
                // `link` or `container` key, a nested one carries `type` beside its fields.
                val link = item["link"] as? JsonObject ?: item.takeIf { it.str("type") == "link" }
                val box = item["container"] as? JsonObject ?: item.takeIf { it.str("type") == "container" }
                when {
                    link != null -> {
                        val view = link.str("viewName") ?: return@forEach
                        val name = link.str("name") ?: view
                        out += AdminMenuEntry(group, if (prefix.isEmpty()) name else "$prefix: $name", view)
                    }
                    box != null -> {
                        val name = box.str("name").orEmpty()
                        if (group.isEmpty()) walk(box["items"] as? JsonArray, name, "")
                        else walk(box["items"] as? JsonArray, group, if (prefix.isEmpty()) name else "$prefix: $name")
                    }
                }
            }
        }
        walk(tree["items"] as? JsonArray, "", "")
        return out
    }
}

/**
 * What a field holds. One case per type the 0.16 schema uses, plus [Unknown], which is the
 * one that matters most: a type added in a later Stalwart becomes a single line saying the
 * web console can edit it, rather than a page that will not open.
 */
internal sealed interface AdminType {
    val nullable: Boolean

    /** A string, by format: string, text, emailAddress, uri, ipAddress, secret and others. */
    data class Text(val format: String, override val nullable: Boolean, val maxLength: Int? = null) : AdminType {
        val secret: Boolean get() = format == "secret" || format == "secretText"
        val multiline: Boolean get() = format == "text" || format == "secretText" || format == "html"
    }

    /** A number, by format: integer, unsignedInteger, float, size (bytes) or duration (milliseconds). */
    data class Number(
        val format: String,
        override val nullable: Boolean,
        val min: Double? = null,
        val max: Double? = null,
    ) : AdminType

    data class Flag(override val nullable: Boolean) : AdminType

    data class Choice(val enumName: String, override val nullable: Boolean) : AdminType

    /** The id of another object, which the server checks exists. */
    data class Reference(val objectName: String, override val nullable: Boolean) : AdminType

    /** A set, sent as an object whose keys are the members, each mapped to true. */
    data class Many(val item: AdminType, val minItems: Int) : AdminType {
        override val nullable: Boolean get() = false
    }

    data class Nested(val objectName: String, override val nullable: Boolean) : AdminType

    /** A list of nested objects, sent as an object keyed "0", "1" and so on. */
    data class NestedList(val objectName: String, val minItems: Int) : AdminType {
        override val nullable: Boolean get() = false
    }

    data class Dictionary(val key: AdminType, val value: AdminType) : AdminType {
        override val nullable: Boolean get() = false
    }

    data class Timestamp(override val nullable: Boolean) : AdminType

    data class Blob(override val nullable: Boolean) : AdminType

    data class Unknown(val name: String, override val nullable: Boolean) : AdminType
}

internal enum class Update { MUTABLE, IMMUTABLE, SERVER_SET }

internal data class AdminField(
    val name: String,
    val label: String,
    val description: String,
    val type: AdminType,
    val update: Update,
    val placeholder: String? = null,
) {
    /** Whether this field can be changed on an object that already exists. */
    val editable: Boolean get() = update == Update.MUTABLE

    /** Whether it can be given a value when the object is first made. */
    val settableOnCreate: Boolean get() = update != Update.SERVER_SET
}

internal data class AdminSection(val title: String?, val fields: List<AdminField>)

internal data class AdminForm(val fieldSet: String, val sections: List<AdminSection>, val defaults: JsonObject) {
    val fields: List<AdminField> get() = sections.flatMap { it.fields }
}

internal data class AdminObject(
    val viewName: String,
    val objectName: String,
    val description: String,
    val permissionPrefix: String?,
)

internal sealed interface AdminShape {
    data class Single(val schemaName: String) : AdminShape
    data class Multiple(val variants: List<AdminVariant>) : AdminShape
}

internal data class AdminVariant(val name: String, val label: String, val schemaName: String?)

internal data class AdminChoice(val name: String, val label: String)

internal data class AdminColumn(val name: String, val label: String)

internal data class AdminList(
    val title: String,
    val subtitle: String,
    val labelProperty: String?,
    val singular: String,
    val plural: String,
    val columns: List<AdminColumn>,
    /** Conditions the server's own console always adds, such as `@type: User` for the accounts page. */
    val staticFilter: JsonObject,
)

internal data class AdminMenuEntry(val group: String, val label: String, val viewName: String)

internal fun fieldType(element: JsonElement?): AdminType {
    val t = element as? JsonObject ?: return AdminType.Unknown("missing", true)
    val nullable = (t["nullable"] as? JsonPrimitive)?.booleanOrNull == true
    val kind = t.str("type").orEmpty()
    return when (kind) {
        "string" -> AdminType.Text(t.str("format") ?: "string", nullable, (t["maxLength"] as? JsonPrimitive)?.intOrNull)
        "number" -> AdminType.Number(
            t.str("format") ?: "integer",
            nullable,
            (t["min"] as? JsonPrimitive)?.doubleOrNull,
            (t["max"] as? JsonPrimitive)?.doubleOrNull,
        )
        "boolean" -> AdminType.Flag(nullable)
        "enum" -> t.str("enumName")?.let { AdminType.Choice(it, nullable) } ?: AdminType.Unknown(kind, nullable)
        "objectId" -> AdminType.Reference(t.str("objectName").orEmpty(), nullable)
        "set" -> AdminType.Many(fieldType(t["class"]), (t["minItems"] as? JsonPrimitive)?.intOrNull ?: 0)
        "object" -> t.str("objectName")?.let { AdminType.Nested(it, nullable) } ?: AdminType.Unknown(kind, nullable)
        "objectList" -> t.str("objectName")?.let { AdminType.NestedList(it, (t["minItems"] as? JsonPrimitive)?.intOrNull ?: 0) }
            ?: AdminType.Unknown(kind, nullable)
        "map" -> AdminType.Dictionary(fieldType(t["keyClass"]), fieldType(t["valueClass"]))
        "utcDateTime" -> AdminType.Timestamp(nullable)
        "blobId" -> AdminType.Blob(nullable)
        else -> AdminType.Unknown(kind.ifEmpty { "missing" }, nullable)
    }
}

/**
 * The account's own permissions, from `GET /api/account`.
 *
 * The menu is gated on these rather than on the schema. The schema describes everything the
 * server can do, not what this credential may do, and a menu built from it alone is a menu
 * of pages that answer 403.
 */
internal data class AdminAccess(val permissions: Set<String>, val edition: String) {
    val enterprise: Boolean get() = edition == "enterprise"

    /** [verb] is Get, Query, Create, Update or Destroy, appended to the object's prefix as the server names them. */
    fun can(obj: AdminObject, verb: String): Boolean {
        val prefix = obj.permissionPrefix ?: return false
        return "$prefix$verb" in permissions
    }

    /** A list page needs both: Query finds the ids and Get reads them. */
    fun canList(obj: AdminObject): Boolean = can(obj, "Query") && can(obj, "Get")

    companion object {
        fun parse(text: String): AdminAccess {
            val tree = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw AdminError("The server's answer about this account's permissions was not readable.")
            val permissions = (tree["permissions"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .toSet()
            return AdminAccess(permissions, tree.str("edition") ?: "oss")
        }
    }
}

/** Anything the admin side could not do, as one sentence a person can read. */
internal class AdminError(message: String) : Exception(message)

/** "catchAllAddress" becomes "Catch all address", for a field the form gave no label. */
internal fun humanise(name: String): String {
    val words = name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase()
    return words.replaceFirstChar { it.uppercase() }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject?.orNone(): JsonObject = this ?: JsonObject(emptyMap())
