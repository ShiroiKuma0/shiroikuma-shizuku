package af.shizuku.manager.shiroikuma

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The category ZIP: one archive per export, `manifest.json` + one JSON per category + `fonts/`.
 *
 * **The export core is headless-callable** ([export]) so the Export/Import page and the automation
 * receiver are two thin callers over the same code — never duplicate export logic in the receiver.
 *
 * Import **merges** and silently skips categories absent from the archive, so an old backup taken
 * before a category existed still restores cleanly.
 */
object ShiroikumaBackup {

    /**
     * The mandatory family filename convention (白い熊, 2026-07-25):
     * `<english-dash-separated-app-name>_<yyyy-MM-dd_HH-mm-ss>.zip` — no version, no `_backup`
     * suffix. Every sister app's backups share one directory, so they must sort and read uniformly.
     */
    const val EXPORT_PREFIX = "shiroikuma-shizuku_"
    private const val MANIFEST = "manifest.json"
    private const val FONTS_DIR = "fonts/"

    fun exportFileName(now: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)
        return "$EXPORT_PREFIX$stamp.zip"
    }

    fun isBackupFileName(name: String): Boolean =
        name.startsWith(EXPORT_PREFIX) && name.endsWith(".zip")

    /** All categories a caller may ask for, in page order. */
    fun categories(): List<ShiroikumaUiPrefs.Category> = ShiroikumaUiPrefs.Category.entries.toList()

    fun categoryById(id: String): ShiroikumaUiPrefs.Category? =
        ShiroikumaUiPrefs.Category.entries.firstOrNull { it.id == id }

    data class Result(val lines: List<String>, val errors: List<String>) {
        val ok: Boolean get() = errors.isEmpty()
    }

    /** Raised when a caller cancels mid-export, so the writer unwinds at the next boundary. */
    class CancelledException : Exception("cancelled")

    /**
     * Write [parts] into [out] as a category ZIP.
     *
     * [onProgress] reports **real counts** — the 1-based *position* of the category being written,
     * the total actually being exported, and that category's id and label. Never a percentage.
     *
     * [isCancelled] is polled **between entries** so a cancel unwinds at a boundary rather than
     * tearing down mid-write; it raises [CancelledException] for the caller to handle.
     */
    fun export(
        context: Context,
        parts: Set<ShiroikumaUiPrefs.Category>,
        out: OutputStream,
        appVersion: String,
        onProgress: ((position: Int, total: Int, id: String, label: String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): Result {
        val lines = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val ordered = categories().filter { it in parts }
        val total = ordered.size

        ZipOutputStream(out.buffered()).use { zip ->
            val manifest = JSONObject().apply {
                put("app", "shiroikuma-shizuku")
                put("label", "白い熊 雫")
                put("version", appVersion)
                put("created", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                put("schema", 1)
                put("categories", ordered.joinToString(",") { it.id })
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString(2).toByteArray())
            zip.closeEntry()

            ordered.forEachIndexed { index, category ->
                if (isCancelled?.invoke() == true) throw CancelledException()
                // `current` is the POSITION of the category being written (1-based), so the panel's
                // highlight lands on the row actually in progress.
                onProgress?.invoke(index + 1, total, category.id, category.label)
                runCatching {
                    when (category) {
                        ShiroikumaUiPrefs.Category.FONTS_FILES -> {
                            val fonts = ShiroikumaFonts.fontsDir(context)
                                .listFiles()?.filter { it.isFile } ?: emptyList()
                            fonts.forEach { f ->
                                if (isCancelled?.invoke() == true) throw CancelledException()
                                zip.putNextEntry(ZipEntry("$FONTS_DIR${f.name}"))
                                f.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                            lines.add("${category.label}: ${fonts.size}")
                        }

                        else -> {
                            val values = ShiroikumaUiPrefs.exportCategory(context, category)
                            val json = JSONObject()
                            values.forEach { (k, v) -> json.put(k, v ?: JSONObject.NULL) }
                            zip.putNextEntry(ZipEntry("${category.id}.json"))
                            zip.write(json.toString(2).toByteArray())
                            zip.closeEntry()
                            lines.add("${category.label}: ${values.size}")
                        }
                    }
                }.onFailure {
                    if (it is CancelledException) throw it
                    errors.add("${category.label}: ${it.message}")
                }
            }
            onProgress?.invoke(total, total, "", "")
        }
        return Result(lines, errors)
    }

    /**
     * Write a backup **atomically** into [dir]: build `<name>.part`, and rename it to `<name>` only
     * after the archive is closed and complete. On any failure — including a cancel — the partial is
     * deleted on the way out.
     *
     * A killed export otherwise leaves a file indistinguishable from a real backup until someone
     * tries to restore it, and 白い熊 keeps every app's backups in one directory sorted by date, so a
     * truncated one silently becomes "the latest backup" of this app.
     */
    fun exportToDirAtomically(
        context: Context,
        parts: Set<ShiroikumaUiPrefs.Category>,
        dir: File,
        onProgress: ((position: Int, total: Int, id: String, label: String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): File {
        if (!dir.exists()) dir.mkdirs()
        val finalFile = File(dir, exportFileName())
        val partFile = File(dir, "${finalFile.name}.part")
        try {
            partFile.outputStream().use {
                export(context, parts, it, appVersion(context), onProgress, isCancelled)
            }
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            return finalFile
        } catch (t: Throwable) {
            partFile.delete()
            throw t
        }
    }

    /** Read a category ZIP and merge the selected [parts] back in. */
    fun import(context: Context, parts: Set<ShiroikumaUiPrefs.Category>, input: InputStream): Result {
        val lines = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val wantedIds = parts.map { it.id }.toSet()
        val counts = mutableMapOf<String, Int>()
        var fontCount = 0

        ZipInputStream(input.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                runCatching {
                    when {
                        name == MANIFEST -> Unit

                        name.startsWith(FONTS_DIR) && !entry!!.isDirectory -> {
                            if (ShiroikumaUiPrefs.Category.FONTS_FILES in parts) {
                                val target = File(
                                    ShiroikumaFonts.fontsDir(context),
                                    name.removePrefix(FONTS_DIR)
                                )
                                target.outputStream().use { zip.copyTo(it) }
                                fontCount++
                            }
                        }

                        name.endsWith(".json") -> {
                            val id = name.removeSuffix(".json")
                            if (id in wantedIds) {
                                val json = JSONObject(zip.readBytes().decodeToString())
                                val map = mutableMapOf<String, Any?>()
                                json.keys().forEach { key ->
                                    map[key] = if (json.isNull(key)) null else json.get(key)
                                }
                                ShiroikumaUiPrefs.importCategory(context, map)
                                counts[id] = map.size
                            }
                        }
                    }
                }.onFailure { errors.add("$name: ${it.message}") }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        categories().forEach { category ->
            when (category) {
                ShiroikumaUiPrefs.Category.FONTS_FILES ->
                    if (category in parts && fontCount > 0) lines.add("${category.label}: $fontCount")

                else -> counts[category.id]?.let { lines.add("${category.label}: $it") }
            }
        }
        if (lines.isEmpty() && errors.isEmpty()) errors.add("nothing matched the selected categories")
        return Result(lines, errors)
    }

    /** Our backups in [dir], newest first — filtered by the family prefix. */
    fun listBackups(dir: File?): List<File> {
        if (dir == null || !dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && isBackupFileName(f.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun exportDir(context: Context): File? =
        ShiroikumaUiPrefs.getString(context, ShiroikumaUiPrefs.KEY_EXPORT_DIR)
            .takeIf { it.isNotBlank() }
            ?.let { File(it) }

    fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    }.getOrDefault("0")
}
