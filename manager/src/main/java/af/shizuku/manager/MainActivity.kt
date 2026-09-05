package af.shizuku.manager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import timber.log.Timber
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.R
import af.shizuku.manager.home.ChangelogDialogFragment
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.migration.MigrationHelper
import af.shizuku.manager.onboarding.OnboardingActivity
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.shiroikuma.ShiroikumaChangelog
import af.shizuku.manager.shiroikuma.showHouse
import af.shizuku.manager.shiroikuma.ShiroikumaToast

class MainActivity : HomeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            Timber.d("Calling super.onCreate")
            super.onCreate(savedInstanceState)

            // Check for previous crashes and offer to report. Crash reporting is entirely
            // manual in this fork — nothing is sent anywhere — so this is gated only on the
            // developer switch, which keeps it out of the general-purpose UI.
            if (af.shizuku.manager.utils.CrashHandler.getLastCrashReport(this) != null) {
                if (ShizukuSettings.isVectorEnabled()) {
                    showCrashReportDialog()
                }
            }

            Timber.d("Checking onboarding status")

            // Auto-restore settings if a force-update backup exists
            checkAndRestoreBackup()

            // Show what's new after an update, off its own last-seen key.
            checkAndShowChangelog()

            Timber.d("MainActivity onCreate complete")
        } catch (e: Exception) {
            Timber.e(e, "Crash in MainActivity.onCreate")
            throw e
        }
    }

    override fun onStart() {
        try {
            super.onStart()
            // Update state machine on app start
            ShizukuStateMachine.update()
            // Self-heal the AICore+ accessibility service if an OEM power manager disabled it
            // while the app was backgrounded but the user still has the feature on (#320).
            af.shizuku.manager.automation.AICoreAccessibilityHealer.reenableIfNeeded(this)
        } catch (e: Exception) {
            Timber.e(e, "Error in onStart")
            throw e
        }
    }

    private fun checkAndRestoreBackup() {
        lifecycleScope.launch(Dispatchers.IO) {
            val backupFile = af.shizuku.manager.update.UpdateInstaller.getBackupFile(this@MainActivity)
            if (backupFile != null && backupFile.exists()) {
                try {
                    val json = backupFile.readText()
                    if (af.shizuku.manager.utils.SettingsBackupManager.import(this@MainActivity, json)) {
                        Timber.i("Successfully auto-restored settings from force-update backup")
                        backupFile.delete()
                        // Notify user or refresh UI if needed
                        withContext(Dispatchers.Main) {
                            ShiroikumaToast.show(this@MainActivity, R.string.migration_success_message, Toast.LENGTH_LONG)
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to auto-restore settings")
                }
            }
        }
    }

    /**
     * Shows "What's New" once per version bump, from the changelog bundled in the APK.
     *
     * FORK: this used to fetch GitHub release notes for a tag built as `"v" + <version part>` —
     * upstream's tag convention. Our tags carry no `v` and are the full fork versionName, so the
     * request 404'd every time and the dialog fell back to "couldn't load the release notes" on
     * every single update. Fixing the tag alone would not have helped: 白い熊 installs every build
     * and only some are published, so an unpublished build has no release to fetch. The changelog
     * is now generated into `assets/changelog.md` at build time (see [ShiroikumaChangelog]), which
     * also means the dialog costs no network request at all.
     */
    private fun checkAndShowChangelog() {
        val currentCode = try { packageManager.getPackageInfo(packageName, 0).versionCode } catch (e: Exception) { 0 }
        if (currentCode <= ShizukuSettings.getLastSeenChangelogVersion()) return

        lifecycleScope.launch {
            val notes = withContext(Dispatchers.IO) {
                try {
                    ShiroikumaChangelog.sectionFor(this@MainActivity, BuildConfig.VERSION_NAME)
                } catch (e: Exception) {
                    Timber.tag("MainActivity").w(e, "Failed to read bundled changelog")
                    null
                }
            }

            // Mark seen either way — a build whose asset somehow has no section for it shouldn't
            // re-prompt on every cold start; the dialog's fallback message covers that case once.
            ShizukuSettings.setLastSeenChangelogVersion(currentCode)

            if (isFinishing || isDestroyed) return@launch
            try {
                ChangelogDialogFragment.newInstance(notes, BuildConfig.VERSION_NAME)
                    .show(supportFragmentManager, ChangelogDialogFragment.TAG)
            } catch (e: Exception) {
                Timber.e(e, "Failed to show changelog dialog")
            }
        }
    }

    private fun showMigrationDialog() {
        lifecycleScope.launch {
            val hasRoot = withContext(Dispatchers.IO) { MigrationHelper.isRootAvailable() }
            if (isFinishing || isDestroyed) return@launch
            try {
                val builder = if (hasRoot) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.migration_dialog_title)
                        .setMessage(R.string.migration_dialog_message_root)
                        .setPositiveButton(R.string.migration_migrate_settings) { _, _ -> performMigration() }
                        .setNeutralButton(R.string.migration_uninstall_old) { _, _ -> launchUninstall(MigrationHelper.OLD_PACKAGE) }
                        .setNegativeButton(R.string.migration_dismiss, null)
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.migration_dialog_title)
                        .setMessage(R.string.migration_no_root_message)
                        .setPositiveButton(R.string.migration_uninstall_old) { _, _ -> launchUninstall(MigrationHelper.OLD_PACKAGE) }
                        .setNegativeButton(R.string.migration_dismiss, null)
                }
                builder.show()
            } catch (e: Exception) {
                Timber.e(e, "Failed to show migration dialog")
            }
        }
    }

    private fun showCrashReportDialog() {
        // The crash file on disk is the only record; this dialog lets it be shared as a
        // human-readable report. It is optional — if the themed context is unavailable
        // (e.g. theme mismatch on old ROM) we silently clear the crash file and move on.
        try {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.manual_report_title)
                .setMessage(R.string.crash_detected_dialog_message)
                .setPositiveButton(R.string.manual_report_button_github) { _, _ ->
                    af.shizuku.manager.utils.CrashReporter.shareAsFile(this)
                    af.shizuku.manager.utils.CrashHandler.clearLastCrash(this)
                }
                .setNegativeButton(R.string.crash_detected_dialog_ignore) { _, _ ->
                    af.shizuku.manager.utils.CrashHandler.clearLastCrash(this)
                }
                .showHouse()
        } catch (e: Exception) {
            Timber.e(e, "showCrashReportDialog failed — clearing crash file silently")
            af.shizuku.manager.utils.CrashHandler.clearLastCrash(this)
        }
    }

    private fun performMigration() {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                MigrationHelper.migrateSettings(this@MainActivity)
            }

            if (isFinishing || isDestroyed) return@launch

            val (title, message) = if (success) {
                Pair(R.string.migration_success_title, R.string.migration_success_message)
            } else {
                Pair(R.string.migration_failure_title, R.string.migration_failure_message)
            }

            try {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(R.string.migration_uninstall_old) { _, _ ->
                        launchUninstall(MigrationHelper.OLD_PACKAGE)
                    }
                    .setNegativeButton(R.string.migration_dismiss, null)
                    .showHouse()
            } catch (e: Exception) {
                Timber.e(e, "Failed to show migration result dialog")
            }
        }
    }

    private fun launchUninstall(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch uninstall for $packageName")
        }
    }
}
