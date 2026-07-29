package af.shizuku.manager.shiroikuma

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import af.shizuku.core.ui.compose.AppThemeOverride
import java.io.File

/**
 * Turns the 白い熊 雫 knobs into the theme every Compose screen actually renders with.
 *
 * Installed once from `ShizukuApplication.onCreate` via [install]; the UI page calls
 * [AppThemeOverride.invalidate] after every change so screens recompose against the new values.
 *
 * **Two rules make the scheme render correctly, and both are easy to undo by accident:**
 *
 * - **Every `*Container` role is a flat near-black surface, never a low-alpha accent.** Material's
 *   generic builders composite containers as `primary.copy(alpha = …)` over the surface — with
 *   yellow over black that lands on **olive**. That is why this builds every role explicitly
 *   instead of feeding colours into `darkColorScheme()` and letting it derive the rest.
 * - **`surfaceTint` is `Color.Transparent`**, so Material's tonal-elevation overlay never pulls a
 *   surface back toward the accent.
 */
object ShiroikumaTheme {

    fun install(context: Context) {
        val app = context.applicationContext
        AppThemeOverride.colorSchemeProvider = { ctx, _ -> colorScheme(ctx.applicationContext ?: app) }
        AppThemeOverride.typographyProvider = { ctx -> typography(ctx.applicationContext ?: app) }
    }

    /** Rebuild everything from the store — call after any knob changes. */
    fun refresh() = AppThemeOverride.invalidate()

    fun colorScheme(context: Context): ColorScheme {
        val p = ShiroikumaUiPrefs
        val background = Color(p.getInt(context, p.KEY_COLOR_BACKGROUND))
        val surface = Color(p.getInt(context, p.KEY_CARD_FILL))
        val text = Color(p.getInt(context, p.KEY_COLOR_TEXT))
        val dim = Color(p.getInt(context, p.KEY_COLOR_TEXT_DIM))
        val accent = Color(p.getInt(context, p.KEY_COLOR_ACCENT))
        val border = Color(p.getInt(context, p.KEY_COLOR_BORDER))
        val borderMinor = Color(p.getInt(context, p.KEY_COLOR_BORDER_MINOR))
        val warn = Color(p.getInt(context, p.KEY_COLOR_WARN))

        // "On accent" has to contrast with the accent itself, not with the page.
        val onAccent = if (luminance(accent) > 0.5f) Color.Black else Color.White

        return darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            // Flat, not alpha-composited — see the class doc.
            primaryContainer = background,
            onPrimaryContainer = accent,
            inversePrimary = accent,

            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = background,
            onSecondaryContainer = accent,

            tertiary = accent,
            onTertiary = onAccent,
            tertiaryContainer = background,
            onTertiaryContainer = accent,

            background = background,
            onBackground = text,

            surface = surface,
            onSurface = text,
            surfaceVariant = surface,
            onSurfaceVariant = dim,
            surfaceContainer = background,
            surfaceContainerHigh = background,
            surfaceContainerHighest = background,
            surfaceContainerLow = background,
            surfaceContainerLowest = background,
            surfaceBright = surface,
            surfaceDim = background,
            inverseSurface = text,
            inverseOnSurface = background,

            // Never let tonal elevation drag a surface toward the accent.
            surfaceTint = Color.Transparent,

            error = warn,
            onError = Color.Black,
            errorContainer = background,
            onErrorContainer = warn,

            outline = border,
            // outlineVariant IS the minor border: every surface role here is the same pure black,
            // so an ordinary container needs this to be visible at all.
            outlineVariant = borderMinor,
            scrim = Color.Black
        )
    }

    fun typography(context: Context): Typography {
        val p = ShiroikumaUiPrefs
        val family = fontFamily(context, p.getString(context, p.KEY_FONT_FAMILY))
        val weight = FontWeight(p.getInt(context, p.KEY_FONT_WEIGHT).coerceIn(100, 900))
        val body = p.getInt(context, p.KEY_TEXT_SIZE).toFloat()
        val heading = p.getInt(context, p.KEY_HEADING_SIZE).toFloat()
        val summary = p.getInt(context, p.KEY_SUMMARY_SIZE).toFloat()
        val tracking = (p.getInt(context, p.KEY_TEXT_LETTER_SPACING) / 100f).sp

        fun style(size: Float, w: FontWeight = weight) = TextStyle(
            fontFamily = family,
            fontWeight = w,
            fontSize = size.sp,
            lineHeight = (size * 1.35f).sp,
            letterSpacing = tracking
        )

        val base = Typography()
        return base.copy(
            displayLarge = style(heading + 14),
            displayMedium = style(heading + 10),
            displaySmall = style(heading + 6),
            headlineLarge = style(heading + 6, FontWeight.Bold),
            headlineMedium = style(heading + 3, FontWeight.Bold),
            headlineSmall = style(heading, FontWeight.Bold),
            titleLarge = style(heading, FontWeight.Bold),
            titleMedium = style(body + 2, FontWeight.Bold),
            titleSmall = style(body, FontWeight.Bold),
            bodyLarge = style(body + 1),
            bodyMedium = style(body),
            bodySmall = style(summary),
            labelLarge = style(body),
            labelMedium = style(summary),
            labelSmall = style(summary - 1)
        )
    }

    private fun fontFamily(context: Context, family: String): FontFamily = when {
        family.isEmpty() -> FontFamily.Default
        family == ShiroikumaFonts.MONOSPACE -> FontFamily.Monospace
        family == ShiroikumaFonts.SERIF -> FontFamily.Serif
        else -> runCatching {
            val file = File(ShiroikumaFonts.fontsDir(context), family)
            if (file.isFile) FontFamily(Font(file)) else FontFamily.Default
        }.getOrDefault(FontFamily.Default)
    }

    private fun luminance(c: Color): Float =
        (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue)
}
