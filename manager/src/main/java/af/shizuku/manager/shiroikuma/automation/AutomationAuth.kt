package af.shizuku.manager.shiroikuma.automation

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The gate for the 保存復元 automation contract — **contract v2**.
 *
 * ## What v2 changed, and why it had to
 *
 * v1 shipped this app **closed**: the master switch defaulted to false and every caller also had to
 * present a 48-character secret 白い熊 had pasted from this page into 自由作業盤's roster.
 *
 * That is the wrong shape for where this is going. **A pasted secret cannot survive a wipe**, and
 * the case the family now exists to serve is 白い熊 応用管理 restoring apps *and their data* onto a
 * clean phone, where nothing has been configured and nobody has pasted anything. A gate that only
 * works once the phone is already set up is no gate for setting the phone up.
 *
 * So the switch ships **ON**, the token is **opt-in**, and the identity check that actually matters
 * moved to the data door — see [AutomationCallers], which knows who is calling because a
 * `ContentProvider` is told by the framework and a broadcast never can be.
 *
 * ## Idempotent about the token — required, not a nicety
 *
 * **A token handed to an app that does not require one is IGNORED. It is never an error.** Tokens
 * live in task arguments and workspace variables that outlive the setting they were pasted for; a
 * caller still sending one — because it was configured last year, or because another app on the
 * batch does want one — must be served. Refusing it would turn "白い熊 turned a switch off" into
 * "half the batch mysteriously fails", which is exactly the friction the switch exists to remove.
 *
 * The token lives in its **own SharedPreferences file**, deliberately outside the export map, so it
 * can never travel inside a backup ZIP.
 */
object AutomationAuth {
    private const val PREFS = "shiroikuma_automation"
    private const val KEY_ENABLED = "automation_enabled"
    private const val KEY_REQUIRE_TOKEN = "automation_require_token"
    private const val KEY_TOKEN = "automation_token"
    private const val TOKEN_BYTES = 24

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Master switch. **Default true** (v1: false).
     *
     * It stays a switch rather than being removed because it is the only way to close this app off,
     * and a feature that can be turned on but never off is one 白い熊 cannot retreat from.
     */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** 「Use authorization token?」 — **default false**. New in v2. */
    fun requireToken(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUIRE_TOKEN, false)

    fun setRequireToken(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REQUIRE_TOKEN, value).apply()
    }

    /**
     * The **one** place both checks live. `null` = proceed; otherwise the exact `ERROR:` string to
     * answer with.
     *
     * One function rather than two checks written out at each entry point, because that is how
     * "disabled" and "bad token" drift apart across forty-two apps — and they are reported as
     * distinct errors on purpose, since they debug differently.
     */
    fun refuse(context: Context, candidate: String?): String? = when {
        !enabled(context) -> "ERROR:automation disabled"
        requireToken(context) && !isTokenValid(context, candidate) -> "ERROR:bad token"
        else -> null
    }

    /** The token, generated lazily on first read so the settings row always shows a value. */
    fun token(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = generate()
        p.edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    fun regenerate(context: Context): String {
        val fresh = generate()
        prefs(context).edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    /** Constant-time comparison — never `==` on a secret. Kept for when a token *is* required. */
    fun isTokenValid(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return MessageDigest.isEqual(candidate.toByteArray(), token(context).toByteArray())
    }

    /** `80922d8c…4c49a87c` — what the settings row shows. */
    fun abbreviated(context: Context): String {
        val t = token(context)
        return if (t.length <= 20) t else "${t.take(8)}…${t.takeLast(8)}"
    }

    private fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
