package af.shizuku.manager.shiroikuma

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import af.shizuku.manager.R

/**
 * The preference widgets the 白い熊 雫 UI page is built from.
 *
 * Every one binds to the house layouts (`preference_*_shizuku*.xml`), so the whole page shares one
 * visual grammar: big bold **word-width-underlined** headings, items indented one step per level,
 * tight row padding, and generous space only above a section heading.
 *
 * Indent levels: category 36dp → sub-category 54dp → item 72dp → nested item 90dp.
 */

/** A top-level section heading. [first] drops the full-width divider above it. */
class ShiroikumaCategory(context: Context, first: Boolean = false) : PreferenceCategory(context) {
    init {
        layoutResource =
            if (first) R.layout.preference_category_shizuku_first else R.layout.preference_category_shizuku
        isIconSpaceReserved = false
    }
}

/** A sub-heading inside a section (level 2). */
class ShiroikumaSubCategory(context: Context) : PreferenceCategory(context) {
    init {
        layoutResource = R.layout.preference_subcategory_shizuku
        isIconSpaceReserved = false
    }
}

/** A plain tappable row. [level] 1 = under a category, 2 = under a sub-category. */
open class ShiroikumaItem(context: Context, level: Int = 1) : Preference(context) {
    init {
        layoutResource =
            if (level >= 2) R.layout.preference_item_shizuku_l2 else R.layout.preference_item_shizuku
        isIconSpaceReserved = false
        isSingleLineTitle = false
    }
}

/**
 * A colour row: title + hex summary, with a bordered swatch on the right showing the current
 * colour at a glance. Tapping opens the four-slider RGBA picker.
 */
class ColorSwatchPreference(context: Context, level: Int = 1) : ShiroikumaItem(context, level) {
    var color: Int = ShiroikumaUiPrefs.BLACK
        set(value) {
            field = value
            summary = String.format("#%08X", value)
            notifyChanged()
        }

    init {
        widgetLayoutResource = R.layout.preference_color_swatch_shizuku
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val swatch = holder.findViewById(R.id.color_swatch) ?: return
        val density = context.resources.displayMetrics.density
        val fill = color // capture before apply{}, where `color` would resolve to GradientDrawable.color
        swatch.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke((1.5f * density).toInt(), ShiroikumaUiPrefs.YELLOW)
            cornerRadius = 4f * density
        }
    }
}

/**
 * The automation-token row: tapping the row copies the full token, and a **Regenerate** action sits
 * on the right. Regenerating invalidates every pasted copy, so it warns first.
 */
class AutomationTokenPreference(
    context: Context,
    private val onRegenerate: () -> Unit
) : ShiroikumaItem(context, 1) {
    init {
        widgetLayoutResource = R.layout.preference_regenerate_shizuku
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val button = holder.findViewById(R.id.regenerate) as? TextView ?: return
        button.setTextColor(ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_COLOR_ACCENT))
        button.setOnClickListener {
            ShiroikumaDialogs.choice(
                context,
                "Regenerate token",
                "A new token invalidates the one already pasted into 白い熊 自由作業盤 — you will " +
                    "have to copy the new one across before the batch can reach this app again.",
                positive = "Regenerate",
                negative = context.getString(android.R.string.cancel),
                onPositive = { onRegenerate() },
                onNegative = {}
            )
        }
    }
}

/**
 * A slider row. Sizes, weights, roundness and thickness are all sliders — and every border /
 * thickness / roundness slider reaches **0**, so "off" is always directly reachable.
 */
class ShiroikumaSeekBar(context: Context, level: Int = 1) : SeekBarPreference(context) {
    var unit: String = ""

    init {
        layoutResource =
            if (level >= 2) R.layout.preference_seekbar_shizuku_l2 else R.layout.preference_seekbar_shizuku
        isIconSpaceReserved = false
        showSeekBarValue = true
        isSingleLineTitle = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val accent = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_COLOR_ACCENT)
        (holder.findViewById(R.id.seekbar) as? android.widget.SeekBar)?.apply {
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        (holder.findViewById(R.id.seekbar_value) as? TextView)?.apply {
            if (unit.isNotEmpty()) text = "$value$unit"
            setTextColor(accent)
        }
    }
}

/**
 * A live preview row. Draws a sample card — heading, body text, dim summary, a bordered box and a
 * pill — using the **current** knob values, so every change is visible immediately without leaving
 * the page. Every group on the page carries one.
 */
class ShiroikumaPreviewPreference(context: Context, level: Int = 1) : Preference(context) {
    init {
        layoutResource = R.layout.preference_preview_shizuku
        isIconSpaceReserved = false
        isSelectable = false
    }

    /** `notifyChanged()` is protected on Preference; the page needs to poke previews from outside. */
    fun refresh() = notifyChanged()

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val container = holder.itemView as? LinearLayout ?: return
        container.removeAllViews()
        container.addView(buildPreview(context))
    }

    companion object {
        /** The sample card. Public so the export/import page can show the same thing. */
        fun buildPreview(context: Context): View {
            val p = ShiroikumaUiPrefs
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            fun dpf(v: Int) = v * density

            val bg = p.getInt(context, p.KEY_COLOR_BACKGROUND)
            val text = p.getInt(context, p.KEY_COLOR_TEXT)
            val dim = p.getInt(context, p.KEY_COLOR_TEXT_DIM)
            val accent = p.getInt(context, p.KEY_COLOR_ACCENT)
            val heading = p.getInt(context, p.KEY_COLOR_HEADING)
            val border = p.getInt(context, p.KEY_COLOR_BORDER)
            val divider = p.getInt(context, p.KEY_COLOR_DIVIDER)

            val typeface = ShiroikumaFonts.current(context)
            val headingBold = p.getBool(context, p.KEY_HEADING_BOLD)
            val textSize = p.getInt(context, p.KEY_TEXT_SIZE).toFloat()
            val headingSize = p.getInt(context, p.KEY_HEADING_SIZE).toFloat()
            val summarySize = p.getInt(context, p.KEY_SUMMARY_SIZE).toFloat()
            val letterSpacing = p.getInt(context, p.KEY_TEXT_LETTER_SPACING) / 100f
            val rowPad = p.getInt(context, p.KEY_ROW_PADDING)

            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(p.getInt(context, p.KEY_CARD_FILL))
                    setStroke(dp(p.getInt(context, p.KEY_CARD_BORDER)), border)
                    cornerRadius = dpf(p.getInt(context, p.KEY_CARD_RADIUS))
                }
            }

            // Heading + its word-width underline, exactly as the page draws them.
            val headingWrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            headingWrap.addView(TextView(context).apply {
                this.text = "見出し  Heading"
                setTextColor(heading)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, headingSize)
                setTypeface(typeface, if (headingBold) Typeface.BOLD else Typeface.NORMAL)
                this.letterSpacing = letterSpacing
            })
            val underline = p.getInt(context, p.KEY_HEADING_UNDERLINE)
            if (underline > 0) {
                headingWrap.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(underline)
                    ).apply { topMargin = dp(2) }
                    setBackgroundColor(heading)
                })
            }
            box.addView(headingWrap)

            box.addView(TextView(context).apply {
                this.text = "Body text — 本文"
                setTextColor(text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
                this.typeface = typeface
                this.letterSpacing = letterSpacing
                setPadding(0, dp(rowPad + 4), 0, dp(rowPad))
            })
            box.addView(TextView(context).apply {
                this.text = "Summary line — 概要"
                setTextColor(dim)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, summarySize)
                this.typeface = typeface
                setPadding(0, 0, 0, dp(rowPad))
            })

            val dividerH = p.getInt(context, p.KEY_DIVIDER_HEIGHT)
            if (dividerH > 0) {
                box.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(dividerH)
                    ).apply { topMargin = dp(2); bottomMargin = dp(8) }
                    setBackgroundColor(divider)
                    alpha = 0.5f
                })
            }

            // A bordered inner box + a pill, so border width, radius and pill knobs all show.
            box.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    this.text = "  Box  "
                    setTextColor(text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, summarySize)
                    this.typeface = typeface
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = GradientDrawable().apply {
                        setColor(bg)
                        setStroke(dp(p.getInt(context, p.KEY_BORDER_WIDTH)), border)
                        cornerRadius = dpf(p.getInt(context, p.KEY_CORNER_RADIUS))
                    }
                })
                addView(TextView(context).apply {
                    this.text = " Minor "
                    setTextColor(dim)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, summarySize)
                    this.typeface = typeface
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = GradientDrawable().apply {
                        setColor(bg)
                        setStroke(
                            dp(p.getInt(context, p.KEY_CARD_BORDER)),
                            p.getInt(context, p.KEY_COLOR_BORDER_MINOR)
                        )
                        cornerRadius = dpf(p.getInt(context, p.KEY_CARD_RADIUS))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(8) }
                })
                addView(TextView(context).apply {
                    this.text = "Pill"
                    setTextColor(accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, summarySize)
                    this.typeface = typeface
                    gravity = Gravity.CENTER
                    setPadding(dp(18), dp(6), dp(18), dp(6))
                    background = GradientDrawable().apply {
                        setColor(bg)
                        setStroke(dp(p.getInt(context, p.KEY_PILL_BORDER)), accent)
                        cornerRadius = dpf(p.getInt(context, p.KEY_PILL_RADIUS))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(10) }
                })
            })

            return box
        }
    }
}
