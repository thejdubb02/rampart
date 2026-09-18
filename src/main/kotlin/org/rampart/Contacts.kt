package org.rampart

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A person in the server's address book.
 *
 * Deliberately smaller than what the server can hold. JSContact (RFC 9553) describes
 * around forty properties, and a client that renders all of them is a contacts application
 * rather than a mail client with contacts in it. Rampart reads the whole card and writes
 * back only what it showed, so nothing it does not understand is destroyed: see [merged].
 */
internal data class Contact(
    val id: String = "",
    val name: String = "",
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val organisation: String = "",
    val note: String = "",
    /** The address books it belongs to. Kept so a save does not move it out of them. */
    val bookIds: List<String> = emptyList(),
    /**
     * Their picture, as the card states it: usually a `data:` URI, occasionally a URL.
     *
     * Read but never fetched. A vCard photo has traditionally been carried inside the card,
     * which costs nobody anything; a photo stated as a URL is somebody else's server and is
     * treated like any other remote picture, which is to say left alone.
     */
    val photo: String = "",
) {
    /** What the list and the autocomplete show. An address is better than an empty line. */
    val label: String get() = name.ifBlank { emails.firstOrNull().orEmpty() }
}

/** One address book on the server. */
internal data class ContactBook(val id: String, val name: String, val isDefault: Boolean)

/**
 * A card as it arrived, read down to the parts Rampart shows.
 *
 * Every collection in JSContact is a map from an arbitrary key to an object rather than a
 * list, because the keys are how a later patch refers to one entry without sending the
 * rest. The keys carry no meaning of their own, so they are dropped on the way in and
 * regenerated on the way out.
 */
internal fun contactOf(card: JsonObject): Contact = Contact(
    id = card["id"]?.jsonPrimitive?.content.orEmpty(),
    name = card["name"]?.jsonObject?.get("full")?.jsonPrimitive?.content.orEmpty(),
    emails = valuesOf(card, "emails", "address"),
    phones = valuesOf(card, "phones", "number"),
    organisation = valuesOf(card, "organizations", "name").firstOrNull().orEmpty(),
    note = valuesOf(card, "notes", "note").firstOrNull().orEmpty(),
    bookIds = (card["addressBookIds"] as? JsonObject)?.keys?.toList().orEmpty(),
    photo = valuesOf(card, "photos", "uri").firstOrNull().orEmpty(),
)

private fun valuesOf(card: JsonObject, group: String, field: String): List<String> =
    (card[group] as? JsonObject)?.values.orEmpty()
        .mapNotNull { (it as? JsonObject)?.get(field)?.jsonPrimitive?.content?.trim() }
        .filter { it.isNotBlank() }

/**
 * The card to send back, built on top of the one that was read.
 *
 * The starting point is [original] rather than an empty object, so a birthday, a photo or
 * an anniversary put there by a phone survives being edited here. Only the groups Rampart
 * draws are replaced, and a group emptied here is removed rather than left holding what it
 * used to say.
 */
internal fun merged(contact: Contact, original: JsonObject? = null): JsonObject = buildJsonObject {
    original?.forEach { (key, value) ->
        // id is the server's, and it refuses one inside a create.
        if (key !in REPLACED && key != "id") put(key, value)
    }
    put("@type", "Card")
    put("version", original?.get("version")?.jsonPrimitive?.content ?: "1.0")
    putJsonObject("name") { put("full", contact.name) }
    group("emails", contact.emails, "address")
    group("phones", contact.phones, "number")
    group("organizations", listOfNotNull(contact.organisation.takeIf { it.isNotBlank() }), "name")
    group("notes", listOfNotNull(contact.note.takeIf { it.isNotBlank() }), "note")
    if (contact.bookIds.isNotEmpty()) {
        putJsonObject("addressBookIds") { contact.bookIds.forEach { put(it, true) } }
    }
}

private val REPLACED = setOf("@type", "version", "name", "emails", "phones", "organizations", "notes", "addressBookIds")

private fun JsonObjectBuilder.group(name: String, values: List<String>, field: String) {
    val kept = values.map(String::trim).filter { it.isNotBlank() }
    if (kept.isEmpty()) return
    putJsonObject(name) {
        kept.forEachIndexed { i, value ->
            putJsonObject(name.first() + (i + 1).toString()) { put(field, value) }
        }
    }
}

/**
 * The server's contacts folded into the book that already drives autocomplete.
 *
 * Not a second list beside it. Somebody typing a recipient does not care which of the two
 * sources knows the address, and two ranked lists shown one after the other is the shape
 * that makes people stop reading either. [noted] is reused rather than reimplemented, so
 * the merge rule is the same one mail history already goes through: a sighting with no
 * name never wipes a name already on file.
 *
 * Seen at zero on purpose. A contact is evidence that the address is real, not evidence
 * that it was written to today, and dating them now would push everyone actually
 * corresponded with down the list.
 */
internal fun withContacts(book: List<Person>, contacts: List<Contact>): List<Person> {
    var merged = book
    contacts.forEach { contact ->
        contact.emails.forEach { email ->
            merged = noted(merged, email, contact.name, at = 0)
        }
    }
    return merged
}

/** Contacts whose name, address, organisation or note contains [query], ignoring case. */
internal fun matching(contacts: List<Contact>, query: String): List<Contact> {
    val needle = query.trim()
    if (needle.isBlank()) return contacts
    return contacts.filter { contact ->
        (contact.name + " " + contact.organisation + " " + contact.note + " " +
            contact.emails.joinToString(" ") + " " + contact.phones.joinToString(" "))
            .contains(needle, ignoreCase = true)
    }
}
