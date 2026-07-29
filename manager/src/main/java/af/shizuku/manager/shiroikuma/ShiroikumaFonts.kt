package af.shizuku.manager.shiroikuma

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File

/** One pickable font. [fileName] = "" (system), the [MONOSPACE]/[SERIF] sentinels, or an imported file. */
data class FontOption(val displayName: String, val fileName: String)

/**
 * The 白い熊 雫 font system — the sister repos' external-font support, in spirit.
 *
 * Built-in families plus any `.ttf`/`.otf` 白い熊 imports through the document picker into the app's
 * private `fonts/` directory. Typefaces are loaded by file and cached; a bad or missing file falls
 * back to the default rather than throwing, so a deleted font can never brick the UI.
 *
 * The picker renders every option **in its own glyphs**, so you choose by looking at the typeface
 * rather than at its filename.
 */
object ShiroikumaFonts {
    const val SYSTEM = ""
    const val MONOSPACE = "@monospace"
    const val SERIF = "@serif"
    private val EXTENSIONS = setOf("ttf", "otf")

    private val cache = HashMap<String, Typeface>()

    fun fontsDir(context: Context): File =
        File(context.applicationContext.filesDir, "fonts").apply { if (!exists()) mkdirs() }

    /** System + Monospace + Serif + every imported font, sorted by name. */
    fun availableFonts(context: Context): List<FontOption> {
        val options = mutableListOf(
            FontOption("System default", SYSTEM),
            FontOption("Monospace", MONOSPACE),
            FontOption("Serif", SERIF)
        )
        fontsDir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { options.add(FontOption(it.nameWithoutExtension, it.name)) }
        return options
    }

    fun displayName(context: Context, family: String): String = when {
        family.isEmpty() -> "System default"
        family == MONOSPACE -> "Monospace"
        family == SERIF -> "Serif"
        else -> File(family).nameWithoutExtension
    }

    /** The base [Typeface] for a stored family value (cached). */
    fun typeface(context: Context, family: String): Typeface = when {
        family.isEmpty() -> Typeface.DEFAULT
        family == MONOSPACE -> Typeface.MONOSPACE
        family == SERIF -> Typeface.SERIF
        else -> cache.getOrPut(family) {
            try {
                Typeface.createFromFile(File(fontsDir(context), family))
            } catch (_: Exception) {
                Typeface.DEFAULT
            }
        }
    }

    /** The family combined with a numeric weight 100..900 (<=0 = the family's own weight). */
    fun weighted(context: Context, family: String, weight: Int): Typeface {
        val base = typeface(context, family)
        if (weight <= 0) return base
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight.coerceIn(1, 1000), false)
        } else {
            Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** The typeface the app's UI should use right now. */
    fun current(context: Context): Typeface = weighted(
        context,
        ShiroikumaUiPrefs.getString(context, ShiroikumaUiPrefs.KEY_FONT_FAMILY),
        ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_FONT_WEIGHT)
    )

    /** Copy a picked font into the private fonts dir; returns its file name, or null on failure. */
    fun importFont(context: Context, uri: Uri): String? {
        val name = fileName(context, uri) ?: return null
        if (name.substringAfterLast('.', "").lowercase() !in EXTENSIONS) return null
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            File(fontsDir(context), name).writeBytes(bytes)
            cache.remove(name)
            name
        } catch (_: Exception) {
            null
        }
    }

    fun deleteFont(context: Context, fileName: String): Boolean {
        cache.remove(fileName)
        return runCatching { File(fontsDir(context), fileName).delete() }.getOrDefault(false)
    }

    private fun fileName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { return it }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
