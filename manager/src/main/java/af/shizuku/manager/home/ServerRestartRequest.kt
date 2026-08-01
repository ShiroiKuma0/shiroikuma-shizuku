package af.shizuku.manager.home

/**
 * A way for code outside the status card to ask for a server restart, without owning a second copy
 * of how one is performed.
 *
 * `ServerStatusViewHolder.startOrRestart` is the only implementation, and it must stay the only
 * one. Without root the sole shell this app can reach is the one the **running server** lends it,
 * so a restart is deliberately *not* stop-then-start: stopping first destroys exactly the privilege
 * needed to start again, leaving wireless debugging as the only way back. That routine also carries
 * the local-TCP-adb fallback, the "run `adb tcpip 5555`" dead-end dialog, and the in-flight button
 * state. A caller that re-implemented any of it would eventually get the ordering wrong, and the
 * failure mode is a phone with no route back to a privileged server.
 *
 * So the card publishes its own action here while it is bound, and the version-skew dialog invokes
 * it. Null when no card is bound — the caller falls back to telling the user where the button is.
 *
 * Cleared in `HomeActivity.onDestroy`: the lambda closes over the card's context.
 */
object ServerRestartRequest {

    @Volatile
    var request: (() -> Unit)? = null

    fun invokeOrNull(): Boolean {
        val r = request ?: return false
        r()
        return true
    }
}
