package af.shizuku.manager.settings

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.backup.BackupKeyUnavailableException
import af.shizuku.manager.backup.BackupRestoreManager
import af.shizuku.manager.backup.CryptoUtils
import af.shizuku.manager.home.ChangelogDialogFragment
import af.shizuku.manager.security.BiometricLock
import af.shizuku.manager.update.UpdateChecker
import af.shizuku.manager.update.UpdateManager
import af.shizuku.manager.shiroikuma.ShiroikumaChangelog
import af.shizuku.manager.shiroikuma.ShiroikumaToast
import af.shizuku.manager.shiroikuma.showHouse
import af.shizuku.manager.utils.CustomTabsHelper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.crypto.AEADBadTagException

class AboutSettingsFragment : BaseSettingsFragment() {
    companion object {
        private const val TAG = "AboutSettingsFragment"
        private const val KEY_AUTO_UPDATE = "auto_update_enabled"
        private const val KEY_AUTO_INSTALL = "auto_install_enabled"
        private const val KEY_UPDATE_CHANNEL = "update_channel"
        private const val KEY_CHECK_FOR_UPDATE = "check_for_update"
        private const val RELEASES_URL = "https://github.com/ShiroiKuma0/shiroikuma-shizuku/releases"
    }

    private val updateManager: UpdateManager by inject()
    private var versionClickCount = 0

    private fun backupErrorMessage(
        prefix: String,
        e: Exception,
    ): String =
        when (e) {
            is KeyPermanentlyInvalidatedException ->
                "$prefix: your device's screen lock or biometrics changed since this backup's " +
                    "encryption key was created, which permanently invalidates it by design. " +
                    if (prefix == "Restore failed") {
                        "This backup can no longer be decrypted."
                    } else {
                        "Please try again to generate a new key."
                    }
            is BackupKeyUnavailableException -> "$prefix: ${e.message}"
            is AEADBadTagException ->
                "$prefix: this backup could not be decrypted. It was most likely created by a different " +
                    "installation of 白い熊 雫 — backups are encrypted per-install and can't be restored " +
                    "after reinstalling or clearing the app's data."
            else -> "$prefix: ${e.message ?: e.javaClass.simpleName}"
        }

    private val createPlainBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext()
            try {
                val payload = BackupRestoreManager.createPlainBackupPayload(ctx)
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
                }
                ShiroikumaToast.show(ctx, R.string.backup_plain_exported, Toast.LENGTH_LONG)
            } catch (e: Exception) {
                ShiroikumaToast.show(ctx, ctx.getString(R.string.backup_failed_generic, e.message), Toast.LENGTH_LONG)
            }
        }

    private val createBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext()
            val lock = BiometricLock(requireActivity())
            val useAuth = lock.canAuthenticate(ctx)

            if (!useAuth) {
                try {
                    val cipher = CryptoUtils.getCipherForEncryption(userAuthRequired = false)
                    val payload = BackupRestoreManager.createBackupPayload(ctx, cipher)
                    ctx.contentResolver.openOutputStream(uri)?.use { os ->
                        OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
                    }
                    ShiroikumaToast.show(ctx, R.string.backup_exported_success, Toast.LENGTH_SHORT)
                } catch (e: Exception) {
                    ShiroikumaToast.show(ctx, backupErrorMessage("Backup failed", e), Toast.LENGTH_LONG)
                }
                return@registerForActivityResult
            }

            try {
                val cipher = CryptoUtils.getCipherForEncryption(userAuthRequired = true)
                lock.authenticate(onSuccess = { crypto ->
                    try {
                        val payload = BackupRestoreManager.createBackupPayload(ctx, crypto?.cipher ?: cipher)
                        ctx.contentResolver.openOutputStream(uri)?.use { os ->
                            OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
                        }
                        ShiroikumaToast.show(ctx, R.string.backup_exported_success, Toast.LENGTH_SHORT)
                    } catch (e: Exception) {
                        ShiroikumaToast.show(ctx, backupErrorMessage("Backup failed", e), Toast.LENGTH_LONG)
                    }
                }, onError = { errCode ->
                    ShiroikumaToast.show(ctx, ctx.getString(R.string.backup_auth_failed, errCode), Toast.LENGTH_SHORT)
                }, crypto = BiometricPrompt.CryptoObject(cipher))
            } catch (e: Exception) {
                ShiroikumaToast.show(ctx, ctx.getString(R.string.backup_failed_generic, e.message), Toast.LENGTH_LONG)
            }
        }

    private val restoreBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext()
            val lock = BiometricLock(requireActivity())
            val useAuth = lock.canAuthenticate(ctx)

            try {
                val payload =
                    ctx.contentResolver.openInputStream(uri)?.use { `is` ->
                        InputStreamReader(`is`, Charsets.UTF_8).readText()
                    } ?: return@registerForActivityResult

                if (!BackupRestoreManager.isEncrypted(payload)) {
                    try {
                        BackupRestoreManager.restoreFromPlainPayload(ctx, payload)
                        onRestoreSuccess()
                    } catch (e: Exception) {
                        ShiroikumaToast.show(ctx, ctx.getString(R.string.restore_failed_generic, e.message), Toast.LENGTH_LONG)
                    }
                    return@registerForActivityResult
                }

                val iv = BackupRestoreManager.extractIv(payload)

                if (!useAuth) {
                    try {
                        val cipher = CryptoUtils.getCipherForDecryption(iv, userAuthRequired = false)
                        BackupRestoreManager.restoreFromPayload(ctx, payload, cipher)
                        onRestoreSuccess()
                    } catch (e: Exception) {
                        ShiroikumaToast.show(ctx, backupErrorMessage("Restore failed", e), Toast.LENGTH_LONG)
                    }
                    return@registerForActivityResult
                }

                val cipher = CryptoUtils.getCipherForDecryption(iv, userAuthRequired = true)
                lock.authenticate(onSuccess = { crypto ->
                    try {
                        BackupRestoreManager.restoreFromPayload(ctx, payload, crypto?.cipher ?: cipher)
                        onRestoreSuccess()
                    } catch (e: Exception) {
                        ShiroikumaToast.show(ctx, backupErrorMessage("Restore failed", e), Toast.LENGTH_LONG)
                    }
                }, onError = { errCode ->
                    ShiroikumaToast.show(ctx, ctx.getString(R.string.backup_auth_failed, errCode), Toast.LENGTH_SHORT)
                }, crypto = BiometricPrompt.CryptoObject(cipher))
            } catch (e: Exception) {
                ShiroikumaToast.show(ctx, ctx.getString(R.string.restore_failed_generic, e.message), Toast.LENGTH_LONG)
            }
        }

    override fun getTitle(): CharSequence? = getString(R.string.settings_about)

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_about, rootKey)
        val context = requireContext()

        val navDevOptions = findPreference<Preference>("nav_developer_options")
        navDevOptions?.let { setChildAvailable(it, ShizukuSettings.isVectorEnabled()) }

        findPreference<Preference>("version")?.apply {
            summary = BuildConfig.VERSION_NAME
            setOnPreferenceClickListener {
                if (ShizukuSettings.isVectorEnabled()) {
                    ShiroikumaToast.show(context, R.string.settings_developer_options_revealed, Toast.LENGTH_SHORT)
                    return@setOnPreferenceClickListener true
                }

                versionClickCount++
                if (versionClickCount >= 7) {
                    ShizukuSettings.setVectorEnabled(true)
                    SettingsSearchEngine.reset()
                    navDevOptions?.let { setChildAvailable(it, true) }
                    ShiroikumaToast.show(context, R.string.settings_developer_options_revealed, Toast.LENGTH_SHORT)
                    versionClickCount = 0
                } else if (versionClickCount > 2) {
                    ShiroikumaToast.show(context, context.getString(R.string.settings_developer_options_click_more, 7 - versionClickCount), Toast.LENGTH_SHORT)
                }
                true
            }
        }

        setupCheckForUpdatePreference()
        setupAutoUpdatePreference()
        setupAutoInstallPreference()
        setupChannelPreference()
        updateLastCheckSummary()

        // Fork: the bundled changelog, not upstream's GitHub release list — it is generated into
        // assets at build time (see ShiroikumaChangelog), covers every build including unpublished
        // ones, and costs no network request.
        findPreference<Preference>("changelog")?.setOnPreferenceClickListener {
            val activity = activity as? androidx.fragment.app.FragmentActivity ?: return@setOnPreferenceClickListener true
            activity.lifecycleScope.launch {
                val notes =
                    withContext(Dispatchers.IO) {
                        try {
                            ShiroikumaChangelog.full(activity)
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to read bundled changelog")
                            null
                        }
                    }
                if (isAdded && !isDetached) {
                    ChangelogDialogFragment
                        .newInstance(notes, BuildConfig.VERSION_NAME)
                        .show(activity.supportFragmentManager, ChangelogDialogFragment.TAG)
                }
            }
            true
        }

        findPreference<Preference>("source_code")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, "https://github.com/ShiroiKuma0/shiroikuma-shizuku")
            true
        }

        findPreference<Preference>("open_source_licenses")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(requireContext(), "https://github.com/ShiroiKuma0/shiroikuma-shizuku/blob/custom/OPEN_SOURCE_LICENSES.md")
            true
        }

        val backupSettingsPref = findPreference<Preference>("backup_settings")
        backupSettingsPref?.setOnPreferenceClickListener {
            val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.backup_export_title)
                .setItems(
                    arrayOf(
                        getString(R.string.backup_type_encrypted),
                        getString(R.string.backup_type_plain),
                    ),
                ) { _, which ->
                    try {
                        when (which) {
                            0 -> createBackupLauncher.launch("shiroikuma-shizuku_settings_$dateStr.json")
                            1 -> createPlainBackupLauncher.launch("shiroikuma-shizuku_settings_plain_$dateStr.json")
                        }
                    } catch (_: android.content.ActivityNotFoundException) {
                        ShiroikumaToast.show(requireContext(), R.string.backup_no_file_manager_save, Toast.LENGTH_LONG)
                    }
                }.showHouse()
            true
        }

        val restoreSettingsPref = findPreference<Preference>("restore_settings")
        restoreSettingsPref?.setOnPreferenceClickListener {
            try {
                restoreBackupLauncher.launch(arrayOf("application/json", "*/*"))
            } catch (_: android.content.ActivityNotFoundException) {
                ShiroikumaToast.show(requireContext(), R.string.backup_no_file_manager_open, Toast.LENGTH_LONG)
            }
            true
        }

        applyBackupCategoryVisibility()
    }

    override fun onResume() {
        super.onResume()
        updateLastCheckSummary()
        applyBackupCategoryVisibility()
    }

    private fun applyBackupCategoryVisibility() {
        val hide = ShizukuSettings.isHideBackupSettingsEnabled()
        findPreference<af.shizuku.manager.settings.CollapsiblePreferenceCategory>("category_backup")
            ?.isVisible = !hide
    }

    private fun onRestoreSuccess() {
        if (!isAdded) return
        ShiroikumaToast.show(requireContext(), R.string.backup_restored_success, Toast.LENGTH_SHORT)
        Snackbar
            .make(
                requireView(),
                R.string.backup_restored_restart_hint,
                Snackbar.LENGTH_LONG,
            ).setAction(R.string.backup_restored_restart_now) {
                requireActivity().recreate()
            }.show()
    }

    private fun setupAutoUpdatePreference() {
        val pref = findPreference<TwoStatePreference>(KEY_AUTO_UPDATE) ?: return
        pref.isChecked = ShizukuSettings.isAutoUpdateEnabled()
        pref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            ShizukuSettings.setAutoUpdateEnabled(enabled)
            pref.isChecked = enabled
            if (!enabled) {
                findPreference<TwoStatePreference>(KEY_AUTO_INSTALL)?.isChecked = false
                ShizukuSettings.setAutoInstallEnabled(false)
            }
            false
        }
    }

    private fun setupAutoInstallPreference() {
        val pref = findPreference<TwoStatePreference>(KEY_AUTO_INSTALL) ?: return
        pref.isChecked = ShizukuSettings.isAutoInstallEnabled()
        pref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled && !updateManager.canRequestPackageInstalls()) {
                showPermissionRequiredDialog()
                return@setOnPreferenceChangeListener false
            }
            ShizukuSettings.setAutoInstallEnabled(enabled)
            pref.isChecked = enabled
            false
        }
    }

    private fun setupChannelPreference() {
        val pref = findPreference<ListPreference>(KEY_UPDATE_CHANNEL) ?: return
        pref.value = ShizukuSettings.getUpdateChannel()
        pref.setOnPreferenceChangeListener { _, newValue ->
            val channel = newValue as String
            if (channel == "dev") {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.update_channel_dev_warning_title)
                    .setMessage(R.string.update_channel_dev_warning_message)
                    .setPositiveButton(R.string.update_channel_dev) { _, _ ->
                        ShizukuSettings.setUpdateChannel("dev")
                        pref.value = "dev"
                    }.setNegativeButton(android.R.string.cancel, null)
                    .show()
                false
            } else {
                ShizukuSettings.setUpdateChannel("stable")
                true
            }
        }
    }

    private fun setupCheckForUpdatePreference() {
        findPreference<Preference>(KEY_CHECK_FOR_UPDATE)?.setOnPreferenceClickListener {
            checkForUpdate()
            true
        }
    }

    private fun updateLastCheckSummary() {
        val pref = findPreference<Preference>(KEY_CHECK_FOR_UPDATE) ?: return
        val lastCheck = ShizukuSettings.getLastUpdateCheckTime()
        pref.summary =
            when {
                ShizukuSettings.wasLastUpdateCheckFailed() && lastCheck > 0 -> {
                    val date =
                        UpdateChecker.formatPublishedDate(
                            java.text
                                .SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                                .format(java.util.Date(lastCheck)),
                        )
                    "$date · ${getString(R.string.update_last_check_failed)}"
                }
                ShizukuSettings.wasLastUpdateCheckFailed() ->
                    getString(R.string.update_last_check_failed)
                lastCheck > 0 -> {
                    val date =
                        UpdateChecker.formatPublishedDate(
                            java.text
                                .SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                                .format(java.util.Date(lastCheck)),
                        )
                    "${getString(R.string.settings_last_check_title)}: $date"
                }
                else ->
                    getString(R.string.settings_check_for_update_summary)
            }
    }

    private fun checkForUpdate() {
        val context = context ?: return
        val pref = findPreference<Preference>(KEY_CHECK_FOR_UPDATE)
        pref?.isEnabled = false
        pref?.summary = getString(R.string.update_checking)
        val channel = ShizukuSettings.getUpdateChannel()
        lifecycleScope.launch {
            try {
                when (val result = UpdateChecker.checkForUpdate(channel)) {
                    is UpdateChecker.CheckResult.UpdateAvailable -> {
                        if (isAdded) {
                            ShizukuSettings.setLastUpdateCheckTime(System.currentTimeMillis())
                            ShizukuSettings.setLastUpdateCheckFailed(false)
                            pref?.isEnabled = true
                            updateLastCheckSummary()
                            showUpdateAvailableDialog(result.info)
                        }
                    }
                    is UpdateChecker.CheckResult.UpToDate -> {
                        if (isAdded) {
                            ShizukuSettings.setLastUpdateCheckTime(System.currentTimeMillis())
                            ShizukuSettings.setLastUpdateCheckFailed(false)
                            pref?.isEnabled = true
                            updateLastCheckSummary()
                            showUpToDateDialog()
                        }
                    }
                    is UpdateChecker.CheckResult.NetworkError -> {
                        if (isAdded) {
                            ShizukuSettings.setLastUpdateCheckFailed(true)
                            pref?.isEnabled = true
                            updateLastCheckSummary()
                            showErrorDialog()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Unexpected error checking for update")
                if (isAdded) {
                    ShizukuSettings.setLastUpdateCheckFailed(true)
                    pref?.isEnabled = true
                    updateLastCheckSummary()
                    showErrorDialog()
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(info: UpdateChecker.UpdateInfo) {
        val context = context ?: return
        val devBadge = if (info.isPrerelease) " ⚠ Dev" else ""
        val builder =
            MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.update_available_title) + devBadge)
                .setNegativeButton(R.string.update_later, null)
                .setNeutralButton(R.string.update_release_notes) { _, _ -> openReleasesPage() }

        if (info.requiresManualDownload) {
            builder
                .setMessage(getString(R.string.update_available_manual_message, info.versionName))
                .setPositiveButton(R.string.update_view_on_github) { _, _ -> openReleasesPage() }
        } else {
            builder
                .setMessage(getString(R.string.update_available_message, info.versionName))
                .setPositiveButton(R.string.update_download) { _, _ ->
                    updateManager.downloadUpdate(info.downloadUrl, info.versionName, manual = true)
                }
        }

        builder.show()
    }

    private fun showUpToDateDialog() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_up_to_date_title)
            .setMessage(getString(R.string.update_up_to_date_message, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showErrorDialog() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_error_title)
            .setMessage(R.string.update_error_message)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.update_view_on_github) { _, _ -> openReleasesPage() }
            .show()
    }

    private fun showPermissionRequiredDialog() {
        val context = context ?: return
        try {
            val intent =
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
            startActivity(intent)
        } catch (_: Exception) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.update_permission_required_title)
                .setMessage(R.string.update_permission_required_message)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun openReleasesPage() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: android.content.ActivityNotFoundException) {
            Timber.w(e, "No activity found to handle releases URL")
        }
    }

    private fun setChildAvailable(
        pref: Preference,
        available: Boolean,
    ) {
        val key = pref.key ?: return
        (pref.parent as? CollapsiblePreferenceCategory)?.setChildAvailable(key, available)
            ?: run { pref.isVisible = available }
    }
}
