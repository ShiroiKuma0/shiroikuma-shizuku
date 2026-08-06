package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.update.UpdateChecker
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.HapticUtils
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
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
    data class ReleaseItem(
        val tag: String,
        val date: String,
        val body: String,
        val isNew: Boolean = false,
    )

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
            rawNotes
                .substringBefore("## 📦 Recent Releases")
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

        // Tag selection implementation (used by Markwon link resolver)
        selectAndDisplayTag = { targetTag ->
            val cleanTarget = targetTag.removePrefix("v").trim()
            val foundIndex =
                releases.indexOfFirst {
                    it.tag
                        .removePrefix("v")
                        .trim()
                        .equals(cleanTarget, ignoreCase = true)
                }
            if (foundIndex >= 0) {
                val targetRelease = releases[foundIndex]
                val chipIndex = if (combinedNewRelease != null) foundIndex + 1 else foundIndex
                if (chipIndex < chipGroup.childCount) {
                    val targetChip = chipGroup.getChildAt(chipIndex) as? Chip
                    targetChip?.isChecked = true
                    targetChip?.let { earlierScroll?.smoothScrollTo(it.left, 0) }
                }
                HapticUtils.segmentTick(notesView)
                displayRelease(targetRelease)
            } else {
                lifecycleScope.launch {
                    try {
                        val notes = UpdateChecker.fetchReleaseNotesForTag(targetTag)
                        if (notes != null && isAdded && !isDetached) {
                            val singleRelease = ReleaseItem(tag = targetTag, date = "", body = notes)
                            HapticUtils.segmentTick(notesView)
                            displayRelease(singleRelease)
                        } else if (isAdded && !isDetached) {
                            CustomTabsHelper.launchUrlOrCopy(
                                requireContext(),
                                "https://github.com/thejaustin/ShizukuPlus/releases/tag/$targetTag",
                            )
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to load release for tag $targetTag")
                    }
                }
            }
        }

        // Initial release display
        if (combinedNewRelease != null) {
            displayRelease(combinedNewRelease, isCombined = true)
        } else if (releases.isNotEmpty()) {
            displayRelease(releases.first())
        } else {
            versionText.text = tagName
            notesView.setText(R.string.changelog_fallback_message)
        }
        notesView.movementMethod = LinkMovementMethod.getInstance()

        // "View on GitHub" links to the currently selected release's page
        btnGithub.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/ShiroiKuma0/shiroikuma-shizuku/releases/tag/$tagName")))
            } catch (e: Exception) {
                Timber.w(e, "Failed to open release page for $currentSelectedTag")
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

    private fun parseReleases(json: String?): List<ReleaseItem> {
        if (json == null) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length())
                .map { i ->
                    val obj = arr.getJSONObject(i)
                    ReleaseItem(
                        tag = obj.optString("tag", ""),
                        date = obj.optString("date", ""),
                        body = obj.optString("body", ""),
                        isNew = obj.optBoolean("is_new", false),
                    )
                }.filter { it.tag.isNotBlank() }
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
