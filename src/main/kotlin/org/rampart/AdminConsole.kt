package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.awt.Desktop

/**
 * The Stalwart admin console: domains and accounts first, every other page later, all of
 * them drawn by [AdminFormBody] from the server's own schema.
 *
 * Class 3 in `docs/self-hosting.md`: it needs the person's own Stalwart server and an admin
 * login for it, and it is hidden, not greyed out, wherever that is missing. It lives in its
 * own window because it is a different job from reading mail, done with a different
 * credential, and because keeping it out of the main window keeps it out of the mail code.
 */

/** Signed in, with what the login may do and the schema to draw it with. */
internal class AdminConnection(val client: AdminClient, val access: AdminAccess, val schema: AdminSchema) {
    /** The pages this login can open, in the server's menu order. */
    val pages: List<Pair<AdminMenuEntry, AdminObject>> = adminPages(schema, access)
}

internal object AdminConsole {
    /** Whether the admin window is showing. */
    var open by mutableStateOf(false)

    /** Null until a saved login has been checked, and whenever there is none. */
    var connection by mutableStateOf<AdminConnection?>(null)

    /** Why the saved login did not work, in a sentence, for the settings page to show. */
    var problem by mutableStateOf<String?>(null)

    private var checked = false

    /** Signs in with the saved login, once per run. Nothing at all happens when none is saved. */
    suspend fun checkOnce() {
        if (checked) return
        checked = true
        val saved = withContext(Dispatchers.IO) { AdminLogin.load() } ?: return
        runCatching { connect(saved) }
    }

    /** Signs in and keeps the result. Throws [AdminError] with the reason when it cannot. */
    suspend fun connect(credential: AdminCredential): AdminConnection = try {
        val made = withContext(Dispatchers.IO) {
            val client = AdminClient.connect(credential)
            AdminConnection(client, client.access(), client.schema())
        }
        connection = made
        problem = null
        made
    } catch (e: AdminError) {
        connection = null
        problem = e.message
        throw e
    }

    fun forget() {
        AdminLogin.forget()
        connection = null
        problem = null
        open = false
    }

    /** The server's own web console, for the fields and pages this build does not draw yet. */
    fun openWebConsole() {
        val base = connection?.client?.base ?: return
        runCatching { Desktop.getDesktop().browse(base) }
    }
}

/**
 * The sidebar button. Draws nothing at all unless a saved admin login works and may open
 * at least one page, which is what "hidden on an account that cannot do it" means.
 */
@Composable
internal fun AdminSidebarButton(size: Dp) {
    LaunchedEffect(Unit) { AdminConsole.checkOnce() }
    val connection = AdminConsole.connection ?: return
    if (connection.pages.isEmpty()) return
    SidebarTooltip("Server admin") {
        IconButton(onClick = { AdminConsole.open = !AdminConsole.open }, modifier = Modifier.size(size)) {
            Icon(
                AdminIcon,
                contentDescription = "Server admin",
                tint = if (AdminConsole.open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The admin window, when it is open. Called from inside the main window's theme so the
 * two look the same; the colours are carried across explicitly rather than trusted to
 * travel into a second native window by themselves.
 */
@Composable
internal fun AdminWindow() {
    if (!AdminConsole.open) return
    val connection = AdminConsole.connection ?: return
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Window(
        onCloseRequest = { AdminConsole.open = false },
        title = "Rampart server admin",
        state = rememberWindowState(size = DpSize(1180.dp, 800.dp)),
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            Surface(Modifier.fillMaxSize()) {
                AdminConsolePane(connection)
            }
        }
    }
}

/** What the right-hand side is showing: a list, one object, or a new one being made. */
private sealed interface AdminView {
    data class Listing(val entry: AdminMenuEntry, val obj: AdminObject) : AdminView
    data class Detail(val entry: AdminMenuEntry, val obj: AdminObject, val id: String?) : AdminView
}

@Composable
internal fun AdminConsolePane(connection: AdminConnection) {
    val first = connection.pages.firstOrNull()
    var view by remember(connection) {
        mutableStateOf<AdminView?>(first?.let { AdminView.Listing(it.first, it.second) })
    }
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.width(232.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(vertical = 14.dp, horizontal = 10.dp),
        ) {
            Text(
                connection.client.base.host.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 10.dp, bottom = 4.dp),
            )
            var heading: String? = null
            connection.pages.forEach { (entry, obj) ->
                if (entry.group != heading) {
                    heading = entry.group
                    // A link the server puts at the top level has no group, and gets no heading.
                    if (entry.group.isNotEmpty()) Text(
                        entry.group.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 5.dp),
                    )
                }
                val here = when (val v = view) {
                    is AdminView.Listing -> v.entry == entry
                    is AdminView.Detail -> v.entry == entry
                    null -> false
                }
                Text(
                    entry.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .background(if (here) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { view = AdminView.Listing(entry, obj) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = AdminConsole::openWebConsole) {
                Text("Everything else is in the server's web console", style = MaterialTheme.typography.bodySmall)
            }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
            Column(Modifier.widthIn(max = 880.dp).fillMaxWidth()) {
                when (val v = view) {
                    is AdminView.Listing -> key(v) {
                        AdminListPage(connection, v.entry, v.obj, onOpen = { id -> view = AdminView.Detail(v.entry, v.obj, id) })
                    }
                    is AdminView.Detail -> key(v) {
                        AdminDetailPage(connection, v.entry, v.obj, v.id, onBack = { view = AdminView.Listing(v.entry, v.obj) })
                    }
                    null -> FaultText("This admin login cannot open any page Rampart knows how to draw yet.")
                }
            }
        }
    }
}

private const val PAGE_SIZE = 50

@Composable
private fun AdminListPage(connection: AdminConnection, entry: AdminMenuEntry, obj: AdminObject, onOpen: (String?) -> Unit) {
    val schema = connection.schema
    val list = remember(obj) { schema.listFor(entry.viewName) ?: schema.listFor(obj.objectName) }
    var position by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf<AdminPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    // The columns as the server's list names them; the label property first when there are none.
    val columns = remember(list) {
        list?.columns?.takeIf { it.isNotEmpty() } ?: listOf(AdminColumn(list?.labelProperty ?: "name", "Name"))
    }
    LaunchedEffect(position) {
        loading = true
        error = null
        try {
            page = withContext(Dispatchers.IO) {
                connection.client.page(obj, list, columns.map { it.name }, position, PAGE_SIZE)
            }
        } catch (e: AdminError) {
            error = e.message
        }
        loading = false
    }

    Section(list?.title ?: entry.label, list?.subtitle?.ifBlank { null } ?: obj.description)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (connection.access.can(obj, "Create")) {
            Button(onClick = { onOpen(null) }) { Text("New ${list?.singular ?: "item"}") }
            Spacer(Modifier.width(12.dp))
        }
        if (loading) Spinner(size = 20.dp, thickness = 2.dp)
    }
    Spacer(Modifier.height(12.dp))
    error?.let {
        FaultText(it)
        return
    }
    val current = page ?: return
    // Types for each column come from the field set the rows share, so a column of
    // durations reads as durations and an enum reads as its label.
    val fieldSet = schema.fieldSetFor(obj.objectName, current.rows.firstOrNull() ?: list?.staticFilter)
    val fieldTypes = remember(fieldSet) {
        fieldSet?.let { set -> schema.form(set, connection.access.enterprise).fields.associate { it.name to it.type } }.orEmpty()
    }
    val choices = { name: String -> schema.choices(name) }
    val variants = { name: String -> (schema.shapeOf(name) as? AdminShape.Multiple)?.variants.orEmpty() }
    val weights = columns.mapIndexed { i, _ -> if (i == 0) 2f else 1f }
    AdminRow(columns.map { it.label }, weights, header = true, onClick = null)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (current.rows.isEmpty()) {
        Text(
            "There are no ${list?.plural ?: "items"} yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(12.dp),
        )
    }
    current.rows.forEach { row ->
        val id = (row["id"] as? JsonPrimitive)?.contentOrNull
        AdminRow(
            columns.map { c ->
                val type = fieldTypes[c.name] ?: AdminType.Text("string", true)
                displayValue(type, row[c.name], choices, variants)
            },
            weights,
            header = false,
            onClick = id?.let { { onOpen(it) } },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
    val total = current.total
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                total == null -> "${current.rows.size} shown"
                total == 0 -> ""
                else -> "${position + 1} to ${position + current.rows.size} of $total"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.weight(1f))
        if (position > 0) TextButton(onClick = { position = (position - PAGE_SIZE).coerceAtLeast(0) }) { Text("Previous") }
        val more = if (total != null) position + current.rows.size < total else current.rows.size == PAGE_SIZE
        if (more) TextButton(onClick = { position += PAGE_SIZE }) { Text("Next") }
    }
}

/**
 * One object: read-only first, then editable, and never saved without the list of what
 * changed being shown and agreed to. [id] null is a new one, started from the server's
 * defaults.
 */
@Composable
private fun AdminDetailPage(
    connection: AdminConnection,
    entry: AdminMenuEntry,
    obj: AdminObject,
    id: String?,
    onBack: () -> Unit,
) {
    val schema = connection.schema
    val enterprise = connection.access.enterprise
    val scope = rememberCoroutineScope()
    val list = remember(obj) { schema.listFor(entry.viewName) ?: schema.listFor(obj.objectName) }
    val creating = id == null
    var original by remember { mutableStateOf<JsonObject?>(null) }
    var draft by remember { mutableStateOf<JsonObject?>(null) }
    var editing by remember { mutableStateOf(creating) }
    // Bumped whenever the draft is reset from the server, so every input box starts again
    // from the new value rather than keeping what was typed into the old one.
    var generation by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<List<AdminChange>?>(null) }
    val inputErrors = remember { mutableStateMapOf<String, String>() }
    var problems by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var currentId by remember { mutableStateOf(id) }

    suspend fun load(which: String) {
        error = null
        try {
            val got = withContext(Dispatchers.IO) { connection.client.one(obj, which) }
            original = got
            draft = got
            generation++
        } catch (e: AdminError) {
            error = e.message
        }
    }

    LaunchedEffect(Unit) {
        if (id == null) {
            // The account pages are a view of accounts of one kind, and the kind is the
            // view's own fixed filter: a new account made from the Users page is a User.
            val variant = (list?.staticFilter?.get("@type") as? JsonPrimitive)?.contentOrNull
            val start = startingValue(schema, obj.objectName, variant, enterprise)
            original = JsonObject(emptyMap())
            draft = start
        } else {
            load(id)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
            Icon(RampartIcons.Back, contentDescription = "Back to the list", modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        val name = draft?.let { d ->
            listOfNotNull(list?.labelProperty, "name", "emailAddress")
                .firstNotNullOfOrNull { (d[it] as? JsonPrimitive)?.contentOrNull?.ifBlank { null } }
        }
        Text(
            if (creating && currentId == null) "New ${list?.singular ?: "item"}" else name ?: entry.label,
            style = MaterialTheme.typography.titleLarge,
        )
    }
    Spacer(Modifier.height(8.dp))
    error?.let { FaultText(it, modifier = Modifier.padding(vertical = 6.dp)) }
    notice?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    val value = draft ?: run {
        if (error == null) Spinner(size = 24.dp, thickness = 2.dp)
        return
    }
    val fieldSet = schema.fieldSetFor(obj.objectName, value)
    if (fieldSet == null) {
        FaultText("The server's schema does not describe this kind of ${obj.objectName.removePrefix("x:").lowercase()}, so Rampart cannot draw it.")
        return
    }
    val form = remember(fieldSet) { schema.form(fieldSet, enterprise) }
    val canSave = if (creating && currentId == null) connection.access.can(obj, "Create") else connection.access.can(obj, "Update")

    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!editing) {
            if (canSave) Button(onClick = { editing = true; notice = null }) { Text("Edit") }
        } else {
            Button(
                enabled = !saving,
                onClick = {
                    val local = draftProblems(form, value, creating && currentId == null)
                    problems = local
                    if (inputErrors.isNotEmpty() || local.isNotEmpty()) {
                        error = "Some values need fixing first. They are marked below."
                        return@Button
                    }
                    error = null
                    val changes = describeChanges(schema, form, original ?: JsonObject(emptyMap()), value, enterprise)
                    if (changes.isEmpty()) {
                        notice = "Nothing has changed, so there is nothing to save."
                        return@Button
                    }
                    confirm = changes
                },
            ) { Text("Review and save") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(enabled = !saving, onClick = {
                if (creating && currentId == null) {
                    onBack()
                } else {
                    draft = original
                    editing = false
                    inputErrors.clear()
                    problems = emptyMap()
                    error = null
                    generation++
                }
            }) { Text("Cancel") }
            if (saving) {
                Spacer(Modifier.width(12.dp))
                Spinner(size = 20.dp, thickness = 2.dp)
            }
        }
    }

    key(generation, editing) {
        AdminFormBody(
            schema = schema,
            form = form,
            value = value,
            editing = editing,
            creating = creating && currentId == null,
            enterprise = enterprise,
            problems = problems,
            onValue = { draft = it; notice = null },
            onInputError = { path, why -> if (why == null) inputErrors.remove(path) else inputErrors[path] = why },
            onOpenConsole = AdminConsole::openWebConsole,
        )
    }

    confirm?.let { changes ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (currentId == null) "Create this ${list?.singular ?: "item"}?" else "Save these changes?") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "This changes the server straight away, for everybody who uses it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    ChangeList(changes)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    saving = true
                    scope.launch {
                        val isNew = currentId == null
                        val body = changedFields(form, original ?: JsonObject(emptyMap()), value, creating = isNew)
                        try {
                            val saved = withContext(Dispatchers.IO) { connection.client.save(obj, currentId, body) }
                            if (saved.refusal != null) {
                                error = saved.refusal
                                problems = saved.problems
                            } else {
                                val newId = saved.id ?: currentId
                                currentId = newId
                                editing = false
                                inputErrors.clear()
                                problems = emptyMap()
                                notice = if (isNew) "Created." else "Saved."
                                if (newId != null) load(newId)
                            }
                        } catch (e: AdminError) {
                            error = e.message
                        }
                        saving = false
                    }
                }) { Text(if (currentId == null) "Create" else "Save") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Go back") } },
        )
    }
}

/**
 * The settings page where the admin login is entered, checked and kept.
 *
 * Deliberately a separate login from any mail account, stored under its own name in the
 * operating system's credential store. See "Two credentials, never one" in
 * `docs/architecture.md`.
 */
@Composable
internal fun AdminLoginPage() {
    val scope = rememberCoroutineScope()
    val saved = remember { AdminLogin.saved() }
    var server by remember { mutableStateOf(saved.first) }
    var user by remember { mutableStateOf(saved.second) }
    var secret by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var worked by remember { mutableStateOf(false) }
    val connection = AdminConsole.connection

    Section(
        "Server admin",
        "Manage your own Stalwart mail server from Rampart: its domains and accounts to begin " +
            "with. This needs Stalwart 0.16 or later and a login on it that is allowed to administer it.",
    )
    Text(
        "This is a separate login from your mailbox, kept in this machine's credential store " +
            "under its own name. Your mail never uses it, and nothing that reads your mail can reach it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = server,
        onValueChange = { server = it; result = null },
        label = { Text("Your Stalwart server") },
        placeholder = { Text("mail.example.com") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = user,
        onValueChange = { user = it; result = null },
        label = { Text("Admin user name, or leave empty for an API key") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = secret,
        onValueChange = { secret = it; result = null },
        label = { Text(if (user.isBlank()) "API key" else "Password") },
        placeholder = { if (saved.first.isNotBlank()) Text("Unchanged") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !checking && server.isNotBlank(),
            onClick = {
                checking = true
                result = null
                scope.launch {
                    // A blank box keeps the saved secret, so changing only the server does not mean retyping it.
                    val kept = secret.ifEmpty { withContext(Dispatchers.IO) { AdminLogin.load()?.secret }.orEmpty() }
                    val credential = AdminCredential(server.trim(), user.trim(), kept)
                    try {
                        val made = AdminConsole.connect(credential)
                        val stored = withContext(Dispatchers.IO) { AdminLogin.save(credential) }
                        worked = made.pages.isNotEmpty()
                        result = when {
                            made.pages.isEmpty() -> "Signed in, but this login is not allowed to manage domains or accounts, so there is nothing to show."
                            stored != null -> "Signed in, but the login was not kept: $stored"
                            else -> "Signed in. The admin button is in the sidebar."
                        }
                        secret = ""
                    } catch (e: AdminError) {
                        result = e.message
                        worked = false
                    }
                    checking = false
                }
            },
        ) { Text(if (checking) "Checking" else "Check and save") }
        if (checking) {
            Spacer(Modifier.width(12.dp))
            Spinner(size = 20.dp, thickness = 2.dp)
        }
        if (saved.first.isNotBlank() || connection != null) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                AdminConsole.forget()
                server = ""
                user = ""
                secret = ""
                result = "Forgotten. The admin button is gone from the sidebar."
                worked = false
            }) { Text("Forget this login") }
        }
    }
    (result ?: AdminConsole.problem)?.let {
        Spacer(Modifier.height(10.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (worked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
    if (connection != null && connection.pages.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "This login can open: " + connection.pages.joinToString(", ") { it.first.label } + ".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * A server rack, two units high, drawn the way the icon packs in `Icons.kt` draw theirs:
 * an 18 unit grid, stroked at the weight of the text. Kept here rather than added to every
 * pack, since only this button uses it.
 */
internal val AdminIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Admin",
        defaultWidth = 18.dp,
        defaultHeight = 18.dp,
        viewportWidth = 18f,
        viewportHeight = 18f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString("M3 3h12v5H3z M3 10h12v5H3z M5.5 5.5h.01 M5.5 12.5h.01").toNodes(),
            // Icon() recolours whatever is drawn, so the colour here is only a placeholder.
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}
