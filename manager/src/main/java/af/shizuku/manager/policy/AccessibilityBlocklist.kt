package af.shizuku.manager.policy

import android.content.Context
import android.os.UserManager
import android.view.accessibility.AccessibilityManager
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.admin.DeviceOwnerHelper
import timber.log.Timber

/**
 * Blocking an accessibility service, expressed as a blocklist even though the platform only offers
 * an allowlist.
 *
 * `setPermittedAccessibilityServices` takes the set of packages that *may* run an accessibility
 * service, and its default — `null` — means "everything is permitted". There is no "block one"
 * form. Used naively it has two failure modes, and the second is the dangerous one because it
 * survives being correct on the day it is set:
 *
 * 1. A wrong enumeration bars **every** service on the device at once.
 * 2. Once a non-`null` list exists, any accessibility service installed *later* is not on it and is
 *    therefore barred — silently, with nothing in any UI explaining why it will not stay enabled.
 *
 * So the allowlist is never authored by hand and never exposed. The durable state is the blocklist;
 * the platform list is derived from it as `(every installed service package) − blocklist` and
 * recomputed whenever either side can have changed — a blocklist edit, or a package being added,
 * replaced or removed (see [PolicyPackageChangeReceiver]). A newly installed service then joins the
 * permitted list by itself and behaves normally.
 *
 * When the blocklist empties the platform list goes back to `null` rather than to a hand-built
 * "everything": `null` is the platform's own no-restriction state, and an enumerated "everything"
 * is one package install away from being wrong.
 *
 * System-bundled accessibility services are exempt from the restriction, so this reaches
 * third-party services — which is both the category worth blocking and the category 白い熊 installs.
 */
object AccessibilityBlocklist {

    /** A copy — the `Set` handed back by SharedPreferences must not be mutated in place. */
    fun blocked(): Set<String> =
        ShizukuSettings.getPreferences()
            ?.getStringSet(ShizukuSettings.Keys.KEY_POLICY_ACCESSIBILITY_BLOCKLIST, null)
            ?.toSortedSet()
            ?: emptySet()

    fun isBlocked(pkg: String): Boolean = pkg in blocked()

    /**
     * Add or remove [pkg] and push the derived allowlist to the platform.
     *
     * The blocklist is only persisted once the platform accepted the new list, so a failed write
     * leaves our state and the platform's agreeing rather than drifting apart.
     */
    fun set(context: Context, pkg: String, block: Boolean): PolicyResult {
        val next = if (block) blocked() + pkg else blocked() - pkg
        val applied = apply(context, next)
        if (applied.ok) store(next)
        return applied
    }

    /** Re-derive and re-push without changing the blocklist. Used after any package event. */
    fun recompute(context: Context): PolicyResult = apply(context, blocked())

    /**
     * Recompute only if the platform's list has actually drifted from what the blocklist implies.
     *
     * The package broadcasts are the intended trigger, but a manifest receiver is not a guarantee
     * on modern Android, and a service that silently refuses to stay enabled is precisely the
     * failure this design exists to avoid. So the cheap comparison also runs from the places that
     * are certain to happen: the `status` call 応用管理 polls, and opening the section in Settings.
     *
     * Returns null when nothing needed doing, so callers can stay quiet in the common case.
     */
    fun recomputeIfStale(context: Context): PolicyResult? {
        if (!DeviceOwnerHelper.isDeviceOwner(context)) return null
        val dpm = DeviceOwnerHelper.dpm(context) ?: return null
        val admin = DeviceOwnerHelper.component(context)
        val blocklist = blocked()

        val current = try {
            dpm.getPermittedAccessibilityServices(admin)
        } catch (e: Throwable) {
            Timber.w(e, "reading the permitted-accessibility list failed")
            return null
        }

        if (blocklist.isEmpty()) {
            // Anything other than null is a restriction we no longer intend to impose.
            return if (current == null) null else clearAll(context)
        }

        val installed = try {
            installedServicePackages(context)
        } catch (e: Throwable) {
            return null  // never write a list derived from a partial enumeration
        }
        val wanted = installed - blocklist
        return if (current != null && current.toSet() == wanted) null else recompute(context)
    }

    /** Drop everything and hand the platform back its unrestricted `null`. */
    fun clearAll(context: Context): PolicyResult {
        val applied = apply(context, emptySet())
        if (applied.ok) store(emptySet())
        return applied
    }

    private fun store(value: Set<String>) {
        ShizukuSettings.getPreferences()
            ?.edit()
            ?.putStringSet(ShizukuSettings.Keys.KEY_POLICY_ACCESSIBILITY_BLOCKLIST, value.toSet())
            ?.apply()
    }

    private fun apply(context: Context, blocklist: Set<String>): PolicyResult {
        val dpm = DeviceOwnerHelper.dpm(context)
            ?: return PolicyResult.fail("DevicePolicyManager unavailable")
        if (!DeviceOwnerHelper.isDeviceOwner(context)) return PolicyResult.fail("not-device-owner")
        val admin = DeviceOwnerHelper.component(context)

        // Empty blocklist -> hand back the platform's own no-restriction state, not "everything".
        if (blocklist.isEmpty()) {
            return try {
                dpm.setPermittedAccessibilityServices(admin, null)
                PolicyResult.OK
            } catch (e: Throwable) {
                Timber.e(e, "clearing the permitted-accessibility list failed")
                PolicyResult.fail(e)
            }
        }

        // A partial enumeration is exactly the failure that bars every service on the device, so a
        // throw here changes nothing at all.
        val installed = try {
            installedServicePackages(context)
        } catch (e: Throwable) {
            Timber.e(e, "enumerating accessibility services failed")
            return PolicyResult.fail("could not enumerate accessibility services: ${reason(e)}")
        }
        if (installed.isEmpty()) {
            return PolicyResult.fail("no accessibility services enumerated; refusing to write a list")
        }

        val permitted = (installed - blocklist).toList()
        return try {
            dpm.setPermittedAccessibilityServices(admin, permitted)
            PolicyResult.OK
        } catch (e: Throwable) {
            Timber.e(e, "writing the permitted-accessibility list failed")
            PolicyResult.fail(e)
        }
    }

    /**
     * The API takes **package names**, unlike every other accessibility API in the platform, which
     * takes `ComponentName`s. Mapping each `AccessibilityServiceInfo` down to its package is
     * therefore load-bearing, not a convenience.
     */
    fun installedServicePackages(context: Context): Set<String> {
        // Before the user unlocks, PackageManager only reports direct-boot-aware components, so the
        // enumeration is short by construction — and a short enumeration is exactly what bars every
        // service on the device. The provider and the package receiver are both directBootAware, so
        // this really is reachable; refusing here is what keeps them safe.
        val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (um?.isUserUnlocked == false) {
            throw IllegalStateException("user is locked; the service list would be incomplete")
        }
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: throw IllegalStateException("AccessibilityManager unavailable")
        return am.installedAccessibilityServiceList
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
            .toSet()
    }

    private fun reason(e: Throwable) = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
}
