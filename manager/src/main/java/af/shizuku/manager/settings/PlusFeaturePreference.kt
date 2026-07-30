package af.shizuku.manager.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import af.shizuku.manager.R
import af.shizuku.manager.shiroikuma.ShiroikumaToast

class PlusFeaturePreference(context: Context, attrs: AttributeSet) : GrayableIconSwitchPreference(context, attrs) {

    private val infoTitle: Int
    private val infoDetail: Int
    private val badgeType: Int
    private val severityBadge: Int
    private var integrationPackage: String? = null
    private var integrationAppName: String? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PlusFeaturePreference)
        infoTitle = a.getResourceId(R.styleable.PlusFeaturePreference_infoTitle, 0)
        infoDetail = a.getResourceId(R.styleable.PlusFeaturePreference_infoDetail, 0)
        badgeType = a.getInt(R.styleable.PlusFeaturePreference_badgeType, 0)
        severityBadge = a.getInt(R.styleable.PlusFeaturePreference_severityBadge, 0)
        a.recycle()
    }

    fun setIntegration(packageName: String, appName: String) {
        this.integrationPackage = packageName
        this.integrationAppName = appName
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val titleView = holder.findViewById(android.R.id.title) as? TextView
        val summaryView = holder.findViewById(android.R.id.summary) as? TextView

        titleView?.apply {
            isSingleLine = false
            if (badgeType != 0 || severityBadge != 0) applyBadges(this)
        }

        summaryView?.apply {
            isSingleLine = false
        }

        holder.itemView.requestLayout()

        holder.itemView.setOnLongClickListener {
            if (integrationPackage != null) launchIntegration() else showHelp()
            true
        }
    }

    private fun badgeStyleFor(type: Int): Triple<String, Int, Int>? = when (type) {
        1 -> Triple(
            "PLUS",
            resolveColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFE8DEF8.toInt()),
            resolveColor(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF21005D.toInt())
        )
        2 -> Triple(
            "ROOT",
            resolveColor(com.google.android.material.R.attr.colorErrorContainer, 0xFFFFDAD6.toInt()),
            resolveColor(com.google.android.material.R.attr.colorOnErrorContainer, 0xFF410002.toInt())
        )
        3 -> Triple(
            "EXP",
            resolveColor(com.google.android.material.R.attr.colorTertiaryContainer, 0xFFFFD8E4.toInt()),
            resolveColor(com.google.android.material.R.attr.colorOnTertiaryContainer, 0xFF31111D.toInt())
        )
        else -> null
    }

    private fun severityBadgeStyleFor(type: Int): Triple<String, Int, Int>? = when (type) {
        // No M3 "warning" role exists, so RISKY is a fixed amber rather than theme-resolved.
        1 -> Triple("RISKY", 0xFFFFE0B2.toInt(), 0xFF7A4A00.toInt())
        // Solid colorError (not the softer colorErrorContainer ROOT uses) so DANGEROUS reads as
        // a step up in severity even when both badges appear on the same item.
        2 -> Triple(
            "DANGEROUS",
            resolveColor(android.R.attr.colorError, 0xFFB3261E.toInt()),
            resolveColor(com.google.android.material.R.attr.colorOnError, 0xFFFFFFFF.toInt())
        )
        else -> null
    }

    private fun applyBadges(titleView: TextView) {
        val badges = listOfNotNull(badgeStyleFor(badgeType), severityBadgeStyleFor(severityBadge))
        if (badges.isEmpty()) return
        val density = titleView.resources.displayMetrics.density
        val spannable = SpannableStringBuilder(titleView.text)
        for ((badgeLabel, bgColor, fgColor) in badges) {
            spannable.append("  ")
            val start = spannable.length
            // Single placeholder char; InlineBadgeSpan draws the full pill itself so the
            // background is always tight around the text (BackgroundColorSpan+RelativeSizeSpan
            // caused the top-heavy padding seen in issue #442).
            spannable.append(" ")
            val end = spannable.length
            spannable.setSpan(
                InlineBadgeSpan(badgeLabel, bgColor, fgColor, density),
                start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        titleView.text = spannable
    }

    /** Draws a pill-shaped badge inline with the preference title. Replaces the stacked
     *  BackgroundColorSpan+RelativeSizeSpan approach that caused asymmetric top/bottom padding
     *  because BackgroundColorSpan draws at the full line's ascent/descent, not the scaled text's. */
    private class InlineBadgeSpan(
        private val label: String,
        private val bgColor: Int,
        private val fgColor: Int,
        private val density: Float,
    ) : ReplacementSpan() {

        private val textSizePx = 9f * density
        private val paddingH = 5f * density
        private val paddingV = 2f * density
        private val cornerRadius = 4f * density

        private fun styledPaint(base: Paint) = Paint(base).apply {
            textSize = textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val p = styledPaint(paint)
            if (fm != null) {
                val pfm = p.fontMetricsInt
                // Adjust font metrics so the line height accommodates the badge height
                val halfHeight = ((textSizePx + paddingV * 2) / 2).toInt()
                fm.ascent = minOf(fm.ascent, -halfHeight)
                fm.descent = maxOf(fm.descent, halfHeight)
                fm.top = minOf(fm.top, pfm.top)
                fm.bottom = maxOf(fm.bottom, pfm.bottom)
            }
            return (p.measureText(label) + paddingH * 2).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val p = styledPaint(paint)
            val w = p.measureText(label) + paddingH * 2
            val badgeH = textSizePx + paddingV * 2
            val centerY = (top + bottom) / 2f
            val rect = RectF(x, centerY - badgeH / 2, x + w, centerY + badgeH / 2)
            p.color = bgColor
            p.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, p)
            p.color = fgColor
            p.style = Paint.Style.FILL
            // Baseline: center text vertically inside the pill
            val textBaseline = rect.top + paddingV + textSizePx - p.fontMetrics.descent / 2
            canvas.drawText(label, x + paddingH, textBaseline, p)
        }
    }

    private fun launchIntegration() {
        val pkg = integrationPackage ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            context.startActivity(intent)
        } else {
            af.shizuku.manager.shiroikuma.ShiroikumaToast.show(
                context, R.string.app_management_no_launcher, android.widget.Toast.LENGTH_SHORT
            )
        }
    }

    private fun resolveColor(attr: Int, fallback: Int): Int {
        val typedValue = android.util.TypedValue()
        val resolved = context.theme.resolveAttribute(attr, typedValue, true)
        return if (resolved) typedValue.data else fallback
    }

    private fun showHelp() {
        if (infoDetail != 0) {
            val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)

            // Outer container
            val container = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(
                    (24 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (24 * context.resources.displayMetrics.density).toInt(),
                    (24 * context.resources.displayMetrics.density).toInt()
                )
                // Use theme surface background
                setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt()))
            }

            // Drag handle indicator
            val dragHandle = android.view.View(context).apply {
                val params = android.widget.LinearLayout.LayoutParams(
                    (36 * context.resources.displayMetrics.density).toInt(),
                    (4 * context.resources.displayMetrics.density).toInt()
                ).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    bottomMargin = (16 * context.resources.displayMetrics.density).toInt()
                }
                layoutParams = params
                setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorOutlineVariant, 0xFFCCCCCC.toInt()))
            }
            container.addView(dragHandle)

            // Title
            val titleTextView = TextView(context).apply {
                text = context.getString(if (infoTitle != 0) infoTitle else R.string.settings_plus_learn_more)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt()))
                setPadding(0, 0, 0, (12 * context.resources.displayMetrics.density).toInt())
            }
            container.addView(titleTextView)

            // Detail Card (container for content)
            val cardView = com.google.android.material.card.MaterialCardView(context).apply {
                radius = (16 * context.resources.displayMetrics.density)
                // strokeWidth was 0 over a colorSurfaceVariant fill. In this theme every surface role
                // is the same pure black as the page, so that card had no edge at all — not flat, but
                // genuinely invisible. Ordinary content inside a panel, so the MINOR border tier.
                val p = af.shizuku.manager.shiroikuma.ShiroikumaUiPrefs
                strokeWidth = (p.getInt(context, p.KEY_CARD_BORDER).coerceAtLeast(1) *
                    context.resources.displayMetrics.density).toInt()
                strokeColor = p.getInt(context, p.KEY_COLOR_BORDER_MINOR)
                cardElevation = 0f
                setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFF5F5F5.toInt()))
                val params = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (20 * context.resources.displayMetrics.density).toInt()
                }
                layoutParams = params
            }

            val detailTextView = TextView(context).apply {
                text = context.getString(infoDetail)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF333333.toInt()))
                setLineSpacing(0f, 1.25f)
                setPadding(
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt()
                )
            }
            cardView.addView(detailTextView)
            container.addView(cardView)

            // Interactive Switch Card
            val switchCard = com.google.android.material.card.MaterialCardView(context).apply {
                radius = (16 * context.resources.displayMetrics.density)
                strokeWidth = 0
                cardElevation = 0f
                setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFE0F2F1.toInt()))
                val params = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (24 * context.resources.displayMetrics.density).toInt()
                }
                layoutParams = params
            }

            val switchLayout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (12 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (12 * context.resources.displayMetrics.density).toInt()
                )
            }

            val enableFeatureLabel = context.getString(R.string.settings_plus_feature_help_enable)
            val switchText = TextView(context).apply {
                text = enableFeatureLabel
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF004D40.toInt()))
                val params = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = params
            }
            switchLayout.addView(switchText)

            val mSwitch = com.google.android.material.materialswitch.MaterialSwitch(context).apply {
                isChecked = this@PlusFeaturePreference.isChecked
                // Standalone switch with no adjacent Preference row for TalkBack to borrow a
                // label from - without this it announces only "Switch, on/off" with no context.
                contentDescription = enableFeatureLabel
                setOnCheckedChangeListener { _, isCheckedVal ->
                    this@PlusFeaturePreference.isChecked = isCheckedVal
                    this@PlusFeaturePreference.callChangeListener(isCheckedVal)
                }
            }
            switchLayout.addView(mSwitch)
            switchCard.addView(switchLayout)
            container.addView(switchCard)

            // Dismiss Button
            val closeButton = com.google.android.material.button.MaterialButton(context).apply {
                text = context.getString(R.string.settings_plus_feature_help_close)
                cornerRadius = (24 * context.resources.displayMetrics.density).toInt()
                val params = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (48 * context.resources.displayMetrics.density).toInt()
                )
                layoutParams = params
                setOnClickListener { dialog.dismiss() }
            }
            container.addView(closeButton)

            dialog.setContentView(container)
            // The container paints itself with colorSurface, which is the same black as the sheet —
            // let the sheet's own bordered background show through instead of stacking a second
            // edgeless black rectangle on top of it.
            container.background = null
            dialog.show()
            // A sheet cannot take the house dialog treatment: it does not draw from the window
            // background. styleSheet borders the sheet container itself, top corners only.
            af.shizuku.manager.shiroikuma.ShiroikumaDialogs.styleSheet(dialog)
        }
    }
}
