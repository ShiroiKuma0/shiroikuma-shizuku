<div align="center">

<img src="manager/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 雫 icon" />

# 白い熊 雫

**Lend system-level APIs to ordinary apps — with nothing phoning home.**

A fork of [ShizukuPlus](https://github.com/thejaustin/ShizukuPlus), itself a fork of
[Shizuku](https://github.com/RikkaApps/Shizuku): it starts a privileged process over **adb** or
**root** and hands that privilege to apps that ask for it.

This fork is **de-branded, black-yellow, and silent** — every telemetry and call-home path upstream
shipped has been removed — with **major additions**: a full **白い熊 雫 UI** theming page, a
**保存復元** export/import contract with token-gated automation, **device-policy powers** handed to
authorized sister apps, and the house look driven through every screen. Installs as
`shiroikuma.shizuku`.

**📥 Latest release: [`13.6.0.r2279+2026-08-16.13-12.g690b3632+002`](https://github.com/ShiroiKuma0/shiroikuma-shizuku/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-shizuku/releases)

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
| **Changelog fetch** — the "What's New" dialog pulled release notes from GitHub on every update | Gone. The changelog is built into the APK (see below). |

What remains is exactly one outbound request, and only when **you tap "Check for updates"**: a read
of this repository's own releases. Nothing about the device is sent.

---

## 📖 The changelog is inside the APK

"What's New" opens after an update with the real notes, **offline, with no network request**, and
it covers **both halves** of what changed: this fork's own work *and* the upstream commits the
build was rebased onto.

That is not how it started. The dialog fetched GitHub release notes for a tag it built the way
*upstream* names tags — `v13.6.0.r2219` — while our tags carry no `v` and are the full version
string. Every fetch 404'd, so the dialog was empty on every single update. And fixing the tag alone
would not have been enough: most builds here are never published, so there is frequently no release
to fetch at all.

So the merge happens at build time. A Gradle task writes the changelog into the APK, splicing in a
section for the build being made — the upstream commits since the previous base, and the fork
commits not yet written up. Both bases are read out of the version strings, which embed the upstream
sha, so they survive a rebase; and the fork half is matched by commit *subject*, because a rebase
rewrites every one of our shas and a sha-based range would call the whole fork new after every sync.

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

## 🛡️ Device policy powers, handed to a sister app

白い熊 雫 can be Device Owner, and Device Owner is what makes a decision **hard** — an app-op or a
permission an ordinary tool revokes can be put back by Settings, by another tool, and sometimes by
the app itself. This fork can pass a slice of that power to an authorized sister app, and there are
exactly two ways to do it because neither covers the other's half.

**Delegated scopes** (`delegation-permission-grant`, `delegation-package-access`,
`delegation-block-uninstall`) let the other app's own process fix permissions, suspend apps and block
uninstalls — with no IPC, and with 白い熊 雫 stopped, because `system_server` stores the grant.
There is no `dpm` command for `setDelegatedScopes`, which is the entire reason this had to live in
the owner app. The grant is verified by reading the scopes **back**: the call returns `void`, so a
scope the platform silently dropped would otherwise look like success.

The rest cannot be delegated at all, so those run here on the other app's behalf over a private
`ContentProvider.call()` at `shiroikuma.shizuku.policy` — a provider call rather than a broadcast,
because it answers synchronously and `Binder.getCallingUid()` cannot be spoofed, so the allowlist is
a real gate and no shared secret is needed.

Accessibility blocking is stored **inverted**: the platform API is an allowlist whose `null` default
means "everything permitted", with no block-one form, and once a non-`null` list exists any service
installed *later* is barred silently. So the durable state is a blocklist and the platform list is
derived from it and recomputed on every package event. Five user restrictions are **refused in
code** — the ones that would remove ADB, safe boot, factory reset or sideloading, i.e. every route
you would use to undo a mistake. Every dangerous row carries a red 危険 tag before any dialog opens,
and **Clear all device-policy locks** works even if the app that set them is gone.

---

## 🗂️ Everything folds, and you can see what's folded

Settings groups had a chevron pointing **down when folded** and up when unfolded — ambiguous both
ways. It is now right when folded, down when unfolded, and the home cards fold the same way. Group
boxes were already being drawn, filled with a surface role that is the same pure black as the page,
so they were invisible; every group now carries a rounded **yellow** border that shrinks to the
title row when folded. Fold state persists across relaunches, and everything starts unfolded.

---

## 🔖 The version says which upstream commit the build is on

`custom` is rebased onto upstream's branch tip, so the fork's base is an arbitrary upstream commit —
and the upstream version pinned in `gradle.properties` is maintained by hand, so it can go stale
without anyone noticing. The version now appends the base commit's **date and sha**, read from
`git merge-base HEAD master`, so `13.6.0.r2201.2026-08-01.g14550b5e+004` says exactly which upstream
code is inside. It moves only on a sync; the date is there so builds sort chronologically, since a
bare sha orders them at random.

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

## ⌨️ `rish` asks once, not on every command — and proves who is asking

Upstream's consent dialog has nothing to remember an answer by, so it re-asks on **every single
request**, putting a full-screen prompt in front of each `rish -c '…'`. The obvious fix — store one
"shell clients are allowed" flag — is the wrong one, and this fork shipped it before removing it:
once set, *any* installed app that could send a broadcast got the full-privilege binder, identifying
itself as nothing at all.

The answer here is to establish **who is actually asking**, in three tiers, cheapest first:

1. **The auth token.** Upstream's own mechanism, compared constant-time, no round trip. The setup
   card below bakes it into your `rish` script, so a normal setup never gets past this tier.
2. **An identity challenge**, and then the grant you already gave that client.
3. **Upstream's consent prompt**, unchanged, for anything the first two could not settle.

The challenge is what makes a remembered answer safe to keep. `Binder.getCallingUid()` tells you
nothing inside a broadcast receiver — which is why a fast path that trusted the *sender's own claim*
about its package and uid was forgeable, and why upstream removed it. But `rish`'s callback binder is
a real binder in `rish`'s own process, so rather than asking the caller who it is, the manager hands
it a fresh binder and a single-use nonce and requires it to call back. On that inbound transaction
the uid comes from the kernel.

A hostile app can answer the challenge perfectly well — and the uid the kernel reports is then its
own. It can only ever satisfy the check with a grant it already holds; it cannot borrow Termux's.
Every failure falls through to tier 3, so the worst case is upstream's behaviour and never weaker.

Grants stay revocable in the authorization list, and every grant and refusal is written to the
Activity Log, so the decisions are auditable after the fact.

---

## 📋 One pasted command sets `rish` up

A fixed card under the server status copies a single command. Paste it into your terminal and that
is the whole setup — no file shuffling, no classpath to work out.

The card turns green **only on evidence**: a token-authenticated request actually arriving. It is not
guessing, and it cannot guess — a terminal app's own directory is unreadable without root, so there
is no file it could check. The token it saw is fingerprinted too, so regenerating your auth token
turns the card red again instead of leaving a stale pass over a script that can no longer
authenticate.

The command is shaped by three failures that are all invisible when you hit them: pointing the
classpath at the installed APK kills the process **silently with exit 0** (the loader ships only as a
bundled dex asset, never in the APK's own `classes.dex`); a hand-copied dex goes stale on the next app
update, whose only symptom is "it still prompts"; and writing to `$PREFIX/bin/rish` quietly loses to
any older `rish` earlier on your `PATH`. So the script re-extracts itself whenever the install path
changes, targets `command -v rish`, and **prints the path it wrote**.

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

## 🔌 A third privileged shell — adb over the loopback

`adb tcpip 5555` is widely misread as opening a channel *to the PC*. It does not: it restarts the
**phone's own adbd** on a TCP port of the phone, which anything on the phone can then reach. The
cable is needed for exactly one command — after that it comes out, and the app drives adb on itself.

This fork turns that into a real privilege tier beside the Shizuku service and root. Privileged
actions **chain** their tiers — service/root, then loopback adb, then a copy-this-command dialog as
the last resort — so an action no longer dead-ends on a PC instruction while a working adb shell sits
listening. Each tier is judged by re-checking the result, never by an exit code: `pm` and `dpm` both
exit 0 without doing anything on some OEM builds.

The port is found by **connecting**, not by reading `service.adb.tcp.port` — that property is labelled
`adbd_config_prop`, so an ordinary app very likely reads nothing, and a blocked read looks exactly
like "adb is off".

---

## ✅ Start 白い熊 雫 automatically after boot

A live checklist above the wireless-debugging card, not a page of instructions: every row reads the
real state and carries its own fix. Notifications, **pairing** over wireless debugging, **starting the
server**, `WRITE_SECURE_SETTINGS` — granted through the running server, or over the loopback adb
connection when the server is down — battery-optimisation exemption, start-on-boot, and background
launch. It collapses to a single satisfied line once nothing is outstanding.

Pairing and starting are kept apart, because they are different things and only the first is what
"paired" in Android's own screen means. Nothing on the device tells an app whether it is paired, so
the app remembers its own handshake — and, since that memory can only go stale in one direction,
that row keeps a **Pair again** button rather than pretending to be live state. The start row then
names the road this device actually has: paired means tapping Start is the whole of it, and the port
is discovered for you; unpaired means one cabled `adb tcpip 5555`, after which the cable comes
straight back out.

That last row knows which world it is in. On a ROM with its own autostart manager it opens that screen
where the ROM permits — decided by a real capability test rather than a brand check — and stays honest
about being unable to read it back. On a stock ROM, where no such screen exists, the standard
background restriction *is* the whole story, so the row shows real state and opens the page that
carries the toggle.

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

- `versionName = <UPSTREAM_VERSION_NAME>+<BUILD_NUMBER>` → `13.6.0.r2178+29`
- `versionCode = UPSTREAM_VERSION_CODE * 10000 + BUILD_NUMBER` → `21780029`

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
