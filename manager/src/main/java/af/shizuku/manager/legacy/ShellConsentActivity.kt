package af.shizuku.manager.legacy

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.R
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.databinding.ConfirmationDialogBinding
import af.shizuku.manager.shell.PendingConsentStore
import af.shizuku.manager.shell.ShellBinderRequestHandler
import af.shizuku.manager.shiroikuma.ShiroikumaDialogs
import af.shizuku.manager.utils.Logger.LOGGER

// Reached only via an explicit in-process launch from BinderRequestReceiver when a
// REQUEST_BINDER broadcast carries a callback binder but no valid encrypted auth token
// (i.e. every rish/shell client - see GH #368/#372/#374, IntentCrypto is scoped to this
// app's own UID so a shell process can never produce one). This restores a first-time
// consent path instead of silently dropping the request.
class ShellConsentActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Take the callback binder eagerly to avoid a TOCTOU between existence-check and use.
        // BinderRequestReceiver stores it in PendingConsentStore and passes the key here so the
        // binder survives the PendingIntent round-trip without being embedded in the extras
        // (Android 15+/API 35 does not reliably deliver IBinder objects in PendingIntent extras,
        // #387).
        val consentKey = intent.getStringExtra(PendingConsentStore.EXTRA_CONSENT_KEY)
        val callbackBinder = consentKey?.let { PendingConsentStore.take(it) }

        if (callbackBinder == null) {
            // Binder is gone (rish timed out and died before the user tapped the notification).
            finish()
            return
        }

        val callingPackage = intent.getStringExtra("callingPackage")
        val intentCallingUid = intent.getIntExtra("callingUid", -1).takeIf { it >= 0 }
        showConsentDialog(callbackBinder, callingPackage, intentCallingUid)
    }

    private fun showConsentDialog(callbackBinder: android.os.IBinder, callingPackage: String?, intentCallingUid: Int?) {
        // Resolve ApplicationInfo from PackageManager rather than trusting the extras directly,
        // so a spoofed broadcast can't grant a different UID than the named package actually has.
        // Also derive the human-readable app label here — the dialog should show "Talkman is
        // requesting…" not "com.nirenr.talkman is requesting…" (#398).
        val appInfo = callingPackage?.let { pkg ->
            try {
                packageManager.getApplicationInfo(pkg, 0)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                null
            }
        }
        // Prefer PM-derived UID; fall back to the UID from ShizukuShellLoader (#391 —
        // classic rish_shizuku.dex omits callingPackage, or PM lookup unavailable on device).
        val callingUid = appInfo?.uid ?: intentCallingUid
        val appLabel = appInfo?.let { packageManager.getApplicationLabel(it).toString() }
            ?: callingPackage  // fallback to package ID if the lookup fails

        val binding = ConfirmationDialogBinding.inflate(layoutInflater).apply {
            if (appLabel != null) {
                title.text = getString(R.string.shell_consent_dialog_title_identified, appLabel)
                // Keeps the layout default "Allow all the time", and it is now literally true: an
                // app label only reaches here when VerifiedBinderRequestReceiver established the
                // caller's uid from the kernel, so the grant stored below lets its next request
                // through unprompted.
            } else {
                title.text = getString(R.string.shell_consent_dialog_title)
                // Unverified caller — the grant cannot be trusted to mean "this app", so promise
                // only what this tap actually does.
                button1.text = getString(R.string.shell_consent_button_allow)
            }
            button3.text = getString(R.string.grant_dialog_button_deny)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(binding.root)
            .setCancelable(false)
            .setOnDismissListener { finish() }
            .create()
        dialog.setCanceledOnTouchOutside(false)

        binding.button1.setOnClickListener {
            // The grant below is what makes the next request unprompted, via
            // VerifiedBinderRequestReceiver's identity challenge — it is keyed on a uid the kernel
            // reported, not on anything the caller claimed. The fork's old global
            // KEY_SHELL_CONSENT_GRANTED flag did the same job by trusting every broadcaster, which
            // was wider than the hole upstream closed in c7c9f6c8; this replaced it.
            // deliverBinder does synchronous binder transacts with retries - keep it off Main.
            lifecycleScope.launch {
                val delivered = withContext(Dispatchers.IO) {
                    try {
                        // Grant permanent authorization before delivering the binder so that
                        // Shell.java's attachApplication() sees allowed=true and skips the
                        // redundant second consent dialog (#391) — and so the next request from
                        // this uid clears VerifiedBinderRequestReceiver's check without a prompt.
                        if (callingPackage != null && callingUid != null) {
                            AuthorizationManager.grant(callingPackage, callingUid)
                        }
                        ActivityLogManager.log(appLabel ?: "Shell", callingPackage ?: "", "Shell: allowed (dialog)")
                        ShellBinderRequestHandler.deliverBinder(this@ShellConsentActivity, callbackBinder)
                    } catch (e: Exception) {
                        LOGGER.w(e, "ShellConsentActivity: deliverBinder failed")
                        false
                    }
                }
                if (!delivered && !isFinishing && !isDestroyed) {
                    // Delivery failed even after retries: the rish process was frozen by Android's
                    // Cached Apps Freezer while waiting. The grant above is already stored, so
                    // running the command again connects — unprompted if the caller was verified.
                    Toast.makeText(
                        this@ShellConsentActivity,
                        getString(R.string.shell_consent_retry_hint),
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (!isFinishing && !isDestroyed) dialog.dismiss()
            }
        }
        binding.button3.setOnClickListener {
            ActivityLogManager.log(appLabel ?: "Shell", callingPackage ?: "", "Shell: denied (dialog)")
            dialog.dismiss()
        }

        try {
            dialog.show()
            // Same trap RequestPermissionActivity documents: an Activity-owned AlertDialog built
            // with create()/show() rather than showHouse() is never seen by
            // ShiroikumaDialogs.installGlobalStyling, so it comes up with no fill and no yellow
            // edge — floating text over whatever is behind it. Must run after show(), because
            // MaterialAlertDialogBuilder installs its own window background during show().
            ShiroikumaDialogs.style(dialog)
        } catch (e: WindowManager.BadTokenException) {
            finish()
        }
    }
}
