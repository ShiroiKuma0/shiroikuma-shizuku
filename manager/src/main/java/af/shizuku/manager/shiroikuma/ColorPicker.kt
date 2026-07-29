package af.shizuku.manager.shiroikuma

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import af.shizuku.manager.R

/**
 * The house ARGB colour picker: a row of **one-click prefilled swatches** (every colour previously
 * chosen, seeded with the black/yellow staples), a live preview/hex line, and **four A/R/G/B
 * sliders**.
 *
 * Applies **live** on every change so the page previews immediately. Cancel reverts to the colour
 * the dialog opened with; OK keeps it and remembers it in the swatches.
 */
object ColorPicker {
    private const val PREFS = "shiroikuma_color_picker"
    private const val KEY_RECENT = "recent"
    private const val MAX_RECENT = 8

    fun show(context: Context, title: String, initial: Int, onColor: (Int) -> Unit) {
        val density = context.resources.displayMetrics.density
        val yellow = ShiroikumaUiPrefs.YELLOW

        var a = Color.alpha(initial)
        var r = Color.red(initial)
        var g = Color.green(initial)
        var b = Color.blue(initial)

        val sliders = mutableListOf<SeekBar>()
        val preview = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            minHeight = (52 * density).toInt()
        }

        fun current() = Color.argb(a, r, g, b)

        fun refresh(apply: Boolean) {
            val color = current()
            // Checkerboard behind the swatch would be nicer, but a solid black ground matches the
            // page and keeps low-alpha colours honest against what they will actually sit on.
            preview.setBackgroundColor(color)
            val luminance = 0.299 * r + 0.587 * g + 0.114 * b
            preview.setTextColor(if (luminance < 128 || a < 128) Color.WHITE else Color.BLACK)
            preview.text = String.format("#%02X%02X%02X%02X", a, r, g, b)
            if (apply) onColor(color)
        }

        fun setFrom(color: Int) {
            a = Color.alpha(color); r = Color.red(color); g = Color.green(color); b = Color.blue(color)
            sliders.getOrNull(0)?.progress = a
            sliders.getOrNull(1)?.progress = r
            sliders.getOrNull(2)?.progress = g
            sliders.getOrNull(3)?.progress = b
            refresh(apply = true)
        }

        fun channelRow(label: String, value: Int, onChange: (Int) -> Unit): View {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply {
                text = label
                setTextColor(yellow)
                width = (22 * density).toInt()
            })
            val seek = SeekBar(context).apply {
                max = 255
                progress = value
                progressTintList = ColorStateList.valueOf(yellow)
                thumbTintList = ColorStateList.valueOf(yellow)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        onChange(p); refresh(apply = true)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            sliders.add(seek)
            row.addView(seek)
            return row
        }

        // One-click prefilled swatches.
        val swatchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sizePx = (32 * density).toInt()
        val gap = (6 * density).toInt()
        recent(context).forEach { sw ->
            swatchRow.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply { marginEnd = gap }
                background = GradientDrawable().apply {
                    setColor(sw)
                    setStroke((1.5f * density).toInt(), yellow)
                    cornerRadius = 4f * density
                }
                setOnClickListener { setFrom(sw) }
            })
        }

        val pad = (20 * density).toInt()
        fun spaced(view: View, bottomDp: Int): View {
            (view.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )).also {
                it.bottomMargin = (bottomDp * density).toInt()
                view.layoutParams = it
            }
            return view
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            if (swatchRow.childCount > 0) addView(spaced(swatchRow, 18))
            addView(spaced(preview, 18))
            addView(spaced(channelRow("A", a) { a = it }, 8))
            addView(spaced(channelRow("R", r) { r = it }, 8))
            addView(spaced(channelRow("G", g) { g = it }, 8))
            addView(channelRow("B", b) { b = it })
        }
        refresh(apply = false)

        AlertDialog.Builder(context, R.style.Theme_Shiroikuma_Dialog)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val color = current()
                onColor(color)
                remember(context, color)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onColor(initial) }
            .setOnCancelListener { onColor(initial) }
            .show()
            .also { ShiroikumaDialogs.style(it) }
    }

    private fun recent(context: Context): List<Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_RECENT, null)
            ?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()
        // Seeded with the black/yellow staples so there is always something to one-click.
        val seeds = listOf(
            ShiroikumaUiPrefs.BLACK, ShiroikumaUiPrefs.YELLOW,
            0xFFFFFFFF.toInt(), ShiroikumaUiPrefs.YELLOW_DIM, ShiroikumaUiPrefs.RED
        )
        return (stored + seeds).distinct().take(MAX_RECENT)
    }

    private fun remember(context: Context, color: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = prefs.getString(KEY_RECENT, null)?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()
        val updated = (listOf(color) + cur).distinct().take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT, updated.joinToString(",")).apply()
    }
}
