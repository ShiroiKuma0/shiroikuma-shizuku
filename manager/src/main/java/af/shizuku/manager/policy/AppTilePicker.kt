package af.shizuku.manager.policy

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import af.shizuku.manager.settings.AppPickerPreference
import af.shizuku.manager.shiroikuma.ShiroikumaDialogs
import af.shizuku.manager.shiroikuma.ShiroikumaUiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick an installed app from a grid of tiles — icon, label, package id — searchable by either.
 *
 * The package id is shown, not just the label, because it is what the allowlist and the delegation
 * actually key on: two apps can carry the same label, a label can be localised out from under you,
 * and "which `shiroikuma.*` is this" is the question being answered when authorizing one of them.
 * Search matches label *or* id for the same reason — 白い熊 knows the sister apps by package.
 *
 * The grid is built by hand rather than reusing [AppPickerPreference]'s dialog: that one is a
 * multi-select checkbox list bound to a CSV preference value, and this is a single-shot picker with
 * no preference behind it.
 */
object AppTilePicker {

    fun show(
        context: Context,
        scope: CoroutineScope,
        title: String,
        onPicked: (packageName: String) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_app_tile_picker, null)
        val grid = view.findViewById<RecyclerView>(R.id.grid)
        val search = view.findViewById<EditText>(R.id.search)
        val empty = view.findViewById<TextView>(R.id.empty)

        val p = ShiroikumaUiPrefs
        val textColor = p.getInt(context, p.KEY_COLOR_TEXT)
        val dimColor = p.getInt(context, p.KEY_COLOR_TEXT_DIM)
        search.setTextColor(textColor)
        search.setHintTextColor(dimColor)
        empty.setTextColor(dimColor)

        // Three across is the widest that still leaves a package id legible at 10sp.
        grid.layoutManager = GridLayoutManager(context, 3)

        val dialog = AlertDialog.Builder(context, R.style.Theme_Shiroikuma_Dialog)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { ShiroikumaDialogs.style(it) }

        val adapter = TileAdapter(context) { pkg ->
            dialog.dismiss()
            onPicked(pkg)
        }
        grid.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
                empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        // Icons and labels are loaded off the main thread: getInstalledApplications plus a
        // loadIcon per app is far too much work for a dialog-open frame.
        scope.launch {
            val apps = withContext(Dispatchers.IO) {
                runCatching { AppPickerPreference.getApps(context) }.getOrDefault(emptyList())
            }
            adapter.submit(apps.sortedBy { it.label.lowercase() })
            empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
        }
    }

    private class TileAdapter(
        private val context: Context,
        private val onPicked: (String) -> Unit
    ) : RecyclerView.Adapter<TileAdapter.Holder>() {

        private var all: List<AppPickerPreference.AppItem> = emptyList()
        private var shown: List<AppPickerPreference.AppItem> = emptyList()
        private var query: String = ""

        fun submit(items: List<AppPickerPreference.AppItem>) {
            all = items
            filter(query)
        }

        fun filter(q: String) {
            query = q
            val needle = q.trim().lowercase()
            shown = if (needle.isEmpty()) all else all.filter {
                it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle)
            }
            notifyDataSetChanged()
        }

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_tile, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = shown[position]
            val p = ShiroikumaUiPrefs

            holder.label.text = item.label
            holder.label.setTextColor(p.getInt(context, p.KEY_COLOR_TEXT))
            holder.packageId.text = item.packageName
            holder.packageId.setTextColor(p.getInt(context, p.KEY_COLOR_TEXT_DIM))
            holder.icon.setImageDrawable(
                runCatching { context.packageManager.getApplicationIcon(item.appInfo) }.getOrNull()
            )

            // Ordinary list item, so the MINOR border tier — separated from the page without
            // shouting. At width 0 borders are off by choice, and that must stay reachable.
            val width = p.getInt(context, p.KEY_CARD_BORDER)
            val density = context.resources.displayMetrics.density
            holder.itemView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = p.getInt(context, p.KEY_CARD_RADIUS) * density
                setColor(Color.TRANSPARENT)
                if (width > 0) {
                    setStroke((width * density).toInt(), p.getInt(context, p.KEY_COLOR_BORDER_MINOR))
                }
            }
            holder.itemView.setOnClickListener { onPicked(item.packageName) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val label: TextView = view.findViewById(R.id.label)
            val packageId: TextView = view.findViewById(R.id.package_id)
        }
    }
}
