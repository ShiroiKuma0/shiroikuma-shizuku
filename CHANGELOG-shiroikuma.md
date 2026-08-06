# 白い熊 雫 — fork changelog

Fork-only notes. Upstream's own release notes live in `CHANGES.md` — never fold fork notes into it.

Versions are `<upstream version>.<upstream base date>.g<sha>+<our build number>`; the `+N` resets to
1 on each upstream sync. Builds up to `13.6.0.r2195+5` used the older `<upstream version>+<N>` form.

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
