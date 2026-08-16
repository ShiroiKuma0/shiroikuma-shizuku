package af.shizuku.manager.shiroikuma

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Applies the 白い熊 雫 knobs to the **View** half of the app — every `androidx.preference` screen
 * and the home cards, which Compose's theme cannot reach.
 *
 * Deliberately conservative: it recolours and re-types what it recognises and leaves everything else
 * alone, so an upstream screen that gains a new widget degrades to "not yet themed" rather than
 * "broken". It is idempotent, so it is safe to re-run on every bind.
 *
 * Rows that already carry the house layouts (`preference_*_shizuku*.xml`) are skipped — those are
 * styled by the layout itself and re-tinting them here would fight the sub-heading/dim colours.
 */
object ShiroikumaViewTheme {

    /** Marks a subtree the applier must not touch (the UI page styles itself). */
    private val SKIP_TAG = "shiroikuma_skip".hashCode()

    /**
     * Marks a view whose **colour** is its own business, while everything else — font, size, letter
     * spacing — is still applied. For state indicators: a view that says "not set up" in red is
     * saying it with the colour, and the generic body-text pass would silently flatten that to the
     * ordinary text colour, leaving a status line that renders identically in every state.
     *
     * Distinct from [markSkipped], which excludes the subtree entirely and therefore also drops the
     * user's chosen typeface and sizes.
     */
    private val COLOR_OWNED_TAG = "shiroikuma_color_owned".hashCode()

    fun markSkipped(view: View) {
        view.setTag(SKIP_TAG, true)
    }

    fun markColorOwned(view: View) {
        view.setTag(COLOR_OWNED_TAG, true)
    }

    fun applyToTree(root: View?, tintBackground: Boolean = true) {
        if (root == null) return
        val ctx = root.context
        val p = ShiroikumaUiPrefs

        val background = p.getInt(ctx, p.KEY_COLOR_BACKGROUND)
        val text = p.getInt(ctx, p.KEY_COLOR_TEXT)
        val dim = p.getInt(ctx, p.KEY_COLOR_TEXT_DIM)
        val accent = p.getInt(ctx, p.KEY_COLOR_ACCENT)
        val icon = p.getInt(ctx, p.KEY_COLOR_ICON)
        val cardFill = p.getInt(ctx, p.KEY_CARD_FILL)
        val border = p.getInt(ctx, p.KEY_COLOR_BORDER)
        val tintIcons = p.getBool(ctx, p.KEY_LIST_ICON_TINT)

        val typeface = ShiroikumaFonts.current(ctx)
        val bodySize = p.getInt(ctx, p.KEY_TEXT_SIZE).toFloat()
        val summarySize = p.getInt(ctx, p.KEY_SUMMARY_SIZE).toFloat()
        val letterSpacing = p.getInt(ctx, p.KEY_TEXT_LETTER_SPACING) / 100f
        val iconSizePx = (p.getInt(ctx, p.KEY_ICON_SIZE) * ctx.resources.displayMetrics.density).toInt()

        if (tintBackground) root.setBackgroundColor(background)

        walk(root) { v ->
            when (v) {
                is MaterialCardView -> {
                    v.setCardBackgroundColor(cardFill)
                    v.strokeColor = border
                    v.strokeWidth =
                        (p.getInt(ctx, p.KEY_CARD_BORDER) * ctx.resources.displayMetrics.density).toInt()
                    v.radius = p.getInt(ctx, p.KEY_CARD_RADIUS) * ctx.resources.displayMetrics.density
                }

                is Button -> {
                    v.setTextColor(accent)
                    v.typeface = typeface
                }

                is MaterialSwitch -> {
                    v.thumbTintList = ColorStateList.valueOf(accent)
                    v.trackTintList = ColorStateList.valueOf(dim)
                    v.setTextColor(text)
                }

                is CompoundButton -> {
                    // Switch / CheckBox / RadioButton share this branch.
                    v.buttonTintList = ColorStateList.valueOf(accent)
                    v.setTextColor(text)
                    if (v is CheckBox || v is Switch) v.typeface = typeface
                }

                is EditText -> {
                    v.setTextColor(text)
                    v.setHintTextColor(dim)
                    v.typeface = typeface
                }

                is TextView -> {
                    // The androidx summary id marks the dim secondary line; everything else is body.
                    val isSummary = v.id == android.R.id.summary
                    // Typography still applies below — only the colour is left alone. See
                    // markColorOwned: this runs on every layout pass, so a state colour set in
                    // onBind is otherwise overwritten before it is ever seen.
                    if (v.getTag(COLOR_OWNED_TAG) != true) {
                        v.setTextColor(if (isSummary) dim else text)
                    }
                    v.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isSummary) summarySize else bodySize)
                    v.letterSpacing = letterSpacing
                    // Preserve an existing bold face rather than flattening headings.
                    val wasBold = v.typeface?.isBold == true
                    v.setTypeface(typeface, if (wasBold) Typeface.BOLD else Typeface.NORMAL)
                }

                is SeekBar -> {
                    v.progressTintList = ColorStateList.valueOf(accent)
                    v.thumbTintList = ColorStateList.valueOf(accent)
                }

                is ProgressBar -> {
                    v.indeterminateTintList = ColorStateList.valueOf(accent)
                    v.progressTintList = ColorStateList.valueOf(accent)
                }

                is ImageView -> {
                    if (tintIcons) v.setColorFilter(icon, PorterDuff.Mode.SRC_IN)
                    if (v.layoutParams != null && v.layoutParams.width > 0 && v.layoutParams.height > 0) {
                        v.layoutParams = v.layoutParams.apply {
                            width = iconSizePx
                            height = iconSizePx
                        }
                    }
                }
            }
        }
    }

    private fun walk(view: View, action: (View) -> Unit) {
        if (view.getTag(SKIP_TAG) == true) return
        action(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), action)
        }
    }
}
