# 白い熊 雫 — fork changelog

Fork-only notes. Upstream's own release notes live in `CHANGES.md` and, since `r2267`, also in a
root `CHANGELOG.md` — never fold fork notes into either.

Versions are `<upstream version>+<upstream base date>.<HH-MM>.g<sha>+<our build number>`; the `+N`
resets to 1 on each upstream sync. Builds from `13.6.0.r2201.2026-08-01.g14550b5e+004` through
`13.6.0.r2246.2026-08-12.g9f2c01e8+001` dot-joined the pin instead and carried no time; builds up to
`13.6.0.r2195+5` used the older `<upstream version>+<N>` form.

## 13.6.0.r2397+2026-09-02.14-54.g37d086d4+002

Rebased onto upstream `13.6.0.r2397` — twelve commits over two days, 2805 insertions against 67
deletions in 29 files, and the largest single expansion of the privileged surface this fork has
absorbed. Eighty-nine replayed commits, five content conflicts, and a submodule that had to move
first: nothing in the parent would compile until it did.

**The api submodule carried nine of the twelve.** Seven new AIDL interfaces — `IStatusBarGovernorPlus`,
`IPackageGovernorPlus`, `IDisplayTunerPlus`, `IAppInspector`, `IPrivilegedDataSource`,
`IBackupRestorePlus`, `IApkPatcher` — plus three methods added to `IStorageProxy` and about 1145
lines of client wrappers in `ShizukuPlusAPI.java`. Every one of the new server implementations
implements one of those interfaces, so the parent rebase was meaningless on its own.

### Two things that would have shipped broken

**A duplicate AIDL transaction id that git merged in silently.** This fork moved
`shouldShowRequestPermissionRationale()` off id 16 long ago, because its wire code
(`FIRST_CALL_TRANSACTION + 16` = 17) is the same code `Shizuku.java` sends as the hand-written v13
attach. It was parked on 122. Upstream has now claimed **122 through 128** for the seven new
`IShizukuService` getters — and because upstream appends at the end of the interface while our edit
sits in the middle, both sides merged **cleanly**, with no conflict marker anywhere, into an
interface declaring id 122 twice. Nothing flags that until `aidl` runs. Ours is now parked at **500**,
far above the block upstream is still appending to, with the reasoning written into the file.

**Upstream's tip does not compile.** `ShizukuPlusAPI.ApkPatcher.getService()` calls
`getIShizukuServiceOrThrow()`, a method that exists nowhere in the tree — every one of its fourteen
sibling wrappers in the same file uses `requirePlusService()`. Fixed here to match the siblings,
including the null guard that helper needs, and marked so it can be dropped when upstream corrects
it. This is the third sync in a row where upstream's master did not build.

The good news on that front: the missing `R` import this fork patched around last cycle **was** fixed
upstream, in `5379bbe0`. That fork fix is retired and `ShizukuTileService`'s imports are upstream's
again.

### Upstream fixed a bug we had already fixed — better

`752afb81` breaks out of a stuck `STARTING` state after a failed launch, which is the same bug this
fork fixed on 2026-07-31 in `c5d730f9`. **Ours is kept**, because it is the stronger of the two:
upstream adds a 90-second timeout to `update()`, whereas this fork splits the two meanings apart —
`update()` stays the passive refresh with that same 90-second backstop, and `settle()` resolves the
state the *moment* an attempt ends rather than making the user wait out the deadline.

Their version did not merge away quietly, though. Upstream's own `STARTING_TIMEOUT_MS` and
`startingTimestamp` fields auto-merged in alongside ours, leaving the constant **declared twice** in
one object — another clean merge that Kotlin rejects. Both upstream fields were removed; the
timestamp had no reader left at all.

### The QS tile, taken whole and then made ours

`81ef87e1` rewrites the tile: the subtitle now shows mode and state together for the Samsung OneUI
8.5 wide layout, a tap while running opens a Stop/Restart/Open dialog on API 34+, a tap while
transitioning says so instead of doing nothing, and a long-press opens a new `TileOptionsActivity`.
Upstream's structure is taken entire. Four fork properties are carried onto it:

- **The tile label is no longer a fork diff at all.** It used to be a hardcoded `"白い熊 雫: Active"`
  that this fork patched in on every sync. Upstream now resolves it through `R.string.app_name` —
  which *is* our `白い熊 雫` resValue — so their line is strictly better de-branding than ours was,
  and the file loses that permanent conflict surface.
- **Both new dialogs are house-styled.** They are raised as plain `android.app.AlertDialog` straight
  from a `TileService` and an `Activity`, and `ShiroikumaDialogs.installGlobalStyling` only reaches
  `DialogFragment`s. In this theme an unstyled dialog is a black fill on a black ground with no
  border — **invisible**, not merely plain — which is exactly the failure the standing border rule
  exists to prevent.
- **The new transitioning toast goes through `ShiroikumaToast`**, like every other toast in the file.
- **`TileOptionsActivity.startShizuku()` settles on failure.** It called `update()` unconditionally,
  and `update()` preserves `STARTING`, so a root shell exiting non-zero would have left the tile and
  the home card mid-transition with the start button — the very control needed to retry — disabled.

### Auto-reconnect is now off by default

`a6aa9edc` added a cold-open auto-enqueue of `AdbStartWorker` when the service is dead but wireless
debugging is still live. `37d086d4`, a day later, gated that on the auto-reconnect toggle and flipped
the toggle's default from **on to off**. A fresh install therefore no longer attempts an ADB
reconnection without being asked; an existing install keeps whatever is stored.

### The new privileged tier

Four interfaces add roughly seventy methods that let an authorised client act on *other* apps without
root: read their logcat by pid, dump their heap, walk `/proc/<pid>/fd` for open databases and
sockets, tar their private data directory through `run-as`, back up and restore app data via
`bu`/`bmgr`, stream APK splits and OBB data, freeze and unfreeze packages, grant and revoke runtime
permissions silently, install and uninstall silently, and insert rows into the SMS provider.
`IApkPatcher` goes further still — a pure-JVM Android binary-XML patcher and an ephemeral RSA-2048
V1+V2 signer that flips an installed app's `debuggable` flag, re-signs it and reinstalls it so its
data can be read.

Three notes on that, none of them changed here:

- `status_bar_governor_plus` and `package_governor_plus` are gated by feature flags that **default
  on**; `display_tuner_plus` defaults off as experimental.
- `AppInspector`, `PrivilegedDataSource`, `BackupRestorePlus` and `ApkPatcher` have **no toggle at
  all** — only `enforceCallingPermission`, so any app authorised for Shizuku has the full set.
- `ApkPatcher.cleanupAllTempDebug()` is never called from anywhere, despite its commit message
  saying it runs at startup, and its session map is in-memory only. An interrupted patch session
  leaves the target app installed under a throwaway key with no automatic route back.

### Also in this sync

- **Root-compat hardening** (`a6aa9edc`): `RootCompatHelper` fast-fails on Android 16+ ADB mode
  before attempting a `/data/local/tmp` write, avoiding a multi-second SELinux stall; an API 36
  warning banner links straight to export-path setup; Service Doctor gains check 8b for a missing
  exported path with a one-tap fix.
- **Wireless ADB UX**: live port and stale cached port are now separated, so a live port goes
  straight to `StarterActivity` instead of opening a dialog that auto-dismisses seconds later.
- **Split-APK install fixed** (`93bc9e82`): `createInstallSession` was passing `--multi-package`,
  the wrong flag for split sessions. Apps with config splits no longer fail.

### The fork layer, re-verified

The no-phone-home contract is whole. The entire twelve-commit delta was grepped for new outbound
paths and has none — the only URL-shaped hit is the Android XML namespace constant inside the binary
XML patcher. Sentry stays plugin-less and DSN-empty, `RemoteDbSyncWorker` stays neutered, VirusTotal
and Pithus still answer "disabled", `isAutoUpdateEnabled()` still defaults false, `support_email` is
still blank and `.github/` is still absent. `UpdateChecker` remains the only thing in the tree that
can open a connection, and it still points at this fork's releases.

Icons were checked despite zero conflicts, as the standing rule requires: no `_base` or `_alt`
variants, `<foreground>` still a bare `@mipmap/ic_launcher_foreground` with nothing composited over
it, `<monochrome>` still our traced mark. Upstream touched no icon asset this round. The app id,
namespace, single-ABI filter, signing config, both wire-protocol strings and both custom permissions
are unchanged, and `ComponentNameContractTest` passes.

`13.6.0.r2397+2026-09-02.14-54.g37d086d4+001` is the same rebase without the four tile fixes above;
`+002` is the build to install.

## 13.6.0.r2385+2026-08-31.23-18.gb892b43f+001

Rebased onto upstream `13.6.0.r2385` — seventeen commits in one evening, 817 insertions against 74
deletions in 23 files. Fifteen of those 23 carry a fork diff, and six of the eighty-seven replayed
commits needed a hand reconciliation. Two of the reconciliations only surfaced at compile time.

**Upstream came for the icon this time, and the answer was no.** `6d96148a` rewrites
`drawable/ic_monochrome.xml` — the themed-icon and notification-shade layer — from a bare "+" into a
stroked hexagon ring with the plus badge on its shoulder, so One UI themed icons read as the Shizuku+
hexagon. In this fork that file is 白い熊's traced mark drawn as a single-tint stroke, so upstream's
version is their identity replacing ours. **Ours is kept.** This is the icon trap CLAUDE.md records,
in its easy form: upstream redrew their own art, so it conflicted loudly instead of sliding through
on a rename. The `<foreground>` is still bare, no `_base` variants exist, and no layer-list was
reintroduced.

**Upstream's tip did not compile — twice, in different ways.** `28c52efb` fixes the `open class`
break this fork patched around last cycle, so that fork fix is now retired and upstream's file is
taken verbatim. But `954064d3` introduced a new one: `ShizukuTileService` now calls
`R.string.tile_state_update_failed` with no `import af.shizuku.manager.R`. That import is added here
as a fork fix, marked in the source so it can be dropped when upstream corrects it.

**The messiest part of this sync was invisible to git.** Upstream spent eight commits extracting
hardcoded English into `strings.xml` — and those lines are precisely the ones this fork had rewritten
from `Toast.makeText(…).show()` to `ShiroikumaToast.show(…)`. Eighteen conflicts, one shape. The
merge takes upstream's string resource every time and keeps the house wrapper, which is strictly
better than either side alone: the toasts are now both localizable and house-styled.

The same refactor moved this fork's de-branding out of Kotlin and into the resource values. Where our
commits had hardcoded "白い熊 雫" into a dialog message, upstream extracted that message to a string;
the resolution takes the resource and de-brands the **value**, so eight strings that were previously
untranslatable now are. Note the de-branding is surgical: "the original Shizuku server" in the
stock-conflict strings means *stock Shizuku* and deliberately keeps that name.

### Two clashes only the compiler caught

- **Two `onResume()` overrides.** Upstream's `b892b43f` added one to refresh the App Profiles summary;
  this fork already had one refreshing the Device Owner section. They sit 690 lines apart, so git
  merged both in without a murmur and Kotlin rejected the file. Upstream's body is folded into ours.
- **A nullable context.** Upstream's extracted line reads `context?.getString(…)`, which
  `Toast.makeText` accepts as a platform type but `ShiroikumaToast.show` does not — its message
  parameter is a non-null `CharSequence`. Switched to the fragment's own `getString`.

### Also taken from upstream

- **Trusted Networks finally has a UI** (`5a9e29b3`). The rule shipped last cycle read a settings key
  that nothing could write, so the feature was dormant for everyone. There is now an SSID dialog
  under the Binder Firewall toggle, with an "Add current network" button reading the SSID from
  `NetworkCapabilities` on API 29+ — no location permission.
- **Per-app firewall profiles** (`b892b43f`) — a new `AppProfilesActivity` listing every installed app
  with a tap-to-cycle chip (Default → Block → Allow). It also fixes `restoreDefaults()`, which was a
  no-op that left the firewall stuck in whatever the last matching profile set, and starts
  `AutomationService` from `BootCompleteReceiver` when rules exist.
- **The 2-second usage poll is gated off** (`8bf2dbe2`). `startForegroundAppMonitor()` polled
  `UsageStatsManager` every two seconds regardless; it now runs only when app profiles actually exist,
  so the network-only path costs nothing.
- **A diagnostic for a dead automation service** (`7e1740bb`) — rules configured but the service
  stopped is otherwise silent, and the firewall simply never toggles. Tapping the banner restarts it.
- **Swift Backup's Compat Hub entry was wrong** (`6897709d`, `b92af50e`). It uses libsu with a
  PATH-resolved `su` and has no custom su-path preference key in its bytecode at all, so the
  "Settings > Storage > Custom shell" hint pointed at a setting that does not exist. It is now marked
  Shizuku-native, and for such apps the Copy & Open / Magic Setup buttons are hidden rather than shown
  permanently greyed out.
- **Storage Bridge admits its ADB-mode limit** (`9f0b07be`) — `run-as` reaches only debuggable apps,
  so production app data stays inaccessible. Said plainly in the summary now.

### Reconciliation worth recording

The `dhizuku_not_owner` diagnostic branch conflicted because upstream added a new
`automation_service_stopped` branch immediately beside it. **Both are kept**: this fork's replacement
of that branch stands — upstream builds the setup command with the `pkg/.Receiver` shorthand, which
expands against the applicationId and names a class that does not exist, and getting that wrong costs
a factory reset — while upstream's new branch is taken and house-toasted like its neighbours.

## 13.6.0.r2368+2026-08-31.06-54.g571ece3f+001

Rebased onto upstream `13.6.0.r2368` — twenty-one commits landed in a single day, 1187 insertions
against 422 deletions in 37 files. Twenty-two of those 37 carry a fork diff, and seven of the
eighty-seven replayed commits needed a hand reconciliation rather than a clean replay.

**Upstream's tip does not compile, and this build carries the fix.** `4aa88f6e` introduced
`GrayableIconSwitchPreference` — a `SwitchPreferenceCompat` subclass that dims a switch's icon to
38% when the toggle is off — and made `PlusFeaturePreference` extend it, while leaving the new class
`final` (Kotlin's default). Nothing in the fork touches that file; the break is upstream's own, and
`open` is the minimal change that matches what their commit message says it intended. It is marked
as a fork fix in the source so it can be dropped the moment upstream corrects it.

**The change that matters most here runs inside the privileged server.** `0c65a9eb` fixes two things
that were silently corrupting shell-client behaviour. The `id`/`whoami` mocking fired for *every*
caller whenever SU Bridge was enabled, so terminal apps were handed `uid=0(root)` instead of the
real shell uid — it is now gated on root mocking, like `getenforce` already was. And the `service
call` exception handler was swallowing every `ServiceManager` lookup error and returning a fake
success `Parcel`, so those commands quietly produced garbage; it now blocks only when
`isBinderCallBlocked()` actually matches. Both sit directly under `rish` in Termux.

The same commit repairs the storage proxy: `createPipe()` sets `O_CLOEXEC`, so the child shell could
never reach the fd through `/proc/self/fd/<n>` and the fallback had never worked — a copier thread
replaces it and closes the write end on exit so the reader sees a clean EOF. The `Android/data`
fallback now starts at SDK 33 rather than 36 (the directory was restricted at Android 13, not 16),
and an ADB-mode server can read `/data/data` for debuggable apps via `run-as`.

### Also taken from upstream

- **Automation is real now** (`19ffdbe0`). Until this batch the rules were scaffolding — hardcoded
  demo SSIDs and package names with the logic commented out, which is why `f65fdab7` stopped the
  service auto-starting in the first place. Trusted Wi-Fi networks and per-app profiles are now read
  from settings and actually drive the binder firewall, with the SSID taken from
  `NetworkCapabilities.getTransportInfo()` on API 29+ so no location permission is needed. The
  service still stays silent until a rule exists.
- **Settings tell the truth about which mode you are in** (`6a93e993`). With the server on uid 2000,
  the four root-only categories are disabled and relabelled "(root mode only)" instead of appearing
  available, and Samsung System UID Escalation is disabled unless the server is uid 0.
- **Magic Setup works in ADB mode** (`8df18044`, `c1c0fc9e`, `c5c84d76`). It had been disabled for
  every ADB user even though four apps are configurable through `settings put global`, and the
  self-test blamed a stopped service for what is really SELinux denying uid 2000 writes to
  `/data/local/tmp` on Android 16+. `am force-stop` now runs before an app's `shared_prefs` are
  edited, so a running app cannot flush its in-memory copy over the change.
- **Our own launcher mark stops appearing as a settings glyph** (`1fba77da`). Upstream used
  `ic_monochrome` as the icon for the black-night-theme and icon-style rows; in this fork that
  drawable *is* the traced mark, so those two rows were showing the app's own icon. They now use a
  crescent moon and a settings glyph.
- **The authorized-app count no longer flashes zero on resume** (`02f548bd`, `c35f1c6c`).
  `getPackages()` swallows IPC exceptions and returns an empty list, which read as a genuine zero
  whenever a resume-triggered load outran the reconnect. Suppressed only when the previous count was
  non-zero, so a fresh install still shows a real 0.
- **`ShizukuLiveService` can no longer crash the process** (`53846f61`) when `POST_NOTIFICATIONS` is
  denied — its foreground promotion is wrapped and falls back to `stopSelf()`, matching what
  `WatchdogService` already did.
- **Fuller accent palettes** (`571ece3f`) — each accent now ships a complete M3 secondary/tertiary
  set instead of four primary roles, plus Amber, Rose and Slate. All of it still sits *under*
  `ThemeOverlay.Shiroikuma`, which is applied last, so the house black-and-yellow is unchanged.

### Reconciliations worth recording

- **The shell-consent flag stayed gone.** The replay walks through the commit that added
  `KEY_SHELL_CONSENT_GRANTED` and the commit that removed it, and both conflicted in the same two
  files. The end state is the correct one: no global flag, `KEY_RISH_TOKEN_AUTH` in its place.
- **Upstream's expanded app database was taken whole and re-de-branded.** `c1c0fc9e` corrected the
  Root Explorer package (`com.speedsoftware.explorer` was wrong; it is `…rootexplorer`), added
  RamExe, Prevent, ES File Explorer and OAndBackupX, and filled in missing navigation hints. That is
  strictly better data than the fork's older copy, so the merge took upstream's list and re-applied
  the 白い熊 雫 naming to it rather than keeping ours.
- **The automation notification strings are upstream's again.** The fork's versions existed only to
  strip "Shizuku+" from them; upstream's replacements carry no brand at all and describe the engine
  rather than just network watching.
- **Two files lost fork edits that upstream made redundant.** `RootCompatibilityActivity` and
  `ServiceDoctorActivity` had `Sentry.captureException` imports the fork kept for conflict-freedom;
  upstream deleted those call sites outright, so the imports went with them. The house toast wrapper
  was re-applied on top of upstream's rewritten logic in both.

## 13.6.0.r2347+2026-08-28.22-32.g39bdbdf1+002

Rebased onto upstream `13.6.0.r2347` — twenty-nine commits across three days, 827 insertions against
214 deletions in 46 files. The widest overlap with this fork's own patches since the fork started:
**38 of those 46 files carry a fork diff**, and nine of the eighty-five replayed commits needed a
hand reconciliation rather than a clean replay.

**The one that matters runs on 白い熊's phones.** `a6c6136b` fixes the reason the server could die
and stay dead: `ShizukuStateMachine.update()` classified an unexpected binder death as `STOPPED`
rather than `CRASHED`, and `WatchdogService` only restarts on `CRASHED` — so the watchdog's external
`AlarmManager` re-arm, added specifically to survive an OEM freezer killing the whole process, never
restarted anything. A freshly cold-started process had no in-memory record of the prior state either,
so even the "was it running" check was blind across a restart; the last settled state is now
persisted through `ShizukuSettings`.

That commit lands in the same function as this fork's own `b33280e2`, which split `update()` (the
passive refresh, which may keep a transition that is still plausibly in flight) from `settle()` (the
one that resolves a start or stop you just performed). Both survive, and the merged order is what
each needs: a **failed start** still settles to `STOPPED`, so the home card's start button is
re-enabled instead of latching on "Starting…", while a server that **was running and died** now
reports `CRASHED` and the watchdog acts on it.

### Also taken from upstream

- **Two server-side force-stops** (`b392c8f3`, `640342d9`). Authorizing an app that is already
  connected updated the server record and the OS permission but never told the live client, so it
  stayed stuck on "no access" until force-stopped by hand; the same gap applied to any other process
  of the same uid when a permission dialog was answered. Both now force-stop, which is the only
  mechanism that makes a live client observe the change. Expect an authorized app to visibly restart.
- **`AutomationService` no longer auto-starts** (`f65fdab7`). It ran a permanent "Network monitor"
  foreground notification, a two-second `UsageStatsManager` foreground-app poll and an always-on
  network callback — for two rules that are placeholders matching hardcoded demo SSIDs and package
  names, with their real logic commented out. Pure background cost, including a privacy-sensitive
  poll, for something that could not do anything. Its remaining notification string is de-branded
  here as well.
- **"Start on boot" stops lying** (`f65fdab7`, `d79a9935`). The toggle read `getComponentEnabledSetting()`,
  which answers "default" — not "enabled" — for anyone who never touched it, while the receiver's
  manifest declares it enabled and it really did auto-start on every boot. It is now accurate from
  first launch and can actually be turned off.
- **A real edge-to-edge / Blur UI fix** (`e627539d`). `AppActivity` read both preferences from
  `"${packageName}_preferences"` while `ShizukuSettings` writes them to a device-protected file named
  `settings`, so the reads always fell back to their defaults: edge-to-edge could never be turned
  off and Blur UI never turned on. The same commit makes `AppCompatDelegate.setApplicationLocales()`
  the locale source of truth, so the in-app language picker and Android 13+'s system "App language"
  screen finally agree.
- **Haptics no longer crash on HyperOS/MIUI** (`b687d860`) — those OEMs route
  `performHapticFeedback()` through the real vibrator service and enforce `VIBRATE`. The permission
  is declared and every call is guarded. `6245b91f` adds a dedicated Haptic Feedback toggle so
  feedback is no longer chained to the unrelated Expressive Animations setting.
- **"Cut (Angular)" shape style** (`4e3edfb5`), a genuine octagon silhouette rather than another
  corner radius, and **One UI Style rescoped to structure only** (`9097822f`) — all colour items
  stripped, so it composes with the colour source instead of fighting it.
- The **responsive two-column home grid** returns at ≥600 dp or in landscape (`e405d30c`), TalkBack
  can now trigger swipe-to-act on app rows (`a4d97930`), the pairing accessibility service stops
  showing as a bare duplicate entry (`38bc9675`), and roughly a hundred hardcoded English strings
  across Settings, Appearance and the diagnostics dashboard became real resources.

### What the rebase had to defend

- **The launcher icon, again — and this time upstream replaced the art itself.** `2f61a052` swapped
  all four densities of `ic_launcher_background.png`, `ic_launcher_foreground_base.png` and
  `ic_launcher.png` for RikkaApps' unmodified cat and hexagon, then `ae4d0d42` and `91ac8fd0`
  repositioned upstream's plus badge and rewrote `ic_monochrome.xml` twice. Fifteen files, every one
  of them ours. The black-yellow traced mark is kept byte-for-byte, the `_base` names stay renamed
  back to the plain form, upstream's `drawable/ic_launcher_foreground.xml` layer-list is deleted
  again, and `<foreground>` still resolves to a bare `@mipmap/ic_launcher_foreground` with nothing
  composited over it.
- **The house theme still goes on last.** Four upstream commits rewrote
  `ThemeDelegateImpl.onApplyUserThemeResource` — a reorder, its revert, the One UI colour strip and
  the new shape style. `ThemeOverlay.Shiroikuma` remains the final `applyStyle` call, so the app
  still comes up pure black and pure yellow on a fresh install, and `getThemeKey` still carries no
  live knob value that would recreate the Activity on every slider frame.
- **The no-phone-home layer is intact.** Re-audited in full: the Sentry plugin is still absent, the
  DSN still hardwired empty, `initializeSentryEarly()` still returns before `SentryAndroid.init()`,
  `RemoteDbSyncWorker` still cancels its own work, VirusTotal and Pithus still contact nobody,
  auto-update still defaults off, and `.github/` is still deleted — upstream touched `app.yml` three
  times this cycle and it stays gone. The only code in the app that can open a connection is
  `UpdateChecker`, pointed at our own releases.
- **`sendBinderToUserApp` still attempts every container** and still logs exactly one line after the
  loop, which is how a duplicate server is diagnosed. `BinderRequestReceiver.kt` remains
  byte-identical to upstream, and `SingleInstanceLock` is untouched.

### Reference remote

`thedjchi/Shizuku` published nothing — still the 2026-07-14 maintenance-pause tip. The two standing
outstanding items (package-name de-hardcoding, and gson → kotlinx.serialization in `:server`) remain
unported.

## 13.6.0.r2318+2026-08-26.09-45.ga3222cd2+001

Rebased onto upstream `13.6.0.r2318` — four commits, all pushed by thejaustin within seventy-three
minutes on 2026-08-26, hours after the sixteen that made up `r2314`. Three files touched in total,
41 insertions against 16 deletions, all of it inside `manager/`.

**One of the four does anything on 白い熊's phones.** The other three are Samsung One UI deep-link
corrections, and every call site is behind `EnvironmentUtils.isSamsung()` — inert on a Huawei Mate XT
and on a Motorola razr 40 ultra alike. They are taken anyway, because carrying upstream's fix costs
nothing and refusing it would create a permanent conflict in a file this fork does not otherwise
touch.

### The battery-optimization button finally does what its own label says

`c72a4ff3`, and it is the reason to install this build.

The Diagnostics dashboard's `battery_optimization` warning has always read **"Battery optimization is
active. 白い熊 雫 may be killed in the background. Tap to exempt."** Tapping it fired
`Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` — the plain alphabetical list of *every*
installed app, three hundred-odd entries deep, with no scroll position and no highlight. There was
nothing to tap; there was a list to search. The promise in the message and the behaviour of the row
had simply never matched.

It now calls `SettingsHelper.requestIgnoreBatteryOptimizations()`, which uses
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: the system's own Allow/Deny dialog, scoped to this app
and nothing else. One tap, done.

The helper was already there and already correct — `HomeActivity`, `BaseSettingsFragment` and
`ServiceDoctorActivity` all used it. This one row was the straggler that never got converted.

The change landed on a line adjacent to two of this fork's own edits in that file (the de-branded
warning text, and `Toast` replaced by the house `ShiroikumaToast` in the fallback path). Git merged
all three without a conflict, and both fork edits are intact.

### Samsung deep links, researched rather than guessed

Three commits, worth recording even though they are dead code here, because they are a good example
of the difference between a fix that compiles and a fix that was checked.

`1ac9011a` found that **Auto Blocker moved out of `com.android.settings` entirely** at some point
after One UI 6, into its own package `com.samsung.android.rampart`; `.ui.MainSettingActivity` lands
straight on the toggle with no permission wall. The two old in-settings paths are demoted to
fallbacks rather than deleted, for devices still routing through them.

The same commit found that while the categorized **App Power Management** hub genuinely is
`READ_SEARCH_INDEXABLES`-walled — a protected permission no third-party app can hold — the list
screen underneath it, `CheckableAppListActivity`, carries no such guard and opens directly.

`a3222cd2` then found Samsung's **own Deeplink API documentation** and learned that this single
activity backs three different lists, selected by an `activity_type` int extra that is invisible in a
manifest dump: `0` sleeping, `1` deep sleeping, `2` never sleeping. The button now passes `2` and
lands on **Never sleeping apps** — the actual exemption list its guidance describes — rather than on
the restriction list it had been reaching by default. No per-package extra is documented, so it opens
the list rather than scrolling to the app; upstream says so plainly instead of implying otherwise.

`41c5f449` and the tail of `a3222cd2` rewrote `doctor_tip_samsung_sleeping` twice in the same hour,
first hedging the wording to match the wrong destination and then restoring confident wording once
the right one was verified. **Both conflicted here**, since this fork de-brands that string. The
resolution takes upstream's final wording with `Shizuku+` replaced by 白い熊 雫: *"Tapping Fix opens
the 'Never sleeping apps' list directly. Tap the + button and add 白い熊 雫 to prevent Samsung from
freezing the background service."* The neighbouring three Samsung and phantom-process tips keep their
de-branded text unchanged.

### Nothing touched the privileged path

No commit in this range goes near `server/`, `starter/`, `shell/`, `compat/`, the `api` submodule or
any build file. `starter.cpp`, `ServiceStarter`, `SingleInstanceLock`, the binder handoff and all
three watchdog layers are untouched, so the one-server invariant and prompt-free `rish` are
unaffected. `BinderRequestReceiver.kt` remains **byte-identical to upstream**.

### The icon trap did not fire this time

Upstream did not touch a single icon asset in this range — verified by diffing the whole
`mipmap*`/`ic_launcher*`/`ic_monochrome` surface across the range rather than by trusting the absence
of a conflict, which is exactly the check that `r2267` and `r2314` both taught. All fifteen PNGs stay
ours, no `_base` variant exists, no `drawable/ic_launcher_foreground.xml` layer-list exists, and both
adaptive-icon XMLs still resolve `<foreground>` to a bare `@mipmap/ic_launcher_foreground` with
nothing composited over it.

### The no-phone-home layer, re-verified in full

Nothing regressed. `UpdateChecker` is still the only `openConnection` call site in the entire app and
still points at `ShiroiKuma0/shiroikuma-shizuku`; the Sentry Gradle plugin is still absent with a
hardwired empty DSN, an empty manifest DSN and auto-init false; `RemoteDbSyncWorker` is still never
scheduled; `isAutoUpdateEnabled()` still defaults **false**; `VirusTotalClient` and `PithusClient`
still contact nobody; `support_email` is still empty; `.github/` is still absent; and both
wire-protocol strings plus the two custom permission names are untouched.

`Helps.kt` keeps the split decided on 2026-08-26 — `ADB`, `ADB_ANDROID11`, `APPS` and
`ADB_PERMISSION` point at upstream's wiki because that is where the writing is, while `HOME`,
`DOWNLOAD`, `RISH` and the `getHelpUrl` fallback stay ours.

### The djchi reference remote

Still **zero new commits**. `thedjchi/Shizuku` has not moved since its maintenance-pause README on
2026-07-14, and the standing ledger items remain deliberately deferred.

## 13.6.0.r2314+2026-08-26.08-02.g8faa3e23+002

Rebased onto upstream `13.6.0.r2314` — sixteen commits, all pushed by thejaustin on 2026-08-26 in
one working session. The development pause announced at `r2298` evidently did not last.

One of the sixteen matters on-device; five are launcher-icon work this fork discards; the rest are
smaller fixes, docs, and CI that does not exist in this tree.

### A third watchdog layer that survives the process being killed

`c6a76f3f` adds **`WatchdogAlarmReceiver`**, and it is the reason to install this build.

The watchdog could previously only notice a *service* dying. When an OEM battery manager freezes or
kills the **whole manager process** — Samsung One UI's "Sleeping apps" is the reported case, EMUI
behaves the same way — nothing inside that process survives to notice, so neither `WatchdogService`'s
state-flow listener nor the two-hour `WatchdogWorker` backstop can ever fire. A dead process cannot
detect its own death.

The new layer is an `AlarmManager.setExactAndAllowWhileIdle` re-arm that reschedules itself every 15
minutes. It survives because **`system_server` dispatches it**, not the app. It is wired into
`setWatchdog()`, `BootCompleteReceiver` (exact alarms do not survive a reboot) and
`ShizukuApplication.onCreate()`, and it declares `SCHEDULE_EXACT_ALARM`, falling back to an inexact
wake when that permission is declined or restricted.

Upstream is explicit, and it is worth repeating: this is a **mitigation bounded by the 15-minute
interval**, not a cure. An aggressive freezer can still take the service down; it just cannot keep it
down indefinitely.

Nothing about `starter.cpp`, the fork, `SingleInstanceLock` or the binder handoff changes, so the
one-server invariant is untouched by this.

### Upstream rebuilt the icon trap; we discard it again

Five commits (`0baba52c`, `bff27741`, `b41156c6`, and the two doc commits recording them) rework
upstream's own launcher icon: the "plus" badge was extracted back out of their flattened art and
re-added as a translucent overlay in a **`drawable/ic_launcher_foreground.xml` layer-list** over
`ic_launcher_foreground_base.png`, after a real-device screenshot showed it clipped by the
adaptive-icon safe-zone mask.

This is the same composition recorded at `r2267` as the trap that produces **no conflict** — git
follows the rename, our art lands at the `_base` name, and upstream's plus is drawn over it while the
build succeeds. It was caught: the layer-list and all four `_base` PNGs are deleted, our art stays at
the plain name, and both adaptive-icon XMLs still resolve `<foreground>` to a bare
`@mipmap/ic_launcher_foreground` with nothing composited over it. All fifteen icon PNGs were verified
byte-identical to the pre-rebase tree and none matching upstream's.

`ic_monochrome.xml` was trimmed upstream to just their plus; ours stays the traced mark drawn as a
single-tint stroke, which is the better themed icon of the two.

### The help links now go where the content actually is

A change of position, decided by 白い熊 on 2026-08-26.

Upstream discovered (`e03f1f80`, `6e84d574`) that four in-app "Learn more" buttons pointed at wiki
pages that never existed — `wiki/Setup` and `wiki/Supported-apps` — so every one of them 404'd, and
repointed them at real pages and anchors. This fork had de-branded those same URLs to our own repo,
where the wiki has **no pages at all**, so ours were dead in exactly the same way.

So the split is now explicit: **`ADB`, `ADB_ANDROID11`, `APPS` and `ADB_PERMISSION` point at
upstream's wiki**, because that is where the writing is, while **`HOME`, `DOWNLOAD`, `RISH` and the
`getHelpUrl` fallback stay ours** — offering upstream's releases would be actively wrong, since they
are signed with a different key and could never install over this build. The `getHelpUrl` fallback
was the last 404 left in the file and now points at our README. A block comment in `Helps.kt` records
the reasoning so a future sync does not de-brand it back into dead buttons.

### Smaller upstream fixes taken

- **The home screen no longer flashes "0 apps authorized"** on a cold start (`2089a6c4`).
  `HomeState.grantedAppCount` was an `Int` defaulting to `0` and rendered before the async count
  loaded; it is now `Int?` end to end, showing a neutral loading title until the real number lands.
- **The SU bridge redeploys on every service start**, not only on app self-update (`e980c0b3`). If
  the privileged server was not yet running at the moment of a self-update, the redeploy silently
  no-op'd with nothing to retry it — common precisely on the devices that delay background processes.
- **Compat Hub install failures name their cause.** Insufficient storage and unsupported ABI now get
  their own messages instead of one generic failure; both were merged onto this fork's house toast
  rather than upstream's plain one, so the styling is unchanged.
- **Update-download failures name their cause too** — out of space, interrupted and unresumable, or
  a network error, instead of one generic string.
- **The Dev/Beta update channel can no longer stick on a stale prerelease** (`c13bf851`). It always
  preferred the newest prerelease-flagged entry even when a newer stable had since been cut; it now
  compares actual version codes and takes whichever is genuinely newer. It reads the fork's own
  `parseVersionCode`, whose `rNNNN * 1000 + N` contract it happens to respect exactly.
- **A Themed Icons toggle** appears in onboarding, on by default, with a best-effort deep link — no
  `Settings.ACTION_*` exists for a launcher's themed-icon setting. Its new strings are de-branded.
- **Samsung Service Doctor deep links** were fixed for One UI 8 (`8faa3e23`). Not relevant to either
  of 白い熊's phones, but it is the path that made upstream's own "exempt this app from Sleeping
  apps" guidance functional, which is what the new watchdog layer leans on.

### `.github/` stays absent, and had to be removed again

This fork deleted upstream's whole `.github/` directory — `app.yml` injected a Sentry DSN, uploaded
native debug symbols, and triggered on pushes to `master`, which we push every sync. Upstream added a
**brand-new** `pull_request_template.md` in this range, and since our de-branding commit only deleted
the files that existed at the time, it arrived unopposed. It is removed.

### The no-phone-home layer, re-verified in full

Nothing regressed. `UpdateChecker` remains the only `openConnection` call site in the entire app;
Sentry stays plugin-free with a hardwired empty DSN and an early return before `SentryAndroid.init()`;
`RemoteDbSyncWorker.schedule()` still cancels rather than schedules; `isAutoUpdateEnabled()` still
defaults **false**; `support_email` is still empty; and both wire-protocol strings are untouched.
`BinderRequestReceiver.kt` remains **byte-identical to upstream**, so `rish` is unaffected.

The `:manager` unit suite passes, so `ComponentNameContractTest` still guarantees the Compat Hub's
string-literal component references resolve and that `applicationId` and `namespace` stay distinct.

### The djchi reference remote

Unchanged at **zero new commits** — `thedjchi/Shizuku` has been dormant since its own maintenance
notice on 2026-07-14. The two standing ledger items (reading the manager package name from the APK
path instead of hardcoding it, and moving `:server` off gson) remain deliberately deferred.

## 13.6.0.r2298+2026-08-23.03-16.g7a3e4098+002

Rebased onto upstream `13.6.0.r2298`. **This release changes nothing about how the app behaves** —
the entire upstream delta is six commits to `README.md`, and no fork code was touched either. It
exists so the installed build's pin says `r2298`, which is where upstream actually is.

Say plainly what that means: if `13.6.0.r2292+…+002` is running and working, this build is
byte-for-byte equivalent in behaviour and there is no reason to hurry. Install it to keep the
version string honest, not to fix anything.

### The whole upstream delta is a README banner

`8e973871`, `7cf983b1`, `56a6f28d`, `73664e90`, `0e28255f` and `7a3e4098` were pushed within 106
seconds of each other on 2026-08-22 — one paragraph, edited six times in place. `git diff --stat`
across the range is a single line: `README.md | 8 ++++++++`.

What they land is a **`[ DEVELOPMENT PAUSED ]`** notice at the top of upstream's README. thejaustin
has moved to a new phone, is rebuilding his development environment, asks for issues to keep coming
in, and is looking for contributors to help stabilize the project.

No source, resource, manifest, Gradle or submodule file appears anywhere in the range. The
privileged server, the starter, the binder handoff and the whole `rish` shell layer are untouched by
definition rather than by inspection, and `BinderRequestReceiver.kt` remains byte-identical to
upstream.

### That banner was the rebase's only conflict

Our `README.md` on `custom` is our own fork README, so upstream's new opening paragraph collided
with it on the first fork commit. Resolved in our favour and one-sidedly: upstream's banner and
their `# Shizuku+` heading are dropped, our icon line and `# 白い熊 雫` heading kept. It is
upstream-identity text about upstream's author — not something this fork should be repeating to
白い熊 as though it were our own status.

The `api/` submodule pin also stays at our own `83cc09e`; upstream's `master` pins `b77af0e7`, which
we do not take.

### Both trees this fork watches are now paused

Upstream has just said so in as many words, and `thedjchi/Shizuku` — upstream's own base, which we
track as a read-only reference remote — has been dormant since its own maintenance-pause notice on
2026-07-14. The djchi delta is unchanged at zero new commits, and the two items standing on that
ledger (reading the manager package name from the APK path instead of hardcoding it, and moving
`:server` off gson) remain deliberately deferred rather than resolved.

Practically: expect the upstream side of these releases to be quiet for a while. Fork work does not
depend on upstream moving.

## 13.6.0.r2292+2026-08-20.07-19.g794da9d8+002

Rebased onto upstream `13.6.0.r2292` — eight upstream commits, all bug fixes plus one new
translation. Nothing touches the privileged server, the starter or the binder handoff, so the shell
layer reworked in the last two releases is untouched and `BinderRequestReceiver.kt` is still
byte-identical to upstream.

**Worth installing for one reason:** a crash this fork was carrying on its own is fixed.

### A notification icon could take the app down on launch

Upstream's `64f552d8` chased down issue #422 — an app that crashed at launch on OneUI 3.1 with
`RemoteServiceException: "Couldn't create icon StatusBarIcon"`. The cause is that notification icons
are rendered by the system in its **own** theme context, where an app-defined
`android:tint="?attr/colorControlNormal"` may not resolve at all. A vector carrying that tint is
fine everywhere in the UI and fatal the moment it is handed to `setSmallIcon()` or `addAction()`.
Upstream added untinted `ic_notification_*` twins and swapped its own call sites.

Two tinted call sites survived that fix, and this release closes both.

The first is **ours**. `StateExportService` — the foreground service behind 保存復元 automated
exports — posted its progress notification with `ic_server_24`, which carries the tint. That is the
same crash, in fork-only code upstream never saw, waiting on whichever device resolves the attribute
least forgivingly. It now uses a new `ic_notification_server_24`, an untinted twin of the same
glyph rather than a different icon, so the notification looks exactly as it did.

The second is **upstream's own miss**. `WatchdogService`'s "Turn Off Alerts" action still passed
`ic_close_24`; upstream created `ic_notification_close_24` for precisely this and swapped it only in
`ShizukuReceiverStarter`. Fixed here.

Upstream also shipped a JUnit regression test for this class of bug, which runs in our suite now.
It matches `.setSmallIcon(`/`.addAction(` followed directly by the drawable reference — so a comment
placed *between* the call and the reference does not fail the check, it silently removes that call
site from it. The comment explaining the `WatchdogService` swap therefore sits above the call, and
says why. The tree currently reports 22 notification icon call sites and no violations.

### Brazilian Portuguese, de-branded by hand

Upstream took a community translation (#409) that replaces `values-pt-rBR/strings.xml` wholesale —
305 lines to 1077. Rather than search-and-replace the brand across it, each string was checked
against the same key in the de-branded English file: 126 keys become 白い熊 雫, and 12 deliberately
keep saying "Shizuku" because they mean *stock* Shizuku, the original Shizuku API, or a package id.

Three things upstream's file got wrong on its way in were fixed too: the translation-credit link
pointed at upstream's repository, the migration dialog named upstream's application id instead of
ours, and an em dash had been written as a bare literal `2014`, which would have rendered as those
four digits mid-sentence in two strings. The translator is credited by name rather than upstream's
author.

### Upstream fixes that come along

- **Dev/beta update channel** picked the newest release by date rather than by its prerelease flag,
  so it silently converged on whatever stable release came last. Irrelevant here — this fork
  publishes no prereleases and the automatic poll is off by default — but the code is now correct.
- **SU Bridge** redeploys its version-specific dex on app update instead of waiting for someone to
  reopen the settings screen, and its self-test now reports the actual exit code and stderr rather
  than a generic failure. Both are no-ops unless the SU Bridge is enabled.
- **Swift Backup's SU-path entry was removed** after upstream decompiled the app and confirmed it
  never reads a custom `su` path at all.

## 13.6.0.r2284+2026-08-17.18-29.g32382e89+002

Rebased onto upstream `13.6.0.r2284`. Two upstream commits, plus one in the API submodule — and
between them they retire the mechanism the last three fork releases were built around.

**This one is worth installing.** `rish` should now prompt **once**, on the first privileged command
after a fresh install, and never again. Every release before this asked on some schedule or other;
the newest of them, `…r2282+002`, asked once per command unless the setup card had been used.

### Upstream stopped guarding the binder, and it is right to

`REQUEST_BINDER` is a plain broadcast, so `Binder.getCallingUid()` says nothing about who sent it.
Upstream's `c7c9f6c8` had closed a real hole there — a fast path that trusted the intent's own
`callingPackage` / `callingUid`, which let any installed app name an already-authorized package and
be handed the live privileged binder with no interaction — and replaced it with a consent
notification on **every** request. That was safe and unusable: "Allow always" stopped meaning
always, and each shell command asked again.

`32382e89` removes the guard instead of tightening it. Receiving a bare binder reference is not the
privilege boundary: every privileged AIDL method is separately gated by `enforceCallingPermission()`,
which reads the uid the kernel attaches to the real transaction a caller makes once it has attached
— not anything a broadcast claimed. So the binder now goes out unconditionally, and the single
consent prompt happens downstream, in `attachApplication()` → `RequestPermissionActivity`, which was
already keyed on that verified uid and already skipped once the uid is granted.

The notification machinery is gone with it: `ShellConsentActivity`, `ShellConsentActionReceiver` and
`PendingConsentStore` are deleted, along with their manifest entries and eleven strings.

### What that leaves of this fork's own shell layer

This fork answered the per-command prompt its own way, in `VerifiedBinderRequestReceiver`: hand the
caller a fresh binder and a single-use nonce, make it transact back, and read its uid from the
kernel on that inbound call. That still works and is still in the build — but it no longer decides
whether the binder is handed over, because upstream would hand it over regardless.

What the tiers are still worth is stated plainly in the code now: the auth-token delivery is the
only observable evidence that `rish` is set up prompt-free, which is what turns the home card green
(a terminal's own directory cannot be read without root); the challenge is what lets a delivery be
logged against an identity the caller could not have invented; and the token path skips the round
trip entirely.

One piece was removed as inert. The fallback used to substitute the kernel-verified uid into the
extras it re-broadcast, so the consent prompt could not be made to read "Termux is requesting shell
access" by an app that was not Termux. There is no prompt on that path any more, and upstream's
receiver reads only `auth` and the callback binder, so the substitution protected nothing. It is
gone, `fallBackToConsentFlow` is now `handOffToPublicAction`, and the `auth` extra is still
forwarded — without it a client holding a real token would lose upstream's own fast path.

`BinderRequestReceiver.kt` needed no reconciliation at all. Upstream rewrote it end to end and it
still applied byte-for-byte, which is the entire reason this fork keeps its shell entry point in a
separate receiver on a separate action rather than patching that file.

### A permission check that had been quietly answering "no"

`checkCallerPermission`'s fallbacks — the OS permission check, and the config-flag bridge that
recognizes a grant for an app with no declared Shizuku permission, which is exactly Termux's case —
all sat inside `if (clientRecord == null)`. Once a client has successfully attached, that record is
never null again, so for every already-attached non-manager caller the function skipped its own
checks and fell through to `return false`.

Not a live bypass: `enforceCallingPermission()` independently checks `clientRecord.allowed` one
layer up, which is what actually kept authorized callers working. But the function was reporting the
opposite of the truth about clients it was asked about, and its real behaviour depended entirely on
a duplicate check elsewhere. It now reads `clientRecord.allowed` directly when a record exists.

Upstream also fixed `showPermissionConfirmation` / `RequestPermissionActivity` bailing in silence
when PackageManager cannot resolve the caller — which left the caller's `requestPermission()` call
hanging forever with nothing dispatched. It now falls back to a package-name/uid label.

### `rish` will tell you what went wrong now

In the API submodule, `Rish.startShell` printed only `e.getMessage()`. For an exception carrying no
message — a `RemoteException`-wrapped `SecurityException` loses its text in transit — that is a
blank line, indistinguishable from the command having run and done nothing. `RishTerminal.waitFor()`
was worse: a failed exit-code round trip went to `Log.w` only.

Both now print the exception class and message to **stderr**. That matters more here than upstream
had reason to know: EMUI's logcat buffer on the Mate XT keeps only `E/` and `F/` records, so a
`Log.w` diagnostic on this hardware is not merely inconvenient, it is invisible.

### The launcher icon, once again untouched by a commit that aimed at it

`0d6f357a` moved upstream's Material-You "Plus" badge onto the hexagon's top-right lobe, so circular
icon masks stop clipping it. That badge is drawn by the layer-list at `@drawable/ic_launcher_foreground`
which this fork deletes, so the change arrived as a modify/delete conflict and the deletion was kept.
The traced black-yellow mark stays bare at `@mipmap/ic_launcher_foreground`, with nothing composited
over it — the third sync in a row where upstream's icon work is correctly declined.

## 13.6.0.r2282+2026-08-16.18-54.ge1eef5d4+002

Rebased onto upstream `13.6.0.r2282`. Three upstream commits, all hardening — and one of them had to
be undone here, while a fourth, in the API submodule, had to be refused outright because taking it
would have reintroduced a bug this fork measured and fixed weeks ago.

Nothing user-visible changes. Take it for the two robustness fixes below; there is no new behaviour
to learn.

### Two upstream hardening fixes, taken

`CrashReporter.shareAsFile()` wrote the report with an unguarded `writeText`, so a full disk or a
storage-mount race would crash the crash reporter itself — at the one moment the app is trying to
tell you something went wrong. It is now wrapped, and the failed-`mkdirs` path returns instead of
falling through.

In the API library, `transactRemote()` read the target binder out of the transact payload and called
`getInterfaceDescriptor()` on it without checking. A caller writing a null strong binder got an
opaque `NullPointerException` from inside the privileged server; it now fails with a named
`IllegalArgumentException`.

### The launcher icon, fixed by upstream in a way that breaks it here

Upstream's adaptive icon composites a Material-You "Plus" badge over its foreground art, drawn by a
layer-list at `@drawable/ic_launcher_foreground`. This fork deletes that layer-list — the badge is
upstream's branding, not ours — and keeps the traced black-yellow mark at the plain
`@mipmap/ic_launcher_foreground` name.

Upstream had a stale reference to that mipmap in a second, legacy copy of the adaptive-icon XML, and
their build had been failing on it for three CI runs. Their fix repointed it at the layer-list. That
file carries no fork diff, so the rebase took their line **without a conflict** — leaving this tree
pointing at a drawable it deliberately does not have. It is pointed back at the mipmap here.

The failure mode is worth naming, because it is the same shape as the trap recorded at `r2267` and
the opposite of how icon problems usually present: nothing conflicts, nothing warns, and the mistake
is caught only because resource linking refuses to resolve the dangling name. An icon change that
*does* conflict is the easy case.

### Upstream's own version of the client-attach fix, refused

The API submodule turned out to be two upstream commits behind rather than one, and the extra commit
is upstream independently arriving at this fork's `enforceInterface` fix for reading the interface
token in `onTransact`. Both rewrite the same block, so they collided.

Ours is kept, because upstream's raw-code switch still carries the cases this fork removed. Binder
wire codes are `FIRST_CALL_TRANSACTION + id`, so a raw case may only name a code no current AIDL
method answers to. Upstream keeps raw `3`, `4` and `8`, which shadow the live `getVersion`, `getUid`
and `newProcess` respectively — and raw `8` is exactly the collision that made `Shizuku.newProcess()`
return null for every caller, taking the whole privileged-shell tier down with it. Upstream also
routes raw `14` to `requestPermission(int)`, but `requestPermission` answers to wire `15`; raw `14`
is what the client hand-writes for the v11 `attachApplication` payload, which this fork handles.

So the API submodule now carries upstream's null-binder guard on top of this fork's `onTransact`,
and nothing else changed in it.

### Not shipped, but present

Upstream generalized its pre-push guard to check every XML under `manager/` for dangling
`@drawable`, `@mipmap`, `@color` and `@style` references, and wired it into CI as a fast-fail job.
The CI half is inert here — this fork has no `.github/`, deliberately — but the script itself is in
the tree and is precisely the check that would have caught the icon reference above, in either
direction.

## 13.6.0.r2279+2026-08-16.13-12.g690b3632+004

**The app itself is unchanged from `+002`.** This build fixes the release machinery that decides
whether an APK's own What's New dialog is real prose or a placeholder — worth a release only because
the previous one shipped despite that check being broken.

### The changelog check could not fail, so it never did

The dialog reads `assets/changelog.md`, which is baked at build time: if the changelog has a written
section for the exact version being built, that prose ships; otherwise a summary generated from git
commit subjects does, headed _"Not published yet"_. Which branch was taken got reported in one log
line, and publishing is supposed to stop on the bad one.

That line lived inside the task that writes the asset — a task with real inputs and a real output,
which Gradle therefore skips as `UP-TO-DATE` on any rebuild that changed neither the changelog nor
the version. The line then never printed at all. Since the rule was *"do not publish if you see the
bad line"*, silence read as a pass, and the check quietly approved everything. `+001` shipped the
generated commit-subject summary with nothing anywhere saying so.

The report is now its own task, finalized by the generator and never up to date, so it prints on
every build. It also reads the asset **that ships** rather than the generator's own control flow, so
it describes what is genuinely in the APK even when an earlier invocation wrote the file.

It distinguishes a third case the old two-way line could not express: an asset whose newest heading
belongs to an *older* release, meaning this build has neither written prose nor a generated section.
That previously reported as "history only" — the publishable answer — while the dialog would open on
the previous release's notes.

### The check's first act was to fail on this very entry

`+003` was built and rejected by the new check, correctly in form and wrongly in substance: the
placeholder marker was spelled once in the task that writes it and again in the task that looks for
it, and the reader matched it as a loose substring anywhere in the file. This entry explains the
mechanism, so it *quotes* the marker — and the check read its own written prose as machine-generated.

A marker meaning "this section was machine-written" is going to appear in prose about itself, so it
now has exactly one definition, shared by writer and reader, and it is matched as a whole line inside
the newest section only. Every older section below is free to discuss it. `+003` is not published;
the fix ships here.

## 13.6.0.r2279+2026-08-16.13-12.g690b3632+002

Shell access rebuilt from the ground up, and four upstream fixes — two of them security. This is the
first published build on the `r2277`/`r2279` line; everything below arrived since `r2275+002`.

**If you are running an earlier release, this one is worth taking.** Every build published before it
carries the spoofable authorization fast path described below.

### The prompt-free `rish` path no longer rests on a blanket grant

The fork used to silence the per-command consent prompt with a single stored flag,
`KEY_SHELL_CONSENT_GRANTED`. Upstream then closed a hole in `REQUEST_BINDER` — the public,
unauthenticated part of the client API — where the receiver trusted the intent's own
`callingPackage` / `callingUid` extras. Any installed app could name an already-authorized package,
supply its own callback binder, and be handed the live full-privilege binder without a single tap.

Taking that fix exposed the fork's flag as **wider than the hole upstream had just closed**: once
set, any app that could broadcast got the binder, with no identification at all — not even a spoofed
package name was needed. The flag, its accessors, its ADB Tools switch and its strings are all gone.
A control that no longer gates anything is worse than a missing feature.

What replaces it is a three-tier receiver of our own, `VerifiedBinderRequestReceiver`, tried cheapest
first:

1. **The auth token** — upstream's own mechanism, compared constant-time, no round trip. The setup
   card bakes it into the `rish` script, so a normal setup never gets past this tier.
2. **An identity challenge**, then the stored per-uid grant.
3. **Upstream's consent flow**, reached by re-broadcasting the public action so the notification path
   is reused rather than copied.

The challenge is the interesting half. `Binder.getCallingUid()` is meaningless inside `onReceive()`
for a plain broadcast, which is exactly why the old fast path was forgeable. But `rish`'s callback
binder is a real `Binder` living in `rish`'s own process — so instead of *asking* the caller who it
is, the manager hands it a fresh binder plus a single-use nonce and requires it to call back. On that
**inbound** transaction the uid is supplied by the kernel, not by the sender.

A malicious app can certainly answer the challenge. The uid the kernel then reports is its own, so it
can only ever satisfy the check with a grant it already holds — it cannot borrow Termux's, which is
precisely what the removed fast path allowed. Every failure falls through to tier 3, so the worst
case is upstream's behaviour and never anything weaker.

`BinderRequestReceiver.kt` is now byte-identical to upstream again, carrying no fork diff at all. The
file upstream actively develops is the one file here that can no longer conflict.

### Setting `rish` up is one pasted command

A fixed home card, directly under the server status. Its button copies a single command; pasting that
into the terminal is the entire setup. The card goes green **only on evidence** — a token-authenticated
request actually arriving — and the token it recorded is fingerprinted, so regenerating the auth token
turns the card red again rather than leaving a stale pass over a script that can no longer
authenticate. Guessing would be worse than asking: the terminal's own directory is unreadable without
root, so checking for the file is not an option.

The generated command exists to avoid three failures that are all invisible when you hit them:

- Pointing `-Djava.class.path` at the installed APK kills the process **silently, with exit 0** — the
  loader class ships only in `assets/rish_shizuku.dex`, never in the APK's `classes.dex`. The script
  extracts the asset instead.
- A hand-copied dex goes stale on the next app update, and the only symptom is "it still prompts".
  The script re-extracts whenever the APK path changes — that path carries a random segment that
  changes on every install, which makes it a reliable staleness stamp.
- Writing to `$PREFIX/bin/rish` loses to any older `rish` earlier on `PATH`, while reporting success.
  So the command targets `command -v rish` and **prints the path it wrote**.

### From upstream: two security fixes

**The spoofable fast path** (`c7c9f6c8`) described above — taken in full.

**A world-writable grant table** (`690b3632`). The per-uid authorization file was written with
`setReadable(true, false)` / `setWritable(true, false)`, i.e. readable and writable by everything on
the device, and it is loaded with no integrity check. Any process that reached the path could forge
grant entries directly and walk past the consent flow — and past both permission fixes above. It now
gets mode `0660` with the group forced to shell on every write: root bypasses DAC checks anyway, shell
is covered by the group bit, and no third party has access at all. Inherited from RikkaApps rather
than introduced by this fork or upstream.

### Also from upstream

**The Compat Hub install button explains itself** (`cf77523e`). Installing the bundled compat shim over
`moe.shizuku.privileged.api` always fails when something else already holds that package under a
different signing certificate — genuine stock Shizuku, most often. That surfaced as the same generic
"failed to install" as any other error. The signing certificates are now compared before the attempt,
and the message names what is actually blocking it. This one is more reachable here than upstream:
this fork is built to sit beside stock Shizuku.

**The Service Doctor stopped faking a result** (`a519414c`). Its Samsung "Sleeping Apps" check
reported a verdict it had never actually determined. Compat Hub install failures are also logged now
instead of vanishing.

## 13.6.0.r2275+2026-08-16.05-56.g56dc0d74+002

One fix, in the `api` submodule, closing the mismatch the previous build recorded but did not patch.

### The client half of upstream's security fix

`+001` took upstream's two privilege-escalation fixes in the privileged server, which added
`enforceCallingPermission()` in front of four binder transactions. What upstream did not do in the
same batch was update the *client* side to expect the new refusal: the server answers an
unauthorized caller with `SecurityException`, and `SecurityException` is a `RuntimeException`, not a
`RemoteException`. The two call sites in `Shizuku.java` catch only the latter, so the exception
escaped both methods uncaught — an app that used to get a polite `false` now took a crash.

That is a contract break rather than a reasonable refusal, because of *which* two transactions
these are. `isCustomApiEnabled()` and `Dhizuku.getBinder()` are capability probes: a client is meant
to call them **before** it has been authorized, precisely to find out whether the enhanced API is
usable at all. The first is documented to return `false` when it is not; the second is `@Nullable`
and already answers `null` for every other unavailable case. "Not permitted" is the *no* answer, not
a crash. Both now return those values.

`SecurityException` is caught narrowly rather than by widening to `RuntimeException`. A probe that
swallows everything would also hide a `BadParcelableException` or an `IllegalStateException` —
genuine defects worth seeing — and the whole point of the change is to answer one specific,
expected refusal correctly.

### Why nothing else was touched

Every raw-transact and `catch (RemoteException)` site in `Shizuku.java` was checked before deciding
the scope. The rest are AIDL proxy calls into methods that were **already** permission-enforced
before this upstream batch, so their behaviour did not change — and for a privileged *operation*, as
opposed to a probe, throwing at an unauthorized caller is the correct answer and worth keeping. Only
the two probes were affected.

The manager-side callers of the other two newly-enforced transactions were verified rather than
assumed: `HomeViewModel.queryServerPatchVersion()` already catches `Throwable`, and
`AuthorizationManager.getApplications()` runs in the manager itself, which passes `isManagerAppId`
unconditionally and can never be refused. Neither needed a change.

This is a change to the `api` submodule, so it is two commits in two repositories — the fix in
`ShiroiKuma0/ShizukuPlus-API` on `custom`, and the moved pin here.

## 13.6.0.r2275+2026-08-16.05-56.g56dc0d74+001

A six-commit upstream sync, all of it fixes — no new features, no new screens, and nothing touching
how the privileged server is started or kept alive. No fork feature work. Five of the six were driven
by upstream's own Sentry telemetry, which is inert in this build; the bugs behind them are not.

### What arrived

**Two privilege-escalation holes closed in the privileged server.** The larger one: `newProcess()`
executed caller-supplied commands for real — SU-bridge mock injection, `build.prop` redirection
`mkdir`/`cp`, `iptables`/`ip6tables`, `pm list` interception — *before* any permission check, because
`enforceCallingPermission("newProcess")` only ran later inside `newProcessInternal()`. It gated the
return value, not the side effects, so any process merely holding a live `IShizukuService` binder
could trigger real root command execution. The smaller one: four `onTransact()` branches validated
only the AIDL descriptor token via `data.enforceInterface(…)`, which says nothing about who is
calling. `getApplications` leaked the full installed-package list with its permission flags, and
`getDhizukuBinder` handed back the live `DevicePolicyManager` system binder to anyone who asked. That
second one matters more here than upstream: this app holds Device Owner on the razr, so the binder it
was giving away is the one carrying those powers.

**An unbounded allocation on the ADB path.** `FakeAdbClientHandler.readMessage()` sized a
`ByteArray` straight from an unvalidated wire header, before the CRC and magic checks — a corrupt or
hostile peer could claim any length and take the process out with it, and an 825 MB single allocation
was observed in the wild. Now capped at 16 MB with the connection dropped cleanly.

**A busy-loop that never slept.** `AutomationService.startForegroundAppMonitor()` used `?: continue`,
which skipped the `delay(2000)` that followed it, so a null `UsageStatsManager` spun the loop flat
out on `Dispatchers.IO` forever. A battery bug wearing a style bug's clothes.

**Three smaller ones.** `WatchdogService.startForegroundSafely()` now reports failure and both call
sites `stopSelf()` instead of leaving a zombie service behind; `RootCompatHelper` stopped discarding
the privileged process's exit code and stderr on a bridge write failure, which had made a `noexec`
mount, an `EACCES` and Shizuku dying mid-write indistinguishable; and `Shizuku.newProcess()`'s
nullable-in-practice return is checked in the Service Doctor's phantom-process fix rather than NPEing
on `waitFor()`.

**The settings search bar speaks the device's language now.** Its back/search/clear content
descriptions, its hint and its empty state were raw English literals, so every other locale saw
English and TalkBack announced English. They are string resources now, the Compose activity log
reuses the resource its Fragment twin already had, and each log row is a merged semantics node — one
swipe stop per row instead of four.

### What had to be re-applied by hand

**The update manager's fork strings.** Upstream wrapped the whole of `downloadUpdate()` in
`scope.launch(Dispatchers.IO)` — correct, since the file-exists check, the delete and `cleanup()` are
all blocking disk I/O that ran on whatever thread clicked the button. But the rewrite re-introduced
`Shizuku+-v$versionName.apk` and the `Shizuku+/…` User-Agent inside the new wrapper, so this was not a
conflict that could be resolved by taking either side: upstream's structure was kept and both strings
re-de-branded inside it. Taking theirs would have quietly put upstream's name back on every
downloaded APK.

**The server's de-branded comments** sat on the exact lines upstream inserted its new permission
checks into, and the string table conflicted twice — once where our de-branded automation-plugin
strings meet upstream's five new search strings, once where our home-status and boot-setup blocks
meet the same tail. All three kept both sides.

### One thing upstream left mismatched

The new server-side checks throw `SecurityException` for an unauthorized caller, but the client-side
call sites in the `api` submodule — `Shizuku.isCustomApiEnabled()` and `Shizuku.Dhizuku.getBinder()` —
catch only `RemoteException`. A `SecurityException` is a `RuntimeException` and will not be caught, so
an unauthorized client that used to receive `false` or `null` now takes an uncaught exception instead.
Both halves shipped in the same batch, which is how the pairing went unnoticed. Nothing in this fork
reaches it — the policy contract goes through a `ContentProvider.call`, and the manager's own two
callers pass unconditionally as the manager app — so it is recorded here rather than patched, since
the fix belongs in the `api` submodule and would change the client library for everyone.

### Unchanged and re-verified

The no-phone-home audit was run again after the rebase and holds in full: `.github/` absent, the
Sentry Gradle plugin unapplied and its DSN hardwired empty, `initializeSentryEarly()` still returning
before `SentryAndroid.init()`, `RemoteDbSyncWorker` cancelling its own work and no-opping, the
VirusTotal and Pithus clients holding no network code, `support_email` blank, automatic update
polling still defaulting off, and the only `openConnection` calls in the app still the two in
`UpdateChecker` — both pointed at this fork's releases. The icon layer was re-checked rather than
assumed even though upstream touched no icon file this round: all five densities carry the plain
`ic_launcher_foreground.png` with no `_base` variant, the badge layer-list is still absent, and both
adaptive-icon XMLs resolve `<foreground>` to a bare mipmap and `<monochrome>` to our own mark. The two
wire-protocol strings, the custom permission names and the `api` submodule pin are where they were.
The component-name contract test passes, and the APK carries the same signing certificate as every
previous release, so it updates in place.

## 13.6.0.r2269+2026-08-14.09-08.g234f1250+001

A two-commit upstream sync, landed by upstream less than an hour after the base of the previous
build. No fork feature work. Both commits touched files this fork owns outright, so neither could
simply be taken: one was merged into our own version of the same function, the other refused for the
second build running.

### What arrived

**One UI corner radii now reach the Compose screens.** The XML half of the app has been applying
upstream's `ShapeAppearance.OneUI.Corner.*` tokens (6/12/18/26/36 dp) through `ThemeOverlay.OneUI`
for some time, but Compose's `MaterialTheme.shapes` never picked them up — so with the One UI theme
switched on, cards, app bars and dialogs on the Compose screens kept stock Material 3 radii while
everything drawn in XML rounded off. `AppTheme` now takes an `isOneUi` flag and swaps in matching
Compose `Shapes`, wired from the home and settings activities. Every other Shape Style — Modern,
Classic, Squircle — is untouched.

That commit rewrote the exact function this fork had replaced to host `AppThemeOverride`, the live
hook that lets a 白い熊 雫 slider recolour every Compose screen without an Activity recreate.
Upstream's version passes `shapes` to a single `MaterialTheme` call; ours branches, because an
imported custom font needs a second call carrying a `typography` argument. The merge threads `shapes`
through **both** branches. Taking upstream's shape verbatim would have silently dropped the new
corner radii for exactly the users who have a font set.

### What was refused, again

**The Plus badge, this time in the themed icon.** Upstream rebuilt `ic_monochrome.xml`, and as a
change to their own art it is a genuine improvement: the old single `evenOdd` path merged a hexagon, a
cat and the badge into one shape that turned to mush once Android flattened it to a single tint, and
the rebuild splits them into three legible pieces. But the third piece is the "Plus" badge in the far
top-right, and this fork's themed icon is the traced mark drawn as a single-tint stroke with the
hexagon deliberately dropped. Ours is kept whole; theirs is discarded.

This is the same badge the previous build refused in the *colour* icon, arriving by a different route.
The whole icon layer was re-checked afterwards rather than assumed: all five densities still carry the
plain `ic_launcher_foreground.png` name with no `_base` variant, the layer-list drawable that
composited the badge is still absent, and both adaptive-icon XMLs still resolve `<foreground>` to a
bare mipmap and `<monochrome>` to our own mark.

### Unchanged and re-verified

The no-phone-home audit was run again after the rebase: `.github/` absent, the Sentry Gradle plugin
unapplied and its DSN hardwired empty, `RemoteDbSyncWorker` cancelling its own work and no-opping,
the VirusTotal and Pithus clients holding no network code, `support_email` blank, automatic update
polling still defaulting off, and the only `openConnection` calls in the entire app still the two in
`UpdateChecker`. The two wire-protocol strings, the custom permission names and the `api` submodule
pin are all where they were. The component-name contract test passes, and the APK carries the same
signing certificate as every previous release, so it updates in place.

## 13.6.0.r2267+2026-08-14.08-14.gd55b5695+002

An upstream sync and nothing else: 21 commits from `13.6.0.r2246` to `13.6.0.r2267`, landed by
upstream across 2026-08-13 and 2026-08-14. No fork feature work in this build. What follows is what
arrived, what was refused, and the two places where the two codebases had independently solved the
same problem and had to be merged rather than picked between.

### What upstream fixed

Two of these are real bugs in the privileged server:

- **The SU bridge was shadowing the real `id` and `whoami`.** The Magisk mock header injected into
  every `sh -c` routed through the bridge defined `id()` and `whoami()` as shell functions, so they
  won for *every* command that went through it, not merely for root-check probes. Any script that
  read `id -u` for a purpose other than detecting root silently got the mocked answer. The two
  functions are gone; `magisk()`, `su()` and `getenforce()` remain.
- **`newProcess()` was wiping the boot environment.** With Magisk mocking enabled and a `null` env,
  the environment was *replaced* with just `MAGISK_VER` and `MAGISK_VER_CODE` instead of appended
  to — so `BOOTCLASSPATH`, `ANDROID_DATA` and `ANDROID_ROOT` all vanished and any child spawning
  `app_process`, a user service among them, died instantly with "ANDROID_DATA environment variable
  unset". It now seeds from the server's own environment first, matching the non-null path.

The rest are manager-side:

- **Update downloads no longer hang.** The silent-install path used when Auto-install is on had no
  timeout, so a stuck root prompt or a dead Shizuku binder wedged the coroutine forever and no
  install prompt ever appeared. It now gives up after five seconds and falls back to the system
  installer.
- **The download progress notification stopped buzzing.** It rebuilt every 500 ms without
  `setOnlyAlertOnce`, so on a high-importance channel it re-alerted on every tick.
- **Manual crash reports carry logs again.** The logcat tail filter allow-listed tags that do not
  match real crash output, so the "Logs" section shipped empty; it now takes the last 300 unfiltered
  lines.
- **The update checker picks the right asset** — by applicationId rather than "first `.apk` in the
  release" — and the Beta channel, not only Dev, now fetches pre-releases. Two fields that were read
  as mandatory are optional now, so a release missing either no longer throws.
- **No more black-screen flash on an appearance change.** Accent, icon style, blur and the OneUI
  theme used to call `Activity.recreate()`; they bump a counter and recompose instead.
- **One-handed mode is a scale, not padding** — content shrinks to 75 % anchored at bottom-centre,
  with a spring animation. Settings gained a Samsung-style ExtraBold large header.
- **The gesture nav bar no longer covers things**: Feature Hub, the Settings list, the Activity Log
  and the app search bar all gained bottom clearance.
- **The Dhizuku Mode switch persists again** — it wrote its value in the wrong branch and then
  returned `false`, so the switch snapped back.
- The "Shizuku is running" status icon reverted to the plain circle-check; a redesign in the same
  batch rendered as a garbled server-rack-and-checkmark mashup.

**Two defaults changed upstream, and they only affect switches you have never touched:** the SU
bridge now defaults **off** (it was on), and Companion fallback now defaults **on** (it was off).
Both were misaligned with their own XML declarations, so the settings screen had been showing one
thing while the code did another.

### What was refused

**The "Plus" badge on the icon.** Upstream renamed the adaptive-icon foreground images to
`*_foreground_base` and layered a badge over them. Because git follows renames, a rebase quietly
carried *our* traced mark to the new name and reparented it under *their* badge — the launcher icon
would have come out black-and-yellow with a Material-You plus sign stuck on the corner. All four
densities are renamed back, the badge layer is deleted, and both adaptive-icon XMLs point at our
foreground again.

One half of that commit was worth keeping: the themed (monochrome) icon now resolves to
`@drawable/ic_monochrome`, which in this fork is the traced mark drawn as a single-tint stroke —
better at themed-icon sizes than the full-colour foreground the old XML reused.

**Upstream's `api` submodule bump.** Upstream moved its pin for the Android 16 client-attach NPE,
but that commit only swaps the reflected `readInterfaceToken` for `enforceInterface`. This fork's
`api` already does that, and also rewinds the parcel on both the failure path and before
`super.onTransact`, intercepts raw code 17, renumbers the AIDL id that collided with it, and undoes
the legacy case shadowing that made `newProcess` return null for every client. Ours is a superset;
the pin stays where it was.

### Where both sides had fixed the same thing

**The theme revision counter.** Upstream's replacement for `Activity.recreate()` is a `themeVersion`
counter threaded into `AppTheme`'s `remember` key — structurally the same device as this fork's
`AppThemeOverride.revision`, arrived at independently. Both now sit in both keys. Drop upstream's and
their appearance settings need a navigation to appear; drop ours and the 白い熊 雫 sliders do, for
anyone with the live theme provider installed, which is everyone.

**The ADB-key reset toast.** Upstream swapped its `recreateWithoutTransition()` for the new
recomposition bump; this fork had swapped the toast for the house-styled one. The merged row does
both.

### Unchanged and re-verified

The whole no-phone-home layer was audited again after the rebase, since upstream develops these paths
actively: `.github/` still absent, the Sentry plugin still not applied and its DSN still hardwired
empty, `RemoteDbSyncWorker` still cancels its own work and no-ops, the VirusTotal and Pithus clients
still hold no network code, `support_email` still blank, and automatic update polling still defaults
off. The only `openConnection` calls anywhere in the app remain the two in `UpdateChecker`.

## 13.6.0.r2246+2026-08-12.02-46.g9f2c01e8+004

No upstream sync — same base commit as `+001`, and the same code as `+003`. This build exists so the
in-app "What's New" dialog carries the prose below instead of the commit summary the build generates
when no section has been written yet; `+003` was built before this section existed, which is the one
thing a release cannot fix after the fact. The changes described here shipped in `+003`.

Three fork changes: the version name grew a time and regrouped its separators, the Device Owner
Tools rows stopped hanging off the wrong switch, and the update checker was taught to read the new
version name it would otherwise have mis-parsed forever.

### The version name is grouped with `+` and pinned to the minute

`+` now opens each top-level group — upstream's version, the pin, our counter — while the pin's own
date, time and sha stay dot-joined, since all three describe one commit:

```
13.6.0.r2246.2026-08-12.g9f2c01e8+001    ->    13.6.0.r2246+2026-08-12.02-46.g9f2c01e8+003
```

The pin gained `HH-MM` because two syncs landing on one day tied on the date and handed the sort
order back to the sha, which is random text. The timestamp is now rendered in **UTC** from the raw
epoch (`%ct`) rather than through `--date=format:`, which used the commit's own timezone and so
disagreed with what a watcher reads back from GitHub. Neither the date nor the sha participates in
ordering; `versionCode` is untouched at `UPSTREAM_VERSION_CODE * 10000 + BUILD_NUMBER`.

### "Check for updates" would have gone permanently silent

Caused by the change above, and the reason this build exists. `UpdateChecker.parseVersionCode` reads
upstream's `rNNNN` and our `+NNN` out of the version name, and it matched the **first** `+N` group.
With `+` now opening the pin as well, that group is the pin's year: `+2026`, clamped to the field's
0–999 range. Every build on base `r2246` therefore parsed to the same `2246999`, so
`versionCode > currentVersionCode` could never be true and no future release on this upstream base
would ever have been offered — the exact failure the function was written to prevent, reintroduced by
the format it had to parse.

The counter is now read from the **last** `+N` group, which it always is. Older published tags
(`…g14550b5e+004`, unpadded `…r2178+14`) parse exactly as before, since there it is both the first
group and the last. `UpdateVersionOrderTest` pins the ordering contract itself rather than the regex:
consecutive counters compare, the pin's date is never read as the counter, both version-name formats
resolve to their real counter, and an upstream sync outranks the counter it resets. The bug is
invisible on the build machine — it shows only as a release that never offers itself — so it needed a
guard that fails the build.

### Device Owner Tools no longer hang off Dhizuku Mode

Screen Capture Lockdown, USB Data Lockdown and Suspended Applications were declared
`android:dependency="dhizuku_mode"`, so they greyed out unless the Dhizuku switch was on. They have
nothing to do with it. All three act through *this* app's own `DevicePolicyManager` under our admin
component, whereas Dhizuku Mode decides only whether `DhizukuProvider` hands the raw `device_policy`
binder to third-party Dhizuku clients. The dependency disabled three working features for anyone who
runs no Dhizuku client, and implied the reverse of the truth — that turning Dhizuku Mode on is what
makes them safe to use.

They are now gated on what they actually require, Device Owner, re-checked on every return to the
screen because it can arrive or vanish elsewhere: the boot-setup card, `dpm` over adb, or Remove
Device Owner two rows above. Gated per row rather than on the category, since disabling a
`PreferenceCategory` also makes its header unclickable and `CollapsiblePreferenceCategory` uses that
click to expand. A row that cannot work is disabled and says why, never hidden.

### The app picker described the wrong feature

`AppPickerPreference` is shared — Shadow Binder's hidden-packages row and Device Owner Tools'
Suspended Applications row both use it — but its empty state was hardcoded to Shadow Binder's string.
Suspended Applications therefore read "Comma-separated list of package names to hide from other apps.
If you have both OG Shizuku and 白い熊 雫 installed…", describing a different feature entirely, and
its own declared `android:summary` was dead text that never appeared. Each row now shows its own
summary when nothing is selected. Shadow Binder renders identically — it declares that same string
as its `android:summary` anyway.

## 13.6.0.r2246+2026-08-12.02-46.g9f2c01e8+003

Superseded by `+004`, which is the same code. The prose for both is under `+004` above; this build
was cut before it was written, so its own bundled changelog carries the generated commit summary
rather than that text. The GitHub release notes for `+003` hold the full text unchanged.

## 13.6.0.r2246.2026-08-12.g9f2c01e8+001

A five-commit upstream sync with no new features in it at all — every one of them lands on the
`rish` consent path, the client-attach handshake, or keeping a frozen client thawed long enough to
attach. That is the same narrow strip of code the fork's own remembered-consent patch and its `api`
fixes live on, so this is the first sync where upstream and this fork had to be merged rather than
stacked, in both the manager and the submodule.

### `rish` could be told "always" and never be remembered

Upstream's fix, and it is the one worth having. The consent flow resolved the caller's UID solely by
looking `callingPackage` up in `PackageManager` — but the classic `rish_shizuku.dex` sends no
`callingPackage` at all, and on some devices the lookup simply fails. The UID was then null the whole
way through, `AuthorizationManager.grant()` was never reached, and "Allow always" therefore granted
nothing: the next invocation prompted again, forever, while every screen involved reported success.

`ShizukuShellLoader` now puts `Os.getuid()` into the `REQUEST_BINDER` broadcast. That process is
spawned as the caller, so the value is authoritative rather than a claim. Every consent surface —
the receiver, the notification's action buttons, the dialog — takes the same fallback chain:
PackageManager first, because it proves the named package really owns that UID, then the broadcast's
own UID. The notification also stops falling back to the generic "cannot be identified" wording when
only the label lookup failed, and shows the package name instead.

### The fork's remembered consent now covers the anonymous caller too

The fork change in this build. The pre-grant that runs behind the fork's global "consent remembered"
gate still refused any caller with no `callingPackage` — which is exactly `rish` in Termux, the case
the whole notification path exists to serve. On every server past pre-v11 the grant is a flag stored
on the UID and the package name is never consulted, so there was nothing to refuse over; the
notification's own "Allow always" button had been granting on the UID alone the entire time. The two
entry points now remember the same thing, instead of the answer sticking or not depending on which
one the user happened to tap.

### A 2.3-second sleep on the main thread

`deliverBinder()` walks a freeze-retry ladder — the fix from the previous release — and can sleep up
to 2.3 s doing it. Both call sites in the receiver were running that on the main thread for the
entire broadcast window; they now hold the broadcast open with `goAsync()` and do the work on IO.
`ShellBinderRequestHandler` also stops allocating a fresh `Parcel` per retry, and passes `null` as
the reply argument, since a ONEWAY transaction never writes one.

### Where upstream stopped short of this fork's own `api` fix

Upstream arrived independently at the two `api` bugs this fork fixed on 2026-08-07 — the dead code-17
handler that left every v13+ client unattached (their #406, the null `getPackageInstaller()` that
crashed installer apps), and the legacy `case 14` shadowing `requestPermission`. Their fix stops
there, and the remaining half is not cosmetic: `onTransact` still keeps the legacy cases for 3, 4 and
8, and an AIDL wire code is `id + 1`, so legacy `case 8` answers a live `newProcess()` call with
`getSELinuxContext()`'s String. The client reads that back as a strong binder, gets null, and
`Shizuku.newProcess()` fails for every caller — SHIZUKUPLUS-85, which took the whole privileged-shell
tier down when this fork hit it.

So the submodule was rebased onto upstream's tip with `Service.java` resolved entirely this fork's
way: the `enforceInterface()` token read instead of reflection for a method that may not be there,
the three shadowing legacy cases still deleted, and `shouldShowRequestPermissionRationale()` still
renumbered off wire 17 so the collision cannot be re-armed by the next method added. One line was
taken from upstream — a second parcel rewind before `super.onTransact`. It cannot fire today, since
every `RishService` branch that reads the parcel returns true, but it guards the precise trap this
method has now been fixed for twice.

### Unfreezing on every retry, not just the first

`addPowerSaveTempWhitelistApp()` was called once, from inside `attachApplication()`, while the binder
call was still on the stack. Some OEM schedulers — Xiaomi and Samsung are named — do not act on the
unfreeze until a later Looper tick, so by the time a retry ran the client could still be frozen. The
whitelist is now refreshed before each retry, against the user ID captured at call time rather than
`Binder.getCallingUid()` read inside the lambda, which by then returns the server's own.

## 13.6.0.r2241.2026-08-09.ge2207af1+001

A three-commit upstream sync, again with no fork changes of its own — but unlike the last one this
batch reaches the binder handoff. Nothing moves the privileged server, the starter, ADB pairing or
the `api` submodule; the one commit that matters lands on the *shell* side of the handoff, which is
exactly the code this fork's remembered-consent patch owns.

### `rish` could be authorized and still get nothing

The substantive fix. When a shell client asks for the binder, the consent prompt arrives as a
notification — and while the user reads it, the caller sits in the background. Android's Cached Apps
Freezer is entitled to freeze it there. A ONEWAY transact into a frozen process does not queue: it
returns `BR_FROZEN_REPLY` immediately, the caller never receives anything, and every layer above
reports success. So the notification said allowed, the manager said authorized, and `rish` said
nothing at all.

`deliverBinder()` now retries four times — at 0, 200, 600 and 1500 ms — to bridge the window while
the OS thaws the process, and returns whether delivery actually happened rather than merely whether
it threw. On total failure the consent screen says so in a Toast, and points out that the
authorization was saved regardless: the next `rish` invocation takes the fast path in
`BinderRequestReceiver` and connects without asking again.

This is the same class of bug the fork already documents on the *app* side — a non-null reply from
`sendBinder` proving only that the provider did not throw. Upstream has now found the shell-side
twin of it.

### A notification that ignored its own switch

Turning the live activity off stopped the foreground service but not the notification. The direct
posting path in `ActivityLogSettingsImpl.showNotification()` never consulted the toggle, so the
notification came back on every 雫 action with the switch plainly off. It checks
`isLiveActivityEnabled()` now.

### The SU bridge could only ever deploy once

`app_process` on Android 14+ requires `rish_shizuku.dex` to be mode 444, so the first root deploy
sets it read-only — and a file that is read-only is read-only to its owner too. Every subsequent
deploy then failed with `EACCES` while writing over it. `streamToPrivilegedFile` now removes the old
file before writing the new one. Root path only; the adb path never touched this.

### USB-tethered ADB now wakes the same machinery as Wi-Fi

`AutomationService` watched `TRANSPORT_WIFI` alone, so an RNDIS/USB-tethering link — the normal
arrangement on ruggedized and enterprise hardware — never triggered a network-state check or the
automation event bus. `TRANSPORT_ETHERNET` joins the request, and Ethernet counts as a valid ADB
transport in `checkNetworkState()`.

The Watchdog summary is honest about its limits in the same commit: it restarts 雫 after a crash or
an unexpected stop, and it cannot do anything when the underlying network itself goes away — a
pulled USB cable is not a crash. The wording is upstream's, with this fork's name in it.

### The rebase itself

Two conflicts, both predicted from the diff before the rebase began, and both in files this fork
edits deliberately.

`ShellConsentActivity.kt` was the real one: upstream rewrote the same click handler that carries
this fork's remembered consent. The reconciliation keeps `setShellConsentGranted(true)` and the
house dialog styling, and takes upstream's new shape around them — the `delivered` flag, the widened
comment about persisting the grant even when delivery fails, and the Toast. All four callers of
`deliverBinder` discard its new return value, so nothing else needed adjusting.

`strings.xml` conflicted twice, once per commit that touched it. The shell-consent block keeps this
fork's remembered-consent wording and its "Always allow" button, with upstream's new retry hint added
beside them; the watchdog summary takes upstream's fuller sentence rather than defending the old
short one. Both then passed through the de-branding commit unchanged, which is what should happen.

`:manager:testShizukuplusDebugUnitTest` is green, so the component-name contract still holds.

The no-phone-home layer was re-audited in full afterwards and is intact: the Sentry plugin absent
and its DSN hardwired empty, `initializeSentryEarly()` still returning before the SDK can arm, the
remote-database worker still cancelling itself, VirusTotal and Pithus still contacting nobody,
auto-update still defaulting off, `.github/` still gone, and `UpdateChecker` — pointed at this repo
— still the only thing in the tree that can open a connection.

## 13.6.0.r2238.2026-08-09.g05fcdfff+001

A seven-commit upstream sync carrying no fork changes of its own. Every commit lands on the manager
UI — theming, settings wiring, window insets — and nothing this round touches the privileged server,
the starter, the binder handoff, ADB pairing, the root path or the `api` submodule. For a fork whose
riskiest rebases are always the ones that move how the service is started, this was the quiet kind.

### Settings that existed only in code are now reachable

Upstream's largest commit here is a wiring audit, and it found preferences that had been dead since
they were written. `hide_disabled_plus_features` was fully implemented with no XML entry at all.
Four more — `vector_enabled`, `experimental_root_compat`, `spoof_device_enabled`, `spoof_target` —
sat in a hidden category that `DeveloperOptionsFragment` could not see, so every one of its
`findPreference` calls returned `null` and Developer Options was, in practice, a stub screen. They
now live in `settings_developer_options.xml` where that fragment actually looks.

The same pass removed listeners that could never fire: four root-integration keys registered by a
fragment whose XML does not contain them, and `ai_core_plus_enabled`, whose generic experimental
handler was silently *replacing* the biometric one it needs. Toggling the activity log now pushes
the new value to the server immediately instead of waiting for the next Feature Hub open.

### Two invisible icons, fixed upstream, that this fork felt hardest

Upstream replaced a set of hardcoded colours with M3 semantic roles. Three of them were literally
invisible here: the edit-mode restore icon was a fixed purple `#4F378B`, the layout-simulator eye
was `Color.DKGRAY` `#444444`, and the home status card fell back to near-black `#1A1C1E` text. On a
theme whose every surface is pure `#000000`, "dark grey on dark" is not dim — it is absent. These
were upstream bugs against its own AMOLED mode, and the fix arrives unchanged.

Stopped-server state also moves from `colorTertiaryContainer` to `colorErrorContainer`, which is the
correct M3 role for a failure and reads as such under the house palette.

### The black screen after a theme change

`recreateWithoutTransition()` zeroed the window animations on the outgoing window only; the incoming
activity inherited `windowAnimationStyle` from the theme and, on some API levels, still played an
enter animation — leaving the screen black until you navigated away. It now zeroes animations on the
new window too, and paints the old-UI snapshot as the window background immediately after
`super.onCreate()` so the one-or-two-frame flash of the new theme colour never shows. Every knob on
the 白い熊 雫 UI page that triggers a recreate goes through this path.

### Three new appearance toggles, and why the house look still wins

Upstream added an **Edge-to-Edge** on/off switch, a **Frosted Glass** window blur on Android 12+,
and a **One UI style theme** — Samsung Galaxy Blue, pill-like corners, bolder headlines — with a
nested one-handed mode that pushes the home cards into the lower third of the screen.

The One UI overlay is applied inside `ThemeDelegateImpl`, one line above where this fork's own
overlay hangs. The order after the rebase is upstream's One UI overlay, then the theme style, then
`ThemeOverlay.Shiroikuma` **last** — so black-and-yellow still wins over One UI exactly as it
already won over dynamic colour, the custom accents and the night overlay. Switching One UI on
changes shapes, type and preference padding; it does not repaint the app blue.

### The rebase itself

One conflict, in `HomeScreen.kt`, and only its import block: upstream's one-handed-mode and blur
imports landed on the same lines as this fork's long-press-cog imports. Both sides kept. The four
files that looked most likely to fight — `ServerStatusViewHolder.kt`, where the fork adds nearly
three hundred lines, plus `settings_shizuku_plus.xml`, `strings.xml` and `ShizukuSettings.java` —
all merged without help.

The no-phone-home layer was re-audited in full afterwards and is intact: the Sentry plugin is still
absent and its DSN still hardwired empty, `initializeSentryEarly()` still returns before the SDK can
arm, the remote-database worker still cancels itself, VirusTotal and Pithus still contact nobody,
auto-update still defaults off, and `UpdateChecker` — pointed at this repo — remains the only thing
in the tree that can open a connection.

## 13.6.0.r2231.2026-08-08.gd5417ebf+002

A single fix, for a regression this fork introduced while merging `+001`.

### The notification's "Allow always" did not mean always

`+001` took upstream's new Allow/Deny buttons on the shell-consent notification (`bd7898de`)
without noticing they are a **second** way to say "Allow always" — and that upstream's version of
"always" is `AuthorizationManager.grant()`, which can only be called for a caller that named itself.

A bare shell client has no `callingPackage`. `rish` in Termux is exactly that, and it is the case
the notification exists for, so the button took its `null` branch, remembered **nothing at all**,
handed the binder over once, and let the next command prompt again — while still reading
"Allow always". The generic *"Shell access request"* wording on the notification, rather than
*"Termux is requesting shell access"*, is itself the tell that no package was attached.

The fork has stored a global answer for unidentified callers since `13.6.0.r2195+2`, and it still
worked; it was simply never reached. It is written in exactly one place — `ShellConsentActivity`'s
Allow button — which until this sync was the only way to say "always", so the two could not
disagree. The new button bypassed it.

It now sets the flag first, on the same terms as the dialog, making the two entry points equivalent
for identified and unidentified callers alike. The Settings → Advanced switch that exposes the same
flag was unaffected throughout, and turning it on by hand was the workaround while this shipped.

## 13.6.0.r2231.2026-08-08.gd5417ebf+001

A nine-commit upstream sync, and eight of the nine land on one subsystem: how a shell client asks
for, and is granted, the privileged binder. Upstream spent this batch arriving at the fix this fork
shipped on 2026-07-31 — by a different route, with different semantics — so the interesting work
this time was deciding how the two fit together rather than porting anything.

### Upstream reached the fork's shell-consent fix from the other side

Since `13.6.0.r2195+2` this fork has short-circuited `BinderRequestReceiver` on a remembered answer,
because upstream re-asked on every single request and a shell client can never present an auth
token — so `rish -c ls` drew a full-screen dialog before each command. Upstream's `b035b101` now
fixes the same complaint by consulting `AuthorizationManager.granted()` for the calling package
(#398: "Allow always" was being stored and then never read, so every new `rish` process re-prompted).

The two gates answer different questions, and both are kept — upstream's first (白い熊, 2026-08-08).

Upstream's is **per package**: it names the app, so it can be granted and revoked one app at a time,
and it can log which app was let through. That is strictly better wherever it applies, so it decides
first. But it can only apply to a caller that *has* a package — and the case this fork's gate was
written for is precisely the one that does not. A bare shell has no `callingPackage` in the
broadcast, so `AuthorizationManager.granted()` can never return true for it, and without the global
gate that caller is back to a dialog per command. The fork gate therefore answers second, for
exactly what upstream's structurally cannot reach.

They also compose in one direction worth noting. The fork gate still performs upstream's #391
pre-grant (uid resolved from `PackageManager`, never read out of the broadcast extra, so a spoofed
broadcast cannot name one package and be granted another's uid). That grant is what *promotes* an
identified caller into upstream's gate: the global path answers for it once, and the per-package
path answers every request after — which is also why the global answer does not quietly widen into
a permanent blanket grant for apps that could have been named.

Both delivery paths now write to the Activity Log with distinct reasons — `pre-authorized` for
upstream's, `consent remembered` for the fork's. Upstream added that audit trail in `407656e2`
specifically so "Allow always doesn't stick" reports could be diagnosed without a logcat; leaving
the fork path silent would have put the fork's own users back in the dark it was built to remove.

### The backup export chooser, in the house look

Upstream's `f89c4beb` adds a plain-text backup format beside the encrypted one, behind a chooser
dialog. The chooser is kept — a plain export really is the only thing that survives a reinstall,
since the encrypted form is keyed per install — but dressed properly: `showHouse()` rather than
`show()`, so it comes up black with the yellow border instead of the stock Material surface, and
`ShiroikumaToast` rather than raw toasts. The exported filenames are de-branded to
`shiroikuma-shizuku_settings_*.json` and `…_settings_plain_*.json`.

Upstream's three new plain-backup toasts arrived raw — the fork's house-toast pass predates them —
and were converted with the rest. Their errors now route through the existing `backupErrorMessage`
helper, which among other things handles a null `e.message`; upstream's `"Backup failed: ${e.message}"`
would have shown the word "null" to anyone whose export failed on a keystore exception, which is the
#315 symptom that helper was written for.

### From upstream (13.6.0.r2222 → 13.6.0.r2231)

**`rish` works for apps that never declared a Shizuku permission (`a8d2b19c`, #387).** Termux and
friends use `rish` without declaring a Shizuku permission in their manifest, so they never received
`grantRuntimePermission()` and `checkCallingPermission()` returned DENIED forever — even after the
user explicitly tapped Allow, every subsequent call hit "Caller … is not an attached client".
`checkCallerPermission()` now also consults the stored consent flags when the OS check fails.

**Apps authorized before 2026-07-19 are repaired automatically (`f89c4beb`, #371/#379/#392).** Before
upstream's `741df2f4`, `grantRuntimePermission` silently failed because the permission had no
defining package, leaving apps with a config entry but no OS grant — they showed "Shizuku not found"
and no amount of re-toggling helped. `migratePermissionGrants()` now re-grants at every server start,
idempotently. This is new work in the server start path, which is worth knowing given how carefully
this fork guards that path, but it touches nothing the single-instance lock does.

**Allow / Deny buttons on the consent notification (`bd7898de`).** Granting no longer requires
opening the dialog. The callback binder travels through `PendingConsentStore` rather than
`PendingIntent` extras — Android 15+ drops an `IBinder` embedded in those — and the receiver is
`exported=false` with explicit component targeting, so no external app can fire the actions.

**The consent dialog and notification name the app (`dd77e934`, `40468712`, #398).** "Talkman is
requesting shell access" instead of the raw package ID, falling back to the package ID when the
`PackageManager` lookup fails.

**Auto-run snippets (`d5417ebf`, #399).** A per-snippet "Auto-run on service start" toggle; flagged
snippets run sequentially through the privileged shell on every transition to RUNNING, including
live service restarts. Room schema v1→v2 adds the column; every existing snippet defaults to **off**,
so nothing runs until you ask it to.

**Decorative icons stop being announced by TalkBack (`b99eadf5`).** `contentDescription="@null"`
leaves a view focusable and announced as an unnamed image; five layouts now use
`importantForAccessibility="no"` instead.

### Verified after the rebase

The no-phone-home layer was re-checked whole, as it is on every sync: nothing in the nine commits
opens a connection, touches Sentry, or revives the remote-database worker, and the only outbound
path in the tree remains the manual update check against this repo. The identity layer, the
server-side single-instance lock and the three-container binder delivery all survived intact, and
the component-name contract test passes.

The `thedjchi/Shizuku` reference remote had **no new commits** — its maintenance pause, announced
2026-07-14, still holds.

## 13.6.0.r2222.2026-08-07.g82ab63b5+001

A three-commit upstream sync, and both substantive commits land in the one subsystem this fork has
spent the last week debugging: how the privileged server gets its binder into a client. Upstream
arrived independently at the same conclusion we did about force-stopping clients, so for once the
sync narrows the fork rather than widening it.

Worth recording alongside that: upstream's own README, in the very commit we rebased onto, now
carries the line *"[Latest Version is Semi-Functional... fixes in progress]"*. Nothing in the two
code commits looks unfinished, and both are coherent fixes to reported issues — but it is their
assessment of their own tip, and it belongs in the record.

### The remembered shell consent skipped upstream's new pre-grant

Upstream's #391 fix stops `rish` asking twice: `ShellConsentActivity` now resolves the calling
package's uid from `PackageManager` and calls `AuthorizationManager.grant()` *before* handing over
the binder, so the `attachApplication()` that follows sees the client already allowed and does not
raise a second dialog.

That fix lives entirely inside the consent Activity — which this fork's own consent work is
designed to skip. Since `13.6.0.r2195+2` a granted answer is remembered, and `BinderRequestReceiver`
short-circuits straight to `deliverBinder()` rather than launching the Activity at all; without it
every single `rish` command drew a full-screen dialog, because a shell client can never present an
auth token. Merged as written, the two fixes would have quietly cancelled: the remembered flag is
one global boolean, so the *first* shell client is granted properly through the Activity and every
client after it is handed the binder having never been granted anything — putting the second dialog
straight back for exactly the case #391 was filed about.

The grant now also runs on the short-circuit path, on upstream's terms: the uid is resolved from
`PackageManager` and never read out of the broadcast extra, so a spoofed broadcast cannot name one
package and be granted another's uid. A caller that cannot be identified falls through unchanged and
is still delivered its binder — only the second-dialog suppression is lost, which is where upstream
stood before the fix.

The new "identified caller" dialog string is de-branded and carries the same *"Allowing is
remembered — revoke it in Settings → Advanced → ADB Tools"* sentence the anonymous variant already
had, because in this fork it is equally true of both.

### From upstream (13.6.0.r2219 → 13.6.0.r2222)

**Force-stopping a client is no longer done at startup or catch-up (#394, #386, #385, #381, #380,
#375, #371).** The previous sync narrowed force-stop-and-retry to the one-time startup catch-up;
upstream has now removed it from there too, and from the server-start path with it. The reasoning is
that the 2-second delayed catch-up pass fires *after* the live observer may already have delivered
the binder, so the target can be mid-operation after all — the reported symptom being
`IPackageInstaller.asBinder()` on a null reference when MT Manager, Morphe or LSPatch was killed out
from under an in-flight `PackageInstaller` session. An app still frozen at catch-up time now simply
waits for its next foreground transition. Force-stop survives only in `sendBinderToUserAppWithRetry`,
which gained a second attempt at 5 s total because 1 s was not enough for a slow OEM cold start.

This composes with the fork's own delivery fix rather than colliding with it: upstream routed *more*
traffic through `sendBinderToUserApp`, which is the method rewritten in `13.6.0.r2219+002` to attempt
every binder container instead of trusting the first non-null reply. The single
`send binder to user app` log line after that loop is intact, so counting those lines per client
launch still diagnoses a duplicate server.

**The freeze-binder retry ladder reaches 9 seconds** (`api` submodule). `{300, 1000, 3000}` becomes
`{300, 1000, 3000, 9000}`, for OEM builds that freeze aggressively enough to miss the old tail. EMUI
is precisely such a build.

**The app-picker list stops being clipped.** Its dialog had a hardcoded 480 dp height; it is now
weight-based, so it fills the dialog on a small screen and at a large font scale instead of cutting
off.

**The toolbar title stops being clamped (#373).** `MaterialToolbar` was a fixed `actionBarSize`
tall, so raising the system font scale cropped the title rather than wrapping it; the height is now
`wrap_content` over a minimum.

**TalkBack skips what carries no meaning.** Decorative images in the header and settings-search
layouts are marked unimportant for accessibility, and the home layout-simulator card — a purely
visual flow diagram — is skipped whole rather than read out element by element.

Plus a cosmetic one the fork inherits for free: the release-notes dialog strips trailing short commit
hashes such as `" (a95d0130)"` from bullet lines.

## 13.6.0.r2219.2026-08-05.gff8ea379+002

An upstream sync of eighteen commits — most of them fixes to the two paths this fork exercises
hardest, third-party binder delivery and `rish` — plus one fork bug of the same shape as the last
release's: a feature that had never once worked, and could not have.

### The changelog was empty on every update, and could never have been anything else

Tapping through an update showed a "What's New" dialog with nothing in it. Three faults had to line
up for that, and each one alone would have been enough.

The tag it asked for could not exist. The dialog fetched GitHub release notes for a tag built as
`"v"` + the version part — `v13.6.0.r2219` — which is **upstream's** convention. Our release tags
carry no `v` and are the full fork versionName. Checked against the live API: `v13.6.0.r2201` is a
404; `13.6.0.r2201.2026-08-01.g14550b5e+006` is the release. So every fetch missed, on every
update, and the dialog fell back to "couldn't load the release notes" every time.

Fixing the tag would not have been enough either. Every build gets installed here and only some get
published, so an unpublished build has no release to fetch — the dialog would still have had
nothing to show for exactly the builds that actually get tested. And upstream's own notes can never
be fetched at runtime at all: that is precisely the sort of outbound path this fork exists to
remove.

So the merge happens at build time instead, and the result ships inside the APK. A Gradle task
writes `assets/changelog.md` — this changelog, with a section for the build being made spliced in,
covering the upstream commits between the previously documented base and this one *and* the fork
commits not already written up. Both base shas are read out of the version strings, which embed
them (`…g14550b5e+006`), so they survive our rebases. The fork half compares commit **subjects**
rather than shas, because a rebase rewrites every one of ours — a sha- or patch-id-based range
reports the entire fork as new after each sync, which was measured, and it listed all thirty-two.

Once a release is written up properly, the generated summary is skipped and the prose wins. Every
step degrades to "no generated section" rather than failing the build, so a source tree without git
still compiles. The dialog now costs no network request at all, and works offline.

Also gone: `assets/changelog.txt`, a stale copy of upstream's `CHANGES.md` still branded
"Shizuku+", shipped in every APK and read by nothing.

### From upstream (13.6.0.r2201 → 13.6.0.r2219)

Eighteen commits, and unusually for a sync almost all of them matter here.

**Cached Apps Freezer and binder delivery (#371).** The long-running bug where third-party apps
intermittently failed to see Shizuku at all. The server now catches up clients that were *already
running* when it started — the process and uid observers only fire on a transition, so an app that
was up (and possibly frozen) at server start would wait indefinitely for an unrelated state change.
Force-stop-and-retry is restored for ordinary clients, having been narrowed to the manager alone by
an earlier startup-speed change, and then correctly *re*-narrowed so it only fires during the
one-time startup catch-up: on the live per-event path a transient ping failure was force-stopping
busy apps, killing one mid-package-install. The `api` submodule gained a 300/1000/3000 ms backoff
for the freeze race and a whitelist of the client UID before its first callback.

**`rish` consent (#377, #387).** Android 15 does not reliably deliver `IBinder` objects inside
`PendingIntent` extras, so the binder arrived null and `rish` hung forever after the user tapped
Allow; the binder is now held in memory and only a key crosses the PendingIntent boundary. The
consent dialog survives rotation, concurrent invocations no longer overwrite each other's
notifications, and the client's abort timer — a fixed 15 s sized for when the dialog launched
directly — is cancelled on success and otherwise stretched to 90 s, which the notification-tap flow
always needed. `POST_NOTIFICATIONS` is now requested on first launch rather than only from the ADB
pairing tutorial, without which the consent notification silently never appeared.

**The black flash on theme change is gone.** Snapshotting with `view.draw(Canvas)` misses
hardware-rendered Compose content and produced a black bitmap; it now uses `PixelCopy` into the
decor view's overlay, which paints above every hardware layer, and fades out after the recreate.
This fork feels it more than upstream does, the theme being pure black already.

**Release notes render as Markdown.** Upstream added Markwon, so the update popup and changelog
dialog show formatted text instead of literal `**` and pipe-table syntax — and the changelog dialog
scrolls again, its link movement method having been swallowing scroll gestures.

Plus two crash fixes: an `IllegalArgumentException` when stopping mDNS discovery with an
unregistered listener, and an `ActivityNotFoundException` opening the releases page on a device
with no browser.

## 13.6.0.r2201.2026-08-01.g14550b5e+006

Three bugs, all found by using the app rather than by reading it, and all of the same shape: the
code was reporting success for something it had not actually established.

### Only one privileged server may exist — now enforced by the server itself

Two `shizuku_plus_server` processes could coexist. Measured on the SM-F971B: both parented to init,
started **44 ms apart**. The consequences compose badly. A server loads the grant table **once** at
startup and never re-reads it; every server pushes its binder to every client at every launch; and a
client keeps the **first** binder that arrives and drops the rest. So which server a client ends up
bound to is a **coin toss per cold start**, and a grant made after both started lives in exactly one
of them — which is how 雫's own UI could truthfully list an app as authorized while that app was
truthfully told it was not. Both servers also flush the same file through their own `AtomicFile`
from their own in-memory copy, so the loser's flush can **erase the grant table wholesale**.

The cause is that the starter kills any existing server and *then* forks, with nothing atomic in
between — and it has **seven** invocation sites (boot receiver, tile, adb start, home card, …). Two
of them racing each sweep, each find nothing to kill because neither server exists yet, and each
forks one. A boot receiver firing while the app is opened is enough.

Reordering cannot close that, so the fix lives in the server: `SingleInstanceLock` takes an
exclusive lock **before** anything can publish a binder, and the loser of the race stands down. It
never kills the winner — that process may be holding live user-service bindings for apps that are
working fine. If the lock cannot be taken *at all*, the server starts unlocked: an unavailable lock
must not turn a rare race into a total outage. The starter additionally re-checks for a survivor
immediately before forking, skipping pids it has just killed, since a `SIGKILL`ed process lingers as
a zombie and would otherwise read as a live conflict.

### A non-null reply from a client is not proof the binder arrived

Every app built against the **published** `dev.rikka.shizuku` artifacts — which is every ordinary
third-party Shizuku client — was silently locked out, while the server logged a successful send.

`sendBinderToUserApp` tries each binder container in its own `call()`, and treated the first
non-null reply as delivery. It is not: the client's `ShizukuProvider.call()` runs a **void**
`handleSendBinder()` and then returns a freshly allocated empty `Bundle` unconditionally, and since
Android 13 a Bundle received over binder is **defusable** — a container class the client does not
have is swallowed and yields `null` rather than throwing. A non-null reply therefore means only
"the provider did not throw".

The container tried first, `rikka.shizuku.BinderContainer`, exists **only** in this repo's vendored
`api/provider`; no released AAR contains it. So the loop stopped on a container the client had
ignored, and the `moe` container every client can actually read was never sent. All containers are
now attempted — free, because a client that already took one bails at its own "already a living
binder" guard. The log line stays deliberately **single**, since its count per launch is what
diagnoses a duplicate server.

### The boot checklist asked for a pairing and tested for a completed start

Step 2 read "Connect once via wireless debugging" but tested `lastPort` plus the recorded launch
mode — both written only *after* adb has connected and run the starter. Pairing successfully left it
red, with the Wireless debugging screen listing the device as paired at the same moment.

Pairing and connecting are different things and now sit in different steps. There is no API for "is
this app paired" — the system's paired-device list is not readable by an ordinary app — so the one
moment the answer is certain is when our own handshake completes, and that is where it is recorded,
at the single choke point all three pairing entry points pass through. The record can only go stale
in one direction (saying paired after the authorisation was revoked), so that row keeps its button
as **Pair again** rather than pretending to be live state.

Step 3 now names the road this device actually has: **paired** means tapping Start is the whole of
it, and the app finds the port itself; **unpaired** means one cabled `adb tcpip 5555`, which is
spelled out along with why the cable comes straight back out. It also stops claiming a port is
reachable on the strength of `lastPort`, which is a memory rather than a probe.

Battery optimisation moves ahead of Start on boot — it decides whether the boot start happens at
all, while the switch only records an intent the receiver already acts on.

## 13.6.0.r2201.2026-08-01.g14550b5e+004

Rebased onto upstream `13.6.0.r2201`. Upstream's six commits are one investigation: **`rish` and
`sh plus` were broken end to end** — a hardcoded package id in the starter, another in the shell
loader, R8 stripping `PlusShell` for want of a `-keep` rule, and a consent dialog that Android's
background-activity-start rules silently dropped so the failure read as a timeout. All four are in.

### The manager package id comes from one place now

Two of upstream's fixes rewrote exactly the files where this fork hand-wrote `shiroikuma.shizuku`,
which is the treadmill the djchi ledger's "package-name de-hardcoding" row has been describing for
months. It is settled: `gradle.properties → APP_ID` is injected as a `buildConfigField` into
`:shell` and `:starter`, alongside the `:manager` `applicationId` that already read it.

Upstream's `resolveManagerPackageName()` probe is kept whole; only its first entry changed, from a
literal to `BuildConfig.MANAGER_APPLICATION_ID`. Upstream needs two literals because one server
binary serves two applicationIds — this fork never builds the Drop-In flavor, so its id is fixed at
build time and the probe is only a safety net. The starter took upstream's side outright: it now
receives the server's already-resolved id as a `--manager=` argument, which is better than any
literal because the value comes from the one process that actually knows.

djchi's alternative — deriving the id from the APK path — was examined and **not** taken. The
`rish`/`plus` scripts set `CLASSPATH` to `rish_shizuku.dex`, not the manager APK, so that derivation
can only ever work in the server process, which already self-corrects.

### The stale-server prompt was a snackbar, and its tracking had a hole

After an app update the privileged server is a *separate* process still running the old code, and it
keeps serving it until restarted — silently breaking apps that connect through it. The prompt for
that was a snackbar: bottom of the screen, reads as transient, and `SnackbarHelper` keeps a single
global slot that any later snackbar takes over without a trace. It is now a house dialog, with
**Restart server** in the emphasis slot and **Later** quiet.

It drives the status card's own restart routine through a small hook rather than a second copy. That
matters: without root the only shell this app can reach is the one the *running server* lends it, so
a restart is deliberately **not** stop-then-start. The old snackbar's action was
`set(STOPPING)` — precisely stop-then-start, which on a non-root device could leave wireless
debugging as the only way back.

Two supporting changes. "We cannot prove the server is current" no longer counts as fine: an
unverified server prompts, tracked by its own key rather than `LAST_SEEN_VERSION`, which
`ShizukuApplication` advances on every update long before the home screen runs. And the status card
carries a persistent red line for as long as the condition holds, so dismissing the dialog does not
make the problem invisible.

**The tracking itself was recording intent, not outcome.** The build id was written the moment the
state became `STARTING` — before anyone knew whether the start worked. A restart that *failed* while
an older server was still alive settled straight back to RUNNING, and that stale server was then
stamped with the current build: the skew masked permanently, in exactly the case the prompt exists
for. Recording now happens in one place, the sticky binder-received callback, gated on a start of
ours being in flight. A new binder arriving is a new server attaching; a failed restart produces no
new binder at all. A missed recording leaves the value unknown, which prompts — an unnecessary
prompt costs a tap, a missed one costs silent breakage.

## 13.6.0.r2195.2026-07-30.gac2ae085+011

### The app calls itself 白い熊 雫 everywhere

The fork was renamed in its label, its icon and its package, but not in its own prose: the home
screen still read "Control Shizuku with automation apps", the quick-settings tile said
"Shizuku: Active", and the same name ran through the setup guides, the doctor tips, the watchdog,
the permission dialogs and the notification channels. 1 785 lines across 40 files now say
**白い熊 雫** — the English strings, all 36 translations, the Compat Hub's own label, the preference
XML, and the literals compiled into the tile, the dialogs and the crash report.

Twenty-one mentions are deliberately still "Shizuku". Every one of them names the *other* app —
"stock Shizuku", "OG Shizuku", "the original Shizuku package name" — or is machine-readable text
(`af.shizuku.plus.*`, `moe.shizuku.privileged.api`, class names, log tags), where a rename would
break the wire protocol rather than the branding.

Two upstream defects were fixed in strings that were being edited anyway: an em dash that had been
mangled into the literal text `2014`, and "Go back to Shizuku and start Shizuku."

### Device policy powers, handed to a sister app

白い熊 雫 is Device Owner on the razr. 白い熊 応用管理's Snooping page switches off an app's
privacy-invasive capabilities, but everything it could do was **soft** — an app-op or a permission
it revoked could be put back by Settings, by another tool, and sometimes by the app itself. Device
Owner is what makes those decisions hard, and 応用管理 cannot grant itself any of it.

There are exactly two ways to pass the power across, and neither covers the other's half, so both
are here. **Delegated scopes** (`delegation-permission-grant`, `delegation-package-access`,
`delegation-block-uninstall`) let 応用管理's own process fix permissions, suspend apps and block
uninstalls — with no IPC, and with 白い熊 雫 stopped, because `system_server` stores the grant.
`setDelegatedScopes` has no `dpm` command, which is the entire reason this had to be built here.
The grant is verified by **reading the scopes back**: the call returns `void`, so a scope the
platform silently dropped would otherwise look like success.

The rest cannot be delegated at all — no scope carries `setUserControlDisabledPackages`,
`setPermittedAccessibilityServices`, `addUserRestriction`, `setAlwaysOnVpnPackage` or
`setCameraDisabled` — so those run in this app's process on 応用管理's behalf, over a new
`ContentProvider.call()` at authority `shiroikuma.shizuku.policy`. A provider call rather than a
broadcast, because it answers synchronously (every operation is "do X, did it work?" behind a switch
the user just tapped) and because `Binder.getCallingUid()` cannot be spoofed — so the allowlist is a
real gate and no shared secret is needed. It is a **new** authority, not an extension of
`DhizukuProvider`: that one is the public Dhizuku authority third-party clients bind.

Accessibility blocking is stored **inverted**. `setPermittedAccessibilityServices` is an allowlist
whose `null` default means "everything is permitted", with no "block one" form, and used naively it
has a failure mode that survives being correct on the day it is set: once a non-`null` list exists,
any accessibility service installed *later* is off the list and is barred silently, with nothing
explaining why it will not stay enabled. So the durable state is a blocklist, the platform list is
derived as `(every installed service) − blocklist`, and it is recomputed on package events, on the
`status` call and when the section is opened. An empty blocklist restores `null` rather than a
hand-built "everything", and the enumeration refuses to run while the user is locked — a short list
is exactly what would bar every service on the device.

Five user restrictions are **refused in code** rather than warned about, because each removes the
route you would use to fix a mistake: `DISALLOW_DEBUGGING_FEATURES` kills the ADB that 応用管理
needs and that you would recover with, `DISALLOW_SAFE_BOOT` and `DISALLOW_FACTORY_RESET` remove the
offline and last-resort routes, and the unknown-sources pair blocks sideloading a fixed build.

Everything is reachable from **Settings → Feature Hub → Security & Access → Device policy powers**:
a Device Owner header, a row per authorized app whose single switch moves *both* halves at once (so
they can never drift apart), the device-wide controls, and — visually separated at the bottom —
**Clear all device-policy locks**. Every dangerous row carries a red 危険 tag on the row itself,
not three taps away, and every confirmation puts Cancel in the positive slot with the destructive
choice quiet and red.

That last action is the point of the whole design. None of these locks can be undone from Settings,
by the affected app, or with `adb`; `dpm` has no command for them either; and a lock **outlives the
app that set it**, because it was stored under 白い熊 雫's admin. Uninstalling 応用管理 with locks
live would leave them with nothing to see or clear them. So the escape hatch works without 応用管理
installed, needs no ledger (every lock is discoverable from the platform), and reports a result per
step — a silent "done" would be the worst possible lie in the one action someone runs when they are
already stuck.

### Folding you can read, in Settings and on the home screen

The settings groups had a disclosure chevron that pointed **down when folded** and **up when
unfolded** — ambiguous in both states, since a down chevron reads equally as "this is open" and
"tap to open", and up and down differ only by which end is wider. It is now **right when folded,
down when unfolded**, the platform's own tree convention.

The group boxes were already being drawn — `M3ECardItemDecoration` painted a rounded card per
category, filled with `colorSurfaceContainerHigh`. In this theme that role is the same pure black as
the page, so the box was invisible: exactly the trap the standing rule in `CLAUDE.md` names, where a
container told apart only by tonal lift stops existing rather than merely looking flat. Every group
now carries a rounded **yellow** border reading the house knobs (`KEY_COLOR_BORDER`,
`KEY_CARD_BORDER`, `KEY_CARD_RADIUS`), so it moves with the same sliders as the home cards. Folded,
the box shrinks to the title row, which is what makes the folded state readable at a glance.

The border is opt-in per decoration rather than always-on: a decoration with no headers draws **one**
card spanning the visible children, so the app list would have got a box around the scrolling
viewport instead of around anything real. And a group cut off by the top or bottom of the screen has
that edge pushed off-screen, so scrolling a long group does not paint a false line across it.

**Every group now starts unfolded**, including any that names no default. Because a category
persists its state the moment it is tapped and a stored value beats a default, there is also a
one-time clear of remembered fold states — without it, groups already collapsed would have stayed
collapsed and the change would have looked like it had not worked.

**The home cards fold too.** Each gets a chevron in its top-right corner; folded, the body goes and
one title line stays. It is wired from a single place — `HomeAdapter.onBindViewHolder`, the only code
that knows both the card view and its stable id — so a card added later is foldable for free. The
folded label is read out of the card's own view tree (its first non-blank `TextView`, which in every
one of these layouts is the heading) rather than from a hand-kept id → string map that would go stale
the first time someone adds a card; and it is recomputed on every bind, because holders recycle and a
title kept from the previous card would be a plausible-looking lie. Fold state persists per card id.

### Device policy powers: granting and un-granting from the same place

Authorizing a sister app moved out of Settings and onto the **home card's Device Owner section**,
where Device Owner is granted and cleared — handing the powers on belongs beside the thing that makes
them possible, not three screens away in a feature list. Neither row appears before this app actually
is Device Owner: an offer that can only fail is worse than no offer. A second row opens the full
Device policy powers page directly.

The app picker is a **searchable tile grid** — icon, label and package id per tile, filtering live on
either. The id is shown because it is what the allowlist and the delegation key on: labels collide,
labels get localised, and "which `shiroikuma.*` is this" is the question actually being answered.

**Un-authorizing now has a front door.** An *Authorized apps* row lists what holds powers and opens
the per-app sheet, where Revoke lives. Previously revoke was reachable only by tapping "Authorize an
app" and picking one that was already authorized — which worked, but nobody would look for a revoke
behind a button labelled authorize.

Revoking again offers to clear that package's locks in the same breath. Revoking only stops *future*
calls: every permission the app already policy-fixed and every package it suspended stays exactly as
it was, stored under 白い熊 雫's admin. Sending 白い熊 to find the recovery action later leaves the
locks live in the gap, with the app that set them no longer able to explain or release them. The
Settings *Clear all device-policy locks* row now drives the same shared code, so the two screens
cannot drift into describing one operation in two ways.

The red "not casually reversible" warning moved **inside** the Remove Device Owner box. Floating
above the section, it read as a warning about the whole Device Owner block rather than about that one
action. It still shows before Device Owner is ever granted — the cost of undoing it is exactly what
should inform the decision to do it.

### The version says which upstream commit the build sits on

`custom` is rebased onto upstream's branch tip, so the fork's base is an arbitrary upstream commit.
`UPSTREAM_VERSION_NAME` does carry upstream's own commit count, but it is pinned **by hand** in
`gradle.properties` — if a sync ever forgot to refresh it, the version would quietly lie about which
upstream code is in the build. The version now appends `.<base commit date>.g<8-char sha>`, read
from `git merge-base HEAD master`: not our own HEAD (which `+N` already covers) and not master's tip
(which overstates the base whenever master is fast-forwarded before `custom` is rebased). It moves
only on a sync, so two builds sharing a pin are built on the same upstream code. The date is there
to sort — a bare sha orders builds at random. No git, or no local `master`, degrades to the old form
rather than failing the build.

The build counter is now zero-padded to three digits (`+008`, never `+8`), the standing family
convention: `~/tmp` is shared by every sister app's builds, and unpadded, `+10` sorts before `+2`.
`versionCode` is untouched by both changes — it stays `UPSTREAM_VERSION_CODE * 10000 + BUILD_NUMBER`
and orders on its own.

`UpdateChecker.parseVersionCode` read only upstream's `rNNNN`, so every build on one upstream base
compared **equal** and a newer fork release of the same upstream version could never be offered. It
now reads `rNNNN * 1000 + N`. The date and sha are ignored on purpose: they say *which* upstream code
is in a build, not whether it is newer.

## 13.6.0.r2195+5

Three fixes, each found by using the app after a reboot on the Mate XT.

### A failed start left "Starting…" on screen forever

`ShizukuStateMachine.update()` treated `STARTING` and `STOPPING` as absorbing states — once entered,
nothing could leave them except the binder actually arriving. Every failure path calls `update()` to
recover, so every failure path was a no-op.

What that looked like: after a reboot, the home card's **Start server** found no server and no adb
TCP port, set `STARTING`, called `update()` to undo it, and showed the dialog asking for
`adb tcpip 5555`. Running the command worked — but the card was now latched, reading "Starting…"
with its progress bar running and its start button **disabled**, which is precisely the button the
dialog tells you to come back and press. Nothing cleared it for the life of the process; even
`ShizukuReceiverStarter` had already hand-rolled a `set(STOPPED)`-then-`update()` workaround for the
same trap.

The two meanings are now separate. `update()` stays the passive refresh — it still preserves a
transition, but only while a fresh `transientSince` stamp says one is plausibly in flight (90s for a
start, 20s for a stop). The stamp is written on transition only, never on a repeated `set` of the
same state, because `Starter.waitForBinder` polls `update()` every 250 ms and would otherwise push
the deadline out forever. The new `settle()` answers definitively: `RUNNING` if the binder responds,
`STOPPED` if it does not, whatever the transition claims.

Nine call sites that had just learned an attempt was over now call `settle()`. The quick-settings
tile distinguishes the cases properly: a successful root shell keeps `STARTING`, because the binder
lands a moment later, while a failed one settles at once.

### The swipe-gesture hint was unreadable, and only offered once

The card on the app-management page removed itself after four seconds while telling you "Tap
anywhere to dismiss" — wrong twice over, since only the card itself was tappable and it left on its
own regardless. It is shown once per install, so being unreadable meant the gestures were never
explained at all.

It now waits for an **OK** button, and records acknowledgement when that button is tapped rather than
the moment it is scheduled — a card you never answered comes back instead of being silently spent.
That is a new preference key on purpose, so installs that burned the old one on a card they never got
to read are owed one more showing.

The card was also carrying `strokeWidth="0dp"` over a `?colorSurfaceContainerHigh` fill, which in
this theme is the same pure black as the page: the standing invisible-container trap. It now takes a
`?attr/colorOutline` baseline stroke plus `ShiroikumaViewTheme` for the live house border, accent
button and typeface, and is `clickable` so taps no longer fall through to the list beneath it.

### The fork inherited stock Shizuku's authorizations

Upstream names the privileged server's grant table `shizuku.json` and keeps it in
`/data/user_de/0/com.android.shell/` — a directory no Shizuku build owns. So every Shizuku on the
device shares one table, it outlives uninstalling the app that wrote it, and whichever server starts
next silently adopts the previous one's decisions.

That is not theoretical. Cleanly uninstalling stock Shizuku and installing this fork came up with
fourteen entries already present — a dozen third-party apps displayed as authorized here that had
never been authorized here, inherited verbatim from a manager that was no longer installed.

The file is now namespaced to `shiroikuma-shizuku.json`. Our table starts empty, so grants are made
deliberately rather than inherited, and any stock leftover is ignored — not read, not migrated, not
deleted. **Existing installs therefore start with nothing authorized**, which is the point; each app
asks again on next use.

The *directory* does not move, and is right as it stands: the server runs as uid 2000 whenever it was
started over adb, so it cannot use the manager's own data dir (0700, owned by the manager's uid), and
it must load the table at startup whether or not the manager is running. `com.android.shell`'s DE
storage is the shell user's own — `drwx------ shell shell` — and DE storage is readable before first
unlock, which a boot-time start depends on. Both facts are now recorded at the call site so the next
reader does not "fix" it.

## 13.6.0.r2195+2

First build on the **13.6.0.r2195** upstream line — 17 upstream commits folded in, plus the fork
work below. The `+N` reset to 1 as it does on every sync; `+2` is the tested build.

### Shell clients are authorized once, not on every command

Upstream restored binder access for `rish`, `adb` and Termux this cycle, behind a new consent
dialog — and then asked again on **every single request**. That is not a stray bug: the answer a
consent prompt would normally remember is an `IntentCrypto` auth token, and a shell client can never
produce one, because that key is scoped by AndroidKeyStore to the manager app's own UID. There was
nothing to remember it by, so `rish -c 'ls'` put a full-screen prompt in front of every command.

The answer is now stored (`KEY_SHELL_CONSENT_GRANTED`). `BinderRequestReceiver` checks it before
launching the activity and hands the binder straight over when it is set; the button reads **"Always
allow"**, and the dialog says the choice is remembered and where to undo it.

Revoke at **Settings → Advanced → ADB Tools → "Shell client authorization"**. That switch is what
makes a standing grant reasonable to offer at all — while it is on, any shell client that can
broadcast to the manager receives the binder unprompted, and as the dialog itself says, the
requester cannot be identified. That exposure is inherent to the shell path; remembering the answer
is what makes it silent, so it is revocable in one tap.

### The consent dialog came up with no border, and therefore no dialog

Upstream's `ShellConsentActivity` builds its dialog with `create()`/`show()` rather than the house
`showHouse()`, so `ShiroikumaDialogs.installGlobalStyling` never saw it. In this theme that does not
look flat — it **disappears**: black text on a black window with no fill and no yellow edge, floating
over whatever the terminal happened to be showing.

`RequestPermissionActivity` hit the identical trap earlier and carries a comment about it; the same
fix applies here — `ShiroikumaDialogs.style()` immediately after `show()`, which must come after
because `MaterialAlertDialogBuilder` installs its own window background during `show()`. This is the
standing "every `surface*` container needs a visible border" rule catching an upstream import, and
it will keep catching them.

### Profile Owner support, merged rather than taken

Upstream added Profile Owner alongside Device Owner in `ShizukuPlusSettingsFragment`. Our own tree
had already lifted that whole code path out into `DeviceOwnerHelper`, because the home boot-setup
card needs the same operations and two copies of a clear path would eventually disagree about
whether they verify the result.

Resolving only the conflicting lines would have silently dropped half of upstream's feature, so the
Profile Owner branch was ported into the helper instead: role detection up front,
`clearProfileOwner()` on the PO branch, and the fork's factory-reset safety layer — dialog rather
than toast, `Throwable` rather than `Exception`, and a post-clear verification, since both clear
calls are documented as best-effort — extended to cover both roles. The guard that used to reject
anything that was not Device Owner had to change too, or a Profile Owner clear would have been
refused outright while the button offering it was visible.

`isDeviceOwner()` and the new `isProfileOwner()` stay separate on purpose: the boot-setup card asks
whether the *boot survival* guarantee holds, and only Device Owner gives that.

### The boot-setup card, ported onto upstream's rebuilt home adapter

Upstream extracted `HomeAdapter`'s item building into `rebuildItems()` to fix the RecyclerView
"Inconsistency detected" crash during drag-to-reorder — `notifyItemMoved()` had been called without
the backing list ever being reordered. The fork's boot-setup card and its scoped server-status
creator were written against the old inline structure, so both were moved into `rebuildItems()`,
the card keeping its ordering slot directly above the wireless-adb card it exists to support.

### `thedjchi/Shizuku` is now watched, never merged

Upstream is itself a fork of [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku), and does not
always absorb its work promptly. That repository is now a **reference remote** — push disabled,
submodule recursion off — and `/upstream-new-version` reports the gap on every sync.

It is deliberately not a sync source. Upstream *replays* djchi's history rather than
fast-forwarding from it, so the git-level merge base collapses to a 2017 commit and a merge would
drag in roughly 1300 duplicate commits, each conflicting against upstream's own rewrite of the same
file. Anything worth taking is taken as an individual cherry-pick, and the review ledger in
`.claude/skills/upstream-new-version/djchi-base` records what has been examined, absorbed, deferred
or rejected so no sync re-litigates it.

### `api` submodule

Rebased onto upstream's dead-binder cache eviction in `SystemServiceHelper.getSystemService`, which
our two fork commits do not touch. Note that upstream's parent repository pins an api commit that
was never published to any branch or PR ref, so the published tip is used instead — pinning the
phantom commit would leave a fresh clone unable to configure.

### From upstream (13.6.0.r2178 → 13.6.0.r2195)

Seventeen commits, the substantial ones being: shell/rish binder authorization restored end to end
(the wire format had regressed to writing an interface token where the client reads a path); Termux
and other two-segment package ids resolving at last in `rish` and `plus`; a security pass fixing a
confused-deputy privilege escalation in `elevateApp`, shell injection in `AICorePlusImpl` and
`StorageProxyImpl`, and a Parcel corruption in the Shizuku-spoof binder branch; app-id normalization
so manager authentication works in Secure Folder, Work Profiles and multi-user; a starter retry when
the manager's provider is transiently null after an OEM kill; `pm grant` zombie/FD leaks reaped;
intercepted `mv` no longer given cp-only flags; and Profile Owner support.

**Note for `rish` users:** the two-segment package fix lives in the `rish` **script**, which sits in
your terminal app's storage — installing the APK does not update it. Re-export `rish` *and*
`rish_shizuku.dex` from the app's terminal screen and replace both copies.

## 13.6.0.r2178+29

### `Shizuku.newProcess()` returned null for every caller — server fix

The privileged shell the app runs its own commands through was dead, silently, on every build. Any
feature routed through `PrivilegedShell` — **"Grant now"** for `WRITE_SECURE_SETTINGS`, **"Make
owner"**, the in-app updater — fell through to its no-privilege fallback and offered a
copy-this-command-on-a-PC dialog, while the server sat running perfectly beside it.

The cause is a transaction-code collision in `Service.onTransact`. Binder wire codes are
`FIRST_CALL_TRANSACTION + id`, i.e. `id + 1`, but the raw pre-v11 compatibility cases were written
using the **ids**. Three of them landed one slot short and, because that switch runs ahead of the
AIDL-generated stub, silently won:

| Wire code | The AIDL method | What the switch answered |
| --- | --- | --- |
| 3 | `getVersion` | `getUid` |
| 4 | `getUid` | `checkPermission` |
| **8** | **`newProcess`** | **`getSELinuxContext`** |

Code 8 is the expensive one. A modern client calling `newProcess` transacts 8, was handed
`getSELinuxContext`'s **String**, and read it back as a strong binder — which yields `null`. So
`newProcess()` returned null unconditionally and `ShizukuRemoteProcess` threw *"the privileged
service could not start the command"*. That is the SHIZUKUPLUS-85 symptom an earlier commit turned
into a clean exception without ever finding its cause.

The collision is inherent — old wire 8 meant `getSELinuxContext`, new wire 8 means `newProcess`, and
one code cannot serve both — so current clients win; the `api` library ships inside this app. Cases 2
and 7 remain: no live method answers to those codes, so they are collision-free legacy support.

### A third privileged shell: adb over the loopback

`adb tcpip 5555` is widely misread as opening a channel *to the PC*. It does not — it restarts the
**phone's own adbd** listening on a TCP port of the phone, which anything on the phone can then
connect to. The cable is needed for exactly one command, after which it comes out and the app can
drive adb on itself.

The new `AdbLoopbackShell` turns that into a real privilege tier beside the Shizuku service and root,
reusing the same `AdbClient`, key and `127.0.0.1` target the wireless-debugging start path already
uses — only reached without pairing.

It finds the port by **connecting**, not by reading `service.adb.tcp.port`. That property is labelled
`adbd_config_prop`: `getprop` from an adb shell shows `5555` while an ordinary app very likely gets
nothing back, and a blocked read is indistinguishable from "adb is off". A socket probe answers the
question actually being asked — *is there an adbd we can reach* — needs no permission beyond
`INTERNET`, and is correct whether or not the property is readable. The probe runs off the main
thread and the card renders from a cached result.

### Privileged actions now chain their tiers instead of choosing one

**service/root → loopback adb → the copy-this-command dialog**, for both the secure-settings grant
and **"Make owner"**. Treating the tiers as alternatives rather than a chain is exactly what put a
dialog on screen while a working adb shell sat listening on port 5555.

Every tier is judged by **re-checking the result** — whether the permission is held, whether the app
is Device Owner — never by an exit code: `pm` and `dpm` both exit 0 without doing anything on some
OEM builds, so a tier that merely *ran* proves nothing and falls through to the next.

Failures now surface their reason instead of vanishing. Release builds plant no Timber tree, so a
swallowed exception is invisible rather than merely quiet — which is why this class of bug went
unnoticed for so long.

### The boot checklist, rebuilt

Now titled **"Start 白い熊 雫 automatically after boot"**, and seven steps rather than six.

- **New step 3, "Start the server"**, names both roads in: pair over wireless debugging, or plug into
  a PC and run `adb tcpip 5555` once. Its Start button uses the probed port directly, instead of the
  lookup that begins with that unreadable system property and otherwise drops to hunting for a
  wireless-debugging service that is not there.
- **Step 4, secure settings**, gains a **"Grant via adb"** button. Where it previously went *blocked*
  whenever the server was down and there was no root, it now runs the grant over the loopback
  connection. When both roads are open the row says so, so the fallback is not invisible on the
  devices that have it.
- **Step 7, background launch, stops lying on stock ROMs.** It only knew about OEM autostart managers
  (MIUI, ColorOS, EMUI…), so on a phone without one it claimed the setting could be neither opened
  nor read — while *Settings → Apps → 白い熊 雫 → App battery usage → Allow background usage* sat
  right there. Where there is no OEM launch manager the standard background restriction **is** the
  whole story: `ActivityManager.isBackgroundRestricted` gives the row real state, and the app-details
  page gives it a button. EMUI and MIUI are untouched, still pointing at the screen they cannot read.

### The restart prompt was invisible

The version-skew snackbar — *"白い熊 雫 was updated, but the running service is still on the old
version"* — is filled with `colorPrimaryContainer`, and every container role in this theme is the
same pure black as the page behind it. With nothing but that fill it had **no edge at all**: a black
slab on a black screen, the standing "every `surface*` container must carry a visible border" trap.
It now draws its own background with the house yellow border, using the same knobs and the same
minimum as the house dialogs, so it can never come up borderless.

## 13.6.0.r2178+24

### Third-party clients can attach at all — server fix

Every client built against the modern Shizuku API (`api` 12.2+) failed to attach to this server, and
therefore could never be asked for permission or bind a user service. Nothing in a client app could
work around it. Three compounding defects, fixed together because each alone leaves the server broken
a different way:

- `Service.onTransact` read the interface token by reflecting for `Parcel.readInterfaceToken()`, which
  does not exist, and returned `""` on failure — so both descriptor comparisons were false and the
  entire interception block, **including both `attachApplication` entry points**, was skipped for every
  caller that ever connected. Replaced with `enforceInterface`, which is public SDK and both validates
  the token and leaves the read cursor where the raw cases expect it.
- With the token read working, every transaction the switch did not handle fell through to
  `RishService.onTransact` and the AIDL stub with the cursor past the token — both read it themselves,
  giving `SecurityException: Binder invocation to an incorrect interface`. The cursor is now rewound
  before either fall-through.
- `BINDER_DESCRIPTOR` is the same literal as the legacy descriptor, so the legacy/new split could never
  take its second branch — the only place code 17 (v13 attach) was handled. Both attach codes are now
  intercepted unconditionally. Raw 17 collided with `shouldShowRequestPermissionRationale`, declared id
  16 (AIDL wire codes are `FIRST_CALL_TRANSACTION + id`, and that is 1), so an attach was dispatched to
  a method that calls `requireClient` and throws *"Not an attached client"*. That method moves to
  declared id 122; ids 13 and 16 must never be used again, and the `.aidl` says so.

Also: **one `BinderContainer` per `call()`**. Three different container classes shared one Bundle, and
`Bundle.getParcelable` unparcels every value rather than just the key asked for — so a client shipping
only one of them could read none, and received no binder at all. Each attempt is now sent separately
with its own error handling, because the failure arrives as a `BadParcelableException` thrown back
across the binder rather than as a null reply.

`ClientManager`'s logger reported as `UserServiceRecord`, filing every `requireClient` refusal under
another class's name.

### Server controls on the status card

- **Start / Stop**, with the first button reading **Restart 白い熊 雫 server** while one is running.
- Restart is **one action, not stop-then-start**: without root the only shell available is the one the
  running server lends the app, so stopping first destroys the privilege needed to start again. The
  starter is executed through the live server, which it then displaces.
- The outcome is decided by waiting for the binder, **not** by the shell's exit code — displacing the
  old server also kills the process carrying the command, so a successful restart often reports failure.
- When no shell is reachable it tries **local TCP ADB** (`127.0.0.1`) before offering wireless pairing.
  That path works over a plain cable with no Wi-Fi and no pairing; if ADB is not in TCP mode the app
  says so and offers the one-time command, since an app cannot speak to ADB over USB at all.
- Immediate, persistent feedback: labels change and a progress bar appears on the tap itself rather
  than waiting for a throttled list rebind, the in-flight state is process-global so a rebind restores
  it instead of re-enabling the buttons mid-operation, and it is cleared in a `finally`.
- An info line explains that this restarts the **server** and not the app, and that force-stopping and
  relaunching the app does not touch it.

### "Enable automatically after reboot" home card

A live checklist above the wireless-debugging card — each row reads real state and carries its own
action, placed there by an order migration so it lands above that card on existing installs too.

- Notifications (a hard gate: the pairing screen does not even start its service without them).
- One recorded ADB connection — the boot path does nothing unless the launch mode was recorded, which
  only happens when the app *sees* the service running.
- `WRITE_SECURE_SETTINGS` granted **through the running server**, no PC needed; the copyable adb
  command remains as the fallback.
- Start-on-boot, deep-linked to the real switch and flashed on arrival. The row is honest that the boot
  receiver is already enabled in the manifest, so the switch is not the gate it appears to be.
- Battery-optimisation exemption.
- OEM launch manager, with an **Open** button wherever the ROM permits it — decided by a capability
  test (resolves, exported, and any guarding permission held), not a brand check. On EMUI, where the
  screen is guarded by a `signature|privileged` permission that no app can hold and that Shizuku's
  shell is refused as well, it names the exact path instead of offering a button that cannot work.

Below a hairline, a **Device Owner** section: granted via `dpm` through the running server with the
real refusal text surfaced, and the removal path always visible — behind a warning that re-granting
requires a device with no accounts at all and can dead-end in a factory reset, with Cancel in the
positive slot and "Clear anyway" in red.

### Fixes

- The Device Owner command in the diagnostics panel used the `pkg/.Receiver` shorthand, which expands
  against the **applicationId** and named a class that does not exist — a copy button for a command
  that could only fail. Now derived from the class.
- The settings deep-link "flash" filled the row with `colorPrimaryContainer`, which in this theme is
  the same pure black the rows sit on — a no-op dressed as a highlight. It now outlines in the accent.
- New home cards were appended to the saved card order, so they landed at the bottom on existing
  installs regardless of where they belong. Missing ids are now inserted at their proper position.
- The authorisation prompt shown when a third-party app asks for access had **no border** — it is an
  Activity-owned `AlertDialog`, which the `DialogFragment` styling hook never sees. Same for the
  fake-ADB pairing prompt.
- Both bottom sheets came up borderless; a sheet draws from its container view rather than the dialog
  window, so it needs its own treatment (black fill, accent stroke, top corners only). The Plus help
  sheet's inner card had `strokeWidth = 0` over a `surfaceVariant` fill — genuinely invisible here, now
  minor-tier bordered.
- Dialog buttons now carry visible borders, with a red destructive variant.
- The Device Owner clear/setup logic moved into a shared helper so the settings screen and the home
  card cannot drift apart on the one path whose failure costs a factory reset.

### Packaging

The `api` submodule now points at **`ShiroiKuma0/ShizukuPlus-API`** (branch `custom`) instead of
upstream's read-only repo, so server-side fixes living there are committable and survive a clone.

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
