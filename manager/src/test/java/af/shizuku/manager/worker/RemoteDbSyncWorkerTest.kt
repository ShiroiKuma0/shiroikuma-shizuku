package af.shizuku.manager.worker

import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import java.io.File

/**
 * FORK GUARD — this worker must never contact anything.
 *
 * Upstream ran `RemoteDbSyncWorker` as a 24-hourly periodic job that fetched `app-context-db.json`
 * from the upstream author's GitHub repo. This fork removed that: the app sends nothing to upstream
 * and nothing anywhere else, so the worker is neutered and its scheduling is gone.
 *
 * Upstream's own test exercised the fetch-and-retry behaviour, and additionally never compiled — it
 * imported `af.shizuku.manager.utils.AppContextManager`, while the class lives in
 * `af.shizuku.manager.database`. Replaced here with the guarantee that actually matters.
 */
class RemoteDbSyncWorkerTest : FunSpec({

    val context: Context = mockk(relaxed = true)
    val workerParams: WorkerParameters = mockk(relaxed = true)

    test("doWork is a no-op that always succeeds") {
        // No network mocking of any kind: if this worker ever tries to open a connection again, the
        // test fails on its own rather than passing against a mock that hides it.
        val worker = RemoteDbSyncWorker(context, workerParams)
        worker.doWork() shouldBe Result.success()
    }

    test("the worker source contains no network call and no upstream URL") {
        // A behavioural test cannot prove absence cheaply — a rebase that restored the fetch would
        // still "succeed" if the network happened to be down. Reading the source can.
        val source = File(
            File(".").canonicalFile,
            "src/main/java/af/shizuku/manager/worker/RemoteDbSyncWorker.kt"
        )
        check(source.isFile) { "RemoteDbSyncWorker source not found at ${source.path}" }
        // Comments are stripped first: the class doc deliberately *names* what was removed
        // ("fetched … over plain HttpURLConnection"), and matching that would be a false positive
        // that punishes documenting the decision.
        val text = source.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//.*"""), "")

        withClue("RemoteDbSyncWorker opens a network connection again — the fetch was restored.") {
            text shouldNotContain "openConnection"
        }
        withClue("RemoteDbSyncWorker uses HttpURLConnection again — the fetch was restored.") {
            text shouldNotContain "HttpURLConnection"
        }
        withClue("RemoteDbSyncWorker points at upstream's repo again.") {
            text shouldNotContain "githubusercontent"
        }
        withClue("RemoteDbSyncWorker enqueues periodic work again — scheduling was restored.") {
            text shouldNotContain "PeriodicWorkRequestBuilder"
        }
    }
})

private inline fun withClue(clue: String, block: () -> Unit) {
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError(clue, e)
    }
}
