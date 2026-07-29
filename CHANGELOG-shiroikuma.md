# 白い熊 雫 — fork changelog

Fork-only notes. Upstream's own release notes live in `CHANGES.md` — never fold fork notes into it.

Versions are `<upstream version>+<our build number>`; the `+N` resets to 1 on each upstream sync.

## 13.6.0.r2178+14 — first release

The first published build of the fork, from `thejaustin/ShizukuPlus` at `13.6.0.r2178`. Everything
below is built on top of upstream; upstream's own feature set is intact and unlisted here.

### Identity

- App id `shiroikuma.shizuku`, label **白い熊 雫**, installable side-by-side with **stock Shizuku**.
- Every hardcoded copy of upstream's application id follows the rename — the privileged server's
  `MANAGER_APPLICATION_ID`/`PLUS_APPLICATION_ID`, the starter's provider lookup, the `rish` shell
  loader, the Compat Hub's two forwarders, the launcher shortcuts and the manifests.
- The `af.shizuku.manager` code namespace is deliberately unchanged, so rebases stay small.
- Two strings keep upstream's name on purpose, because they are wire protocol rather than identity:
  `af.shizuku.plus.api.intent.extra.BINDER` (the binder handoff key, whose other side is
  `ShizukuProvider` in the unforked `api` submodule) and the `af.shizuku.plus.API` meta-data key that
  third-party clients set. Renaming either yields an app that builds and launches but never connects.
- The custom permissions (`af.shizuku.plus.permission.*`) also keep upstream's names, matching
  `ShizukuProvider` in the `api` submodule. The consequence is deliberate: this build is **not**
  installable alongside upstream's Shizuku+, since two apps cannot declare the same custom
  permission. Coexistence with stock Shizuku is unaffected.

### No phone-home

Every automatic outbound path upstream shipped is gone:

- **Sentry** removed at build time (the Gradle plugin is not applied, so no ProGuard-mapping or
  native-symbol upload to sentry.io) and disarmed at runtime (DSN hardwired empty,
  `SentryAndroid.init()` unreachable). The SDK is never armed, so the remaining
  `captureException`/`addBreadcrumb` call sites are inert no-ops; they are left in place only to keep
  upstream files conflict-free on rebase.
- **`RemoteDbSyncWorker`** — upstream's 24-hourly `WorkManager` fetch of `app-context-db.json` from
  the upstream repo — is no longer scheduled, and the worker itself now cancels the work instead of
  running it, so a rebase that restores the call still produces no traffic.
- **"Update app database"** no longer pulls `apps.json` from the upstream repo.
- **VirusTotal** and **Pithus** APK lookups are disabled — upstream sent the SHA-256 of every APK
  being installed (and, for VirusTotal, the API key in the query string) to those services.
- **Automatic update polling** is off by default (upstream defaulted it on, hitting the releases API
  on every app start), and the update checker reads *this* repository's releases, never upstream's.
- The **"email support"** button, which sent a device/OS/version report to the upstream author's
  support address, is removed.
- Upstream's **CI workflows** are removed — `app.yml` injected a Sentry DSN and uploaded debug
  symbols, and it triggered on pushes to `master`, which this fork pushes on every sync.

The only outbound request left is the manual "Check for updates", against this repository.

### De-branding

- The product name, GitHub links, wiki/issue/release links, About page, Help pages, bug-report
  dialog, watchdog notifications and crash reporter all carry our name and repository.
- Upstream's "thejaustin's Apps" block — ten of the upstream author's own apps seeded into the
  app-context database as recommendations — is removed, along with the matching entries in the SU
  Bridge's suggested-app links.
- Fixed two upstream links that 404'd (`/releases/wiki`, `/releases/issues`) while repointing them,
  and repointed `blob/main` / `tree/master` paths at branches this fork actually has.

### The 白い熊 雫 UI page

- A new theming page under **Settings → 白い熊 雫 UI**, also reached by **long-pressing the home
  settings cog**. A Compose `IconButton` has no long-press, so the cog became a `combinedClickable`
  box; `SettingsActivity` pushes the page on top of the settings root so Back still lands on Settings.
- Built from kxkb's grammar, carried as layouts rather than per-row styling: headings big, bold and
  underlined only as wide as their own text, each section preceded by a thin full-width hairline,
  indents stepping 36 → 54 → 72 → 90 dp, tight row padding, space only above a heading.
- Sections — **Export/import, Colours, Typography, Shape & borders, Spacing, Lists & cards, Reset** —
  each with a live preview and its own reset.
- Colours open a four-slider **RGBA** picker with a live preview and one-click prefilled swatches.
- Every size, weight, roundness and thickness is a slider, and the border, divider, underline and
  pill sliders all reach **0**.
- Fonts import from `.ttf`/`.otf`; the picker renders each font in its own glyphs, and a long-press
  deletes an import.

### The knobs actually drive the app

The app is hybrid — Compose over View-based `androidx.preference` screens and RecyclerView home cards
— so the look is driven from three places kept in step:

- `ThemeOverlay.Shiroikuma`, applied **last** by `ThemeDelegateImpl`, beats dynamic colour, the custom
  accents and the black-night overlay. Compose reads the resolved Activity theme, so one line makes
  both halves come up black-and-yellow on a fresh install.
- `ShiroikumaTheme` builds a `ColorScheme` + `Typography` from the store and installs it through a new
  `AppThemeOverride` hook in `core/ui` — a hook, not an import, because `manager` depends on
  `core:ui` and not the reverse. Its revision is in the `remember` key, or Compose reuses the scheme
  it cached before the edit.
- `ShiroikumaViewTheme` recolours the preference rows and home cards, which no Compose theme reaches.

Live knob values are deliberately **not** in `getThemeKey`: a changed key triggers `recreate()`, so
dragging a slider would recreate the Activity on every frame.

### Every container gets a border, every dialog too

- All surface roles are the same pure black, so a container with no border is **invisible** rather
  than flat. Two tiers, both user-settable: yellow for groups and major items, grey for ordinary
  ones. Applied to the settings search cards, the System Hub cards and the progress track, which had
  all gone black-on-black.
- Material's elevation overlay blends `colorSurface` with `colorPrimary`; black plus yellow
  composites to **olive**, which is what every raised View surface had become. Disabled in both the
  app overlay and the dialog theme — the View-world twin of `surfaceTint = Transparent`.
- `MaterialAlertDialogBuilder` overwrites the themed window background during `show()`, and the
  `MaterialAlertDialog` styleable has no stroke attribute, so a border can only be applied after the
  dialog exists. `DialogFragment`s are handled by a global lifecycle hook, `BaseSettingsFragment`
  styles its own, and the 45 dialogs raised directly from activities and view holders call
  `showHouse()`.
- **Toasts** are drawn in the house style — black fill, yellow text, yellow rounded frame, house font,
  following the accent/background/border knobs — instead of the system's grey pill. A detached
  Fragment context is a no-op rather than a crash, and an OEM that refuses custom toast views
  degrades to the plain system toast instead of showing nothing.
- **Splash**: `Theme.App.Starting` came up grey behind the black-and-yellow icon, because the splash
  theme is applied by the system before the Activity exists, so `?android:attr/colorBackground`
  resolved against `Theme.SplashScreen`'s own light parent. The colour is spelled out now.
  `postSplashScreenTheme` was the *light* theme, which flashed white between the splash and the first
  frame. Both `values/` and `values-night/` carry the fix — both define `Theme.App.Starting`, and
  which one applies depends on the device's dark-mode setting.

### 保存復元 — export/import and automation

- One category ZIP per export, `shiroikuma-shizuku_<stamp>.zip`, written **atomically** as `.part` and
  renamed only when complete — a truncated archive would otherwise silently become the latest backup,
  since every sister app shares one directory. Import **merges** and skips absent categories.
- The panel follows the Kōjiki format with the ArcaneChat pill bar. A successful export or import
  closes the dialog, the panel and the UI page; failures leave the panel open. The no-folder message
  is red until a folder is set, in the panel and on the page row alike.
- The automation hand-off: `EXPORT_STATE` / `LIST_CATEGORIES` / `CANCEL_EXPORT` on one exported
  receiver with no `android:permission` — the token is the gate, and the master switch defaults
  **off**. The receiver only validates and hands off to a foreground service, because `goAsync()` does
  not extend the broadcast window and overrunning it would ANR the app mid-write. Replies are plain
  broadcasts with `FLAG_INCLUDE_STOPPED_PACKAGES`; progress carries real counts with the category id,
  never a percentage. The token prefs file is deliberately absent from the export.
- `MANAGE_EXTERNAL_STORAGE` is declared because the shared backup folder is a real path; the export
  checks the grant and reports `ERROR:no-storage-access` rather than failing halfway.

### Fixes and behaviour

- **The manual `adb shell .../libshizuku.so` start works.** `starter.cpp` resolved the manager APK
  with `pm path` over two hardcoded upstream application ids, so on this fork both lookups came back
  empty and the documented "start from a computer" command died with "fatal: can't get path of
  manager" while the app was plainly installed. Starting from inside the app always worked, because
  `Starter.internalCommand` appends `--apk=` and never reaches that path — which is why only the
  manual command was affected. The application id now reaches the native build as a compile
  definition (`APP_ID` → `-DSHIROIKUMA_PACKAGE_NAME` → `CMakeLists.txt` → `starter.cpp`), derived
  rather than spelled, so it cannot drift. Upstream's own id is deliberately not in the lookup list:
  querying it would load a *different* app's APK whenever upstream's build is installed alongside ours.
- **The home card notices a service it did not start.** `HomeActivity` pings the binder every 1.5 s
  while resumed. The sticky binder-received listener is delayed or missed on some devices — upstream
  says so itself in `Starter.waitForBinder`, and polls there for exactly that reason — so a service
  started externally left the card reading "not running" until the screen was left and re-entered. It
  also catches the mirror case, a service dying externally.
- **Device Owner can be left cleanly.** Once an app is Device Owner it cannot be uninstalled normally
  and `dpm remove-active-admin` refuses, so a failure here leaves a factory reset as the only exit.
  `clearDeviceOwner` no longer swallows the reason: it pre-checks that we are the owner, reports the
  real exception in a copyable dialog, and **verifies** the clear took effect, since the API is
  documented as best-effort. Both failure paths say not to uninstall in that state.
- **The `applicationId` / `namespace` trap.** `applicationId` (`shiroikuma.shizuku`) differs from the
  `namespace` (`af.shizuku.manager`), so any reference built assuming they are equal is wrong — and
  the `dpm set-device-owner` command is where being wrong costs a wipe. It is now derived with
  `ComponentName(ctx, Class)`, so a rename carries it.

### Tests

- `ComponentNameContractTest` reads `:compat`'s real source, extracts every `setClassName` literal and
  asserts the package and class still resolve — `:compat` addresses us by string and must not depend
  on `:manager`, so a rename fails the **build** instead of failing silently on a device. It also
  asserts the two identifiers stay distinct, so the shorthand forms can never start working by
  accident.
- Two further tests cover the native sources, so the hardcoded application id cannot come back: the
  fork rename swept `.kt`/`.java`/`.xml` and missed `.cpp`, and this guards that whole class of miss.
- Upstream's unit tests never compiled (`RemoteDbSyncWorkerTest` imported `AppContextManager` from the
  wrong package). That test now guards the no-phone-home contract instead — the worker is a no-op, and
  its source may not regain a fetch — so the suite is green and worth running.

### Look

- Black-yellow traced launcher icon: the Shizuku mascot and its hexagon redrawn as uniform-width
  `#FFFF00` line-art on black, with the hexagon cut where the head passes in front so it reads as
  behind. Traced from upstream's alpha channel, simplified, splined, and rendered from a distance
  field for exact stroke width. Matching monochrome layer for themed icons, at every density.
- Defaults are the house look: pure black `#000000` with pure yellow `#FFFF00` (not Material amber).
  A fresh install is black-and-yellow with no user action.

### Build and packaging

- `buildApk` assembles the signed `shizukuplus` release, copies it to `~/tmp` as
  `shiroikuma-shizuku_<version>_arm64-v8a.apk`, and bumps `BUILD_NUMBER`.
- Single-ABI **arm64-v8a** instead of upstream's universal APK.
- Release signing from the gitignored `signing.properties`; `signing.properties_sample` documents the
  keys and warns that upstream's `signing.gradle` silently falls back to the Android debug keystore
  when the file is absent — which builds an APK carrying the wrong identity that then refuses to
  update an existing install.
- Fork versioning: upstream's git-commit-count formula replaced with pinned `UPSTREAM_VERSION_CODE` /
  `UPSTREAM_VERSION_NAME` plus our `BUILD_NUMBER`, so our own commits never inflate the version.
- Release tags carry **no `v` prefix** and equal the fork `versionName` exactly, so the in-app update
  check never re-offers the installed build.
