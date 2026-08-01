package af.shizuku.manager.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import af.shizuku.manager.ShizukuSettings
import timber.log.Timber
import kotlin.concurrent.thread

/**
 * Keeps the derived accessibility allowlist honest when the set of installed services changes.
 *
 * The platform list is `(every installed accessibility service) − blocklist`, so a package being
 * installed, replaced or removed can make it wrong. Without this, a service installed *after* a
 * block was set would be absent from the permitted list and therefore barred — silently, with
 * nothing anywhere explaining why it will not stay enabled.
 *
 * Best-effort by design: [AccessibilityBlocklist.recomputeIfStale] also runs from the `status` call
 * and from the Settings section, so a broadcast the platform declines to deliver costs freshness,
 * never correctness.
 */
class PolicyPackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (ShizukuSettings.getPreferences() == null) {
            runCatching { ShizukuSettings.initialize(app) }
        }
        // Nothing is derived while the blocklist is empty, so the common case costs one read.
        if (AccessibilityBlocklist.blocked().isEmpty()) return

        // DPM and PackageManager calls are binder round-trips; keep them off the main thread. The
        // work is short and self-contained, so a plain thread beats holding the broadcast open.
        val pending = goAsync()
        thread {
            try {
                AccessibilityBlocklist.recomputeIfStale(app)
            } catch (e: Throwable) {
                Timber.e(e, "recomputing the accessibility allowlist failed")
            } finally {
                pending.finish()
            }
        }
    }
}
