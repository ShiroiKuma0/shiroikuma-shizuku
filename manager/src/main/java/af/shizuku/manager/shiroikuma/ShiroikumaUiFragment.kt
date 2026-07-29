package af.shizuku.manager.shiroikuma

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import af.shizuku.manager.R
import af.shizuku.manager.settings.BaseSettingsFragment
import af.shizuku.manager.shiroikuma.automation.AutomationAuth

/**
 * **白い熊 雫 UI** — the house customization page.
 *
 * Page conventions (kxkb style — keep them):
 * - Headings are big, bold, accent-coloured and underlined **only as wide as their own text**; each
 *   top-level section is preceded by a thin full-width hairline.
 * - Items indent one step per level (36 → 54 → 72 → 90 dp), sub-headings included, and row padding
 *   stays **tight** — the only generous space is above a section heading.
 * - Every group carries a **live preview**.
 * - Colour pickers are **RGBA** (four sliders) with one-click prefilled swatches above.
 * - Every size is a slider, and border/thickness/roundness sliders reach **0**.
 *
 * The page opens with the **Export/import** section, then the look knobs.
 *
 * Reached from Settings → 白い熊 雫 UI, and by **long-pressing the settings cog** on the home screen.
 */
class ShiroikumaUiFragment : BaseSettingsFragment() {

    private val importFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = ShiroikumaFonts.importFont(requireContext(), uri)
            if (name != null) {
                ShiroikumaUiPrefs.setString(requireContext(), ShiroikumaUiPrefs.KEY_FONT_FAMILY, name)
                rebuild()
            } else {
                ShiroikumaDialogs.ok(
                    requireContext(), "Import font",
                    "That file could not be imported — only .ttf and .otf are supported."
                )
            }
        }
    }

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        build(preferenceScreen)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(
            ShiroikumaUiPrefs.getInt(requireContext(), ShiroikumaUiPrefs.KEY_COLOR_BACKGROUND)
        )
        listView?.setBackgroundColor(
            ShiroikumaUiPrefs.getInt(requireContext(), ShiroikumaUiPrefs.KEY_COLOR_BACKGROUND)
        )
        // The page's own separators are drawn by the category layouts, not by the list.
        setDivider(null)
        setDividerHeight(0)
        // This page is styled by its own house layouts; the generic View applier must not walk it,
        // or it would flatten the sub-heading and dim-summary colours back to body text.
        ShiroikumaViewTheme.markSkipped(view)
    }

    /** Re-render so every live preview reflects the change that was just made. */
    private fun rebuild() {
        // Push the new values into the Compose theme too, so the change is not confined to this
        // page — a knob that only moves its own preview is a bug.
        ShiroikumaTheme.refresh()
        preferenceScreen?.removeAll()
        build(preferenceScreen)
        view?.setBackgroundColor(
            ShiroikumaUiPrefs.getInt(requireContext(), ShiroikumaUiPrefs.KEY_COLOR_BACKGROUND)
        )
        listView?.setBackgroundColor(
            ShiroikumaUiPrefs.getInt(requireContext(), ShiroikumaUiPrefs.KEY_COLOR_BACKGROUND)
        )
    }

    // ---------------------------------------------------------------------------------------
    // The page
    // ---------------------------------------------------------------------------------------

    private fun build(screen: PreferenceScreen) {
        val ctx = requireContext()
        val p = ShiroikumaUiPrefs

        // ===== 1. Export / import — the first, separated section ==============================
        screen.addPreference(ShiroikumaCategory(ctx, first = true).apply {
            title = "保存復元 — Export / import"
        })
        screen.addPreference(ShiroikumaItem(ctx).apply {
            title = "Export / import…"
            val dir = p.getString(ctx, p.KEY_EXPORT_DIR)
            // markWarnIfUnset() below re-colours this RED when no folder is set — the same signal
            // the panel itself shows, so the warning is visible without opening it.
            summary = if (dir.isBlank()) "Backup folder not set" else dir
            setOnPreferenceClickListener {
                ExportImportPanel(requireActivity()) {
                    // Close the UI settings page behind the panel.
                    activity?.onBackPressedDispatcher?.onBackPressed()
                }.show()
                true
            }
        }.also { pref -> markWarnIfUnset(pref) })

        // The automation controls belong INSIDE this Export/Import section, directly below the
        // existing rows — this is a backup feature, so it lives where backup lives, and every
        // sister app looks the same. Not a section of its own.
        screen.addPreference(SwitchPreferenceCompat(ctx).apply {
            layoutResource = R.layout.preference_item_shizuku
            isIconSpaceReserved = false
            title = "Automation export"
            summary = "Let sister-app tasks trigger this app's export through the token-gated intent"
            isChecked = AutomationAuth.enabled(ctx)
            setOnPreferenceChangeListener { _, newValue ->
                AutomationAuth.setEnabled(ctx, newValue as Boolean)
                true
            }
        })
        screen.addPreference(
            AutomationTokenPreference(ctx, onRegenerate = { AutomationAuth.regenerate(ctx); rebuild() }).apply {
                title = "Automation token"
                summary = AutomationAuth.abbreviated(ctx) + "  —  tap to copy"
                setOnPreferenceClickListener {
                    val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("token", AutomationAuth.token(ctx))
                    )
                    android.widget.Toast.makeText(ctx, "Token copied", android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
            }
        )

        // ===== 2. Colours ====================================================================
        screen.addPreference(ShiroikumaCategory(ctx).apply { title = "色 — Colours" })
        screen.addPreference(ShiroikumaSubCategory(ctx).apply { title = "Surfaces" })
        colour(screen, "Background", p.KEY_COLOR_BACKGROUND, 2)
        colour(screen, "Card / surface fill", p.KEY_CARD_FILL, 2)
        screen.addPreference(ShiroikumaSubCategory(ctx).apply { title = "Text" })
        colour(screen, "Body text", p.KEY_COLOR_TEXT, 2)
        colour(screen, "Summary / dim text", p.KEY_COLOR_TEXT_DIM, 2)
        colour(screen, "Headings", p.KEY_COLOR_HEADING, 2)
        colour(screen, "Warning text", p.KEY_COLOR_WARN, 2)
        screen.addPreference(ShiroikumaSubCategory(ctx).apply { title = "Lines & accents" })
        colour(screen, "Accent (buttons, sliders)", p.KEY_COLOR_ACCENT, 2)
        colour(screen, "Borders — groups & major items", p.KEY_COLOR_BORDER, 2)
        colour(screen, "Borders — ordinary items", p.KEY_COLOR_BORDER_MINOR, 2)
        colour(screen, "Dividers", p.KEY_COLOR_DIVIDER, 2)
        colour(screen, "Icons", p.KEY_COLOR_ICON, 2)
        preview(screen)
        resetRow(screen, "Reset colours", ShiroikumaUiPrefs.Category.COLOURS)

        // ===== 3. Typography =================================================================
        screen.addPreference(ShiroikumaCategory(ctx).apply { title = "文字 — Typography" })
        screen.addPreference(ShiroikumaItem(ctx).apply {
            title = "Font family"
            summary = ShiroikumaFonts.displayName(ctx, p.getString(ctx, p.KEY_FONT_FAMILY))
            setOnPreferenceClickListener {
                FontPicker.show(
                    ctx,
                    current = p.getString(ctx, p.KEY_FONT_FAMILY),
                    onPick = { p.setString(ctx, p.KEY_FONT_FAMILY, it); rebuild() },
                    onImport = { importFont.launch(arrayOf("font/*", "application/octet-stream", "*/*")) },
                    onDelete = { ShiroikumaFonts.deleteFont(ctx, it); rebuild() }
                )
                true
            }
        })
        slider(screen, "Font weight", p.KEY_FONT_WEIGHT, 1)
        slider(screen, "Body text size", p.KEY_TEXT_SIZE, 1)
        slider(screen, "Heading size", p.KEY_HEADING_SIZE, 1)
        slider(screen, "Summary size", p.KEY_SUMMARY_SIZE, 1)
        slider(screen, "Letter spacing", p.KEY_TEXT_LETTER_SPACING, 1)
        toggle(screen, "Bold headings", p.KEY_HEADING_BOLD, 1)
        preview(screen)
        resetRow(screen, "Reset typography", ShiroikumaUiPrefs.Category.TYPOGRAPHY)

        // ===== 4. Shape & borders ============================================================
        screen.addPreference(ShiroikumaCategory(ctx).apply { title = "形 — Shape & borders" })
        screen.addPreference(ShiroikumaSubCategory(ctx).apply { title = "Boxes" })
        slider(screen, "Corner roundness", p.KEY_CORNER_RADIUS, 2)
        slider(screen, "Border thickness", p.KEY_BORDER_WIDTH, 2)
        screen.addPreference(ShiroikumaSubCategory(ctx).apply { title = "Lines" })
        slider(screen, "Divider thickness", p.KEY_DIVIDER_HEIGHT, 2)
        slider(screen, "Heading underline", p.KEY_HEADING_UNDERLINE, 2)
        screen.addPreference(ShiroikumaSubCategory(ctx).apply { title = "Pill buttons" })
        slider(screen, "Pill roundness", p.KEY_PILL_RADIUS, 2)
        slider(screen, "Pill border", p.KEY_PILL_BORDER, 2)
        preview(screen)
        resetRow(screen, "Reset shape", ShiroikumaUiPrefs.Category.SHAPE)

        // ===== 5. Spacing & size =============================================================
        screen.addPreference(ShiroikumaCategory(ctx).apply { title = "間隔 — Spacing & size" })
        slider(screen, "Row padding", p.KEY_ROW_PADDING, 1)
        slider(screen, "Gap above a section", p.KEY_GROUP_GAP, 1)
        slider(screen, "Indent per level", p.KEY_INDENT_STEP, 1)
        slider(screen, "Icon size", p.KEY_ICON_SIZE, 1)
        preview(screen)
        resetRow(screen, "Reset spacing", ShiroikumaUiPrefs.Category.SPACING)

        // ===== 6. Lists & cards ==============================================================
        screen.addPreference(ShiroikumaCategory(ctx).apply { title = "一覧 — Lists & cards" })
        slider(screen, "Card border", p.KEY_CARD_BORDER, 1)
        slider(screen, "Card roundness", p.KEY_CARD_RADIUS, 1)
        toggle(screen, "Tint list icons with the accent", p.KEY_LIST_ICON_TINT, 1)
        preview(screen)
        resetRow(screen, "Reset lists & cards", ShiroikumaUiPrefs.Category.LISTS)

        // ===== 7. Everything back to the house look ==========================================
        screen.addPreference(ShiroikumaCategory(ctx).apply { title = "初期化 — Reset" })
        screen.addPreference(ShiroikumaItem(ctx).apply {
            title = "Restore the black-yellow defaults"
            summary = "Every knob on this page back to the house look"
            setOnPreferenceClickListener {
                ShiroikumaDialogs.choice(
                    ctx, "Restore defaults",
                    "Reset every 白い熊 雫 UI setting to pure black with pure yellow?",
                    positive = "Reset", negative = getString(android.R.string.cancel),
                    onPositive = {
                        ShiroikumaUiPrefs.Category.entries.forEach { p.resetCategory(ctx, it) }
                        rebuild()
                    },
                    onNegative = {}
                )
                true
            }
        })
    }

    // ---------------------------------------------------------------------------------------
    // Row builders
    // ---------------------------------------------------------------------------------------

    private fun markWarnIfUnset(pref: Preference) {
        val ctx = requireContext()
        if (ShiroikumaUiPrefs.getString(ctx, ShiroikumaUiPrefs.KEY_EXPORT_DIR).isBlank()) {
            // Red until a folder is chosen — same signal as inside the panel.
            pref.summary = buildRedSummary("Backup folder not set")
        }
    }

    private fun buildRedSummary(text: String): CharSequence {
        val span = android.text.SpannableString(text)
        span.setSpan(
            android.text.style.ForegroundColorSpan(
                ShiroikumaUiPrefs.getInt(requireContext(), ShiroikumaUiPrefs.KEY_COLOR_WARN)
            ),
            0, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }

    private fun colour(screen: PreferenceScreen, label: String, key: String, level: Int) {
        val ctx = requireContext()
        screen.addPreference(ColorSwatchPreference(ctx, level).apply {
            title = label
            color = ShiroikumaUiPrefs.getInt(ctx, key)
            setOnPreferenceClickListener {
                ColorPicker.show(ctx, label, ShiroikumaUiPrefs.getInt(ctx, key)) { picked ->
                    ShiroikumaUiPrefs.setInt(ctx, key, picked)
                    color = picked
                    rebuild()
                }
                true
            }
        })
    }

    private fun slider(screen: PreferenceScreen, label: String, key: String, level: Int) {
        val ctx = requireContext()
        val knob = ShiroikumaUiPrefs.INT_DEFAULTS[key] ?: return
        screen.addPreference(ShiroikumaSeekBar(ctx, level).apply {
            title = label
            unit = knob.unit
            min = knob.min
            max = knob.max
            value = ShiroikumaUiPrefs.getInt(ctx, key)
            updatesContinuously = true
            setOnPreferenceChangeListener { _, newValue ->
                ShiroikumaUiPrefs.setInt(ctx, key, newValue as Int)
                // Refresh the previews without rebuilding the whole tree mid-drag.
                ShiroikumaTheme.refresh()
                notifyPreviewsChanged()
                true
            }
        })
    }

    private fun toggle(screen: PreferenceScreen, label: String, key: String, level: Int) {
        val ctx = requireContext()
        screen.addPreference(SwitchPreferenceCompat(ctx).apply {
            layoutResource =
                if (level >= 2) R.layout.preference_item_shizuku_l2 else R.layout.preference_item_shizuku
            isIconSpaceReserved = false
            title = label
            isChecked = ShiroikumaUiPrefs.getBool(ctx, key)
            setOnPreferenceChangeListener { _, newValue ->
                ShiroikumaUiPrefs.setBool(ctx, key, newValue as Boolean)
                rebuild()
                true
            }
        })
    }

    private fun preview(screen: PreferenceScreen) {
        screen.addPreference(ShiroikumaPreviewPreference(requireContext()).apply {
            key = "preview_${screen.preferenceCount}"
        })
    }

    private fun resetRow(screen: PreferenceScreen, label: String, category: ShiroikumaUiPrefs.Category) {
        val ctx = requireContext()
        screen.addPreference(ShiroikumaItem(ctx).apply {
            title = label
            summary = "Back to the house default"
            setOnPreferenceClickListener {
                ShiroikumaUiPrefs.resetCategory(ctx, category)
                rebuild()
                true
            }
        })
    }

    /** Ask every preview row to redraw (cheap — they rebuild their own sample card). */
    private fun notifyPreviewsChanged() {
        val screen = preferenceScreen ?: return
        for (i in 0 until screen.preferenceCount) {
            (screen.getPreference(i) as? ShiroikumaPreviewPreference)?.refresh()
        }
    }

    companion object {
        /** Open this page directly (used by the home screen's settings-cog long-press). */
        fun intent(context: android.content.Context): Intent =
            Intent(context, af.shizuku.manager.settings.SettingsActivity::class.java)
                .putExtra(EXTRA_OPEN_SHIROIKUMA_UI, true)

        const val EXTRA_OPEN_SHIROIKUMA_UI = "open_shiroikuma_ui"
    }
}
