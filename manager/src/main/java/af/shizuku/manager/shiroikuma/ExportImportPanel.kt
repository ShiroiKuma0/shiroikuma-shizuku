package af.shizuku.manager.shiroikuma

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import af.shizuku.manager.R
import java.io.File

/**
 * The Export / import panel — the first, separated section at the top of the 白い熊 雫 UI page.
 *
 * Visual format = the Kōjiki export/import sheet: the whole panel in **one bordered rounded box** —
 * centred bold title, dim intro, a bordered tappable folder box (small label over the bold value,
 * **warn-red when unset**), the last-backup line, a thin divider, Select all + the category
 * checkboxes, a divider, then the **ArcaneChat button bar**: Cancel alone on the left, Import and
 * Export grouped on the right, all as fully round pills.
 *
 * Shown as a dialog over the UI page. The dialog-chain behaviour is specified:
 * - **Successful export** → OK closes the info dialog, this panel, *and* the UI settings page.
 * - **Successful import** → "Later" closes the whole chain the same way; "Restart now" restarts.
 * - **Failures** ("Export failed…", "No categories selected.") leave the panel open.
 */
class ExportImportPanel(
    private val activity: Activity,
    /** Called when the whole chain should close — the panel and the UI settings page behind it. */
    private val onCloseAll: () -> Unit
) {
    private val context: Context = activity
    private val selected: MutableSet<ShiroikumaUiPrefs.Category> =
        ShiroikumaBackup.categories().toMutableSet()

    private var dialog: AlertDialog? = null
    private lateinit var root: LinearLayout

    private val accent get() = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_COLOR_ACCENT)
    private val dim get() = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_COLOR_TEXT_DIM)
    private val warn get() = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_COLOR_WARN)
    private val bg get() = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_COLOR_BACKGROUND)

    fun show() {
        val scroll = ScrollView(context).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
        }
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(14))
            clipToPadding = false
            clipChildren = false
        }
        scroll.addView(root)

        dialog = AlertDialog.Builder(context, R.style.Theme_Shiroikuma_Dialog)
            .setView(scroll)
            .create()
        dialog?.show()
        dialog?.let { ShiroikumaDialogs.style(it) }
        rebuild()
    }

    private fun dismissPanel() {
        dialog?.dismiss()
        dialog = null
    }

    // ---- the page ---------------------------------------------------------------------------

    private fun rebuild() {
        root.removeAllViews()

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            clipToPadding = false
            clipChildren = false
            background = GradientDrawable().apply {
                setColor(bg)
                setStroke(dp(ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_CARD_BORDER)), accent)
                cornerRadius = dp(ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_CORNER_RADIUS)).toFloat()
            }
        }
        root.addView(
            box,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        box.addView(heading("保存復元 — Export / import"))
        box.addView(caption(
            "Back up everything settable in 白い熊 雫 to one ZIP, or restore it. " +
                "Pick a folder once; the newest backup found there is shown below."
        ).apply {
            alpha = 0.85f
            setPadding(0, 0, 0, dp(10))
        })

        if (!hasAllFilesAccess()) {
            box.addView(caption("Storage access is required to read and write the backup folder.", color = warn))
            box.addView(pillButton("Grant access") { requestAllFilesAccess() })
            box.addView(spacer(6))
        }

        box.addView(dirRow())
        box.addView(statusLine())

        box.addView(divider())
        box.addView(selectAllRow())
        ShiroikumaBackup.categories().forEach { box.addView(categoryRow(it)) }

        box.addView(divider(topGap = 8))
        box.addView(actionRow())
    }

    /** The folder box: bordered, clearly tappable — small label over the bold value, red when unset. */
    private fun dirRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(bg)
            setStroke(dp(2), accent)
            cornerRadius = dp(10).toFloat()
        }
        setOnClickListener { editDir() }
        addView(TextView(context).apply {
            text = "Backup folder"
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        val set = ShiroikumaUiPrefs.getString(context, ShiroikumaUiPrefs.KEY_EXPORT_DIR).takeIf { it.isNotBlank() }
        addView(TextView(context).apply {
            text = set ?: "Not set — tap to choose"
            setTextColor(if (set == null) warn else dim)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    private fun statusLine(): View {
        val (text, isWarn) = lastBackupStatus()
        return TextView(context).apply {
            this.text = text
            setTextColor(if (isWarn) warn else dim)
            alpha = if (isWarn) 1f else 0.8f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(2), 0, 0, dp(8))
        }
    }

    /** Queried on opening the page: the newest export in the configured folder. */
    private fun lastBackupStatus(): Pair<String, Boolean> {
        val dir = ShiroikumaBackup.exportDir(context)
            ?: return "No backup folder set." to true
        val newest = ShiroikumaBackup.listBackups(dir).firstOrNull()
            ?: return "No backup found in this folder yet." to true
        val ts = DateFormat.getDateFormat(context).format(newest.lastModified()) + " " +
            DateFormat.getTimeFormat(context).format(newest.lastModified())
        return "Last backup: $ts" to false
    }

    private fun selectAllRow(): View = checkbox("Select all", bold = true).apply {
        isChecked = selected.size == ShiroikumaBackup.categories().size
        setOnClickListener {
            if (isChecked) selected.addAll(ShiroikumaBackup.categories()) else selected.clear()
            rebuild()
        }
    }

    private fun categoryRow(category: ShiroikumaUiPrefs.Category): View =
        checkbox(category.label).apply {
            isChecked = category in selected
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(category) else selected.remove(category)
            }
        }

    /** The ArcaneChat button bar: Cancel alone left, Import + Export grouped right, all round pills. */
    private fun actionRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, dp(14), 0, 0)
        addView(pillButton("Cancel") { dismissPanel() })
        addView(View(context).also { it.layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
        addView(pillButton("Import") { onImport() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
        })
        addView(pillButton("Export") { onExport() })
    }

    // ---- export -----------------------------------------------------------------------------

    private fun onExport() {
        if (!ensureReady()) return
        if (selected.isEmpty()) {
            // Failure: the panel stays open.
            ShiroikumaDialogs.ok(context, "Export", "No categories selected.")
            return
        }
        val dir = ShiroikumaBackup.exportDir(context)!!
        val name = ShiroikumaBackup.exportFileName()
        val parts = selected.toSet()

        val result = runCatching {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            file.outputStream().use {
                ShiroikumaBackup.export(context, parts, it, ShiroikumaBackup.appVersion(context))
            }
            file
        }

        result.onSuccess { file ->
            val size = humanSize(file.length())
            // Success → OK closes the info dialog, this panel AND the UI settings page.
            ShiroikumaDialogs.ok(
                context,
                "Export complete",
                "${file.name}\n$size — ${parts.size} categories\n\n${file.parent}"
            ) {
                dismissPanel()
                onCloseAll()
            }
        }.onFailure {
            // Failure: the panel stays open.
            ShiroikumaDialogs.ok(context, "Export", "Export failed — ${it.message ?: "unknown error"}")
        }
    }

    // ---- import -----------------------------------------------------------------------------

    private fun onImport() {
        if (!ensureReady()) return
        if (selected.isEmpty()) {
            ShiroikumaDialogs.ok(context, "Import", "No categories selected.")
            return
        }
        val backups = ShiroikumaBackup.listBackups(ShiroikumaBackup.exportDir(context))
        if (backups.isEmpty()) {
            ShiroikumaDialogs.ok(context, "Import", "No backups found in this folder.")
            return
        }
        val names = backups.map { it.name }.toTypedArray()
        AlertDialog.Builder(context, R.style.Theme_Shiroikuma_Dialog)
            .setTitle("Pick a backup")
            .setItems(names) { _, which -> runImport(backups[which]) }
            .setNegativeButton("Cancel", null)
            .show()
            .also { ShiroikumaDialogs.style(it) }
    }

    private fun runImport(file: File) {
        val parts = selected.toSet()
        val result = runCatching {
            file.inputStream().use { ShiroikumaBackup.import(context, parts, it) }
        }
        result.onSuccess { res ->
            val body = buildString {
                res.lines.forEach { appendLine(it) }
                if (res.errors.isNotEmpty()) {
                    appendLine()
                    appendLine("⚠ " + res.errors.joinToString(", "))
                }
                appendLine()
                append("Restart the app for every change to take effect.")
            }.trim()

            if (res.ok) {
                // Success → both buttons close the whole chain; "Restart now" also restarts.
                ShiroikumaDialogs.choice(
                    context,
                    "Import complete",
                    body,
                    positive = "Restart now",
                    negative = "Later",
                    onPositive = {
                        dismissPanel()
                        onCloseAll()
                        restartApp()
                    },
                    onNegative = {
                        dismissPanel()
                        onCloseAll()
                    }
                )
            } else {
                // Partial failure: leave the panel open.
                ShiroikumaDialogs.ok(context, "Import", body)
            }
        }.onFailure {
            ShiroikumaDialogs.ok(context, "Import", "Import failed — ${it.message ?: "unknown error"}")
        }
    }

    private fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (intent != null) {
            context.startActivity(intent)
            Runtime.getRuntime().exit(0)
        }
    }

    // ---- folder -----------------------------------------------------------------------------

    private fun ensureReady(): Boolean {
        if (!hasAllFilesAccess()) {
            requestAllFilesAccess(); return false
        }
        if (ShiroikumaBackup.exportDir(context) == null) {
            editDir(); return false
        }
        return true
    }

    private fun editDir() {
        val current = ShiroikumaUiPrefs.getString(context, ShiroikumaUiPrefs.KEY_EXPORT_DIR)
        val input = EditText(context).apply {
            setText(current)
            hint = "/storage/emulated/0/…"
            setSingleLine()
            setTextColor(accent)
            setHintTextColor(dim)
        }
        val box = FrameLayout(context).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(context, R.style.Theme_Shiroikuma_Dialog)
            .setTitle("Backup folder")
            .setMessage("A real filesystem path — every sister app writes its backups here.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> saveDir(input.text.toString()) }
            .setNeutralButton("Browse…") { _, _ ->
                val start = current.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }
                    ?: Environment.getExternalStorageDirectory()
                browseForFolder(start) { picked -> saveDir(picked.absolutePath) }
            }
            .setNegativeButton("Cancel", null)
            .show()
            .also { ShiroikumaDialogs.style(it) }
    }

    private fun saveDir(path: String) {
        ShiroikumaUiPrefs.setString(context, ShiroikumaUiPrefs.KEY_EXPORT_DIR, path.trim())
        rebuild()
    }

    private fun browseForFolder(dir: File, onPick: (File) -> Unit) {
        if (!hasAllFilesAccess()) {
            requestAllFilesAccess(); return
        }
        val subDirs = dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val labels = mutableListOf<String>()
        val targets = mutableListOf<File?>()
        labels.add("✓ Use this folder"); targets.add(null)
        dir.parentFile?.let { labels.add(".. (${it.name.ifBlank { "/" }})"); targets.add(it) }
        subDirs.forEach { labels.add("📁 ${it.name}"); targets.add(it) }
        AlertDialog.Builder(context, R.style.Theme_Shiroikuma_Dialog)
            .setTitle(dir.absolutePath)
            .setItems(labels.toTypedArray()) { _, which ->
                val t = targets[which]
                if (t == null) onPick(dir) else browseForFolder(t, onPick)
            }
            .setNegativeButton("Cancel", null)
            .show()
            .also { ShiroikumaDialogs.style(it) }
    }

    private fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        val pkg = "package:" + context.packageName
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse(pkg))
            )
        } catch (_: Exception) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    // ---- view builders ----------------------------------------------------------------------

    private fun heading(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(accent)
        setTypeface(ShiroikumaFonts.current(context), Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun caption(text: String, topGap: Int = 0, color: Int = dim) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        typeface = ShiroikumaFonts.current(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topGap) }
    }

    private fun checkbox(labelText: String, bold: Boolean = false): CheckBox = CheckBox(context).apply {
        text = labelText
        setTextColor(accent)
        setTypeface(ShiroikumaFonts.current(context), if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        buttonTintList = ColorStateList.valueOf(accent)
        setPadding(dp(8), dp(7), 0, dp(7))
    }

    private fun divider(topGap: Int = 0): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            .apply { topMargin = dp(topGap) }
        setBackgroundColor(ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_COLOR_DIVIDER))
        alpha = 0.4f
    }

    private fun spacer(height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
    }

    /** An ArcaneChat-style round pill: black fill, thin accent stroke, accent text, accent ripple. */
    private fun pillButton(text: String, onClick: () -> Unit): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextColor(accent)
        typeface = ShiroikumaFonts.current(context)
        background = RippleDrawable(
            ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x33000000),
            GradientDrawable().apply {
                setColor(bg)
                setStroke(
                    (ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_PILL_BORDER) *
                        context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    accent
                )
                cornerRadius = dp(ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_PILL_RADIUS)).toFloat()
            },
            null
        )
        // Explicit padding + zeroed minimums so the rounded stroke is never clipped at the edge.
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(20), dp(6), dp(20), dp(6))
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); gravity = Gravity.CENTER }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.1f kB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
