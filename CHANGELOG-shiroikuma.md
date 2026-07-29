# 白い熊 雫 — fork changelog

Fork-only notes. Upstream's own release notes live in `CHANGES.md` — never fold fork notes into it.

Versions are `<upstream version>+<our build number>`; the `+N` resets to 1 on each upstream sync.

## 13.6.0.r2178+1 — current

The first build of the fork, from `thejaustin/ShizukuPlus` at `13.6.0.r2178`.

**Identity**
- App id `shiroikuma.shizuku`, label **白い熊 雫**, installable side-by-side with stock Shizuku.
  Every hardcoded copy of upstream's application id follows the rename — the privileged server's
  `MANAGER_APPLICATION_ID`/`PLUS_APPLICATION_ID`, the starter's provider lookup, the `rish` shell
  loader, the Compat Hub's two forwarders, the launcher shortcuts and the manifests.
- The `af.shizuku.manager` code namespace is deliberately unchanged, so rebases stay small.
- Two strings keep upstream's name on purpose, because they are wire protocol rather than identity:
  `af.shizuku.plus.api.intent.extra.BINDER` (the binder handoff key, whose other side is
  `ShizukuProvider` in the unforked `api` submodule) and the `af.shizuku.plus.API` meta-data key
  that third-party clients set. Renaming either yields an app that builds and launches but never
  connects.
- The custom permissions (`af.shizuku.plus.permission.*`) also keep upstream's names — this build is
  not intended to be installed alongside upstream's Shizuku+.

**No phone-home** — every automatic outbound path upstream shipped is gone:
- **Sentry** removed at build time (plugin not applied, so no ProGuard-mapping or native-symbol
  upload to sentry.io) and disarmed at runtime (DSN hardwired empty, `SentryAndroid.init()`
  unreachable). The SDK is never armed, so the remaining `captureException`/`addBreadcrumb` call
  sites are inert no-ops; they are left in place only to keep upstream files conflict-free on rebase.
- **`RemoteDbSyncWorker`** — upstream's 24-hourly `WorkManager` fetch of `app-context-db.json` from
  the upstream repo — is no longer scheduled, and the worker itself now cancels the work instead of
  running it, so a rebase that restores the call still produces no traffic.
- **"Update app database"** no longer pulls `apps.json` from the upstream repo.
- **VirusTotal** and **Pithus** APK lookups are disabled — upstream sent the SHA-256 of every APK
  being installed (and, for VirusTotal, the API key) to those services.
- **Automatic update polling** is off by default (upstream defaulted it on), and the update checker
  reads *this* repository's releases, never upstream's.
- The **"email support"** button, which sent a device/OS/version report to the upstream author's
  support address, is removed.
- Upstream's **CI workflows** are removed — `app.yml` injected a Sentry DSN and uploaded debug
  symbols, and it triggered on pushes to `master`, which this fork pushes on every sync.

The only outbound request left is the manual "Check for updates", against this repository.

**De-branding**
- The product name, GitHub links, wiki/issue/release links, About page, Help pages, bug-report
  dialog, watchdog notifications and crash reporter all carry our name and repository.
- Upstream's "thejaustin's Apps" block — ten of the upstream author's own apps seeded into the
  app-context database as recommendations — is removed, along with the matching entries in the SU
  Bridge's suggested-app links.
- Fixed two upstream links that 404'd (`/releases/wiki`, `/releases/issues`) while repointing them.

**Look**
- Black-yellow traced launcher icon: the Shizuku mascot and its hexagon redrawn as uniform-width
  `#FFFF00` line-art on black, at every density, plus a matching monochrome layer for themed icons.

**Build**
- `buildApk` assembles the signed `shizukuplus` release, copies it to `~/tmp` as
  `shiroikuma-shizuku_<version>_arm64-v8a.apk`, and bumps `BUILD_NUMBER`.
- Single-ABI arm64-v8a instead of upstream's universal APK.
- Release signing from the gitignored `signing.properties`.
- Fork versioning: upstream's git-commit-count formula replaced with pinned
  `UPSTREAM_VERSION_CODE`/`UPSTREAM_VERSION_NAME` plus our `BUILD_NUMBER`, so our own commits never
  inflate the version.
