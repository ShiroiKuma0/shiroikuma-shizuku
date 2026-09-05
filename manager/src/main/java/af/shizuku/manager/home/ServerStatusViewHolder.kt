package af.shizuku.manager.home

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import rikka.core.content.asActivity
import androidx.core.content.ContextCompat
import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeServerStatusBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.model.ServiceStatus
import rikka.html.text.HtmlCompat
import rikka.html.text.toHtml
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import af.shizuku.manager.shiroikuma.showHouse
import kotlinx.coroutines.launch

class ServerStatusViewHolder(
    private val binding: HomeServerStatusBinding,
    root: View,
    private val scope: kotlinx.coroutines.CoroutineScope
) : BaseViewHolder<ServiceStatus>(root) {

    private val cardView: com.google.android.material.card.MaterialCardView = itemView as com.google.android.material.card.MaterialCardView

    companion object {
        private const val TAG = "ServerStatus"

        /**
         * Whether a start/stop is in flight, and what to call it.
         *
         * **Process-global on purpose.** This was a field on the view holder, and the holder is
         * recreated whenever the adapter rebuilds its list — which a state change itself triggers. So
         * the "busy" state was wiped almost immediately, the buttons came back enabled, and a second
         * tap landed on top of the first while the only sign of progress was a toast arriving seconds
         * later. Keeping it here means a rebind *restores* the in-flight look instead of clearing it.
         */
        @Volatile
        private var inFlightLabel: Int? = null

        fun creator(scope: kotlinx.coroutines.CoroutineScope) = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeServerStatusBinding.inflate(inflater, outer.cardContent, true)
            ServerStatusViewHolder(inner, outer.root, scope)
        }
    }

    private var prevBgColor: Int? = null

    init {
        cardView.applySpringTouch()
    }

    private inline val textView get() = binding.text1
    private inline val summaryView get() = binding.text2
    private inline val iconView get() = binding.icon
    private inline val logChip get() = binding.btnActivityLog
    private inline val diagnosticsChip get() = binding.btnDiagnostics
    private inline val statusIndicator get() = binding.statusIndicator

    override fun onBind() {
        val context = itemView.context
        val status = data
        val ok = status.isRunning
        val state = af.shizuku.manager.utils.ShizukuStateMachine.get()

        // Live Status Indicator
        statusIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(
            when {
                ok -> ContextCompat.getColor(context, R.color.status_ok)
                state == af.shizuku.manager.utils.ShizukuStateMachine.State.STARTING -> ContextCompat.getColor(context, R.color.status_starting)
                else -> ContextCompat.getColor(context, R.color.status_error)
            }
        )

        // Pulse animation for Starting/Running state
        if (state == af.shizuku.manager.utils.ShizukuStateMachine.State.STARTING || ok) {
            val pulse = android.view.animation.AlphaAnimation(0.4f, 1.0f).apply {
                duration = if (ok) 1500 else 600
                repeatMode = android.view.animation.Animation.REVERSE
                repeatCount = android.view.animation.Animation.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }
            statusIndicator.startAnimation(pulse)
        } else {
            statusIndicator.clearAnimation()
        }

        // S-Pen / DeX Mouse Hover Effect (Expressive Polish)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            itemView.setOnHoverListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_HOVER_ENTER -> {
                        v?.animate()
                            ?.scaleX(1.015f)
                            ?.scaleY(1.015f)
                            ?.translationZ(6f)
                            ?.setDuration(af.shizuku.manager.ShizukuSettings.scaledAnimationDuration(150))
                            ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                            ?.start()
                        true
                    }
                    android.view.MotionEvent.ACTION_HOVER_EXIT -> {
                        v?.animate()
                            ?.scaleX(1f)
                            ?.scaleY(1f)
                            ?.translationZ(0f)
                            ?.setDuration(af.shizuku.manager.ShizukuSettings.scaledAnimationDuration(150))
                            ?.setInterpolator(android.view.animation.AccelerateInterpolator())
                            ?.start()
                        true
                    }
                    else -> false
                }
            }
        }

        logChip.visibility = if (ok && af.shizuku.manager.ShizukuSettings.showActivityLogHome()) View.VISIBLE else View.GONE
        logChip.setOnClickListener {
            val activity = context.asActivity<android.app.Activity>() ?: return@setOnClickListener
            activity.startWithSceneTransition(
                android.content.Intent(activity, af.shizuku.manager.activitylog.ActivityLogActivity::class.java),
                iconView, "icon_server_status"
            )
        }

        diagnosticsChip.visibility = if (ok) View.VISIBLE else View.GONE
        diagnosticsChip.setOnClickListener {
            val activity = context.asActivity<android.app.Activity>() ?: return@setOnClickListener
            activity.startActivity(android.content.Intent(activity, SystemHubActivity::class.java))
        }

        val okColorAttr = if (ok) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorErrorContainer
        val onColorAttr = if (ok) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnErrorContainer

        val bgColor = com.google.android.material.color.MaterialColors.getColor(
            context, okColorAttr,
            com.google.android.material.color.MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorSurfaceContainerHigh, android.graphics.Color.TRANSPARENT
            )
        )
        val textColor = com.google.android.material.color.MaterialColors.getColor(
            context, onColorAttr,
            com.google.android.material.color.MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK
            )
        )

        // Animate background color transition when service state changes (running ↔ stopped)
        val prevBg = prevBgColor
        prevBgColor = bgColor
        if (prevBg != null && prevBg != bgColor) {
            android.animation.ValueAnimator.ofArgb(prevBg, bgColor).apply {
                duration = af.shizuku.manager.ShizukuSettings.scaledAnimationDuration(450)
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { cardView.setCardBackgroundColor(it.animatedValue as Int) }
            }.start()
        } else {
            cardView.setCardBackgroundColor(bgColor)
        }

        // Status-aware stroke — echoes the live indicator dot to add visual depth to the hero card
        val strokeDp = if (ok || state == af.shizuku.manager.utils.ShizukuStateMachine.State.STARTING) 1.5f else 0f
        cardView.strokeWidth = (strokeDp * context.resources.displayMetrics.density + 0.5f).toInt()
        cardView.strokeColor = when {
            ok -> ContextCompat.getColor(context, R.color.status_ok)
            state == af.shizuku.manager.utils.ShizukuStateMachine.State.STARTING -> ContextCompat.getColor(context, R.color.status_starting)
            else -> android.graphics.Color.TRANSPARENT
        }

        textView.setTextColor(textColor)
        summaryView.setTextColor(textColor)
        // Same colour as the summary: the card's background is state-tinted, so a theme-attr colour
        // set in XML would drift against it.
        binding.serverInfo.setTextColor(textColor)
        bindStaleWarning(context)
        logChip.setTextColor(textColor)
        logChip.chipIconTint = android.content.res.ColorStateList.valueOf(textColor)
        diagnosticsChip.setTextColor(textColor)
        diagnosticsChip.chipIconTint = android.content.res.ColorStateList.valueOf(textColor)

        // Icon pill uses vivid semantic role colors so it stands out against the card's lighter
        // container background — matching pill-to-card was invisible in users' issue screenshots.
        val (iconPillColor, iconOnPillColor) = when {
            ok -> {
                com.google.android.material.color.MaterialColors.getColor(
                    context, R.attr.colorPrimary, android.graphics.Color.TRANSPARENT
                ) to com.google.android.material.color.MaterialColors.getColor(
                    context, com.google.android.material.R.attr.colorOnPrimary, android.graphics.Color.WHITE
                )
            }
            state == af.shizuku.manager.utils.ShizukuStateMachine.State.STARTING -> {
                ContextCompat.getColor(context, R.color.status_starting) to android.graphics.Color.WHITE
            }
            else -> {
                com.google.android.material.color.MaterialColors.getColor(
                    context, R.attr.colorError, android.graphics.Color.RED
                ) to com.google.android.material.color.MaterialColors.getColor(
                    context, com.google.android.material.R.attr.colorOnError, android.graphics.Color.WHITE
                )
            }
        }
        af.shizuku.manager.utils.IconStyleHelper.applyToStatusCardIcon(iconView, pillColor = iconPillColor, tintColor = iconOnPillColor)

        val isRoot = status.uid == 0
        val apiVersion = status.apiVersion
        val patchVersion = status.patchVersion
        if (ok) {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_ok_24))
        } else {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_error_24))
        }
        val user = if (isRoot) context.getString(R.string.home_status_service_user_root) else context.getString(R.string.home_status_service_user_adb)
        val title = if (ok) {
            context.getString(R.string.home_status_service_is_running, context.getString(R.string.app_name))
        } else {
            context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
        }
        val summary = if (ok) {
            // patchVersion is -1 when unknown (not yet delivered / not supported by the server);
            // 0 is a legitimate patch value. Don't claim the server is outdated based on an unknown
            // patch, or the "restart to update" prompt shows spuriously.
            val patchKnown = patchVersion >= 0
            val versionText = if (patchKnown) "${apiVersion}.${patchVersion}" else "$apiVersion"
            if (apiVersion != Shizuku.getLatestServiceVersion() || (patchKnown && patchVersion != ShizukuApiConstants.SERVER_PATCH_VERSION)) {
                context.getString(
                    R.string.home_status_service_version_update, user,
                    versionText,
                    "${Shizuku.getLatestServiceVersion()}.${ShizukuApiConstants.SERVER_PATCH_VERSION}"
                )
            } else {
                context.getString(R.string.home_status_service_version, user, versionText)
            }
        } else {
            context.getString(R.string.home_status_service_not_running_summary, context.getString(R.string.app_name))
        }
        textView.text = title.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        summaryView.text = summary.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        summaryView.visibility = if (TextUtils.isEmpty(summaryView.text)) View.GONE else View.VISIBLE

        bindServerControls(context, ok, state)
    }

    /**
     * Start / Stop, with the first button doubling as **Restart** while a server is running.
     *
     * Restart is the case that matters: after updating the app, the *old* server is still the one
     * running, and its version skew silently breaks clients. It is also the case that dictates the
     * design — see [startOrRestart] for why this cannot be stop-then-start.
     */
    /**
     * The persistent half of the stale-server warning.
     *
     * The dialog asks once per update and can be dismissed; this line stays for as long as the
     * condition holds, so a "Later" does not make the problem invisible. Red and
     * [ShiroikumaViewTheme.markSkipped] for the same reason as every other warning in this app —
     * the generic View applier recolours every TextView it walks, and a warning in the ordinary
     * text colour has stopped being one.
     */
    private fun bindStaleWarning(context: android.content.Context) {
        val view = binding.serverStale
        val skewed = af.shizuku.manager.utils.ShizukuStateMachine.isServerVersionSkewed()
        val unverified = af.shizuku.manager.utils.ShizukuStateMachine.isServerVersionUnverified()
        if (!skewed && !unverified) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.setText(if (skewed) R.string.home_status_server_stale else R.string.home_status_server_unverified)
        view.setTextColor(af.shizuku.manager.shiroikuma.ShiroikumaUiPrefs.RED)
        af.shizuku.manager.shiroikuma.ShiroikumaViewTheme.markSkipped(view)
    }

    private fun bindServerControls(
        context: android.content.Context,
        running: Boolean,
        state: af.shizuku.manager.utils.ShizukuStateMachine.State
    ) {
        val p = af.shizuku.manager.shiroikuma.ShiroikumaUiPrefs
        val accent = p.getInt(context, p.KEY_COLOR_ACCENT)
        val density = context.resources.displayMetrics.density

        val startButton = binding.btnServerStart
        val stopButton = binding.btnServerStop

        listOf(startButton, stopButton).forEach { button ->
            button.setTextColor(accent)
            button.iconTint = android.content.res.ColorStateList.valueOf(accent)
            button.strokeColor = android.content.res.ColorStateList.valueOf(accent)
            button.strokeWidth = (p.getInt(context, p.KEY_CARD_BORDER).coerceAtLeast(1) * density).toInt()
        }
        binding.serverProgress.setIndicatorColor(accent)
        binding.serverProgress.trackColor = p.getInt(context, p.KEY_COLOR_BORDER_MINOR)

        // Idle labels; applyInFlight() overrides them while an operation is running.
        startButton.setText(if (running) R.string.home_status_action_restart else R.string.home_status_action_start)
        stopButton.setText(R.string.home_status_action_stop)
        stopButton.visibility = if (running) View.VISIBLE else View.GONE

        startButton.setOnClickListener { if (inFlightLabel == null) startOrRestart(context) }

        // Publish the ONE restart implementation so the version-skew dialog can drive it instead of
        // carrying a second copy of a routine whose ordering, if got wrong, strands the phone.
        ServerRestartRequest.request = { if (inFlightLabel == null) startOrRestart(context) }
        stopButton.setOnClickListener { if (inFlightLabel == null) stopServer(context) }

        // Re-applied on every bind so a list rebuild mid-operation restores the in-flight look rather
        // than resetting the buttons to "tap me again". Also covers the state-machine transitions that
        // originate outside this card (the watchdog, a boot start, the WADB flow).
        val externalTransition = when (state) {
            af.shizuku.manager.utils.ShizukuStateMachine.State.STARTING -> R.string.home_status_starting
            af.shizuku.manager.utils.ShizukuStateMachine.State.STOPPING -> R.string.home_status_stopping
            else -> null
        }
        applyInFlight(inFlightLabel ?: externalTransition)
    }

    /**
     * The whole of the in-flight look, applied **synchronously**.
     *
     * Called straight from the click listener before any suspending work starts, so the feedback is
     * immediate rather than waiting on a rebind that the adapter throttles and coalesces — which is
     * what made the button look inert for seconds while the restart was already running.
     *
     * [label] null means idle.
     */
    private fun applyInFlight(label: Int?) {
        val startButton = binding.btnServerStart
        val stopButton = binding.btnServerStop
        val busy = label != null

        binding.serverProgress.visibility = if (busy) View.VISIBLE else View.GONE
        startButton.isEnabled = !busy
        stopButton.isEnabled = !busy
        if (busy) {
            startButton.setText(label!!)
        } else {
            startButton.setText(
                if (af.shizuku.manager.utils.ShizukuStateMachine.isRunning()) R.string.home_status_action_restart
                else R.string.home_status_action_start
            )
        }
    }

    /** Enter the in-flight state now, from the click, and remember it across rebinds. */
    private fun beginOperation(label: Int) {
        inFlightLabel = label
        applyInFlight(label)
    }

    private fun endOperation() {
        inFlightLabel = null
        applyInFlight(null)
    }

    /**
     * Start the server, or replace a running one.
     *
     * **Why this is one action and not stop-then-start.** Without root, the only shell this app can
     * reach is the one the *running server* lends it through `Shizuku.newProcess`. Stopping first
     * would destroy exactly the privilege needed to start again, leaving no way back except wireless
     * debugging. So the starter is executed *through* the live server, and the starter itself
     * displaces the old process.
     *
     * **Why the exit code is ignored.** Killing the old server also kills the remote process that was
     * carrying this command, so a successful restart frequently reports a non-zero exit or a dead
     * binder. The only trustworthy signal is whether a server is running afterwards, so the outcome is
     * decided by [Starter.waitForBinder] and a state re-check rather than by the shell's verdict.
     */
    private fun startOrRestart(context: android.content.Context) {
        val label = if (af.shizuku.manager.utils.ShizukuStateMachine.isRunning())
            R.string.home_status_restarting else R.string.home_status_starting
        beginOperation(label)
        af.shizuku.manager.utils.ShizukuStateMachine.set(af.shizuku.manager.utils.ShizukuStateMachine.State.STARTING)
        scope.launch {
            // finally, not a flag reset per branch: an exception anywhere below would otherwise leave
            // the buttons disabled and the bar spinning for the rest of the process's life.
            try {
            val outcome = af.shizuku.manager.utils.PrivilegedShell.run(
                af.shizuku.manager.starter.Starter.internalCommand
            )

            if (outcome is af.shizuku.manager.utils.PrivilegedShell.Outcome.Unavailable) {
                // No usable shell from a running server, and no root. Before falling back to the
                // wireless-debugging *pairing* flow, try local TCP adb — which is not a WiFi feature.
                //
                // Once adbd is in TCP mode it listens on 127.0.0.1, and this app can drive it with its
                // own stored key over a plain cable with no WiFi, no pairing and no PC in the loop.
                // That is the path that matters on the Mate XT, where the wireless route is the
                // awkward one. It is also the only way out of the deadlock after an update: the
                // manager cannot get a shell from a server whose attach handling is the very thing
                // being fixed, so the replacement has to be launched from outside that server.
                //
                // What this app CANNOT do is speak to adbd over USB — that endpoint is USB functionfs,
                // not a socket any app can dial. So adbd has to have been put in TCP mode once; if it
                // has not, say so with the exact command rather than dumping the user into pairing.
                val sysPropPort = af.shizuku.manager.utils.EnvironmentUtils.getAdbTcpPort()
                val lastPort = af.shizuku.manager.ShizukuSettings.getLastPort()
                val localPort = when {
                    sysPropPort in 1..65535 -> sysPropPort
                    lastPort in 1..65535 -> lastPort
                    else -> -1
                }

                if (localPort <= 0) {
                    // Dead end, and the STARTING set above has to be undone explicitly: update()
                    // keeps a transient state, so it would leave this card reading "Starting…" with
                    // its own start button disabled — and the dialog we are about to show asks the
                    // user to go run `adb tcpip 5555` and come back to press exactly that button.
                    af.shizuku.manager.utils.ShizukuStateMachine.settle()
                    showEnableTcpAdbDialog(context)
                    return@launch
                }

                val started = runCatching {
                    af.shizuku.manager.adb.AdbStarter.startAdb(context, localPort)
                }
                runCatching { af.shizuku.manager.starter.Starter.waitForBinder() }
                if (af.shizuku.manager.utils.ShizukuStateMachine.settle() ==
                    af.shizuku.manager.utils.ShizukuStateMachine.State.RUNNING
                ) {
                    af.shizuku.manager.shiroikuma.ShiroikumaToast.show(
                        context, R.string.home_status_restart_ok, android.widget.Toast.LENGTH_SHORT
                    )
                } else {
                    // A refused connection here almost always means adbd has this app's key but the
                    // authorisation was revoked, or adbd left TCP mode — both are the dialog's subject.
                    timber.log.Timber.tag(TAG).w(started.exceptionOrNull(), "Local TCP adb start failed on port %d", localPort)
                    showEnableTcpAdbDialog(context)
                }
                return@launch
            }

            runCatching { af.shizuku.manager.starter.Starter.waitForBinder() }
            val nowRunning = af.shizuku.manager.utils.ShizukuStateMachine.settle() ==
                af.shizuku.manager.utils.ShizukuStateMachine.State.RUNNING

            if (nowRunning) {
                af.shizuku.manager.shiroikuma.ShiroikumaToast.show(
                    context, R.string.home_status_restart_ok, android.widget.Toast.LENGTH_SHORT
                )
            } else {
                val detail = when (outcome) {
                    is af.shizuku.manager.utils.PrivilegedShell.Outcome.Failed -> outcome.output
                    is af.shizuku.manager.utils.PrivilegedShell.Outcome.Ok -> outcome.output
                    else -> ""
                }
                timber.log.Timber.tag(TAG).w("Restart did not yield a running server: %s", detail)
                af.shizuku.manager.shiroikuma.ShiroikumaToast.show(
                    context,
                    context.getString(R.string.home_status_restart_failed, detail),
                    android.widget.Toast.LENGTH_LONG
                )
            }
            } finally {
                endOperation()
            }
        }
    }

    /**
     * Offered when no shell is reachable: the one-time PC command that puts adbd into TCP mode.
     *
     * After it runs, adbd listens on 127.0.0.1:5555 and this app can start the server itself over a
     * cable with no WiFi and no pairing — so this dialog is what converts "wireless only" into "works
     * on the cable". The wireless pairing flow stays available as the second button for anyone who has
     * no PC to hand.
     */
    private fun showEnableTcpAdbDialog(context: android.content.Context) {
        val command = "adb tcpip 5555"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.home_status_tcp_adb_title)
            .setMessage(context.getString(R.string.home_status_tcp_adb_message, command))
            .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("adb command", command))
                af.shizuku.manager.shiroikuma.ShiroikumaToast.show(
                    context, R.string.toast_copied_to_clipboard, android.widget.Toast.LENGTH_SHORT
                )
            }
            .setNeutralButton(R.string.home_status_tcp_adb_use_wireless) { _, _ ->
                StartWirelessAdbViewHolder.start(context, scope)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showHouse()
    }

    /** `exit()` only enforces the manager permission, which we hold — so this needs no shell at all. */
    private fun stopServer(context: android.content.Context) {
        beginOperation(R.string.home_status_stopping)
        af.shizuku.manager.receiver.ShizukuReceiverStarter.stop()
        scope.launch {
            try {
                // exit() is System.exit(0) on the far side; the binder death takes a moment to land.
                kotlinx.coroutines.delay(600)
                if (af.shizuku.manager.utils.ShizukuStateMachine.settle() !=
                    af.shizuku.manager.utils.ShizukuStateMachine.State.RUNNING
                ) {
                    af.shizuku.manager.shiroikuma.ShiroikumaToast.show(
                        context, R.string.home_status_stop_ok, android.widget.Toast.LENGTH_SHORT
                    )
                }
            } finally {
                endOperation()
            }
        }
    }
}
