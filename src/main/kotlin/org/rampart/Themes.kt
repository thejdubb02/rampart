package org.rampart

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp

/**
 * A named palette, and optionally a character to sit in the corner of the reading pane.
 *
 * Ported from Clique, which is where these were drawn and tuned. A theme is one block of
 * data here and nothing else needs to change to add another: the Material scheme is derived
 * rather than written out, so a new theme cannot forget a colour or set one that disagrees
 * with the rest of it.
 */
data class Theme(
    val key: String,
    val label: String,
    val dark: Boolean,
    /** A file under resources/art, drawn faintly in the bottom corner. */
    val art: String?,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val selection: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
) {
    fun scheme(): ColorScheme {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = selection,
            onPrimaryContainer = text,
            background = background,
            onBackground = text,
            surface = surface,
            onSurface = text,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = text,
            outline = muted,
            outlineVariant = line,
        )
    }
}

/**
 * Rampart's own two come first, because they are the ones cut from the brand rather than
 * borrowed, and the rest follow in the order Clique lists them.
 *
 * Two things about the Rampart pair that are not obvious from the numbers. The neutrals are
 * genuinely neutral rather than Material's faintly purple baseline, which under a red
 * primary reads as a cast over everything. And the red is lifted on dark: #DB2D54 on a
 * near-black background falls under the contrast a link needs to be read at body size.
 */
val THEMES: List<Theme> = listOf(
    Theme(
        key = "rampart-light",
        label = "Rampart light",
        dark = false,
        art = null,
        background = Color(0xFFFAFAFB),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF4F4F6),
        selection = Color(0xFFFDECF0),
        text = Color(0xFF17171B),
        muted = Color(0xFF63636E),
        line = Color(0xFFE6E6EB),
        accent = Color(0xFFDB2D54),
        onAccent = Color(0xFFFFFFFF),
    ),
    Theme(
        key = "rampart-dark",
        label = "Rampart dark",
        dark = true,
        art = null,
        background = Color(0xFF16161B),
        surface = Color(0xFF121216),
        surfaceVariant = Color(0xFF1C1C23),
        selection = Color(0xFF2A1A21),
        text = Color(0xFFECECF1),
        muted = Color(0xFF9B9BA8),
        line = Color(0xFF272730),
        accent = Color(0xFFFF7A96),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "clique-dark",
        label = "Clique dark",
        dark = true,
        art = null,
        background = Color(0xFF1E1E1E),
        surface = Color(0xFF252526),
        surfaceVariant = Color(0xFF2A2D2E),
        selection = Color(0xFF04395E),
        text = Color(0xFFCCCCCC),
        muted = Color(0xFF8B8B8B),
        line = Color(0xFF333333),
        accent = Color(0xFF0078D4),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "light",
        label = "Clique light",
        dark = false,
        art = null,
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFF3F3F3),
        surfaceVariant = Color(0xFFE8E8E8),
        selection = Color(0xFFCFE6FF),
        text = Color(0xFF24292F),
        muted = Color(0xFF57606A),
        line = Color(0xFFD8DEE4),
        accent = Color(0xFF0969DA),
        onAccent = Color(0xFFFFFFFF),
    ),
    Theme(
        key = "dracula",
        label = "Dracula",
        dark = true,
        art = null,
        background = Color(0xFF282A36),
        surface = Color(0xFF21222C),
        surfaceVariant = Color(0xFF343746),
        selection = Color(0xFF44475A),
        text = Color(0xFFF8F8F2),
        muted = Color(0xFF6272A4),
        line = Color(0xFF191A21),
        accent = Color(0xFFBD93F9),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "nord",
        label = "Nord",
        dark = true,
        art = null,
        background = Color(0xFF2E3440),
        surface = Color(0xFF3B4252),
        surfaceVariant = Color(0xFF434C5E),
        selection = Color(0xFF4C566A),
        text = Color(0xFFECEFF4),
        muted = Color(0xFF8FA1B3),
        line = Color(0xFF3B4252),
        accent = Color(0xFF88C0D0),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "gruvbox",
        label = "Gruvbox",
        dark = true,
        art = null,
        background = Color(0xFF282828),
        surface = Color(0xFF32302F),
        surfaceVariant = Color(0xFF3C3836),
        selection = Color(0xFF504945),
        text = Color(0xFFEBDBB2),
        muted = Color(0xFFA89984),
        line = Color(0xFF3C3836),
        accent = Color(0xFFFABD2F),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "tokyonight",
        label = "Tokyo Night",
        dark = true,
        art = null,
        background = Color(0xFF1A1B26),
        surface = Color(0xFF16161E),
        surfaceVariant = Color(0xFF232433),
        selection = Color(0xFF283457),
        text = Color(0xFFC0CAF5),
        muted = Color(0xFF787C99),
        line = Color(0xFF232433),
        accent = Color(0xFF7AA2F7),
        onAccent = Color(0xFF000000),
    ),
    // Solarized picks its colours by a fixed lightness relationship, so lightening the row
    // and selection the way every other theme here does walks body text down to 3.9:1.
    // Darkening them instead keeps the highlight visible and the text on it readable, and
    // leaves the two colours Solarized is actually recognised by untouched.
    Theme(
        key = "solarized",
        label = "Solarized Dark",
        dark = true,
        art = null,
        background = Color(0xFF002B36),
        surface = Color(0xFF073642),
        surfaceVariant = Color(0xFF05303B),
        selection = Color(0xFF04262F),
        text = Color(0xFF93A1A1),
        muted = Color(0xFF657B83),
        line = Color(0xFF073642),
        accent = Color(0xFF268BD2),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "trinity",
        label = "Trinity",
        dark = true,
        art = null,
        background = Color(0xFF050705),
        surface = Color(0xFF0A0F0A),
        surfaceVariant = Color(0xFF0F1A10),
        selection = Color(0xFF0D3B18),
        text = Color(0xFFB9FFC9),
        muted = Color(0xFF4F8F5F),
        line = Color(0xFF142A16),
        accent = Color(0xFF00FF41),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "paper",
        label = "Paper (light)",
        dark = false,
        art = null,
        background = Color(0xFFFDF6E3),
        surface = Color(0xFFF4ECD8),
        surfaceVariant = Color(0xFFEEE5CD),
        selection = Color(0xFFDCD3BB),
        text = Color(0xFF3B3A36),
        muted = Color(0xFF7D7A70),
        line = Color(0xFFE0D7C0),
        accent = Color(0xFFB58900),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "plumber",
        label = "Plumber",
        dark = true,
        art = "plumber.png",
        background = Color(0xFF141A2E),
        surface = Color(0xFF1B2340),
        surfaceVariant = Color(0xFF25304F),
        selection = Color(0xFF33406B),
        text = Color(0xFFE8ECF7),
        muted = Color(0xFF8D99C0),
        line = Color(0xFF2A3457),
        accent = Color(0xFFE5372C),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "triforce",
        label = "Triforce",
        dark = true,
        art = "triforce.png",
        background = Color(0xFF101A14),
        surface = Color(0xFF16241B),
        surfaceVariant = Color(0xFF1E3125),
        selection = Color(0xFF2B4A33),
        text = Color(0xFFE4F0E2),
        muted = Color(0xFF87A58E),
        line = Color(0xFF22382A),
        accent = Color(0xFFF2C14E),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "fellowship",
        label = "Fellowship",
        dark = true,
        art = "fellowship.png",
        background = Color(0xFF15130F),
        surface = Color(0xFF1C1A15),
        surfaceVariant = Color(0xFF26231C),
        selection = Color(0xFF3A3427),
        text = Color(0xFFE8DCC0),
        muted = Color(0xFF9A8F76),
        line = Color(0xFF2B271F),
        accent = Color(0xFFC9A227),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "drizzt",
        label = "Drizzt",
        dark = true,
        art = "drizzt.png",
        background = Color(0xFF0F0C18),
        surface = Color(0xFF171226),
        surfaceVariant = Color(0xFF211A33),
        selection = Color(0xFF332A52),
        text = Color(0xFFE6E1F5),
        muted = Color(0xFF8F86B5),
        line = Color(0xFF241D3A),
        accent = Color(0xFFB39DDB),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "chompy",
        label = "Chompy",
        dark = true,
        art = "chompy.png",
        background = Color(0xFF08080F),
        surface = Color(0xFF101024),
        surfaceVariant = Color(0xFF181838),
        selection = Color(0xFF22225A),
        text = Color(0xFFF0F0D8),
        muted = Color(0xFF8A8AB0),
        line = Color(0xFF1C1C40),
        accent = Color(0xFFFFD93B),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "bricks",
        label = "Bricks",
        dark = true,
        art = "bricks.png",
        background = Color(0xFF0D1226),
        surface = Color(0xFF141B36),
        surfaceVariant = Color(0xFF1D2749),
        selection = Color(0xFF2A3766),
        text = Color(0xFFE6ECFF),
        muted = Color(0xFF8996C4),
        line = Color(0xFF212C53),
        accent = Color(0xFF31C7DE),
        onAccent = Color(0xFF000000),
    ),
    Theme(
        key = "aincrad",
        label = "Aincrad",
        dark = true,
        art = "aincrad.png",
        background = Color(0xFF0A0D14),
        surface = Color(0xFF111620),
        surfaceVariant = Color(0xFF1A2130),
        selection = Color(0xFF26334A),
        text = Color(0xFFE2E9F2),
        muted = Color(0xFF8595A8),
        line = Color(0xFF1D2534),
        accent = Color(0xFF4FC3F7),
        onAccent = Color(0xFF000000),
    ),
)

/** The stored theme, or the one that matches what the operating system is set to. */
fun themeFor(key: String?, systemDark: Boolean): Theme =
    THEMES.firstOrNull { it.key == key }
        ?: THEMES.first { it.dark == systemDark }

/**
 * The theme in force. A local rather than a parameter because the two places that need the
 * theme itself, as opposed to the colours Material already carries, are a corner decoration
 * and the settings screen. Threading it through every composable in between to reach those
 * two would be a worse trade than one local.
 */
val LocalRampartTheme = staticCompositionLocalOf { THEMES.first() }

/**
 * The character in the corner, if the theme has one.
 *
 * Faint on purpose. It sits behind the message you are reading, and the point is that the
 * app feels like yours, not that there is a picture in the way of the text.
 */
@Composable
fun ThemeArt(modifier: Modifier = Modifier) {
    val art = LocalRampartTheme.current.art ?: return
    val image = artImage(art) ?: return
    Box(modifier.padding(end = 18.dp, bottom = 14.dp)) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alpha = 0.22f,
            modifier = Modifier.sizeIn(maxWidth = 190.dp, maxHeight = 190.dp),
        )
    }
}

/**
 * A bundled character, or null. A failed load is nothing: the decoration is missing and the
 * mail still reads, which is not worth an error anybody has to dismiss.
 */
@Composable
internal fun artImage(file: String) = remember(file) {
    runCatching { useResource("art/$file") { loadImageBitmap(it) } }.getOrNull()
}
