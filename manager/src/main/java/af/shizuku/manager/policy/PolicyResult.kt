package af.shizuku.manager.policy

import android.os.Bundle

/**
 * The answer to one policy operation: did the platform actually do it, and if not, *why*.
 *
 * The reason is never flattened to a generic string. 白い熊 応用管理's Snooping page refuses to
 * record a decision a write did not achieve, and it has to tell the user what went wrong — a bare
 * `false` makes that impossible, and "failed" makes it worse by looking like an answer.
 */
data class PolicyResult(val ok: Boolean, val error: String? = null) {

    fun toBundle(extra: Bundle? = null): Bundle = Bundle().apply {
        extra?.let { putAll(it) }
        putBoolean(KEY_OK, ok)
        error?.let { putString(KEY_ERROR, it) }
    }

    companion object {
        const val KEY_OK = "ok"
        const val KEY_ERROR = "error"

        val OK = PolicyResult(true)

        fun fail(reason: String) = PolicyResult(false, reason)

        fun fail(e: Throwable) =
            PolicyResult(false, "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
    }
}
