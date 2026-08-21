---
name: publish-version
description: Publish the latest built shiroikuma-shizuku APK as a GitHub release of the fork — create the version tag (no "v" prefix), attach the APK, refresh the fork README + CHANGELOG-shiroikuma.md, and keep the GitHub default branch on `custom` so the repo page lands on our work. Use when 白い熊 says publish / release / cut a version / ship this build / make a GitHub release / publish the latest build.
---

# Publish a 雫 version to GitHub

Turn the latest tested build into a public GitHub **release** of the fork
(`ShiroiKuma0/shiroikuma-shizuku`): a version tag, the APK as a downloadable asset, refreshed
README + changelog, and the default branch on `custom` so the landing page shows our work.

> **This is outward-facing — it publishes to GitHub.** 白い熊 invoking this skill *is* the
> authorization. Still, summarise the exact version + assets first, then proceed. **Never publish a
> build 白い熊 hasn't tested.**

> **No `Co-Authored-By: Claude` / "Generated with Claude" trailer** in commits or release notes.

## ⛔ The changelog is written BEFORE the build it describes

The in-app "What's New" dialog reads `assets/changelog.md`, which `generateBundledChangelog`
(`manager/build.gradle`) bakes **at build time**. It compares the changelog's newest `## <version>`
heading against the version being built: equal → the file ships as written and the dialog shows the
real prose; different → it splices in a section generated from git commit subjects, headed
_"Not published yet — this summary is generated from git at build time."_

So writing the changelog *after* building — the obvious order, and the one every release through
`…+003` used — guarantees the shipped APK carries the generated summary. Publishing does not fix it:
the release notes on GitHub are right while the dialog inside the APK is a list of commit subjects,
and nothing can change that APK afterwards.

**Therefore: write the section for the version that is about to be built, then build, then test,
then publish that exact APK.** The version name is fully known before the build — it is what
`./gradlew` echoes at configuration time (`shiroikuma-shizuku <versionName> (versionCode …)`), i.e.
`UPSTREAM_VERSION_NAME` + the git pin + the *current* `BUILD_NUMBER`.

**Verify it, every time.** `verifyBundledChangelog` reports what is actually inside the built APK's
`assets/changelog.md`, on every build, in one of three forms:

```
>>> changelog asset: history only — written prose for <version>        <- correct: publish this
>>> changelog asset: generated section + history — NO written prose    <- WRONG: commit subjects ship
>>> changelog asset: NO section for <version> — newest heading is …    <- WRONG: dialog opens on an older release
```

Only the first is publishable. The other two mean the prose is missing or its heading does not match
the version being built — fix the heading and rebuild rather than publishing that APK.

**⛔ An absent line is a failure, not a pass.** Do not read "I didn't see the bad line" as success —
check the good line is actually *there*. The report is a separate always-running task precisely
because it used to live inside `generateBundledChangelog`, which declares inputs and an output and is
therefore skipped as UP-TO-DATE on most rebuilds; the line then never printed at all and this check
silently passed. That is how `r2279+001` came to ship the generated summary (2026-08-16). If no line
appears, the wiring is broken — investigate before publishing.

**Do not grep a truncated log.** `./gradlew buildApk … | tail -30` cuts the line off, which looks
identical to the line not existing. Grep the full output, or read the asset out of the APK directly —
the strongest check, and the one that settles any doubt:

```bash
# The first `## ` heading must be exactly the version being published.
unzip -p <the apk> assets/changelog.md | grep -m1 '^## '

# Match the placeholder as a WHOLE LINE, never as a loose phrase. The changelog's own prose
# discusses this mechanism by name, so `grep -c "Not published yet"` matches that discussion
# and false-alarms on a perfectly good APK (it did, on r2292+002). `manager/build.gradle`
# defines the marker once as `bundledChangelogGeneratedMarker` and matches it as a line for
# exactly this reason — use the same shape here.
unzip -p <the apk> assets/changelog.md \
  | grep -cxF '_Not published yet — this summary is generated from git at build time._'   # must be 0
```

## What gets published

The **latest APK in `~/tmp/`** — the build 白い熊 just tested on-device. Derive the version from the
**APK filename**, NOT `gradle.properties` (whose `BUILD_NUMBER` is already the *next* number,
because `buildApk` bumps it after building).

```bash
APK=$(ls -t ~/tmp/shiroikuma-shizuku_*.apk 2>/dev/null | head -1)
VERSION=$(basename "$APK" | sed -E 's/^shiroikuma-shizuku_(.+)_arm64-v8a\.apk$/\1/')   # e.g. 13.6.0.r2178+1
TAG="$VERSION"   # bare version, no "v" prefix
```

If `$APK` is empty, stop and tell 白い熊 there is no built APK to publish (run `build-apk` first).

> **The tag format matters here.** The in-app update checker
> (`manager/…/update/UpdateChecker.kt`) reads this repo's releases and compares tag names against
> the running `versionName`. Keep the tag exactly equal to the fork `versionName`
> (`<UPSTREAM_VERSION_NAME>+<N>`, no `v`) so it never offers a "newer" version that is really the
> one already installed. Note upstream tags **do** carry a `v` — ours deliberately do not.

## Preconditions

1. **The APK matches `HEAD`.** If the working tree has uncommitted source changes, or `HEAD` moved
   past the build, warn — the safe path is to rebuild via `build-apk` so the APK and the tag agree.
   Never publish a tag pointing at code the APK wasn't built from.
2. **On `custom`** (`git rev-parse --abbrev-ref HEAD` = `custom`) and pushed.
3. **The tag doesn't already exist** (`git tag -l "$TAG"` empty and `gh release view "$TAG"` 404s).
   If it exists, confirm with 白い熊 before re-cutting.
4. **The APK bundles the written prose**, per the section above. Check the changelog's newest
   heading against the version being published:
   ```bash
   grep -m1 '^## ' CHANGELOG-shiroikuma.md      # must read "## $VERSION"
   ```
   If it doesn't match, the tested APK carries the generated summary — go to phase A below rather
   than publishing it.

## Two phases, with 白い熊's testing between them

Because the prose has to be baked in at build time, a publish that starts from an APK with no
written section is **two** passes, not one:

- **Phase A — write and rebuild.** Write the README + changelog section for the version that
  `BUILD_NUMBER` is about to produce (steps 2–3), then `build-apk`, which delivers via
  `/after-build`. Confirm `>>> changelog asset: history only`. **Stop there** and hand the build to
  白い熊 — the standing rule is that nothing is published untested. Do not commit the docs yet; they
  ride along with the release commit in phase B.
- **Phase B — publish.** On 白い熊's go-ahead, run steps 1 and 4–6 against that tested APK.

When the tested APK already has its section (phase A ran earlier, or the changelog was written
before the build as a matter of course), publish straight through — no rebuild.

The rebuild in phase A changes exactly one file in the APK, `assets/changelog.md`. Say so when
handing it over, so the retest is a spot-check of the What's New dialog rather than another pass
over the whole app.

## Steps

1. **Keep the GitHub default branch on `custom`** so the repo page lands on our README, not
   upstream's master (idempotent — safe to run every time):
   ```bash
   gh repo edit ShiroiKuma0/shiroikuma-shizuku --default-branch custom
   gh repo edit ShiroiKuma0/shiroikuma-shizuku \
     --description "白い熊 雫 — a fork of ShizukuPlus: run privileged (adb/root) APIs for other apps, de-branded, black-yellow, side-by-side installable. Apache-2.0."
   ```

2. **Update the README badge** — point the "Latest release" line at the new version. *(Phase A: write
   it now, commit it in phase B.)*

3. **Update `CHANGELOG-shiroikuma.md`** — **phase A, before the build.** Upstream owns `CHANGES.md`;
   **our** fork changelog is `CHANGELOG-shiroikuma.md` — never fold fork notes into an upstream file.
   Add a `## <new version>` section at the top, above every existing one, summarising what changed
   **since the last tag**:
   ```bash
   git log --oneline <previous-tag>..HEAD
   ```
   Group by area (service & starter / bridges / manager UI / look / fixes), one specific bullet
   each — not raw commit subjects.

   **The heading is a bare `## <version>` on its own line** — no ` — current` suffix, no trailing
   text. `generateBundledChangelog` matches `^##[ \t]+(\S+)[ \t]*$` and takes the **first** such
   heading as "the newest documented release", so a heading with anything after the version does not
   match the build and the generated summary ships instead. The extraction in step 5 relies on the
   same shape.

4. **Commit the docs** on `custom` and push:
   ```bash
   git add README.md CHANGELOG-shiroikuma.md
   git commit -m "Release <VERSION>: README + changelog"
   git push origin custom
   ```

5. **Tag and release.** Annotated tag at `HEAD`, then a GitHub release targeting `custom` with the
   APK attached. **Always pin the repo with `-R ShiroiKuma0/shiroikuma-shizuku`** — the working copy
   has an `upstream` remote (`thejaustin/ShizukuPlus`), and a bare `gh release` will otherwise 404
   against upstream. Write the notes to a real file under `~/tmp` (do **not** rely on `$TMPDIR`,
   which is unset when the sandbox is off):
   ```bash
   REPO=ShiroiKuma0/shiroikuma-shizuku
   git tag -a "$TAG" -m "白い熊 雫 $VERSION"
   git push origin "$TAG"
   NOTES="$HOME/tmp/shizuku_release_notes.md"
   # Everything under this version's heading, stopping at the next one. `index(...)==1` is a literal
   # prefix match, so the `+` and `.` in the version are never read as regex metacharacters — and it
   # matches the bare heading step 3 mandates. (The old `/^## ${VERSION} —/` pattern silently
   # produced EMPTY notes: no heading in this file has ever carried that em dash.)
   awk -v v="## $VERSION" 'index($0,v)==1{f=1;next} f && /^## [0-9]/{exit} f{print}' \
     CHANGELOG-shiroikuma.md > "$NOTES"
   [ -s "$NOTES" ] || { echo "empty release notes for $VERSION — check the heading"; exit 1; }
   gh release create "$TAG" "$APK" -R "$REPO" \
     --target custom \
     --title "白い熊 雫 $VERSION" \
     --notes-file "$NOTES"
   rm -f "$NOTES"
   ```
   Keep the APK asset name as built (`shiroikuma-shizuku_<VERSION>_arm64-v8a.apk`).

6. **Report** the release URL and confirm the default branch:
   ```bash
   gh release view "$TAG" -R ShiroiKuma0/shiroikuma-shizuku --json url -q .url
   gh repo view ShiroiKuma0/shiroikuma-shizuku --json defaultBranchRef -q .defaultBranchRef.name
   ```

## Notes

- `git push`, `gh` and `scp` need `~/.ssh` / `~/.config/gh`, which the command sandbox blocks — run
  the push / `gh` / tag steps with `dangerouslyDisableSandbox: true`, same as the other fork skills.
  Writes anywhere under `~/git` need it too.
- **Phase B does not build** — it ships the tested APK in `~/tmp/`. Phase A builds exactly once, via
  `build-apk`, and only to bake the written changelog into `assets/changelog.md`; when the tested APK
  already carries its section there is no phase A at all. Never rebuild between 白い熊's test and the
  release — the tag must point at the code they tested.
- `master` stays tracking upstream; releases are always cut from `custom`. After an
  `upstream-new-version` rebase, the first release on the new base is `+1`.
- Upstream's own `.github/workflows/` release pipeline is **not** used — it signs with a repo secret
  we don't have and uploads Sentry symbols to their org. Our releases are cut by hand from this skill.
