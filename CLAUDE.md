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
- **"Push" pushes *everything*** (白い熊, 2026-07-30) — including the `api/` submodule. When `api/`
  has changes, commit and push inside it first (`git -C api push origin custom`), then commit the
  moved submodule pointer in the parent and push that. One word, both repos: never stop after the
  parent, never ask which repo to push, never report the submodule as needing separate approval.
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
| Telemetry | none — see "No phone-home" below | many |
| Update target | `ShiroiKuma0/shiroikuma-shizuku` releases, manual only | `manager/…/update/UpdateChecker.kt` |
| Help / issue links | our fork | `manager/…/Helps.kt` and the home/watchdog/crash links |

**The update checker points at our releases, not upstream's.** Upstream builds are signed with a
different key and could never install over ours, so offering them as "updates" would be both broken
and wrong branding. Our release tags carry **no `v` prefix** (upstream's do), and the tag must equal
the fork `versionName` so the checker never re-offers the installed build.

## No phone-home (a standing fork requirement)

**白い熊's rule, 2026-07-29: this app sends nothing to upstream, and nothing anywhere else.** Every
automatic outbound path upstream ships is removed. Treat any change that reintroduces one as a
regression, not a feature — and re-check this whole table after every rebase.

| Upstream path | What it did | State here |
| --- | --- | --- |
| Sentry SDK | crashes, breadcrumbs, ANRs → the upstream author's Sentry account | DSN hardwired `""` in `manager/build.gradle`; `initializeSentryEarly()` returns before `SentryAndroid.init()`; manifest DSN empty, auto-init false |
| Sentry Gradle plugin | ProGuard mappings + native debug symbols → sentry.io at build time | plugin removed from `settings.gradle` and `manager/build.gradle`; upstream's `sentry { … }` block deleted |
| `RemoteDbSyncWorker` | 24-hourly `WorkManager` fetch of `app-context-db.json` from upstream's repo | not scheduled (`ShizukuApplication`); the worker itself now *cancels* the work and its `doWork` is a no-op |
| "Update app database" | pulled `apps.json` from upstream's repo | removed; the row reports it is disabled |
| `VirusTotalClient` | SHA-256 of every installed APK + the API key → VirusTotal | returns "disabled", no connection |
| `PithusClient` | SHA-256 of every installed APK → `beta.pithus.org` | returns "disabled", no connection |
| `HomeActivity.checkForUpdates()` | polled the releases API on every app start | `isAutoUpdateEnabled()` now defaults **false** (upstream: true) |
| "Email support" button | device / OS / version report → the upstream author's address | button removed; `support_email` blanked |
| `.github/workflows/` | `app.yml` injected a Sentry DSN and uploaded debug symbols, triggered on pushes to `master` | whole `.github/` directory removed (also FUNDING.yml and the issue templates) |

The **only** outbound request the app can make is the update check, and only when 白い熊 taps
"Check for updates" — it reads our own releases and sends nothing about the device.

Note the Sentry SDK is still a *dependency*: ~14 upstream files call `Sentry.captureException` /
`addBreadcrumb` / `startTransaction`, and with the SDK unarmed those are inert no-ops with no
transport. Keeping them avoids permanent rebase conflicts in files we otherwise never touch. If you
ever remove the dependency, every one of those call sites has to go with it.

## Two strings that deliberately keep upstream's name

Both are **wire protocol, not identity**. Renaming them to match our app id breaks the app in ways
that are hard to diagnose, because it still builds and still launches.

| String | Why it must not change |
| --- | --- |
| `af.shizuku.plus.api.intent.extra.BINDER` | The key the privileged server uses to hand the binder to the manager. The receiving side is `rikka.shizuku.ShizukuProvider.EXTRA_BINDER` in the **`api` submodule**, which we do not fork. Rename it and the service never connects. Appears in `ServiceStarter.kt`, `ShizukuManagerProvider.kt`, `ShizukuService.java`. |
| `af.shizuku.plus.API` | A meta-data key **third-party client apps** set to advertise Plus-API support, read by `AuthorizationManager.isPlusApiSupported`. Rename it and no client is ever detected as Plus-capable. |

The **custom permissions also keep upstream's names** — `af.shizuku.plus.permission.API_V23` and
`af.shizuku.plus.permission.MANAGER` (declared in `manager/src/main/AndroidManifest.xml`, mirrored in
`manager/…/Manifest.java`, `server/…/ServerConstants.java` and `server/…/BinderSender.java`). They
match `ShizukuProvider.PERMISSION` in the `api` submodule, so leaving them alone keeps client
compatibility exact.

> ⚠ **The consequence, decided deliberately (白い熊, 2026-07-29): this build is NOT installable
> alongside upstream's Shizuku+.** Two apps cannot declare the same custom permission, so installing
> both fails with `INSTALL_FAILED_DUPLICATE_PERMISSION` (upstream's own issue #316). Coexistence with
> **stock Shizuku** (`moe.shizuku.privileged.api`) is unaffected and still works. If we ever *do*
> want to sit beside upstream's Shizuku+, the fix is to prefix these two permissions with our app id
> — and then also patch the `api` submodule, which hardcodes the old name.

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

## ⛔ Never assume `applicationId` == `namespace`

`applicationId` is **`shiroikuma.shizuku`**; the code `namespace` is **`af.shizuku.manager`**. They
differ on purpose — renaming the namespace would make every rebase a mass-conflict — and that makes
**any component reference built by assuming they are equal wrong**. The `pkg/.Receiver` shorthand
expands against the *applicationId*, so `shiroikuma.shizuku/.admin.DhizukuAdminReceiver` names a
class that does not exist.

The sharp edge is the Device Owner setup command. A wrong component there means `dpm
set-device-owner` fails — or appears to succeed — and once an app *is* Device Owner it cannot be
uninstalled normally and `dpm remove-active-admin` refuses. Being wrong there costs a factory reset.

**Derive, never spell:**

```kotlin
// Right — package from the context, FQCN from the class; a rename moves it automatically.
ComponentName(ctx, DhizukuAdminReceiver::class.java).flattenToString()

// Wrong — expands against the applicationId; names a class that does not exist.
"${ctx.packageName}/.admin.DhizukuAdminReceiver"

// Also wrong — correct today, silently stale after any rename or move.
"${ctx.packageName}/af.shizuku.manager.admin.DhizukuAdminReceiver"
```

**Where deriving is impossible, a test guards it.** The `:compat` module addresses us with string
literals (`ForwardActivity`, `ForwardReceiver`) because it does not — and must not — depend on
`:manager`. `manager/src/test/…/ComponentNameContractTest.kt` reads compat's real source, extracts
every `setClassName(...)` literal, and asserts the package equals `BuildConfig.APPLICATION_ID` and
that a source file exists for the named class. Rename `RequestPermissionActivity` or
`BinderRequestReceiver` and the **build** fails, instead of the Compat Hub silently failing on a
device. The same test asserts the two identifiers stay distinct, so the shorthand forms can never
start working by accident.

Run it with `./gradlew :manager:testShizukuplusDebugUnitTest`.

> Note: `:manager`'s unit tests did **not** compile upstream — `RemoteDbSyncWorkerTest` imported
> `af.shizuku.manager.utils.AppContextManager`, but the class lives in `af.shizuku.manager.database`.
> That test now guards the no-phone-home contract instead, so the suite is green and worth running.

## The 白い熊 雫 UI page (`shiroikuma/`)

The whole house layer lives in one package,
`manager/src/main/java/af/shizuku/manager/shiroikuma/`, so it survives upstream rebases as a
self-contained unit.

| File | Role |
| --- | --- |
| `ShiroikumaUiPrefs.kt` | Every settable attribute + the persisted store; the `Category` enum that the page, the export ZIP and the automation contract all share |
| `ShiroikumaFonts.kt` | External `.ttf`/`.otf` import, listing, deletion, cached typefaces |
| `ColorPicker.kt` | The RGBA picker — four sliders, live preview, one-click prefilled swatches |
| `FontPicker.kt` | Font list, each row **rendered in its own glyphs**; long-press deletes an import |
| `ShiroikumaPreferences.kt` | The preference widgets: category / sub-category / item / seekbar / colour swatch / live preview / automation-token rows |
| `ShiroikumaDialogs.kt` | The house dialog: black fill, **yellow border**, yellow buttons |
| `ShiroikumaUiFragment.kt` | The page itself |
| `ShiroikumaBackup.kt` | The category ZIP: headless export/import core, atomic `.part` write, backup listing |
| `ExportImportPanel.kt` | The Kōjiki-format export/import panel |
| `automation/AutomationAuth.kt`, `automation/StateExportReceiver.kt`, `automation/StateExportService.kt` | The 保存復元 automation contract |

**Page conventions (kxkb style — keep them).** Headings are big, bold, accent-coloured and
underlined **only as wide as their own text**, each top-level section preceded by a thin full-width
hairline. Items indent one step per level — **36 → 54 → 72 → 90 dp** — sub-headings included, and row
padding stays **tight**; the only generous space is above a section heading. Every group carries a
live preview. Colour pickers are **RGBA** (four sliders) with one-click prefilled swatches above.
Every size is a slider, and border/thickness/roundness sliders reach **0**.

The layouts carrying that grammar are `res/layout/preference_*_shizuku*.xml` — do not restyle the
page by editing individual rows.

**Reached from** Settings → 白い熊 雫 UI, and by **long-pressing the settings cog** on the home
screen (`HomeScreen.onSettingsLongClick` — a Compose `IconButton` has no long-press, so the cog is a
`combinedClickable` box; `SettingsActivity` reads `EXTRA_OPEN_SHIROIKUMA_UI` and pushes the page on
top of the settings root so Back still lands on Settings).

**Defaults are the house look** — pure black `#000000` with pure yellow `#FFFF00` (not Material
amber). A fresh install is black-and-yellow with no user action.

### How the knobs reach the rest of the app

The app is **hybrid** — Compose (home, settings chrome) over View-based `androidx.preference`
screens and RecyclerView home cards — so the look is driven from three places that must stay in
step. A knob that only moves its own preview is a bug.

| Layer | Mechanism |
| --- | --- |
| **Static baseline** | `ThemeOverlay.Shiroikuma` in `values/themes.xml`, applied **last** by `ThemeDelegateImpl.onApplyUserThemeResource` so it wins over dynamic colour, the custom accents and the black-night overlay. Compose reads the resolved Activity attributes, so this one line is what makes both halves come up black-and-yellow on a fresh install. |
| **Live, Compose** | `ShiroikumaTheme` builds a `ColorScheme` + `Typography` from the store and installs it through `core/ui`'s `AppThemeOverride` (a hook, so `core:ui` never has to depend on `manager`). Installed in `ShizukuApplication.onCreate`, before any Activity composes. |
| **Live, Views** | `ShiroikumaViewTheme.applyToTree` recolours and re-types the preference rows and home cards, hooked from `BaseSettingsFragment.onViewCreated` and the home `recyclerViewProvider`. Rows recycle, so it re-runs on layout; it is idempotent. |

Three things to keep right:

- **`AppThemeOverride.revision` is in the `remember` key.** Without it Compose reuses the scheme it
  cached before the edit and the change appears only after a navigation.
- **Never put live knob values in `ThemeDelegateImpl.getThemeKey`.** A changed key triggers
  `recreate()`, so dragging a slider would recreate the Activity on every frame. The static overlay
  is constant; the live values are applied without a recreate, by design.
- **Every `*Container` role is a flat near-black surface, never a low-alpha accent** — in the XML
  overlay *and* in `ShiroikumaTheme`. Material composites containers as primary-over-surface, and
  yellow over black composites to **olive**. That is why the scheme is built role by role instead of
  handing colours to `darkColorScheme()` and letting it derive the rest, and why `surfaceTint` is
  `Color.Transparent` (so tonal elevation never drags a surface back toward the accent).

The UI page itself is marked `ShiroikumaViewTheme.markSkipped` — it is styled by its own house
layouts, and letting the generic applier walk it would flatten the sub-heading and dim-summary
colours back to body text.

### ⛔ Every container filled with a `surface*` role MUST carry a visible border

**This is a standing rule, and breaking it makes UI disappear rather than look wrong.**

In this theme `surface`, `surfaceVariant` and all five `surfaceContainer*` roles are the **same pure
black as the page**. Upstream's dark theme told containers apart by *tonal lift* — `surfaceContainerHigh`
was a lighter grey than the background, and that alone was what made a card visible. Flattening every
role to black removes that, so **a container with no border is invisible**, not merely flat.

Two tiers, and both are user-settable in the UI page's Colours section:

| Tier | Colour | Scheme role | Use for |
| --- | --- | --- | --- |
| **Major** | yellow `#FFFF00` (`KEY_COLOR_BORDER`) | `outline` | groups, sections, the home cards, panels, dialogs — anything that is a heading in its own right |
| **Minor** | grey `#4A4A4A` (`KEY_COLOR_BORDER_MINOR`) | `outlineVariant` | ordinary items: list rows, metric tiles, search results — separated from the ground without shouting |

How to comply, per layer:

- **Compose** — pass `border = majorBorder()` or `minorBorder()` from `ShiroikumaCompose.kt`. They
  read the card-border width slider and return `null` at 0, so "off" stays reachable.
- **Views** — `ShiroikumaViewTheme` already sets `strokeColor`/`strokeWidth` on every
  `MaterialCardView` it walks (major tier — the home cards are the case that matters). A container
  that is *not* a `MaterialCardView` needs its own `GradientDrawable` stroke.
- **Never** rely on a `surface*` fill, an alpha over it, or tonal elevation to make something
  visible. `surfaceTint` is transparent here, so elevation contributes nothing at all.

The same trap bites indicators: a `LinearProgressIndicator` whose `trackColor` is `surfaceVariant`
has an invisible unfilled portion — use `outlineVariant`.

Known compliant call sites: `SettingsScreen.kt` (search results), `SystemHubScreens.kt` (memory card,
`MetricCard`, progress track), `home_item_container.xml` via the View applier, and everything drawn
by hand in `ExportImportPanel` / `ShiroikumaPreferences` / `ShiroikumaDialogs`. **If you add a Compose
`Card`, `Surface` or sheet, add a border in the same commit.**

**Export/Import.** One ZIP per export, `shiroikuma-shizuku_<yyyy-MM-dd_HH-mm-ss>.zip` (the mandatory
family convention: no version, no suffix — every sister app's backups share one directory), holding
`manifest.json` + one JSON per category + `fonts/`. Import **merges** and skips absent categories.
Written **atomically**: `<name>.part` renamed only after the archive is closed, and deleted on any
failure or cancel — a truncated archive would otherwise silently become "the latest backup".

The dialog chain is specified: a **successful export** closes the info dialog, the panel *and* the
UI page; a **successful import** does the same on either button ("Restart now" also restarts).
**Failures leave the panel open.** The no-backup-folder message is **red** until a folder is set —
in the panel and on the UI page row alike.

**Automation (保存復元).** Token-gated, master switch **default OFF**, both rows **inside** the
Export/Import section. `EXPORT_STATE` / `LIST_CATEGORIES` / `CANCEL_EXPORT` on one exported receiver
with **no `android:permission`** — the token is the gate. The receiver only validates and hands off
to `StateExportService`: `goAsync()` does not extend the broadcast window, and overrunning it ANRs
this app mid-write. Replies are **plain broadcasts** with `FLAG_INCLUDE_STOPPED_PACKAGES` — never a
Binder, never the ordered-broadcast result (EMUI severs both). Progress carries **real counts, never
a percentage**, with `item` naming the category id being written so the caller highlights the right
row. The token prefs file is deliberately absent from the export ZIP.

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
