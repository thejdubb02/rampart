package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.YearMonth

/**
 * What the model is allowed to do, and what it has cost.
 *
 * Off by default and off per feature, which is the whole shape of this: see
 * `docs/assistant.md`. A model that summarises a thread and a model that reads every
 * message as it arrives are different appetites, so agreeing to one is not agreeing to the
 * other, and neither is agreed to by installing Rampart.
 *
 * Nothing here calls anything. It holds the settings, the consent and the running total,
 * and [Llm] does the talking.
 */
enum class AssistantMode {
    /** No model, no key, no network. The default and the one that needs no explaining. */
    OFF,

    /**
     * A model on this machine, reached at [AssistantConfig.baseUrl].
     *
     * Ollama and friends speak the same chat-completions shape as everybody else, so this
     * is the same code path with a different address and no key. It is a separate mode
     * rather than a blank key because the question it answers is "does the text leave this
     * machine", and that deserves its own answer rather than being inferred.
     */
    LOCAL,

    /** Somebody else's model, reached with a key the user typed in. */
    BYOK,
}

/**
 * Where to ask, what to ask, and what it may cost before it stops.
 *
 * The price is here rather than in a table because a table of model prices is a thing that
 * goes stale silently and then reports a cost that is wrong in the reassuring direction.
 * Two numbers the reader can correct are honest about being an estimate.
 */
data class AssistantConfig(
    val mode: AssistantMode = AssistantMode.OFF,
    /** Chat-completions base, without the `/chat/completions` on the end. */
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val model: String = "anthropic/claude-haiku-4-5",
    /** Dollars per million tokens in and out, for the running total. */
    val dollarsIn: Double = 1.0,
    val dollarsOut: Double = 5.0,
    /**
     * Dollars a month, after which the features stop rather than warn.
     *
     * Stopping is the point. A warning is a thing somebody dismisses at the moment they are
     * busiest, and the failure mode being guarded against is a bill nobody pressed.
     */
    val ceiling: Double = 5.0,
)

/** What one feature has spent this month. */
data class Spend(val calls: Int, val tokensIn: Int, val tokensOut: Int, val dollars: Double)

object Assistant {
    /** The features that can be agreed to separately. Used as ledger and consent keys. */
    const val SUMMARISE = "summarise"

    /**
     * Describing a filter in words and having the rule built from it.
     *
     * Its own agreement, because what leaves the machine is not what leaves for a summary.
     * This sends one sentence somebody typed and a list of folder names. A summary sends
     * the mail.
     */
    const val FILTER = "filter"

    /**
     * The panel that talks back and can act.
     *
     * Its own agreement again, and the one that deserves it most: this is the feature
     * where the mail itself reaches the model and where the model can change something.
     */
    const val CHAT = "chat"

    /**
     * AI compose-drafting feature, modeled on Help me write.
     *
     * Its own agreement again, gating access to the AI drafting toolbar helper.
     */
    const val COMPOSE = "compose"

    /** Settings as saved, or the defaults, which are off. */
    fun config(): AssistantConfig {
        val saved = read()
        val fallback = AssistantConfig()
        return AssistantConfig(
            // A mode nobody has heard of is off. Every unreadable field falls the same way,
            // towards the setting that costs nothing and sends nothing.
            mode = AssistantMode.entries.firstOrNull { it.name == saved.text("mode") } ?: fallback.mode,
            baseUrl = saved.text("baseUrl")?.ifBlank { null } ?: fallback.baseUrl,
            model = saved.text("model")?.ifBlank { null } ?: fallback.model,
            dollarsIn = saved.number("dollarsIn") ?: fallback.dollarsIn,
            dollarsOut = saved.number("dollarsOut") ?: fallback.dollarsOut,
            ceiling = saved.number("ceiling") ?: fallback.ceiling,
        )
    }

    fun setConfig(config: AssistantConfig) = write {
        put("mode", JsonPrimitive(config.mode.name))
        put("baseUrl", JsonPrimitive(config.baseUrl))
        put("model", JsonPrimitive(config.model))
        put("dollarsIn", JsonPrimitive(config.dollarsIn))
        put("dollarsOut", JsonPrimitive(config.dollarsOut))
        put("ceiling", JsonPrimitive(config.ceiling))
    }

    /**
     * Whether this feature has been explained and agreed to, on this install.
     *
     * Per feature, and it survives a restart. Turning the assistant off and on again does
     * not re-ask: a decision somebody already made and can see in Settings is not a
     * decision worth interrupting them for twice.
     */
    fun agreed(feature: String): Boolean = agreements().contains(feature)

    fun agree(feature: String) {
        if (agreed(feature)) return
        val next = agreements() + feature
        write { put("agreed", buildJsonArray { next.forEach { add(JsonPrimitive(it)) } }) }
    }

    /** Forget every agreement, which is what the switch in Settings does when it goes off. */
    fun forgetAgreements() = write { put("agreed", JsonArray(emptyList())) }

    /** This month, as `YYYY-MM`, in the machine's own time zone. */
    fun thisMonth(): String = YearMonth.now().toString()

    /**
     * Add one call to the running total.
     *
     * Kept per month and per feature, because "what is this costing" and "what is costing
     * it" are the two questions anybody actually has. Months older than a year are dropped
     * on write, so the file cannot grow without end.
     */
    fun record(feature: String, tokensIn: Int, tokensOut: Int, config: AssistantConfig = config()) {
        val month = thisMonth()
        val was = breakdown(month)[feature] ?: Spend(0, 0, 0, 0.0)
        val now = Spend(
            calls = was.calls + 1,
            tokensIn = was.tokensIn + tokensIn,
            tokensOut = was.tokensOut + tokensOut,
            dollars = was.dollars + cost(tokensIn, tokensOut, config),
        )
        // Rebuilt rather than patched in place, so the pruning below is the only rule about
        // what a ledger holds and there is nowhere else for an old month to survive.
        val months = read()["ledger"]?.asObject().orEmpty().toMutableMap()
        months[month] = JsonObject(
            (months[month]?.asObject().orEmpty() + (feature to now.json())),
        )
        write {
            put("ledger", JsonObject(months.filterKeys { worthKeeping(it) }))
        }
    }

    /** What each feature has spent this month, in the order the features were first used. */
    fun breakdown(month: String = thisMonth()): Map<String, Spend> {
        val entries = read()["ledger"]?.asObject()?.get(month)?.asObject().orEmpty()
        return entries.mapNotNullTo(ArrayList()) { (feature, value) ->
            val spend = value.asObject() ?: return@mapNotNullTo null
            feature to Spend(
                calls = spend.whole("calls") ?: 0,
                tokensIn = spend.whole("tokensIn") ?: 0,
                tokensOut = spend.whole("tokensOut") ?: 0,
                dollars = spend.number("dollars") ?: 0.0,
            )
        }.toMap(LinkedHashMap())
    }

    /** Everything spent this month, across features. */
    fun spent(month: String = thisMonth()): Double = breakdown(month).values.sumOf { it.dollars }

    /**
     * Whether the ceiling has been reached, which is the answer to "may I make this call".
     *
     * Asked before the call rather than after, so the ceiling is a ceiling rather than a
     * line the last call is allowed to cross.
     */
    fun blocked(config: AssistantConfig = config()): Boolean =
        config.ceiling > 0 && spent() >= config.ceiling

    /**
     * Whether a feature can run at all right now, and if not, why, in one sentence.
     *
     * Not having agreed to a feature is deliberately not a reason. Agreement is something
     * the reader is asked for at the moment they press the button, and answering "you have
     * not agreed" to somebody who has not been asked yet is a dead end rather than an
     * explanation.
     *
     * @param account Whose folder list [folder] is checked against. Null when there is no
     *   message open yet, in which case there is nothing to check and this branch never
     *   fires.
     * @param folder The folder the open message is actually sitting in. Checked first and
     *   unconditionally, ahead of the mode, the key and the ceiling: a folder somebody put
     *   on the never list stays refused whether or not the assistant is switched on, so
     *   turning something else on cannot answer it.
     */
    fun whyNot(
        feature: String,
        config: AssistantConfig = config(),
        account: String? = null,
        folder: String? = null,
    ): String? = when {
        account != null && folder != null && folder in deniedFolders(account) ->
            "$folder is set to never leave this machine. Change that in Settings if this message should be readable."
        config.mode == AssistantMode.OFF -> "The assistant is switched off."
        config.mode == AssistantMode.BYOK && Secrets.loadNamed(KEY).isNullOrBlank() ->
            "No key has been added yet."
        blocked(config) -> "This month has reached the ${money(config.ceiling)} limit you set."
        else -> null
    }

    /**
     * Folders, by account, that nothing here is ever allowed to read from.
     *
     * One list rather than one per feature: the promise in `docs/assistant.md` is about
     * the folder, not about which button was pressed, so a folder marked never-leaves does
     * not leave because Summarise asked rather than Chat. Empty by default, on every
     * account, because a boundary nobody drew is not a boundary yet.
     */
    fun deniedFolders(account: String): Set<String> =
        read()["deniedFolders"]?.asObject()?.get(account)?.let { it as? JsonArray }
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toSet()
            .orEmpty()

    fun setDeniedFolders(account: String, folders: Set<String>) {
        val byAccount = read()["deniedFolders"]?.asObject().orEmpty().toMutableMap()
        byAccount[account] = buildJsonArray { folders.forEach { add(JsonPrimitive(it)) } }
        write { put("deniedFolders", JsonObject(byAccount)) }
    }

    /** The name the key is kept under in the operating system's own store. */
    const val KEY = "assistant"

    private fun cost(tokensIn: Int, tokensOut: Int, config: AssistantConfig): Double =
        tokensIn / 1_000_000.0 * config.dollarsIn + tokensOut / 1_000_000.0 * config.dollarsOut

    /** Dollars, as somebody would write them, which is what goes in a sentence. */
    internal fun money(amount: Double): String =
        if (amount >= 1) "$" + String.format("%.2f", amount) else "$" + String.format("%.3f", amount)

    /**
     * A month worth keeping: one that parses and is not more than a year behind.
     *
     * A key that does not parse is dropped rather than kept, because a ledger is only ever
     * written by this file and anything else in there is not a record of spending.
     */
    private fun worthKeeping(month: String): Boolean = runCatching {
        !YearMonth.parse(month).isBefore(YearMonth.now().minusMonths(12))
    }.getOrDefault(false)

    private fun agreements(): Set<String> =
        read()["agreed"]?.let { it as? JsonArray }
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toSet()
            .orEmpty()

    private fun Spend.json(): JsonObject = buildJsonObject {
        put("calls", JsonPrimitive(calls))
        put("tokensIn", JsonPrimitive(tokensIn))
        put("tokensOut", JsonPrimitive(tokensOut))
        put("dollars", JsonPrimitive(dollars))
    }

    /*
     * Its own file beside settings.json, not a key inside it.
     *
     * Preferences are a thing somebody pastes into a bug report. A running total of what a
     * model has cost and which features were agreed to is not secret, but it is not a
     * preference either, and keeping it separate means a reset of one is not a reset of the
     * other. The key itself is in neither: that is in the operating system's store.
     */
    private val store = JsonStore("assistant.json")

    private fun read(): JsonObject = store.read()

    private fun write(change: MutableMap<String, JsonElement>.() -> Unit) = store.write(change)

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.whole(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
}
