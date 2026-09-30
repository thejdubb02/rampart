package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Themes and message templates fetched from a catalogue.
 *
 * An item is colours or text. Nothing downloaded here is executed, evaluated, loaded as
 * a class, or used as a path on disk. An add-on that ran code could read all your mail
 * and your keys, so the catalogue stays data.
 *
 * Nothing in this file runs until the catalogue page is open. There is no startup fetch
 * and no timer. The index is trusted only for the size and SHA-256 it claims: the bytes
 * are checked against both before they are read as a theme or a template.
 */

/** The catalogue Rampart ships with. Blank in settings means this address. */
internal const val DEFAULT_CATALOGUE =
    "https://raw.githubusercontent.com/thejdubb02/rampart-catalogue/main/index.json"

/** Shown when the index says it is newer than this copy of Rampart knows how to read. */
internal const val CATALOGUE_NEWER = "This catalogue needs a newer Rampart."

/** Shown on the row, and nothing is saved, when the bytes are not the ones the index named. */
internal const val CATALOGUE_MISMATCH = "The download did not match the catalogue, so it was not added."

/** The index, and no more. Read one byte past this and the catalogue is refused. */
internal const val CATALOGUE_INDEX_CAP = 256 * 1024

/** One theme or template, and no more. An index entry larger than this is not downloaded. */
internal const val CATALOGUE_ITEM_CAP = 8 * 1024

private const val REDIRECT_LIMIT = 5

private val HEX_COLOUR = Regex("^#[0-9A-Fa-f]{6}$")
private val HEX_SHA256 = Regex("^[0-9a-fA-F]{64}$")

/**
 * One catalogue entry, already checked far enough to be listed.
 *
 * [path] is still only a relative name. It becomes a URL through [safeItemUrl], and the
 * bytes at that URL are still checked with [verified] before they are parsed.
 */
internal data class CatalogueItem(
    val kind: String,
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val path: String,
    val size: Int,
    val sha256: String,
    val preview: CataloguePreview,
)

/**
 * The little the list draws, and nothing else.
 *
 * A theme preview is enough to paint a swatch. A template preview is the subject and the
 * first line. The full file is fetched only when the person adds it.
 */
internal sealed interface CataloguePreview {
    data class Theme(
        val dark: Boolean,
        val background: String,
        val surface: String,
        val text: String,
        val accent: String,
        val pageBackground: String? = null,
        val pageText: String? = null,
        val pageAccent: String? = null,
    ) : CataloguePreview

    data class Template(val subject: String, val firstLine: String) : CataloguePreview
}

/** A catalogue answer a person can read. Anything else thrown from the network is rewritten. */
internal class CatalogueProblem(message: String) : Exception(message)

/**
 * The index, as a list.
 *
 * Throws when the document is not JSON or its version is not 1, because those are the
 * whole catalogue being wrong. One bad entry is skipped: a future kind, a path that is
 * not a relative JSON file, a size over [CATALOGUE_ITEM_CAP], or a preview the list
 * cannot draw. The rest still load.
 */
internal fun parseIndex(text: String): List<CatalogueItem> {
    val root = Json.parseToJsonElement(text).jsonObject
    val version = root["version"]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("This catalogue's version is not one Rampart can read.")
    if (version > 1) throw IllegalArgumentException(CATALOGUE_NEWER)
    if (version != 1) throw IllegalArgumentException("This catalogue's version is not one Rampart can read.")
    val items = root["items"] as? JsonArray
        ?: throw IllegalArgumentException("This catalogue has no list of items.")
    return items.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        runCatching { readItem(obj) }.getOrNull()
    }
}

private fun readItem(obj: JsonObject): CatalogueItem? {
    val kind = text(obj, "kind") ?: return null
    if (kind != "theme" && kind != "template") return null
    val id = text(obj, "id")?.takeIf { it.isNotBlank() } ?: return null
    val name = text(obj, "name")?.takeIf { it.isNotBlank() } ?: return null
    val path = text(obj, "path") ?: return null
    if (safePath(path) == null) return null
    val size = obj["size"]?.jsonPrimitive?.longOrNull ?: return null
    if (size !in 1..CATALOGUE_ITEM_CAP) return null
    val sha256 = text(obj, "sha256")?.takeIf { HEX_SHA256.matches(it) }?.lowercase() ?: return null
    val previewObj = obj["preview"] as? JsonObject ?: return null
    val preview = if (kind == "theme") themePreview(previewObj) else templatePreview(previewObj)
    preview ?: return null
    return CatalogueItem(
        kind = kind,
        id = id,
        name = name,
        description = text(obj, "description").orEmpty(),
        author = text(obj, "author").orEmpty(),
        path = path,
        size = size.toInt(),
        sha256 = sha256,
        preview = preview,
    )
}

private fun themePreview(obj: JsonObject): CataloguePreview.Theme? {
    val dark = obj["dark"]?.jsonPrimitive?.booleanOrNull ?: return null
    val background = hex(obj, "background") ?: return null
    val surface = hex(obj, "surface") ?: return null
    val textColour = hex(obj, "text") ?: return null
    val accent = hex(obj, "accent") ?: return null
    val pageNode = obj["page"]
    if (pageNode == null || pageNode is JsonNull) {
        return CataloguePreview.Theme(dark, background, surface, textColour, accent)
    }
    val page = pageNode as? JsonObject ?: return null
    val pageBackground = hex(page, "background") ?: return null
    val pageText = hex(page, "text") ?: return null
    val pageAccent = hex(page, "accent") ?: return null
    return CataloguePreview.Theme(
        dark, background, surface, textColour, accent, pageBackground, pageText, pageAccent,
    )
}

private fun templatePreview(obj: JsonObject): CataloguePreview.Template? {
    val subject = text(obj, "subject") ?: return null
    val firstLine = text(obj, "firstLine") ?: return null
    return CataloguePreview.Template(subject, firstLine)
}

/**
 * The URL of one item, or null when [path] is not a relative file under the index.
 *
 * Resolved against the index so a catalogue cannot name a host of its own. `..`, a
 * leading slash, a scheme, a query, a backslash or an encoded character would be how a
 * path left the catalogue, so those are refused here, before any request.
 */
internal fun safeItemUrl(index: URI, path: String): URI? {
    if (safePath(path) == null || !plainHttps(index)) return null
    val resolved = runCatching { index.resolve(path) }.getOrNull() ?: return null
    return resolved.takeIf { plainHttps(it) && it.rawQuery == null }
}

/** https with a host and no username or password in it. */
private fun plainHttps(uri: URI): Boolean =
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null

/**
 * A relative `themes/...json` or `templates/...json`, or null.
 *
 * The checks are on the text, not on a normalised path: normalisation is what turns
 * `themes/../../secret` into something that looks safe after it has already escaped.
 */
private fun safePath(path: String): String? {
    if (path.isEmpty() || path != path.trim()) return null
    if (!(path.startsWith("themes/") || path.startsWith("templates/"))) return null
    if (!path.endsWith(".json")) return null
    if ('\\' in path || '?' in path || '#' in path || ':' in path || '%' in path) return null
    if (".." in path || "//" in path) return null
    if (path.any { it.isWhitespace() || it.code < 0x20 }) return null
    return path
}

/**
 * Whether these bytes are the item the index named.
 *
 * Both checks, not either. A file with the right hash and the wrong length is still not
 * the file, and it is rejected before anyone parses it.
 */
internal fun verified(bytes: ByteArray, item: CatalogueItem): Boolean {
    if (item.size !in 1..CATALOGUE_ITEM_CAP || bytes.size != item.size) return false
    val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    return hash.equals(item.sha256, ignoreCase = true)
}

/** The address to fetch, or a refusal. http is refused here, before a connection is opened. */
internal fun catalogueUri(address: String): URI {
    val trimmed = address.trim()
    if (trimmed.isEmpty()) throw CatalogueProblem("The catalogue address is empty.")
    val uri = runCatching { URI(trimmed) }.getOrElse {
        throw CatalogueProblem("That catalogue address could not be read.")
    }
    if (!plainHttps(uri)) throw CatalogueProblem("The catalogue address has to be https.")
    return uri
}

/**
 * Where a redirect is willing to go, or null.
 *
 * Redirects are followed, because a host may move. They are never followed off https,
 * and never to an address that carries a username or a password.
 */
internal fun redirectTarget(current: URI, location: String): URI? {
    val trimmed = location.trim()
    if (trimmed.isEmpty()) return null
    return runCatching { current.resolve(trimmed) }.getOrNull()?.takeIf(::plainHttps)
}

/** The host named in the contact line. Never the path, and never a username. */
internal fun catalogueHost(address: String): String =
    runCatching { URI(address.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: address.trim().ifBlank { "the catalogue" }

/**
 * [added] replaces any custom theme with the same key, and does not become the theme
 * in use. Choosing it stays a separate step, the same as importing a file.
 */
internal fun themesWith(existing: List<Theme>, added: Theme): List<Theme> =
    (existing.filterNot { it.key == added.key } + added).sortedBy { it.label.lowercase() }

internal fun installTheme(theme: Theme) {
    Settings.setCustomThemes(themesWith(Settings.customThemes(), theme))
}

/** [added] replaces a template of the same name and leaves every other one where it was. */
internal fun templatesWith(existing: List<Template>, added: Template): List<Template> =
    if (existing.any { it.name == added.name }) existing.map { if (it.name == added.name) added else it }
    else existing + added

internal fun installTemplate(template: Template, path: Path = Templates.file()) {
    Templates.write(templatesWith(Templates.read(path), template), path)
}

/**
 * A template file. Placeholders are kept as text: what they mean is decided when the
 * template is used, not when it is added. A description in the file is ignored.
 */
internal fun catalogueTemplate(text: String): Template {
    val root = Json.parseToJsonElement(text).jsonObject
    val name = text(root, "name")?.trim().orEmpty()
    require(name.isNotEmpty()) { "That template has no name." }
    val subject = text(root, "subject") ?: throw IllegalArgumentException("That template has no subject.")
    val body = text(root, "body") ?: throw IllegalArgumentException("That template has no body.")
    return Template(name, subject, body)
}

/** A theme is added when a custom theme already has the key its name would be saved under. */
internal fun catalogueAdded(item: CatalogueItem, themes: List<Theme>, templates: List<Template>): Boolean =
    when (item.kind) {
        "theme" -> themes.any { it.key == customThemeKey(item.name) }
        "template" -> templates.any { it.name == item.name }
        else -> false
    }

private val http: HttpClient by lazy {
    HttpClient.newBuilder()
        // Followed by hand, below. The client itself would walk an https address onto http.
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .build()
}

/** The index. Runs off the UI thread. Throws [CatalogueProblem] in a sentence. */
internal suspend fun loadIndex(address: String): List<CatalogueItem> = withContext(Dispatchers.IO) {
    try {
        val bytes = get(catalogueUri(address), CATALOGUE_INDEX_CAP, "That catalogue is larger than Rampart will read.")
        parseIndex(bytes.toString(Charsets.UTF_8))
    } catch (e: CancellationException) {
        throw e
    } catch (e: CatalogueProblem) {
        throw e
    } catch (e: IllegalArgumentException) {
        val message = e.message.orEmpty()
        throw CatalogueProblem(if (message.startsWith("This catalogue")) message else "That catalogue could not be read.")
    } catch (e: Exception) {
        throw CatalogueProblem(plainNetworkError(e, catalogueHost(address)))
    }
}

/**
 * Download [item], check it, and save it.
 *
 * Null when it was added. A sentence when it was not, in which case nothing was written.
 * The hash is checked before the bytes are parsed: parsing is what turns them into a
 * theme or a template, and an unverified file is not one.
 */
internal suspend fun addCatalogueItem(indexAddress: String, item: CatalogueItem): String? = withContext(Dispatchers.IO) {
    try {
        if (item.size !in 1..CATALOGUE_ITEM_CAP) {
            throw CatalogueProblem("That item is larger than Rampart will read.")
        }
        val url = safeItemUrl(catalogueUri(indexAddress), item.path)
            ?: throw CatalogueProblem("That item's address is not one Rampart will open.")
        val bytes = get(url, CATALOGUE_ITEM_CAP, "That item is larger than Rampart will read.")
        if (!verified(bytes, item)) throw CatalogueProblem(CATALOGUE_MISMATCH)
        val text = bytes.toString(Charsets.UTF_8)
        when (item.kind) {
            "theme" -> installTheme(ThemeJson.decode(text))
            "template" -> installTemplate(catalogueTemplate(text))
            else -> throw CatalogueProblem("Rampart does not know how to add this.")
        }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: CatalogueProblem) {
        e.message
    } catch (e: IllegalArgumentException) {
        e.message ?: "That could not be added."
    } catch (e: Exception) {
        plainNetworkError(e, catalogueHost(indexAddress))
    }
}

private fun get(start: URI, cap: Int, tooBig: String): ByteArray {
    var uri = start
    var hops = 0
    while (true) {
        val response = http.send(
            HttpRequest.newBuilder(uri)
                .header("User-Agent", "Rampart")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        val code = response.statusCode()
        if (code in 300..399) {
            closeQuietly(response.body())
            if (++hops > REDIRECT_LIMIT) throw CatalogueProblem("The catalogue kept redirecting and never sent the file.")
            val location = response.headers().firstValue("location").orElse("")
            if (location.isBlank()) throw CatalogueProblem("The catalogue redirected without saying where to.")
            uri = redirectTarget(uri, location)
                ?: throw CatalogueProblem("The catalogue redirected to an address that is not https.")
            continue
        }
        if (code != 200) {
            closeQuietly(response.body())
            throw CatalogueProblem("The catalogue answered HTTP $code instead of a file.")
        }
        return readLimited(response.body(), cap, tooBig)
    }
}

/**
 * At most [cap] bytes, counted as they arrive.
 *
 * Content-Length is not consulted. A server can claim a small file and send a large one,
 * and the extra byte past the cap is what makes "larger than the cap" detectable.
 */
private fun readLimited(body: InputStream, cap: Int, tooBig: String): ByteArray =
    body.use { it.readNBytes(cap + 1) }.also { if (it.size > cap) throw CatalogueProblem(tooBig) }

private fun closeQuietly(body: InputStream) {
    runCatching { body.close() }
}

private fun text(obj: JsonObject, name: String): String? {
    val value = obj[name] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: return null
    return primitive.contentOrNull
}

private fun hex(obj: JsonObject, name: String): String? {
    val value = text(obj, name) ?: return null
    return value.takeIf { HEX_COLOUR.matches(it) }
}

/**
 * The catalogue page.
 *
 * Composed only while this settings page is the one on screen, which is what keeps the
 * fetch off startup and off any timer. Changing the address at the bottom loads again.
 */
@Composable
internal fun CataloguePage() {
    var draft by remember { mutableStateOf(Settings.catalogue()) }
    var address by remember { mutableStateOf(draft.ifBlank { DEFAULT_CATALOGUE }) }
    var generation by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<CatalogueItem>>(emptyList()) }
    var themes by remember { mutableStateOf(Settings.customThemes()) }
    var templates by remember { mutableStateOf(Templates.read()) }
    var rowErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun commit(raw: String, force: Boolean = false) {
        val trimmed = raw.trim()
        val next = trimmed.ifBlank { DEFAULT_CATALOGUE }
        val storedChanged = trimmed != Settings.catalogue()
        if (storedChanged) Settings.setCatalogue(trimmed)
        draft = trimmed
        val changed = next != address
        address = next
        if (force || changed || storedChanged) {
            generation++
            loading = true
            error = null
            items = emptyList()
            rowErrors = emptyMap()
        }
    }

    LaunchedEffect(address, generation) {
        loading = true
        error = null
        try {
            items = loadIndex(address)
            loading = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            items = emptyList()
            error = e.message ?: "The catalogue could not be opened."
            loading = false
        }
    }

    fun add(item: CatalogueItem) {
        if (busy != null) return
        busy = item.path
        rowErrors = rowErrors - item.path
        val index = address
        scope.launch {
            try {
                val problem = addCatalogueItem(index, item)
                if (problem == null) {
                    themes = Settings.customThemes()
                    templates = Templates.read()
                } else {
                    rowErrors = rowErrors + (item.path to problem)
                }
            } finally {
                if (busy == item.path) busy = null
            }
        }
    }

    Section(
        "Catalogue",
        "Add a theme or a message template. An item is colours or text, and nothing else.",
    )
    Text(
        "Opening this contacts ${catalogueHost(address)}.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(14.dp))

    when {
        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(size = 22.dp, thickness = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Loading the catalogue.", style = MaterialTheme.typography.bodyMedium)
        }
        error != null -> Column {
            Text(
                error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { commit(draft, force = true) }) { Text("Retry") }
        }
        else -> {
            CatalogueGroup(
                title = "Themes",
                note = "A second swatch is the light page a hybrid theme reads messages on.",
                rows = items.filter { it.kind == "theme" },
                themes = themes,
                templates = templates,
                rowErrors = rowErrors,
                busy = busy,
                onAdd = { add(it) },
            )
            Spacer(Modifier.height(8.dp))
            CatalogueGroup(
                title = "Templates",
                note = "The subject and the first line. Placeholders stay as written until you use the template.",
                rows = items.filter { it.kind == "template" },
                themes = themes,
                templates = templates,
                rowErrors = rowErrors,
                busy = busy,
                onAdd = { add(it) },
            )
        }
    }

    Spacer(Modifier.height(22.dp))
    Section("Catalogue address", "Blank uses the catalogue Rampart ships with. Changing it loads that catalogue.")
    CatalogueAddress(draft, onDraft = { draft = it }, onCommit = { commit(draft) })
    TextButton(onClick = {
        draft = ""
        commit("", force = true)
    }) { Text("Use the default") }
}

@Composable
private fun CatalogueGroup(
    title: String,
    note: String,
    rows: List<CatalogueItem>,
    themes: List<Theme>,
    templates: List<Template>,
    rowErrors: Map<String, String>,
    busy: String?,
    onAdd: (CatalogueItem) -> Unit,
) {
    Section(title, note)
    if (rows.isEmpty()) {
        Text(
            "None in this catalogue.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    rows.forEach { item ->
        CatalogueRow(
            item = item,
            added = catalogueAdded(item, themes, templates),
            error = rowErrors[item.path],
            busy = busy == item.path,
            enabled = busy == null || busy == item.path,
            onAdd = { onAdd(item) },
        )
    }
}

@Composable
private fun CatalogueRow(
    item: CatalogueItem,
    added: Boolean,
    error: String?,
    busy: Boolean,
    enabled: Boolean,
    onAdd: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (val preview = item.preview) {
            is CataloguePreview.Theme -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Swatch(preview.background, preview.text, preview.accent)
                if (preview.pageBackground != null && preview.pageText != null && preview.pageAccent != null) {
                    Swatch(preview.pageBackground, preview.pageText, preview.pageAccent)
                }
            }
            is CataloguePreview.Template -> Unit
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (item.description.isNotBlank()) {
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (item.author.isNotBlank()) {
                Text(
                    "by ${item.author}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (item.preview is CataloguePreview.Template) {
                Text(item.preview.subject, style = MaterialTheme.typography.bodySmall)
                Text(
                    item.preview.firstLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (!error.isNullOrBlank()) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (added) {
            OutlinedButton(onClick = {}, enabled = false) { Text("Added") }
        } else {
            OutlinedButton(onClick = onAdd, enabled = enabled && !busy) { Text(if (busy) "Adding" else "Add") }
        }
    }
}

@Composable
private fun Swatch(background: String, text: String, accent: String) {
    Box(
        Modifier.size(46.dp, 30.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(parseThemeColour(background))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = 5.dp)
                .size(16.dp, 5.dp)
                .background(parseThemeColour(accent), RoundedCornerShape(1.dp)),
        )
        Box(
            Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 6.dp)
                .size(24.dp, 3.dp)
                .background(parseThemeColour(text), RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
private fun CatalogueAddress(value: String, onDraft: (String) -> Unit, onCommit: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().height(38.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                DEFAULT_CATALOGUE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onDraft,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
                val was = focused
                focused = state.isFocused
                if (was && !state.isFocused) onCommit()
            }.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    onCommit()
                    true
                } else {
                    false
                }
            },
        )
    }
}
