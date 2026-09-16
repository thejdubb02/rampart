package org.rampart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/** Bulwark's red. See docs/architecture.md. */
private val RampartRed = Color(0xFFDB2D54)

private val http: HttpClient = HttpClient.newBuilder()
    // Redirects are followed by hand: HttpClient drops the Authorization header across one,
    // and Stalwart answers /.well-known/jmap with a 307 to the real session URL.
    .followRedirects(HttpClient.Redirect.NEVER)
    .connectTimeout(Duration.ofSeconds(15))
    .build()

/** Fetches the JMAP Session document (RFC 8620 section 2), the first thing any client asks for. */
private fun fetchSession(server: String, user: String, password: String): String {
    val base = server.trim().removeSuffix("/").let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
    }
    val credential = "Basic " + Base64.getEncoder()
        .encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    fun get(url: URI): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(url)
            .header("Authorization", credential)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    var response = get(URI.create("$base/.well-known/jmap"))
    if (response.statusCode() in 300..399) {
        val location = response.headers().firstValue("location").orElse("")
        if (location.isBlank()) return "Redirect with no Location header."
        response = get(response.uri().resolve(location))
    }
    return "HTTP ${response.statusCode()}\n\n${response.body()}"
}

@Composable
private fun App() {
    var server by remember { mutableStateOf("mail.example.org") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = lightColorScheme(primary = RampartRed)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Rampart", style = MaterialTheme.typography.headlineMedium)
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Email address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("App password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        result = ""
                        scope.launch {
                            result = withContext(Dispatchers.IO) {
                                runCatching { fetchSession(server, user, password) }
                                    .getOrElse { "Failed: ${it.javaClass.simpleName}: ${it.message}" }
                            }
                            busy = false
                        }
                    },
                ) {
                    Text(if (busy) "Connecting" else "Connect")
                }
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Rampart") {
        App()
    }
}
