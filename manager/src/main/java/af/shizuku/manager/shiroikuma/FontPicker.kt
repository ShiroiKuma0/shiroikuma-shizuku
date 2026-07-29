package af.shizuku.manager.shiroikuma

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import af.shizuku.manager.R

/**
 * The font picker: a black/yellow dialog listing System / Monospace / Serif plus every imported
 * font, **each rendered in its own glyphs** so you choose by looking at the typeface rather than at
 * its filename. The neutral button opens the document picker to import a new `.ttf`/`.otf`.
 *
 * Long-pressing an imported font offers to delete it.
 */
object FontPicker {
    fun show(
        context: Context,
        current: String,
        onPick: (String) -> Unit,
        onImport: () -> Unit,
        onDelete: (String) -> Unit
    ) {
        val fonts = ShiroikumaFonts.availableFonts(context)
        val accent = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_COLOR_ACCENT)
        val density = context.resources.displayMetrics.density

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = fonts.size
            override fun getItem(position: Int): FontOption = fonts[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val option = fonts[position]
                val tv = (convertView as? TextView) ?: TextView(context)
                val selected = option.fileName == current
                tv.text = (if (selected) "✓  " else "") + option.displayName
                // The point of the picker: each row draws itself in the font it offers.
                tv.typeface = ShiroikumaFonts.typeface(context, option.fileName)
                tv.setTextColor(accent)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                val padH = (20 * density).toInt()
                val padV = (10 * density).toInt()
                tv.setPadding(padH, padV, padH, padV)
                return tv
            }
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_Shiroikuma_Dialog)
            .setTitle("Font family")
            .setAdapter(adapter) { _, position -> onPick(fonts[position].fileName) }
            .setNeutralButton("Import…") { _, _ -> onImport() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        ShiroikumaDialogs.style(dialog)

        dialog.listView?.setOnItemLongClickListener { _, _, position, _ ->
            val option = fonts[position]
            // Only imported files can be deleted — the three built-ins have no file behind them.
            if (option.fileName.isEmpty() || option.fileName.startsWith("@")) {
                false
            } else {
                dialog.dismiss()
                ShiroikumaDialogs.choice(
                    context,
                    "Delete font",
                    "Remove “${option.displayName}” from imported fonts?",
                    positive = "Delete",
                    negative = context.getString(android.R.string.cancel),
                    onPositive = { onDelete(option.fileName) },
                    onNegative = {}
                )
                true
            }
        }
    }
}
