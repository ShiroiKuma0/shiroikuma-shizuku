package af.shizuku.manager.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import af.shizuku.manager.R
import af.shizuku.manager.settings.compose.SettingsScreen
import af.shizuku.core.ui.AppActivity

class SettingsActivity : AppActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private var currentTitle by mutableStateOf("")
    private var searchResults by mutableStateOf<List<SettingsSearchEngine.SettingItem>>(emptyList())
    var themeVersion by mutableStateOf(0)

    fun onThemeChanged() {
        themeVersion++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        SettingsSearchEngine.init(this)

        currentTitle = getString(R.string.settings_title)

        setContent {
            val tv = themeVersion
            af.shizuku.core.ui.compose.AppTheme(
                isBlackNightTheme = af.shizuku.manager.app.ThemeHelper.isBlackNightTheme(this),
                isOneUi = af.shizuku.manager.ShizukuSettings.isOneUiThemeEnabled(),
                themeVersion = tv
            ) {
                SettingsScreen(
                    title = currentTitle,
                    onNavigateUp = {
                        if (!onSupportNavigateUp()) {
                            finish()
                        }
                    },
                    onNavigateToSetting = { item -> navigateToSetting(item) },
                    searchResults = searchResults,
                    onSearchQueryChanged = { query ->
                        if (query.isBlank()) {
                            searchResults = emptyList()
                        } else {
                            searchResults = SettingsSearchEngine.search(this, query)
                        }
                    },
                    onContainerCreated = {
                        if (savedInstanceState == null && supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
                            // Fork: long-pressing the home settings cog asks for the 白い熊 雫 UI page
                            // directly, so open it instead of the settings root. The root is pushed
                            // underneath first, so Back still lands on Settings rather than exiting.
                            val openHouseUi = intent?.getBooleanExtra(
                                af.shizuku.manager.shiroikuma.ShiroikumaUiFragment.EXTRA_OPEN_SHIROIKUMA_UI,
                                false
                            ) == true
                            supportFragmentManager.beginTransaction()
                                .replace(R.id.fragment_container, SettingsFragment())
                                .commit()
                            if (openHouseUi) {
                                supportFragmentManager.beginTransaction()
                                    .setReorderingAllowed(true)
                                    .replace(
                                        R.id.fragment_container,
                                        af.shizuku.manager.shiroikuma.ShiroikumaUiFragment()
                                    )
                                    .addToBackStack(null)
                                    .commit()
                                currentTitle = "白い熊 雫 UI"
                            }
                            // Fork: a deep link straight to one row, flashed on arrival — the home
                            // boot-setup card points at "Start on boot" this way. Same mechanism the
                            // search results use; the root goes underneath first so Back lands on
                            // Settings, and the fragment sets its own title in onResume.
                            intent?.getStringExtra(EXTRA_OPEN_FRAGMENT)?.let { fragmentClass ->
                                openFragment(fragmentClass, intent?.getStringExtra(EXTRA_HIGHLIGHT_KEY))
                            }
                        }
                    }
                )
            }
        }
    }

    /** Pushes a settings sub-page, optionally asking it to scroll to and flash one row. */
    private fun openFragment(fragmentClass: String, highlightKey: String?) {
        val fragment = runCatching {
            supportFragmentManager.fragmentFactory.instantiate(classLoader, fragmentClass)
        }.getOrElse {
            timber.log.Timber.w(it, "Unknown settings fragment: $fragmentClass")
            return
        }
        if (highlightKey != null) {
            fragment.arguments = Bundle().apply { putString("highlight_key", highlightKey) }
        }
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun navigateToSetting(item: SettingsSearchEngine.SettingItem) {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, item.fragmentClass)
        fragment.arguments = Bundle().apply {
            putString("highlight_key", item.key)
        }

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()

        currentTitle = item.title
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragmentName = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, fragmentName)
        fragment.arguments = pref.extras

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()

        currentTitle = pref.title?.toString() ?: currentTitle
        return true
    }

    fun updateTitle(title: String) {
        currentTitle = title
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        return super.onSupportNavigateUp()
    }

    companion object {
        /** Fully-qualified name of a settings fragment to push on top of the settings root. */
        const val EXTRA_OPEN_FRAGMENT = "af.shizuku.manager.extra.OPEN_FRAGMENT"

        /** Preference key to scroll to and flash once the fragment is up. */
        const val EXTRA_HIGHLIGHT_KEY = "af.shizuku.manager.extra.HIGHLIGHT_KEY"

        /** "Startup & Behavior", with one row flashed — used by the home boot-setup card. */
        fun behaviorSettingsIntent(context: android.content.Context, highlightKey: String) =
            android.content.Intent(context, SettingsActivity::class.java)
                .putExtra(EXTRA_OPEN_FRAGMENT, BehaviorSettingsFragment::class.java.name)
                .putExtra(EXTRA_HIGHLIGHT_KEY, highlightKey)
    }
}
