---
name: upstream-new-version
description: Sync the shiroikuma-shizuku fork onto a newer upstream ShizukuPlus (thejaustin/ShizukuPlus) and rebuild. Checks upstream for new commits, and also reports the delta from the reference remote thedjchi/Shizuku (what ShizukuPlus has not yet absorbed from its own base); ALWAYS presents a proceed-gated tabular summary BEFORE any rebase; then fast-forwards master, rebases custom, resets BUILD_NUMBER, and builds the new +1. Use when 白い熊 runs /upstream-new-version, says a new ShizukuPlus version is out, or asks to update/sync/bump to upstream, rebase custom onto upstream, or rebase-and-rebuild the fork.
---

# Sync shiroikuma-shizuku onto a newer upstream ShizukuPlus

This fork tracks [thejaustin/ShizukuPlus](https://github.com/thejaustin/ShizukuPlus).
`master` mirrors `upstream/master` (fast-forward only, never carries fork work); `custom` carries all
our patches and is rebased onto each new upstream tip.

> **Never `git push`, `git commit` or `adb install` unprompted.** After the rebase + build you stop
> and let 白い熊 test on-device. You push only when they explicitly say **"Push"**.

## Branch / remote model

| Branch | Role | Update mode |
| --- | --- | --- |
| `master` | Mirrors `upstream/master`. No fork work here. | fast-forward only |
| `custom` | Our patches; the working/dev branch. Default branch on GitHub. | rebased onto `master` each sync |

- `origin` = `git@github.com:ShiroiKuma0/shiroikuma-shizuku.git` (ssh, **push here**).
- `upstream` = `https://github.com/thejaustin/ShizukuPlus.git` (https, **fetch only**) — the **only**
  sync source. `master` fast-forwards from here and nowhere else. Carries
  `fetchRecurseSubmodules=no`: upstream pins `api` commits it never published, so a recursing fetch
  always ends in `Errors during submodule fetch: api`. Step 3 runs `git submodule update` explicitly,
  so nothing here depends on fetch-time recursion. **If that error reappears, the config was lost**
  (fresh clone) — re-apply it rather than treating the error as meaningful.
- `djchi` = `https://github.com/thedjchi/Shizuku.git` — upstream's *own* base, added as a
  **reference remote only** (push URL `DISABLED`, `fetchRecurseSubmodules=no`). We read it to see
  what ShizukuPlus has not yet absorbed. **We never merge or rebase onto it** — see Step 1b.
- Pin `gh` with `-R ShiroiKuma0/shiroikuma-shizuku` — the `upstream` remote otherwise wins.
- **`api/` is a git submodule** (`thejaustin/ShizukuPlus-API`). Upstream bumps its pinned commit
  from time to time; after any sync run `git submodule update --init --recursive` before building.

## Versioning

Upstream computes `versionCode` from `git rev-list --count HEAD` and builds `versionName` from the
same count (`Shizuku+ 13.6.0.r2178`). **We cannot use that formula** — on `custom` the count includes
our own commits, so every fork commit would inflate the version and it would stop meaning "the
upstream release we are based on". Instead `gradle.properties` **pins** upstream's numbers:

| Property | Meaning | Who updates it |
| --- | --- | --- |
| `UPSTREAM_VERSION_CODE` | upstream's `versionCode` (its commit count) at our base, e.g. `2178` | **this skill**, each sync |
| `UPSTREAM_VERSION_NAME` | upstream's version, brand prefix stripped, e.g. `13.6.0.r2178` | **this skill**, each sync |
| `BUILD_NUMBER` | our increment | `buildApk` bumps it; **this skill resets it to 1** |

- Fork `versionName = "<UPSTREAM_VERSION_NAME>+<BUILD_NUMBER>"` → `13.6.0.r2178+1`.
- Fork `versionCode = UPSTREAM_VERSION_CODE * 10000 + BUILD_NUMBER` → `21780001`.
- **Sanity-check the ceiling**: `new_code * 10000 + 9999` must stay under Android's hard limit of
  `2100000000`, i.e. `UPSTREAM_VERSION_CODE` must stay under **210000**. It grows by one per
  upstream commit (currently ~2178), so there is room for decades — but if upstream ever switched to
  a date-style code (e.g. `20260726`), stop and re-plan the multiplier with 白い熊 rather than
  shipping an uninstallable APK.

## Step 1 — check for a newer upstream version

```bash
cd ~/git/shiroikuma-shizuku
git fetch upstream --tags
git fetch origin

if git merge-base --is-ancestor upstream/master master; then
  echo ">>> No new upstream version — master is already at or above upstream/master."
else
  echo ">>> $(git rev-list --count master..upstream/master) new upstream commit(s)."
  echo ">>> our base: $(grep UPSTREAM_VERSION_NAME gradle.properties)"
  echo ">>> newest upstream tag: $(git tag --sort=-creatordate | head -1)"
  echo ">>> new upstream commit count: $(git rev-list --count upstream/master)"
fi
```

The new `UPSTREAM_VERSION_CODE` is `git rev-list --count upstream/master`; the new
`UPSTREAM_VERSION_NAME` is `13.6.0.r<that count>` — cross-check it against upstream's newest release
tag (`gh release view -R thejaustin/ShizukuPlus`), and against `baseVersionName` in upstream's
`build.gradle` in case they bump `13.6.0` itself.

If nothing is new, do not ff, do not rebase, do not build — but **still run Step 1b** before
reporting, so "we are up to date" covers the reference remote too.

## Step 1b — check the `djchi` reference remote

`thejaustin/ShizukuPlus` is itself a fork of `thedjchi/Shizuku`, and it does not always absorb
djchi's work promptly. This step reports that gap. It **never** changes a branch.

> **⛔ Never `git merge djchi/master`, never `git rebase` onto it, never add it as a `master` source.**
> ShizukuPlus **replays** djchi's history instead of fast-forwarding from it, so the git-level merge
> base collapses to `12fcfad3` — 2017-06-28, djchi's 28th commit. Git therefore reports well over a
> thousand commits as "missing" when only a few dozen are, and a merge would drag in ~1300
> duplicates, each conflicting against ShizukuPlus's rewrite of the same file. Anything taken from
> djchi is taken as an **individual cherry-pick**, decided by 白い熊, item by item.

```bash
cd ~/git/shiroikuma-shizuku
git fetch djchi
. .claude/skills/upstream-new-version/djchi-base

new=$(git rev-list --count "$DJCHI_REVIEWED_SHA"..djchi/master)
echo ">>> reviewed through: $DJCHI_REVIEWED_SHA ($DJCHI_REVIEWED_DATE, $DJCHI_REVIEWED_VERSION)"
echo ">>> djchi tip:        $(git log -1 --format='%h %ci %s' djchi/master)"
echo ">>> NEW since review: $new commit(s)"
[ "$new" -gt 0 ] && git log --reverse --format='%h | %ci | %s' "$DJCHI_REVIEWED_SHA"..djchi/master
```

Then, for each new commit, check whether thejaustin has **already ported it** before proposing
anything. Subject matching alone is not sufficient — he re-homes ported code into different modules
under new subjects (the Android 17 cluster went into `common/…/compat/`, not djchi's
`server/…/util/`). Grep our tree for the *identifiers* the commit introduces, not its message.

**Report, do not act.** Fold the result into the Step 2 gate as a short section:

| Column | What goes in it |
| --- | --- |
| **Commit** | short SHA |
| **What djchi changed** | plain-language, from the commit body |
| **Already in our tree?** | absorbed by thejaustin (say where it landed) / absent / solved differently by us |
| **Worth taking?** | Yes / No / Ask — with the reason, and the conflict surface if yes |

Also re-surface the **outstanding** rows already recorded in `djchi-base` (currently the
package-name de-hardcoding cluster) — they were reviewed and deliberately deferred, not resolved,
and they do not expire.

If 白い熊 approves a cherry-pick, mind the path map — the modules diverged unevenly:

| djchi path | Our path | Pick cleanly? |
| --- | --- | --- |
| `server/…/rikka/shizuku/server/…` | same | yes — namespace never renamed |
| `shell/…/rikka/shizuku/shell/…` | same | yes |
| `manager/…/moe/shizuku/manager/…` | `manager/…/af/shizuku/manager/…` | needs `moe` → `af` remapping (ShizukuPlus renamed it in `d44de470`) |
| `starter/…/moe/shizuku/starter/ServiceStarter.java` | `starter/…/af/shizuku/starter/ServiceStarter.kt` | **no** — rewritten Java → Kotlin; hand port only |

After a cherry-pick is accepted **or** a new commit is reviewed and rejected, update
`DJCHI_REVIEWED_SHA` in `djchi-base` to the reviewed tip and add a ledger line saying which it was.
Never advance it past commits nobody looked at.

If nothing is new here and nothing is new upstream, **stop** — report both as up to date.

## Step 2 — ⛔ proceed-gated table of what the new upstream version introduces

**Mandatory on every single sync. Present the table, then WAIT.** Do not fast-forward `master`, do
not rebase, do not build until 白い熊 explicitly says proceed / continue / yes. The rebase is never
started silently — this is 白い熊's standing request, made when the fork was created.

Capture the old tip **before** any fast-forward, and read the commits:

```bash
old=$(git rev-parse master)     # capture BEFORE the Step 3 ff
git log --format='%h | %an | %s' "$old"..upstream/master
git log --stat --format='%n### %h  %s%n%b' "$old"..upstream/master   # bodies + files touched
git diff --stat "$old"..upstream/master
gh release view -R thejaustin/ShizukuPlus --json tagName,name,body -q '.tagName + "\n" + .body' | head -80
git show upstream/master:CHANGES.md | head -60
```

Present a **Markdown table**, one row per non-trivial change (fold the recurring Crowdin/i18n
translation commits into a single "translations" row), with these columns:

| Column | What goes in it |
| --- | --- |
| **Commit** | short SHA |
| **Area** | subsystem — privileged server (`:server`), starter / `app_process` bootstrap, ADB pairing & wireless debugging, root / `libsu` path, permission model & authorization UI, the Plus bridges (Storage / AI / Theming / SU Bridge), manager UI (home, logs, settings), widgets & shortcuts, Compat Hub (`:compat`), API submodule, build/CI |
| **What it changes** | a plain-language sentence drawn from the commit *body*, not just the subject — what is actually new or fixed, described so 白い熊 can judge it without reading the diff |
| **Relevance to this fork** | **High / Medium / Low, and why** — does it touch a file in our customization layer (identity, versioning, signing, icon, de-branding strings, the update checker's repo URL, the Sentry kill-switch), the `api` submodule pin, or a feature 白い熊 actually uses? Flag anything likely to **conflict on rebase** and anything that is a **genuinely useful fix** |

Then add a short **"New features"** section in prose for anything user-visible that the table's
one-liners undersell (a new bridge, a new pairing flow, a new settings screen, a new widget), call
out explicitly any change to **how the privileged service is started or kept alive** (that is the
part most likely to break on a rebase and the part 白い熊 will notice first), and end with a
one-line takeaway.

Finally append the **Step 1b `djchi` section** — the reference-remote delta plus the outstanding
ledger rows. Keep it visibly separate from the upstream table: the upstream rows are what the
go-ahead *applies to*, the djchi rows are a menu that only moves on an explicit per-item "take this".
A "proceed" answers the rebase, never a cherry-pick.

**Then stop and wait for the go-ahead.**

## Step 3 — fast-forward master, rebase custom (after the go-ahead)

```bash
cd ~/git/shiroikuma-shizuku
git status --short          # must be clean before rebasing

git checkout master
git merge --ff-only upstream/master

git checkout custom
git rebase master

git submodule update --init --recursive    # upstream may have moved the api/ pin
```

Do **not** push here — both pushes are deferred to Step 7. If the rebase goes irrecoverable,
`git rebase --abort` and re-plan with 白い熊 (an aborted rebase leaves `custom` untouched; `master`
stays safely fast-forwarded).

## Step 4 — reconcile conflicts

Re-derive the *intent* against the new upstream files rather than blindly taking either side. If
upstream restructured a file we patch, port our change to the new structure.

**If the conflicts are significant, stop and plan with 白い熊 before continuing.**

Conflict-prone files, and the shape each must end up in:

- **`build.gradle`** (root) — our fork-version block replaces upstream's
  `versionCode = gitCommitCount` / `versionName = "Shizuku+ …"`. Keep ours; do **not** let the
  rebase restore the git-commit-count formula. `getGitCommitCount()` itself may stay (unused).
- **`gradle.properties`** — keep `UPSTREAM_VERSION_CODE`, `UPSTREAM_VERSION_NAME`, `BUILD_NUMBER`
  and `APP_ID`. (`SENTRY_DSN` is no longer read from here — the BuildConfig value is hardwired empty
  in `manager/build.gradle`. If a rebase reintroduces the property, leave it empty.)

- **⛔ The no-phone-home layer — re-verify ALL of it after every sync.** This is a standing
  requirement (白い熊, 2026-07-29): the app sends nothing to upstream and nothing anywhere else.
  Upstream actively develops these paths, so each sync will try to bring them back:

  | File | What must hold |
  | --- | --- |
  | `settings.gradle`, `manager/build.gradle` | the `io.sentry.android.gradle` plugin is **not** applied and upstream's `sentry { … }` block is **absent** (it uploads mappings + native symbols to sentry.io) |
  | `manager/build.gradle` | `buildConfigField "String", "SENTRY_DSN", "\"\""` — hardwired empty, never read from a property |
  | `manager/…/ShizukuApplication.kt` | `initializeSentryEarly()` returns **before** `SentryAndroid.init()`; the `RemoteDbSyncWorker.schedule(this)` call stays **removed** |
  | `manager/src/main/AndroidManifest.xml` | `io.sentry.dsn` empty, `io.sentry.auto-init` false |
  | `manager/…/worker/RemoteDbSyncWorker.kt` | `schedule()` **cancels** the work; `doWork()` is a no-op; no URL, no fetch |
  | `manager/…/settings/AdvancedSettingsFragment.kt` | "update app database" does **not** fetch `apps.json` |
  | `manager/…/installer/verifier/VirusTotalClient.kt`, `PithusClient.kt` | no network call; both return the "disabled" result |
  | `manager/…/ShizukuSettings.java` | `isAutoUpdateEnabled()` defaults **false** (upstream defaults true → a startup poll on every launch) |
  | `manager/…/settings/BugReportDialog.kt` | no "email support" button (it reported device/OS/version to the upstream author's address) |
  | `manager/src/main/res/values/strings_untranslatable.xml` | `support_email` empty |
  | `.github/` | still **absent** — `app.yml` injected a Sentry DSN and uploaded debug symbols, and triggered on pushes to `master`, which we push every sync |

  Quick audit — anything that fetches must be `UpdateChecker` only:
  ```bash
  grep -rn "openConnection\|OkHttpClient" --include=*.kt --include=*.java \
    manager/src/main server/src/main common/src database/src
  ```
- **`manager/build.gradle`** — keep all of:
  1. `applicationId = providers.gradleProperty("APP_ID").get()` in `defaultConfig` **and** in the
     `shizukuplus` flavor (namespace `af.shizuku.manager` stays **unchanged**).
  2. `resValue "string", "app_name", "白い熊 雫"` in the `shizukuplus` flavor.
  3. The single-ABI `ndk { abiFilters "arm64-v8a" }`.
  4. The `buildApk` task at the end of the file.
- **`signing.properties`** is gitignored and survives rebases; `signing.gradle` is upstream's.
- **`.gitignore`** — upstream ignores `CLAUDE.md`; **we do not**. Keep `CLAUDE.md` and
  `.claude/skills/` tracked, and keep `/signing.properties` + `.claude/settings.local.json` ignored.
- **The app-id rename.** Upstream hardcodes its own applicationId in a dozen places; every one must
  read `shiroikuma.shizuku` after the rebase. Re-check with:
  ```bash
  grep -rn "af\.shizuku\.plus\.api" --include=*.kt --include=*.java --include=*.xml . \
    | grep -v '^\./\.git' | grep -v '^\./_archive' | grep -v 'intent\.extra\.BINDER'
  ```
  That must come back **empty**. The files involved are `server/…/ServerConstants.java`
  (`MANAGER_APPLICATION_ID`, `PLUS_APPLICATION_ID`), `starter/…/ServiceStarter.kt`,
  `shell/…/ShizukuShellLoader.java`, `compat/…/ForwardActivity.java` + `ForwardReceiver.java`,
  `manager/src/main/res/xml/shortcuts.xml`, and the manifests.
- **⚠ The two strings that must KEEP upstream's name** — both are wire protocol, not identity:
  - `af.shizuku.plus.api.intent.extra.BINDER` (in `ServiceStarter.kt`, `ShizukuManagerProvider.kt`,
    `ShizukuService.java`) must stay byte-equal to `rikka.shizuku.ShizukuProvider.EXTRA_BINDER` in
    the **`api` submodule**, which we do not fork. Rename it and the binder handoff silently stops
    working — the app comes up but the service never connects.
  - `af.shizuku.plus.API` (the meta-data key read by `AuthorizationManager.isPlusApiSupported`) is
    declared by *third-party client apps*, not by us. Renaming it means no client is ever detected
    as Plus-API-capable.
- **Custom permission names stay upstream's** — `af.shizuku.plus.permission.API_V23` / `.MANAGER`.
  They match `ShizukuProvider.PERMISSION` in the `api` submodule, which keeps client compatibility
  exact. 白い熊 decided (2026-07-29) that this build is **not** meant to sit beside upstream's
  Shizuku+, so the duplicate-permission conflict does not apply. Coexistence with **stock Shizuku**
  is unaffected. Take upstream's side on any conflict here.
- **`manager/src/main/java/af/shizuku/manager/Helps.kt`** — every wiki/README/releases URL must point
  at **`ShiroiKuma0/shiroikuma-shizuku`**, never upstream.
- **`manager/…/update/UpdateChecker.kt`** — `RELEASES_URL` and `ATOM_URL` must stay pointed at our
  fork. A rebase that restores upstream's URL would offer 白い熊 *upstream* builds as "updates";
  they are signed with a different key and would fail to install.
- **`manager/…/utils/CrashReporter.kt`, `service/WatchdogService.kt`,
  `receiver/ShizukuReceiverStarter.kt`, `home/ServerStatusViewHolder.kt`,
  `home/ChangelogDialogFragment.kt`, `home/HomeActivity.kt`** — issue/release URLs point at our fork.
- **`manager/src/main/res/values/strings.xml`** (and the `values-*` translations) — the de-branded
  strings. Upstream edits this file constantly, so expect conflicts here every sync. Anything
  user-visible saying "Shizuku+" / "ShizukuPlus" / "thejaustin" becomes ours.
- **Icon assets** — `manager/src/main/res/mipmap-*/ic_launcher*.png` and
  `mipmap-anydpi-v26/ic_launcher*.xml` must stay our black-yellow traced mark. A binary conflict here
  means upstream redrew theirs — keep **ours**.

## Step 5 — refresh the version pins and reset the build tail

In `gradle.properties`:
- `UPSTREAM_VERSION_CODE=<git rev-list --count master>` (after the ff)
- `UPSTREAM_VERSION_NAME=13.6.0.r<that same count>` (or the new `baseVersionName` if upstream bumped it)
- **`BUILD_NUMBER=1`** — the new upstream line starts its `+N` at 1.

## Step 6 — verify the customization layer survived, then build

| What | Expected value | Where |
| --- | --- | --- |
| Installed app id | `shiroikuma.shizuku` | `gradle.properties` → `APP_ID`, used by `manager/build.gradle` |
| Code namespace | `af.shizuku.manager` (**never rename**) | `manager/build.gradle` → `android.namespace` |
| App label | `白い熊 雫` | `app_name` resValue in the `shizukuplus` flavor |
| Launcher icon | black-yellow traced mark | `manager/src/main/res/mipmap-*` |
| Fork version logic | pinned upstream props, `* 10000 +` | `build.gradle` |
| Sentry | `SENTRY_DSN=` (empty) | `gradle.properties` |
| Single ABI | `abiFilters "arm64-v8a"` | `manager/build.gradle` |
| APK naming | `shiroikuma-shizuku_…_arm64-v8a.apk` | `buildApk` task |
| Signing | `signing.properties` → `~/.android-keystores/shiroikuma-shizuku.jks` | `signing.gradle` |
| Update checker | `ShiroiKuma0/shiroikuma-shizuku` | `manager/…/update/UpdateChecker.kt` |
| Help/issue links | our fork | `Helps.kt`, `CrashReporter.kt`, the home/watchdog links |
| Build tail | `BUILD_NUMBER=1` | `gradle.properties` |
| De-branding | no "Shizuku+"/"ShizukuPlus"/`thejaustin` in user-visible strings, About or Help | `values*/strings.xml`, About/Help screens |
| Wire keys intact | `…intent.extra.BINDER` and `af.shizuku.plus.API` still upstream's | see Step 4 |
| Committed agent files | `CLAUDE.md`, `.claude/skills/` tracked | `.gitignore` |

Sanity-check that the script still evaluates, then build the new `+1` via the **build-apk** skill:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk
./gradlew :manager:tasks --console=plain | head      # config sanity; echoes the fork version line
./gradlew buildApk --console=plain < /dev/null       # the new <newVersion>+1
```

`build-apk` then delivers the APK automatically via the global **`/after-build`** skill (adb push if
the phone is reachable, else scp to skhw — no prompt, no transfer question).

## Step 7 — push ONLY after 白い熊 tests and says "Push"

Stop after the build with a signed APK delivered, and **wait**. On their explicit **"Push"**:

```bash
cd ~/git/shiroikuma-shizuku
git checkout master
git push origin master                        # fast-forward, safe

git checkout custom
git push --force-with-lease origin custom     # rebased history
```

## One-line summary of the flow

`fetch upstream` + `fetch djchi` → new version? (else report both and stop) → **tabular feature
summary + the djchi reference delta + WAIT for go-ahead** → ff `master` → rebase `custom` (reconcile
per Step 4) → refresh version pins, `BUILD_NUMBER=1` → verify the layer → **build the new `+1` via
build-apk** → 白い熊 tests → on "Push": push `master`, force-with-lease `custom`.

## Hard rules

- Never `adb install` / `adb uninstall` — 白い熊 installs manually from `/sdcard/tmp/`.
- Never commit or push unprompted; wait for **"Push"**.
- **Never merge or rebase onto `djchi`.** It is a reference remote; `master` fast-forwards from
  `upstream` alone. Anything from djchi arrives as a per-item cherry-pick 白い熊 approved (Step 1b).
- Never advance `DJCHI_REVIEWED_SHA` past commits that were not actually reviewed.
- Never rename the `af.shizuku.manager` namespace — only the installed `applicationId` differs.
- Never rename the two wire-protocol strings (Step 4) — the service silently stops connecting.
- Never restore upstream's Sentry DSN, or the update checker's upstream URL.
- `git push` / `gh` / `scp` need `~/.ssh` and `~/.config/gh`, which the command sandbox blocks — run
  those with `dangerouslyDisableSandbox: true`. Writes anywhere under `~/git` need it too.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of
the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
