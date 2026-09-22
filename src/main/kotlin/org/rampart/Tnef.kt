package org.rampart

import org.apache.poi.hmef.HMEFMessage
import org.apache.poi.hmef.attribute.TNEFProperty
import org.apache.poi.hmef.attribute.TNEFStringAttribute

/**
 * What was packed inside a winmail.dat.
 *
 * [body] is the plain text, when the wrapper carried one. [files] is each real
 * file, under the name it was given. A rendering stub with no bytes of its own
 * is left out: it is how Outlook describes an attachment, not an attachment.
 */
internal data class TnefContents(
    val body: String?,
    val files: List<Pair<String, ByteArray>>,
)

/**
 * The files inside a winmail.dat, or null when these bytes are not one.
 *
 * A hostile or truncated file comes back as null, so the click can save the
 * wrapper instead of failing. Same bargain as [scaledPreview].
 */
internal fun readTnef(bytes: ByteArray): TnefContents? = runCatching {
    // Closes the stream itself, including when the signature is wrong.
    val message = HMEFMessage(bytes.inputStream())
    /*
     * getBody() is the compressed RTF, which is not text anyone can read.
     * The plain text, when Outlook included one, is the TNEF body attribute.
     */
    val body = TNEFStringAttribute.getAsString(message.getMessageAttribute(TNEFProperty.ID_BODY))
        ?.takeIf { it.isNotBlank() }
    val files = buildList {
        for (attachment in message.attachments) {
            // A stub with no data section throws. One bad file should not hide the rest.
            val file = runCatching {
                val raw = attachment.contents
                // The long name is what the sender called it. The short one is the 8.3 form.
                val called = attachment.longFilename?.takeIf { it.isNotBlank() }
                    ?: attachment.filename?.takeIf { it.isNotBlank() }
                    ?: "attachment"
                called to raw
            }.getOrNull() ?: continue
            add(file)
        }
    }
    TnefContents(body, files)
}.getOrNull()
