package af.shizuku.manager.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.google.android.material.snackbar.Snackbar
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.R
import af.shizuku.manager.shiroikuma.ShiroikumaUiPrefs

object SnackbarHelper {

    private var snackbar: Snackbar? = null

    fun show(
        context: Context,
        view: View,
        msg: String,
        duration: Int = Snackbar.LENGTH_SHORT,
        actionText: String? = null,
        action: (() -> Unit)? = null,
        onDismiss: ((event: Int) -> Unit)? = null
    ) {
        dismiss() // Dismiss any existing snackbar
        val newSnackbar = Snackbar.make(view, msg, duration).setDuration(duration)
        if (action != null) {
            newSnackbar.setAction(actionText ?: context.getString(android.R.string.ok)) { action() }
        }
        if (onDismiss != null) {
            newSnackbar.addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    onDismiss(event)
                }
            })
        }
        ThemeHelper.applySnackbarTheme(context, newSnackbar)
        applyHouseBorder(context, newSnackbar)
        newSnackbar.show()
        snackbar = newSnackbar
    }

    /**
     * The house border, major tier — a snackbar is a prompt in its own right, not a list row.
     *
     * [ThemeHelper.applySnackbarTheme] fills it with `colorPrimaryContainer`, and in this theme every
     * container role is the same pure black as the page behind it. With nothing but that fill the bar
     * has no edge at all: a black slab on a black screen, which is the standing "every `surface*`
     * container MUST carry a visible border" trap. Mirrors `ShiroikumaDialogs.style` — same knobs,
     * same `coerceAtLeast(1)`, so a snackbar can never end up borderless.
     */
    private fun applyHouseBorder(context: Context, snackbar: Snackbar) {
        val p = ShiroikumaUiPrefs
        val density = context.resources.displayMetrics.density
        val border = p.getInt(context, p.KEY_BORDER_WIDTH).coerceAtLeast(1)
        // The tint list would recolour whatever drawable we install, undoing the border with it.
        snackbar.view.backgroundTintList = null
        snackbar.view.background = GradientDrawable().apply {
            setColor(p.getInt(context, p.KEY_COLOR_BACKGROUND))
            cornerRadius = p.getInt(context, p.KEY_CORNER_RADIUS) * density
            setStroke((border * density).toInt(), p.getInt(context, p.KEY_COLOR_BORDER))
        }
    }

    fun dismiss() {
        snackbar?.dismiss()
        snackbar = null
    }

}
