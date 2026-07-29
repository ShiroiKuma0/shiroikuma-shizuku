package af.shizuku.manager.settings

import android.app.Dialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.databinding.BugReportDialogBinding
import af.shizuku.manager.ktx.asLink
import af.shizuku.manager.ktx.applyTemplateArgs
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.worker.AdbStartWorker

class BugReportDialog : DialogFragment() {

    private lateinit var binding: BugReportDialogBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = BugReportDialogBinding.inflate(layoutInflater)

        val updateLink = getString(R.string.bug_report_dialog_link_update)
            .asLink("https://github.com/ShiroiKuma0/shiroikuma-shizuku/releases/latest")

        // Fork: upstream had "/releases/wiki" and "/releases/issues" here — both 404. Corrected
        // to the real paths while repointing them at our fork.
        val wikiLink = getString(R.string.bug_report_dialog_link_wiki)
            .asLink("https://github.com/ShiroiKuma0/shiroikuma-shizuku/wiki#troubleshooting")

        val issuesLink = getString(R.string.bug_report_dialog_link_issues)
            .asLink("https://github.com/ShiroiKuma0/shiroikuma-shizuku/issues")

        binding.apply {
            updateText.applyTemplateArgs(updateLink)
            wikiText.applyTemplateArgs(wikiLink)
            issuesText.applyTemplateArgs(issuesLink)
            methodText.applyTemplateArgs("GitHub")
        }

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_report_bug)
            .setView(binding.root)
            .setPositiveButton(R.string.action_open_github) { _, _ ->
                CustomTabsHelper.launchUrlOrCopy(context, "https://github.com/ShiroiKuma0/shiroikuma-shizuku/issues/new")
            }
            // Fork: the "email support" button is removed. It sent a device/OS/version report to
            // the upstream author's support address (shizukuplus-support@thejaustin.com), which is
            // both upstream branding and an outbound channel this fork does not have. See
            // CLAUDE.md, "No phone-home".
            .setNeutralButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .create()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(AdbStartWorker.NOTIFICATION_ID)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity is BugReportDialogActivity) activity?.finish()
    }

}
