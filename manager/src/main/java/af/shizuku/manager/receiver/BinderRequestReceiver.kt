package af.shizuku.manager.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.legacy.ShellConsentActivity
import af.shizuku.manager.shell.PendingConsentStore
import af.shizuku.manager.shell.ShellBinderRequestHandler
import af.shizuku.manager.shell.ShellConsentActionReceiver
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.utils.IntentCrypto
import java.security.MessageDigest
import timber.log.Timber

class BinderRequestReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "shell_consent"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER" &&
            intent.action != "${context.packageName}.intent.action.REQUEST_BINDER") {
            return
        }

        val rawToken = intent.getStringExtra("auth")
        val authToken = if (rawToken != null) IntentCrypto.decrypt(rawToken) else null
        val expectedToken = ShizukuSettings.getAuthToken()
        // Constant-time compare: this gates handing out the live Shizuku binder.
        val authValid = authToken != null &&
            MessageDigest.isEqual(authToken.toByteArray(), expectedToken.toByteArray())

        if (authValid) {
            // deliverBinder() may Thread.sleep() up to 2.3 s on freeze-retry — move off main thread.
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ShellBinderRequestHandler.handleRequest(context, intent, requireAuth = true)
                } finally {
                    pending.finish()
                }
            }
            return
        }

        // No/invalid auth token - the path every rish/shell client takes today, since
        // IntentCrypto's AndroidKeyStore key is scoped to this app's own UID and a shell
        // process can never produce a token that decrypts correctly (GH #368/#372/#374).
        // Ask the user for one-time consent instead of silently dropping the request, but
        // only if there's a live callback binder to reply to - otherwise there's nothing
        // to grant access to.
        val callbackBinder = intent.getBundleExtra("data")?.getBinder("binder") ?: return
        val callingPackage = intent.getStringExtra("callingPackage")
        // intentCallingUid is set by ShizukuShellLoader (Os.getuid()) — authoritative
        // fallback when PackageManager.getApplicationInfo() is unavailable (e.g. classic
        // rish_shizuku.dex omits callingPackage, or PM lookup fails on some devices #391).
        val intentCallingUid = intent.getIntExtra("callingUid", -1).takeIf { it >= 0 }
        // Prefer PM-derived UID (verifies callingPackage ownership); fall back to intent UID.
        val effectiveUid: Int? = if (callingPackage != null) {
            try { context.packageManager.getApplicationInfo(callingPackage, 0).uid }
            catch (_: Exception) { intentCallingUid }
        } else intentCallingUid

        // Two consent gates, deliberately in this order (白い熊, 2026-08-08).
        //
        // First, upstream's per-package one: if this exact caller is already permanently
        // authorized, deliver directly (#398 — "Allow always" was not persisting because this
        // check was absent; every new rish process re-prompted even though
        // AuthorizationManager.grant() had already been stored for that UID). It is the narrower
        // and more informative of the two, so it answers first and names the app in the log.
        if (effectiveUid != null) {
            try {
                if (AuthorizationManager.granted(callingPackage ?: "", effectiveUid)) {
                    ActivityLogManager.log(
                        callingPackage?.let { appLabelOf(context, it) ?: it } ?: effectiveUid.toString(),
                        callingPackage ?: "",
                        "Shell: binder delivered (pre-authorized)"
                    )
                    deliverAsync(context, callbackBinder)
                    return
                }
            } catch (_: Exception) {
                // Check failed — fall through to the fork gate, then the notification path.
            }
        }

        // Then the fork's global one. Upstream re-asks on every request from a caller it holds no
        // per-package grant for, because the auth token it would otherwise remember can never
        // exist for a shell client - so `rish -c ls` put a full-screen dialog in front of every
        // single command, and an *unidentified* caller (no callingPackage, so the gate above can
        // never fire for it) is still in exactly that position. A remembered answer is the whole
        // point of a consent prompt; without it the prompt is just a tax. Revocable from
        // Settings → Advanced → ADB Tools, which is what makes granting it safe to offer.
        if (ShizukuSettings.isShellConsentGranted()) {
            // The remembered answer skips ShellConsentActivity entirely, so upstream's #391
            // pre-grant never runs for a caller that first appears AFTER the flag was set — and
            // attachApplication() would then put the second permission dialog back in front of it,
            // which is exactly what #391 removed. Do the same grant here, on the same terms — and
            // on the same UID chain, so a caller PackageManager cannot resolve is still granted
            // rather than silently skipped.
            //
            // That grant is also what promotes an identified caller into the gate above, so this
            // path answers for it once and the per-package gate answers every time after.
            grantIdentifiedCaller(context, callingPackage, effectiveUid)
            ActivityLogManager.log(
                callingPackage?.let { appLabelOf(context, it) } ?: "Shell",
                callingPackage ?: "",
                "Shell: binder delivered (consent remembered)"
            )
            deliverAsync(context, callbackBinder)
            return
        }

        // A manifest-registered BroadcastReceiver has no visible UI, so a direct
        // startActivity() here is exactly the pattern Android's background-activity-start
        // (BAL) restrictions are designed to block - on modern OEM builds (e.g. Samsung
        // One UI) it is silently dropped, ShellConsentActivity never appears, and
        // ShizukuShellLoader's 15s timeout fires with a misleading "may be blocked by your
        // system / disable battery optimization" message (#377). Route through a
        // notification instead: tapping it is a user-initiated foreground action and is
        // exempt from BAL, so the consent dialog reliably shows up.
        //
        // Android 15+ (API 35) does not reliably preserve IBinder objects embedded in
        // PendingIntent extras — the binder arrives null when the notification fires (#387).
        // Store it in PendingConsentStore and pass only a lightweight key in the intent.
        //
        // All three compose: either gate above means this notification is posted once, not
        // before every command.
        postConsentNotification(context, intent, callbackBinder, intentCallingUid)
    }

    /**
     * deliverBinder() walks a freeze-retry ladder and can Thread.sleep() up to 2.3 s, which would
     * block the main thread for the whole broadcast window when the rish process is frozen. Hold
     * the broadcast open with goAsync() and do it on IO instead.
     */
    private fun deliverAsync(context: Context, callbackBinder: IBinder) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ShellBinderRequestHandler.deliverBinder(context, callbackBinder)
            } finally {
                pending.finish()
            }
        }
    }

    /** The caller's display name, or null when it cannot be resolved (unknown or uninstalled). */
    private fun appLabelOf(context: Context, callingPackage: String): String? = try {
        val info = context.packageManager.getApplicationInfo(callingPackage, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        null
    }

    /**
     * Mirrors [af.shizuku.manager.legacy.ShellConsentActivity]'s pre-grant for the path that never
     * reaches it. A null package just means an unidentified shell client, which is the case the
     * generic consent copy already describes — deliver the binder anyway, exactly as before; only
     * the second-dialog suppression is lost, which is where upstream was.
     *
     * [effectiveUid] is the same chain the gates above use — PackageManager first, then the UID
     * ShizukuShellLoader put in the broadcast. A grant still needs a package name to key on, so an
     * anonymous caller is skipped no matter how well-known its UID is.
     */
    private fun grantIdentifiedCaller(context: Context, callingPackage: String?, effectiveUid: Int?) {
        if (callingPackage == null || effectiveUid == null) return
        try {
            AuthorizationManager.grant(callingPackage, effectiveUid)
        } catch (e: Exception) {
            Timber.tag("BinderRequestReceiver").w(e, "Could not pre-grant %s", callingPackage)
        }
    }

    private fun postConsentNotification(context: Context, intent: Intent, callbackBinder: IBinder, intentCallingUid: Int? = null) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            // notify() below would silently no-op without this - same symptom as the original
            // BAL-blocked startActivity (#377) with no visible cause. Home screen now requests
            // POST_NOTIFICATIONS proactively, but log this so a still-blocked case is diagnosable
            // instead of reproducing the exact same "mysteriously still times out" report.
            Timber.tag("BinderRequestReceiver").w(
                "Notifications disabled for %s - shell consent request will silently fail to display",
                context.packageName
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_shell_consent),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        // Store the callback binder in-memory. Android 15+ (API 35) does not reliably
        // deliver IBinder objects through PendingIntent extras — the binder arrives null when
        // ShellConsentActivity reads it (#387). We pass only the key; the activity takes the
        // live binder from PendingConsentStore eagerly in onCreate.
        // put() returns null when the binder is already dead — don't show a notification that
        // can't possibly deliver anything.
        val consentKey = PendingConsentStore.put(callbackBinder, context) ?: return

        val callingPackage = intent.getStringExtra("callingPackage")
        // appLabel: try PM lookup; fall back to package name; then UID string (#391 — some
        // devices/callers can't be resolved via PM but the package name is still display-useful).
        val appLabel = callingPackage?.let { pkg ->
            try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) { pkg }
        }
        ActivityLogManager.log(appLabel ?: "Shell", callingPackage ?: "", "Shell: consent requested")
        val consentIntent = Intent(context, ShellConsentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(PendingConsentStore.EXTRA_CONSENT_KEY, consentKey)
            callingPackage?.let { putExtra("callingPackage", it) }
            // Pass callingUid so ShellConsentActivity can grant authorization even when
            // PackageManager.getApplicationInfo() fails (e.g. classic rish_shizuku.dex, #391).
            intentCallingUid?.let { putExtra("callingUid", it) }
        }
        // Use the key's hash as both the PendingIntent requestCode and the notification ID so
        // concurrent consent requests each get their own slot in the shade. A shared ID would
        // let a second nm.notify() silently replace the first notification — the first request's
        // binder would be orphaned with no UI to deliver it. The death recipient in
        // PendingConsentStore cancels the notification if rish times out before the user taps.
        val notificationId = consentKey.hashCode()
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            consentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notifTitle = if (appLabel != null)
            context.getString(R.string.notification_shell_consent_title_identified, appLabel)
        else
            context.getString(R.string.notification_shell_consent_title)
        val notifText = if (appLabel != null)
            context.getString(R.string.notification_shell_consent_text_identified)
        else
            context.getString(R.string.notification_shell_consent_text)

        // Action intents: explicit component + exported=false keeps these internal.
        // Strings (consentKey, callingPackage, callingUid) survive PendingIntent serialization
        // safely; the live binder stays in PendingConsentStore and is fetched inside the receiver.
        val allowIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            Intent(ShellConsentActionReceiver.ACTION_ALLOW, null, context, ShellConsentActionReceiver::class.java).apply {
                putExtra(PendingConsentStore.EXTRA_CONSENT_KEY, consentKey)
                callingPackage?.let { putExtra("callingPackage", it) }
                intentCallingUid?.let { putExtra("callingUid", it) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            Intent(ShellConsentActionReceiver.ACTION_DENY, null, context, ShellConsentActionReceiver::class.java).apply {
                putExtra(PendingConsentStore.EXTRA_CONSENT_KEY, consentKey)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(notifTitle)
            .setContentText(notifText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.notification_shell_consent_action_allow), allowIntent)
            .addAction(0, context.getString(R.string.notification_shell_consent_action_deny), denyIntent)
            .build()

        nm.notify(notificationId, notification)
    }
}
