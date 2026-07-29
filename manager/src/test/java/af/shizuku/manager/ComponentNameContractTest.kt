package af.shizuku.manager

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * FORK GUARD — the applicationId / namespace trap.
 *
 * This fork's `applicationId` (`shiroikuma.shizuku`) deliberately differs from its code `namespace`
 * (`af.shizuku.manager`), because renaming the namespace would turn every upstream rebase into a
 * mass-conflict. The cost is that **any component reference built by assuming the two are equal is
 * wrong** — the `pkg/.Receiver` shorthand expands against the applicationId and names a class that
 * does not exist.
 *
 * Where the module graph allows it we never spell such a reference out: `ShizukuPlusSettingsFragment`
 * derives the Device Owner component with `ComponentName(context, DhizukuAdminReceiver::class.java)`,
 * so a rename moves it automatically.
 *
 * The `:compat` module cannot do that — it does not (and must not) depend on `:manager`, so its two
 * forwarders address us with **string literals**. Those are exactly the references that rot silently:
 * rename the target and nothing complains until the Compat Hub stops working on a device.
 *
 * So this reads compat's real source, extracts the literals it actually ships, and checks each one
 * still points at something that exists. A rename now fails the build instead of the device.
 */
class ComponentNameContractTest : FunSpec({

    /** JVM unit tests run with the module directory as the working directory. */
    val moduleDir = File(".").canonicalFile
    val repoRoot = moduleDir.parentFile

    val compatSources = listOf(
        "compat/src/main/java/moe/shizuku/privileged/api/ForwardActivity.java",
        "compat/src/main/java/moe/shizuku/privileged/api/ForwardReceiver.java"
    )

    /** `setClassName("<package>", "<fully.qualified.Class>")` */
    val setClassName = Regex("""setClassName\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")

    data class Reference(val source: String, val pkg: String, val className: String)

    fun references(): List<Reference> = compatSources.flatMap { rel ->
        val file = File(repoRoot, rel)
        check(file.isFile) { "Expected compat source at ${file.path} — has the module moved?" }
        setClassName.findAll(file.readText())
            .map { Reference(rel, it.groupValues[1], it.groupValues[2]) }
            .toList()
    }

    test("compat forwarders address a package matching our applicationId") {
        val refs = references()
        withClue("Found no setClassName(...) forwards in compat — has the forwarding changed shape?") {
            refs shouldNotBe emptyList<Reference>()
        }
        refs.forEach { ref ->
            withClue(
                "${ref.source} targets package '${ref.pkg}', but this app installs as " +
                    "'${BuildConfig.APPLICATION_ID}'. The Compat Hub would forward stock-Shizuku " +
                    "clients into a package that does not exist."
            ) {
                ref.pkg shouldBe BuildConfig.APPLICATION_ID
            }
        }
    }

    test("compat forwarders address classes that still exist") {
        references().forEach { ref ->
            // Resolved on the filesystem rather than with Class.forName: these types extend Android
            // framework classes that are not on the unit-test classpath, so loading them would fail
            // for reasons that have nothing to do with the name being right.
            val path = ref.className.replace('.', '/')
            val candidates = listOf(
                File(moduleDir, "src/main/java/$path.kt"),
                File(moduleDir, "src/main/java/$path.java")
            )
            withClue(
                "${ref.source} names '${ref.className}', but no source file exists for it (looked " +
                    "for ${candidates.joinToString(" and ") { it.path }}). It was probably renamed " +
                    "or moved — update the compat forwarder to match."
            ) {
                candidates.any { it.isFile } shouldBe true
            }
        }
    }

    // ---- native ---------------------------------------------------------------------------
    // The rename that created this fork swept .kt/.java/.xml and MISSED .cpp, so the native
    // starter kept querying upstream's application ids and every manual
    // `adb shell .../libshizuku.so` died with "fatal: can't get path of manager". These lock the
    // replacement wiring in place.

    test("the native starter does not hardcode an application id") {
        val starter = File(moduleDir, "src/main/jni/starter.cpp")
        check(starter.isFile) { "starter.cpp not found at ${starter.path}" }
        val text = starter.readText()

        withClue(
            "starter.cpp names upstream's own applicationId. It resolves the manager APK with " +
                "`pm path`, so that would load a DIFFERENT app's APK whenever upstream's build is " +
                "installed alongside ours."
        ) {
            text shouldNotContain "\"af.shizuku.plus.api\""
        }
        withClue(
            "starter.cpp no longer takes its package from SHIROIKUMA_PACKAGE_NAME — the injected " +
                "applicationId. Hardcoding it here is what broke the manual ADB start."
        ) {
            text shouldContain "SHIROIKUMA_PACKAGE_NAME"
        }
    }

    test("the applicationId is injected into the native build") {
        val cmake = File(moduleDir, "src/main/jni/CMakeLists.txt")
        val gradle = File(moduleDir, "build.gradle")
        check(cmake.isFile && gradle.isFile) { "native build files not found" }

        withClue("CMakeLists.txt no longer turns SHIROIKUMA_PACKAGE_NAME into a compile definition.") {
            cmake.readText() shouldContain "target_compile_definitions"
        }
        withClue(
            "manager/build.gradle no longer passes -DSHIROIKUMA_PACKAGE_NAME to CMake, so the " +
                "native starter would fall back to its compiled-in default and drift from APP_ID."
        ) {
            gradle.readText() shouldContain "-DSHIROIKUMA_PACKAGE_NAME="
        }
        withClue("build.gradle should derive the value from the APP_ID property, not spell it out.") {
            gradle.readText() shouldContain "providers.gradleProperty(\"APP_ID\")"
        }
    }

    test("applicationId and namespace stay distinct") {
        // If these ever became equal the shorthand forms would start working by accident, someone
        // would use one, and the next time they diverged it would break silently — in the Device
        // Owner path, where the cost of being wrong is a factory reset.
        BuildConfig.APPLICATION_ID shouldBe "shiroikuma.shizuku"
        // BuildConfig is generated into the namespace package, so reading its own package name
        // means this assertion cannot go stale.
        BuildConfig::class.java.`package`?.name shouldBe "af.shizuku.manager"
    }
})

private inline fun withClue(clue: String, block: () -> Unit) {
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError(clue, e)
    }
}
