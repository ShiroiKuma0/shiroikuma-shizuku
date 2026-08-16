# shiroikuma-shizuku

**白い熊 雫** — a fork of [ShizukuPlus](https://github.com/thejaustin/ShizukuPlus) (Apache-2.0),
itself a fork of [Shizuku](https://github.com/RikkaApps/Shizuku): the app that runs a privileged
process via **adb** or **root** and lends system APIs to ordinary apps. Package
`shiroikuma.shizuku`, label **"白い熊 雫"**, installable side-by-side with stock Shizuku and with
upstream Shizuku+.

## Branch & remote model (same as the sister forks)

- `origin` = `git@github.com:ShiroiKuma0/shiroikuma-shizuku.git` (ssh) — our fork.
- `upstream` = `https://github.com/thejaustin/ShizukuPlus.git` (https, fetch only) — the **only**
  sync source. Set `fetchRecurseSubmodules=no` on it: upstream's parent repo pins `api` commits that
  were never published to any branch or PR ref, so a recursing fetch always ends in
  `Errors during submodule fetch: api`. We manage `api` by hand against our own fork anyway, and the
  sync skill runs `git submodule update --init --recursive` explicitly, so nothing depends on
  fetch-time recursion. `origin` keeps the default — our own pins are always fetchable.
- `djchi` = `https://github.com/thedjchi/Shizuku.git` — upstream's own base, a **reference remote
  only** (push URL `DISABLED`, `fetchRecurseSubmodules=no`). Read to see what ShizukuPlus has not
  absorbed yet; **never merged or rebased onto**. ShizukuPlus *replays* djchi's history rather than
  fast-forwarding from it, so the git merge base collapses to 2017 and a merge would pull ~1300
  duplicate commits. Anything taken from djchi is a per-item cherry-pick 白い熊 approved. The
  review ledger is `.claude/skills/upstream-new-version/djchi-base`.
- **`master`** mirrors `upstream/master` (currently the `13.6.0.r2195` line). Fast-forward only —
  no fork work ever lives here.
- **`custom`** carries all our work, rebased onto `master` on each upstream sync. **All development
  happens on `custom`**, and it is the GitHub default branch.
- **`api/` is a git submodule** — `origin` is **our** fork `ShiroiKuma0/ShizukuPlus-API` on branch
  `custom`, with `thejaustin/ShizukuPlus-API` as its own `upstream` (push URL `DISABLED`). We carry
  fork commits there, so **an `api/` change needs two pushes** — the submodule first, then the moved
  pointer in the parent. A fresh clone needs `git submodule update --init --recursive` or Gradle
  fails at configuration time.

**The remote tweaks above live in `.git/config`, so a fresh clone does not have them.** Re-apply:

```bash
git remote add djchi https://github.com/thedjchi/Shizuku.git
git remote set-url --push djchi DISABLED
git config remote.djchi.fetchRecurseSubmodules no
git config remote.upstream.fetchRecurseSubmodules no
```
- **Do not rename the `af.shizuku.manager` code namespace** — only the installed `applicationId`
  differs (`shiroikuma.shizuku`). Renaming would make every rebase a mass-conflict.

## Skills (`.claude/skills/`)

- **`build-apk`** — build the signed release APK via the `buildApk` Gradle task, then deliver it
  automatically via the global `/after-build` skill (adb push to `/sdcard/tmp/` if the phone is
  reachable, else scp to skhw) — **no transfer prompt**, never pause to ask how to transfer.
- **`upstream-new-version`** — check upstream for new commits, **and** report the `djchi`
  reference-remote delta (what ShizukuPlus has not yet absorbed from its own base, plus the standing
  outstanding items in `djchi-base`); **⛔ before any rebase, present a proceed-gated descriptive
  table of the new upstream version's features and wait for 白い熊's explicit go-ahead**; then
  fast-forward `master`, rebase `custom`, refresh the version pins, reset `BUILD_NUMBER`, build the
  new `+1`. A "proceed" authorizes the rebase only — djchi cherry-picks are approved item by item.
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
  to 1 on each new upstream version, and **zero-padded to three digits in the name** (`+008`, never
  `+8`) so a shared `~/tmp` sorts in build order; `versionCode` keeps the plain integer.
- **Upstream tracking: `git`** — `custom` is rebased onto every upstream commit, not onto release
  tags, so the fork `versionName` pins the upstream base:
  `<UPSTREAM_VERSION_NAME>+<base commit date>.<HH-MM>.g<8-char merge-base sha>+<BUILD_NUMBER, 3 digits>`
  → `13.6.0.r2246+2026-08-12.02-46.g9f2c01e8+008`. See the global **`git-versioning`** skill. The
  `HH-MM` and the `+` grouping arrived 2026-08-12, together with a fix to render the timestamp in
  **UTC** rather than the commit's own timezone. The sha is
  `git merge-base HEAD master` — the upstream commit our patches sit on, not our own HEAD and not
  master's tip — and the timestamp is that commit's own committer time, so the pin moves *only* on a
  sync. Upstream's `rNNNN` already counts upstream commits, but it is pinned **by hand** in
  `gradle.properties`; the merge-base is read from git and cannot go stale. No git, or no local
  `master`, degrades to `<UPSTREAM_VERSION_NAME>+<NNN>` — the build never fails over a missing pin.
  `versionCode = UPSTREAM_VERSION_CODE * 10000 + BUILD_NUMBER` (`21950008`) is untouched by any of
  this: the sha carries no ordering.
- **`UpdateChecker.parseVersionCode` reads `rNNNN * 1000 + N`**, not `rNNNN` alone. The date and
  sha are ignored on purpose — they say *which* upstream code is in the build, not whether it is
  newer. Before this, every build on one upstream base compared equal, so a newer fork release of
  the same upstream version could never be offered.
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
| **Shell clients** (`rish` in Termux, `adb shell`) | Our own action `${applicationId}.intent.action.REQUEST_BINDER_VERIFIED` → `VerifiedBinderRequestReceiver`. See "Shell access" below — this is the fork's, not upstream's. |

The **`dropin` flavor** (applicationId `moe.shizuku.privileged.api`) is upstream's stock-Shizuku
*replacement*. We never build it — it is incompatible with our own app id by definition. `buildApk`
targets **`shizukuplus`** only.

### ⛔ Exactly one `shizuku_plus_server` may exist

Each server loads the grant table **once** at startup and never re-reads it, every server pushes its
binder to every client at every launch, and a client keeps the **first** binder that arrives and
drops the rest (`ShizukuProvider.handleSendBinder`'s "already a living binder" guard). So a second
server makes authorization a **coin toss per cold start** — the manager truthfully reports "1 app
authorised" while the client truthfully reports being refused, because they are talking to different
servers. Both also flush the same file through their own `AtomicFile` from their own in-memory copy,
so the loser's flush can **silently erase the grant table**.

`starter.cpp` kills any existing server and *then* forks, with nothing atomic in between, and it has
**seven** invocation sites — two starters racing each sweep, each find nothing to kill, and each
forks one (measured 2026-08-04: two servers 44 ms apart). Reordering cannot fix that.

- **`SingleInstanceLock`** (server side) is the actual fix: an exclusive `FileLock` on
  `shiroikuma-shizuku.lock`, taken in `main()` **before** the constructor can publish any binder.
  The loser exits with `ServerConstants.ALREADY_RUNNING`. Keep all three static references — a
  `FileLock` dies with its channel, so dropping them hands the lock back at the next GC.
- **Failure is open**: if the lock cannot be taken *at all* the server starts anyway. A device where
  the lock is unavailable must still get a working Shizuku.
- **Never "the new server kills the old one"** — the old one may hold live user-service bindings for
  apps that are working fine.
- `starter.cpp` also re-checks for a survivor immediately before forking. That only narrows the
  window; it skips pids it just SIGKILLed, because a killed process lingers as a zombie and would
  otherwise read as a live conflict.

### ⛔ A non-null reply from `sendBinder` is NOT proof of delivery

`ShizukuProvider.call()` runs `handleSendBinder()` — **void** — and then returns a freshly allocated
empty `Bundle` unconditionally. Since Android 13 a Bundle received over binder is **defusable** and
unparcelled lazily per key, so a container class the client does not have is swallowed and yields
`null` instead of throwing. A non-null reply therefore means only *"the provider did not throw"*.

`sendBinderToUserApp` must attempt **every** container and never early-return on the first non-null
reply. It did, and that locked out every app built against the published `dev.rikka.shizuku`
artifacts: **`rikka.shizuku.BinderContainer` is ours alone** — it exists only in the vendored
`api/provider`, no released AAR has it — yet it is tried before the `moe` container that every
client does have. The server logged a successful send while the client held no binder at all
(measured 2026-08-04, canary `shiroikuma.mise`).

The redundant calls are free: a client that already took a container bails at the "already a living
binder" guard. **Keep `LOGGER.i("send binder to user app …")` as a single line after the loop** —
the count of those lines per client launch is how a duplicate server is diagnosed, and moving it
inside the loop makes that check silently meaningless.

## Shell access — how `rish` gets the binder without a prompt (2026-08-16)

Upstream asks for consent on **every** `REQUEST_BINDER`, and it is right to. That action is the
public, unauthenticated part of the client API, `Binder.getCallingUid()` is meaningless in
`onReceive()` for a plain broadcast, and `c7c9f6c8` removed a fast path that trusted the intent's own
`callingPackage` / `callingUid`: any app could name an already-authorized package, supply its own
callback binder, and be handed the live full-privilege binder with no interaction at all.

That fix is taken **in full**, and `BinderRequestReceiver.kt` is deliberately kept **byte-identical
to upstream** — it carries no fork diff, so the file upstream actively develops never conflicts.
Verify with:

```bash
diff <(git show master:manager/src/main/java/af/shizuku/manager/receiver/BinderRequestReceiver.kt) \
     manager/src/main/java/af/shizuku/manager/receiver/BinderRequestReceiver.kt
```

### ⛔ `KEY_SHELL_CONSENT_GRANTED` is gone — do not bring it back

The fork used to silence the per-command prompt with a single global flag. It was **wider than the
hole upstream had just closed**: once set, *any* installed app that could broadcast got the binder,
with no identification whatsoever — not even a spoofed package name was needed. The flag, its
accessors, its ADB Tools switch and its strings were all removed together, because a control that no
longer gates anything is worse than a missing feature.

### The three tiers, cheapest first — `VerifiedBinderRequestReceiver`

Our loader sends **our** action, always, whenever the resolved manager is ours. The receiver tries:

| Tier | Mechanism | Notes |
| --- | --- | --- |
| 1 | **Auth token** — `IntentCrypto.decrypt(auth)` vs `ShizukuSettings.getAuthToken()`, constant-time | Upstream's own mechanism, no round trip. The card bakes this into the `rish` script, so a normal setup never reaches tier 2. |
| 2 | **Identity challenge** — verified uid, then `AuthorizationManager.granted` | What makes a remembered consent actually count |
| 3 | **Upstream's consent flow** | Re-broadcasts the public action so `postConsentNotification` is reused, never copied |

**⛔ Never route a token-carrying request to the public action instead.** An earlier attempt did, and
the consequence is not a performance detail: the challenge then never runs, so a stored per-uid grant
is never consulted, and answering the consent prompt has **no effect on the next command**. The user
taps Allow, the grant is written, and they are asked again forever.

### How the identity challenge works, and why it is not the hole upstream closed

`rish`'s callback binder is a real `Binder` in `rish`'s own process. So rather than asking the caller
who it is, the manager hands it a fresh `Binder` plus a single-use nonce and requires it to call
back — on that **inbound** transaction `Binder.getCallingUid()` is supplied by the kernel, the same
property that lets `PolicyProvider` gate on the calling uid with no shared token.

```
rish  --broadcast-->  VerifiedBinderRequestReceiver   (extras ignored for identity)
us    --code 2   -->  rish.receiverBinder             [identityBinder, nonce]
rish  --code 1   -->  our identityBinder              [nonce]
                       |
      Binder.getCallingUid() == the real uid of the rish process
```

A malicious app can absolutely answer the challenge — but the uid the kernel reports is then **its
own**, so it can only ever satisfy the check with a grant it already holds. It cannot borrow Termux's,
which is exactly what the removed fast path allowed.

- Transaction code **2** must agree between `VerifiedBinderRequestReceiver` and
  `ShizukuShellLoader.receiverBinder.onTransact`. Code 1 is the binder handoff itself.
- No `writeInterfaceToken` on either side — matches the existing code-1 convention, which
  `deliverBinder` documents. Adding one is read as the binder slot and breaks the handoff.
- Both directions are `FLAG_ONEWAY`, so there is no deadlock; the loader answers **inline on the
  binder thread**, because the challenge can arrive before `main()` reaches `Looper.loop()`.
- The grant is keyed on the **uid alone** — past pre-v11 `granted`/`grant` use
  `getFlagsForUid`/`updateFlagsForUid` and never consult the package name. That is why an anonymous
  shell client is handled rather than skipped.
- Every failure falls through to tier 3. The worst case is upstream's behaviour, never weaker.

## Setting `rish` up — `RishSetup` and the home card

The card is **fixed**, directly under the server-status card (`HomeAdapter.ID_RISH`, added in the
fixed block, not `DEFAULT_ORDER`). Its button copies one command; pasting that into the terminal is
the whole setup. It green-lights **only on evidence** — a token-authenticated request actually
arriving, recorded by `ShellBinderRequestHandler` and `VerifiedBinderRequestReceiver` — and the
recorded token is fingerprinted, so regenerating the auth token turns the card red again instead of
leaving a pass over a script that can no longer authenticate. The terminal's own directory is
unreadable without root, so checking for files is not an option and guessing would be worse.

### ⛔ Three traps the generated command exists to avoid — all measured on-device

| Trap | What actually happens |
| --- | --- |
| **`-Djava.class.path=<the APK>`** | `rikka.shizuku.shell.ShizukuShellLoader` is built by the separate **`:shell` application module** and ships **only** in `assets/rish_shizuku.dex` — it is **not** in the APK's `classes.dex`. Point the classpath at the APK and the process dies **silently with exit 0**: no error, no output. The script extracts the asset instead. |
| **A hand-copied dex** | `rish` loads `$(dirname "$0")/rish_shizuku.dex`, a copy beside the script — **not** the one in the installed APK. An app update does not update it, so a manager with a new protocol talks to a loader that lacks it, and the only symptom is "it still prompts". The script re-extracts whenever the APK path changes; that path carries a random segment that changes on **every** install, which makes it a reliable staleness stamp. |
| **Writing to `$PREFIX/bin/rish`** | An older `rish` earlier on `PATH` keeps winning. The setup reports success and the script that runs is untouched — this survived being pasted correctly on every install for an afternoon. So the command targets **`command -v rish`** and **prints the path it wrote**. A success message that does not name the file it wrote hides this completely. |

`/system/bin/unzip` is toybox's `ziptool` and `pm path` resolves from an unprivileged app uid — both
confirmed on the Mate XT. If either is unavailable an already-extracted dex keeps working.

**The waiting notice is deferred, not deleted.** `ShizukuShellLoader` prints "Waiting for … 
authorization" 1.5 s after the request and cancels it in `onBinderReceived`. Printed unconditionally
it lands in front of every authorized command; removed entirely, a genuine 90 s wait for a human is
indistinguishable from a hang, which is why it was added.

### ⛔ Diagnostics on 白い熊's devices: use `Log.e`, never Timber

Two independent reasons a log line can be a silent no-op here, both of which cost a full
build-and-test round trip:

- **Timber is unarmed in release.** `ShizukuApplication` plants `Timber.DebugTree()` only when
  `BuildConfig.DEBUG`; release plants just the Sentry tree, and this fork keeps Sentry DSN-less.
- **EMUI drops everything below error level.** The Mate XT's logcat buffer holds only `E/` and `F/`
  lines — measured 7282 and 37, with zero `V`/`D`/`I`/`W`. `Log.i` is invisible.

An empty grep is therefore **not** evidence that the code did not run. Check the level distribution
first: `adb logcat -d -v brief | grep -oE '^[VDIWEF]/' | sort | uniq -c`.

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

## Device policy powers (`policy/`) — lending Device Owner to a sister app

白い熊 made this app **Device Owner**. Sister apps — **白い熊 応用管理**
(`shiroikuma.oyokanri`) first — can then make decisions the user cannot undo from Settings, which is
the whole point: an app-op or a permission that app revokes is otherwise reversible by Settings, by
another tool, and sometimes by the target app itself.

**Two channels, and the split is deliberate. Do not collapse it.**

| | **Delegated scopes** | **The policy API (`policy/`)** |
| --- | --- | --- |
| Covers | `setPermissionGrantState`, `setPackagesSuspended`, `setApplicationHidden`, `setUninstallBlocked` | accessibility blocking, user restrictions, always-on VPN, camera, user-control |
| Why | in the platform's fixed `DELEGATION_*` list | **Device-Owner-only** — no scope can carry them |
| Runs in | the *sister app's* own process, `admin = null` | **this** process, on its behalf |
| Survives this app being stopped | **yes** — `system_server` persists it | no |
| Needs Shizuku running | **no** (see the gate note below) | **no** |

**Prefer delegation wherever it reaches.** A delegated call needs no IPC and no running 雫; the
policy API exists only for what delegation provably cannot carry. Routing a delegatable operation
through the provider trades a persistent grant for a live dependency.

- **`DeviceOwnerHelper.delegate` / `undelegate` / `delegatedScopes`.** `SNOOPING_SCOPES` is the only
  set we ever hand out — `DELEGATION_PERMISSION_GRANT`, `DELEGATION_PACKAGE_ACCESS`,
  `DELEGATION_BLOCK_UNINSTALL`. Every other `DELEGATION_*` constant is privilege given away
  permanently for no reason we have. **`setDelegatedScopes` is `void`**, so `delegate` verifies by
  reading the scopes back; a silently-dropped scope would otherwise look like success.
  **`setDelegatedScopes` can only be called by the owner app** — there is no `dpm` shell command for
  it (checked against `dpm help` on Android 13 and 15), which is the entire reason this lives here
  rather than in the sister app.
- **Revoking a delegation does not undo what the delegate did.** `undelegate` stops future calls
  only; permissions already policy-fixed and packages already suspended stay that way. Offer
  `clearAllLocks` alongside any revoke, or the locks become invisible.

### The wire contract

`PolicyContract.kt` holds every method name, extra and error string **in one place** so the provider,
the UI and the sister app cannot drift apart. Those strings are shared across two repos — **never
rename one.** `PolicyProvider` is exported with **no `android:permission`** (the caller cannot hold
one we define), authority `${applicationId}.policy` → `shiroikuma.shizuku.policy`.

- **A `ContentProvider.call`, not a broadcast.** It is **synchronous** and answers with a `Bundle`,
  which is what a switch the user just tapped needs; and `Binder.getCallingUid()` cannot be spoofed,
  so `PolicyAllowlist` is a real gate and **no shared token is needed** — unlike the 保存復元
  contract, whose token exists precisely because a broadcast has no trustworthy sender. Read the
  calling uid **before** any `clearCallingIdentity()`.
- **Do not extend `DhizukuProvider`.** That authority is the public Dhizuku one that third-party
  Dhizuku clients bind; this private contract does not belong in it.
- `status` answers **even an unauthorized caller** (`ok=false`, `error=not-authorized`, plus
  `is_device_owner`, `api_level`, `delegated_scopes`, `refused_restrictions`), so the sister app can
  say *why* it has no powers rather than merely that it has none.
- Errors carry the **real reason**; the sister app never records a decision a write did not achieve
  and needs something to show.

### Where 白い熊 authorizes a caller — `PolicyAllowlist`

A **separate, explicit, persisted allowlist** (`ShizukuSettings.Keys.KEY_POLICY_ALLOWED_PACKAGES`),
**not** `AuthorizationManager`. Two reasons, both load-bearing: `AuthorizationManager.granted()`
opens with `if (!Shizuku.pingBinder()) return false`, which would drag the policy API into needing a
running Shizuku service — and it does not need one, since DPM runs in this app's own process as the
owner. And device-policy powers are a *different* consent from shell access, worth granting per app
deliberately rather than inheriting.

UI: the **Device policy powers** category on the 白い熊 雫 UI page (Feature Hub), beneath the Device
Owner controls. `DevicePolicyGrantUi` owns the grant/revoke flow so it belongs to no single screen.
The master switch does **both halves** — the allowlist *and* `setDelegatedScopes` — so the two can
never be out of step. Note the category is **collapsed by default**, like every category on that
page: `CollapsiblePreferenceCategory` reads `android:defaultValue` as *defaultExpanded*, and all of
them are declared `false`. A row that cannot work is **disabled, never hidden** — hiding it would
make the section look complete while doing nothing.

### ⛔ Accessibility is stored as a BLOCKLIST; the platform list is derived

`setPermittedAccessibilityServices` is an **allowlist** whose default (`null`) means *everything is
permitted*, and it takes **package names**, not `ComponentName`s. There is no "block one" form. Used
naively it has two failure modes, and the second survives being correct: a wrong enumeration bars
every service on the device, and once a non-`null` list exists **any accessibility service installed
later is off it and silently barred**, with nothing in any UI explaining why it will not stay enabled.

So `AccessibilityBlocklist` inverts it. The durable state is the **blocklist**; the platform list is
derived as `(every installed service package) − blocklist` and recomputed whenever either side can
have changed — a blocklist edit, or a package add/remove/replace via `PolicyPackageChangeReceiver`,
plus `recomputeIfStale` when the section is opened, since the broadcast is best-effort. **When the
blocklist empties the platform list goes back to `null`**, not to a hand-built "everything" — that
would be one install away from wrong. The blocklist is persisted **only after the platform accepted
the new list**, and a failed enumeration changes nothing.

### ⛔ Five user restrictions are refused in code

`DevicePolicyApi.REFUSED_RESTRICTIONS` — `DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_SAFE_BOOT`,
`DISALLOW_FACTORY_RESET`, `DISALLOW_INSTALL_UNKNOWN_SOURCES` and its `_GLOBALLY` twin. Each removes a
route needed to fix a mistake: ADB is both how 応用管理 gets its privileges and how you would recover;
safe boot is the offline route; factory reset is the last resort named at the top of this file; and
sideloading is how a fixed build of either app gets installed. **They are refused, not warned about**
— `set_user_restriction` returns an error rather than applying them. The reasoning lives beside the
constant; read it before removing any.

### The way back must always exist

`clearAllLocks(context, pkg)` releases everything for one package, or for every installed package
when `pkg` is null: permission grant states not at `PERMISSION_GRANT_STATE_DEFAULT`, suspension,
uninstall block, user-control, and the accessibility blocklist. It needs **no ledger on either side**
— the Device Owner can walk a package's permissions and read the grant state back, so it can undo
what its *delegate* did even after that delegate is uninstalled. It returns a per-step result
(`steps` / `steps_failed`); a silent "done" would be worst precisely here, because this is what
someone runs when they are already stuck.

### Everything here is hard to undo — say so in the UI

Every power above is designed so the user cannot reverse it from Settings; that is what makes a lock
a lock, and what makes a mistake expensive. A lock **outlives the app that set it** — uninstalling
応用管理 releases nothing, since the policy is stored under *this* app's admin. Dangerous rows carry a
red 危険 tag on the row itself (not only in the dialog), and the confirmation follows
`DeviceOwnerHelper.confirmAndClear`'s shape: **Cancel in the positive slot**, the destructive choice
red in the quiet negative slot via `ShiroikumaDialogs.markDestructive`, and a message that says *what
it does, what it breaks, and how to undo it*, in that order.

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

**Device policy powers** (2026-08-01, `13.6.0.r2195…+008`): this app holds Device Owner on the
Motorola razr 40 ultra and lends it to sister apps — delegated scopes plus the `policy/` provider.
Verified end to end from the 応用管理 side: `dumpsys device_policy` shows
`mDelegationMap → shiroikuma.oyokanri[size=3]`, and a permission locked from that app reads back
`granted=false, flags=[…POLICY_FIXED…]`. See the section above. The Mate XT has **no** Device Owner
(its only device admin is `shiroikuma.jiyusagyoban`), so this is testable on the razr only.

**Shell access reworked** (2026-08-16, `13.6.0.r2277…+011`): upstream's `c7c9f6c8` closed a spoofable
fast path, the fork's wider `KEY_SHELL_CONSENT_GRANTED` flag went with it, and prompt-free `rish` was
rebuilt on the auth token plus a uid-verified binder challenge. `BinderRequestReceiver.kt` is back to
byte-identical upstream. A fixed home card generates the one-paste setup command. Working on the Mate
XT in Termux; the two published releases on the older base (`…r2277+001`/`+002`) still carry the
upstream vulnerability and may be worth pulling.

See the "Shell access" and "Setting `rish` up" sections above — **all three traps recorded there cost
a failed build each**, and every one was discoverable by running the thing on the phone instead of
reasoning about it. If shell access ever breaks again, measure first: check which `rish` is on `PATH`,
check the loader is not a stale dex, and remember that a silent logcat on EMUI proves nothing.
