package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The address book, as the server holds it.
 *
 * A list rather than an application. Enough to find somebody, correct a spelling, add the
 * person whose message is open, and have all of it turn up on the phone, which is the
 * whole reason for keeping contacts on the server rather than in a file here. The
 * contacts application proper is a later tier.
 */
@Composable
internal fun ContactsPane(
    contacts: List<Contact>,
    loading: Boolean,
    error: String?,
    /** Null on a server with no address book, which is what hides the editing entirely. */
    onSave: ((Contact) -> Unit)?,
    onDelete: (Contact) -> Unit,
    onWrite: (String) -> Unit,
    /**
     * Every address book on the account, not only the default one.
     *
     * A mailbox routinely has more than one and the second is rarely decorative: this
     * account's is "Trusted Senders", which decides what the server believes about a sender.
     * Reading only the default one showed those cards mixed in with everything else with
     * nothing saying where they came from, and saved every new card into the wrong book.
     */
    books: List<ContactBook> = emptyList(),
) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Contact?>(null) }
    var confirmDelete by remember { mutableStateOf<Contact?>(null) }
    // One book is not a choice, so the filter and the picker are simply absent then.
    val several = books.size > 1
    var book by remember { mutableStateOf<String?>(null) }
    // A book that disappears while its filter is on would otherwise leave an empty list
    // and no way back to the others.
    if (book != null && books.none { it.id == book }) book = null
    val shown = remember(contacts, query, book) {
        matching(contacts, query)
            .filter { book == null || book in it.bookIds }
            .sortedBy { it.label.lowercase() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Contacts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onSave != null) {
                Button(onClick = { editing = Contact() }) { Text("New contact") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (onSave == null) {
                "This server keeps no address book, so there is nothing to show here. " +
                    "Recipient autocomplete still works: it is built from your own mail."
            } else {
                "Kept on the server, so the same list is on your phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        if (onSave != null) {
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (onSave != null && several) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = book == null,
                    onClick = { book = null },
                    label = { Text("All") },
                )
                books.forEach { each ->
                    FilterChip(
                        selected = book == each.id,
                        onClick = { book = if (book == each.id) null else each.id },
                        label = { Text(each.name) },
                    )
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))
        when {
            loading && contacts.isEmpty() -> CircularProgressIndicator()
            onSave == null -> Unit
            shown.isEmpty() -> Text(
                if (contacts.isEmpty()) "No contacts yet." else "Nobody matches that.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { contact ->
                    ContactRow(
                        contact = contact,
                        // Only where there is more than one book and no filter narrowing
                        // it to one: naming the book on every row of a filtered list is
                        // the same word repeated down the screen.
                        book = if (several && book == null) {
                            books.filter { it.id in contact.bookIds }
                                .joinToString(", ") { it.name }
                        } else {
                            ""
                        },
                        onEdit = { editing = contact },
                        onDelete = { confirmDelete = contact },
                        onWrite = onWrite,
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { contact ->
        ContactEditor(
            contact = contact,
            // A new card opened while a book is filtered goes into that book, because
            // that is plainly what was meant by making it there.
            books = if (several) books else emptyList(),
            preferred = book,
            onCancel = { editing = null },
            onSave = {
                editing = null
                onSave?.invoke(it)
            },
        )
    }

    // Asked rather than undone, because a contact deleted on the server is gone from the
    // phone too and there is no Trash to fish it back out of.
    confirmDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${contact.label}?") },
            text = { Text("This removes them from the server's address book, everywhere it syncs.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onDelete(contact) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    /** Which address book they are in. Empty where naming it would say nothing. */
    book: String = "",
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onWrite: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(30.dp).height(30.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                contact.label.trim().take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                contact.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val under = listOfNotNull(
                contact.organisation.takeIf { it.isNotBlank() },
                contact.emails.firstOrNull()?.takeIf { it != contact.label },
                contact.phones.firstOrNull(),
                book.takeIf { it.isNotBlank() },
            ).joinToString("  ")
            if (under.isNotBlank()) {
                Text(
                    under,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        contact.emails.firstOrNull()?.let { address ->
            TextButton(onClick = { onWrite(address) }) {
                Text("Write", style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = onDelete) {
            Text("Delete", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * One contact, in a dialog.
 *
 * Several addresses and several numbers, one per line, because that is how people paste
 * them and it saves an add-another-row control that would be the largest thing on the
 * form. Everything the card holds that is not on here travels through untouched.
 */
@Composable
private fun ContactEditor(
    contact: Contact,
    onCancel: () -> Unit,
    onSave: (Contact) -> Unit,
    /** Empty where the account has only one, which is not a choice worth drawing. */
    books: List<ContactBook> = emptyList(),
    /** The book to put a new card in when nothing else says. */
    preferred: String? = null,
) {
    var name by remember(contact) { mutableStateOf(contact.name) }
    var emails by remember(contact) { mutableStateOf(contact.emails.joinToString("\n")) }
    var phones by remember(contact) { mutableStateOf(contact.phones.joinToString("\n")) }
    var organisation by remember(contact) { mutableStateOf(contact.organisation) }
    var note by remember(contact) { mutableStateOf(contact.note) }
    /*
     * An existing card keeps the books it is already in, and a new one starts in the
     * filtered book, or the default, or the first there is.
     *
     * Kept as a set because a card may legitimately be in several: JSContact says so, and
     * a picker that forced it to one would quietly take a contact out of a book somebody
     * else put it in.
     */
    var chosen by remember(contact) { mutableStateOf(booksFor(contact, books, preferred)) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (contact.id.isBlank()) "New contact" else "Edit contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = emails,
                    onValueChange = { emails = it },
                    label = { Text("Email, one per line") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phones,
                    onValueChange = { phones = it },
                    label = { Text("Phone, one per line") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = organisation,
                    onValueChange = { organisation = it },
                    label = { Text("Company") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (books.size > 1) {
                    Text(
                        "Address book",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        books.forEach { each ->
                            FilterChip(
                                selected = each.id in chosen,
                                // Never down to none: Stalwart refuses a card that belongs
                                // to no book, and the refusal arrives as a save that failed
                                // rather than as anything explaining why.
                                onClick = {
                                    chosen = if (each.id in chosen) {
                                        (chosen - each.id).ifEmpty { chosen }
                                    } else {
                                        chosen + each.id
                                    }
                                },
                                label = { Text(each.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        contact.copy(
                            name = name.trim(),
                            emails = emails.lines().map(String::trim).filter { it.isNotBlank() },
                            phones = phones.lines().map(String::trim).filter { it.isNotBlank() },
                            organisation = organisation.trim(),
                            note = note.trim(),
                            bookIds = chosen.toList().ifEmpty { contact.bookIds },
                        ),
                    )
                },
                enabled = name.isNotBlank() || emails.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
