package af.shizuku.manager.adb

import android.content.Context
import android.provider.Settings
import af.shizuku.manager.ShizukuSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One shell command run through the **device's own adbd**, over the loopback — a privileged shell
 * with no PC in the loop.
 *
 * The point people miss about `adb tcpip 5555`: it does not open a channel *to the PC*. It restarts
 * adbd on the phone listening on a TCP port of the phone, which anything on the phone can then
 * connect to. So the cable is needed for exactly one command, after which it comes out and this app
 * can drive adb on itself — same [AdbClient], same key, same 127.0.0.1 target the wireless-debugging
 * start path already uses, only reached without pairing.
 *
 * That is what lets the boot checklist grant `WRITE_SECURE_SETTINGS` before the server is running:
 * the grant needs a shell we would otherwise not have until the server exists, and this is one.
 *
 * **Why [detectPort] opens a socket instead of reading `service.adb.tcp.port`.** That property is
 * the obvious source and it is the one upstream reads, but its SELinux context is
 * `adbd_config_prop` — `getprop` from an adb shell shows `5555` while an ordinary app very likely
 * gets nothing back, and a blocked read is indistinguishable from "adb is off". Connecting to the
 * port answers the question we actually care about — *is there an adbd listening that we can
 * reach* — needs no permission beyond INTERNET, and is correct whether or not the property is
 * readable. The property is still consulted, as a hint about which port to try first.
 *
 * The first connection with a key adbd has not seen raises the system's "Allow USB debugging?"
 * prompt and blocks until it is answered, so [run] retries with backoff instead of failing on the
 * first read timeout — those retries are the window in which the user taps Allow.
 */
object AdbLoopbackShell {

    private const val TAG = "AdbLoopbackShell"
    private const val MAX_ATTEMPTS = 8

    /** Loopback refuses instantly when nothing listens, so this only bounds the pathological case. */
    private const val PROBE_TIMEOUT_MS = 300

    sealed interface Outcome {
        /** The command ran. [output] is adbd's merged output, often empty on success. */
        data class Ok(val output: String) : Outcome

        /** Reached adbd but the attempt failed — [reason] is the only clue there is, so surface it. */
        data class Failed(val reason: String) : Outcome

        /** No reachable adbd, so nothing was attempted. */
        data object Unavailable : Outcome
    }

    /** Whether adbd is running at all. Cheap, and settles the common "USB debugging is off" case. */
    fun adbEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
    }.getOrDefault(false)

    /** What `service.adb.tcp.port` claims, or -1 — a hint only; see the class comment. */
    fun propertyPort(): Int {
        val port = runCatching { af.shizuku.common.util.EnvironmentUtils.getAdbTcpPort() }.getOrDefault(-1)
        return if (port in 1..65535) port else -1
    }

    /**
     * The loopback port an adbd actually answers on, or -1.
     *
     * Blocking connects, so never on the main thread — the caller renders from a cached result.
     */
    suspend fun detectPort(context: Context): Int = withContext(Dispatchers.IO) {
        if (!adbEnabled(context)) return@withContext -1
        for (port in candidatePorts()) {
            val reachable = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS) }
                true
            }.getOrDefault(false)
            if (reachable) {
                Timber.tag(TAG).d("adbd answers on 127.0.0.1:$port")
                return@withContext port
            }
        }
        -1
    }

    /**
     * Ports worth trying, best guess first: what the property says (when it is readable at all),
     * the conventional `adb tcpip` port, the configured TCP port, and the last one we connected on.
     * A stale entry costs one refused connect.
     */
    private fun candidatePorts(): List<Int> = listOf(
        propertyPort(),
        5555,
        ShizukuSettings.getTcpPort(),
        ShizukuSettings.getLastPort()
    ).filter { it in 1..65535 }.distinct()

    suspend fun run(context: Context, port: Int, command: String): Outcome = withContext(Dispatchers.IO) {
        if (port !in 1..65535 || !adbEnabled(context)) return@withContext Outcome.Unavailable

        val key = try {
            AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku+")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "adb key unavailable")
            return@withContext Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }

        try {
            AdbClient("127.0.0.1", port, key).use { client ->
                connectWithRetry(client)
                val output = StringBuilder()
                client.command("shell:$command") { output.append(String(it)) }
                Outcome.Ok(output.toString().trim())
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "loopback adb command failed on port $port")
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun connectWithRetry(client: AdbClient) {
        var delayTime = 500L
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                if (attempt > 1) {
                    delay(delayTime)
                    delayTime = (delayTime * 1.5).toLong().coerceAtMost(3000L)
                }
                client.connect()
                return
            } catch (e: Exception) {
                if (attempt == MAX_ATTEMPTS || e is CancellationException) throw e
            }
        }
    }
}
