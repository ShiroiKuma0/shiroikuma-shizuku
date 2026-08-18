package af.shizuku.manager.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.shiroikuma.RishSetup
import af.shizuku.manager.utils.IntentCrypto
import af.shizuku.manager.utils.Logger.LOGGER
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fork-owned entry point for shell clients (`rish`, `adb shell`) that restores **unprompted**
 * access without trusting anything the caller claims about itself.
 *
 * ## Why this exists at all
 *
 * `REQUEST_BINDER` is a plain broadcast: `Binder.getCallingUid()` is meaningless in `onReceive()`
 * for one, and the action is the public, unauthenticated part of the client API that any installed
 * app can send. Upstream removed a fast path that trusted the intent's own `callingPackage` /
 * `callingUid` extras (`c7c9f6c8`), because any app could name an already-authorized package,
 * supply its *own* callback binder, and be handed the live full-privilege binder with no user
 * interaction. It then gated every request on a consent notification instead, which cost a prompt
 * per shell command. This class answered that with an identity check the caller cannot fake.
 *
 * This fork previously papered over the per-command prompt with a global "shell consent granted"
 * flag, which was **worse** — once set it handed the binder to any app that could broadcast, with
 * no identification at all. Both are gone.
 *
 * ## What changed upstream at `32382e89`, and what it leaves this class doing
 *
 * Upstream now delivers the binder **unconditionally** and makes no authorization decision in
 * [af.shizuku.manager.receiver.BinderRequestReceiver] at all: receiving a bare binder reference is
 * not the privilege boundary, because every privileged AIDL method is separately gated by
 * `enforceCallingPermission()`, reading the kernel-supplied uid of the real transaction the caller
 * makes once attached. The one consent prompt now happens downstream — `attachApplication()` →
 * `checkSelfPermission()` → `requestPermission()` → `showPermissionConfirmation()` →
 * `RequestPermissionActivity` — already keyed on that verified uid, and already skipped once the
 * uid is granted. The notification-consent machinery this class used to fall back into
 * (`ShellConsentActivity`, `ShellConsentActionReceiver`, `PendingConsentStore`) was deleted with it.
 *
 * So the tiers below no longer decide *whether* the binder is handed over — upstream would hand it
 * over regardless. What they still buy the fork is the **evidence** [RishSetup] needs (only a
 * token-authenticated delivery proves `rish` is set up prompt-free; the terminal's own directory
 * cannot be read without root), a truthful [ActivityLogManager] entry naming a kernel-verified
 * caller rather than a self-declared one, and a delivery path that skips the round trip entirely
 * when the token matches.
 *
 * ## The mechanism: make the caller transact into US
 *
 * `rish`'s callback binder is a real [Binder] living in `rish`'s own process. So instead of asking
 * the caller who it is, we hand it a fresh [Binder] of ours plus a single-use nonce and require it
 * to call back. On that **inbound** transaction `Binder.getCallingUid()` is supplied by the kernel
 * and cannot be forged — the same property that lets `PolicyProvider` gate on the calling uid with
 * no shared token.
 *
 * ```
 * rish  --broadcast-->  this receiver          (extras still untrusted, ignored for identity)
 * us    --code 2   -->  rish.receiverBinder    [identityBinder, nonce]
 * rish  --code 1   -->  our identityBinder     [nonce]
 *                        |
 *       Binder.getCallingUid() == the real uid of the rish process
 *                        |
 *   granted(uid)? --yes--> deliver here, logged against the verified uid
 *                 --no --> hand off to upstream's public action
 * ```
 *
 * **Why this is not the hole upstream closed.** A malicious app can absolutely answer the
 * challenge — but the uid the kernel reports is then *its own*, so it can only ever satisfy the
 * check with a grant it already holds itself. It cannot borrow Termux's grant, or any other
 * package's, which is precisely what the removed fast path allowed. The nonce is single-use and is
 * only ever sent to the binder that made the request, so a stale or third-party binder cannot
 * answer on someone else's behalf.
 *
 * **The grant is keyed on the uid, and that is deliberate.** Past pre-v11,
 * [AuthorizationManager.granted] and [AuthorizationManager.grant] both read and write
 * `Shizuku.getFlagsForUid` / `updateFlagsForUid` — the package name is consulted only on pre-v11.
 * A kernel-verified uid is therefore exactly the right key, and it is why an anonymous shell
 * client (`rish` in Termux, which may report no package at all) is handled rather than skipped.
 *
 * **Failure always falls back to upstream.** Any failure — the challenge transact throwing, the
 * reply not arriving inside [CHALLENGE_TIMEOUT_MS], a mismatched nonce, or simply no grant yet —
 * re-broadcasts the public action and lets upstream's receiver handle the request on its own
 * terms. The worst case is exactly the behaviour without this class, never anything weaker.
 *
 * Deliberately a *separate* receiver on a *separate* action: it keeps
 * [af.shizuku.manager.receiver.BinderRequestReceiver] byte-identical to upstream, so the
 * security-critical file upstream actively develops stays conflict-free on every rebase — which is
 * exactly what let `32382e89`, a full rewrite of that file, land here without a single conflict.
 */
class VerifiedBinderRequestReceiver : BroadcastReceiver() {

    companion object {
        /** Suffix appended to the applicationId. Sent only by our own [ShizukuShellLoader]. */
        const val ACTION_SUFFIX = ".intent.action.REQUEST_BINDER_VERIFIED"

        /** Upstream's public action, used for the hand-off fallback. */
        private const val PUBLIC_ACTION = "rikka.shizuku.intent.action.REQUEST_BINDER"

        /**
         * Challenge sent to the client's callback binder. `FIRST_CALL_TRANSACTION` (1) is already
         * taken by the binder handoff itself, so the challenge is the next code up.
         * `ShizukuShellLoader.receiverBinder.onTransact` must agree with this exactly.
         */
        private const val TRANSACTION_IDENTITY_CHALLENGE = IBinder.FIRST_CALL_TRANSACTION + 1

        /** Code the client uses to answer, on the binder we hand it in the challenge. */
        private const val TRANSACTION_IDENTITY_REPLY = IBinder.FIRST_CALL_TRANSACTION

        /**
         * The client is blocked waiting for its binder, so a genuine reply is a single round trip
         * on a binder thread — milliseconds. This only bounds the pathological case; exceeding it
         * costs the hand-off to upstream's own path, not a failure.
         */
        private const val CHALLENGE_TIMEOUT_MS = 3_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "${context.packageName}$ACTION_SUFFIX") return

        // Nothing to reply to means nothing to authorize — same guard upstream applies.
        val callbackBinder = intent.getBundleExtra("data")?.getBinder("binder") ?: return

        // The challenge waits on a latch and deliverBinder() can sleep on its freeze-retry ladder;
        // neither belongs on the main thread inside a broadcast window.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Three tiers, cheapest first. The token needs no round trip, so it costs nothing
                // to try it before the challenge; the challenge is what lets the delivery be
                // logged against an identity the caller could not have invented; upstream's own
                // public action is the backstop.
                if (deliverIfTokenValid(context, intent, callbackBinder)) return@launch
                val verifiedUid = challengeForUid(callbackBinder)
                if (verifiedUid == null || !deliverIfGranted(context, callbackBinder, verifiedUid)) {
                    handOffToPublicAction(context, intent)
                }
            } catch (e: Throwable) {
                LOGGER.w(e, "verified binder request failed, handing off to upstream")
                runCatching { handOffToPublicAction(context, intent) }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Tier 1 — upstream's auth token, re-checked here so a token-carrying request does not have to
     * be routed to the public action just to be validated. Routing it there was what stopped the
     * identity challenge from ever running.
     *
     * Deliberately mirrors [af.shizuku.manager.receiver.BinderRequestReceiver]'s check, including
     * the constant-time compare: this gates handing over the live binder.
     */
    private fun deliverIfTokenValid(context: Context, intent: Intent, callbackBinder: IBinder): Boolean {
        val raw = intent.getStringExtra("auth")
        if (raw == null) {
            return false
        }
        val decrypted = IntentCrypto.decrypt(raw)
        if (decrypted == null) {
            // The token is mangled or was produced by a different keystore key — never the token
            // itself in the log, only the stage that failed and its length.
            return false
        }
        val expected = ShizukuSettings.getAuthToken()
        val match = MessageDigest.isEqual(decrypted.toByteArray(), expected.toByteArray())
        if (!match) return false

        if (!ShellBinderRequestHandler.deliverBinder(context, callbackBinder)) {
            return true
        }
        // Also recorded here, not only in ShellBinderRequestHandler: that records the public-action
        // path, and this path never goes through it. Without this the home card would stay red
        // while rish was in fact running prompt-free.
        RishSetup.recordTokenAuth(expected)
        ActivityLogManager.log("Shell", "", "Shell: binder delivered (auth token)")
        return true
    }

    /** @return true when the request is fully handled; false to fall back to asking. */
    private fun deliverIfGranted(context: Context, callbackBinder: IBinder, uid: Int): Boolean {
        val packageName = packageNameFor(context, uid)
        val granted = AuthorizationManager.granted(packageName, uid)
        if (!granted) return false

        if (!ShellBinderRequestHandler.deliverBinder(context, callbackBinder)) {
            // The client is gone, or stayed frozen past the whole retry ladder. Reported as handled
            // regardless: re-broadcasting for a dead client would only orphan another binder.
            LOGGER.w("verified caller uid $uid was authorized but delivery failed")
            return true
        }

        val label = packageName.takeIf { it.isNotEmpty() }?.let { pkg ->
            try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) { pkg }
        } ?: "Shell (uid $uid)"
        ActivityLogManager.log(label, packageName, "Shell: binder delivered (verified uid)")
        return true
    }

    /**
     * Resolved from a **verified** uid, never from an intent extra. Empty when the uid owns no
     * package the platform will name, which is fine: past pre-v11 the grant is keyed on the uid and
     * the package name is not consulted at all.
     */
    private fun packageNameFor(context: Context, uid: Int): String = try {
        context.packageManager.getPackagesForUid(uid)?.firstOrNull()
    } catch (_: Exception) {
        null
    } ?: ""

    /**
     * Hands [callbackBinder] a fresh binder of ours plus a nonce and waits for it to transact back,
     * so the uid we act on comes from the kernel rather than from the caller.
     *
     * @return the caller's real uid, or null if it did not answer correctly in time.
     */
    private fun challengeForUid(callbackBinder: IBinder): Int? {
        val nonce = ByteArray(16)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }

        val latch = CountDownLatch(1)
        val verifiedUid = AtomicInteger(-1)

        // Held in a local until after await() below: a Binder that becomes unreachable can be
        // collected, and the reply would then land on nothing.
        val identityBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != TRANSACTION_IDENTITY_REPLY) {
                    return super.onTransact(code, data, reply, flags)
                }
                // FIRST, before anything that could clear the calling identity. This single line is
                // the entire security property of this class.
                val callingUid = Binder.getCallingUid()
                val echoed = try { data.readString() } catch (_: Exception) { null }
                // Constant-time compare is unnecessary here: the nonce is single-use, never
                // reachable by a third party, and a wrong answer only costs a consent prompt.
                if (echoed == nonce) verifiedUid.set(callingUid)
                latch.countDown()
                return true
            }
        }

        val data = Parcel.obtain()
        try {
            data.writeStrongBinder(identityBinder)
            data.writeString(nonce)
            // ONEWAY: the reply comes back as its own inbound transaction, so waiting here for the
            // client to finish would be a needless round-trip stall (and, if the client were ever
            // to answer synchronously, a deadlock).
            callbackBinder.transact(
                TRANSACTION_IDENTITY_CHALLENGE, data, null, IBinder.FLAG_ONEWAY
            )
        } catch (e: Exception) {
            // An older client that does not implement the challenge lands here, as does a dead one.
            LOGGER.w(e, "identity challenge could not be sent")
            return null
        } finally {
            data.recycle()
        }

        if (!latch.await(CHALLENGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            // Overwhelmingly the stale-dex case: rish loads rish_shizuku.dex from a copy beside the
            // rish script, so a loader predating the challenge simply ignores transaction code 2
            // and never answers. Named explicitly because the symptom — this path never confirming
            // an identity — otherwise looks like the verified path is broken rather than absent.
            LOGGER.w(
                "identity challenge unanswered after ${CHALLENGE_TIMEOUT_MS}ms; " +
                    "shell client is probably running an out-of-date rish_shizuku.dex " +
                    "(re-copy rish and rish_shizuku.dex from the app), handing off to upstream"
            )
            return null
        }
        // Keep identityBinder reachable until the reply has definitely been handled.
        identityBinder.hashCode()
        return verifiedUid.get().takeIf { it >= 0 }
    }

    /**
     * Re-broadcasts the request on upstream's public action so
     * [af.shizuku.manager.receiver.BinderRequestReceiver] handles it on its own terms.
     *
     * Handing off this way — rather than copying its logic here — is what keeps that file
     * byte-identical to upstream, and it is why `32382e89` rewrote it end to end without
     * conflicting with anything in this fork. The live callback binder survives the hop the same
     * way it survived the original broadcast: `Bundle.putBinder` is carried by the binder
     * transaction, not serialised into the parcel body.
     *
     * **The identity extras are deliberately not forwarded.** They used to be: when the challenge
     * succeeded the verified uid replaced them, so the consent prompt named a caller that could not
     * lie about itself, and when it failed the caller's own extras went through unchanged because
     * the consent flow needed *some* uid to store a grant against. Since `32382e89` there is no
     * prompt on this path and no grant written from it — upstream reads only `auth` and the callback
     * binder, and decides nothing here — so passing them on would be theatre. Consent moved to
     * `attachApplication()` → `RequestPermissionActivity`, which reads the uid from a real
     * transaction and never sees this broadcast at all.
     *
     * The `auth` token still is forwarded: without it a client holding a real `SHIZUKU_TOKEN` would
     * lose upstream's own fast path, and that check lives in that receiver by design.
     */
    private fun handOffToPublicAction(context: Context, original: Intent) {
        val intent = Intent(PUBLIC_ACTION)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        original.getBundleExtra("data")?.let { intent.putExtra("data", it) }
        original.getStringExtra("auth")?.let { intent.putExtra("auth", it) }
        context.sendBroadcast(intent)
    }
}
