package af.shizuku.manager.shiroikuma.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import af.shizuku.manager.R
import af.shizuku.manager.shiroikuma.ShiroikumaBackup
import af.shizuku.manager.shiroikuma.ShiroikumaUiPrefs
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where a data export or import actually runs.
 *
 * ## Why a foreground service and not the provider call
 *
 * The call returns in milliseconds; this can run for minutes. Two hard reasons it cannot be done
 * anywhere cheaper:
 *
 * - **A binder call holds the caller.** 応用管理 is drawing a list; a multi-minute synchronous call
 *   would freeze its UI, report no progress, and refuse cancellation.
 * - **A backgrounded app writing for minutes is frozen mid-stream on this phone**, which yields a
 *   truncated archive underneath a success reply — the worst possible failure, because it is
 *   indistinguishable from a good backup until the day it is restored (応用管理, 2026-09-04).
 *
 * ## The descriptor
 *
 * Already duplicated by [AutomationProvider] before it got here, because the original belongs to
 * the binder transaction and is closed the moment `call()` returns. This service owns the copy and
 * closes it in a `finally` — leaking one would hold the caller's file open indefinitely, and the
 * caller cannot checksum or encrypt a file that is still open.
 */
class AutomationDataService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(EXTRA_JOB) ?: return stop(startId)
        val fd = HANDOVER.remove(jobId) ?: return stop(startId)
        val importing = intent.getBooleanExtra(EXTRA_IMPORTING, false)
        val replyAction = intent.getStringExtra(AutomationProvider.KEY_REPLY_ACTION)
        val replyPackage = intent.getStringExtra(AutomationProvider.KEY_REPLY_PACKAGE)
        val progressAction = intent.getStringExtra(AutomationProvider.KEY_PROGRESS_ACTION)

        val replied = AtomicBoolean(false)
        fun reply(result: String) {
            // Exactly one terminal answer per job, whatever path got here — a synchronous failure
            // and an asynchronous success must never both fire. The same guard the broadcast
            // contract has carried since the first sister app.
            if (!replied.compareAndSet(false, true)) return
            AutomationJobs.finish(jobId)
            if (replyAction.isNullOrEmpty() || replyPackage.isNullOrEmpty()) return
            sendBroadcast(
                Intent(replyAction).apply {
                    setPackage(replyPackage)
                    // Without this a caller that has been backgrounded never hears the answer, and
                    // on a clean phone the caller may not have been launched at all.
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(AutomationProvider.KEY_JOB_ID, jobId)
                    putExtra(AutomationProvider.KEY_RESULT, result)
                },
            )
        }

        // AFTER `reply` exists, and guarded. `startForeground` throws when the declared
        // foregroundServiceType disagrees with the manifest, and on API 31+ it can be refused
        // outright for a service started from the background — which a provider `call()` always is.
        // The descriptor has already left HANDOVER by this point, so nothing else would ever close
        // it, and a throw out of `onStartCommand` would kill the service with the caller still
        // waiting for an answer it will never get.
        //
        // It must also still happen within 5 s of the service starting, or the system kills us for
        // the same class of reason a receiver gets ANR'd.
        try {
            startForeground(NOTIFICATION_ID, notification(importing))
        } catch (e: Exception) {
            runCatching { fd.close() }
            reply("ERROR:cannot go foreground: ${e.javaClass.simpleName}")
            return stop(startId)
        }

        scope.launch {
            try {
                fd.use { open ->
                    if (importing) {
                        runImport(open, reply = ::reply)
                    } else {
                        runExport(
                            jobId = jobId,
                            fd = open,
                            items = intent.getStringExtra(AutomationProvider.KEY_ITEMS),
                            progressAction = progressAction,
                            replyPackage = replyPackage,
                            reply = ::reply,
                        )
                    }
                }
            } catch (_: ShiroikumaBackup.CancelledException) {
                reply("ERROR:cancelled")
            } catch (t: Throwable) {
                reply("ERROR:${t.message ?: t::class.java.simpleName}")
            } finally {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun runExport(
        jobId: String,
        fd: ParcelFileDescriptor,
        items: String?,
        progressAction: String?,
        replyPackage: String?,
        reply: (String) -> Unit,
    ) {
        val cats = resolve(items) ?: run { reply("ERROR:unknown category in items: $items"); return }
        if (cats.isEmpty()) { reply("ERROR:no categories selected"); return }
        // Bound once, non-null, outside the lambda: progress is only ever sent when there is a
        // package to aim it at, because an implicit broadcast reaches no manifest receiver at all.
        val progressTo = if (replyPackage.isNullOrEmpty()) null else replyPackage
        var written = 0L
        var lastBeat = 0L
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            // Counted as it goes rather than stat'ed afterwards: the caller owns the file and we
            // may not be able to see it at all — it can be an anonymous pipe or a descriptor into a
            // directory this app cannot list.
            val counting = object : OutputStream() {
                override fun write(b: Int) { out.write(b); written++ }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len); written += len
                }
            }
            ShiroikumaBackup.export(
                context = this,
                parts = cats,
                out = counting,
                appVersion = ShiroikumaBackup.appVersion(this),
                onProgress = { position, total, id, label ->
                    val now = System.currentTimeMillis()
                    val done = position >= total && id.isEmpty()
                    // Real counts, never a percentage; throttled to one every 500 ms, with the
                    // completion one always allowed through.
                    if (progressAction != null && progressTo != null &&
                        (done || now - lastBeat >= 500)
                    ) {
                        lastBeat = now
                        sendProgress(progressAction, progressTo, jobId, id, position, total, label)
                    }
                },
                isCancelled = { AutomationJobs.isCancelled(jobId) },
            )
        }
        if (AutomationJobs.isCancelled(jobId)) reply("ERROR:cancelled")
        else reply("OK:$written|${cats.size} categories")
    }

    /**
     * Read the whole archive before touching anything.
     *
     * [ShiroikumaBackup.import] wants a stream, and reading it fully first is the right shape here
     * for a reason beyond convenience: a partial read that failed halfway would otherwise import
     * half an archive, and a half-restored app is worse than one that refused.
     */
    private fun runImport(fd: ParcelFileDescriptor, reply: (String) -> Unit) {
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        if (bytes.isEmpty()) { reply("ERROR:empty archive"); return }
        // Every category the archive actually carries, not every category we know about: asking
        // for one the archive lacks is how a restore ends up reporting success over nothing.
        val present = ShiroikumaBackup.categoriesIn(bytes)
        if (present.isEmpty()) { reply("ERROR:archive carries no categories"); return }
        val result = ShiroikumaBackup.import(this, present, ByteArrayInputStream(bytes))
        if (!result.ok) { reply("ERROR:${result.errors.joinToString("; ")}"); return }
        // The caller force-stops us straight after this. That is deliberate and belongs on its side:
        // a running process writes its cached SharedPreferences back out at orderly shutdown and
        // silently undoes the import that just happened (応用管理 paid for this one already).
        reply("OK:${result.lines.size} restored")
    }

    /**
     * `item` is the category id being written right now, so the panel highlights the row actually
     * in progress; `current` is that category's 1-based POSITION, not a count of finished ones.
     */
    /**
     * Every one of these carries `setPackage`, and is not sent at all without a package to aim it
     * at. Since API 26 an implicit broadcast is not delivered to a manifest-declared receiver **at
     * all**, so a progress line with no `setPackage` is not weak progress — it is none, silently,
     * while the export runs to completion and reports its terminal reply correctly.
     */
    private fun sendProgress(
        action: String,
        replyPackage: String,
        jobId: String,
        item: String,
        position: Int,
        total: Int,
        label: String,
    ) {
        val text = if (label.isEmpty()) "区分 $total/$total" else "区分 $position/$total — $label"
        sendBroadcast(
            Intent(action).apply {
                setPackage(replyPackage)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                // Both keys carry the same id: the data door correlates on `job_id`, while §3's
                // progress shape — which the panel already parses — reads `reply_id`.
                putExtra(AutomationProvider.KEY_JOB_ID, jobId)
                putExtra("reply_id", jobId)
                putExtra("app", "白い熊 雫")
                putExtra("item", item)
                putExtra("text", text)
                putExtra("current", position.toLong())
                putExtra("total", total.toLong())
                putExtra("unit", "区分")
            },
        )
    }

    /** Absent/empty `items` = our default set — here every category, none being opt-out. */
    private fun resolve(items: String?): Set<ShiroikumaUiPrefs.Category>? {
        if (items.isNullOrBlank()) return ShiroikumaBackup.categories().toSet()
        val wanted = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val found = wanted.mapNotNull { ShiroikumaBackup.categoryById(it) }
        return if (found.size == wanted.size) found.toSet() else null
    }

    private fun notification(importing: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager?.getNotificationChannel(CHANNEL) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL, "自動化データ", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("白い熊 雫")
            .setContentText(if (importing) "データを戻しています" else "データを書き出しています")
            .setSmallIcon(R.drawable.ic_notification_server_24)
            .setOngoing(true)
            .build()
    }

    private fun stop(startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "shiroikuma_automation_data"
        private const val NOTIFICATION_ID = 0x5A12
        private const val EXTRA_JOB = "job"
        private const val EXTRA_IMPORTING = "importing"

        /**
         * The descriptor's way across, because an Intent is the wrong vehicle for one.
         *
         * A `ParcelFileDescriptor` in an Intent extra is duplicated by the system on delivery and
         * the copy's lifetime stops being ours to reason about. Handing it through a map keyed by
         * the job id keeps exactly one open descriptor with exactly one owner — the service, which
         * closes it in a `finally`.
         */
        private val HANDOVER = java.util.concurrent.ConcurrentHashMap<String, ParcelFileDescriptor>()

        /**
         * `false` when the service could not be started — a background-start refusal on API 31+,
         * or a foreground-service throw. The descriptor is closed and the job dropped on that path:
         * it has already left the caller's transaction, so nothing else would ever close it, and a
         * stranded entry in [HANDOVER] holds the caller's file open for the life of the process.
         */
        fun start(
            context: Context,
            jobId: String,
            fd: ParcelFileDescriptor,
            importing: Boolean,
            extras: Bundle?,
        ): Boolean = try {
            HANDOVER[jobId] = fd
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutomationDataService::class.java).apply {
                    putExtra(EXTRA_JOB, jobId)
                    putExtra(EXTRA_IMPORTING, importing)
                    putExtra(AutomationProvider.KEY_ITEMS, extras?.getString(AutomationProvider.KEY_ITEMS))
                    putExtra(
                        AutomationProvider.KEY_REPLY_ACTION,
                        extras?.getString(AutomationProvider.KEY_REPLY_ACTION),
                    )
                    putExtra(
                        AutomationProvider.KEY_REPLY_PACKAGE,
                        extras?.getString(AutomationProvider.KEY_REPLY_PACKAGE),
                    )
                    putExtra(
                        AutomationProvider.KEY_PROGRESS_ACTION,
                        extras?.getString(AutomationProvider.KEY_PROGRESS_ACTION),
                    )
                },
            )
            true
        } catch (e: Exception) {
            HANDOVER.remove(jobId)
            runCatching { fd.close() }
            AutomationJobs.finish(jobId)
            false
        }
    }
}
