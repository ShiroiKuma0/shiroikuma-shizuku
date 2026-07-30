<div align="center">

<img src="manager/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 雫 icon" />

# 白い熊 雫

**Lend system-level APIs to ordinary apps — with nothing phoning home.**

A fork of [ShizukuPlus](https://github.com/thejaustin/ShizukuPlus), itself a fork of
[Shizuku](https://github.com/RikkaApps/Shizuku): it starts a privileged process over **adb** or
**root** and hands that privilege to apps that ask for it.

This fork is **de-branded, black-yellow, and silent** — every telemetry and call-home path upstream
shipped has been removed — with **major additions**: a full **白い熊 雫 UI** theming page, a
**保存復元** export/import contract with token-gated automation, and the house look driven through
every screen. Installs as `shiroikuma.shizuku`.

**📥 Latest release: [`13.6.0.r2178+24`](https://github.com/ShiroiKuma0/shiroikuma-shizuku/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-shizuku/releases)

</div>

---

## 🔇 No phone-home

This is the headline difference from upstream. The app makes **no automatic network request of any
kind**. Removed outright:

| Upstream behaviour | Here |
| --- | --- |
| **Sentry crash reporting** — every crash, breadcrumb trail and ANR sent to the upstream author's Sentry account | Gone. The Gradle plugin is not applied, the DSN is hardwired empty, and `SentryAndroid.init()` is never reached, so the SDK is never armed and has no transport. |
| **Sentry build-time uploads** — ProGuard mappings and native debug symbols pushed to sentry.io | Gone with the plugin. |
| **24-hourly remote database sync** — a `WorkManager` job fetching `app-context-db.json` from the upstream repo on a timer | Gone. The worker now *cancels* itself instead of scheduling. |
| **"Update app database"** — pulled `apps.json` from the upstream repo | Gone. The bundled database is used as shipped. |
| **VirusTotal lookup** — SHA-256 of every APK you install, plus your API key, sent to VirusTotal | Gone. No connection, no key read. |
| **Pithus lookup** — SHA-256 of every APK you install sent to `beta.pithus.org` | Gone. No connection. |
| **Automatic update poll** — releases API hit on every app start | Off by default, and repointed at *this* repo. |
| **"Email support"** — device, OS and version report to the upstream author's support address | Button removed. |
| **Upstream CI** — workflows that injected a Sentry DSN and uploaded debug symbols | Removed. |

What remains is exactly one outbound request, and only when **you tap "Check for updates"**: a read
of this repository's own releases. Nothing about the device is sent.

---

## 🎨 The look

Pure black with pure yellow `#FFFF00`, from the splash screen to the last dialog — no grey flash on
launch, no white frame between the splash and the first screen. The launcher icon is the house
**traced mark**: the Shizuku mascot and its hexagon redrawn as uniform-width yellow line-art on
black, with a matching monochrome layer for themed icons. Dialogs get a black fill and a yellow
border; even transient toasts are drawn in the house style rather than the system's grey pill.

Because every surface role is the same pure black, **every container carries a visible border** —
yellow for groups and sections, grey for ordinary rows — instead of relying on the tonal lift that a
normal dark theme uses to tell a card from its background.

---

## 🐻‍❄️ The 白い熊 雫 UI page

A theming page that actually drives the app, not a preview. Reached from **Settings → 白い熊 雫 UI**,
or by **long-pressing the settings cog** on the home screen.

- **Colours** open a four-slider **RGBA** picker with a live preview and one-click prefilled swatches.
- **Typography** imports your own `.ttf`/`.otf` fonts; the picker renders each font *in its own
  glyphs*, and a long-press deletes an import.
- **Shape & borders, spacing, lists & cards** — every size, weight, roundness and thickness is a
  slider, and the border, divider, underline and pill sliders all reach **0**, so "off" stays
  reachable.
- Every section carries a **live preview** and its own reset.

The app is hybrid — Compose over View-based preference screens and RecyclerView cards — so each knob
is wired through all three layers at once. Changes apply immediately, without restarting the Activity.

---

## 💾 保存復元 — export, import, automation

- **One ZIP per export**, `shiroikuma-shizuku_<timestamp>.zip`, holding a manifest plus one JSON per
  category and your imported fonts. Import **merges** and skips absent categories.
- Written **atomically** — a `.part` file renamed only once the archive is closed, and deleted on any
  failure or cancel, so a truncated archive can never silently become "the latest backup".
- **Automation** is token-gated with the master switch **off by default**: `EXPORT_STATE`,
  `LIST_CATEGORIES` and `CANCEL_EXPORT` on one receiver, with progress reported as **real counts**
  rather than a percentage. The token file is deliberately excluded from the export.

---

## 🔌 Third-party apps can actually attach

Upstream's server could not attach any client built against the modern Shizuku API — every one of
them, not just an unlucky few. Three defects compounded: the interface token was read by reflection
for a method that does not exist, so the whole interception block was skipped for every caller; the
read cursor was never rewound before falling through, which turns ordinary calls into
`Binder invocation to an incorrect interface` the moment the first defect is fixed; and the raw
transaction code a client attaches with collided with an AIDL method whose declared id lands on the
same wire code, so an attach was answered by a method that throws *"Not an attached client"* at the
very client trying to become one. No app could work around it — the collision was on the server's
side of the wire.

Fixed here, together, because each fix alone leaves the server broken in a different way. Binder
delivery is fixed too: the three `BinderContainer` classes now go out in separate calls, since
`Bundle.getParcelable` unparcels *every* value and a client shipping only one of them could
previously read none.

---

## 🔁 Start, stop and restart the server from the app

The privileged server is a separate process that outlives the app, so force-stopping and relaunching
白い熊 雫 does nothing to it — after an update the *old* server keeps serving the old code. The status
card now carries **Start / Stop**, with the first reading **Restart 白い熊 雫 server** while one is
running, and an info line that explains exactly that distinction.

Restart is deliberately one action rather than stop-then-start: without root the only shell available
is the one the running server lends the app, so stopping first would destroy the privilege needed to
start again. When no shell exists it tries local TCP ADB — which works over a plain cable with no
Wi-Fi and no pairing — before offering the wireless route. Every press gives immediate, persistent
feedback until the server is confirmed back up.

---

## ✅ Enable automatically after reboot

A live checklist above the wireless-debugging card, not a page of instructions: every row reads the
real state and carries its own fix. Notifications, one recorded ADB connection, `WRITE_SECURE_SETTINGS`
granted through the running server with no PC involved, start-on-boot, battery-optimisation exemption,
and the OEM launch manager — with a button wherever the ROM permits opening it, decided by a real
capability test rather than a brand check. It collapses to a single satisfied line once nothing is
outstanding.

Below a hairline, the same card handles **Device Owner**: granted through the running server, with the
real `dpm` refusal surfaced rather than a generic failure, and the exit always visible — behind a
warning that says plainly that re-granting needs a device with no accounts at all and can dead-end in
a factory reset.

---

## 🧩 Installing side-by-side

The app id is `shiroikuma.shizuku`, so it installs **alongside stock Shizuku**
(`moe.shizuku.privileged.api`). Third-party apps still find it:

- Apps written against the **stock** Shizuku API reach it through the bundled **Compat Hub**
  (package `moe.shizuku.privileged.api`), installed on demand from the home screen once the service
  is running.
- Apps written against the **Plus** API reach it directly.

Upstream's `dropin` flavour — which *replaces* stock Shizuku by claiming its package name — is not
built here.

> ⚠️ **Not installable alongside upstream's Shizuku+.** This fork keeps upstream's
> `af.shizuku.plus.permission.*` names, because they match `ShizukuProvider` in the unforked `api`
> submodule and changing them would break client compatibility. Two apps cannot declare the same
> custom permission, so installing both fails with `INSTALL_FAILED_DUPLICATE_PERMISSION`. This is a
> deliberate trade: exact client compatibility over coexistence with upstream. Stock Shizuku is
> unaffected.

---

## 🔧 What it does (inherited from upstream)

Everything upstream's feature set provides is intact: the unified **root / ADB / Dhizuku** privilege
provider, the transparent shell interceptor, the SU bridge and local ADB proxy, the Plus APIs
(storage proxy, window manager, overlay/theming bridge, network governor, process control, AVF),
the activity log, gestures, bulk management, Service Doctor, and the quick-settings tile.

See upstream's documentation for the details of those features.

---

## 🏗️ Build

```bash
git submodule update --init --recursive       # the api/ submodule is required
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=$HOME/android-sdk
./gradlew buildApk
```

`buildApk` assembles the signed `shizukuplus` release, copies it to `~/tmp` as
`shiroikuma-shizuku_<version>_arm64-v8a.apk`, and bumps the build counter.

**Versioning.** Upstream derives its version from its git commit count, which cannot be reused on a
fork (our own commits would inflate it). The upstream numbers are pinned in `gradle.properties` and
refreshed on each sync; `BUILD_NUMBER` is this fork's increment:

- `versionName = <UPSTREAM_VERSION_NAME>+<BUILD_NUMBER>` → `13.6.0.r2178+14`
- `versionCode = UPSTREAM_VERSION_CODE * 10000 + BUILD_NUMBER` → `21780014`

Single-ABI **arm64-v8a**; upstream packages a universal APK. Release tags carry **no `v` prefix** and
equal the fork `versionName` exactly, so the in-app update check never re-offers an installed build.

Unit tests — including the contract tests that keep the app id from drifting out of the Compat Hub
and the native starter — run with:

```bash
./gradlew :manager:testShizukuplusDebugUnitTest
```

---

## 📃 License

[Apache 2.0](LICENSE), as upstream. Upstream's acknowledgements, third-party attributions and full
license inventory are preserved in [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) and
[NOTICE](NOTICE) — this fork claims none of that work.

Not affiliated with RikkaApps, thedjchi, or the ShizukuPlus author.
