package af.shizuku.manager.shiroikuma.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import af.shizuku.manager.R
import af.shizuku.manager.shiroikuma.ShiroikumaBackup
import af.shizuku.manager.shiroikuma.ShiroikumaUiPrefs
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where the automation export actually runs.
 *
 * A manifest receiver cannot hold it: `goAsync()` does not extend the broadcast window, so an export
 * that overruns it gets this app ANR'd and killed **mid-write**. The receiver therefore does nothing
 * but validate and start this service, which does the whole export, sends the progress broadcasts,
 * sends the **one** terminal reply, and stops itself.
 *
 * Exactly one terminal reply per request, guarded by an [AtomicBoolean] so an async success and a
 * synchronous error can never both fire.
 */
class StateExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MUST be within 5 s of the service starting or the system kills us.
        startForeground(NOTIF_ID, buildNotification())

        val i = intent ?: run { stopSelf(); return START_NOT_STICKY }
        val replyAction = i.getStringExtra("reply_action")
        val replyPackage = i.getStringExtra("reply_package")
        val replyId = i.getStringExtra("reply_id")
        if (replyAction == null || replyPackage == null || replyId == null) {
            finish(); return START_NOT_STICKY
        }

        // Process-local, never persisted: a persisted flag would wedge the app for good after one
        // crash, and every later request would answer "export already running".
        if (!running.compareAndSet(false, true)) {
            StateExportReceiver.reply(this, replyAction, replyPackage, replyId, "ERROR:export already running")
            finish(); return START_NOT_STICKY
        }
        cancelRequested = false

        val replied = AtomicBoolean(false)
        fun reply(result: String) {
            if (!replied.compareAndSet(false, true)) return
            StateExportReceiver.reply(this, replyAction, replyPackage, replyId, result)
        }

        scope.launch {
            val wakeLock = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shiroikuma:export")
                ?.also { runCatching { it.acquire(10 * 60 * 1000L) } }
            try {
                val dir = resolveDir(i.getStringExtra("path"))
                    ?: run { reply("ERROR:no-directory"); return@launch }

                // Declaring MANAGE_EXTERNAL_STORAGE is not holding it — check the grant rather than
                // discovering it by failing. This exact string is what 自由作業盤 keys on to offer
                // the "grant all-files access" repair on the failed row.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    !Environment.isExternalStorageManager()
                ) {
                    reply("ERROR:no-storage-access"); return@launch
                }

                val parts = resolveParts(i.getStringExtra("items"))
                if (parts.isEmpty()) { reply("ERROR:no categories selected"); return@launch }

                val progressAction = i.getStringExtra("progress_action")
                var lastBeat = 0L

                val file = ShiroikumaBackup.exportToDirAtomically(
                    context = this@StateExportService,
                    parts = parts,
                    dir = dir,
                    onProgress = { position, total, id, label ->
                        val now = System.currentTimeMillis()
                        // Throttle to one every 500 ms, but always let the completion one through.
                        val done = position >= total && id.isEmpty()
                        if (progressAction != null && (done || now - lastBeat >= 500)) {
                            lastBeat = now
                            sendProgress(
                                progressAction, replyPackage, replyId,
                                item = id, position = position, total = total, label = label
                            )
                        }
                    },
                    isCancelled = { cancelRequested }
                )

                val bytes = file.length()
                reply("OK:${file.absolutePath}|$bytes|${human(bytes)}|${parts.size} categories")
            } catch (_: ShiroikumaBackup.CancelledException) {
                reply("ERROR:cancelled")
            } catch (e: Exception) {
                reply("ERROR:${e.message ?: e.javaClass.simpleName}")
            } finally {
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
                running.set(false)
                finish()
            }
        }
        return START_NOT_STICKY
    }

    /** Directory precedence: the `path` extra → the app's configured directory → `ERROR:no-directory`. */
    private fun resolveDir(pathOverride: String?): File? {
        pathOverride?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return ShiroikumaUiPrefs.getString(this, ShiroikumaUiPrefs.KEY_EXPORT_DIR)
            .takeIf { it.isNotBlank() }?.let { File(it) }
    }

    /** Absent/empty `items` = our default set (here: everything — no category is opt-out). */
    private fun resolveParts(items: String?): Set<ShiroikumaUiPrefs.Category> {
        val ids = items?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (ids.isEmpty()) return ShiroikumaBackup.categories().toSet()
        return ids.mapNotNull { ShiroikumaBackup.categoryById(it) }.toSet()
    }

    private fun sendProgress(
        action: String,
        replyPackage: String,
        replyId: String,
        item: String,
        position: Int,
        total: Int,
        label: String
    ) {
        // Real counts, never a percentage. `item` is the category id being written right now, so the
        // panel highlights the row actually in progress; `current` is its 1-based POSITION.
        val text = if (label.isEmpty()) "区分 $total/$total" else "区分 $position/$total — $label"
        sendBroadcast(
            Intent(action).apply {
                setPackage(replyPackage)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("reply_id", replyId)
                putExtra("app", "白い熊 雫")
                putExtra("item", item)
                putExtra("text", text)
                putExtra("current", position.toLong())
                putExtra("total", total.toLong())
                putExtra("unit", "区分")
            }
        )
    }

    private fun finish() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "保存復元", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("白い熊 雫")
            .setContentText("保存復元 — exporting…")
            .setSmallIcon(R.drawable.ic_notification_server_24)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    companion object {
        private const val CHANNEL = "shiroikuma_automation"
        private const val NOTIF_ID = 0x5A11

        private val running = AtomicBoolean(false)

        /**
         * Checked between entries by the writer, so a cancel unwinds at a boundary rather than
         * tearing down mid-write. Safe to call at any time — a no-op when nothing is running.
         */
        @Volatile
        private var cancelRequested = false

        fun requestCancel() {
            if (running.get()) cancelRequested = true
        }
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.1f kB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
