---
name: build-apk
description: Build the signed release APK of shiroikuma-shizuku (the "白い熊 雫" privileged-API manager — a fork of ShizukuPlus, itself a fork of Shizuku) with the `buildApk` Gradle task, then deliver it automatically via the global /after-build skill (adb push if the phone is reachable, else scp to skhw — no prompt). Always build first without asking permission to build. Use whenever 白い熊 asks to build the app, build the APK, make a release build, or build and send to the phone.
---

# Build the 雫 release APK and deliver it

> **Never ask whether to build — just build.** When this skill applies (白い熊 asked to build, or
> you've made changes ready to test), run the build immediately. Do **not** ask "shall I build?".
> There is **no** transfer question either: after a successful build, deliver the APK automatically
> via the global **`/after-build`** skill — no prompts at all.

> **Never run `adb install` (or `pm install`).** You may `adb push` (that's what `/after-build`
> does); **白い熊 installs the APK themselves** from the phone's file manager. The push destination
> is always `/sdcard/tmp/`.

> **Never `git commit` or `git push` on your own.** Building does not include committing. After
> building, 白い熊 tests the build. **Only when they explicitly say "Push"** do you `git commit` and
> `git push origin custom`. Their **"Push"** means *commit-and-push-to-the-fork* — unrelated to the
> `adb push` file copy.

## Build environment (this machine)

- The default `java` is **JDK 11**, which cannot run Gradle 9.x. Always export JDK 21.
- The Android SDK is **not** on a default env var; export `ANDROID_HOME` explicitly, and keep the
  gitignored `local.properties` (`sdk.dir=/home/shiroikuma/android-sdk`) at the repo root — a
  background shell does not inherit `ANDROID_HOME`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/shiroikuma/android-sdk
```

**The `api/` directory is a git submodule** (`thejaustin/ShizukuPlus-API`). A fresh clone must run
`git submodule update --init --recursive` or Gradle fails at configuration time with
*"Configuring project ':server-shared' without an existing directory is not allowed"*.

There **is** native code here (`manager/src/main/jni`, CMake 3.22.1) plus the `:server`,
`:starter`, `:shell` and `:compat` modules the manager bundles as assets — a cold build is slow.
Run it with `run_in_background` and poll the log rather than risking a foreground timeout.

## Steps

1. **Note the output filename / version.** Everything comes from `gradle.properties`:
   ```bash
   grep -E 'UPSTREAM_VERSION_CODE|UPSTREAM_VERSION_NAME|BUILD_NUMBER' gradle.properties
   ```
   - The APK will be `shiroikuma-shizuku_<UPSTREAM_VERSION_NAME>+<BUILD_NUMBER>_arm64-v8a.apk`,
     using the `BUILD_NUMBER` value **before** the build (`buildApk` bumps it afterward).
   - versionCode for that build = `UPSTREAM_VERSION_CODE * 10000 + BUILD_NUMBER`
     (e.g. 2178 → `21780001`).
   - Gradle configuration echoes the same thing as its first line:
     `shiroikuma-shizuku 13.6.0.r2178+1 (versionCode 21780001)`.

2. **Build** (release, signed) — from the repo root:
   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk
   ./gradlew buildApk --console=plain < /dev/null
   ```
   - `buildApk` runs `assembleShizukuplusRelease`, copies the signed APK to `~/tmp/<apk name>`, and
     auto-increments `BUILD_NUMBER` in `gradle.properties`.
   - It prints `>>> <path>` and `>>> versionCode <n>` (cyan) — use those to confirm the exact
     filename/code; confirm `BUILD SUCCESSFUL`.
   - **Fast dev iteration:** `./gradlew :manager:assembleShizukuplusDebug` — no R8, much faster.
     The shippable build is always `buildApk`.

3. **At the end of every build, deliver the APK via `/after-build`** — no exceptions, no asking. As
   soon as `BUILD SUCCESSFUL` appears and the signed APK is in `~/tmp/`, invoke the global
   **`/after-build`** skill; it picks adb-push (phone reachable) or scp-to-skhw on its own and
   announces what landed.

4. **What `/after-build` does** (for reference — you don't run these by hand): `/adb-check` lists
   devices UNSANDBOXED; if the phone is reachable, `/adb-push` copies the newest `~/tmp/*.apk` to
   `/sdcard/tmp/`; otherwise `/scp` copies it to `skhw:~/tmp/`. It never runs `adb install`.
   Per the global adb rule, wireless adb is disconnected at the end of the delivery batch.

## Product flavors (upstream ships two)

| Flavor | applicationId | What it is |
| --- | --- | --- |
| **`shizukuplus`** | `shiroikuma.shizuku` (ours) | **What we build.** Coexists with stock Shizuku — it does not claim the stock package name or the stock provider authority. |
| `dropin` | `moe.shizuku.privileged.api` | Upstream's stock-Shizuku *replacement*, mutually exclusive with stock. We never build it; it is incompatible with our own app id by definition. |

`buildApk` targets **`shizukuplus`** (`assembleShizukuplusRelease`).

**Third-party client apps still work.** Apps written against the stock Shizuku API look for
`moe.shizuku.privileged.api`; they reach us through the **Compat Hub** stub — the separate
`:compat` module, package `moe.shizuku.privileged.api`, bundled as `manager/src/main/assets/compat.apk`
and installed on demand — which forwards to our manager. That stub is upstream's design and is
deliberately left alone.

## Signing

Release signing is non-interactive: `signing.gradle` reads the **gitignored** `signing.properties`
at the repo root (`signing.properties_sample` documents the keys). This fork uses its own keystore
`~/.android-keystores/shiroikuma-shizuku.jks` (alias `shizuku`); the store/key password is recorded
in `~/〇/[666] 私資料/[666][27] 暗号/android-keystores.org`, with a backup of the `.jks` in the
`android-keystores/` directory next to it. Losing both loses the signing identity — updates could no
longer install over an existing app.

> ⚠ **`signing.gradle` fails SILENTLY-ish.** If `signing.properties` is missing it falls back to the
> Android **debug** keystore rather than erroring, so the release APK is produced but carries the
> wrong signing identity and will refuse to update an existing install
> (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Before delivering, confirm `signing.properties` exists.

## Versioning (how the numbers are formed)

- Upstream derives its version from `git rev-list --count HEAD`. We **cannot** reuse that: on
  `custom` the count includes our own commits. So `gradle.properties` **pins** the upstream numbers:
  `UPSTREAM_VERSION_CODE` (upstream's commit count, e.g. 2178) and `UPSTREAM_VERSION_NAME` (e.g.
  `13.6.0.r2178`), refreshed by the `upstream-new-version` skill on each sync.
- `BUILD_NUMBER` is **our** increment, bumped on every `buildApk`, reset to `1` on each new upstream
  version.
- Fork `versionName = "<UPSTREAM_VERSION_NAME>+<BUILD_NUMBER>"`;
  `versionCode = UPSTREAM_VERSION_CODE * 10000 + BUILD_NUMBER`.
- Single-ABI **arm64-v8a** build (`abiFilters` in `manager/build.gradle`); upstream packages a
  universal APK carrying all four ABIs.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line
of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
