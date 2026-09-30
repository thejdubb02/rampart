package org.rampart

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The stable prefix keeps custom names from ever colliding with built-in theme keys. */
private const val CUSTOM_THEME_PREFIX = "custom:"

/** The JSON format is deliberately small and independent of Compose's internal colour representation. */
internal object ThemeJson {
    private val json = Json { prettyPrint = true }

    fun encode(theme: Theme): String = json.encodeToString(JsonObject.serializer(), objectOf(theme))

    /**
     * A hybrid theme carries a light reading page under `page`.
     *
     * The page is the same kind of colours as the window, drawn light, and it is only
     * valid when the window itself is dark. A light theme that also claims a page is
     * rejected: there is no second surface to put it on. A `description` may be present
     * and is ignored, so a catalogue file can explain itself without becoming part of
     * the theme. Files written before pages existed have no `page` and load unchanged.
     */
    fun decode(text: String): Theme {
        val root = json.parseToJsonElement(text).jsonObject
        val label = required(root, "name").trim()
        require(label.isNotEmpty()) { "Theme name cannot be empty." }
        val dark = root["dark"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("Theme JSON has an invalid dark value.")
        val theme = palette(root, customThemeKey(label), label, dark)
        val pageNode = root["page"]
        if (pageNode == null || pageNode is JsonNull) return theme
        if (!dark) throw IllegalArgumentException("A theme with a reading page has to be dark.")
        val page = palette(pageNode.jsonObject, "${theme.key}-page", label, dark = false)
        return theme.copy(page = page)
    }

    internal fun objectOf(theme: Theme): JsonObject = buildJsonObject {
        put("name", theme.label)
        put("dark", theme.dark)
        put("background", themeColourString(theme.background))
        put("surface", themeColourString(theme.surface))
        put("surfaceVariant", themeColourString(theme.surfaceVariant))
        put("selection", themeColourString(theme.selection))
        put("text", themeColourString(theme.text))
        put("muted", themeColourString(theme.muted))
        put("line", themeColourString(theme.line))
        put("accent", themeColourString(theme.accent))
        put("onAccent", themeColourString(theme.onAccent))
        // Omitted on an ordinary theme, so a file that never had a page stays the file it was.
        theme.page?.let { page ->
            put("page", buildJsonObject {
                put("background", themeColourString(page.background))
                put("surface", themeColourString(page.surface))
                put("surfaceVariant", themeColourString(page.surfaceVariant))
                put("selection", themeColourString(page.selection))
                put("text", themeColourString(page.text))
                put("muted", themeColourString(page.muted))
                put("line", themeColourString(page.line))
                put("accent", themeColourString(page.accent))
                put("onAccent", themeColourString(page.onAccent))
            })
        }
    }

    private fun palette(obj: JsonObject, key: String, label: String, dark: Boolean): Theme = Theme(
        key = key,
        label = label,
        dark = dark,
        art = null,
        background = parseThemeColour(required(obj, "background")),
        surface = parseThemeColour(required(obj, "surface")),
        surfaceVariant = parseThemeColour(required(obj, "surfaceVariant")),
        selection = parseThemeColour(required(obj, "selection")),
        text = parseThemeColour(required(obj, "text")),
        muted = parseThemeColour(required(obj, "muted")),
        line = parseThemeColour(required(obj, "line")),
        accent = parseThemeColour(required(obj, "accent")),
        onAccent = parseThemeColour(required(obj, "onAccent")),
        // A page is a palette, not a theme that has its own page.
        page = null,
    )

    private fun required(obj: JsonObject, name: String): String = obj[name]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("Theme JSON is missing $name.")
}

internal fun customThemeKey(name: String): String = CUSTOM_THEME_PREFIX + name.trim().lowercase()
    .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "theme" }

internal fun parseThemeColour(value: String): Color {
    require(Regex("^#[0-9a-fA-F]{6}$").matches(value)) { "$value is not a colour in #RRGGBB form." }
    return Color(0xFF000000L or value.drop(1).toLong(16))
}

internal fun themeColourString(colour: Color): String = "#%02X%02X%02X".format(
    (colour.red * 255).toInt(),
    (colour.green * 255).toInt(),
    (colour.blue * 255).toInt(),
)

/** WCAG 2 relative contrast ratio. Alpha is not accepted by the theme JSON format. */
internal fun contrastRatio(first: Color, second: Color): Double {
    fun luminance(colour: Color): Double {
        fun channel(value: Float): Double {
            val linear = value.toDouble()
            return if (linear <= 0.04045) linear / 12.92 else ((linear + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(colour.red) + 0.7152 * channel(colour.green) + 0.0722 * channel(colour.blue)
    }
    val one = luminance(first)
    val two = luminance(second)
    return (max(one, two) + 0.05) / (min(one, two) + 0.05)
}
