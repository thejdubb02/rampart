package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Bulwark's red. See docs/architecture.md. */
private val RampartRed = Color(0xFFDB2D54)

private val WHEN = DateTimeFormatter.ofPattern("d MMM  HH:mm").withZone(ZoneId.systemDefault())

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Rampart") {
        MaterialTheme(colorScheme = lightColorScheme(primary = RampartRed)) {
            Surface(Modifier.fillMaxSize()) {
                var jmap by remember { mutableStateOf<Jmap?>(null) }
                val session = jmap
                if (session == null) Connect { jmap = it } else Reader(session)
            }
        }
    }
}

@Composable
private fun Connect(onConnected: (Jmap) -> Unit) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Rampart", style = MaterialTheme.typography.headlineMedium, color = RampartRed)
        OutlinedTextField(server, { server = it }, label = { Text("Server") }, singleLine = true, modifier = Modifier.width(380.dp))
        OutlinedTextField(user, { user = it }, label = { Text("Email address") }, singleLine = true, modifier = Modifier.width(380.dp))
        OutlinedTextField(
            password, { password = it },
            label = { Text("App password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(380.dp),
        )
        Button(
            enabled = !busy && server.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
            onClick = {
                busy = true
                error = ""
                scope.launch {
                    try {
                        onConnected(withContext(Dispatchers.IO) { Jmap.connect(server, user, password) })
                    } catch (e: Exception) {
                        error = e.message ?: e.toString()
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) "Connecting" else "Connect") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.width(380.dp))
        Text(
            "The password is held in memory for this session only, and never written to disk.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun Reader(jmap: Jmap) {
    var mailboxes by remember { mutableStateOf<List<Mailbox>>(emptyList()) }
    var mailbox by remember { mutableStateOf<Mailbox?>(null) }
    var emails by remember { mutableStateOf<List<Summary>>(emptyList()) }
    var selected by remember { mutableStateOf<Summary?>(null) }
    var body by remember { mutableStateOf<Body?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }

    suspend fun <T> io(block: () -> T): T? = try {
        error = ""
        withContext(Dispatchers.IO) { block() }
    } catch (e: Exception) {
        error = e.message ?: e.toString()
        null
    }

    LaunchedEffect(jmap) {
        io { jmap.mailboxes() }?.let {
            mailboxes = it
            mailbox = it.firstOrNull { m -> m.role == "inbox" } ?: it.firstOrNull()
        }
        loading = false
    }
    LaunchedEffect(mailbox) {
        val id = mailbox?.id ?: return@LaunchedEffect
        selected = null
        body = null
        loading = true
        emails = io { jmap.emails(id) } ?: emptyList()
        loading = false
    }
    LaunchedEffect(selected) {
        val message = selected ?: return@LaunchedEffect
        body = null
        body = io { jmap.body(message.id) }
        if (!message.seen) {
            io { jmap.markSeen(message.id) }
            emails = emails.map { if (it.id == message.id) it.copy(seen = true) else it }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (error.isNotBlank()) {
            Text(
                error,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),
            )
        }
        Row(Modifier.fillMaxSize()) {
            MailboxList(mailboxes, mailbox) { mailbox = it }
            VerticalDivider()
            MessageList(emails, selected, loading) { selected = it }
            VerticalDivider()
            Message(selected, body) { confirm = it }
        }
    }

    confirm?.let { url ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Open this link?") },
            text = { Text(url, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { Desktop.getDesktop().browse(URI(url)) }
                    confirm = null
                }) { Text("Open in browser") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MailboxList(mailboxes: List<Mailbox>, selected: Mailbox?, onSelect: (Mailbox) -> Unit) {
    LazyColumn(Modifier.width(200.dp).fillMaxHeight()) {
        items(mailboxes, key = { it.id }) { box ->
            val here = box.id == selected?.id
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(if (here) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .clickable { onSelect(box) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(box.name, fontWeight = if (here) FontWeight.Bold else FontWeight.Normal)
                if (box.unread > 0) Text("${box.unread}", color = RampartRed, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MessageList(emails: List<Summary>, selected: Summary?, loading: Boolean, onSelect: (Summary) -> Unit) {
    Box(Modifier.width(320.dp).fillMaxHeight()) {
        if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@Box
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(emails, key = { it.id }) { message ->
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (message.id == selected?.id) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { onSelect(message) }
                        .padding(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            message.from,
                            maxLines = 1,
                            fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
                            modifier = Modifier.weight(1f, false),
                        )
                        Text(
                            message.receivedAt.asLocalTime(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(
                        message.subject,
                        maxLines = 1,
                        fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
                    )
                    if (message.preview.isNotBlank()) {
                        Text(
                            message.preview,
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Message(summary: Summary?, body: Body?, onLink: (String) -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    val rendered = remember(body, linkColor) {
        body?.let {
            when {
                it.html != null -> renderHtml(it.html, linkColor, onLink)
                it.text != null -> renderText(it.text, linkColor, onLink)
                else -> null
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        if (summary == null) {
            Text("Pick a message.", color = MaterialTheme.colorScheme.outline)
            return@Column
        }
        Text(summary.subject, style = MaterialTheme.typography.titleLarge)
        Text(
            "${summary.from}    ${summary.receivedAt.asLocalTime()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (rendered == null) {
            if (body == null) CircularProgressIndicator() else Text("This message has no readable body.")
            return@Column
        }
        if (rendered.blockedImages > 0) {
            Text(
                "${rendered.blockedImages} image${if (rendered.blockedImages == 1) "" else "s"} were not loaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
            )
        }
        SelectionContainer { Text(rendered.text, modifier = Modifier.padding(top = 12.dp)) }
    }
}

private fun String.asLocalTime(): String =
    runCatching { WHEN.format(Instant.parse(this)) }.getOrDefault(this)
