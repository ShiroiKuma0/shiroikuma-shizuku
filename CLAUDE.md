# shiroikuma-shizuku

**白い熊 雫** — a fork of [ShizukuPlus](https://github.com/thejaustin/ShizukuPlus) (Apache-2.0),
itself a fork of [Shizuku](https://github.com/RikkaApps/Shizuku): the app that runs a privileged
process via **adb** or **root** and lends system APIs to ordinary apps. Package
`shiroikuma.shizuku`, label **"白い熊 雫"**, installable side-by-side with stock Shizuku and with
upstream Shizuku+.

## Branch & remote model (same as the sister forks)

- `origin` = `git@github.com:ShiroiKuma0/shiroikuma-shizuku.git` (ssh) — our fork.
- `upstream` = `https://github.com/thejaustin/ShizukuPlus.git` (https, fetch only).
- **`master`** mirrors `upstream/master` (currently the `13.6.0.r2178` line). Fast-forward only —
  no fork work ever lives here.
- **`custom`** carries all our work, rebased onto `master` on each upstream sync. **All development
  happens on `custom`**, and it is the GitHub default branch.
- **`api/` is a git submodule** (`thejaustin/ShizukuPlus-API`) — a fresh clone needs
  `git submodule update --init --recursive` or Gradle fails at configuration time.
- **Do not rename the `af.shizuku.manager` code namespace** — only the installed `applicationId`
  differs (`shiroikuma.shizuku`). Renaming would make every rebase a mass-conflict.

## Skills (`.claude/skills/`)

- **`build-apk`** — build the signed release APK via the `buildApk` Gradle task, then deliver it
  automatically via the global `/after-build` skill (adb push to `/sdcard/tmp/` if the phone is
  reachable, else scp to skhw) — **no transfer prompt**, never pause to ask how to transfer.
- **`upstream-new-version`** — check upstream for new commits; **⛔ before any rebase, present a
  proceed-gated descriptive table of the new upstream version's features and wait for 白い熊's
  explicit go-ahead**; then fast-forward `master`, rebase `custom`, refresh the version pins, reset
  `BUILD_NUMBER`, build the new `+1`.
- **`publish-version`** — publish the latest tested APK as a GitHub release: tag `<version>` (no `v`
  prefix), attach the APK, refresh README + `CHANGELOG-shiroikuma.md`, keep the default branch on
  `custom`. Pin `gh` with `-R ShiroiKuma0/shiroikuma-shizuku` (the `upstream` remote otherwise wins).

## Build, versioning, signing

- **Build env (this machine):** default `java` is JDK 11 (can't run Gradle 9.x). Always:
  `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk`.
  Keep the gitignored `local.properties` (`sdk.dir=/home/shiroikuma/android-sdk`) at the repo root.
- **Build:** `./gradlew buildApk` (release, signed; copies the APK to `~/tmp` and bumps
  `BUILD_NUMBER`). Fast dev iteration: `./gradlew :manager:assembleShizukuplusDebug`.
- **Toolchain:** Gradle 9.1, AGP 9.0, Kotlin 2.3.6 (KSP), JDK 21, compileSdk/targetSdk 35,
  minSdk 24. Native code via CMake 3.22.1 in `manager/src/main/jni`. Views + Compose + Glance
  widgets, Koin DI, Mavericks, Room. Cold builds are slow — run them with `run_in_background`.
- **Versioning:** upstream derives `versionCode` from `git rev-list --count HEAD` and its
  `versionName` from the same count (`Shizuku+ 13.6.0.r2178`). **We cannot reuse that** — on `custom`
  the count includes our own commits. So `gradle.properties` **pins** upstream's numbers:
  `UPSTREAM_VERSION_CODE` (2178) and `UPSTREAM_VERSION_NAME` (`13.6.0.r2178`), refreshed by
  `upstream-new-version` on each sync. `BUILD_NUMBER` is our increment — bumped every build, reset
  to 1 on each new upstream version. Fork `versionName = "<UPSTREAM_VERSION_NAME>+<BUILD_NUMBER>"`
  (`13.6.0.r2178+1`), `versionCode = UPSTREAM_VERSION_CODE * 10000 + BUILD_NUMBER` (`21780001`).
- **APK filename:** `shiroikuma-shizuku_<versionName>_arm64-v8a.apk`, single-ABI arm64-v8a
  (upstream packages a universal APK with all four ABIs).
- **Signing:** release signed from the **gitignored** `signing.properties`
  (`signing.properties_sample` documents the keys) → `~/.android-keystores/shiroikuma-shizuku.jks`
  (alias `shizuku`). Password recorded in `~/〇/[666] 私資料/[666][27] 暗号/android-keystores.org`
  (jks backup in `android-keystores/` next to it). Losing both loses the signing identity.
  ⚠ Upstream's `signing.gradle` **falls back to the Android debug keystore** when
  `signing.properties` is absent instead of failing — the APK then builds but carries the wrong
  identity and refuses to update an existing install. Check the file exists before delivering.
- **Delivery:** APK to `~/tmp`, then `/after-build` (adb push to `/sdcard/tmp/` or scp to skhw);
  **白い熊 installs from the on-device file manager** (never `adb install`).

## Working rules (override harness defaults where noted)

- **No `Co-Authored-By: Claude` / "Generated with Claude" trailer** in commits or PR bodies — end
  the message at the last line of the body. (Overrides the harness default; global rule in
  `~/.claude/CLAUDE.md`.)
- **Never commit or push until 白い熊 says "Push".** Treat the working tree as scratch between
  "Push" commands; multiple uncommitted fixes can stack. "Push" = `git commit` + `git push origin
  custom` (and `master` after an upstream sync). 白い熊 tests each build on-device first.
- **After every successful build, deliver the APK automatically via `/after-build`** — never ask how
  to transfer it, never pause.
- **Commit subjects:** plain descriptive summary, no prefix.
- Fork changelog notes go to `CHANGELOG-shiroikuma.md` only (upstream owns `CHANGES.md`).
- `git push` / `gh` / `scp` need `~/.ssh` and `~/.config/gh`, which the command sandbox blocks — run
  those with `dangerouslyDisableSandbox: true`. **Any write under `~/git` needs it too** (the
  sandbox only grants write access to the scratch dir and `~/tmp`).

## Fork identity (the standing customization layer)

| What | Value | Where |
| --- | --- | --- |
| App id | `shiroikuma.shizuku` | `gradle.properties` → `APP_ID`, read by `manager/build.gradle` |
| Namespace | `af.shizuku.manager` (**never rename**) | `manager/build.gradle` → `android.namespace` |
| Label | `白い熊 雫` | `app_name` resValue, `shizukuplus` flavor |
| Icon | black-yellow traced mark (yellow `#FFFF00` line-art on black) | `manager/src/main/res/mipmap-*` |
| Version logic | pinned upstream props + the `* 10000` fork block | `build.gradle` |
| Signing | `signing.properties` → `~/.android-keystores/shiroikuma-shizuku.jks` | `signing.gradle` |
| Single ABI | `ndk { abiFilters "arm64-v8a" }` | `manager/build.gradle` |
| Telemetry | `SENTRY_DSN=` (empty — Sentry becomes a no-op) | `gradle.properties` |
| Update target | `ShiroiKuma0/shiroikuma-shizuku` releases | `manager/…/update/UpdateChecker.kt` |
| Help / issue links | our fork | `manager/…/Helps.kt` and the home/watchdog/crash links |

**Telemetry is off deliberately.** Upstream ships a live Sentry DSN in `gradle.properties`, which
would send every crash and breadcrumb from 白い熊's device to the upstream author's Sentry account.
An empty DSN makes the SDK a no-op. Never restore it while resolving a rebase conflict.

**The update checker points at our releases, not upstream's.** Upstream builds are signed with a
different key and could never install over ours, so offering them as "updates" would be both broken
and wrong branding. Our release tags carry **no `v` prefix** (upstream's do), and the tag must equal
the fork `versionName` so the checker never re-offers the installed build.

## Two strings that deliberately keep upstream's name

Both are **wire protocol, not identity**. Renaming them to match our app id breaks the app in ways
that are hard to diagnose, because it still builds and still launches.

| String | Why it must not change |
| --- | --- |
| `af.shizuku.plus.api.intent.extra.BINDER` | The key the privileged server uses to hand the binder to the manager. The receiving side is `rikka.shizuku.ShizukuProvider.EXTRA_BINDER` in the **`api` submodule**, which we do not fork. Rename it and the service never connects. Appears in `ServiceStarter.kt`, `ShizukuManagerProvider.kt`, `ShizukuService.java`. |
| `af.shizuku.plus.API` | A meta-data key **third-party client apps** set to advertise Plus-API support, read by `AuthorizationManager.isPlusApiSupported`. Rename it and no client is ever detected as Plus-capable. |

By contrast the **custom permissions must** carry our app id
(`shiroikuma.shizuku.permission.API_V23` / `.MANAGER`): two installed apps cannot declare the same
custom permission, so keeping upstream's would make our APK fail to install with
`INSTALL_FAILED_DUPLICATE_PERMISSION` whenever upstream's Shizuku+ is present.

## How this app reaches its clients (read before touching the app id)

Shizuku is a **service provider** — its whole point is that *other* apps talk to it. Three paths
matter, and the fork keeps all three working:

| Client type | How it finds us |
| --- | --- |
| Apps built against the **stock** Shizuku API (SD Maid SE, Swift Backup, …) | The **Compat Hub**: the separate `:compat` module, package `moe.shizuku.privileged.api`, bundled as `manager/src/main/assets/compat.apk` and installed on demand. It owns the stock provider authority and forwards to our manager. Upstream's design — left alone. |
| Apps built against the **Shizuku+** API | The `af.shizuku.plus.API` meta-data key + the binder extra above, both unchanged. |
| The manager itself | `ShizukuManagerProvider`, authority `${applicationId}.shizuku` → `shiroikuma.shizuku.shizuku`. |

The **`dropin` flavor** (applicationId `moe.shizuku.privileged.api`) is upstream's stock-Shizuku
*replacement*. We never build it — it is incompatible with our own app id by definition. `buildApk`
targets **`shizukuplus`** only.

## Repo layout (upstream ShizukuPlus)

- `manager/` — the app: home, authorization UI, settings, logs, widgets, the Plus bridges.
  Namespace `af.shizuku.manager`.
- `server/` — the privileged process (`rikka.shizuku.server`), the part that runs as shell/root.
- `starter/` — the `app_process` bootstrap that launches the server.
- `shell/`, `app-process/` — the `rish` shell entry points.
- `compat/` — the Compat Hub stub (package `moe.shizuku.privileged.api`).
- `common/`, `database/`, `core/ui/` — shared code, Room storage, UI components.
- `api/` — **submodule**, the client-facing API library (`rikka.shizuku.*`).
- `_archive/`, `.agent/`, `.agents/`, `AGENTS.md`, `JULES.md`, `skills/`, `.skills/` — upstream's own
  scratch and AI tooling; not ours, not used here. Our working rules live in this file and
  `.claude/skills/`.

## Current status

**Fork bootstrapped** (2026-07-29): forked from `thejaustin/ShizukuPlus` at `13.6.0.r2178`,
`master`/`custom` branch model, own keystore, fork versioning + signing, single-ABI arm64-v8a,
`buildApk` task, the black-yellow traced icon, de-branding, and the three repo skills.
