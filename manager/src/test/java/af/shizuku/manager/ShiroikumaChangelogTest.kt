package af.shizuku.manager

import af.shizuku.manager.shiroikuma.ShiroikumaChangelog
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The "What's New" dialog picks ONE section out of the bundled changelog. Getting that wrong is
 * silent — the dialog still opens, it just describes a build the user is not running, which is the
 * same class of failure that made this fork's changelog useless before: it asked GitHub for a
 * release tag that could never exist and showed a fallback message forever, on every update.
 */
class ShiroikumaChangelogTest : FunSpec({

    val changelog = """
        # 白い熊 雫 — fork changelog

        Fork-only notes.

        ## 13.6.0.r2219.2026-08-05.gff8ea379+002

        ### From upstream

        - upstream commit subject

        ## 13.6.0.r2201.2026-08-01.g14550b5e+006

        ### Only one privileged server may exist

        Older release prose.

        ## 13.6.0.r2195+5

        Oldest.
    """.trimIndent()

    test("extracts the section for the requested version") {
        val section = ShiroikumaChangelog.extractSection(changelog, "13.6.0.r2201.2026-08-01.g14550b5e+006")!!
        section shouldContain "Only one privileged server may exist"
        section shouldContain "Older release prose."
    }

    test("stops at the next version heading") {
        val section = ShiroikumaChangelog.extractSection(changelog, "13.6.0.r2219.2026-08-05.gff8ea379+002")!!
        section shouldContain "upstream commit subject"
        // The whole point: a section must not bleed into the release below it.
        section shouldNotContain "Only one privileged server"
        section shouldNotContain "Oldest."
    }

    test("includes the last section when it is the newest") {
        ShiroikumaChangelog.extractSection(changelog, "13.6.0.r2195+5")!! shouldContain "Oldest."
    }

    test("does not match a version by prefix") {
        // "+001" must never be served the prose of "+0011", and a near-miss would be invisible to
        // the reader — the dialog would look perfectly normal while describing another build.
        ShiroikumaChangelog.extractSection(changelog, "13.6.0.r2219.2026-08-05.gff8ea379+00").shouldBeNull()
        ShiroikumaChangelog.extractSection(changelog, "13.6.0.r2195").shouldBeNull()
    }

    test("returns null for an unknown version so the caller can fall back") {
        ShiroikumaChangelog.extractSection(changelog, "9.9.9.r1+001").shouldBeNull()
    }

    test("keeps the heading line so the dialog shows which build it describes") {
        val section = ShiroikumaChangelog.extractSection(changelog, "13.6.0.r2195+5")!!
        section.lines().first() shouldBe "## 13.6.0.r2195+5"
    }
})
