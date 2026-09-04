package af.shizuku.manager.shiroikuma.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import af.shizuku.manager.shiroikuma.ShiroikumaBackup

/**
 * The 保存復元 automation entry point — the wire contract 白い熊 自由作業盤 drives.
 *
 * Three actions on this one **exported** receiver, gated by [AutomationAuth.refuse] — the master
 * switch, plus the token **only when 白い熊 has asked for one** (contract v2). It carries no
 * `android:permission` because the caller cannot hold one, and that is deliberate rather than
 * merely unavoidable: this receiver only ever *writes where it was told to* and reports what it
 * did. Everything that moves data through a caller-supplied descriptor lives behind
 * [AutomationProvider], which is told who is calling.
 *
 * The three actions:
 *
 * - `EXPORT_STATE` — validate, then hand off to [StateExportService] and return **immediately**.
 *   The receiver never runs the export itself: `goAsync()` does not extend the broadcast window
 *   (~10 s foreground, ~60 s background), and overrunning it ANRs *this* app and kills the process
 *   mid-export, leaving a half-written archive and a caller waiting on a reply that can never come.
 * - `LIST_CATEGORIES` — instant, answered right here.
 * - `CANCEL_EXPORT` — signals the running export; fire-and-forget, sends no reply of its own, and is
 *   a **silent no-op** when nothing is running.
 */
class StateExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pkg = app.packageName
        val token = intent.getStringExtra("token")

        when (intent.action) {
            "$pkg.action.EXPORT_STATE" -> {
                val replyAction = intent.getStringExtra("reply_action")
                val replyPackage = intent.getStringExtra("reply_package")
                val replyId = intent.getStringExtra("reply_id")

                // Without a reply channel there is nobody to tell about a refusal either.
                if (replyAction == null || replyPackage == null || replyId == null) return

                // Both checks in ONE place, so "disabled" and "bad token" cannot drift apart.
                // A token sent to this app while it is not asking for one is IGNORED, never an
                // error: tokens outlive the setting they were pasted for.
                AutomationAuth.refuse(app, token)?.let {
                    reply(app, replyAction, replyPackage, replyId, it)
                    return
                }

                // Validate `items` here so a typo fails fast rather than after a service spin-up.
                val items = intent.getStringExtra("items")
                val unknown = items?.split(",")
                    ?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.filter { ShiroikumaBackup.categoryById(it) == null }
                    ?: emptyList()
                if (unknown.isNotEmpty()) {
                    reply(
                        app, replyAction, replyPackage, replyId,
                        "ERROR:unknown category in items: ${unknown.joinToString(",")}"
                    )
                    return
                }

                val svc = Intent(app, StateExportService::class.java).apply {
                    putExtra("path", intent.getStringExtra("path"))
                    putExtra("items", items)
                    putExtra("progress_action", intent.getStringExtra("progress_action"))
                    putExtra("reply_action", replyAction)
                    putExtra("reply_package", replyPackage)
                    putExtra("reply_id", replyId)
                }
                ContextCompat.startForegroundService(app, svc)
            }

            "$pkg.action.LIST_CATEGORIES" -> {
                val replyAction = intent.getStringExtra("reply_action") ?: return
                val replyPackage = intent.getStringExtra("reply_package") ?: return
                val replyId = intent.getStringExtra("reply_id") ?: return

                // Both checks in ONE place, so "disabled" and "bad token" cannot drift apart.
                // A token sent to this app while it is not asking for one is IGNORED, never an
                // error: tokens outlive the setting they were pasted for.
                AutomationAuth.refuse(app, token)?.let {
                    reply(app, replyAction, replyPackage, replyId, it)
                    return
                }

                // `id<TAB>label` per line. Flat list — no sub-options — so the third field is
                // omitted; every category is on by default, so the fourth is omitted too.
                val body = ShiroikumaBackup.categories()
                    .joinToString("\n") { "${it.id}\t${it.label}" }
                reply(app, replyAction, replyPackage, replyId, "OK:$body")
            }

            "$pkg.action.CANCEL_EXPORT" -> {
                // Silent on every refusal: a cancel has no reply channel of its own, and one that
                // arrives when nothing is running is a no-op, not an error.
                if (AutomationAuth.refuse(app, token) != null) return
                StateExportService.requestCancel()
            }
        }
    }

    companion object {
        /**
         * The one reply channel that works on this device: a **fresh broadcast**, never a Binder.
         * EMUI will not reliably carry a live Binder into another app's manifest receiver, and it
         * severs the ordered-broadcast result channel between third-party apps.
         * `FLAG_INCLUDE_STOPPED_PACKAGES` matters — without it a backgrounded caller never hears us.
         */
        fun reply(
            context: Context,
            replyAction: String,
            replyPackage: String,
            replyId: String,
            result: String
        ) {
            context.sendBroadcast(
                Intent(replyAction).apply {
                    setPackage(replyPackage)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra("reply_id", replyId)
                    putExtra("result", result)
                }
            )
        }
    }
}
