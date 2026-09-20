package org.rampart

/**
 * Twenty messages, one paragraph, before you decide whether to read them.
 *
 * The first assistant feature and the one with the least that can go wrong: read-only,
 * one thread at a time, and a bad answer costs a few seconds. See `docs/assistant.md`.
 *
 * **What the model is given is plain text, and only the text.** No HTML, so no pixel is
 * fetched and no tracking URL is carried along, and the model is told in the system
 * prompt that what follows is mail rather than instructions. That last part is not a
 * defence on its own and is not treated as one: the real defence is that this feature has
 * no tool it could be talked into using. It summarises and returns a paragraph.
 */
internal data class Turn(val from: String, val when_: String, val text: String)

internal object Summarise {
    /**
     * How much of a thread is worth sending.
     *
     * Long enough for a real thread, short enough that one runaway newsletter cannot turn
     * a summary into a bill. Measured in characters because tokens are a thing only the
     * provider can count, and a character budget is one the reader could check themselves.
     */
    const val BUDGET = 24_000

    /** What the model is told it is doing. */
    fun system(): String = TODO()

    /**
     * The thread as one block of text, newest last, trimmed to [BUDGET].
     *
     * The oldest messages go first when something has to go, because a thread is read
     * from the top and what somebody wants summarised is usually how it ended. A message
     * that is dropped whole is better than twenty messages each cut mid-sentence.
     */
    fun user(subject: String, turns: List<Turn>): String = TODO()
}
