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
 * Upstream's [af.shizuku.manager.receiver.BinderRequestReceiver] asks for consent on *every*
 * request, and it is right to: `REQUEST_BINDER` is a plain broadcast, `Binder.getCallingUid()` is
 * meaningless in `onReceive()` for one, and the action is the public, unauthenticated part of the
 * client API that any installed app can send. Upstream removed a fast path that trusted the
 * intent's own `callingPackage` / `callingUid` extras (`c7c9f6c8`), because any app could name an
 * already-authorized package, supply its *own* callback binder, and be handed the live
 * full-privilege binder with no user interaction.
 *
 * This fork previously papered over the per-command prompt with a global "shell consent granted"
 * flag, which was **worse** — once set it handed the binder to any app that could broadcast, with
 * no identification at all. Both are gone.
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
 *   granted(uid)? --yes--> deliver, NO prompt
 *                 --no --> upstream's consent notification, exactly as today
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
 * **Failure always falls back to asking.** Any failure — the challenge transact throwing, the
 * reply not arriving inside [CHALLENGE_TIMEOUT_MS], a mismatched nonce, or simply no grant yet —
 * re-broadcasts the public action so upstream's receiver posts its consent notification. The worst
 * case is the behaviour without this class, never anything weaker.
 *
 * Deliberately a *separate* receiver on a *separate* action: it keeps
 * [af.shizuku.manager.receiver.BinderRequestReceiver] byte-identical to upstream, so the
 * security-critical file upstream actively develops stays conflict-free on every rebase, and the
 * consent-notification code is reused rather than duplicated.
 */
class VerifiedBinderRequestReceiver : BroadcastReceiver() {

    companion object {
        /** Suffix appended to the applicationId. Sent only by our own [ShizukuShellLoader]. */
        const val ACTION_SUFFIX = ".intent.action.REQUEST_BINDER_VERIFIED"

        /** Upstream's public action, used for the always-ask fallback. */
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
         * costs a consent prompt, not a failure.
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
                // to try it before the challenge; the challenge is what makes a remembered consent
                // actually count; the public consent flow is the backstop.
                if (deliverIfTokenValid(context, intent, callbackBinder)) return@launch
                val verifiedUid = challengeForUid(callbackBinder)
                if (verifiedUid == null || !deliverIfGranted(context, callbackBinder, verifiedUid)) {
                    fallBackToConsentFlow(context, intent, verifiedUid)
                }
            } catch (e: Throwable) {
                LOGGER.w(e, "verified binder request failed, falling back to consent")
                runCatching { fallBackToConsentFlow(context, intent, null) }
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
            // The client is gone, or stayed frozen past the whole retry ladder. Prompting cannot
            // help it, and a consent notification for a dead client would only orphan a binder.
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
            // and never answers. Named explicitly because the symptom — a consent prompt on every
            // command — otherwise looks like the verified path is broken rather than absent.
            LOGGER.w(
                "identity challenge unanswered after ${CHALLENGE_TIMEOUT_MS}ms; " +
                    "shell client is probably running an out-of-date rish_shizuku.dex " +
                    "(re-copy rish and rish_shizuku.dex from the app), falling back to consent"
            )
            return null
        }
        // Keep identityBinder reachable until the reply has definitely been handled.
        identityBinder.hashCode()
        return verifiedUid.get().takeIf { it >= 0 }
    }

    /**
     * Re-broadcasts the request on upstream's public action so
     * [af.shizuku.manager.receiver.BinderRequestReceiver] runs its unmodified consent flow.
     *
     * Reusing it this way — rather than extracting or copying `postConsentNotification` — is what
     * keeps that file byte-identical to upstream. The live callback binder survives the hop the
     * same way it survived the original broadcast: `Bundle.putBinder` is carried by the binder
     * transaction, not serialised into the parcel body.
     *
     * When the challenge succeeded, [verifiedUid] **replaces** the caller-supplied identity extras:
     * the consent prompt names the caller from `callingPackage`, and any app can put any name
     * there, so an app holding no grant could otherwise raise a prompt reading "Termux is
     * requesting shell access" and phish the tap that authorizes it. Substituting a kernel-verified
     * identity makes that prompt truthful.
     *
     * When it failed, the original extras are forwarded **unchanged**. Dropping them instead looks
     * safer and is not: an attacker who wants the spoofable label simply sends the public action
     * directly to upstream's receiver, which never consults this class at all — so withholding them
     * here closes nothing, while it does destroy the only uid the consent flow has to store a grant
     * against. Without that uid [ShellConsentActionReceiver] cannot call
     * [af.shizuku.manager.authorization.AuthorizationManager.grant], so "Allow" remembers nothing
     * and every single command re-prompts — measured on-device, and strictly worse than upstream.
     */
    private fun fallBackToConsentFlow(context: Context, original: Intent, verifiedUid: Int?) {
        val intent = Intent(PUBLIC_ACTION)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        original.getBundleExtra("data")?.let { intent.putExtra("data", it) }

        if (verifiedUid != null) {
            intent.putExtra("callingUid", verifiedUid)
            packageNameFor(context, verifiedUid)
                .takeIf { it.isNotEmpty() }
                ?.let { intent.putExtra("callingPackage", it) }
        } else {
            original.getStringExtra("callingPackage")?.let { intent.putExtra("callingPackage", it) }
            original.getIntExtra("callingUid", -1)
                .takeIf { it >= 0 }
                ?.let { intent.putExtra("callingUid", it) }
        }

        // Must be forwarded, or a client holding a real SHIZUKU_TOKEN would lose upstream's
        // auth-token fast path and be prompted instead — the token check lives in that receiver and
        // is deliberately not duplicated here.
        original.getStringExtra("auth")?.let { intent.putExtra("auth", it) }
        context.sendBroadcast(intent)
    }
}
