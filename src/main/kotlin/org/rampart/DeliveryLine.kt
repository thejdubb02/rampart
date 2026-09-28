package org.rampart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether a message you sent arrived, as the server recorded it.
 *
 * Draws nothing at all until there is something to say, and nothing when the server has no
 * record of sending it: see [deliveryReport] for why that is the ordinary case rather than
 * a fault. When the server could not be asked, that is said in one sentence, because a
 * missing line would otherwise read as "nothing went wrong".
 *
 * [backend] is null on anything that is not the reader's own sent mail, and the line is then
 * not drawn and the server is not asked.
 */
@Composable
internal fun DeliveryLine(backend: MailBackend?, emailId: String) {
    if (backend == null) return
    var report by remember(backend, emailId) { mutableStateOf<DeliveryReport?>(null) }
    var fault by remember(backend, emailId) { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(backend, emailId) {
        report = null
        fault = null
        try {
            report = withContext(Dispatchers.IO) { backend.delivery(emailId) }
        } catch (e: CancellationException) {
            // Leaving the message is not a failure to report.
            throw e
        } catch (e: Exception) {
            fault = e
        }
    }
    val failed = fault
    if (failed != null) {
        val title = "Could not check whether this message was delivered."
        FaultText(title, faultDetail(failed, title), Modifier.padding(top = 8.dp))
        return
    }
    val shown = report ?: return
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            shown.says,
            style = MaterialTheme.typography.bodySmall,
            color = if (shown.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        )
        shown.because?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
