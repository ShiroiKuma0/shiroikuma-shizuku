package af.shizuku.manager.shiroikuma

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast

/**
 * The house transient flash: **black background, yellow text, yellow rounded frame** — replacing the
 * system's default grey/white toast pill. Every `Toast` in the app routes through here so the look is
 * consistent with the dialogs and the rest of the UI.
 *
 * Implemented with a custom Toast view. `Toast.setView` is deprecated on API 30+ and custom toasts
 * shown **from the background** are blocked — but every flash in this app accompanies a user action
 * on a visible screen, so the foreground path is the only one that matters. If a background caller
 * ever appears it degrades to the system text toast rather than failing.
 *
 * Colours come from the 白い熊 雫 store, so the flash follows the accent/background knobs like
 * everything else.
 */
object ShiroikumaToast {

    /**
     * [context] is nullable because a Fragment's `context` is, and upstream's
     * `Toast.makeText(context, …)` only accepted it through Java platform types — it would have
     * thrown at runtime if the fragment were detached. A detached caller has no window to flash in,
     * so this quietly does nothing instead.
     */
    fun show(context: Context?, message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        val app = context?.applicationContext ?: return
        val d = app.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val accent = ShiroikumaUiPrefs.getInt(app, ShiroikumaUiPrefs.KEY_COLOR_ACCENT)
        val fill = ShiroikumaUiPrefs.getInt(app, ShiroikumaUiPrefs.KEY_COLOR_BACKGROUND)
        val border = ShiroikumaUiPrefs.getInt(app, ShiroikumaUiPrefs.KEY_BORDER_WIDTH).coerceAtLeast(1)

        val view = TextView(app).apply {
            text = message
            setTextColor(accent)
            typeface = ShiroikumaFonts.current(app)
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                ShiroikumaUiPrefs.getInt(app, ShiroikumaUiPrefs.KEY_TEXT_SIZE).toFloat()
            )
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(fill)
                setStroke(dp(border), accent)
            }
        }

        runCatching {
            @Suppress("DEPRECATION")
            Toast(app).apply {
                this.duration = duration
                @Suppress("DEPRECATION")
                this.view = view
            }.show()
        }.onFailure {
            // Background caller, or an OEM that refuses custom toast views — better a plain flash
            // than none at all. This MUST stay the raw system toast: routing it back through
            // ShiroikumaToast.show would recurse forever.
            @Suppress("ShiroikumaToastBypass")
            Toast.makeText(app, message, duration).show()
        }
    }

    fun show(context: Context?, resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        show(context, (context ?: return).getString(resId), duration)
    }
}
