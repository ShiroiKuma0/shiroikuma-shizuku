package af.shizuku.manager.settings

import android.content.Context
import android.content.DialogInterface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import af.shizuku.manager.admin.DeviceOwnerHelper
import af.shizuku.manager.policy.AccessibilityBlocklist
import af.shizuku.manager.policy.DevicePolicyApi
import af.shizuku.manager.policy.PolicyAllowlist
import af.shizuku.manager.shiroikuma.ShiroikumaDialogs
import af.shizuku.manager.shiroikuma.ShiroikumaToast
import af.shizuku.manager.shiroikuma.ShiroikumaUiPrefs
import af.shizuku.manager.shiroikuma.showHouse
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Device policy powers" section — the single place where 白い熊 hands a sister app the parts of
 * Device Owner it needs, and the single place to take them back.
 *
 * It carries **both** halves of the hand-off, because from 白い熊's side they are one decision:
 * flipping a package on delegates the `DELEGATION_*` scopes *and* adds it to the policy-API
 * allowlist. Splitting them into two switches would let the two drift apart, and an app holding one
 * without the other fails in ways that look like a bug in the app.
 *
 * ⛔ **Everything here is hard to undo.** Only this app can reverse any of it — not Settings, not
 * the affected app, not `adb`; `dpm` has no command for these either. That is what makes a lock a
 * lock, and it is why every dangerous row carries a red 危険 tag *before* the dialog is opened, and
 * why *Clear all device-policy locks* sits at the bottom as a recovery action rather than a
 * footnote.
 *
 * Lives in its own file rather than inside `ShizukuPlusSettingsFragment` so an upstream rebase
 * touches one call line instead of four hundred lines of fork code.
 */
object DevicePolicyPowersSection {

    private const val KEY_CATEGORY = "category_device_policy_powers"
    private const val KEY_STATUS = "policy_status"
    private const val KEY_AUTHORIZE = "policy_authorize_app"
    private const val KEY_ACCESSIBILITY = "policy_accessibility"
    private const val KEY_RESTRICTIONS = "policy_user_restriction"
    private const val KEY_VPN = "policy_always_on_vpn"
    private const val KEY_CAMERA = "policy_camera_disabled"
    private const val KEY_CLEAR = "policy_clear_all_locks"

    /** Per-app rows start here; the static rows sit at 1 and 100+ so they never collide. */
    private const val APP_ROW_ORDER_BASE = 10

    fun attach(fragment: PreferenceFragmentCompat) {
        val ctx = fragment.context ?: return

        fragment.findPreference<Preference>(KEY_AUTHORIZE)?.setOnPreferenceClickListener {
            showAuthorizePicker(fragment); true
        }
        fragment.findPreference<Preference>(KEY_ACCESSIBILITY)?.setOnPreferenceClickListener {
            confirmThenAccessibility(fragment); true
        }
        fragment.findPreference<Preference>(KEY_RESTRICTIONS)?.setOnPreferenceClickListener {
            confirmThenRestrictions(fragment); true
        }
        fragment.findPreference<Preference>(KEY_VPN)?.setOnPreferenceClickListener {
            confirmThenVpn(fragment); true
        }
        fragment.findPreference<Preference>(KEY_CLEAR)?.setOnPreferenceClickListener {
            confirmClearAll(fragment, null); true
        }
        fragment.findPreference<SwitchPreferenceCompat>(KEY_CAMERA)
            ?.setOnPreferenceChangeListener { pref, newValue ->
                val enabled = newValue as? Boolean ?: false
                val result = DevicePolicyApi.setCameraDisabled(ctx, enabled)
                if (result.ok) {
                    ShiroikumaToast.show(
                        ctx,
                        if (enabled) "Camera disabled device-wide" else "Camera re-enabled",
                        Toast.LENGTH_SHORT
                    )
                    (pref as SwitchPreferenceCompat).isChecked = enabled
                } else {
                    ShiroikumaToast.show(ctx, "Failed: ${result.error}", Toast.LENGTH_LONG)
                }
                false  // we set the checked state ourselves, only on a write the platform accepted
            }

        refresh(fragment)
    }

    /**
     * Rebuild the rows from what the platform actually reports.
     *
     * Also the natural moment to notice that the derived accessibility allowlist went stale — the
     * package broadcast is best-effort, and a service silently refusing to stay enabled is exactly
     * the failure the blocklist design exists to avoid.
     */
    fun refresh(fragment: PreferenceFragmentCompat) {
        val ctx = fragment.context ?: return
        val isOwner = DeviceOwnerHelper.isDeviceOwner(ctx)

        fragment.findPreference<Preference>(KEY_STATUS)?.apply {
            title = if (isOwner) "Device Owner: active" else "Device Owner required"
            summary = if (isOwner) {
                "白い熊 雫 holds Device Owner, so it can hand these powers on."
            } else {
                "Every row below is inert until this app is Device Owner."
            }
        }

        // A row that cannot work is disabled rather than hidden: hiding it would make the section
        // look complete while quietly doing nothing.
        for (key in listOf(KEY_AUTHORIZE, KEY_ACCESSIBILITY, KEY_RESTRICTIONS, KEY_VPN, KEY_CAMERA, KEY_CLEAR)) {
            fragment.findPreference<Preference>(key)?.isEnabled = isOwner
        }

        fragment.findPreference<Preference>(KEY_ACCESSIBILITY)?.apply {
            title = danger("Accessibility blocks")
            val blocked = AccessibilityBlocklist.blocked()
            summary = if (blocked.isEmpty()) {
                "Nothing blocked. A blocked service cannot be enabled by anyone, including you in Settings."
            } else {
                "Blocked: ${blocked.joinToString()}"
            }
        }

        fragment.findPreference<Preference>(KEY_RESTRICTIONS)?.apply {
            title = danger("User restrictions")
            val set = if (isOwner) DevicePolicyApi.userRestrictions(ctx) else emptySet()
            summary = if (set.isEmpty()) {
                "None set. A user restriction is device-wide, and only 白い熊 雫 can clear it."
            } else {
                "Set: ${set.joinToString()}"
            }
        }

        fragment.findPreference<Preference>(KEY_VPN)?.apply {
            title = danger("Always-on VPN")
            summary = "With lockdown on, no traffic leaves the phone unless that VPN is connected — " +
                "including the Wi-Fi ADB you would use to undo it."
        }

        fragment.findPreference<SwitchPreferenceCompat>(KEY_CAMERA)?.isChecked =
            isOwner && DevicePolicyApi.isCameraDisabled(ctx)

        rebuildAppRows(fragment, isOwner)

        if (isOwner) {
            fragment.lifecycleScope.launch(Dispatchers.IO) {
                runCatching { AccessibilityBlocklist.recomputeIfStale(ctx) }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Per-app rows
    // -----------------------------------------------------------------------------------------

    private fun rebuildAppRows(fragment: PreferenceFragmentCompat, isOwner: Boolean) {
        val ctx = fragment.context ?: return
        val category = fragment.findPreference<CollapsiblePreferenceCategory>(KEY_CATEGORY) ?: return

        // 応用管理 is always listed even when it is not yet authorized — it is the app this exists
        // for, and "where do I turn it on" should not require finding it in a picker first.
        val packages = (PolicyAllowlist.packages() + PolicyAllowlist.OYOKANRI).toSortedSet()

        // Drop the previous generation before rebuilding; keys are stable, so a stale row for a
        // package that is no longer listed would otherwise linger.
        val stale = (0 until category.preferenceCount)
            .map { category.getPreference(it) }
            .filter { it.key?.startsWith(APP_ROW_PREFIX) == true }
        stale.forEach { category.removePreference(it) }

        packages.forEachIndexed { index, pkg ->
            val row = Preference(ctx).apply {
                key = APP_ROW_PREFIX + pkg
                order = APP_ROW_ORDER_BASE + index
                icon = ctx.getDrawable(af.shizuku.manager.R.drawable.ic_admin_panel_settings_24)
                isEnabled = isOwner
                title = label(ctx, pkg)
                summary = appRowSummary(ctx, pkg)
                setOnPreferenceClickListener { showAppSheet(fragment, pkg); true }
            }
            category.addPreference(row)
        }
    }

    private const val APP_ROW_PREFIX = "policy_app::"

    private fun appRowSummary(ctx: Context, pkg: String): String {
        if (!PolicyAllowlist.allows(pkg)) return "$pkg — not authorized"
        val scopes = DeviceOwnerHelper.delegatedScopes(ctx, pkg)
        return if (scopes.isEmpty()) {
            "$pkg — authorized for the policy API; the platform reports no delegated scopes"
        } else {
            "$pkg — ${scopes.size} delegated scope(s) + the policy API"
        }
    }

    /**
     * The per-app sheet: one switch that moves both halves, and a read-back of what the platform
     * actually reports rather than what we asked for.
     *
     * Granting sits in the **negative** slot painted red, and Cancel in the positive one — the same
     * asymmetry `DeviceOwnerHelper.confirmAndClear` uses, for the same reason: this hands another
     * process the ability to fix permissions and suspend apps in ways the user cannot undo from
     * Settings.
     */
    private fun showAppSheet(fragment: PreferenceFragmentCompat, pkg: String) {
        val ctx = fragment.context ?: return
        val authorized = PolicyAllowlist.allows(pkg)
        val scopes = DeviceOwnerHelper.delegatedScopes(ctx, pkg)
        val name = label(ctx, pkg)

        if (!authorized) {
            val body = buildString {
                append("Allow device-policy powers for $name ($pkg)?\n\n")
                append("This lets $name lock permissions so apps cannot restore them themselves, ")
                append("suspend apps outright, and block their uninstallation — and it lets it ask ")
                append("白い熊 雫 to block accessibility services, set device-wide restrictions, ")
                append("pin an always-on VPN and disable the camera.\n\n")
                append("⛔ None of it can be undone from Settings, by the affected app, or with adb. ")
                append("Only 白い熊 雫 or $name can release what they set — use ")
                append("“Clear all device-policy locks” below if you get stuck.\n\n")
                append("The delegated half keeps working with 白い熊 雫 stopped, because the system ")
                append("stores it. Turning this off later stops future calls but does not release ")
                append("locks already in place.")
            }
            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(danger("Allow device-policy powers"))
                .setMessage(body)
                .setPositiveButton(android.R.string.cancel, null)
                .setNegativeButton("Allow") { _, _ -> grant(fragment, pkg) }
                .showHouse()
            ShiroikumaDialogs.markDestructive(dialog, DialogInterface.BUTTON_NEGATIVE)
            return
        }

        val body = buildString {
            append("$name ($pkg) holds device-policy powers.\n\n")
            append("Delegated scopes, as the platform reports them:\n")
            if (scopes.isEmpty()) {
                append("  (none — the platform dropped or never stored them)\n")
            } else {
                scopes.forEach { append("  • $it\n") }
            }
            append("\nPolicy API: authorized (content://${ctx.packageName}.policy)\n\n")
            append("Revoking stops future calls. It does NOT release permissions already fixed, ")
            append("apps already suspended, or uninstall blocks already set — those were stored ")
            append("under 白い熊 雫's admin and stay until they are cleared.")
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(name)
            .setMessage(body)
            .setPositiveButton("Close", null)
            .setNegativeButton("Revoke") { _, _ -> revoke(fragment, pkg) }
            .showHouse()
        ShiroikumaDialogs.markDestructive(dialog, DialogInterface.BUTTON_NEGATIVE)
    }

    private fun grant(fragment: PreferenceFragmentCompat, pkg: String) {
        val ctx = fragment.context ?: return
        PolicyAllowlist.add(pkg)
        val delegated = DeviceOwnerHelper.delegate(ctx, pkg)
        refresh(fragment)
        if (delegated) {
            ShiroikumaToast.show(ctx, "$pkg authorized", Toast.LENGTH_SHORT)
        } else {
            // The policy API half still works — say so, rather than reporting a flat failure.
            ShiroikumaDialogs.ok(
                ctx,
                "Delegation refused",
                "The policy API is authorized for $pkg, but the platform did not store the " +
                    "delegated scopes — it reports none back.\n\n" +
                    "$pkg will still be able to ask 白い熊 雫 to act on its behalf, but its own " +
                    "process cannot fix permissions or suspend apps directly. This usually means " +
                    "the package is not installed yet; authorize it again once it is."
            )
        }
    }

    private fun revoke(fragment: PreferenceFragmentCompat, pkg: String) {
        val ctx = fragment.context ?: return
        PolicyAllowlist.remove(pkg)
        DeviceOwnerHelper.undelegate(ctx, pkg)
        refresh(fragment)
        // Offered in the same breath, per the hand-off: otherwise the locks it already set become
        // invisible — no UI anywhere lists them once the app that set them has no powers.
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Revoked")
            .setMessage(
                "$pkg can no longer set device policy.\n\n" +
                    "Anything it already locked is still locked. Clear those now?"
            )
            .setPositiveButton("Leave them") { _, _ -> }
            .setNegativeButton("Clear locks for $pkg") { _, _ -> confirmClearAll(fragment, pkg) }
            .showHouse()
    }

    private fun showAuthorizePicker(fragment: PreferenceFragmentCompat) {
        val ctx = fragment.context ?: return
        fragment.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                runCatching { AppPickerPreference.getApps(ctx) }.getOrDefault(emptyList())
            }
            if (apps.isEmpty()) {
                ShiroikumaToast.show(ctx, "Could not list installed apps", Toast.LENGTH_SHORT)
                return@launch
            }
            val sorted = apps.sortedBy { it.label.toString().lowercase() }
            val labels = sorted.map { "${it.label}\n${it.packageName}" }.toTypedArray()
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Authorize another app")
                .setItems(labels) { _, which -> showAppSheet(fragment, sorted[which].packageName) }
                .setNegativeButton(android.R.string.cancel, null)
                .showHouse()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Device-wide powers
    // -----------------------------------------------------------------------------------------

    private fun confirmThenAccessibility(fragment: PreferenceFragmentCompat) {
        val ctx = fragment.context ?: return
        warn(
            ctx,
            "Accessibility blocks",
            "Blocks an app's accessibility service so it cannot be enabled by anyone, including " +
                "you in Settings. Only 白い熊 雫 can lift it.\n\n" +
                "Be careful what you block. An accessibility service is how automation tools, " +
                "keyboard helpers, screen readers and gesture utilities work. Blocking one you " +
                "rely on can leave you unable to operate the phone the way you normally do — and " +
                "you cannot fix it from Settings, only from here.",
            "Choose services"
        ) { showAccessibilityPicker(fragment) }
    }

    private fun showAccessibilityPicker(fragment: PreferenceFragmentCompat) {
        val ctx = fragment.context ?: return
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val services = runCatching { am?.installedAccessibilityServiceList.orEmpty() }
            .getOrDefault(emptyList())
            .mapNotNull { info -> info.resolveInfo?.serviceInfo?.packageName?.let { it to info } }
            .distinctBy { it.first }
            .sortedBy { it.first }

        if (services.isEmpty()) {
            // A partial or empty enumeration is exactly what would bar every service on the device.
            ShiroikumaDialogs.ok(
                ctx,
                "No services found",
                "No accessibility services could be enumerated, so nothing was changed. Writing a " +
                    "permitted list from an empty enumeration would bar every service on the device."
            )
            return
        }

        val pm = ctx.packageManager
        val names = services.map { (pkg, info) ->
            val label = runCatching { info.resolveInfo.loadLabel(pm).toString() }.getOrDefault(pkg)
            "$label\n$pkg"
        }.toTypedArray()
        val blocked = AccessibilityBlocklist.blocked()
        val checked = services.map { it.first in blocked }.toBooleanArray()

        MaterialAlertDialogBuilder(ctx)
            .setTitle(danger("Blocked services"))
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.cancel, null)
            .setNegativeButton("Apply") { _, _ ->
                val failures = mutableListOf<String>()
                services.forEachIndexed { index, (pkg, _) ->
                    val want = checked[index]
                    if (want != (pkg in AccessibilityBlocklist.blocked())) {
                        val result = AccessibilityBlocklist.set(ctx, pkg, want)
                        if (!result.ok) failures += "$pkg: ${result.error}"
                    }
                }
                refresh(fragment)
                if (failures.isNotEmpty()) {
                    ShiroikumaDialogs.ok(ctx, "Some blocks failed", failures.joinToString("\n"))
                }
            }
            .showHouse()
            .also { ShiroikumaDialogs.markDestructive(it, DialogInterface.BUTTON_NEGATIVE) }
    }

    private fun confirmThenRestrictions(fragment: PreferenceFragmentCompat) {
        val ctx = fragment.context ?: return
        warn(
            ctx,
            "User restrictions",
            "A user restriction is device-wide, not per-app, and only 白い熊 雫 can clear it.\n\n" +
                "The keys that would remove the routes you need to fix a mistake — ADB, safe " +
                "boot, factory reset and sideloading — are refused outright and are not offered " +
                "here at all.",
            "Choose restrictions"
        ) { showRestrictionPicker(fragment) }
    }

    private fun showRestrictionPicker(fragment: PreferenceFragmentCompat) {
        val ctx = fragment.context ?: return
        val keys = DevicePolicyApi.OFFERED_RESTRICTIONS
        val active = DevicePolicyApi.userRestrictions(ctx)
        val checked = keys.map { it in active }.toBooleanArray()

        MaterialAlertDialogBuilder(ctx)
            .setTitle(danger("User restrictions"))
            .setMultiChoiceItems(keys.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.cancel, null)
            .setNegativeButton("Apply") { _, _ ->
                val failures = mutableListOf<String>()
                keys.forEachIndexed { index, key ->
                    if (checked[index] != (key in active)) {
                        val result = DevicePolicyApi.setUserRestriction(ctx, key, checked[index])
                        if (!result.ok) failures += "$key: ${result.error}"
                    }
                }
                refresh(fragment)
                if (failures.isNotEmpty()) {
                    ShiroikumaDialogs.ok(ctx, "Some restrictions failed", failures.joinToString("\n"))
                }
            }
            .showHouse()
            .also { ShiroikumaDialogs.markDestructive(it, DialogInterface.BUTTON_NEGATIVE) }
    }

    private fun confirmThenVpn(fragment: PreferenceFragmentCompat) {
        val ctx = fragment.context ?: return
        warn(
            ctx,
            "Always-on VPN",
            "Pins one app as the device's VPN.\n\n" +
                "With lockdown on, no traffic leaves the phone unless that VPN app is connected. " +
                "If the VPN app breaks, is suspended, or is uninstalled, the phone has no network " +
                "at all — including the Wi-Fi ADB you might use to undo it.",
            "Choose app"
        ) { showVpnPicker(fragment) }
    }

    private fun showVpnPicker(fragment: PreferenceFragmentCompat) {
        val ctx = fragment.context ?: return
        fragment.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                runCatching { AppPickerPreference.getApps(ctx) }.getOrDefault(emptyList())
            }
            val sorted = apps.sortedBy { it.label.toString().lowercase() }
            val labels = (listOf("None — clear the always-on VPN") +
                sorted.map { "${it.label}\n${it.packageName}" }).toTypedArray()

            MaterialAlertDialogBuilder(ctx)
                .setTitle("Always-on VPN")
                .setItems(labels) { _, which ->
                    if (which == 0) {
                        val result = DevicePolicyApi.setAlwaysOnVpn(ctx, null, false)
                        report(ctx, fragment, result.ok, "Always-on VPN cleared", result.error)
                    } else {
                        askLockdown(fragment, sorted[which - 1].packageName)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showHouse()
        }
    }

    /**
     * Lockdown gets its own confirmation, separate from choosing the package.
     *
     * Setting the VPN package without lockdown is comparatively mild — traffic still flows if the
     * VPN drops. Lockdown is the setting that can take the phone off the network entirely, so it is
     * not something to acquire as a side effect of picking an app from a list.
     */
    private fun askLockdown(fragment: PreferenceFragmentCompat, pkg: String) {
        val ctx = fragment.context ?: return
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(danger("Lockdown?"))
            .setMessage(
                "Set $pkg as the always-on VPN.\n\n" +
                    "Without lockdown: traffic falls back to the normal network if the VPN drops.\n\n" +
                    "With lockdown: nothing leaves the phone unless $pkg is connected. If it " +
                    "breaks or is removed, the phone has no network at all — and no Wi-Fi ADB to " +
                    "undo this with."
            )
            .setPositiveButton("Without lockdown") { _, _ ->
                val result = DevicePolicyApi.setAlwaysOnVpn(ctx, pkg, false)
                report(ctx, fragment, result.ok, "Always-on VPN set to $pkg", result.error)
            }
            .setNegativeButton("With lockdown") { _, _ ->
                val result = DevicePolicyApi.setAlwaysOnVpn(ctx, pkg, true)
                report(ctx, fragment, result.ok, "Always-on VPN set to $pkg with lockdown", result.error)
            }
            .showHouse()
        ShiroikumaDialogs.markDestructive(dialog, DialogInterface.BUTTON_NEGATIVE)
    }

    // -----------------------------------------------------------------------------------------
    // The escape hatch
    // -----------------------------------------------------------------------------------------

    private fun confirmClearAll(fragment: PreferenceFragmentCompat, pkg: String?) {
        val ctx = fragment.context ?: return
        val scope = pkg ?: "every installed app"
        val body = buildString {
            append("Release every device-policy lock on $scope.\n\n")
            append("This clears permissions fixed by policy, suspended apps, hidden apps and ")
            append("uninstall blocks — including ones a delegate set, because they were stored ")
            append("under 白い熊 雫's admin.\n\n")
            if (pkg == null) {
                append("It also clears the device-wide items: every user restriction this app set, ")
                append("the always-on VPN, and the camera block.\n\n")
            }
            append("Nothing here can strand the device — this action only releases. It can take a ")
            append("while: it checks every permission of every package.")
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Clear all device-policy locks")
            .setMessage(body)
            .setPositiveButton(android.R.string.cancel, null)
            .setNegativeButton("Clear") { _, _ -> runClearAll(fragment, pkg) }
            .showHouse()
    }

    private fun runClearAll(fragment: PreferenceFragmentCompat, pkg: String?) {
        val ctx = fragment.context ?: return
        ShiroikumaToast.show(ctx, "Clearing locks…", Toast.LENGTH_SHORT)
        fragment.lifecycleScope.launch {
            val steps = withContext(Dispatchers.IO) { DevicePolicyApi.clearAllLocks(ctx, pkg) }
            refresh(fragment)
            // A silent "done" would be the worst possible lie here: this is what someone runs when
            // they are already stuck, so every step reports for itself.
            val body = steps.joinToString("\n") { step ->
                "${if (step.ok) "✓" else "✗"} ${step.name}" +
                    (step.detail?.let { " — $it" } ?: "")
            }
            ShiroikumaDialogs.ok(
                ctx,
                if (steps.all { it.ok }) "Locks cleared" else "Cleared with failures",
                body
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // House helpers
    // -----------------------------------------------------------------------------------------

    /**
     * The red 危険 tag, on the row itself — visible before any dialog is opened.
     *
     * A warning that only appears once you have already tapped through is not a warning about
     * whether to tap.
     */
    private fun danger(text: String): CharSequence = SpannableStringBuilder().apply {
        val tag = "危険 "
        append(tag)
        setSpan(ForegroundColorSpan(ShiroikumaUiPrefs.RED), 0, tag.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, tag.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(text)
    }

    /** The §B.5 pattern: what it does, what it breaks, how to undo it — then Cancel wins the slot. */
    private fun warn(
        ctx: Context,
        title: String,
        message: String,
        proceedLabel: String,
        onProceed: () -> Unit
    ) {
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(danger(title))
            .setMessage(message)
            .setPositiveButton(android.R.string.cancel, null)
            .setNegativeButton(proceedLabel) { _, _ -> onProceed() }
            .showHouse()
        ShiroikumaDialogs.markDestructive(dialog, DialogInterface.BUTTON_NEGATIVE)
    }

    private fun report(
        ctx: Context,
        fragment: PreferenceFragmentCompat,
        ok: Boolean,
        success: String,
        error: String?
    ) {
        refresh(fragment)
        if (ok) ShiroikumaToast.show(ctx, success, Toast.LENGTH_SHORT)
        else ShiroikumaDialogs.ok(ctx, "Failed", error ?: "no reason reported")
    }

    private fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Throwable) {
        pkg
    }
}
