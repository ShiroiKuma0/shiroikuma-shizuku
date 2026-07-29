package af.shizuku.manager.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.KEY_COMPANION_FALLBACK
import af.shizuku.manager.service.AdbProxyService
import af.shizuku.manager.utils.StockShizukuCompat
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import af.shizuku.manager.shiroikuma.showHouse
import af.shizuku.manager.shiroikuma.ShiroikumaToast

/**
 * Root Integration Settings
 *
 * Provides SU Bridge and ADB proxy features to integrate root access
 * with apps that don't natively support Shizuku.
 */
class RootIntegrationSettingsFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = "Root & Compatibility"

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_root_integration, rootKey)
        val context = requireContext()

        // Sync current su_bridge state to server on fragment open
        ShizukuSettings.syncAllPlusFeaturesToServer()

        val bootloaderUnlocked = isBootloaderUnlocked()
        val bootloaderCategory = findPreference<androidx.preference.PreferenceGroup>("category_unlocked_bootloader")
        if (bootloaderCategory != null) {
            bootloaderCategory.isVisible = bootloaderUnlocked
            if (!bootloaderUnlocked) {
                // Optionally remove it completely
                preferenceScreen.removePreference(bootloaderCategory)
            }
        }

        findPreference<TwoStatePreference>(KEY_COMPANION_FALLBACK)?.apply {
            isChecked = ShizukuSettings.isCompanionFallbackEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) ShizukuSettings.setCompanionFallbackEnabled(newValue)
                true
            }
        }
        findPreference<Preference>("launch_stock_shizuku")?.apply {
            isVisible = StockShizukuCompat.isInstalled(requireContext())
            setOnPreferenceClickListener {
                StockShizukuCompat.launch(it.context)
                true
            }
        }

        findPreference<TwoStatePreference>("adb_proxy_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                val intent = Intent(context, AdbProxyService::class.java)
                if (newValue) context.startService(intent) else context.stopService(intent)
            }
            true
        }

        findPreference<TwoStatePreference>("on_device_adb_tcp")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                preferenceManager.sharedPreferences?.edit()?.putBoolean("on_device_adb_tcp", newValue)?.apply()
                lifecycleScope.launch(Dispatchers.IO) {
                    if (newValue) {
                        af.shizuku.manager.service.AdbProxyService.enableAdbTcp()
                    } else {
                        af.shizuku.manager.service.AdbProxyService.disableAdbTcp()
                    }
                }
            }
            true
        }

        findPreference<TwoStatePreference>("force_start_wadb")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                preferenceManager.sharedPreferences?.edit()?.putBoolean("force_start_wadb", newValue)?.apply()
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            true
        }

        findPreference<TwoStatePreference>("su_bridge_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                preferenceManager.sharedPreferences?.edit()?.putBoolean("su_bridge_enabled", newValue)?.apply()
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            true
        }

        val rootModules = listOf(
            "root_build_prop_redirect_enabled",
            "root_iptables_mocking_enabled",
            "root_magisk_mocking_enabled",
            "root_auto_grant_enabled",
            "root_file_interceptor_enabled",
            "root_busybox_mocking_enabled",
            "overlay_fs_proxy_enabled",
            "root_kernel_ghosting_enabled",
            "root_partition_ghosting_enabled",
            "root_power_ghosting_enabled",
            "bootloader_flash_ota_enabled",
            "bootloader_fastbootd_reboot_enabled"
        )
        rootModules.forEach { key ->
            val pref = findPreference<TwoStatePreference>(key)
            pref?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    if (newValue && key == "bootloader_flash_ota_enabled") {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.ota_flash_danger_title)
                            .setMessage(R.string.ota_flash_danger_message)
                            .setPositiveButton(R.string.ota_flash_danger_confirm) { _, _ ->
                                preferenceManager.sharedPreferences?.edit()?.putBoolean(key, true)?.apply()
                                pref.isChecked = true
                                ShizukuSettings.syncAllPlusFeaturesToServer()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .showHouse()
                        return@setOnPreferenceChangeListener false
                    } else {
                        preferenceManager.sharedPreferences?.edit()?.putBoolean(key, newValue)?.apply()
                        ShizukuSettings.syncAllPlusFeaturesToServer()
                        return@setOnPreferenceChangeListener true
                    }
                }
                true
            }
        }

        findPreference<Preference>("root_compatibility_hub")?.setOnPreferenceClickListener {
            startActivity(Intent(context, RootCompatibilityActivity::class.java))
            true
        }

        // Preset SU Path Picker helper
        val suPathPref = findPreference<androidx.preference.EditTextPreference>("custom_su_path")
        suPathPref?.setOnPreferenceChangeListener { _, _ ->
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }
        suPathPref?.setOnPreferenceClickListener {
            val presets = arrayOf(
                "Default (Auto-detect)",
                "/system/bin/su (Standard AOSP)",
                "/system/xbin/su (SuperSU Legacy)",
                "/sbin/su (Magisk/Custom ROMs)",
                "/data/adb/ksu/bin/su (KernelSU)",
                "/data/adb/ap/bin/su (APatch)",
                "Custom Path..."
            )
            val presetValues = arrayOf(
                "",
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/ap/bin/su",
                "custom"
            )

            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(R.string.su_path_preset_dialog_title)
                .setItems(presets) { _, which ->
                    val chosen = presetValues[which]
                    if (chosen == "custom") {
                        // Let the default EditTextDialog handle custom text entry
                        (this@RootIntegrationSettingsFragment).onDisplayPreferenceDialog(suPathPref)
                    } else {
                        // Setting .text programmatically does NOT fire the change listener,
                        // so push the new path to the server explicitly.
                        suPathPref.text = chosen
                        ShizukuSettings.syncAllPlusFeaturesToServer()
                        ShiroikumaToast.show(context, getString(R.string.su_path_preset_applied, presets[which]), Toast.LENGTH_SHORT)
                    }
                }
                .showHouse()
            true // Intercept click to show presets dialog first
        }

        applyModeConstraints()
    }

    // Gray out categories that require root when running in ADB/shell mode (uid 2000).
    // Only applied when the server is actually running — if uid == -1 (not attached), leave
    // everything enabled so the user can still configure settings before starting Shizuku.
    private fun applyModeConstraints() {
        val uid = try { Shizuku.getUid() } catch (_: Exception) { -1 }
        if (uid != 2000) return // root or not running — no restrictions to apply

        val rootOnlyCategories = listOf(
            "category_su_bridge",
            "category_root_modules",
            "category_ghost_bridge",
            "category_unlocked_bootloader"
        )
        for (key in rootOnlyCategories) {
            findPreference<PreferenceGroup>(key)?.apply {
                isEnabled = false
                // Append the mode hint to the category title so users understand why it's grayed.
                title = "$title (root mode only)"
            }
        }
    }

    private fun isBootloaderUnlocked(): Boolean {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", "ro.boot.flash.locked"))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val locked = reader.readLine()
            if (locked == "0") return true

            val process2 = Runtime.getRuntime().exec(arrayOf("getprop", "ro.boot.verifiedbootstate"))
            val reader2 = java.io.BufferedReader(java.io.InputStreamReader(process2.inputStream))
            val state = reader2.readLine()
            if (state == "orange") return true
        } catch (e: Exception) {
            // Ignore
        }
        return false
    }
}
