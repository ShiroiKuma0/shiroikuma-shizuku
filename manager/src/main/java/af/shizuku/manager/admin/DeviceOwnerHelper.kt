package af.shizuku.manager.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.widget.Toast
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbLoopbackShell
import af.shizuku.manager.adb.LocalNetworkPermission
import af.shizuku.manager.shiroikuma.ShiroikumaDialogs
import af.shizuku.manager.shiroikuma.ShiroikumaToast
import af.shizuku.manager.shiroikuma.showHouse
import af.shizuku.manager.utils.PrivilegedShell
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.core.content.asActivity
import timber.log.Timber

/**
 * Everything about becoming — and, more importantly, ceasing to be — Device Owner.
 *
 * This lived inside `ShizukuPlusSettingsFragment` and reached into that fragment's own preference
 * widgets, so it could not be called from anywhere else. The home boot-setup card needs the same
 * two operations, and a second copy of *this* logic is not acceptable: the clear path is the only
 * clean exit from Device Owner, and two implementations would eventually disagree about whether
 * they verify the result. So it lives here, context-only, and the settings screen delegates to it.
 */
object DeviceOwnerHelper {

    /**
     * The admin component, **derived — never spelled out**.
     *
     * `applicationId` (`shiroikuma.shizuku`) differs from the code `namespace`
     * (`af.shizuku.manager`), so the `pkg/.Receiver` shorthand expands against the applicationId and
     * names a class that does not exist; `dpm` then fails, or worse appears to succeed. Taking the
     * package from the context and the fully-qualified name from the class keeps this correct even
     * if the receiver is renamed or moved. Getting it wrong here costs a factory reset.
     * See CLAUDE.md, "Never assume applicationId == namespace".
     */
    fun component(context: Context): ComponentName =
        ComponentName(context, DhizukuAdminReceiver::class.java)

    /** The command as the user would run it from a PC — the fallback when we have no privilege. */
    fun setupCommand(context: Context): String =
        "adb shell dpm set-device-owner ${component(context).flattenToString()}"

    private fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    fun isDeviceOwner(context: Context): Boolean = try {
        dpm(context)?.isDeviceOwnerApp(context.packageName) == true
    } catch (e: Exception) {
        false
    }

    /**
     * Profile Owner, which upstream added alongside Device Owner.
     *
     * Kept separate from [isDeviceOwner] on purpose: the boot-setup card asks whether the *boot
     * survival* guarantee holds, and only Device Owner gives that. The clear path, by contrast, has
     * to handle either role — an app stuck as Profile Owner needs the same escape hatch.
     */
    fun isProfileOwner(context: Context): Boolean = try {
        dpm(context)?.isProfileOwnerApp(context.packageName) == true
    } catch (e: Exception) {
        false
    }

    /**
     * Whether the system would currently *accept* device-owner provisioning at all.
     *
     * Lets the card say "blocked" before running anything, instead of offering a button that can
     * only fail. It does not say why — `dpm`'s own message does that, which is why
     * [makeDeviceOwner] surfaces the output verbatim.
     */
    @Suppress("DEPRECATION")
    fun isProvisioningAllowed(context: Context): Boolean = try {
        dpm(context)?.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE) == true
    } catch (e: Exception) {
        false
    }

    /**
     * Run `dpm set-device-owner` through Shizuku (shell, UID 2000), root, or the device's own adb.
     *
     * `dpm` refuses for several distinct reasons — accounts present on the device, more than one
     * user, an owner already set, or an OEM policy layer declining outright — and the only way to
     * tell them apart is the text it prints, so the outcome is returned intact for the caller to
     * display.
     *
     * The loopback adb tier is not a consolation prize: `adb shell dpm set-device-owner` is *the*
     * canonical way to do this, and once `adb tcpip` has been issued from a cable the phone can run
     * it on itself with no PC attached. Landing on the copy-this-command dialog while that shell sits
     * listening is a dead end with the road right beside it — so the dialog is now only reached when
     * there is genuinely no shell of any kind.
     *
     * Success is judged by [isDeviceOwner], never by an exit code: `dpm` can exit 0 on some OEM
     * builds without the flag landing, and a tier that merely *ran* proves nothing.
     */
    suspend fun makeDeviceOwner(context: Context): PrivilegedShell.Outcome {
        val command = "dpm set-device-owner ${component(context).flattenToString()}"

        val privileged = PrivilegedShell.run(command)
        if (isDeviceOwner(context)) return privileged

        val port = AdbLoopbackShell.detectPort(context)
        if (port !in 1..65535) return privileged

        // Best-effort, exactly as the ADB start screen does it: a loopback socket is still
        // local-network access under Android 16+ LNP. requestPermissions is main-thread only.
        withContext(Dispatchers.Main) {
            context.asActivity<Activity>()?.let { LocalNetworkPermission.request(it) }
        }

        return when (val adb = AdbLoopbackShell.run(context, port, command)) {
            is AdbLoopbackShell.Outcome.Ok -> PrivilegedShell.Outcome.Ok(adb.output)
            is AdbLoopbackShell.Outcome.Failed ->
                PrivilegedShell.Outcome.Failed(-1, "adb on port $port: ${adb.reason}")
            // Nothing was attempted, so the earlier tier's reason is still the better one to show.
            AdbLoopbackShell.Outcome.Unavailable -> privileged
        }
    }

    /**
     * The strong warning that must precede any clear, with **Cancel in the positive slot**.
     *
     * Android has no OS-level default button, so the only way to make cancelling the default is to
     * put it where the thumb lands and the emphasis sits, and to leave the destructive choice in the
     * quiet negative slot. Clearing takes one tap; getting Device Owner back requires stripping every
     * account off the device and can be refused outright, at which point a factory reset is the only
     * route back — so the asymmetry in the buttons matches the asymmetry in the consequences.
     */
    fun confirmAndClear(context: Context, onCleared: () -> Unit = {}) {
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dhizuku_clear_owner_title)
            .setMessage(R.string.dhizuku_clear_owner_message)
            .setPositiveButton(android.R.string.cancel, null)
            .setNegativeButton(R.string.dhizuku_clear_owner_confirm) { _, _ ->
                clearDeviceOwner(context, onCleared)
            }
            .showHouse()
        // Both buttons carry a border; only this one is red.
        ShiroikumaDialogs.markDestructive(dialog, DialogInterface.BUTTON_NEGATIVE)
    }

    /**
     * Give up Device Owner.
     *
     * This is the **only** clean way out: once an app is Device Owner it cannot be uninstalled
     * normally and `dpm remove-active-admin` refuses, so if this path fails the remaining exit is a
     * factory reset. That makes silent or vague failure unacceptable here — upstream reported one
     * generic toast and swallowed the exception, which left no way to tell "not device owner" from
     * "SecurityException" from "cleared, but the flag survived".
     *
     * So: report the real reason, verify the clear actually took effect (the API is documented as
     * best-effort), and make the text copyable so it can be acted on.
     *
     * Needs neither Shizuku nor shell — the app clears itself — so the escape hatch still works with
     * the service down.
     */
    fun clearDeviceOwner(context: Context, onCleared: () -> Unit = {}) {
        val dpm = dpm(context)
        if (dpm == null) {
            showClearFailure(context, "DevicePolicyManager unavailable on this device.")
            return
        }
        val wasDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
        val wasProfileOwner = dpm.isProfileOwnerApp(context.packageName)
        if (!wasDeviceOwner && !wasProfileOwner) {
            showClearFailure(
                context,
                "This app is neither Device Owner nor Profile Owner, so there is nothing to clear.\n\n" +
                    "Package: ${context.packageName}"
            )
            return
        }
        val role = if (wasDeviceOwner) "Device Owner" else "Profile Owner"

        try {
            if (wasDeviceOwner) {
                dpm.clearDeviceOwnerApp(context.packageName)
            } else {
                dpm.clearProfileOwner(component(context))
            }
        } catch (e: Throwable) {
            Timber.e(e, "clearing $role failed")
            showClearFailure(
                context,
                "${e.javaClass.simpleName}: ${e.message ?: "no message"}\n\n" +
                    "Package: ${context.packageName}\n\n" +
                    "The device is still $role. Do NOT uninstall the app in this state — " +
                    "removing it while it holds $role leaves a factory reset as the only exit."
            )
            return
        }

        // Both clear calls are documented as best-effort, so confirm rather than assume.
        if (dpm.isDeviceOwnerApp(context.packageName) || dpm.isProfileOwnerApp(context.packageName)) {
            showClearFailure(
                context,
                "The call returned without error, but this app is still reported as $role.\n\n" +
                    "Package: ${context.packageName}\n\n" +
                    "Do NOT uninstall the app in this state."
            )
            return
        }

        ShiroikumaToast.show(context, R.string.dhizuku_clear_owner_success, Toast.LENGTH_LONG)
        ShizukuSettings.setDhizukuModeEnabled(false)
        onCleared()
    }

    /** A dialog, not a toast: this text has to survive long enough to be read and copied. */
    fun showClearFailure(context: Context, detail: String) {
        val body = context.getString(R.string.dhizuku_clear_owner_failure) + "\n\n" + detail
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dhizuku_clear_owner_failure_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.dhizuku_clear_owner_copy) { _, _ ->
                copy(context, "device owner error", body)
            }
            .showHouse()
    }

    /** The PC route, offered when we hold no privilege of our own to run `dpm` with. */
    fun showSetupCommandDialog(context: Context) {
        val command = setupCommand(context)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dhizuku_setup_title)
            .setMessage(context.getString(R.string.dhizuku_setup_message, command))
            .setPositiveButton(R.string.dhizuku_setup_copy) { _, _ ->
                copy(context, "dpm command", command)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showHouse()
    }

    /** Shown when `dpm` ran and refused — the reason is the whole value, so it must be copyable. */
    fun showSetupFailure(context: Context, detail: String) {
        val body = context.getString(R.string.boot_setup_do_failed_message, detail)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.boot_setup_do_failed_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.dhizuku_clear_owner_copy) { _, _ ->
                copy(context, "dpm error", body)
            }
            .showHouse()
    }

    private fun copy(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        ShiroikumaToast.show(context, R.string.dhizuku_setup_copied, Toast.LENGTH_SHORT)
    }
}
