package af.shizuku.manager.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import af.shizuku.manager.admin.DeviceOwnerHelper
import timber.log.Timber

/**
 * The Device-Owner-only powers, executed in **this** app's process on an authorized sister app's
 * behalf.
 *
 * These are the half of the hand-off that delegation provably cannot carry: no `DELEGATION_*` scope
 * exists for `setUserControlDisabledPackages`, `setPermittedAccessibilityServices`,
 * `addUserRestriction`, `setAlwaysOnVpnPackage` or `setCameraDisabled`. Everything that *can* be
 * delegated is delegated instead — see [DeviceOwnerHelper.SNOOPING_SCOPES] — because a delegated
 * call needs no IPC, no running 白い熊 雫 service, and survives this app being stopped.
 *
 * None of this needs the Shizuku service or a shell: `DevicePolicyManager` answers us directly
 * because we are the owner.
 *
 * ⛔ Every power here is designed so the user cannot reverse it from Settings. That is what makes a
 * lock a lock, and it is also why [clearAllLocks] exists and why the UI tags these rows red.
 */
object DevicePolicyApi {

    /**
     * User restrictions that would remove the very routes you need to fix a mistake. **Refused
     * outright**, not warned about — a warning is not a defence when the failure mode is "the phone
     * can no longer be reached to undo it".
     *
     * - `DISALLOW_DEBUGGING_FEATURES` kills ADB, which is both how 応用管理 obtains its privileges
     *   and how you would recover from anything else in this file.
     * - `DISALLOW_SAFE_BOOT` removes the offline recovery route.
     * - `DISALLOW_FACTORY_RESET` removes the last resort — the only clean exit from Device Owner.
     * - The unknown-sources pair blocks sideloading, i.e. installing a *fixed* build of 雫 or 応用管理.
     *
     * Before deleting anything from this set, work out how the phone gets rescued without it.
     */
    val REFUSED_RESTRICTIONS: Set<String> = setOf(
        UserManager.DISALLOW_DEBUGGING_FEATURES,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
    )

    private fun admin(context: Context): ComponentName = DeviceOwnerHelper.component(context)

    private fun dpmOrNull(context: Context): DevicePolicyManager? = DeviceOwnerHelper.dpm(context)

    // -----------------------------------------------------------------------------------------
    // Accessibility
    // -----------------------------------------------------------------------------------------

    /** See [AccessibilityBlocklist] for why the durable state is a blocklist, not the allowlist. */
    fun setAccessibilityBlocked(context: Context, pkg: String, blocked: Boolean): PolicyResult =
        AccessibilityBlocklist.set(context, pkg, blocked)

    // -----------------------------------------------------------------------------------------
    // User control (force-stop / clear-data)
    // -----------------------------------------------------------------------------------------

    /**
     * Add or remove [pkg] from `setUserControlDisabledPackages`.
     *
     * **Read-modify-write, always.** The API takes the whole list, so writing just this package
     * would wipe every other entry — silently, because the call reports nothing.
     */
    fun setUserControlDisabled(context: Context, pkg: String, enabled: Boolean): PolicyResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return PolicyResult.fail("setUserControlDisabledPackages requires Android 11")
        }
        val dpm = dpmOrNull(context) ?: return PolicyResult.fail("DevicePolicyManager unavailable")
        return try {
            val current = dpm.getUserControlDisabledPackages(admin(context)).toMutableSet()
            if (enabled) current.add(pkg) else current.remove(pkg)
            dpm.setUserControlDisabledPackages(admin(context), current.toList())
            PolicyResult.OK
        } catch (e: Throwable) {
            Timber.e(e, "setUserControlDisabledPackages for %s failed", pkg)
            PolicyResult.fail(e)
        }
    }

    fun userControlDisabledPackages(context: Context): List<String> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpmOrNull(context)?.getUserControlDisabledPackages(admin(context)).orEmpty()
        } else {
            emptyList()
        }
    } catch (e: Throwable) {
        emptyList()
    }

    // -----------------------------------------------------------------------------------------
    // Device-wide restrictions
    // -----------------------------------------------------------------------------------------

    /** A user restriction is **device-wide**, never per-app. The key is passed through verbatim. */
    fun setUserRestriction(context: Context, key: String, enabled: Boolean): PolicyResult {
        if (enabled && key in REFUSED_RESTRICTIONS) {
            return PolicyResult.fail("refused-dangerous-key")
        }
        val dpm = dpmOrNull(context) ?: return PolicyResult.fail("DevicePolicyManager unavailable")
        return try {
            if (enabled) dpm.addUserRestriction(admin(context), key)
            else dpm.clearUserRestriction(admin(context), key)
            PolicyResult.OK
        } catch (e: Throwable) {
            Timber.e(e, "user restriction %s -> %s failed", key, enabled)
            PolicyResult.fail(e)
        }
    }

    /**
     * Set (or, with a null package, clear) the always-on VPN.
     *
     * With `lockdown` on, **no traffic leaves the phone unless that VPN app is connected** —
     * including the Wi-Fi ADB you might use to undo it. The UI gates `lockdown = true` behind its
     * own confirmation, separately from choosing the package.
     */
    fun setAlwaysOnVpn(context: Context, pkg: String?, lockdown: Boolean): PolicyResult {
        val dpm = dpmOrNull(context) ?: return PolicyResult.fail("DevicePolicyManager unavailable")
        return try {
            dpm.setAlwaysOnVpnPackage(admin(context), pkg, lockdown)
            PolicyResult.OK
        } catch (e: PackageManager.NameNotFoundException) {
            PolicyResult.fail("no such VPN package: $pkg")
        } catch (e: UnsupportedOperationException) {
            PolicyResult.fail("this app does not support always-on VPN: $pkg")
        } catch (e: Throwable) {
            Timber.e(e, "always-on VPN %s (lockdown=%s) failed", pkg, lockdown)
            PolicyResult.fail(e)
        }
    }

    /** The restrictions **this admin** set — not the union every admin on the device imposed. */
    fun userRestrictions(context: Context): Set<String> = try {
        val set = dpmOrNull(context)?.getUserRestrictions(admin(context))
        set?.keySet()?.filter { set.getBoolean(it) }?.toSet().orEmpty()
    } catch (e: Throwable) {
        emptySet()
    }

    fun isCameraDisabled(context: Context): Boolean = try {
        dpmOrNull(context)?.getCameraDisabled(admin(context)) == true
    } catch (e: Throwable) {
        false
    }

    /** Device-wide, not per-app — and instantly reversible from the same switch. */
    fun setCameraDisabled(context: Context, enabled: Boolean): PolicyResult {
        val dpm = dpmOrNull(context) ?: return PolicyResult.fail("DevicePolicyManager unavailable")
        return try {
            dpm.setCameraDisabled(admin(context), enabled)
            PolicyResult.OK
        } catch (e: Throwable) {
            Timber.e(e, "setCameraDisabled(%s) failed", enabled)
            PolicyResult.fail(e)
        }
    }

    // -----------------------------------------------------------------------------------------
    // The escape hatch
    // -----------------------------------------------------------------------------------------

    /** One line per step, so a partial recovery is visible instead of being reported as "done". */
    data class ClearStep(val name: String, val ok: Boolean, val detail: String? = null)

    /**
     * Release every device-policy lock on [pkg], or on every installed package when it is null.
     *
     * **There must always be a way back, and it must not depend on 応用管理 being installed.** Locks
     * its delegated calls set were stored under *our* admin, so the Device Owner can always undo
     * what its delegate did — which is also why no ledger is needed: every one of these is
     * discoverable from the platform.
     *
     * Slow by nature (a binder call per permission per package in the all-packages form), so this
     * must not run on the main thread.
     *
     * Device-wide items — user restrictions, always-on VPN, camera — are cleared only in the
     * all-packages form, because they are not per-app and clearing them while "fixing one app"
     * would be a surprise.
     */
    fun clearAllLocks(context: Context, pkg: String?): List<ClearStep> {
        val steps = mutableListOf<ClearStep>()
        val dpm = dpmOrNull(context)
            ?: return listOf(ClearStep("device policy", false, "DevicePolicyManager unavailable"))
        if (!DeviceOwnerHelper.isDeviceOwner(context)) {
            return listOf(ClearStep("device policy", false, "not-device-owner"))
        }
        val admin = admin(context)
        val targets = if (pkg != null) listOf(pkg) else installedPackages(context)

        // 1. Permissions fixed by policy -> back to DEFAULT.
        var permsCleared = 0
        val permFailures = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (target in targets) {
                for (perm in requestedPermissions(context, target)) {
                    try {
                        val state = dpm.getPermissionGrantState(admin, target, perm)
                        if (state != DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT) {
                            val done = dpm.setPermissionGrantState(
                                admin, target, perm,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                            )
                            if (done) permsCleared++ else permFailures += "$target/$perm"
                        }
                    } catch (e: Throwable) {
                        permFailures += "$target/$perm (${e.javaClass.simpleName})"
                    }
                }
            }
            steps += ClearStep(
                "permission locks", permFailures.isEmpty(),
                "released $permsCleared" + if (permFailures.isEmpty()) ""
                else "; refused: ${permFailures.take(10).joinToString()}"
            )
        }

        // 2. Suspension. A non-empty return names the packages it could NOT unsuspend.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val refused = dpm.setPackagesSuspended(admin, targets.toTypedArray(), false)
                steps += ClearStep(
                    "suspension", refused.isNullOrEmpty(),
                    if (refused.isNullOrEmpty()) "all unsuspended"
                    else "refused: ${refused.take(10).joinToString()}"
                )
            } catch (e: Throwable) {
                steps += ClearStep("suspension", false, reason(e))
            }
        }

        // 2b. Hidden packages. Not in the hand-off's list, but DELEGATION_PACKAGE_ACCESS carries
        // setApplicationHidden too, and a hidden app with no UI to unhide it is the same trap.
        val hiddenFailures = mutableListOf<String>()
        var unhidden = 0
        for (target in targets) {
            try {
                if (dpm.isApplicationHidden(admin, target)) {
                    if (dpm.setApplicationHidden(admin, target, false)) unhidden++
                    else hiddenFailures += target
                }
            } catch (e: Throwable) {
                // An uninstalled/unknown package throws here; that is not a failure to report.
            }
        }
        steps += ClearStep(
            "hidden packages", hiddenFailures.isEmpty(),
            "un-hid $unhidden" + if (hiddenFailures.isEmpty()) ""
            else "; refused: ${hiddenFailures.take(10).joinToString()}"
        )

        // 3. Uninstall block.
        var unblocked = 0
        val blockFailures = mutableListOf<String>()
        for (target in targets) {
            try {
                if (dpm.isUninstallBlocked(admin, target)) {
                    dpm.setUninstallBlocked(admin, target, false)
                    if (dpm.isUninstallBlocked(admin, target)) blockFailures += target else unblocked++
                }
            } catch (e: Throwable) {
                blockFailures += "$target (${e.javaClass.simpleName})"
            }
        }
        steps += ClearStep(
            "uninstall blocks", blockFailures.isEmpty(),
            "released $unblocked" + if (blockFailures.isEmpty()) ""
            else "; refused: ${blockFailures.take(10).joinToString()}"
        )

        // 4. User control disabled — read-modify-write, as always with a whole-list API.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val current = dpm.getUserControlDisabledPackages(admin)
                val next = if (pkg != null) current - pkg else emptyList()
                if (next.size != current.size) {
                    dpm.setUserControlDisabledPackages(admin, next)
                }
                steps += ClearStep("user control", true, "released ${current.size - next.size}")
            } catch (e: Throwable) {
                steps += ClearStep("user control", false, reason(e))
            }
        }

        // 5. Accessibility — remove from our blocklist and re-derive; empty blocklist -> null.
        val accessibility = if (pkg != null) {
            if (AccessibilityBlocklist.isBlocked(pkg)) {
                AccessibilityBlocklist.set(context, pkg, false)
            } else {
                PolicyResult.OK
            }
        } else {
            AccessibilityBlocklist.clearAll(context)
        }
        steps += ClearStep("accessibility blocks", accessibility.ok, accessibility.error)

        // 6. Device-wide items, only in the all-packages form.
        if (pkg == null) {
            for (key in OFFERED_RESTRICTIONS) {
                try {
                    dpm.clearUserRestriction(admin, key)
                } catch (e: Throwable) {
                    Timber.w(e, "clearing restriction %s failed", key)
                }
            }
            steps += ClearStep("user restrictions", true, "cleared every restriction we can set")

            val vpn = setAlwaysOnVpn(context, null, false)
            steps += ClearStep("always-on VPN", vpn.ok, vpn.error ?: "cleared")

            val camera = setCameraDisabled(context, false)
            steps += ClearStep("camera", camera.ok, camera.error ?: "re-enabled")
        }

        return steps
    }

    /**
     * The restrictions the UI offers — and therefore exactly the set the all-packages clear puts
     * back. Offered and clearable are deliberately the same list: a power that can be turned on
     * from a screen with no matching way back is the trap this whole feature is built to avoid.
     *
     * [REFUSED_RESTRICTIONS] are absent on purpose. We never set them, and clearing a restriction
     * some *other* admin owns is not ours to do.
     */
    val OFFERED_RESTRICTIONS: List<String> = listOf(
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_CONFIG_BLUETOOTH,
        UserManager.DISALLOW_CONFIG_CREDENTIALS,
        UserManager.DISALLOW_CONFIG_LOCATION,
        UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
        UserManager.DISALLOW_CONFIG_TETHERING,
        UserManager.DISALLOW_CONFIG_VPN,
        UserManager.DISALLOW_CONFIG_WIFI,
        UserManager.DISALLOW_INSTALL_APPS,
        UserManager.DISALLOW_MODIFY_ACCOUNTS,
        UserManager.DISALLOW_OUTGOING_BEAM,
        UserManager.DISALLOW_SHARE_LOCATION,
        UserManager.DISALLOW_UNINSTALL_APPS,
        UserManager.DISALLOW_UNMUTE_MICROPHONE,
        UserManager.DISALLOW_USB_FILE_TRANSFER,
    )

    private fun installedPackages(context: Context): List<String> = try {
        context.packageManager.getInstalledPackages(0).map { it.packageName }
    } catch (e: Throwable) {
        Timber.e(e, "enumerating installed packages failed")
        emptyList()
    }

    private fun requestedPermissions(context: Context, pkg: String): List<String> = try {
        context.packageManager
            .getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toList().orEmpty()
    } catch (e: Throwable) {
        emptyList()
    }

    private fun reason(e: Throwable) = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
}
