package af.shizuku.manager.utils

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Runs one shell command with privilege, preferring the **running Shizuku server** and falling back
 * to root.
 *
 * Why this exists as a shared object: several places need to run a command that only shell or root
 * may run — `pm grant`, `dpm set-device-owner` — and each had grown its own copy of the
 * Shizuku-then-root dance (`ShizukuCompanionViewHolder.runPrivilegedCommand`,
 * `UpdateInstaller`). The copies differed in whether they captured output at all, which matters:
 * `dpm` refuses for several distinct reasons and the *only* way to tell them apart is the message it
 * prints. Fork note: Shizuku's `newProcess` runs as shell, **UID 2000 — exactly what `adb shell`
 * is** — so anything the user could run over adb works here without a PC attached.
 *
 * Output is captured with `2>&1` rather than by reading two streams: a command whose stderr fills
 * the pipe buffer while we are blocked reading stdout deadlocks, and merging the streams in the
 * shell sidesteps that entirely. We want the error text anyway, so nothing is lost by merging.
 */
object PrivilegedShell {

    sealed interface Outcome {
        /** Exit code 0. [output] is whatever the command printed (often empty). */
        data class Ok(val output: String) : Outcome

        /** Ran, but exited non-zero. [output] carries the reason — surface it, never swallow it. */
        data class Failed(val exitCode: Int, val output: String) : Outcome

        /** Neither Shizuku nor root is available, so the command was never run. */
        data object Unavailable : Outcome
    }

    /** Blocking IPC and a subprocess wait — always off the main thread. */
    suspend fun run(command: String): Outcome = withContext(Dispatchers.IO) {
        val merged = "$command 2>&1"

        if (Shizuku.pingBinder()) {
            var process: Process? = null
            try {
                process = Shizuku.newProcess(arrayOf("sh", "-c", merged), null, null)
                val output = process.inputStream.bufferedReader().readText().trim()
                val code = process.waitFor()
                return@withContext if (code == 0) Outcome.Ok(output) else Outcome.Failed(code, output)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Shizuku exec failed, trying root")
            } finally {
                // waitFor() can throw if the binder dies mid-command; without this the process
                // handle and its pipes leak on that path.
                try { process?.destroy() } catch (_: Exception) {}
            }
        }

        if (EnvironmentUtils.isRooted()) {
            return@withContext try {
                val result = Shell.cmd(merged).exec()
                val output = (result.out + result.err).joinToString("\n").trim()
                if (result.isSuccess) Outcome.Ok(output) else Outcome.Failed(result.code, output)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Root exec failed")
                Outcome.Failed(-1, "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            }
        }

        Outcome.Unavailable
    }

    private const val TAG = "PrivilegedShell"
}
