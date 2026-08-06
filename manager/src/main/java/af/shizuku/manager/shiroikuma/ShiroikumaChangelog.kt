package af.shizuku.manager.shiroikuma

import android.content.Context
import timber.log.Timber

/**
 * FORK: the changelog the "What's New" dialog shows, read from the APK — never from the network.
 *
 * Upstream fetched GitHub release notes for a tag it built as `"v" + <version part>`, which is
 * upstream's tag convention. Our release tags carry no `v` and are the full fork versionName, so
 * every fetch 404'd and the dialog silently fell back to "couldn't load the release notes".
 *
 * Fixing the tag would not have been enough. 白い熊 installs every build and only some are
 * published, so an unpublished build has no release to fetch and would still show nothing. And
 * upstream's own notes can never be fetched at runtime at all — that is precisely the sort of
 * outbound path the no-phone-home rule removes.
 *
 * So `assets/changelog.md` is generated at build time by `generateBundledChangelog`
 * (manager/build.gradle): our fork changelog with a git-derived section for the running build
 * prepended, covering both our commits and the upstream delta. Reading it needs no network, works
 * offline, and works for a build that will never be published.
 */
object ShiroikumaChangelog {

    private const val ASSET_NAME = "changelog.md"

    /**
     * The whole bundled changelog, newest version first, or null if the asset is missing.
     */
    fun full(context: Context): String? = try {
        context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            .takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Timber.w(e, "Bundled changelog asset unavailable")
        null
    }

    /**
     * The section for [versionName] — the `## <versionName>` heading and everything up to the next
     * `## ` heading. Falls back to the newest section when the running build has no section of its
     * own, which happens only if the build-time generator could not run (no git, source tarball).
     *
     * Returning the *newest* section rather than the whole file matters: the file carries every
     * release ever made, and a dialog opened on update should say what changed in the build that
     * was just installed, not present 800 lines of history as though it were all new.
     */
    fun sectionFor(context: Context, versionName: String): String? {
        val text = full(context) ?: return null
        return extractSection(text, versionName) ?: extractFirstSection(text)
    }

    internal fun extractSection(text: String, versionName: String): String? {
        val lines = text.lines()
        // Match the heading exactly: a prefix match would let "…+001" be served by "…+0011", and
        // an unpublished "+001" must never be answered with a different build's prose.
        val start = lines.indexOfFirst { it.trimEnd() == "## $versionName" }
        if (start < 0) return null
        return sectionFrom(lines, start)
    }

    private fun extractFirstSection(text: String): String? {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith("## ") }
        if (start < 0) return null
        return sectionFrom(lines, start)
    }

    private fun sectionFrom(lines: List<String>, start: Int): String? {
        val rest = lines.drop(start + 1)
        val length = rest.indexOfFirst { it.startsWith("## ") }.let { if (it < 0) rest.size else it }
        return (listOf(lines[start]) + rest.take(length))
            .joinToString("\n")
            .trim()
            .takeIf { it.isNotBlank() }
    }
}
