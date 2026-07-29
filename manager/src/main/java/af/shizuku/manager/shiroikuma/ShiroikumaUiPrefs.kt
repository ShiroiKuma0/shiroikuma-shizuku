package af.shizuku.manager.shiroikuma

import android.content.Context
import android.content.SharedPreferences

/**
 * Every attribute the 白い熊 雫 UI page can set, and the store that persists them.
 *
 * The whole house layer lives in this one package so it survives upstream rebases as a
 * self-contained unit. Defaults are the house look: **pure black `#000000` with pure yellow
 * `#FFFF00`** — a fresh install is black-and-yellow with no user action.
 *
 * Every knob here is read by [ShiroikumaTheme] and actually drives the app. A knob that only moves
 * its own preview is a bug.
 */
object ShiroikumaUiPrefs {

    const val PREFS_NAME = "shiroikuma_ui"

    // ---- the house palette -----------------------------------------------------------------
    const val BLACK = 0xFF000000.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()
    const val YELLOW_DIM = 0xFFCCCC66.toInt()
    const val RED = 0xFFFF6666.toInt()

    /**
     * The MINOR border. Every surface role in this theme is the same pure black, so a container
     * needs a border or it vanishes into the page. Groups and major items take [YELLOW]; ordinary
     * items take this grey, which separates them without shouting.
     */
    const val GREY = 0xFF4A4A4A.toInt()

    /**
     * One settable attribute. [key] is the storage key, [default] the house-look value.
     *
     * Grouped by [Category] so the UI page, the export ZIP and the automation contract all agree on
     * one decomposition — add a knob here and it appears in all three.
     */
    enum class Category(val id: String, val label: String) {
        COLOURS("colours", "色 — Colours"),
        TYPOGRAPHY("typography", "文字 — Typography"),
        SHAPE("shape", "形 — Shape & borders"),
        SPACING("spacing", "間隔 — Spacing & size"),
        LISTS("lists", "一覧 — Lists & cards"),
        FONTS_FILES("fonts_files", "書体 — Imported font files")
    }

    // ---------------------------------------------------------------------------------------
    // Colours
    // ---------------------------------------------------------------------------------------
    const val KEY_COLOR_BACKGROUND = "color_background"
    const val KEY_COLOR_SURFACE = "color_surface"
    const val KEY_COLOR_TEXT = "color_text"
    const val KEY_COLOR_TEXT_DIM = "color_text_dim"
    const val KEY_COLOR_ACCENT = "color_accent"
    const val KEY_COLOR_BORDER = "color_border"
    const val KEY_COLOR_BORDER_MINOR = "color_border_minor"
    const val KEY_COLOR_HEADING = "color_heading"
    const val KEY_COLOR_ICON = "color_icon"
    const val KEY_COLOR_DIVIDER = "color_divider"
    const val KEY_COLOR_WARN = "color_warn"

    // ---------------------------------------------------------------------------------------
    // Typography
    // ---------------------------------------------------------------------------------------
    const val KEY_FONT_FAMILY = "font_family"
    const val KEY_FONT_WEIGHT = "font_weight"
    const val KEY_TEXT_SIZE = "text_size"
    const val KEY_HEADING_SIZE = "heading_size"
    const val KEY_SUMMARY_SIZE = "summary_size"
    const val KEY_HEADING_BOLD = "heading_bold"
    const val KEY_TEXT_LETTER_SPACING = "text_letter_spacing"

    // ---------------------------------------------------------------------------------------
    // Shape & borders
    // ---------------------------------------------------------------------------------------
    const val KEY_CORNER_RADIUS = "corner_radius"
    const val KEY_BORDER_WIDTH = "border_width"
    const val KEY_DIVIDER_HEIGHT = "divider_height"
    const val KEY_HEADING_UNDERLINE = "heading_underline"
    const val KEY_PILL_RADIUS = "pill_radius"
    const val KEY_PILL_BORDER = "pill_border"

    // ---------------------------------------------------------------------------------------
    // Spacing & size
    // ---------------------------------------------------------------------------------------
    const val KEY_ROW_PADDING = "row_padding"
    const val KEY_GROUP_GAP = "group_gap"
    const val KEY_INDENT_STEP = "indent_step"
    const val KEY_ICON_SIZE = "icon_size"

    // ---------------------------------------------------------------------------------------
    // Lists & cards
    // ---------------------------------------------------------------------------------------
    const val KEY_CARD_BORDER = "card_border"
    const val KEY_CARD_RADIUS = "card_radius"
    const val KEY_CARD_FILL = "card_fill"
    const val KEY_LIST_ICON_TINT = "list_icon_tint"

    // ---------------------------------------------------------------------------------------
    // Export/import
    // ---------------------------------------------------------------------------------------
    const val KEY_EXPORT_DIR = "export_dir"

    /** Integer knobs: key -> (default, min, max, unit suffix shown on the slider). */
    val INT_DEFAULTS: Map<String, IntKnob> = mapOf(
        KEY_COLOR_BACKGROUND to IntKnob(BLACK),
        KEY_COLOR_SURFACE to IntKnob(BLACK),
        KEY_COLOR_TEXT to IntKnob(YELLOW),
        KEY_COLOR_TEXT_DIM to IntKnob(YELLOW_DIM),
        KEY_COLOR_ACCENT to IntKnob(YELLOW),
        KEY_COLOR_BORDER to IntKnob(YELLOW),
        KEY_COLOR_BORDER_MINOR to IntKnob(GREY),
        KEY_COLOR_HEADING to IntKnob(YELLOW),
        KEY_COLOR_ICON to IntKnob(YELLOW),
        KEY_COLOR_DIVIDER to IntKnob(YELLOW),
        KEY_COLOR_WARN to IntKnob(RED),

        KEY_FONT_WEIGHT to IntKnob(400, 100, 900, ""),
        KEY_TEXT_SIZE to IntKnob(16, 8, 34, "sp"),
        KEY_HEADING_SIZE to IntKnob(20, 10, 42, "sp"),
        KEY_SUMMARY_SIZE to IntKnob(13, 7, 28, "sp"),
        KEY_TEXT_LETTER_SPACING to IntKnob(0, 0, 20, "/100"),

        // Every border / thickness / roundness slider reaches 0 — "off" is always reachable.
        KEY_CORNER_RADIUS to IntKnob(16, 0, 48, "dp"),
        KEY_BORDER_WIDTH to IntKnob(2, 0, 12, "dp"),
        KEY_DIVIDER_HEIGHT to IntKnob(1, 0, 8, "dp"),
        KEY_HEADING_UNDERLINE to IntKnob(3, 0, 12, "dp"),
        KEY_PILL_RADIUS to IntKnob(50, 0, 50, "dp"),
        KEY_PILL_BORDER to IntKnob(2, 0, 10, "dp"),

        KEY_ROW_PADDING to IntKnob(5, 0, 28, "dp"),
        KEY_GROUP_GAP to IntKnob(10, 0, 48, "dp"),
        KEY_INDENT_STEP to IntKnob(18, 0, 48, "dp"),
        KEY_ICON_SIZE to IntKnob(24, 8, 56, "dp"),

        KEY_CARD_BORDER to IntKnob(2, 0, 12, "dp"),
        KEY_CARD_RADIUS to IntKnob(10, 0, 40, "dp"),
        KEY_CARD_FILL to IntKnob(BLACK)
    )

    val BOOL_DEFAULTS: Map<String, Boolean> = mapOf(
        KEY_HEADING_BOLD to true,
        KEY_LIST_ICON_TINT to true
    )

    val STRING_DEFAULTS: Map<String, String> = mapOf(
        KEY_FONT_FAMILY to ShiroikumaFonts.SYSTEM,
        KEY_EXPORT_DIR to ""
    )

    /** Which category each key belongs to — drives the export ZIP split and the automation contract. */
    val KEY_CATEGORY: Map<String, Category> = buildMap {
        listOf(
            KEY_COLOR_BACKGROUND, KEY_COLOR_SURFACE, KEY_COLOR_TEXT, KEY_COLOR_TEXT_DIM,
            KEY_COLOR_ACCENT, KEY_COLOR_BORDER, KEY_COLOR_BORDER_MINOR, KEY_COLOR_HEADING,
            KEY_COLOR_ICON, KEY_COLOR_DIVIDER, KEY_COLOR_WARN
        ).forEach { put(it, Category.COLOURS) }
        listOf(
            KEY_FONT_FAMILY, KEY_FONT_WEIGHT, KEY_TEXT_SIZE, KEY_HEADING_SIZE,
            KEY_SUMMARY_SIZE, KEY_HEADING_BOLD, KEY_TEXT_LETTER_SPACING
        ).forEach { put(it, Category.TYPOGRAPHY) }
        listOf(
            KEY_CORNER_RADIUS, KEY_BORDER_WIDTH, KEY_DIVIDER_HEIGHT, KEY_HEADING_UNDERLINE,
            KEY_PILL_RADIUS, KEY_PILL_BORDER
        ).forEach { put(it, Category.SHAPE) }
        listOf(KEY_ROW_PADDING, KEY_GROUP_GAP, KEY_INDENT_STEP, KEY_ICON_SIZE)
            .forEach { put(it, Category.SPACING) }
        listOf(KEY_CARD_BORDER, KEY_CARD_RADIUS, KEY_CARD_FILL, KEY_LIST_ICON_TINT)
            .forEach { put(it, Category.LISTS) }
    }

    data class IntKnob(val default: Int, val min: Int = 0, val max: Int = 0, val unit: String = "")

    // ---- store -----------------------------------------------------------------------------

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getInt(context: Context, key: String): Int =
        prefs(context).getInt(key, INT_DEFAULTS[key]?.default ?: 0)

    fun setInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
    }

    fun getBool(context: Context, key: String): Boolean =
        prefs(context).getBoolean(key, BOOL_DEFAULTS[key] ?: false)

    fun setBool(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun getString(context: Context, key: String): String =
        prefs(context).getString(key, STRING_DEFAULTS[key] ?: "") ?: (STRING_DEFAULTS[key] ?: "")

    fun setString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    /** Restore one key — or every key in a category — to the house default. */
    fun reset(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    fun resetCategory(context: Context, category: Category) {
        val editor = prefs(context).edit()
        KEY_CATEGORY.filterValues { it == category }.keys.forEach { editor.remove(it) }
        editor.apply()
    }

    /** Every stored value in a category, as a plain map (used by the export ZIP). */
    fun exportCategory(context: Context, category: Category): Map<String, Any?> {
        val all = prefs(context).all
        return KEY_CATEGORY.filterValues { it == category }.keys.associateWith { key ->
            all[key] ?: INT_DEFAULTS[key]?.default ?: BOOL_DEFAULTS[key] ?: STRING_DEFAULTS[key]
        }
    }

    /** Merge a category's values back in (import). Absent keys are left untouched. */
    fun importCategory(context: Context, values: Map<String, Any?>) {
        val editor = prefs(context).edit()
        values.forEach { (key, value) ->
            when {
                value == null -> editor.remove(key)
                key in INT_DEFAULTS -> (value as? Number)?.let { editor.putInt(key, it.toInt()) }
                key in BOOL_DEFAULTS -> (value as? Boolean)?.let { editor.putBoolean(key, it) }
                key in STRING_DEFAULTS -> editor.putString(key, value.toString())
            }
        }
        editor.apply()
    }
}
