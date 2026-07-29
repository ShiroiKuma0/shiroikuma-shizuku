package af.shizuku.manager.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import timber.log.Timber

/**
 * FORK: neutered. Does nothing, contacts nobody.
 *
 * Upstream ran this as a 24-hourly periodic [androidx.work.PeriodicWorkRequest] that fetched
 * `app-context-db.json` from the upstream author's GitHub repo over plain
 * [java.net.HttpURLConnection], sending a `Shizuku+/<version>` User-Agent plus ETag /
 * If-Modified-Since headers on every cycle. That is a recurring automatic call-out from
 * 白い熊's device, so it is gone: no URL, no fetch, no scheduling.
 *
 * The class is kept (rather than deleted) as a deliberate tripwire — [schedule] now *cancels*
 * the work instead of enqueuing it, so if an upstream rebase restores the
 * `RemoteDbSyncWorker.schedule(this)` call in `ShizukuApplication`, the result is still zero
 * network traffic, and any job left enqueued by an older build is torn down.
 *
 * This app sends nothing anywhere. See CLAUDE.md, "No phone-home".
 */
class RemoteDbSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "remote_app_db_sync"

        /** Cancels any previously-enqueued sync instead of scheduling one. */
        fun schedule(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
            Timber.d("RemoteDbSync: disabled in this fork — nothing scheduled")
        }
    }

    override suspend fun doWork(): Result {
        Timber.d("RemoteDbSync: disabled in this fork — no-op")
        return Result.success()
    }
}
