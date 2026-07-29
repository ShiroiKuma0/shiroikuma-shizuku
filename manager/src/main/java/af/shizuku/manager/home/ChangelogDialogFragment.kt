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
 * Material Expressive bottom sheet that shows what changed since the user's last update.
 * Displays the newest release in full with formatted Markdown, then surfaces older releases
 * as tappable chips so users can browse history without the dialog becoming overwhelming.
 */
class ChangelogDialogFragment : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ChangelogDialogFragment"
        private const val ARG_RELEASES_JSON = "releases_json"
        private const val ARG_TAG_NAME = "tag_name"

        fun newInstance(
            releases: List<UpdateChecker.ReleaseEntry>,
            currentTagName: String
        ): ChangelogDialogFragment = ChangelogDialogFragment().apply {
            val arr = JSONArray()
            releases.forEach { r ->
                arr.put(JSONObject().apply {
                    put("tag", r.tagName)
                    put("date", r.publishedAt)
                    put("body", r.body)
                })
            }
            arguments = Bundle().apply {
                putString(ARG_RELEASES_JSON, arr.toString())
                putString(ARG_TAG_NAME, currentTagName)
            }
        }

        private val COMMIT_HASH_SUFFIX = Regex("""\s+\([0-9a-f]{7,8}\)$""", RegexOption.MULTILINE)
        private val CC_PREFIX = Regex(
            """^(fix|feat|chore|refactor|perf|test|docs|build|ci|style|revert)(\([^)]+\))?:\s*""",
            RegexOption.IGNORE_CASE
        )

        private fun stripConventionalPrefixes(text: String): String =
            text.lines().joinToString("\n") { line ->
                val bulletEnd = Regex("""^[-*]\s+""").find(line)?.range?.last?.plus(1)
                    ?: return@joinToString line
                val bullet = line.substring(0, bulletEnd)
                val rest = line.substring(bulletEnd)
                val stripped = CC_PREFIX.replaceFirst(rest, "")
                if (stripped == rest) line
                else bullet + stripped.replaceFirstChar { it.uppercase() }
            }

        fun formatNotes(rawNotes: String): String =
            rawNotes.substringBefore("## 📦 Recent Releases")
                .replace(COMMIT_HASH_SUFFIX, "")
                .let { stripConventionalPrefixes(it) }
                .trim()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_changelog, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tagName = arguments?.getString(ARG_TAG_NAME) ?: ""
        val releases = parseReleases(arguments?.getString(ARG_RELEASES_JSON))
        val markwon = Markwon.create(requireContext())

        val current = releases.firstOrNull()
        val previous = releases.drop(1)

        // Version/date subtitle under the title
        view.findViewById<TextView>(R.id.version_text).text = current?.let { (tag, date, _) ->
            val formatted = UpdateChecker.formatPublishedDate(date)
            if (formatted.isNotBlank() && formatted != date) "$tag · $formatted" else tag
        } ?: tagName

        // Notes body for the current (newest) release
        val notesView = view.findViewById<TextView>(R.id.notes_text)
        val rawNotes = current?.third
        val formatted = rawNotes?.let { formatNotes(it) }?.takeIf { it.isNotBlank() }
        if (formatted != null) {
            markwon.setMarkdown(notesView, formatted)
        } else {
            notesView.setText(R.string.changelog_fallback_message)
        }
        notesView.movementMethod = LinkMovementMethod.getInstance()

        // Earlier releases as tappable chips — each opens that release's GitHub page
        val earlierSection = view.findViewById<LinearLayout>(R.id.earlier_section)
        val chipGroup = view.findViewById<ChipGroup>(R.id.earlier_chip_group)
        if (previous.isNotEmpty()) {
            earlierSection.isVisible = true
            previous.forEach { (prevTag, _, _) ->
                val chip = Chip(requireContext()).apply {
                    text = prevTag
                    isCheckable = false
                    setEnsureMinTouchTargetSize(true)
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/ShiroiKuma0/shiroikuma-shizuku/releases/tag/$prevTag")))
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to open release $prevTag")
                        }
                    }
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
