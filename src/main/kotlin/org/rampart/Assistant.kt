package org.rampart

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

    /** Settings as saved, or the defaults, which are off. */
    fun config(): AssistantConfig = TODO()

    fun setConfig(config: AssistantConfig): Unit = TODO()

    /**
     * Whether this feature has been explained and agreed to, on this install.
     *
     * Per feature, and it survives a restart. Turning the assistant off and on again does
     * not re-ask: a decision somebody already made and can see in Settings is not a
     * decision worth interrupting them for twice.
     */
    fun agreed(feature: String): Boolean = TODO()

    fun agree(feature: String): Unit = TODO()

    /** Forget every agreement, which is what the switch in Settings does when it goes off. */
    fun forgetAgreements(): Unit = TODO()

    /** This month, as `YYYY-MM`, in the machine's own time zone. */
    fun thisMonth(): String = TODO()

    /**
     * Add one call to the running total.
     *
     * Kept per month and per feature, because "what is this costing" and "what is costing
     * it" are the two questions anybody actually has. Months older than a year are dropped
     * on write, so the file cannot grow without end.
     */
    fun record(feature: String, tokensIn: Int, tokensOut: Int, config: AssistantConfig = config()): Unit = TODO()

    /** What each feature has spent this month, in the order the features were first used. */
    fun breakdown(month: String = thisMonth()): Map<String, Spend> = TODO()

    /** Everything spent this month, across features. */
    fun spent(month: String = thisMonth()): Double = TODO()

    /**
     * Whether the ceiling has been reached, which is the answer to "may I make this call".
     *
     * Asked before the call rather than after, so the ceiling is a ceiling rather than a
     * line the last call is allowed to cross.
     */
    fun blocked(config: AssistantConfig = config()): Boolean = TODO()

    /** Whether a feature can run at all right now, and if not, why, in one sentence. */
    fun whyNot(feature: String, config: AssistantConfig = config()): String? = TODO()
}
