package af.shizuku.manager.utils

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.BuildConfig
import rikka.shizuku.Shizuku
import io.sentry.Sentry
import io.sentry.Breadcrumb

object ShizukuStateMachine {

    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    // Seeded from the last persisted settled state (see the persistence hook in transition()),
    // not a hardcoded STOPPED - a freshly cold-started process (e.g. WatchdogAlarmReceiver reviving
    // the app after an OEM freezer killed it, #417) otherwise has no way to tell "the server was
    // RUNNING when this process died" apart from "the user deliberately stopped it": both would
    // read as the in-memory default, and update() below would silently report STOPPED for an actual
    // crash, so the watchdog's CRASHED-triggered restart would never fire for exactly the case it
    // exists to handle.
    private var state = AtomicReference<State>(loadPersistedSettledState())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    private fun loadPersistedSettledState(): State = try {
        when (ShizukuSettings.getLastSettledState()) {
            State.RUNNING.name -> State.RUNNING
            State.CRASHED.name -> State.CRASHED
            else -> State.STOPPED
        }
    } catch (e: Exception) {
        State.STOPPED
    }

    /**
     * `elapsedRealtime` at which the current transient state (STARTING/STOPPING) was entered, or 0.
     *
     * [update] is a passive observer — it must not clobber a start that is genuinely in flight, so
     * it preserves those two states. Preserving them *unconditionally* is what let a failed start
     * latch permanently: the home card renders STARTING as "Starting…" with the start button
     * disabled, so a start that died left the only control that could retry it switched off for the
     * rest of the process's life. Failure paths call [settle] and resolve at once; this deadline is
     * only the backstop for the ones that die without getting that far.
     *
     * Refreshed on transition only, never on a repeated `set` of the same state — `waitForBinder`
     * polls [update] every 250 ms, and refreshing there would push the deadline out forever.
     */
    private val transientSince = AtomicLong(0L)

    /** Generous: a wireless-adb start retries with backoff and then waits 20s for the binder. */
    private const val STARTING_TIMEOUT_MS = 90_000L
    private const val STOPPING_TIMEOUT_MS = 20_000L

    init {
        Shizuku.addBinderReceivedListenerSticky(
            Shizuku.OnBinderReceivedListener {
                Sentry.addBreadcrumb(Breadcrumb("Binder received - service is now RUNNING").apply {
                    category = "shizuku.service"
                })
                set(State.RUNNING)
            }
        )
        Shizuku.addBinderDeadListener(
            Shizuku.OnBinderDeadListener {
                Sentry.addBreadcrumb(Breadcrumb("Binder dead - service connection lost").apply {
                    category = "shizuku.service"
                    level = io.sentry.SentryLevel.WARNING
                })
                setDead()
            }
        )
    }

    fun get(): State = state.get()

    private fun transition(transform: (State) -> State) {
        // Capture the value our own CAS produced: a separate state.get() after getAndUpdate can
        // observe a concurrent transition's later write, making oldState == newState and silently
        // skipping listener/broadcast side effects for a transition that did occur.
        var computed: State? = null
        val oldState = state.getAndUpdate { current -> transform(current).also { computed = it } }
        val newState = computed!!
        if(oldState != newState) {
            // Only on a real transition — see [transientSince].
            transientSince.set(
                if (newState == State.STARTING || newState == State.STOPPING)
                    SystemClock.elapsedRealtime() else 0L
            )

            listeners.forEach { it(newState) }
            Timber.tag("ShizukuStateMachine").d(newState.toString())

            // Record which app build is starting this server instance. All deliberate start paths
            // (AdbStarter, ShizukuReceiverStarter, tile, StarterActivity) funnel through STARTING,
            // so this captures every fresh start centrally and lets isServerVersionSkewed() later
            // detect a running server left behind by a pre-update build.
            if (newState == State.STARTING) {
                try {
                    ShizukuSettings.setServerStartedBuild(BuildConfig.VERSION_CODE)
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to record server start build")
                }
            }

            if (newState == State.RUNNING || newState == State.STOPPED || newState == State.CRASHED) {
                try {
                    val context = ShizukuApplication.appContext
                    af.shizuku.manager.automation.AutomationEngine.dispatchEvent(
                        af.shizuku.manager.automation.ShizukuStateEvent(newState == State.RUNNING),
                        context
                    )
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to dispatch automation event")
                }

                // Persist so a future cold-started process (see loadPersistedSettledState() above)
                // can tell a crash from a deliberate stop instead of defaulting to "assume stopped".
                try {
                    ShizukuSettings.setLastSettledState(newState.name)
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to persist settled state")
                }
            }

            // Broadcast state change for widgets and other receivers
            try {
                val context = ShizukuApplication.appContext
                val intent = android.content.Intent("af.shizuku.manager.action.STATE_CHANGED").apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(intent)
            } catch (e: UninitializedPropertyAccessException) {
                Timber.tag("ShizukuStateMachine").w("Skipping broadcast: appContext not initialized yet")
            }
        }
    }

    fun set(newState: State) = transition { newState }

    fun setDead() = transition {
        when (it) {
            State.RUNNING -> State.CRASHED
            State.STOPPING -> {
                try {
                    val context = ShizukuApplication.appContext
                    val permissionGranted = context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                    val shouldDisableUsbDebugging = permissionGranted && ShizukuSettings.getAutoDisableUsbDebugging()
                    if (shouldDisableUsbDebugging) {
                        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
                    }
                } catch (e: UninitializedPropertyAccessException) {
                    Timber.tag("ShizukuStateMachine").w("Skipping USB debugging disable: appContext not initialized yet")
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to disable USB debugging")
                }
                State.STOPPED
            }
            else -> it
        }
    }

    /**
     * Re-detect the server, preserving a transition that is still plausibly in flight.
     *
     * This is the *passive* refresh — the one to call from a resume, a poll or a widget rebuild,
     * where reporting STOPPED mid-start would flicker the UI. To decide the outcome of a start or
     * stop you just performed, call [settle] instead: this one deliberately answers STARTING while
     * a start is running, so using it to recover from a failure does nothing at all.
     */
    fun update(): State = evaluate(keepTransient = true)

    /**
     * Resolve a transition now — RUNNING if the binder answers, STOPPED if it does not — whatever
     * STARTING or STOPPING claims.
     *
     * Every path that has just found out a start or stop is over calls this, so the in-flight look
     * ends the moment the attempt does rather than at [transientSince]'s deadline.
     */
    fun settle(): State = evaluate(keepTransient = false)

    private fun evaluate(keepTransient: Boolean): State {
        val span = Sentry.getSpan()?.startChild("ipc.shizuku", "pingBinder")
        val isAlive = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        } finally {
            span?.finish()
        }

        val currentState = get()
        val state = when {
            isAlive -> State.RUNNING
            keepTransient && currentState == State.STARTING && isTransientFresh() -> State.STARTING
            keepTransient && currentState == State.STOPPING && isTransientFresh() -> State.STOPPING
            currentState == State.CRASHED -> State.CRASHED
            // Was RUNNING (or, thanks to loadPersistedSettledState(), a freshly cold-started
            // process that persisted RUNNING before it died) and the binder isn't answering: that's
            // a crash, not a stop. Previously fell into the `else -> STOPPED` branch below, which
            // WatchdogService's flow collector (only listens for CRASHED) silently ignores - the
            // watchdog's external re-arm (#417) never restarted anything because every unexpected
            // death got misreported as an intentional stop.
            currentState == State.RUNNING -> State.CRASHED
            else -> State.STOPPED
        }
        set(state)
        return state
    }

    /** Whether the in-flight state has been held short enough to still be believable. */
    private fun isTransientFresh(): Boolean {
        val since = transientSince.get()
        if (since == 0L) return false
        val timeout =
            if (get() == State.STOPPING) STOPPING_TIMEOUT_MS else STARTING_TIMEOUT_MS
        return SystemClock.elapsedRealtime() - since < timeout
    }

    fun isRunning(): Boolean {
        return get() == State.RUNNING
    }

    /**
     * True when the running privileged server was started by an app build older than the one now
     * installed — i.e. the app was updated but the server (a separate long-lived process) is still
     * running the old code. The binder wire protocol can differ across versions, so this can
     * silently break connections for third-party apps until the service is restarted. Returns false
     * when the starting build is unknown (0, e.g. server predates this tracking) or already current.
     */
    fun isServerVersionSkewed(): Boolean {
        if (!isRunning()) return false
        val startedBuild = ShizukuSettings.getServerStartedBuild()
        return startedBuild in 1 until BuildConfig.VERSION_CODE
    }

    fun isDead(): Boolean {
        return (get() == State.STOPPED || get() == State.CRASHED)
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state.get())
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun asFlow(): Flow<State> = callbackFlow {
        val listener: (State) -> Unit = { trySend(it).isSuccess }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }

}
