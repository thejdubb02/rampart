package org.rampart

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * Rook's two filter tools, declared and dispatched the way the settings tools are.
 *
 * list_filters is read only. propose_filter never writes: it turns a sentence into a rule
 * the way the Filters page does, checks that script on the server, and puts a card on
 * screen. The filter exists when the person presses Save on that card, which is an event
 * only the window can raise. There is no tool for Save, and no text a message could
 * contain reaches it.
 */

/** The most filter cards one turn may put on screen. */
internal const val MOST_FILTERS = 3

/**
 * Why [backend] cannot keep a filter, in one sentence, or null when it can.
 *
 * The same answer the Filters page gives, so the reason reads the same wherever it turns up.
 * IMAP and a JMAP server without Sieve are the same fact: there is nowhere to put a rule.
 */
internal fun noFiltersBecause(backend: MailBackend?, accountName: String): String? = when {
    backend == null -> "Sign in to an account to keep filters."
    !backend.hasSieve() -> noSieveSentence(accountName)
    else -> null
}

/** The one sentence for a server that has nowhere to keep a filter. */
internal fun noSieveSentence(accountName: String): String {
    val who = accountName.ifBlank { "This account" }
    return "This server does not offer Sieve, so $who cannot keep filters on it."
}

/**
 * Thrown before any model call when this install has not agreed to describe a filter.
 *
 * Agreement is the packet itself, the same one the Filters page shows. The window asks,
 * and only a yes from that dialog lets the description leave the machine.
 */
internal class NeedsFilterConsent(val words: String, val packet: String) : Exception(
    "Describing a filter in words needs the person's say-so first.",
)

/**
 * A description waiting on that yes.
 *
 * [account] is the account Rook was working in when it asked, so the rule that follows
 * is saved there even if the person has since switched.
 */
internal data class FilterConsent(val account: String, val words: String, val packet: String)

/**
 * One filter Rook has put on screen.
 *
 * [account] is stored with the rule. Save writes to that account, not to whichever account
 * happens to be open when the button is pressed.
 */
internal data class FilterCard(
    val number: Int,
    val account: String = "",
    val rule: Rule,
    val status: CardStatus = CardStatus.WAITING,
    val outcome: String? = null,
) {
    /** The rule in the same plain words the Filters page uses on its own card. */
    val words: String get() = previewRule(rule)

    /** What the rule will do, the sentence the filter list uses. */
    val does: String get() = summarise(rule)
}

/**
 * Every filter card this conversation has made.
 *
 * Immutable, like the settings desk: a transition that does not apply returns the same
 * desk. Save moves a waiting card to applying, and a second press does nothing. Clearing
 * the conversation keeps a card that is still being written, so its outcome is not lost.
 * Adding a card drops finished cards past the newest [KEPT_SETTLED]. Waiting and applying
 * cards stay, and a dropped card's number is not handed out again.
 */
internal data class FilterDesk(
    val cards: List<FilterCard> = emptyList(),
    /** Highest number handed out. Dropping old cards must not make [nextNumber] go backwards. */
    val lastNumber: Int = cards.maxOfOrNull { it.number } ?: 0,
) {
    val nextNumber: Int get() = lastNumber + 1

    val waiting: List<FilterCard> get() = cards.filter { it.status == CardStatus.WAITING }

    fun card(number: Int): FilterCard? = cards.firstOrNull { it.number == number }

    /**
     * New cards from a turn, stamped with the account they belong to.
     *
     * A number already handed out is never reused, including one whose card has since left
     * the desk. The tool picks the numbers; this only records which account Save must write to.
     */
    fun add(account: String, drafts: List<FilterCard>): FilterDesk {
        val fresh = drafts.filter { d -> cards.none { it.number == d.number } }
            .map { it.copy(account = account, status = CardStatus.WAITING, outcome = null) }
        if (fresh.isEmpty()) return settle()
        val high = fresh.maxOf { it.number }
        return copy(cards = cards + fresh, lastNumber = maxOf(lastNumber, high)).settle()
    }

    /** Drops finished cards past the newest [KEPT_SETTLED]. Waiting and applying stay. */
    fun settle(): FilterDesk {
        val kept = cards.withoutOldSettled { it.status }
        return if (kept === cards) this else copy(cards = kept)
    }

    /** Waiting to applying, which is what Save does. Anything else is left as it is. */
    fun start(number: Int): FilterDesk = move(number, CardStatus.WAITING) { it.copy(status = CardStatus.APPLYING) }

    /** Applying to saved or failed, with the sentence that says which. */
    fun finish(number: Int, failure: String?): FilterDesk = move(number, CardStatus.APPLYING) {
        if (failure == null) it.copy(status = CardStatus.DONE, outcome = "Saved. It is on the server now.")
        else it.copy(status = CardStatus.FAILED, outcome = failure.ifBlank { "The filter was not saved." })
    }

    fun dismiss(number: Int): FilterDesk = move(number, CardStatus.WAITING) {
        it.copy(status = CardStatus.DISMISSED, outcome = "Not saved.")
    }

    /** A new conversation. A card still being written is kept until it lands. */
    fun clear(): FilterDesk = copy(cards = cards.filter { it.status == CardStatus.APPLYING })

    private fun move(number: Int, from: CardStatus, change: (FilterCard) -> FilterCard): FilterDesk =
        copy(cards = cards.map { if (it.number == number && it.status == from) change(it) else it })
}

/**
 * The filter tools for one turn.
 *
 * [unavailable] is the sentence both tools answer with when the account cannot keep a
 * filter, so Rook can say why rather than guess. [build] is the Filters page's own
 * words-to-rule step, and [validate] is that page's server check. Neither saves. The
 * cards are collected from [proposed] when the turn ends. [consent] is set when the
 * description may not be sent yet, and the window shows it before anything else happens.
 */
internal class FilterTools(
    private val unavailable: String?,
    private val rules: () -> List<Rule>,
    private val build: (String) -> Result<Rule>,
    private val validate: (Rule) -> String?,
    private val firstNumber: Int = 1,
    private val alreadyWaiting: Int = 0,
) {
    val proposed = mutableListOf<FilterCard>()
    var consent: FilterConsent? = null

    /** The answer to a filter tool, or null when [asked] is not one, so the others can have it. */
    fun run(asked: Asked): String? = when (asked.tool) {
        "list_filters" -> unavailable ?: list()
        "propose_filter" -> unavailable ?: propose(text(asked.args, "description"))
        else -> null
    }

    /**
     * propose_filter again, after the person has allowed the description to be sent.
     *
     * The Filters page builds the rule in that moment rather than making them ask twice.
     * This is that same step, still without saving.
     */
    fun offer(words: String): String =
        run(Asked("propose_filter", buildJsonObject { put("description", words) }))
            ?: "No filter was made."

    private fun list(): String {
        val found = try {
            rules()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message ?: "The filters could not be read."
        }
        if (found.isEmpty()) return "No filters yet."
        return found.joinToString("\n") { rule ->
            val off = if (rule.enabled) "" else "(off) "
            val everywhere = if (rule.global) " Kept for every account." else ""
            "$off${rule.name}: ${previewRule(rule)}$everywhere"
        }
    }

    private fun propose(raw: String): String {
        val words = raw.trim()
        if (words.isBlank()) return "That needs a description of the filter."
        if (alreadyWaiting + proposed.size >= MOST_FILTERS) {
            return "There are already $MOST_FILTERS filter cards waiting. Let the person save or cancel those first."
        }
        val built = try {
            build(words)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsFilterConsent) {
            if (consent == null) consent = FilterConsent(account = "", words = e.words, packet = e.packet)
            return "Describing a filter in words needs the person's say-so first. Nothing was sent, and nothing was saved."
        } catch (e: Exception) {
            return "No card was made. ${e.message ?: "The rule could not be built."}"
        }
        val rule = built.getOrElse { error ->
            return "No card was made. ${error.message ?: "The rule could not be built."}"
        }
        val rejected = try {
            validate(rule)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "The server could not check this filter."
        }
        if (rejected != null) return "No card was made. $rejected"
        val card = FilterCard(number = firstNumber + proposed.size, rule = rule)
        proposed += card
        return "A card for \"${rule.name}\" is on screen: ${previewRule(rule)} " +
            "Nothing is saved until the person presses Save on the card, which you cannot do."
    }

    private fun text(args: JsonObject, name: String): String =
        (args[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

/**
 * The filter tools for [backend], on the account Rook is working in.
 *
 * Built before the turn and cheap to build: nothing is read until Rook asks. A script
 * Rampart cannot safely rebuild is refused before the model is called, so a hand-written
 * script never costs a request. The server check is on the whole script Save would write,
 * not on the new rule alone, because that is what the server will be asked to keep.
 */
internal fun filterToolsFor(
    backend: MailBackend?,
    accountKey: String,
    accountName: String,
    folders: List<String>,
    config: AssistantConfig = Assistant.config(),
    firstNumber: Int = 1,
    alreadyWaiting: Int = 0,
): FilterTools {
    val jmap = backend as? Jmap
    return FilterTools(
        unavailable = noFiltersBecause(backend, accountName),
        rules = {
            val mail = backend ?: throw JmapError(noSieveSentence(accountName))
            rulesOnAccount(mail, accountKey)
        },
        build = { words -> ruleFromAccount(jmap, config, words, folders, accountName) },
        validate = { rule ->
            if (jmap == null) noSieveSentence(accountName)
            else try {
                rejectionOf(jmap, accountKey, rule)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "The server could not check this filter."
            }
        },
        firstNumber = firstNumber,
        alreadyWaiting = alreadyWaiting,
    )
}

/**
 * One sentence into a rule, or a failure, without saving.
 *
 * [NeedsFilterConsent] leaves this function as a throw so the tool can put the packet on
 * screen instead of treating "not agreed yet" as a broken rule. Everything else is a
 * [Result], which is what the Filters page's builder already returns.
 */
private fun ruleFromAccount(
    jmap: Jmap?,
    config: AssistantConfig,
    words: String,
    folders: List<String>,
    accountName: String,
): Result<Rule> {
    Assistant.whyNot(Assistant.FILTER, config)?.let { return Result.failure(LlmError(it)) }
    if (!Assistant.agreed(Assistant.FILTER)) throw NeedsFilterConsent(words, rulePacket(config, words, folders))
    if (jmap == null) return Result.failure(JmapError(noSieveSentence(accountName)))
    editableOrWhy(jmap)?.let { return Result.failure(JmapError(it)) }
    val key = Secrets.loadNamed(Assistant.KEY)
    return ruleFromWords(config, words, folders) { packet ->
        val reply = Llm.ask(config, key, packet)
        Assistant.record(Assistant.FILTER, reply.tokensIn, reply.tokensOut, config)
        reply.text
    }
}

/** Why this script must not be rebuilt, or null when the Filters page would edit it. */
private fun editableOrWhy(jmap: Jmap): String? = try {
    val (chosen, current) = runningScript(jmap)
    if (chosen != null && !current.editable) UNEDITABLE_FILTERS else null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    e.message ?: "The filters could not be read."
}

/**
 * What the server says about the script Save would write.
 *
 * Null when the server accepts it. A hand-written script is refused here too, with the
 * same sentence the Filters page shows, so the two cannot disagree about it.
 */
private fun rejectionOf(jmap: Jmap, accountKey: String, rule: Rule): String? {
    val (_, current) = runningScript(jmap)
    val next = scriptWithNewRule(current, accountKey, rule) ?: return UNEDITABLE_FILTERS
    return jmap.validateSieve(sieveOf(next))
}

/**
 * A filter Rook asked for, waiting for a person.
 *
 * Save is a button in the window. Nothing the model writes can press it, and nothing is
 * written until it is pressed. The two sentences are the ones the Filters page already uses:
 * the plain words on its confirmation card, and the one-line "what it does" under a rule.
 */
@Composable
internal fun FilterChangeCard(card: FilterCard, onSave: (Int) -> Unit, onCancel: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(12.dp),
    ) {
        Text(card.rule.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "A filter for this account. It runs on the server when you save it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(8.dp))
        Text(card.words, style = MaterialTheme.typography.bodyMedium)
        Text(
            card.does,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(10.dp))
        when (card.status) {
            CardStatus.WAITING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(card.number) }) { Text("Save") }
                TextButton(onClick = { onCancel(card.number) }) { Text("Cancel") }
            }
            CardStatus.APPLYING -> Text(
                "Saving it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            CardStatus.FAILED -> Text(
                card.outcome ?: "That did not work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            CardStatus.DONE, CardStatus.DISMISSED -> Text(
                card.outcome.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * The description and folder names that would be sent, before they are.
 *
 * The same question the Filters page asks the first time, for the same reason: the person
 * sees the packet, and Send it is what allows it. Cancel sends nothing.
 */
@Composable
internal fun FilterConsentDialog(packet: String, onSend: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("This is what would be sent") },
        text = {
            Column {
                Text(
                    "What you typed and the names of your folders. No mail, no addresses, " +
                        "and nothing else from this machine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text(packet, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Send it") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
