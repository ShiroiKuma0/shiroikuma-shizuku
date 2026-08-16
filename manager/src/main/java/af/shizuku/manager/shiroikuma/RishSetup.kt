package af.shizuku.manager.shiroikuma

import android.content.Context
import android.content.pm.PackageManager
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.IntentCrypto

/**
 * One-paste setup for `rish` in a terminal app, and the state behind the home screen's rish card.
 *
 * ## Why this exists
 *
 * `rish` asks for consent on every command unless the request carries a valid auth token — see
 * [af.shizuku.manager.receiver.BinderRequestReceiver], which decrypts the `auth` extra and delivers
 * the binder with no prompt when it matches. The token has always been supported; the only thing
 * missing was a way to get it into the terminal without hand-editing files.
 *
 * ## Two failure modes this deliberately designs out
 *
 * **The dex going stale.** `rish` loads its loader from `$(dirname "$0")/rish_shizuku.dex` — a copy
 * the user placed beside the script, *not* the copy inside the installed APK. So updating the app
 * never updated the loader, and a manager expecting a newer loader silently fell back to prompting
 * with nothing anywhere explaining why. The script written here **re-extracts that dex from the
 * installed APK** whenever the APK path changes, so it tracks the app automatically.
 *
 * It has to be extracted rather than used in place: `rikka.shizuku.shell.ShizukuShellLoader` is
 * built by the separate `:shell` application module and only ever ships inside
 * `assets/rish_shizuku.dex` — it is **not** in the APK's own `classes.dex`, so pointing
 * `-Djava.class.path` straight at the APK loads a dex without the loader in it and the process dies
 * silently with exit 0 (measured on-device). The APK path carries a random segment that changes on
 * every install, which is what makes it a reliable staleness stamp. `/system/bin/unzip` is toybox's
 * `ziptool`, present on stock Android; `pm path` resolves because this app is visible to the
 * terminal. If either is unavailable an already-extracted dex keeps working.
 *
 * **A green light that isn't true.** [isWorking] does not check that files exist — it cannot, since
 * the terminal's private directory is unreadable without root. It reports only that a token-
 * authenticated request has actually arrived, recorded by
 * [af.shizuku.manager.shell.ShellBinderRequestHandler]. That is the same event as "ran without a
 * prompt", so the card cannot claim success for a setup that does not work. The recorded token is
 * fingerprinted too, so regenerating the auth token turns the card back to "needs setup" rather
 * than leaving a green light over a script that no longer authenticates.
 */
object RishSetup {

    /**
     * Terminal apps that use Termux's `$PREFIX` layout. Ordered by preference; the first installed
     * one is what the generated command targets.
     */
    private val TERMINAL_PACKAGES = listOf(
        "com.termux",
        "com.termux.fdroid",
    )

    /** @return the installed terminal's package name, or null when none is present. */
    fun installedTerminal(context: Context): String? = TERMINAL_PACKAGES.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /** True once a shell client has actually connected with a valid, still-current auth token. */
    fun isWorking(): Boolean {
        val recorded = ShizukuSettings.getRishTokenAuth() ?: return false
        val fingerprint = recorded.substringBefore(':', "")
        return fingerprint.isNotEmpty() && fingerprint == currentTokenFingerprint()
    }

    /** Epoch millis of the last prompt-less shell connection, or 0. */
    fun lastConnectedAt(): Long =
        ShizukuSettings.getRishTokenAuth()?.substringAfter(':', "")?.toLongOrNull() ?: 0L

    /** Called from the token path only; [token] is the value that just authenticated. */
    fun recordTokenAuth(token: String) {
        ShizukuSettings.setRishTokenAuth("${token.hashCode()}:${System.currentTimeMillis()}")
    }

    private fun currentTokenFingerprint(): String =
        try { ShizukuSettings.getAuthToken().hashCode().toString() } catch (_: Exception) { "" }

    /**
     * The single command the user pastes into the terminal. Overwrites whatever `rish` currently
     * resolves to, with the token baked in and the loader extracted from the installed APK.
     *
     * **It targets `command -v rish`, not a fixed path, and that is load-bearing.** Writing to
     * `$PREFIX/bin/rish` looks equivalent and is not: an older `rish` earlier on `PATH` keeps
     * winning, so the setup reports success while the script that actually runs is untouched —
     * measured on-device, after the command had been pasted correctly on every install for an
     * afternoon. The fixed path remains the fallback for a first-time setup where no `rish` exists.
     *
     * It also **echoes the path it wrote**. Without that there is nothing to check: "rish is set up"
     * is indistinguishable from "rish is set up somewhere that will never run".
     *
     * @return null when the token cannot be encrypted, which is the one case where handing over a
     *   command would produce a script that silently never authenticates.
     */
    fun buildSetupCommand(context: Context, terminalPackage: String): String? {
        val encryptedToken = IntentCrypto.encrypt(ShizukuSettings.getAuthToken()) ?: return null
        // Base64 NO_WRAP — [A-Za-z0-9+/=] only, so it is safe unquoted-by-the-heredoc and inside
        // the double quotes of the generated export line.
        val prefix = "/data/data/$terminalPackage/files/usr"
        // Derived, never spelled: applicationId and namespace differ in this fork, so a literal
        // here would be wrong the moment either moves.
        val appId = context.packageName

        val d = '$'
        return """
            TARGET=$d(command -v rish 2>/dev/null)
            [ -n "${d}TARGET" ] || TARGET="$d{PREFIX:-$prefix}/bin/rish"
            mkdir -p "$d(dirname "${d}TARGET")" && cat > "${d}TARGET" <<'RISH_EOF'
            #!/system/bin/sh
            export SHIZUKU_TOKEN="$encryptedToken"
            export RISH_APPLICATION_ID="$terminalPackage"
            DIR=$d(dirname "${d}0")
            DEX="${d}DIR/rish_shizuku.dex"
            STAMP="${d}DIR/.rish_apk"
            APK=$d(/system/bin/pm path $appId 2>/dev/null | sed -n 's/^package://p' | head -1)
            if [ -n "${d}APK" ] && { [ ! -f "${d}DEX" ] || [ "$d(cat "${d}STAMP" 2>/dev/null)" != "${d}APK" ]; }; then
              if /system/bin/unzip -o -p "${d}APK" assets/rish_shizuku.dex > "${d}DEX.new" 2>/dev/null && [ -s "${d}DEX.new" ]; then
                rm -f "${d}DEX" && mv "${d}DEX.new" "${d}DEX" && chmod 400 "${d}DEX" && printf '%s' "${d}APK" > "${d}STAMP"
              else
                rm -f "${d}DEX.new"
              fi
            fi
            [ -f "${d}DEX" ] || { echo "rish: cannot read the loader from the app - is it installed?" >&2; exit 1; }
            exec /system/bin/app_process -Djava.class.path="${d}DEX" /system/bin --nice-name=rish rikka.shizuku.shell.ShizukuShellLoader "$d@"
            RISH_EOF
            chmod 700 "${d}TARGET" && echo "rish set up at: ${d}TARGET" && echo "now run: rish -c id"
        """.trimIndent()
    }
}
