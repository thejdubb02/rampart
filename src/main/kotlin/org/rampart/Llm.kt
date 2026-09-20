package org.rampart

/**
 * One model, reached one way.
 *
 * Chat completions, the shape OpenAI defined and everybody else copied, with the address
 * configurable. OpenRouter is the default because it fronts every model worth having;
 * pointing it at an Ollama on this machine has to work on the same code path, and does,
 * because the request is the same request. One client, one interface: see
 * `docs/assistant.md`.
 *
 * **The packet is built separately from being sent**, and that is not tidiness. The
 * promise this feature makes is that the exact text about to leave can be looked at
 * first, and a promise like that is only worth anything if the thing shown and the thing
 * sent are the same object rather than two renderings of the same intent.
 */
internal data class LlmReply(
    val text: String,
    val tokensIn: Int,
    val tokensOut: Int,
)

internal object Llm {
    /**
     * The exact JSON that will be sent, pretty-printed for a person to read.
     *
     * Deterministic: the same arguments give the same bytes, so what the viewer shows is
     * what [ask] posts.
     */
    fun packet(model: String, system: String, user: String, maxTokens: Int = 700): String = TODO()

    /**
     * Post a packet and read the answer back.
     *
     * @param key null for a local model, which has nothing to authenticate to.
     * @throws LlmError with a sentence a person can act on. Never a stack trace and never
     *   a status code on its own: "the key was refused" is a thing somebody can fix and
     *   "401" is a thing they have to look up.
     */
    fun ask(config: AssistantConfig, key: String?, packet: String): LlmReply = TODO()
}

/** A failure with a sentence in it, which is the only kind worth showing to anybody. */
internal class LlmError(message: String) : Exception(message)
