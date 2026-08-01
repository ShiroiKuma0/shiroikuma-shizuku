package af.shizuku.manager.policy

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.admin.DeviceOwnerHelper
import timber.log.Timber

/**
 * The private device-policy contract, exposed as a `ContentProvider.call()`.
 *
 * 白い熊 asked whether an intent would do it. It would, but a provider call is the better primitive
 * here — worth stating, because this family already has a broadcast contract (保存復元) and the
 * temptation is to copy it:
 *
 * - **It is synchronous.** `ContentResolver.call()` returns a `Bundle` on the spot, and every
 *   operation here is *"do X, did it work?"* behind a switch the user just tapped. The broadcast
 *   contract needs a whole reply channel (`setPackage`, `FLAG_INCLUDE_STOPPED_PACKAGES`, a
 *   `goAsync()` worker) precisely because a broadcast cannot answer.
 * - **The caller is knowable.** `Binder.getCallingUid()` cannot be spoofed, so [PolicyAllowlist] is
 *   a real gate. 保存復元 needs a shared secret *because* a broadcast has no trustworthy sender
 *   identity; a token here would be strictly weaker than what the binder already gives us.
 * - **It starts this app by itself**, so there is no "is 雫 running" dance.
 * - Dhizuku is already a `ContentProvider.call()` on these very phones, so the path is proven on
 *   EMUI.
 *
 * Exported with **no `android:permission`**: the caller could not hold one we define, so the
 * allowlist is the gate — exactly as `StateExportReceiver` is gated by its token rather than by a
 * permission.
 */
class PolicyProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // MUST be read before anything that could clear the identity: afterwards it is our own uid.
        val callingUid = Binder.getCallingUid()
        val context = context ?: return PolicyResult.fail("no context").toBundle()

        // A provider can be reached before the Application had a chance to prime the store.
        if (ShizukuSettings.getPreferences() == null) {
            runCatching { ShizukuSettings.initialize(context) }
        }

        val caller = allowedCaller(callingUid)
        val isDeviceOwner = DeviceOwnerHelper.isDeviceOwner(context)
        val args = extras ?: Bundle()

        // `status` answers even an unauthorized caller, so the sister app can tell the user *why*
        // it has no powers instead of silently showing a dead page.
        if (method == PolicyContract.METHOD_STATUS) {
            // The one call 応用管理 polls, so it is the reliable moment to notice that a newly
            // installed accessibility service is missing from the derived allowlist. Cheap: a read
            // and a comparison unless something actually drifted.
            runCatching { AccessibilityBlocklist.recomputeIfStale(context) }
            val info = Bundle().apply {
                putBoolean(PolicyContract.EXTRA_IS_DEVICE_OWNER, isDeviceOwner)
                putInt(PolicyContract.EXTRA_API_LEVEL, Build.VERSION.SDK_INT)
                putStringArray(
                    PolicyContract.EXTRA_DELEGATED_SCOPES,
                    scopesOf(callingUid).toTypedArray()
                )
                putStringArray(
                    PolicyContract.EXTRA_REFUSED_RESTRICTIONS,
                    DevicePolicyApi.REFUSED_RESTRICTIONS.toTypedArray()
                )
            }
            return if (caller == null) {
                PolicyResult.fail(PolicyContract.ERROR_NOT_AUTHORIZED).toBundle(info)
            } else {
                PolicyResult.OK.toBundle(info)
            }
        }

        if (caller == null) {
            return PolicyResult.fail(PolicyContract.ERROR_NOT_AUTHORIZED).toBundle()
        }
        if (!isDeviceOwner) {
            return PolicyResult.fail(PolicyContract.ERROR_NOT_DEVICE_OWNER).toBundle()
        }

        return try {
            when (method) {
                PolicyContract.METHOD_SET_ACCESSIBILITY_BLOCKED -> {
                    val pkg = args.getString(PolicyContract.EXTRA_PACKAGE)
                        ?: return missing(PolicyContract.EXTRA_PACKAGE)
                    DevicePolicyApi.setAccessibilityBlocked(
                        context, pkg, args.getBoolean(PolicyContract.EXTRA_BLOCKED, false)
                    ).toBundle()
                }

                PolicyContract.METHOD_SET_USER_CONTROL_DISABLED -> {
                    val pkg = args.getString(PolicyContract.EXTRA_PACKAGE)
                        ?: return missing(PolicyContract.EXTRA_PACKAGE)
                    DevicePolicyApi.setUserControlDisabled(
                        context, pkg, args.getBoolean(PolicyContract.EXTRA_ENABLED, false)
                    ).toBundle()
                }

                PolicyContract.METHOD_SET_USER_RESTRICTION -> {
                    val key = args.getString(PolicyContract.EXTRA_KEY)
                        ?: return missing(PolicyContract.EXTRA_KEY)
                    DevicePolicyApi.setUserRestriction(
                        context, key, args.getBoolean(PolicyContract.EXTRA_ENABLED, false)
                    ).toBundle()
                }

                PolicyContract.METHOD_SET_ALWAYS_ON_VPN -> DevicePolicyApi.setAlwaysOnVpn(
                    context,
                    args.getString(PolicyContract.EXTRA_PACKAGE),  // null clears it
                    args.getBoolean(PolicyContract.EXTRA_LOCKDOWN, false)
                ).toBundle()

                PolicyContract.METHOD_SET_CAMERA_DISABLED -> DevicePolicyApi.setCameraDisabled(
                    context, args.getBoolean(PolicyContract.EXTRA_ENABLED, false)
                ).toBundle()

                PolicyContract.METHOD_CLEAR_ALL_LOCKS -> {
                    // Null package = every installed package, per the contract.
                    val steps = DevicePolicyApi.clearAllLocks(
                        context, args.getString(PolicyContract.EXTRA_PACKAGE)
                    )
                    val detail = Bundle().apply {
                        putStringArray(
                            PolicyContract.EXTRA_STEPS,
                            steps.map {
                                "${it.name}: ${if (it.ok) "ok" else "FAILED"}" +
                                    (it.detail?.let { d -> " — $d" } ?: "")
                            }.toTypedArray()
                        )
                        putStringArray(
                            PolicyContract.EXTRA_STEPS_FAILED,
                            steps.filterNot { it.ok }.map { it.name }.toTypedArray()
                        )
                    }
                    val failed = steps.filterNot { it.ok }
                    if (failed.isEmpty()) PolicyResult.OK.toBundle(detail)
                    else PolicyResult.fail(
                        failed.joinToString("; ") { "${it.name}: ${it.detail ?: "failed"}" }
                    ).toBundle(detail)
                }

                else -> PolicyResult.fail(PolicyContract.ERROR_UNKNOWN_METHOD).toBundle()
            }
        } catch (e: Throwable) {
            Timber.e(e, "policy call %s from %s failed", method, caller)
            PolicyResult.fail(e).toBundle()
        }
    }

    private fun missing(name: String): Bundle =
        PolicyResult.fail("${PolicyContract.ERROR_MISSING_ARG}: $name").toBundle()

    /**
     * The allowlisted package behind [uid], or null.
     *
     * A uid can carry several packages (a shared user id), so every one of them is checked rather
     * than only the first: taking `getPackagesForUid(uid).first()` would deny an authorized app
     * purely because of the order the platform happened to return.
     */
    private fun allowedCaller(uid: Int): String? {
        val names = context?.packageManager?.getPackagesForUid(uid) ?: return null
        return names.firstOrNull { PolicyAllowlist.allows(it) }
    }

    /** Delegated scopes for the caller — read back from the platform, never from our own intent. */
    private fun scopesOf(uid: Int): List<String> {
        val ctx = context ?: return emptyList()
        val names = ctx.packageManager?.getPackagesForUid(uid) ?: return emptyList()
        return names.flatMap { DeviceOwnerHelper.delegatedScopes(ctx, it) }.distinct()
    }

    // A call()-only provider. The CRUD surface is deliberately inert rather than half-implemented.
    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?
    ): Int = 0
}
