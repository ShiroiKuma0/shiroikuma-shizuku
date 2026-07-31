# 白い熊 雫 — fork changelog

Fork-only notes. Upstream's own release notes live in `CHANGES.md` — never fold fork notes into it.

Versions are `<upstream version>+<our build number>`; the `+N` resets to 1 on each upstream sync.

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
