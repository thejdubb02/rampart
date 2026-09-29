package org.rampart

import java.util.Collections
import java.util.WeakHashMap

/**
 * What Rook may read of encrypted mail: by default, nothing.
 *
 * Somebody who encrypted a message did so to keep its words between the people it was sent
 * to, and a model provider is not one of them. So every path that hands message text to the
 * model (summaries, action items, suggested replies, Ask Rook, the Today view, the chat
 * panel's open message and its read tool, events and tasks from mail, the tone sample) goes
 * through [textFor], and an encrypted message comes out of it as [WITHHELD].
 *
 * That covers both halves of the problem:
 *
 * - **Ciphertext.** An inline PGP block, or an S/MIME blob, is noise to a model and still a
 *   thing nobody agreed to send. [looksSealed] catches it by content, so it holds for a
 *   message nobody has opened yet. PGP/MIME and S/MIME messages carry no text part at all as
 *   the server describes them, so there is nothing of theirs to send in the first place.
 * - **Plaintext the reader decrypted.** The decrypted body is kept apart from the server's copy
 *   and registered here with [markDecrypted], so a feature that picks up the body on screen
 *   gets [WITHHELD] instead of the words.
 *
 * The only way past it is "Decrypt to summarise": an explicit press on one thread, which
 * passes `explicit = true` and opens the packet viewer so the person sees exactly what is about
 * to go before it goes.
 */
internal object RookGate {
    /** What the model is told instead of an encrypted message's words. */
    const val WITHHELD = "(This message is encrypted, so its text was not sent. Rook reads encrypted mail only when asked, one thread at a time.)"

    private val decrypted: MutableSet<Body> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** Whether [text] carries OpenPGP or S/MIME ciphertext. */
    fun looksSealed(text: String): Boolean =
        text.contains("-----BEGIN PGP MESSAGE-----") ||
            text.contains("application/pkcs7-mime", ignoreCase = true) ||
            text.contains("application/pgp-encrypted", ignoreCase = true)

    /** Registers a body the reader decrypted, so it is never read out to the model by accident. */
    fun markDecrypted(body: Body) {
        decrypted += body
    }

    fun isDecrypted(body: Body?): Boolean = body != null && body in decrypted

    /**
     * The text a model may be given for one message.
     *
     * [serverText] is the message as the server holds it. [sealed] is whether the reader has
     * already found it to be encrypted. [plaintext] is its decrypted text, when the reader has
     * it, and is only used with [explicit].
     */
    fun textFor(serverText: String, sealed: Boolean = false, plaintext: String? = null, explicit: Boolean = false): String =
        when {
            !sealed && !looksSealed(serverText) -> serverText
            explicit && plaintext != null -> plaintext
            else -> WITHHELD
        }
}
