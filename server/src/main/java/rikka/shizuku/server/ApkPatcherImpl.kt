package rikka.shizuku.server

import android.os.ParcelFileDescriptor
import af.shizuku.server.IApkPatcher
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ApkPatcherImpl : IApkPatcher.Stub() {

    companion object {
        private const val TMP_DIR = "/data/local/tmp/splus_td"
    }

    // pkg → path of saved original APK
    private val sessions = ConcurrentHashMap<String, String>()

    private fun exec(vararg args: String): String = try {
        val proc = Runtime.getRuntime().exec(args)
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out
    } catch (_: Exception) { "" }

    private fun execCode(vararg args: String): Int = try {
        Runtime.getRuntime().exec(args).waitFor()
    } catch (_: Exception) { -1 }

    private fun pipe(vararg args: String): ParcelFileDescriptor? = try {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        Thread {
            try {
                val proc = Runtime.getRuntime().exec(args)
                proc.inputStream.use { src ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { dst ->
                        src.copyTo(dst)
                    }
                }
                proc.waitFor()
            } catch (_: Exception) {
                try { writeSide.close() } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }.start()
        readSide
    } catch (_: Exception) { null }

    private fun pipeFrom(pfd: ParcelFileDescriptor, vararg args: String): Boolean = try {
        val proc = ProcessBuilder(*args).start()
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { src ->
                    proc.outputStream.use { dst -> src.copyTo(dst) }
                }
            } catch (_: Exception) {
                proc.outputStream.runCatching { close() }
            }
        }.also { it.isDaemon = true }.start()
        proc.waitFor() == 0
    } catch (_: Exception) { false }

    private fun findBaseApk(packageName: String): String? {
        val out = exec("pm", "path", packageName)
        return out.lines()
            .firstOrNull { it.startsWith("package:") && !it.contains("split_") }
            ?.removePrefix("package:")?.trim()
            ?: out.lines().firstOrNull { it.startsWith("package:") }
                ?.removePrefix("package:")?.trim()
    }

    override fun prepareTempDebug(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (sessions.containsKey(packageName)) return true  // already patched

        val baseApk = findBaseApk(packageName) ?: return false
        File(TMP_DIR).mkdirs()

        val origPath = "$TMP_DIR/${packageName}_orig.apk"

        // Save original
        if (execCode("cp", baseApk, origPath) != 0) return false

        // Read, patch manifest, re-sign
        val origBytes = File(origPath).readBytes()
        val patched = try {
            val manifestPatched = ApkBinaryXmlPatcher.patch(origBytes)
            ApkSigner.sign(manifestPatched)
        } catch (_: Exception) {
            File(origPath).delete()
            return false
        }

        val patchedPath = "$TMP_DIR/${packageName}_debug.apk"
        File(patchedPath).writeBytes(patched)

        // pm uninstall -k (keep data)
        if (execCode("pm", "uninstall", "--user", "0", "-k", packageName) != 0) {
            File(origPath).delete()
            File(patchedPath).delete()
            return false
        }

        // pm install (fresh install, any signature accepted after -k uninstall)
        val installResult = execCode("pm", "install", "-g", patchedPath)
        File(patchedPath).delete()

        if (installResult != 0) {
            // Try to reinstall original so data isn't stranded
            execCode("pm", "install", origPath)
            File(origPath).delete()
            return false
        }

        sessions[packageName] = origPath
        return true
    }

    override fun streamDataDir(packageName: String?): ParcelFileDescriptor? {
        if (packageName.isNullOrBlank()) return null
        if (!sessions.containsKey(packageName)) return null
        return pipe("run-as", packageName, "tar", "-czf", "-", "-C", "/data/data/$packageName", ".")
    }

    override fun restoreDataDir(packageName: String?, tarStream: ParcelFileDescriptor?): Boolean {
        if (packageName.isNullOrBlank() || tarStream == null) return false
        if (!sessions.containsKey(packageName)) return false
        return pipeFrom(tarStream, "run-as", packageName, "tar", "-xzf", "-", "-C", "/data/data/$packageName")
    }

    override fun restoreOriginal(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val origPath = sessions[packageName] ?: return false

        val ok = execCode("pm", "uninstall", "--user", "0", "-k", packageName) == 0 &&
                 execCode("pm", "install", origPath) == 0

        sessions.remove(packageName)
        File(origPath).delete()
        return ok
    }

    override fun streamOriginalApk(packageName: String?): ParcelFileDescriptor? {
        if (packageName.isNullOrBlank()) return null
        val origPath = sessions[packageName] ?: return null
        return pipe("cat", origPath)
    }

    override fun isTempDebugging(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return sessions.containsKey(packageName)
    }

    override fun cleanupAllTempDebug() {
        for (pkg in sessions.keys.toList()) {
            restoreOriginal(pkg)
        }
        // Clean any orphaned files from a previous crash
        File(TMP_DIR).listFiles()?.forEach { f ->
            if (f.name.endsWith("_orig.apk") && !sessions.values.contains(f.absolutePath)) {
                f.delete()
            }
        }
    }
}
