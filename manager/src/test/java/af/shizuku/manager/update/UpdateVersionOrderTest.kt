package af.shizuku.manager.update

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * FORK GUARD — the update checker must keep seeing our own build counter.
 *
 * `UpdateChecker.parseVersionCode` is what decides whether a published release is newer than the
 * installed build. It reads two numbers out of the version name: upstream's `rNNNN` and **our**
 * `+NNN` counter. Upstream's number alone is not enough — several fork releases usually sit on one
 * upstream base, and without the counter they all compare equal and no update is ever offered.
 *
 * That is exactly what the 2026-08-12 version-format change broke. `+` became the separator opening
 * each top-level group, so `13.6.0.r2246+2026-08-12.02-46.g9f2c01e8+002` contains **two** `+N`
 * groups and the old first-match regex read `+2026` — clamped to 999, identical for every build.
 * The bug is invisible on the build machine and only shows up as "Check for updates" quietly
 * reporting no update, forever.
 *
 * So this pins the ordering contract itself rather than the regex: consecutive counters must
 * compare, and both version-name formats must parse to their real counter.
 */
class UpdateVersionOrderTest : FunSpec({

    val base = "13.6.0.r2246+2026-08-12.02-46.g9f2c01e8"

    test("the counter is read from the current version-name format") {
        UpdateChecker.parseVersionCode("$base+002") shouldBe 2246002
        UpdateChecker.parseVersionCode("$base+014") shouldBe 2246014
    }

    test("a newer build on the same upstream base is newer") {
        UpdateChecker.parseVersionCode("$base+003") shouldBeGreaterThan
            UpdateChecker.parseVersionCode("$base+002")
    }

    test("the pin's date is never mistaken for the counter") {
        // +2026 would clamp to 999 and tie every build on this base against every other.
        UpdateChecker.parseVersionCode("$base+002") shouldBe
            UpdateChecker.parseVersionCode("13.6.0.r2246.2026-08-12.g9f2c01e8+002")
    }

    test("the formats published before 2026-08-12 still parse to their counter") {
        UpdateChecker.parseVersionCode("13.6.0.r2201.2026-08-01.g14550b5e+004") shouldBe 2201004
        // The oldest tags predate the zero-padding entirely.
        UpdateChecker.parseVersionCode("13.6.0.r2178+14") shouldBe 2178014
    }

    test("an upstream sync outranks the counter it resets") {
        UpdateChecker.parseVersionCode("13.6.0.r2250+2026-08-14.09-00.gdeadbeef+001") shouldBeGreaterThan
            UpdateChecker.parseVersionCode("$base+002")
    }

    test("an unparseable name sorts below everything rather than throwing") {
        UpdateChecker.parseVersionCode("") shouldBe 0
        UpdateChecker.parseVersionCode("nightly") shouldBe 0
    }
})
