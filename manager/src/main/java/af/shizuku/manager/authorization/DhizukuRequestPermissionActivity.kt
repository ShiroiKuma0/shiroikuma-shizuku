package af.shizuku.manager.authorization

import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.ConfirmationDialogBinding
import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.ShizukuStateMachine
import android.app.Dialog
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.html.text.HtmlCompat
import rikka.shizuku.Shizuku

/**
 * Fork: the target of Dhizuku-API's `Dhizuku.requestPermission()`.
 *
 * The library starts `<owner package>.action.REQUEST_DHIZUKU_PERMISSION` with the caller's `uid`
 * and a `request_permission_binder` (an `IDhizukuRequestPermissionListener`), both at the top level
 * and nested under `bundle`. Upstream declares no activity for it, so `startActivity` threw
 * `ActivityNotFoundException` and the library answered DENIED at once, with no prompt.
 *
 * Dhizuku access shares the Shizuku grant table: `DhizukuProvider` authorizes through
 * [AuthorizationManager.granted], so an app already allowed for Shizuku is answered GRANTED here
 * without a dialog, and allowing one here allows it for both.
 *
 * The `uid` extra is the caller's own claim — a plain `startActivity` carries no trustworthy
 * sender. That is acceptable for this one decision: the prompt names the app that `uid` belongs to,
 * and a grant only ever benefits that uid, so a forged request can at most ask the user to allow a
 * different, named app. It can never obtain the binder for itself.
 */
class DhizukuRequestPermissionActivity : AppActivity() {
    private var dialog: Dialog? = null
    private var listener: IBinder? = null
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nested = intent.getBundleExtra("bundle")
        val uid = intent.getIntExtra("uid", nested?.getInt("uid", -1) ?: -1)
        listener = intent.extras?.getBinder("request_permission_binder")
            ?: nested?.getBinder("request_permission_binder")
        if (uid < 0 || listener == null || !ShizukuSettings.isDhizukuModeEnabled()) {
            reply(granted = false)
            finish()
            return
        }

        val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull()
        if (packageName == null) {
            reply(granted = false)
            finish()
            return
        }

        lifecycleScope.launch {
            val binderReady = awaitShizukuBinder()
            if (isFinishing || isDestroyed) return@launch
            if (!binderReady) {
                reply(granted = false)
                finish()
                return@launch
            }
            val already = withContext(Dispatchers.IO) { AuthorizationManager.granted(packageName, uid) }
            if (isFinishing || isDestroyed) return@launch
            if (already) {
                reply(granted = true)
                finish()
                return@launch
            }
            showPrompt(packageName, uid)
        }
    }

    // Same wait as RequestPermissionActivity: the provider or this activity may be what cold-started
    // the process, and AuthorizationManager reads the grant table through the Shizuku binder.
    private suspend fun awaitShizukuBinder(): Boolean =
        try {
            withTimeout(5000) {
                while (true) {
                    if (ShizukuStateMachine.get() == ShizukuStateMachine.State.RUNNING) {
                        val alive =
                            withContext(Dispatchers.IO) {
                                try {
                                    Shizuku.pingBinder()
                                } catch (_: Exception) {
                                    false
                                }
                            }
                        if (alive) break
                    }
                    delay(100)
                }
            }
            true
        } catch (e: TimeoutCancellationException) {
            LOGGER.e(e, "Dhizuku permission: Shizuku binder not received in 5s")
            false
        }

    private fun showPrompt(
        packageName: String,
        uid: Int,
    ) {
        val label =
            try {
                packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager)
            } catch (_: Exception) {
                packageName
            }

        val binding =
            ConfirmationDialogBinding.inflate(layoutInflater).apply {
                button1.setOnClickListener {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { AuthorizationManager.grant(packageName, uid) }
                        reply(granted = true)
                        dialog?.dismiss()
                    }
                }
                button3.setOnClickListener {
                    reply(granted = false)
                    dialog?.dismiss()
                }
                title.text =
                    HtmlCompat.fromHtml(
                        getString(R.string.permission_warning_template, label, getString(R.string.permission_group_description)),
                    )
            }

        val d =
            MaterialAlertDialogBuilder(this)
                .setView(binding.root)
                .setCancelable(false)
                .setOnDismissListener { finish() }
                .create()
        d.setCanceledOnTouchOutside(false)
        dialog = d
        try {
            d.show()
            // An Activity-owned AlertDialog, so installGlobalStyling never sees it — see
            // RequestPermissionActivity for why this must follow show().
            af.shizuku.manager.shiroikuma.ShiroikumaDialogs
                .style(d)
        } catch (_: WindowManager.BadTokenException) {
            finish()
        }
    }

    override fun onDestroy() {
        // Leaving without an answer (the task was swept away) must still unblock the caller.
        reply(granted = false)
        super.onDestroy()
    }

    /**
     * `IDhizukuRequestPermissionListener.onRequestPermission(int grantResult)` — transaction 1,
     * oneway, interface token written. Dhizuku-API's PERMISSION_GRANTED is 0 and DENIED is -1
     * (the library itself answers -1 when no activity resolves). Answers at most once.
     */
    private fun reply(granted: Boolean) {
        if (answered) return
        answered = true
        val binder = listener ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.rosan.dhizuku.aidl.IDhizukuRequestPermissionListener")
            data.writeInt(if (granted) 0 else -1)
            binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Throwable) {
            LOGGER.e(e, "Dhizuku permission: reply to listener failed")
        } finally {
            data.recycle()
        }
    }
}
