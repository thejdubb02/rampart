package org.rampart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/*
 * The composer's side: Sign and Encrypt on the message, and everything the writer needs to
 * know before pressing Send with them on.
 *
 * Three rules shape it:
 *
 * - **A missing key is said by name, before Send.** The same check runs again at send time
 *   (SealedSend.kt) and refuses there too, so this is for the writer's benefit, not the only
 *   guard.
 * - **Looking a key up on the network is announced while it happens.** It only happens here,
 *   for a recipient on a message with encryption on, and the line says which address is being
 *   asked about and where. A key found that way is offered, not used: pressing Use is what
 *   adds it.
 * - **The subject is not hidden, and the page says so** whenever encryption is on, because
 *   everybody's first guess is that it is.
 */

/** Sign and Encrypt, for the row of options under the composer's title bar. */
@Composable
internal fun SealToggles(draft: Draft, enabled: Boolean, onChange: (Draft) -> Unit) {
    val on = MaterialTheme.colorScheme.primary
    TextButton(enabled = enabled, onClick = { onChange(draft.copy(sign = !draft.sign)) }) {
        Text(if (draft.sign) "Signed" else "Sign", maxLines = 1, color = if (draft.sign) on else Color.Unspecified)
    }
    TextButton(enabled = enabled, onClick = { onChange(draft.copy(encrypt = !draft.encrypt)) }) {
        Text(if (draft.encrypt) "Encrypted" else "Encrypt", maxLines = 1, color = if (draft.encrypt) on else Color.Unspecified)
    }
}

/** An address complete enough to be worth asking a directory about. */
private fun lookupable(email: String): Boolean {
    val at = email.lastIndexOf('@')
    return at > 0 && email.indexOf('.', at) > at + 1 && !email.endsWith(".")
}

/**
 * What protecting this message will do, what is missing, and the key lookups. Nothing at all
 * while neither switch is on.
 *
 * [contactCards] are the account's address book entries as the server sent them, which is
 * where a key is looked for first, without any network request.
 */
@Composable
internal fun SealNotes(draft: Draft, contactCards: List<JsonObject> = emptyList()) {
    if (!draft.sign && !draft.encrypt) return
    val ring = Keys.ring
    var version by remember { mutableStateOf(0) }
    val recipients = draft.recipients
    val plan = remember(draft.from, recipients, draft.sign, draft.encrypt, version) {
        runCatching { planSeal(ring, draft.from, recipients, draft.sign, draft.encrypt) }
    }
    val kind = remember(draft.from, version) { ring.ownKey(draft.from)?.kind ?: KeyKind.OPENPGP }
    val missing = remember(recipients, kind, draft.encrypt, version) {
        if (draft.encrypt) missingKeys(recipients) { ring.publicFor(it, kind) != null } else emptyList()
    }
    val looking = remember { mutableStateMapOf<String, Boolean>() }
    val found = remember { mutableStateMapOf<String, KeyLookup.Found>() }
    var lookupFailure by remember { mutableStateOf<String?>(null) }

    // The address book first: a key inside a contact card costs no request.
    LaunchedEffect(missing, contactCards) {
        if (missing.isEmpty() || contactCards.isEmpty()) return@LaunchedEffect
        val added = withContext(Dispatchers.IO) {
            missing.sumOf { email ->
                contactCards.filter { card -> cardHas(card, email) }
                    .flatMap { KeyLookup.keysInCard(it) }
                    .sumOf { bytes -> keepContactKey(ring, bytes, email) }
            }
        }
        if (added > 0) version++
    }

    // Then the network, OpenPGP only, once per address, after the typing has settled.
    val askable = missing.filter { kind == KeyKind.OPENPGP && lookupable(it) && it.lowercase() !in found }
    LaunchedEffect(askable) {
        if (askable.isEmpty()) return@LaunchedEffect
        delay(1_500)
        for (email in askable) {
            looking[email.lowercase()] = true
            val result = withContext(Dispatchers.IO) {
                runCatching { KeyLookup.lookUp(email) }.getOrElse { KeyLookup.Found(emptyList(), null, "The lookup for $email failed: ${whyFailed(it).removeSuffix(".")}.") }
            }
            looking.remove(email.lowercase())
            found[email.lowercase()] = result
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        val quiet = MaterialTheme.colorScheme.outline
        if (draft.encrypt) {
            Text(
                "Encryption hides the message and its attachments. The subject line, the addresses and the date are not hidden.",
                style = MaterialTheme.typography.bodySmall,
                color = quiet,
            )
            Text(
                "Drafts saved while you write are kept on your server unencrypted, as ordinary drafts.",
                style = MaterialTheme.typography.bodySmall,
                color = quiet,
            )
        }
        plan.getOrNull()?.let { p ->
            val what = listOfNotNull("signed".takeIf { draft.sign }, "encrypted".takeIf { draft.encrypt }).joinToString(" and ")
            val scheme = when (p.scheme) {
                SealScheme.PGP_MIME -> "OpenPGP"
                SealScheme.PGP_INLINE -> "inline OpenPGP"
                SealScheme.SMIME -> "S/MIME"
            }
            Text(
                "This message will be $what with $scheme, using your key ${Pgp.short(p.own.fingerprint)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        plan.exceptionOrNull()?.let { FaultText(it.message ?: "This message cannot be protected as asked.") }
        // Your own key, when it has a passphrase nobody has typed yet this session.
        plan.getOrNull()?.own?.takeIf { own -> ring.locked().any { it.fingerprint == own.fingerprint } }?.let { own ->
            KeyUnlock(own) { version++ }
        }
        missing.forEach { email ->
            val key = email.lowercase()
            when {
                looking[key] == true -> Text(
                    "Looking up a key for $email on their own domain and on keys.openpgp.org, because encryption is on. Only the address is sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = quiet,
                )
                found[key]?.rings?.isNotEmpty() == true -> {
                    val result = found.getValue(key)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${result.note} Fingerprint ${groupFingerprint(Pgp.fingerprintOf(result.rings.first()))}.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            runCatching {
                                result.rings.forEach { ring.addPgpPublic(it.encoded, result.source ?: KeySource.VKS, trusted = true) }
                            }.onSuccess { version++ }.onFailure { lookupFailure = whyFailed(it) }
                        }) { Text("Use this key") }
                    }
                }
                found[key] != null -> Text(found.getValue(key).note, style = MaterialTheme.typography.bodySmall, color = quiet)
            }
        }
        if (missing.isNotEmpty() && kind == KeyKind.OPENPGP && missing.all { it.lowercase() in found }) {
            TextButton(onClick = { missing.forEach { found.remove(it.lowercase()) } }) { Text("Look up again") }
        }
        if (missing.isNotEmpty() && kind == KeyKind.SMIME) {
            Text(
                "S/MIME certificates are not looked up on the network. Ask for a signed message from them and keep the certificate it carries.",
                style = MaterialTheme.typography.bodySmall,
                color = quiet,
            )
        }
        lookupFailure?.let { FaultText(it) }
    }
}

private fun cardHas(card: JsonObject, email: String): Boolean {
    val emails = card["emails"] as? JsonObject ?: return false
    return emails.values.any { entry ->
        ((entry as? JsonObject)?.get("address") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.equals(email, ignoreCase = true) == true
    }
}

/** Keeps a key from a contact card when it names [email]. Returns how many were kept. */
private fun keepContactKey(ring: Keyring, bytes: ByteArray, email: String): Int {
    runCatching {
        return KeyLookup.forAddress(Pgp.publicRings(bytes), email).sumOf { ring.addPgpPublic(it.encoded, KeySource.CONTACT).size }
    }
    return runCatching {
        val entry = ring.addCertificate(bytes, KeySource.CONTACT)
        if (entry.emails.any { it.equals(email, ignoreCase = true) }) 1 else { ring.remove(entry.fingerprint); 0 }
    }.getOrDefault(0)
}

/** A fingerprint in groups of four, which is how people read one out to each other. */
internal fun groupFingerprint(fingerprint: String): String = fingerprint.chunked(4).joinToString(" ")
