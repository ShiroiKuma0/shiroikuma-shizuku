package af.shizuku.manager.home

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
import af.shizuku.manager.R
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
class ChangelogDialogFragment : DialogFragment() {

    // LinkMovementMethod consumes every touch event (including scroll gestures), which prevents the
    // parent AlertDialog ScrollView from scrolling. This subclass only intercepts DOWN/UP events
    // that land on a ClickableSpan — all other events fall through so the dialog can still scroll.
    private object LinkOnlyMovementMethod : LinkMovementMethod() {
        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            val action = event.actionMasked
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return false
            val x = (event.x - widget.totalPaddingLeft + widget.scrollX).toInt()
            val y = (event.y - widget.totalPaddingTop + widget.scrollY).toInt()
            val layout = widget.layout ?: return false
            val line = layout.getLineForVertical(y)
            val offset = layout.getOffsetForHorizontal(line, x.toFloat())
            return buffer.getSpans(offset, offset, ClickableSpan::class.java).isNotEmpty() &&
                super.onTouchEvent(widget, buffer, event)
        }
    }

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

        /**
         * GitHub release notes are Markdown meant for a web page. For a short dialog, drop the
         * "Recent Releases" rollup table/links (useful on GitHub, noisy here) and strip trailing
         * short git commit hashes (e.g. " (a95d0130)") from bullet lines — they appear in the
         * auto-generated release body but add nothing for end users. The rest is rendered as real
         * Markdown by Markwon so bold/italic/code/list formatting shows up instead of literal
         * `**`/`_`/`` ` ``.
         */
        private val COMMIT_HASH_SUFFIX = Regex("""\s+\([0-9a-f]{7,8}\)$""", RegexOption.MULTILINE)

        private fun formatForDialog(rawNotes: String): String =
            rawNotes.substringBefore("## 📦 Recent Releases")
                .replace(COMMIT_HASH_SUFFIX, "")
                .trim()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val rawNotes = arguments?.getString(ARG_NOTES)
        val versionName = arguments?.getString(ARG_VERSION_NAME) ?: ""
        val markwon = Markwon.create(requireContext())

        val message: CharSequence = try {
            rawNotes?.let { formatForDialog(it) }?.takeIf { it.isNotBlank() }
                ?.let { markwon.toMarkdown(it) }
                ?: getString(R.string.changelog_fallback_message)
        } catch (e: Exception) {
            Timber.w(e, "Failed to format release notes for dialog")
            getString(R.string.changelog_fallback_message)
        }

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
            }
            .create()

        // Bold/italic/headings/code/lists render from the Spanned message above with no extra
        // work, but a tappable Markdown link needs a movement method on the message TextView -
        // AlertDialog's default one has none, so set it once the view actually exists.
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
                LinkOnlyMovementMethod
        }

        return dialog
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
