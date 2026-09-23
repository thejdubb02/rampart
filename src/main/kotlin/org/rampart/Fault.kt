package org.rampart

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A failure a person can read, with the technical line under it.
 *
 * The title is the sentence. The detail is whatever the server or the runtime
 * said, smaller and quieter, and never the line that gets read first.
 */
@Composable
internal fun FaultText(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.error,
    detailColor: Color = MaterialTheme.colorScheme.outline,
) {
    val extra = detail?.trim()?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
    androidx.compose.foundation.layout.Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = titleColor, maxLines = 4)
        if (extra != null) {
            Text(
                extra,
                style = MaterialTheme.typography.bodySmall,
                color = detailColor,
            )
        }
    }
}

/** The second line under a title, or null when there is nothing extra to say. */
internal fun faultDetail(thrown: Throwable, title: String): String? =
    whyFailed(thrown).trim().takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
