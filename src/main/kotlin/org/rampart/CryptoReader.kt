package org.rampart

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files

/*
 * The reader's side of encrypted and signed mail: the line above the message saying what
 * protects it and who signed it, and the decrypted text in place of the ciphertext.
 *
 * **Decrypted content is drawn by exactly the same path as any other message.** It becomes a
 * [Body] and goes through [prepareReading], which is the jsoup clean, the content security
 * policy and the link confirmation, with remote pictures always blocked: a message somebody
 * encrypted to your public key is still a message from a stranger, and a remote picture in
 * one would tell its sender the moment you decrypted it.
 *
 * **The decrypted body is kept apart from the server's copy.** It lives in [SealedView] for
 * this session only, is never written to the local store as a body, and is registered with
 * [RookGate] so no assistant feature reads it by accident. Replies still quote the server's
 * copy, which for PGP/MIME and S/MIME is empty, so a reply cannot carry the decrypted words
 * out in the clear because somebody forgot encryption was on.
 */

/** One message as the reader opened it. [body] is null when it was only signed. */
internal data class OpenedView(
    val body: Body?,
    val reading: Reading?,
    val lines: List<SealLine>,
    val files: List<SealedFile>,
    val encrypted: Boolean,
    val plaintext: String?,
    val verdict: SignatureVerdict?,
    val carriedKeys: List<ByteArray>,
)

/** What the reader has decrypted this session, by account and message. Memory only. */
internal object SealedView {
    private const val MOST = 200
    private val opened = mutableStateMapOf<String, OpenedView>()
    private val order = ArrayDeque<String>()

    private fun key(account: String, id: String) = "$account/$id"

    fun of(account: String?, id: String): OpenedView? = account?.let { opened[key(it, id)] }

    fun put(account: String, id: String, view: OpenedView) {
        val k = key(account, id)
        if (k !in opened) order.addLast(k)
        opened[k] = view
        while (order.size > MOST) opened.remove(order.removeFirst())
    }

    /** The decrypted text of one message, for "Decrypt to summarise" and nothing else. */
    fun plaintext(account: String, id: String): String? = opened[key(account, id)]?.plaintext

    fun sealed(account: String, id: String): Boolean = opened[key(account, id)]?.encrypted == true
}

/** The text of [body] that a model may be given. See [RookGate]. */
internal fun rookTextOf(body: Body?): String =
    if (RookGate.isDecrypted(body)) RookGate.WITHHELD else RookGate.textFor(plainTextOf(body))

private sealed interface PanelState {
    data object Opening : PanelState
    data object Hidden : PanelState
    data class Done(val view: OpenedView) : PanelState
    data class Failed(val reason: String, val locked: List<KeyEntry>) : PanelState
}

/** The most of one message fetched to be decrypted. */
private const val SEALED_LIMIT = 32L * 1024 * 1024

/**
 * The whole message as bytes. The message's own blob where the server has one, which keeps
 * 8-bit bytes exactly as they arrived; the text source otherwise.
 */
private fun rawOf(backend: MailBackend, id: String, emailBlobId: String?): ByteArray? =
    emailBlobId?.let { runCatching { backend.blob(Attachment(it, "message.eml", "message/rfc822", 0L), SEALED_LIMIT) }.getOrNull() }
        ?: backend.raw(id, SEALED_LIMIT)?.toByteArray(Charsets.UTF_8)

/**
 * The line above a signed or encrypted message, and the work of opening it.
 *
 * Draws nothing for an ordinary message, and nothing is fetched for one: the check that
 * decides is [mayBeSealed], over what the reader already has.
 */
@Composable
internal fun SealedPanel(
    account: String?,
    summary: Summary,
    body: Body?,
    attachments: List<Attachment>,
    emailBlobId: String?,
    backend: MailBackend?,
    /** The account's local copy, asked for off the UI thread, since the first ask opens it. */
    store: () -> Store?,
    known: Set<String>,
    dark: Boolean,
    /** A plain message's page. A theme change retints an open message without decrypting it again. */
    pageBackground: String = "#ffffff",
    pageText: String = "#1a1a1a",
    /** Shows the packet for summarising this thread with its decrypted text, before anything is sent. Null hides it. */
    onDecryptToSummarise: (() -> Unit)?,
) {
    if (account == null || backend == null || body == null) return
    val candidate = remember(summary.id, body, attachments) {
        mayBeSealed(attachments.map { it.type }, attachments.map { it.name }, body.text ?: body.html)
    }
    if (!candidate) return
    var attempt by remember(account, summary.id) { mutableStateOf(0) }
    var state by remember(account, summary.id) { mutableStateOf<PanelState>(PanelState.Opening) }
    var note by remember(account, summary.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(account, summary.id, attempt, pageBackground, pageText) {
        val cached = SealedView.of(account, summary.id)
        val cachedBody = cached?.body
        if (cached != null && cachedBody != null && !plainPageMatches(cached.reading, pageBackground, pageText)) {
            val reading = withContext(Dispatchers.Default) {
                prepareReading(
                    summary.fromEmail,
                    summary.from,
                    cachedBody,
                    emptyList(),
                    emptyMap(),
                    false,
                    dark,
                    known,
                    pageBackground,
                    pageText,
                )
            }
            val view = cached.copy(reading = reading)
            SealedView.put(account, summary.id, view)
            state = PanelState.Done(view)
            return@LaunchedEffect
        }
        state = PanelState.Opening
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val raw = rawOf(backend, summary.id, emailBlobId)
                    ?: throw CryptoFailure("The whole message could not be fetched from the server to check it.")
                val ring = Keys.ring
                val opened = openSealed(raw, ring.sealKeys())
                val lines = sealLines(opened, summary.fromEmail) { ring.trusted(it) }
                val carried = keysCarried(raw, summary.fromEmail, opened.files)
                if (!opened.encrypted) {
                    OpenedView(null, null, lines, opened.files, false, null, opened.signature, carried)
                } else {
                    val text = listOfNotNull(
                        opened.text,
                        opened.outside?.let { "Not encrypted, and shown apart from the rest:\n$it" },
                    ).joinToString("\n\n").ifBlank { null }
                    val shown = body.copy(html = opened.html, text = text)
                    RookGate.markDecrypted(shown)
                    val reading = prepareReading(
                        summary.fromEmail,
                        summary.from,
                        shown,
                        emptyList(),
                        emptyMap(),
                        false,
                        dark,
                        known,
                        pageBackground,
                        pageText,
                    )
                    val plaintext = text ?: opened.html?.let { org.jsoup.Jsoup.parse(it).text() }
                    val local = if (ring.index().searchInside) runCatching { store() }.getOrNull() else null
                    if (plaintext != null && local != null && EncryptedSearch.mayIndex(true, local.encrypted)) {
                        runCatching { local.indexDecrypted(summary.id, plaintext) }
                    }
                    OpenedView(shown, reading, lines, opened.files, true, plaintext, opened.signature, carried)
                }
            }
        }
        state = result.fold(
            onSuccess = { view ->
                SealedView.put(account, summary.id, view)
                PanelState.Done(view)
            },
            onFailure = { e ->
                // A wrong passphrase is forgotten at once, so the box to type it comes back.
                if (e is WrongPassphrase) Keys.ring.forgetPassphrase(e.fingerprint)
                val locked = Keys.ring.locked()
                when (e) {
                    // Looked protected from its parts and was not: nothing to say about it.
                    is NotSealed -> PanelState.Hidden
                    is PassphraseNeeded -> PanelState.Failed(e.message.orEmpty(), locked.filter { it.fingerprint == e.fingerprint }.ifEmpty { locked })
                    is CryptoFailure -> PanelState.Failed(e.message.orEmpty(), locked)
                    else -> PanelState.Failed("This message could not be opened: ${whyFailed(e).removeSuffix(".")}.", emptyList())
                }
            },
        )
    }

    if (state == PanelState.Hidden) return
    val alarm = (state as? PanelState.Done)?.view?.lines?.any { it.alarm } == true || state is PanelState.Failed
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .border(
                1.dp,
                if (alarm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.small,
            )
            .padding(12.dp),
    ) {
        when (val s = state) {
            PanelState.Hidden -> Unit
            PanelState.Opening -> Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 16.dp, thickness = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Checking the encryption and the signature", style = MaterialTheme.typography.bodySmall)
            }
            is PanelState.Done -> {
                s.view.lines.forEach { line ->
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (line.alarm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (line.alarm) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                SealActions(s.view, summary, onDecryptToSummarise, onChanged = { attempt++ }, onNote = { note = it })
            }
            is PanelState.Failed -> {
                FaultText(s.reason)
                s.locked.forEach { entry ->
                    KeyUnlock(entry) { attempt++ }
                }
                TextButton(onClick = { attempt++ }) { Text("Try again") }
            }
        }
        note?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Trust a signer, keep a key or certificate, save a file from inside, or summarise. */
@Composable
private fun SealActions(
    view: OpenedView,
    summary: Summary,
    onDecryptToSummarise: (() -> Unit)?,
    onChanged: () -> Unit,
    onNote: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ring = Keys.ring
    val verdict = view.verdict as? SignatureVerdict.Good
    val listed = remember(view) { ring.entries() }
    val signerListed = verdict?.let { v -> listed.firstOrNull { it.fingerprint == v.fingerprint } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (view.encrypted && onDecryptToSummarise != null) {
            TextButton(onClick = onDecryptToSummarise) { Text("Decrypt to summarise") }
        }
        if (signerListed != null && !signerListed.trusted && !signerListed.hasSecret) {
            TextButton(onClick = {
                runCatching { ring.setTrusted(signerListed.fingerprint, true) }
                    .onSuccess { onNote("Rampart now trusts ${signerListed.name.ifBlank { summary.fromEmail }}'s key."); onChanged() }
                    .onFailure { onNote(whyFailed(it)) }
            }) { Text("Trust this key") }
        }
        val pem = verdict?.certificatePem
        if (pem != null && signerListed == null) {
            TextButton(onClick = {
                runCatching { ring.addCertificate(pem.toByteArray(), KeySource.ATTACHED, trusted = verdict?.vouched == true) }
                    .onSuccess { onNote("Kept ${it.name}'s certificate, so you can write back encrypted."); onChanged() }
                    .onFailure { onNote(whyFailed(it)) }
            }) { Text("Keep this certificate") }
        }
        val newKeys = remember(view, listed) {
            view.carriedKeys.filter { bytes ->
                runCatching { Pgp.publicRings(bytes).any { r -> listed.none { it.fingerprint == Pgp.fingerprintOf(r) } } }.getOrDefault(false)
            }
        }
        if (newKeys.isNotEmpty()) {
            TextButton(onClick = {
                runCatching { newKeys.flatMap { ring.addPgpPublic(it, KeySource.ATTACHED, trusted = false) } }
                    .onSuccess {
                        onNote("Kept the key this message carries. It is not trusted until you say so in Settings, Encryption.")
                        onChanged()
                    }
                    .onFailure { onNote(whyFailed(it)) }
            }) { Text("Keep the sender's key") }
        }
        view.files.filterNot { it.type.equals("application/pgp-keys", true) }.forEach { file ->
            TextButton(onClick = {
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { Defender.check(Files.write(uniqueIn(downloadsFolder(), file.name), file.bytes)) }
                    }
                    onNote(saved.fold({ "Saved ${file.name} to $it." }, { "${file.name} could not be saved: ${whyFailed(it)}" }))
                }
            }) { Text("Save ${file.name}") }
        }
    }
    if (view.encrypted && onDecryptToSummarise != null) {
        Text(
            "Rook never reads encrypted mail on its own. Decrypt to summarise shows you exactly what would be sent first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** A passphrase box for one locked key, held in memory for the session once typed. Also used by the composer. */
@Composable
internal fun KeyUnlock(entry: KeyEntry, onUnlocked: () -> Unit) {
    var phrase by remember(entry.fingerprint) { mutableStateOf("") }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it },
            label = { Text("Passphrase for ${entry.name.ifBlank { Pgp.short(entry.fingerprint) }}") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            enabled = phrase.isNotEmpty(),
            onClick = {
                Keys.ring.rememberPassphrase(entry.fingerprint, phrase.toCharArray())
                phrase = ""
                onUnlocked()
            },
        ) { Text("Unlock") }
    }
}
