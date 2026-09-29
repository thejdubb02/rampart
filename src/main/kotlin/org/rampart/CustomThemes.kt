package org.rampart

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
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

    fun decode(text: String): Theme {
        val root = json.parseToJsonElement(text).jsonObject
        fun required(name: String): String = root[name]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Theme JSON is missing $name.")
        val label = required("name").trim()
        require(label.isNotEmpty()) { "Theme name cannot be empty." }
        return Theme(
            key = customThemeKey(label),
            label = label,
            dark = root["dark"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: throw IllegalArgumentException("Theme JSON has an invalid dark value."),
            art = null,
            background = parseThemeColour(required("background")),
            surface = parseThemeColour(required("surface")),
            surfaceVariant = parseThemeColour(required("surfaceVariant")),
            selection = parseThemeColour(required("selection")),
            text = parseThemeColour(required("text")),
            muted = parseThemeColour(required("muted")),
            line = parseThemeColour(required("line")),
            accent = parseThemeColour(required("accent")),
            onAccent = parseThemeColour(required("onAccent")),
        )
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
    }
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
