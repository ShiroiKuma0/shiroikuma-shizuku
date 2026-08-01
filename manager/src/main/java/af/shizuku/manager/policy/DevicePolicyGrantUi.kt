package af.shizuku.manager.policy

import android.content.Context
import android.content.DialogInterface
import android.widget.Toast
import af.shizuku.manager.admin.DeviceOwnerHelper
import af.shizuku.manager.shiroikuma.ShiroikumaDialogs
import af.shizuku.manager.shiroikuma.ShiroikumaToast
import af.shizuku.manager.shiroikuma.showHouse
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Authorizing a sister app, as UI that belongs to no particular screen.
 *
 * Both halves of the grant move together here — [PolicyAllowlist] and
 * [DeviceOwnerHelper.delegate] — because an app holding one without the other fails in ways that
 * look like a bug in the app rather than a half-finished switch.
 *
 * Context-only, so the home card's Device Owner section and the settings section drive the same
 * code. A second copy would eventually disagree about whether it verifies the delegation.
 */
object DevicePolicyGrantUi {

    /** Pick an app from the tile grid, then open its grant sheet. */
    fun authorizeAnotherApp(
        context: Context,
        scope: CoroutineScope,
        onChanged: () -> Unit
    ) {
        AppTilePicker.show(context, scope, context.getString(af.shizuku.manager.R.string.policy_authorize_title)) { pkg ->
            showAppSheet(context, pkg, scope, onChanged)
        }
    }

    /**
     * The per-app sheet: what the powers buy, what the platform actually reports back, and one
     * action that moves both halves.
     *
     * Granting sits in the **negative** slot painted red with Cancel in the positive one — the same
     * asymmetry `DeviceOwnerHelper.confirmAndClear` uses, for the same reason: this hands another
     * process the ability to fix permissions and suspend apps in ways the user cannot undo from
     * Settings.
     */
    fun showAppSheet(
        context: Context,
        pkg: String,
        scope: CoroutineScope,
        onChanged: () -> Unit
    ) {
        val authorized = PolicyAllowlist.allows(pkg)
        val scopes = DeviceOwnerHelper.delegatedScopes(context, pkg)
        val name = label(context, pkg)

        if (!authorized) {
            val body = buildString {
                append("Allow device-policy powers for $name ($pkg)?\n\n")
                append("This lets $name lock permissions so apps cannot restore them themselves, ")
                append("suspend apps outright, and block their uninstallation — and it lets it ask ")
                append("白い熊 雫 to block accessibility services, set device-wide restrictions, ")
                append("pin an always-on VPN and disable the camera.\n\n")
                append("⛔ None of it can be undone from Settings, by the affected app, or with adb. ")
                append("Only 白い熊 雫 or $name can release what they set — revoking here offers ")
                append("to clear them, and Settings has the same action for every app.\n\n")
                append("The delegated half keeps working with 白い熊 雫 stopped, because the system ")
                append("stores it. Turning this off later stops future calls but does not release ")
                append("locks already in place.")
            }
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle("危険 · Allow device-policy powers")
                .setMessage(body)
                .setPositiveButton(android.R.string.cancel, null)
                .setNegativeButton("Allow") { _, _ -> grant(context, pkg, onChanged) }
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
            append("\nPolicy API: authorized (content://${context.packageName}.policy)\n\n")
            append("Revoking stops future calls. It does NOT release permissions already fixed, ")
            append("apps already suspended, or uninstall blocks already set — those were stored ")
            append("under 白い熊 雫's admin and stay until they are cleared.")
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(name)
            .setMessage(body)
            .setPositiveButton("Close", null)
            .setNegativeButton("Revoke") { _, _ -> revoke(context, pkg, scope, onChanged) }
            .showHouse()
        ShiroikumaDialogs.markDestructive(dialog, DialogInterface.BUTTON_NEGATIVE)
    }

    fun grant(context: Context, pkg: String, onChanged: () -> Unit) {
        PolicyAllowlist.add(pkg)
        val delegated = DeviceOwnerHelper.delegate(context, pkg)
        onChanged()
        if (delegated) {
            ShiroikumaToast.show(context, "$pkg authorized", Toast.LENGTH_SHORT)
        } else {
            // The policy API half still works — say so, rather than reporting a flat failure.
            ShiroikumaDialogs.ok(
                context,
                "Delegation refused",
                "The policy API is authorized for $pkg, but the platform did not store the " +
                    "delegated scopes — it reports none back.\n\n" +
                    "$pkg will still be able to ask 白い熊 雫 to act on its behalf, but its own " +
                    "process cannot fix permissions or suspend apps directly. This usually means " +
                    "the package is not installed yet; authorize it again once it is."
            )
        }
    }

    /**
     * Revoke, and offer to clear that package's locks in the same breath.
     *
     * The offer is the point. Revoking only stops *future* calls: every permission the app already
     * policy-fixed and every package it suspended stays exactly as it was, stored under 白い熊 雫's
     * admin. Sending 白い熊 to find the recovery action later means the locks stay live in the gap,
     * with the app that set them no longer able to explain or release them.
     */
    fun revoke(context: Context, pkg: String, scope: CoroutineScope, onChanged: () -> Unit) {
        PolicyAllowlist.remove(pkg)
        DeviceOwnerHelper.undelegate(context, pkg)
        onChanged()
        MaterialAlertDialogBuilder(context)
            .setTitle("Revoked")
            .setMessage(
                "$pkg can no longer set device policy.\n\n" +
                    "Anything it already locked is still locked — permissions it fixed, apps it " +
                    "suspended or hid, uninstall blocks it set. Those were stored under " +
                    "白い熊 雫's admin, so only 白い熊 雫 can release them now.\n\n" +
                    "Clear them for $pkg?"
            )
            .setPositiveButton("Leave them", null)
            .setNegativeButton("Clear locks for this app") { _, _ ->
                confirmClearLocks(context, pkg, scope, onChanged)
            }
            .showHouse()
    }

    /**
     * The escape hatch, for one package or — with a null [pkg] — for everything.
     *
     * Shared by the per-app sheet and the Settings row so both describe the same operation in the
     * same words; the device-wide items are named only in the all-packages form, because that is
     * the only form that touches them.
     */
    fun confirmClearLocks(
        context: Context,
        pkg: String?,
        scope: CoroutineScope,
        onChanged: () -> Unit
    ) {
        val scopeLabel = pkg ?: "every installed app"
        val body = buildString {
            append("Release every device-policy lock on $scopeLabel.\n\n")
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
        MaterialAlertDialogBuilder(context)
            .setTitle("Clear device-policy locks")
            .setMessage(body)
            .setPositiveButton(android.R.string.cancel, null)
            .setNegativeButton("Clear") { _, _ -> runClearLocks(context, pkg, scope, onChanged) }
            .showHouse()
    }

    private fun runClearLocks(
        context: Context,
        pkg: String?,
        scope: CoroutineScope,
        onChanged: () -> Unit
    ) {
        ShiroikumaToast.show(context, "Clearing locks…", Toast.LENGTH_SHORT)
        scope.launch {
            val steps = withContext(Dispatchers.IO) { DevicePolicyApi.clearAllLocks(context, pkg) }
            onChanged()
            // A silent "done" would be the worst possible lie here: this is what someone runs when
            // they are already stuck, so every step reports for itself.
            val body = steps.joinToString("\n") { step ->
                "${if (step.ok) "✓" else "✗"} ${step.name}" + (step.detail?.let { " — $it" } ?: "")
            }
            ShiroikumaDialogs.ok(
                context,
                if (steps.all { it.ok }) "Locks cleared" else "Cleared with failures",
                body
            )
        }
    }

    /**
     * The list of apps that currently hold powers, as a way *in* to revoking.
     *
     * Without this, un-authorizing is only reachable by tapping "Authorize an app" and picking one
     * that is already authorized — which works, but nobody would look for it there.
     */
    fun showAuthorizedApps(context: Context, scope: CoroutineScope, onChanged: () -> Unit) {
        val packages = PolicyAllowlist.packages().toList().sorted()
        if (packages.isEmpty()) {
            ShiroikumaDialogs.ok(
                context,
                "No authorized apps",
                "No app holds device-policy powers. Use “Authorize an app” to grant them."
            )
            return
        }
        val labels = packages.map { "${label(context, it)}\n$it" }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle("Authorized apps")
            .setItems(labels) { _, which ->
                showAppSheet(context, packages[which], scope, onChanged)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showHouse()
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Throwable) {
        pkg
    }
}
