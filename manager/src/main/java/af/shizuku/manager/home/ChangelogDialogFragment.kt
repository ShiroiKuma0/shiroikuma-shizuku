package af.shizuku.manager.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import io.noties.markwon.Markwon
import org.json.JSONArray
import org.json.JSONObject
import af.shizuku.manager.R
import af.shizuku.manager.update.UpdateChecker
import timber.log.Timber

/**
 * Shows what changed in the version the user just updated to. [newInstance] takes the Markdown
 * section for that version, read by the caller from the changelog bundled in the APK
 * ([af.shizuku.manager.shiroikuma.ShiroikumaChangelog]) — this fragment only formats and displays
 * it, so it stays usable if the section is somehow missing.
 *
 * FORK: the notes used to come from a GitHub fetch, which could never succeed here — see
 * [af.shizuku.manager.shiroikuma.ShiroikumaChangelog] for why the tag never matched and why a
 * network source was the wrong shape for this fork in the first place.
 */
class ChangelogDialogFragment : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ChangelogDialogFragment"
        private const val ARG_NOTES = "notes"
        private const val ARG_VERSION_NAME = "version_name"

        fun newInstance(notes: String?, versionName: String): ChangelogDialogFragment =
            ChangelogDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_NOTES, notes)
                    putString(ARG_VERSION_NAME, versionName)
                }
            }

        fun formatNotes(rawNotes: String): String =
            rawNotes.substringBefore("## 📦 Recent Releases")
                .replace(COMMIT_HASH_SUFFIX, "")
                .let { stripConventionalPrefixes(it) }
                .trim()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val rawNotes = arguments?.getString(ARG_NOTES)
        val versionName = arguments?.getString(ARG_VERSION_NAME) ?: ""
        val markwon = Markwon.create(requireContext())

        val current = releases.firstOrNull()
        val previous = releases.drop(1)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.changelog_title)
            .setMessage(message)
            .setPositiveButton(R.string.changelog_close, null)
            .setNeutralButton(R.string.changelog_view_on_github) { _, _ ->
                try {
                    // Our release tags ARE the fork versionName, with no `v` prefix — upstream's
                    // convention is the other way round and using it here is what broke the
                    // changelog in the first place. A build that was never published has no page;
                    // the releases index is the honest destination for it.
                    val base = "https://github.com/ShiroiKuma0/shiroikuma-shizuku/releases"
                    val url = if (versionName.isNotEmpty()) "$base/tag/$versionName" else base
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Timber.w(e, "Failed to open release page for $versionName")
                }
                chipGroup.addView(chip)
            }
        } else {
            earlierSection.isVisible = false
        }

        // "View on GitHub" links to the current release's page
        view.findViewById<MaterialButton>(R.id.btn_github).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/ShiroiKuma0/shiroikuma-shizuku/releases/tag/$tagName")))
            } catch (e: Exception) {
                Timber.w(e, "Failed to open release page for $tagName")
            }
        }

        view.findViewById<MaterialButton>(R.id.btn_close).setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    override fun onStart() {
        super.onStart()
        val dlg = dialog as? BottomSheetDialog ?: return
        val sheet = dlg.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val screenHeight = resources.displayMetrics.heightPixels
        sheet.layoutParams = sheet.layoutParams.apply { height = (screenHeight * 0.82).toInt() }
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    private fun parseReleases(json: String?): List<Triple<String, String, String>> {
        if (json == null) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Triple(obj.optString("tag", ""), obj.optString("date", ""), obj.optString("body", ""))
            }.filter { it.first.isNotBlank() }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse releases JSON")
            emptyList()
        }
    }

    /**
     * FORK: the house look — black fill, **yellow border**.
     *
     * It has to happen here rather than on the builder: `MaterialAlertDialogBuilder` installs its
     * own `MaterialShapeDrawable` window background during `show()`, which overrides the bordered
     * `android:windowBackground` our dialog theme sets. `onStart()` runs after the dialog is shown,
     * so this is the first point at which the background can be replaced for good.
     */
    override fun onStart() {
        super.onStart()
        dialog?.let { af.shizuku.manager.shiroikuma.ShiroikumaDialogs.style(it) }
    }
}
