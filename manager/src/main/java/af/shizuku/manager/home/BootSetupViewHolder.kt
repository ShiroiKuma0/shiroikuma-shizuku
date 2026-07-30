package af.shizuku.manager.home

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbLoopbackShell
import af.shizuku.manager.adb.AdbPairingService
import af.shizuku.manager.adb.AdbPairingTutorialActivity
import af.shizuku.manager.adb.LocalNetworkPermission
import af.shizuku.manager.admin.DeviceOwnerHelper
import af.shizuku.manager.databinding.HomeBootSetupBinding
import af.shizuku.manager.databinding.HomeBootSetupRowBinding
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.settings.SettingsActivity
import af.shizuku.manager.starter.StarterActivity
import af.shizuku.manager.shiroikuma.ShiroikumaToast
import af.shizuku.manager.shiroikuma.ShiroikumaUiPrefs
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import af.shizuku.manager.utils.PrivilegedShell
import af.shizuku.manager.utils.SettingsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import rikka.core.content.asActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * "Start 白い熊 雫 automatically after boot" — a live checklist rather than a list of instructions.
 *
 * Each row reads the real state and carries its own action, so nothing has to be translated from
 * prose into taps. The reason this is worth a card at all: the boot path
 * (`BootCompleteReceiver` → `ShizukuReceiverStarter.start` → `AdbStartWorker`) has five separate
 * preconditions, four of them invisible, and failing any one of them looks identical from the
 * outside — nothing happens after a reboot.
 *
 * Two of them are worth spelling out because they surprise people:
 *  - `ShizukuReceiverStarter.start` returns silently unless the recorded launch mode is ADB, and
 *    that is only written by [HomeActivity] when it *sees* the service running. So "start it once
 *    with the app open" is a real requirement, not a suggestion.
 *  - The "Start on boot" switch is not the gate it appears to be. `BootCompleteReceiver` is
 *    `android:enabled="true"` in the manifest while `ShizukuSettings.getStartOnBoot` tests for
 *    `COMPONENT_ENABLED_STATE_ENABLED`, which a fresh install never reports — so the receiver
 *    already fires while the switch reads off. The row says so instead of pretending.
 *
 * Device Owner sits below a hairline under its own heading: it is not step 7 of booting, and unlike
 * every row above it, undoing it can dead-end in a factory reset.
 */
class BootSetupViewHolder(
    private val binding: HomeBootSetupBinding,
    private val containerBinding: HomeItemContainerBinding,
    private val scope: CoroutineScope
) : BaseViewHolder<Any?>(containerBinding.root) {

    companion object {
        private const val TAG = "BootSetup"
        private const val REQUEST_POST_NOTIFICATIONS = 4711

        fun creator(scope: CoroutineScope): Creator<Any> {
            return Creator { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeBootSetupBinding.inflate(inflater, outer.cardContent, true)
                BootSetupViewHolder(inner, outer, scope)
            }
        }
    }

    /** Dot appearance per state. Nothing here may rely on a surface fill — every surface role in
     *  this theme is the same pure black, so an unfilled, unstroked dot would simply not exist. */
    private enum class State { DONE, TODO, BLOCKED, INFO }

    private class Step(
        val title: CharSequence,
        val summary: CharSequence,
        val state: State,
        val actionLabel: CharSequence? = null,
        val action: (() -> Unit)? = null
    )

    private val originalIcon = binding.icon.drawable

    /** Set while a privileged command is in flight, so the row can't be fired twice. */
    private var busy = false

    /**
     * The loopback port an adbd answers on, or -1 — found by connecting, not by reading
     * `service.adb.tcp.port`, which an ordinary app is not allowed to see (AdbLoopbackShell).
     * Cached because the probe is a socket connect and [render] runs on the main thread.
     */
    private var loopbackPort = -1

    init {
        containerBinding.root.applySpringTouch()
        containerBinding.root.setOnLongClickListener { HomeEditMode.enter(); true }

        // Both carry a deliberate colour the generic View applier would flatten to body text — it
        // recolours every TextView it walks, and a red warning rendered in the ordinary text colour
        // stops being a warning. Skipping costs them the imported typeface, which is the cheaper loss.
        af.shizuku.manager.shiroikuma.ShiroikumaViewTheme.markSkipped(binding.doHeading)
        af.shizuku.manager.shiroikuma.ShiroikumaViewTheme.markSkipped(binding.doWarning)

        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@BootSetupViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }
    }

    override fun onBind() {
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(binding.icon, originalIcon, "home_boot_setup")
        render()
        probeLoopbackAdb()
    }

    /** Re-renders only when the answer changed, so it can't chase its own tail on every bind. */
    private fun probeLoopbackAdb() {
        scope.launch {
            val port = AdbLoopbackShell.detectPort(context)
            if (port != loopbackPort) {
                loopbackPort = port
                render()
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------------------------

    private fun render() {
        val p = ShiroikumaUiPrefs
        val steps = bootSteps()

        // Collapse to one satisfied line when there is nothing left to do, so the card stops being
        // clutter once it has served its purpose. The unreadable OEM row never counts as outstanding.
        val outstanding = steps.count { it.state == State.TODO || it.state == State.BLOCKED }
        binding.text1.text = context.getString(
            if (outstanding == 0) R.string.boot_setup_all_done else R.string.boot_setup_description
        )

        fill(binding.bootRows, steps)

        binding.sectionHairline.setBackgroundColor(p.getInt(context, p.KEY_COLOR_BORDER))
        binding.doHeading.setTextColor(p.getInt(context, p.KEY_COLOR_HEADING))
        binding.doWarning.setTextColor(ShiroikumaUiPrefs.RED)

        fill(binding.doRows, listOf(deviceOwnerStep()))
        fill(binding.doClearRows, listOf(clearDeviceOwnerStep()))
        // Always shown, including before Device Owner is ever granted: the cost of undoing it is
        // exactly what should inform the decision to do it, so hiding the warning until it applies
        // would withhold it at the only moment it could change anything.
    }

    private fun fill(container: ViewGroup, steps: List<Step>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        steps.forEach { step ->
            val row = HomeBootSetupRowBinding.inflate(inflater, container, true)
            bindRow(row, step)
        }
    }

    private fun bindRow(row: HomeBootSetupRowBinding, step: Step) {
        val p = ShiroikumaUiPrefs
        row.rowTitle.text = step.title
        row.summary.text = step.summary

        val accent = p.getInt(context, p.KEY_COLOR_ACCENT)
        val dim = p.getInt(context, p.KEY_COLOR_TEXT_DIM)
        val minor = p.getInt(context, p.KEY_COLOR_BORDER_MINOR)
        val (dotColor, filled) = when (step.state) {
            State.DONE -> accent to true
            State.TODO -> minor to false
            State.BLOCKED -> ShiroikumaUiPrefs.RED to true
            State.INFO -> dim to false
        }
        row.statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (filled) setColor(dotColor) else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), dotColor)
            }
        }

        // Ordinary list item, so the MINOR border tier. A row with no border would be invisible as a
        // unit here; at width 0 the user has explicitly turned borders off, and that must stay
        // reachable, so the stroke is skipped rather than forced to a minimum.
        val borderWidth = p.getInt(context, p.KEY_CARD_BORDER)
        row.row.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(p.getInt(context, p.KEY_CARD_RADIUS)).toFloat()
            setColor(Color.TRANSPARENT)
            if (borderWidth > 0) setStroke(dp(borderWidth), minor)
        }

        val action = step.action
        if (action == null || step.actionLabel == null) {
            row.rowAction.isVisible = false
        } else {
            row.rowAction.isVisible = true
            row.rowAction.text = if (busy) context.getString(R.string.boot_setup_action_working) else step.actionLabel
            row.rowAction.isEnabled = !busy
            row.rowAction.setOnClickListener { if (!busy) action() }
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // -----------------------------------------------------------------------------------------
    // The reboot checklist
    // -----------------------------------------------------------------------------------------

    private fun bootSteps(): List<Step> {
        val steps = mutableListOf<Step>()

        // 1 — notifications. A hard gate, not a nicety: AdbPairingTutorialActivity does not even
        // call startPairingService() unless notifications are enabled, because the pairing code is
        // entered through the notification's reply action.
        val notificationsOn = areNotificationsEnabled()
        steps += Step(
            title = context.getString(R.string.boot_setup_notifications),
            summary = context.getString(
                if (notificationsOn) R.string.boot_setup_notifications_done
                else R.string.boot_setup_notifications_todo
            ),
            state = if (notificationsOn) State.DONE else State.TODO,
            actionLabel = if (notificationsOn) null else context.getString(R.string.boot_setup_action_allow),
            action = if (notificationsOn) null else ({ requestNotifications() })
        )

        // 2 — one successful connect, which is what records both the port and the launch mode.
        val lastPort = ShizukuSettings.getLastPort()
        val modeIsAdb = ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ADB
        val connected = lastPort in 1..65535 && modeIsAdb
        steps += Step(
            title = context.getString(R.string.boot_setup_connect),
            summary = if (connected) context.getString(R.string.boot_setup_connect_done, lastPort)
            else context.getString(R.string.boot_setup_connect_todo),
            state = if (connected) State.DONE else State.TODO,
            actionLabel = if (connected) null else context.getString(R.string.boot_setup_action_pair),
            action = if (connected) null else ({ startPairing() })
        )

        // 3 — the server itself, and the row where the cable route is spelled out. `adb tcpip 5555`
        // over a cable restarts the *phone's* adbd on a TCP port, so the one thing the PC is needed
        // for is that single command; afterwards the app reaches adb over the loopback with the
        // cable unplugged. StartWirelessAdbViewHolder.start already prefers that system property
        // over the discovered wireless-debugging port, so one action serves both routes.
        val running = Shizuku.pingBinder()
        val adbPort = loopbackPort
        val startablePort = if (adbPort > 0) adbPort else lastPort
        steps += Step(
            title = context.getString(R.string.boot_setup_start),
            summary = when {
                running -> context.getString(R.string.boot_setup_start_done)
                startablePort in 1..65535 -> context.getString(R.string.boot_setup_start_ready, startablePort)
                else -> context.getString(R.string.boot_setup_start_todo)
            },
            state = if (running) State.DONE else State.TODO,
            actionLabel = if (running) null else context.getString(R.string.boot_setup_action_start),
            action = if (running) null else ({ startServer() })
        )

        // 4 — WRITE_SECURE_SETTINGS, which the boot worker needs to switch wireless debugging back
        // on for itself. Granted from here through the running service, or — when there is no
        // service and no root but `adb tcpip` has been run — over the loopback adb connection, which
        // is a shell in its own right. Only with neither is this genuinely blocked.
        val hasSecure = SettingsHelper.hasWriteSecureSettings(context)
        val canGrant = Shizuku.pingBinder() || EnvironmentUtils.isRooted()
        val adbReachable = adbPort in 1..65535
        val canGrantOverAdb = !canGrant && adbReachable
        steps += Step(
            title = context.getString(R.string.boot_setup_secure),
            summary = when {
                hasSecure -> context.getString(R.string.boot_setup_secure_done)
                // Says so when both roads are open, because otherwise the adb fallback is invisible
                // on exactly the devices that have it: the service wins, and nothing on the row
                // hints that it would still work with the service stopped.
                canGrant && adbReachable -> context.getString(R.string.boot_setup_secure_todo_adb, adbPort)
                canGrant -> context.getString(R.string.boot_setup_secure_todo)
                canGrantOverAdb -> context.getString(R.string.boot_setup_secure_adb, adbPort)
                else -> context.getString(R.string.boot_setup_secure_blocked)
            },
            state = when {
                hasSecure -> State.DONE
                canGrant || canGrantOverAdb -> State.TODO
                else -> State.BLOCKED
            },
            actionLabel = if (hasSecure) null else context.getString(
                if (canGrantOverAdb) R.string.boot_setup_action_grant_adb
                else R.string.boot_setup_action_grant
            ),
            action = if (hasSecure) null else ({ grantSecureSettings() })
        )

        // 5 — the switch. See the class comment: the receiver already fires without it, so the row
        // is honest about what flipping it actually buys.
        val startOnBoot = ShizukuSettings.getStartOnBoot(context)
        steps += Step(
            title = context.getString(R.string.boot_setup_boot),
            summary = context.getString(
                if (startOnBoot) R.string.boot_setup_boot_done else R.string.boot_setup_boot_todo
            ),
            state = if (startOnBoot) State.DONE else State.TODO,
            actionLabel = if (startOnBoot) null else context.getString(R.string.boot_setup_action_open),
            action = if (startOnBoot) null else ({ openStartOnBootSetting() })
        )

        // 6 — Doze. The boot start is a WorkManager job, so this is not cosmetic.
        val batteryExempt = SettingsHelper.isIgnoringBatteryOptimizations(context)
        steps += Step(
            title = context.getString(R.string.boot_setup_battery),
            summary = context.getString(
                if (batteryExempt) R.string.boot_setup_battery_done else R.string.boot_setup_battery_todo
            ),
            state = if (batteryExempt) State.DONE else State.TODO,
            actionLabel = if (batteryExempt) null else context.getString(R.string.boot_setup_action_fix),
            action = if (batteryExempt) null else ({
                SettingsHelper.requestIgnoreBatteryOptimizations(context)
            })
        )

        // 7 — background launch, which is two different worlds wearing one name.
        //
        // On a ROM with its own launch manager the binding setting lives in that screen, cannot be
        // read back, and the row can only point at it — no state, by necessity.
        //
        // On a stock-ish ROM there is no such screen and the standard background restriction IS the
        // whole story: it is what "Allow background usage" toggles, it is what stops a boot start,
        // and unlike the OEM screens it can be both read (ActivityManager) and opened. Claiming
        // otherwise there — as this row did on every non-OEM device — is just wrong.
        val oemIntent = launchableOemIntent
        val isEmui = Build.MANUFACTURER.lowercase().let { it == "huawei" || it == "honor" }
        val bgAllowed = if (oemIntent == null && !isEmui) backgroundUsageAllowed() else null
        val bgIntent = backgroundUsageIntent
        steps += Step(
            title = context.getString(R.string.boot_setup_oem),
            summary = context.getString(
                when {
                    oemIntent != null -> R.string.boot_setup_oem_available
                    isEmui -> R.string.boot_setup_oem_emui
                    bgAllowed == true -> R.string.boot_setup_oem_bg_allowed
                    bgAllowed == false -> R.string.boot_setup_oem_bg_blocked
                    else -> R.string.boot_setup_oem_manual
                }
            ),
            state = when (bgAllowed) {
                true -> State.DONE
                false -> State.TODO
                null -> State.INFO
            },
            actionLabel = when {
                oemIntent != null -> context.getString(R.string.boot_setup_action_open)
                bgAllowed == false && bgIntent != null -> context.getString(R.string.boot_setup_action_open)
                else -> null
            },
            action = when {
                oemIntent != null -> ({ openOemLaunchSettings(oemIntent) })
                bgAllowed == false && bgIntent != null -> ({ openOemLaunchSettings(bgIntent) })
                else -> null
            }
        )

        return steps
    }

    /**
     * Whether the system will let this app run in the background, or null when it cannot be read.
     *
     * `isBackgroundRestricted` is exactly the "Allow background usage" toggle under Settings → Apps →
     * 白い熊 雫 → App battery usage: on means unrestricted, off means the system stops background
     * work — including the boot start, which is a WorkManager job.
     */
    private fun backgroundUsageAllowed(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return null
        return runCatching { !am.isBackgroundRestricted }.getOrNull()
    }

    /**
     * Where to send the user for that toggle. A few ROMs expose the battery page directly; most do
     * not (this Motorola does not — `android.settings.APP_BATTERY_SETTINGS` resolves to nothing), and
     * there the app-details page is the right destination because "App battery usage" is one tap
     * inside it. Note this is *not* the old "fall back to app details when the OEM screen was
     * refused" bug: on those ROMs app details genuinely has no launch control, which is why this
     * path is only ever taken when there is no OEM launch manager at all.
     */
    private val backgroundUsageIntent: Intent? by lazy {
        listOf(
            Intent("android.settings.APP_BATTERY_SETTINGS"),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        )
            .map { it.setData(android.net.Uri.parse("package:${context.packageName}")) }
            .firstOrNull { canStart(it) }
    }

    // -----------------------------------------------------------------------------------------
    // Device Owner
    // -----------------------------------------------------------------------------------------

    private fun deviceOwnerStep(): Step {
        val isOwner = DeviceOwnerHelper.isDeviceOwner(context)
        val allowed = !isOwner && DeviceOwnerHelper.isProvisioningAllowed(context)
        return Step(
            title = context.getString(R.string.boot_setup_do),
            summary = context.getString(
                when {
                    isOwner -> R.string.boot_setup_do_active
                    allowed -> R.string.boot_setup_do_todo
                    else -> R.string.boot_setup_do_blocked
                }
            ),
            state = when {
                isOwner -> State.DONE
                allowed -> State.TODO
                else -> State.BLOCKED
            },
            actionLabel = if (isOwner) null else context.getString(R.string.boot_setup_do_action),
            // Offered even when provisioning reports as disallowed: that pre-check is advisory, and
            // dpm's own refusal message is far more useful than our guess at the reason.
            action = if (isOwner) null else ({ makeDeviceOwner() })
        )
    }

    private fun clearDeviceOwnerStep(): Step {
        val isOwner = DeviceOwnerHelper.isDeviceOwner(context)
        return Step(
            title = context.getString(R.string.boot_setup_do_clear),
            summary = context.getString(
                if (isOwner) R.string.boot_setup_do_clear_available else R.string.boot_setup_do_clear_na
            ),
            state = if (isOwner) State.INFO else State.TODO,
            actionLabel = if (isOwner) context.getString(R.string.boot_setup_do_clear_action) else null,
            action = if (isOwner) ({
                DeviceOwnerHelper.confirmAndClear(context) { render() }
            }) else null
        )
    }

    private fun makeDeviceOwner() {
        busy = true
        render()
        scope.launch {
            val outcome = DeviceOwnerHelper.makeDeviceOwner(context)
            busy = false
            when (outcome) {
                is PrivilegedShell.Outcome.Ok -> {
                    if (DeviceOwnerHelper.isDeviceOwner(context)) {
                        ShiroikumaToast.show(context, R.string.boot_setup_do_granted, Toast.LENGTH_LONG)
                    } else {
                        // dpm can exit 0 without the flag landing on some OEM builds; do not claim
                        // success we cannot see.
                        DeviceOwnerHelper.showSetupFailure(context, outcome.output.ifBlank { "dpm reported success, but this app is still not Device Owner." })
                    }
                }
                is PrivilegedShell.Outcome.Failed ->
                    DeviceOwnerHelper.showSetupFailure(context, outcome.output.ifBlank { "dpm exited ${outcome.exitCode}." })
                PrivilegedShell.Outcome.Unavailable -> DeviceOwnerHelper.showSetupCommandDialog(context)
            }
            render()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------------------------

    private fun areNotificationsEnabled(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = nm.getNotificationChannel(AdbPairingService.NOTIFICATION_CHANNEL)
        return nm.areNotificationsEnabled() &&
            (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }

    /** Mirrors the pairing screen: ask for the permission if it is merely ungranted, otherwise the
     *  block is at app or channel level and only the settings page can undo it. */
    private fun requestNotifications() {
        val activity = context.asActivity<android.app.Activity>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            activity != null
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
            return
        }
        af.shizuku.manager.utils.SettingsPage.Notifications.NotificationSettings.launch(context)
    }

    private fun startPairing() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val activity = context.asActivity<FragmentActivity>()
            if (activity != null) {
                activity.startActivity(Intent(context, AdbPairingTutorialActivity::class.java))
                return
            }
        }
        StartWirelessAdbViewHolder.start(context, scope)
    }

    /**
     * Start the server by whichever road is open. Handing the Activity in rather than the row's own
     * context matters: the no-port fallback is a DialogFragment, and a plain cast of a themed
     * context would come back null and make the button do nothing at all.
     */
    private fun startServer() {
        // A probed port beats StartWirelessAdbViewHolder.start's own lookup, which begins with the
        // system property this app cannot read — on the cable route that lookup finds nothing and
        // drops to mDNS discovery, hunting for a wireless service that isn't there.
        val port = loopbackPort
        if (port in 1..65535) {
            val intent = Intent(context, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_PORT, port)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { Timber.tag(TAG).w(it, "Failed to open the starter on port $port") }
            return
        }
        val activity = context.asActivity<FragmentActivity>()
        StartWirelessAdbViewHolder.start(activity ?: context, scope)
    }

    /**
     * Grant `WRITE_SECURE_SETTINGS`, trying every shell this device can offer before giving up.
     *
     * The order is service/root → loopback adb → the copy-this-command dialog, and the fall-through
     * is the whole point: an earlier tier reporting failure is not evidence the next one will fail.
     * Treating the tiers as alternatives instead of a chain is what put the dialog on screen while a
     * perfectly good adb shell sat listening on 5555 — the service tier failed (it was returning a
     * null remote process, see the Service.onTransact fix) and nothing tried the next road.
     *
     * The permission held is the only success signal that means anything: `pm grant` can exit 0
     * without granting, so every tier is judged by re-checking rather than by its own exit code.
     */
    private fun grantSecureSettings() {
        val command = "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
        val port = loopbackPort

        // A loopback socket is still local-network access under Android 16+ LNP, so ask for it in
        // the same best-effort way the ADB start screen does. The connect retries give the user time
        // to answer both this and adbd's own "Allow USB debugging" prompt.
        if (port in 1..65535) {
            context.asActivity<android.app.Activity>()?.let { LocalNetworkPermission.request(it) }
        }

        busy = true
        render()
        scope.launch {
            var reason: String? = null

            if (Shizuku.pingBinder() || EnvironmentUtils.isRooted()) {
                reason = when (val outcome = PrivilegedShell.run(command)) {
                    is PrivilegedShell.Outcome.Ok ->
                        outcome.output.ifBlank { "the privileged shell reported success without granting it" }
                    is PrivilegedShell.Outcome.Failed ->
                        outcome.output.ifBlank { "pm grant exited ${outcome.exitCode}" }
                    PrivilegedShell.Outcome.Unavailable ->
                        "the privileged service did not run the command"
                }
            }

            if (!SettingsHelper.hasWriteSecureSettings(context) && port in 1..65535) {
                reason = when (val outcome = AdbLoopbackShell.run(context, port, command)) {
                    is AdbLoopbackShell.Outcome.Ok ->
                        outcome.output.ifBlank { "adb ran the command without granting it" }
                    is AdbLoopbackShell.Outcome.Failed -> "adb on port $port: ${outcome.reason}"
                    AdbLoopbackShell.Outcome.Unavailable -> "adb on port $port is no longer reachable"
                }
            }

            busy = false
            if (!SettingsHelper.hasWriteSecureSettings(context)) {
                // Never silent: the tiers each swallow their own exception, so without this the
                // failure is invisible in a release build, where Timber plants no tree.
                if (reason != null) ShiroikumaToast.show(context, reason, Toast.LENGTH_LONG)
                SettingsHelper.promptWriteSecureSettings(context)
            }
            render()
        }
    }

    /**
     * The ROM-specific "autostart / background launch" screens, in the order we would rather have
     * them. Android has no standard action for this — every OEM invented its own screen — so a
     * candidate list is the only way, and [canStart] decides which of them this device will accept.
     *
     * EMUI's two are deliberately in the list even though they are known to be refused here: the
     * check is a real capability test, not a brand check, so if Huawei ever drops the permission
     * guard the button starts working with no code change. The same holds in reverse — an OEM that
     * tightens its screen loses the button instead of gaining a crash.
     *
     * Computed once per view holder: this is ~10 PackageManager round trips and rows re-render on
     * every bind, while the answer cannot change without an OS update.
     */
    private val launchableOemIntent: Intent? by lazy {
        listOf(
            // MIUI / HyperOS
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // ColorOS / realme UI
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            // Funtouch / OriginOS
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            // OxygenOS
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            // One UI — "never sleeping apps" lives in Device care's battery screen
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            // EUI, ZenUI
            "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity",
            // EMUI / Magic UI — present, permission-guarded, kept for the reason above
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        )
            .map { (pkg, cls) -> Intent().setComponent(ComponentName(pkg, cls)) }
            .firstOrNull { canStart(it) }
    }

    /**
     * Whether this app could actually start [intent] — it resolves, it is exported, and any
     * permission guarding it is one we hold.
     *
     * Resolving alone is not enough, and assuming it is caused the bug this replaces: EMUI's
     * App-launch screen resolves perfectly and then throws `SecurityException` on start, because it
     * is guarded by `com.huawei.permission.external_app_settings.USE_COMPONENT`
     * (`signature|privileged`, so unobtainable). The old code caught that and fell through to the
     * app-details page, which has no launch control on it — a button that went somewhere useless.
     */
    private fun canStart(intent: Intent): Boolean {
        val info = runCatching {
            context.packageManager.resolveActivity(intent, 0)?.activityInfo
        }.getOrNull() ?: return false
        if (!info.exported) return false
        // An activity with no permission of its own inherits the application-level one, so both have
        // to be consulted before calling this startable.
        val required = info.permission ?: info.applicationInfo?.permission ?: return true
        return context.checkSelfPermission(required) == PackageManager.PERMISSION_GRANTED
    }

    private fun openOemLaunchSettings(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            Timber.tag(TAG).w(it, "OEM launch manager refused to start")
            ShiroikumaToast.show(context, R.string.boot_setup_oem_manual, Toast.LENGTH_LONG)
        }
    }

    private fun openStartOnBootSetting() {
        val intent = SettingsActivity.behaviorSettingsIntent(context, ShizukuSettings.Keys.KEY_START_ON_BOOT)
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.tag(TAG).w(it, "Failed to open Startup & Behavior settings") }
    }

}
