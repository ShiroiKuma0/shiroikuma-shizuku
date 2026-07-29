package af.shizuku.core.ui.compose

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography

/**
 * FORK: the hook that lets the 白い熊 雫 UI page drive every Compose screen.
 *
 * [AppTheme] normally derives its scheme from the hosting Activity's resolved theme attributes,
 * which are static resources — they cannot carry a colour the user just moved a slider to. This
 * holder lets the app module install a live provider instead, without `core:ui` having to depend on
 * `manager` (the dependency runs the other way).
 *
 * `ShizukuApplication.onCreate` installs it; when nothing is installed, [AppTheme] behaves exactly
 * as upstream's.
 */
object AppThemeOverride {

    /** Returns the live scheme for (context, darkTheme), or null to fall back to upstream's. */
    @Volatile
    @JvmStatic
    var colorSchemeProvider: ((Context, Boolean) -> ColorScheme?)? = null

    /** Returns the live typography, or null to keep Material's default. */
    @Volatile
    @JvmStatic
    var typographyProvider: ((Context) -> Typography?)? = null

    /**
     * Bumped whenever a knob changes, so Compose recomposes instead of holding a `remember`ed
     * scheme from before the edit.
     */
    @Volatile
    @JvmStatic
    var revision: Int = 0
        private set

    @JvmStatic
    fun invalidate() {
        revision++
    }
}
