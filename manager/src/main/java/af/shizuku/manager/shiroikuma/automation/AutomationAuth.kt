package af.shizuku.manager.shiroikuma.automation

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The gate for the 保存復元 automation contract: a master switch (**default OFF**) and a shared
 * secret that 白い熊 pastes into 白い熊 自由作業盤's roster.
 *
 * The receivers carry **no `android:permission`** — the caller cannot hold one — so this token *is*
 * the gate. Both the switch and the token are checked on every request, before any work.
 *
 * The token lives in its **own SharedPreferences file**, deliberately outside the export map, so it
 * can never travel inside a backup ZIP.
 */
object AutomationAuth {
    private const val PREFS = "shiroikuma_automation"
    private const val KEY_ENABLED = "automation_enabled"
    private const val KEY_TOKEN = "automation_token"
    private const val TOKEN_BYTES = 24

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Master switch. Default **false** — nothing is reachable until 白い熊 turns it on. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
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

    /** Constant-time comparison — never `==` on a secret. */
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
