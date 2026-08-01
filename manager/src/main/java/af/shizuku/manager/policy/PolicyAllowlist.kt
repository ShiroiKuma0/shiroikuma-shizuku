package af.shizuku.manager.policy

import af.shizuku.manager.ShizukuSettings

/**
 * Which packages 白い熊 has authorized to drive [DevicePolicyApi].
 *
 * Deliberately **not** `AuthorizationManager`:
 *
 * - `AuthorizationManager.granted()` opens with `if (!Shizuku.pingBinder()) return false`, so it is
 *   unusable as a gate here. The whole point of the policy API is that it works with the 白い熊 雫
 *   service down — `DevicePolicyManager` runs in this app's own process as the owner and needs no
 *   shell.
 * - Device-policy powers are a different consent from shell access. Handing an app the ability to
 *   suspend packages device-wide because it was once allowed to run `pm list packages` would be a
 *   silent escalation; 白い熊 grants this one deliberately, per app.
 *
 * Stored in [ShizukuSettings]' device-protected preferences, so it survives reboots and updates.
 */
object PolicyAllowlist {

    /** A copy — [android.content.SharedPreferences.getStringSet] must never be mutated in place. */
    fun packages(): Set<String> =
        ShizukuSettings.getPreferences()
            ?.getStringSet(ShizukuSettings.Keys.KEY_POLICY_ALLOWED_PACKAGES, null)
            ?.toSortedSet()
            ?: emptySet()

    fun allows(pkg: String?): Boolean = pkg != null && pkg in packages()

    fun add(pkg: String) = write(packages() + pkg)

    fun remove(pkg: String) = write(packages() - pkg)

    fun set(pkg: String, allowed: Boolean) = if (allowed) add(pkg) else remove(pkg)

    private fun write(value: Set<String>) {
        ShizukuSettings.getPreferences()
            ?.edit()
            ?.putStringSet(ShizukuSettings.Keys.KEY_POLICY_ALLOWED_PACKAGES, value.toSet())
            ?.apply()
    }

    /** The sister app this exists for. Only a *default* — the mechanism takes any package name. */
    const val OYOKANRI = "shiroikuma.oyokanri"
}
