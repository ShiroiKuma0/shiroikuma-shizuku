package af.shizuku.manager.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.shiroikuma.ShiroikumaToast

class AboutSettingsFragment : BaseSettingsFragment() {

    private var versionClickCount = 0

    override fun getTitle(): CharSequence? = getString(R.string.settings_about)

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
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

        findPreference<Preference>("source_code")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, "https://github.com/ShiroiKuma0/shiroikuma-shizuku")
            true
        }

        findPreference<Preference>("open_source_licenses")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(requireContext(), "https://github.com/ShiroiKuma0/shiroikuma-shizuku/blob/custom/OPEN_SOURCE_LICENSES.md")
            true
        }
    }

    private fun setChildAvailable(pref: Preference, available: Boolean) {
        val key = pref.key ?: return
        (pref.parent as? CollapsiblePreferenceCategory)?.setChildAvailable(key, available)
            ?: run { pref.isVisible = available }
    }
}
