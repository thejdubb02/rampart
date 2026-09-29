package org.rampart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path

/**
 * Settings, Encryption: your keys, other people's keys, the two options, and the server's
 * own encryption at rest.
 *
 * Everything but the last section works on its own, on any account, IMAP included: keys are
 * on this computer and signing and encryption happen here. The last section is Stalwart's,
 * set as the signed-in person, and is one sentence on any other account.
 */
@Composable
internal fun EncryptionPage(
    session: Session?,
    identities: List<Identity>,
    accounts: List<AccountMailboxes> = emptyList(),
    chosen: String? = null,
    onChoose: (String) -> Unit = {},
) {
    val ring = Keys.ring
    var version by remember { mutableStateOf(0) }
    val index = remember(version) { runCatching { ring.index() }.getOrDefault(KeyIndex()) }
    val refresh: () -> Unit = { version++ }

    Section(
        "Encryption",
        "Sign and encrypt mail with OpenPGP or S/MIME. Your secret keys are kept in this computer's " +
            "credential store, never in a file of Rampart's, and never leave this computer unless you export them.",
    )
    Secrets.unavailableReason()?.let {
        FaultText("Secret keys cannot be kept on this computer: ${it.removeSuffix(".")}. You can still keep other people's public keys.")
        Spacer(Modifier.height(10.dp))
    }

    YourKeys(index, identities, refresh)
    Divided()
    MakeKey(identities, refresh)
    Divided()
    ImportKey(refresh)
    Divided()
    TheirKeys(index, session, refresh)
    Divided()
    Options(index, session, refresh)
    Divided()
    ServerAtRest(session, index, accounts, chosen, onChoose)
}

@Composable
private fun Divided() {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

/** A one line outcome: quiet when it worked, the error colour when it did not. */
@Composable
private fun Settled(text: String?, worked: Boolean) {
    text ?: return
    Spacer(Modifier.height(6.dp))
    if (worked) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) else FaultText(text)
}

private fun describe(entry: KeyEntry): String = buildString {
    append(entry.kind.label)
    if (entry.testingOnly) append(", test certificate only")
    if (entry.needsPassphrase) append(", protected by a passphrase")
    append(". ").append(entry.source.label)
    if (entry.added.isNotBlank()) append(", ").append(entry.added)
    append(".")
}

private fun chooseFile(title: String, save: Boolean, name: String = ""): Path? {
    val dialog = FileDialog(null as Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (name.isNotBlank()) dialog.file = name
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return Path.of(dialog.directory ?: ".", file)
}

@Composable
private fun YourKeys(index: KeyIndex, identities: List<Identity>, onChanged: () -> Unit) {
    val ring = Keys.ring
    val clipboard = LocalClipboardManager.current
    var outcome by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    val mine = index.keys.filter { it.hasSecret }

    Section("Your keys", "The keys you sign with and read encrypted mail with.")
    if (mine.isEmpty()) Quiet("None yet. Make one below, or import one you already use.")
    mine.forEach { entry ->
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(entry.name.ifBlank { entry.emails.firstOrNull().orEmpty() }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            SelectionContainer {
                Text(groupFingerprint(entry.fingerprint), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Quiet(describe(entry))
            val using = identities.filter { index.identityKeys[it.email.lowercase()] == entry.fingerprint }.map { it.email }
            if (using.isNotEmpty()) Quiet("Used for ${using.joinToString(", ")}.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(entry.publicKey))
                    outcome = "The public key is on the clipboard. It is safe to share." to true
                }) { Text("Copy public key") }
                identities.filter { id -> id.email.lowercase() in entry.emails && index.identityKeys[id.email.lowercase()] != entry.fingerprint }
                    .forEach { id ->
                        TextButton(onClick = {
                            runCatching { ring.setIdentityKey(id.email, entry.fingerprint) }
                                .onSuccess { onChanged() }
                                .onFailure { outcome = whyFailed(it) to false }
                        }) { Text("Use for ${id.email}") }
                    }
                TextButton(onClick = { exporting = entry.fingerprint; removing = null }) { Text("Export secret key") }
                TextButton(onClick = { removing = entry.fingerprint; exporting = null }) { Text("Remove") }
            }
            if (exporting == entry.fingerprint) {
                FaultText("Anybody with the exported file can read your encrypted mail and sign as you. Keep it somewhere only you can reach.")
                Row {
                    Button(onClick = {
                        exporting = null
                        val target = chooseFile(
                            "Export secret key",
                            save = true,
                            name = if (entry.kind == KeyKind.OPENPGP) "secret-key.asc" else "certificate.p12",
                        ) ?: return@Button
                        outcome = runCatching { Files.write(target, ring.exportSecret(entry.fingerprint, confirmed = true)) }
                            .fold({ "Exported to $target." to true }, { whyFailed(it) to false })
                    }) { Text("Export it") }
                    TextButton(onClick = { exporting = null }) { Text("Cancel") }
                }
            }
            if (removing == entry.fingerprint) {
                FaultText("Mail encrypted to this key cannot be read here once it is removed, unless you import it again.")
                Row {
                    Button(onClick = {
                        removing = null
                        runCatching { ring.remove(entry.fingerprint) }.onSuccess { onChanged() }.onFailure { outcome = whyFailed(it) to false }
                    }) { Text("Remove it") }
                    TextButton(onClick = { removing = null }) { Text("Keep it") }
                }
            }
        }
    }
    Settled(outcome?.first, outcome?.second == true)
}

@Composable
private fun MakeKey(identities: List<Identity>, onChanged: () -> Unit) {
    val ring = Keys.ring
    val scope = rememberCoroutineScope()
    var identity by remember(identities) { mutableStateOf(identities.firstOrNull()) }
    var algorithm by remember { mutableStateOf(PgpAlgorithm.CURVE25519) }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    Section("Make a key", "A new OpenPGP key for one of your addresses. It works with Thunderbird, Proton, GnuPG and anything else that speaks OpenPGP.")
    if (identities.isEmpty()) {
        Quiet("Sign in to an account first, and its addresses will be offered here.")
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        identities.forEach { id ->
            Row(Modifier.clickable { identity = id }.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = identity == id, onClick = { identity = id })
                Text(id.email, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PgpAlgorithm.entries.forEach { option ->
            Row(Modifier.clickable { algorithm = option }.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = algorithm == option, onClick = { algorithm = option })
                Text(option.label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    OutlinedTextField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = { Text("Passphrase (optional)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Quiet("Without one, the key is protected by this computer's credential store alone. With one, it is asked for once each time Rampart starts, and never written down.")
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && identity != null, onClick = {
            val id = identity ?: return@Button
            busy = true
            scope.launch {
                val made = withContext(Dispatchers.Default) {
                    runCatching { ring.generatePgp(id.name, id.email, algorithm, passphrase.takeIf { it.isNotEmpty() }?.toCharArray()) }
                }
                busy = false
                passphrase = ""
                outcome = made.fold({ "Made a key for ${id.email}: ${groupFingerprint(it.fingerprint)}." to true }, { whyFailed(it) to false })
                onChanged()
            }
        }) { Text(if (busy) "Making it" else "Make OpenPGP key") }
        TextButton(enabled = !busy && identity != null, onClick = {
            val id = identity ?: return@TextButton
            busy = true
            scope.launch {
                val made = withContext(Dispatchers.Default) { runCatching { ring.makeTestCertificate(id.name, id.email) } }
                busy = false
                outcome = made.fold(
                    { "Made a test S/MIME certificate for ${id.email}. Nobody else's mail program will trust it: use one from a certificate authority for real mail." to true },
                    { whyFailed(it) to false },
                )
                onChanged()
            }
        }) { Text("Make a test S/MIME certificate") }
    }
    Settled(outcome?.first, outcome?.second == true)
}

@Composable
private fun ImportKey(onChanged: () -> Unit) {
    val ring = Keys.ring
    var pasted by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    Section("Import a key", "Paste an OpenPGP key, yours or somebody else's, or choose a certificate file (.p12 or .pfx) from a certificate authority.")
    OutlinedTextField(
        value = pasted,
        onValueChange = { pasted = it },
        label = { Text("-----BEGIN PGP PUBLIC KEY BLOCK----- or PRIVATE KEY BLOCK") },
        minLines = 3,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = pasted.isNotBlank(), onClick = {
            val bytes = pasted.toByteArray()
            outcome = runCatching {
                if (pasted.contains("PRIVATE KEY BLOCK")) {
                    val entry = ring.importPgpSecret(bytes)
                    "Imported your key ${groupFingerprint(entry.fingerprint)}." +
                        if (entry.needsPassphrase) " It keeps its passphrase, which is asked for when the key is first used." else ""
                } else {
                    val added = ring.addPgpPublic(bytes, KeySource.IMPORTED)
                    "Imported ${added.size} public key${if (added.size == 1) "" else "s"}: ${added.joinToString(", ") { it.emails.firstOrNull() ?: it.name }}."
                }
            }.fold({ it to true }, { whyFailed(it) to false })
            pasted = ""
            onChanged()
        }) { Text("Import pasted key") }
        TextButton(onClick = {
            val file = chooseFile("Choose a key or certificate file", save = false) ?: return@TextButton
            outcome = runCatching {
                val bytes = Files.readAllBytes(file)
                val name = file.fileName.toString().lowercase()
                when {
                    name.endsWith(".p12") || name.endsWith(".pfx") -> {
                        val entry = ring.importPkcs12(bytes, password.toCharArray())
                        "Imported the certificate for ${entry.emails.joinToString(", ").ifBlank { entry.name }}."
                    }
                    name.endsWith(".pem") || name.endsWith(".crt") || name.endsWith(".cer") -> {
                        val entry = ring.addCertificate(bytes, KeySource.IMPORTED)
                        "Imported ${entry.name}'s certificate."
                    }
                    String(bytes, Charsets.US_ASCII).contains("PRIVATE KEY BLOCK") -> {
                        val entry = ring.importPgpSecret(bytes)
                        "Imported your key ${groupFingerprint(entry.fingerprint)}."
                    }
                    else -> {
                        val added = ring.addPgpPublic(bytes, KeySource.IMPORTED)
                        "Imported ${added.size} public key${if (added.size == 1) "" else "s"}."
                    }
                }
            }.fold({ it to true }, { whyFailed(it) to false })
            password = ""
            onChanged()
        }) { Text("Choose a file") }
    }
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password for a .p12 or .pfx file") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Quiet("The password is checked and not kept. It is asked for again when the certificate is first used after Rampart starts.")
    Settled(outcome?.first, outcome?.second == true)
}

@Composable
private fun TheirKeys(index: KeyIndex, session: Session?, onChanged: () -> Unit) {
    val ring = Keys.ring
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var looking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<KeyLookup.Found?>(null) }
    var outcome by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val theirs = index.keys.filter { !it.hasSecret }

    Section("Other people's keys", "What you encrypt to, and what signatures are checked against.")
    if (theirs.isEmpty()) Quiet("None yet.")
    theirs.forEach { entry ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.emails.joinToString(", ").ifBlank { entry.name }, style = MaterialTheme.typography.bodyMedium)
                Text(groupFingerprint(entry.fingerprint), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Quiet(describe(entry) + if (entry.trusted) " Trusted." else " Not trusted: signatures by it show in red and mail is not encrypted to it.")
            }
            TextButton(onClick = {
                runCatching { ring.setTrusted(entry.fingerprint, !entry.trusted) }.onSuccess { onChanged() }.onFailure { outcome = whyFailed(it) to false }
            }) { Text(if (entry.trusted) "Stop trusting" else "Trust") }
            TextButton(onClick = {
                runCatching { ring.remove(entry.fingerprint) }.onSuccess { onChanged() }.onFailure { outcome = whyFailed(it) to false }
            }) { Text("Remove") }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; result = null },
            label = { Text("Look up a key by address") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(enabled = !looking && address.contains('@'), onClick = {
            looking = true
            scope.launch {
                result = withContext(Dispatchers.IO) {
                    runCatching { KeyLookup.lookUp(address.trim()) }
                        .getOrElse { KeyLookup.Found(emptyList(), null, "The lookup failed: ${whyFailed(it).removeSuffix(".")}.") }
                }
                looking = false
            }
        }) { Text(if (looking) "Looking" else "Look up") }
    }
    Quiet("Asks the address's own domain (Web Key Directory) and then keys.openpgp.org, over https, only when you press Look up. Only the address is sent.")
    result?.let { found ->
        Spacer(Modifier.height(6.dp))
        if (found.rings.isEmpty()) {
            Quiet(found.note)
        } else {
            Text("${found.note} Fingerprint ${groupFingerprint(Pgp.fingerprintOf(found.rings.first()))}.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                outcome = runCatching { found.rings.flatMap { ring.addPgpPublic(it.encoded, found.source ?: KeySource.VKS) } }
                    .fold({ "Kept the key for ${address.trim()}." to true }, { whyFailed(it) to false })
                result = null
                onChanged()
            }) { Text("Keep this key") }
        }
    }
    val backend = session?.jmap
    if (backend != null) {
        TextButton(onClick = {
            scope.launch {
                outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        if (!backend.hasContacts()) throw CryptoFailure("This account has no address book on the server.")
                        backend.contacts().sumOf { (_, card) ->
                            KeyLookup.keysInCard(card).sumOf { bytes ->
                                runCatching { ring.addPgpPublic(bytes, KeySource.CONTACT).size }
                                    .recoverCatching { ring.addCertificate(bytes, KeySource.CONTACT); 1 }
                                    .getOrDefault(0)
                            }
                        }
                    }.fold({ "Found $it key${if (it == 1) "" else "s"} in your address book." to true }, { whyFailed(it) to false })
                }
                onChanged()
            }
        }) { Text("Take keys from your address book") }
    }
    Settled(outcome?.first, outcome?.second == true)
}

@Composable
private fun Options(index: KeyIndex, session: Session?, onChanged: () -> Unit) {
    val ring = Keys.ring
    var outcome by remember { mutableStateOf<String?>(null) }
    val storeEncrypted = remember(session) { session?.store?.encrypted == true }

    Section("Options", "How encrypted mail is handled on this computer.")
    Row(
        Modifier.fillMaxWidth().clickable {
            outcome = runCatching { setSearch(ring, session, !index.searchInside) }.exceptionOrNull()?.let(::whyFailed)
            onChanged()
        }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = index.searchInside, onCheckedChange = {
            outcome = runCatching { setSearch(ring, session, it) }.exceptionOrNull()?.let(::whyFailed)
            onChanged()
        })
        Spacer(Modifier.width(12.dp))
        Text("Search inside encrypted mail", style = MaterialTheme.typography.bodyMedium)
    }
    Quiet(
        "Off unless you turn it on. When on, a message you open and decrypt is added to the search index kept on this computer, " +
            "which is itself encrypted. It is never sent to the server's search.",
    )
    if (index.searchInside && !storeEncrypted) {
        FaultText("This account has no encrypted local copy on this computer, so nothing is indexed until it has one.")
    }
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().clickable { runCatching { ring.setInlinePgp(!index.inlinePgp) }; onChanged() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = index.inlinePgp, onCheckedChange = { runCatching { ring.setInlinePgp(it) }; onChanged() })
        Spacer(Modifier.width(12.dp))
        Text("Send OpenPGP inline instead of PGP/MIME", style = MaterialTheme.typography.bodyMedium)
    }
    Quiet("Only for correspondents on very old mail programs. Inline cannot carry formatting or attachments, so such a message is refused rather than sent partly unprotected.")
    outcome?.let { FaultText(it) }
}

private fun setSearch(ring: Keyring, session: Session?, on: Boolean) {
    ring.setSearchInside(on)
    // Turning it off takes back out what was put in, rather than leaving it for the next clean.
    if (!on) session?.store?.forgetDecrypted()
}

/**
 * Stalwart's encryption at rest, as the signed-in person. See EncryptionAtRest.kt and
 * `docs/encryption.md`.
 */
@Composable
private fun ServerAtRest(
    session: Session?,
    index: KeyIndex,
    accounts: List<AccountMailboxes>,
    chosen: String?,
    onChoose: (String) -> Unit,
) {
    val jmap = session?.jmap as? Jmap
    Section(
        "Encrypted on the server",
        "Your mail server can encrypt every new message it stores for you to your own public key, so the copy on its disk " +
            "is unreadable without your private key.",
    )
    SettingsAccountPicker(accounts, chosen, onChoose)
    val unavailable = restUnavailable(session?.account?.protocol, jmap?.managementAccountId)
    if (session == null || jmap == null || unavailable != null) {
        Quiet(unavailable ?: "This account cannot be managed from here.")
        return
    }
    val rest = remember(session.key, jmap) { EncryptionAtRest(jmap) }
    val scope = rememberCoroutineScope()
    var state by remember(session.key) { mutableStateOf<RestState?>(null) }
    var failure by remember(session.key) { mutableStateOf<String?>(null) }
    var busy by remember(session.key) { mutableStateOf(false) }
    var outcome by remember(session.key) { mutableStateOf<Pair<String, Boolean>?>(null) }
    val mine = index.keys.filter { it.hasSecret && !it.testingOnly }
    var pick by remember(session.key, mine) { mutableStateOf(mine.firstOrNull()) }
    var cipher by remember(session.key) { mutableStateOf(RestCipher.AES256) }
    var onAppend by remember(session.key) { mutableStateOf(false) }
    var reload by remember(session.key) { mutableStateOf(0) }

    LaunchedEffect(session.key, reload) {
        val read = withContext(Dispatchers.IO) { runCatching { rest.state() } }
        state = read.getOrNull()
        failure = read.exceptionOrNull()?.let(::whyFailed)
    }

    FaultText(
        "Before turning this on: the server can no longer search the text of new mail, webmail and phone apps show it as " +
            "an encrypted attachment they cannot open, and only a program holding your private key can read it. " +
            "Losing the private key means losing that mail.",
    )
    Spacer(Modifier.height(8.dp))
    val current = state
    when {
        failure != null -> FaultText(failure!!)
        current == null -> Spinner(size = 20.dp, thickness = 2.dp)
        current.cipher != null -> {
            Text("On, with ${current.cipher.label}.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Quiet(if (current.encryptOnAppend) "Mail that apps copy into the mailbox is encrypted too." else "Only mail arriving from outside is encrypted.")
            Spacer(Modifier.height(6.dp))
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val done = withContext(Dispatchers.IO) { runCatching { rest.disable() } }
                    busy = false
                    outcome = done.fold({ "Turned off. Mail already encrypted stays encrypted." to true }, { whyFailed(it) to false })
                    reload++
                }
            }) { Text("Turn off") }
        }
        mine.isEmpty() -> Quiet("Make or import a key of your own above first. A test certificate will not do: this protects real mail.")
        else -> {
            Text("Off.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                mine.forEach { entry ->
                    Row(Modifier.clickable { pick = entry }.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = pick == entry, onClick = { pick = entry })
                        Text("${entry.kind.label} ${Pgp.short(entry.fingerprint)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val allowed = pick?.let { ciphersFor(it.kind) } ?: emptyList()
            // A choice the picked key cannot use falls back to the first it can, without
            // writing state during composition.
            val effective = if (cipher in allowed) cipher else allowed.firstOrNull() ?: RestCipher.AES256
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                allowed.forEach { option ->
                    Row(Modifier.clickable { cipher = option }.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = effective == option, onClick = { cipher = option })
                        Text(option.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.clickable { onAppend = !onAppend }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = onAppend, onCheckedChange = { onAppend = it })
                Text("Also encrypt mail that apps copy into the mailbox, such as sent copies", style = MaterialTheme.typography.bodySmall)
            }
            Quiet("That last one also needs the server to allow it; if it does not, only incoming mail is encrypted.")
            Spacer(Modifier.height(6.dp))
            Button(enabled = !busy && pick != null, onClick = {
                val entry = pick ?: return@Button
                busy = true
                scope.launch {
                    val done = withContext(Dispatchers.IO) { runCatching { rest.enable(entry, effective, onAppend) } }
                    busy = false
                    outcome = done.fold(
                        { "Turned on. New mail is encrypted to ${Pgp.short(entry.fingerprint)} from now; mail already there is left as it was." to true },
                        { whyFailed(it) to false },
                    )
                    reload++
                }
            }) { Text(if (busy) "Working" else "Turn on") }
        }
    }
    Settled(outcome?.first, outcome?.second == true)
}
