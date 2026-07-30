package af.shizuku.manager.shiroikuma

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import af.shizuku.manager.R
import af.shizuku.manager.shiroikuma.showHouse

/**
 * Black-yellow dialogs with a **yellow border** — the house dialog look, used by every dialog this
 * layer raises (colour picker, font picker, export/import results).
 *
 * The border is applied to the dialog window's own background rather than through a style, because
 * `AlertDialog` builds its background from the theme before the content view exists; setting it
 * after `show()` is the only reliable way to get a stroke around the whole thing.
 */
object ShiroikumaDialogs {

    /**
     * Style **every** `DialogFragment` in the app automatically.
     *
     * There is no theme-level way to do this: Material's `MaterialAlertDialog` styleable exposes
     * only `backgroundTint` and the four insets — **no stroke** — and `MaterialAlertDialogBuilder`
     * installs its own `MaterialShapeDrawable` window background during `show()`, overriding the
     * bordered `android:windowBackground` our dialog theme sets. So the border can only be applied
     * after the dialog exists.
     *
     * The app raises dialogs from ~40 files, so rather than convert every call site this hooks the
     * fragment lifecycle once and styles any `DialogFragment` as it starts — the wireless-debugging
     * and pairing dialogs, the changelog, the WADB prompts, and anything added later, for free.
     *
     * Call once from `ShizukuApplication.onCreate`.
     */
    fun installGlobalStyling(app: android.app.Application) {
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, bundle: android.os.Bundle?) {
                val fm = (activity as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager
                    ?: return
                fm.registerFragmentLifecycleCallbacks(
                    object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                        override fun onFragmentStarted(
                            fm: androidx.fragment.app.FragmentManager,
                            f: androidx.fragment.app.Fragment
                        ) {
                            // Also re-applied on resume: some fragments swap the dialog's content
                            // (and its background) in their own onStart.
                            (f as? androidx.fragment.app.DialogFragment)?.dialog
                                ?.let { runCatching { style(it) } }
                        }

                        override fun onFragmentResumed(
                            fm: androidx.fragment.app.FragmentManager,
                            f: androidx.fragment.app.Fragment
                        ) {
                            (f as? androidx.fragment.app.DialogFragment)?.dialog
                                ?.let { runCatching { style(it) } }
                        }
                    },
                    /* recursive = */ true
                )
            }

            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, out: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    /**
     * Give an already-shown dialog the house black fill + yellow border, and tint its buttons.
     *
     * Must run **after** the dialog is shown. `MaterialAlertDialogBuilder` installs its own
     * `MaterialShapeDrawable` window background (filled from `colorSurface`) during `show()`, which
     * overrides `android:windowBackground` from the theme — so a dialog raised through that builder
     * has a black fill and **no border** until this replaces it.
     *
     * The background is wrapped in an [InsetDrawable] because replacing Material's drawable also
     * drops the inset it carried; without it the dialog would bleed to the screen edges and the
     * border would sit flush against them.
     */
    fun style(dialog: Dialog) {
        val context = dialog.context
        val density = context.resources.displayMetrics.density
        val border = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_BORDER_WIDTH).coerceAtLeast(1)
        val radius = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_CORNER_RADIUS)
        val accent = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_COLOR_ACCENT)
        val fill = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_COLOR_BACKGROUND)

        val shape = GradientDrawable().apply {
            setColor(fill)
            setStroke((border * density).toInt(), accent)
            cornerRadius = radius * density
        }
        val inset = (16 * density).toInt()
        dialog.window?.setBackgroundDrawable(InsetDrawable(shape, inset, inset, inset, inset))
        (dialog as? AlertDialog)?.let { d ->
            listOf(
                DialogInterfaceButton.POSITIVE, DialogInterfaceButton.NEGATIVE, DialogInterfaceButton.NEUTRAL
            ).forEach { which ->
                d.getButton(which)?.let { button ->
                    // A button marked destructive keeps its red across re-styling: style() runs again
                    // on every DialogFragment start and resume, so colouring the button at the call
                    // site alone would be silently reverted on the next pass.
                    val destructive = button.getTag(DESTRUCTIVE_TAG) == true
                    styleButton(button, if (destructive) ShiroikumaUiPrefs.RED else accent, border, radius, density)
                }
            }
            d.findViewById<TextView>(android.R.id.message)?.setTextColor(
                ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_COLOR_TEXT)
            )
        }
    }

    /**
     * The house look for a **bottom sheet**, which [style] cannot give it.
     *
     * A sheet does not draw itself from the dialog window's background — that is just the dim scrim
     * behind it — so the inset, rounded, bordered drawable [style] installs would either do nothing
     * or box the sheet in at the wrong place. The surface that matters is the sheet container view
     * itself, and it needs its own treatment: black fill, accent stroke, and **top corners only**,
     * because a sheet is anchored flush to the bottom edge and rounding the bottom would carve a
     * notch out of the screen edge.
     *
     * Call after `show()`, like [style] — the container view does not exist before then.
     */
    fun styleSheet(dialog: com.google.android.material.bottomsheet.BottomSheetDialog) {
        val context = dialog.context
        val density = context.resources.displayMetrics.density
        val accent = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_COLOR_ACCENT)
        val fill = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_COLOR_BACKGROUND)
        val border = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_BORDER_WIDTH).coerceAtLeast(1)
        val radius = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_CORNER_RADIUS) * density

        // Material fills the window with an opaque surface behind the sheet; leaving it in place would
        // show as a black band under the border.
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )

        val sheet = dialog.findViewById<android.view.View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        sheet.background = GradientDrawable().apply {
            setColor(fill)
            setStroke((border * density).toInt(), accent)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
    }

    private val DESTRUCTIVE_TAG = "shiroikuma_destructive".hashCode()

    /**
     * Mark one of a dialog's buttons as the destructive choice: red text, red border.
     *
     * Call after `showHouse()`. Pair it with putting the destructive action in the **negative** slot
     * and Cancel in the positive one — Android has no OS-level default button, so position plus
     * colour is the whole of what makes the safe option the obvious one.
     */
    fun markDestructive(dialog: Dialog, which: Int) {
        val button = (dialog as? AlertDialog)?.getButton(which) ?: return
        button.setTag(DESTRUCTIVE_TAG, true)
        style(dialog)
    }

    /** Every dialog button gets a visible border — a bare text button on black reads as a label,
     *  not something tappable, and in this theme there is no fill to tell them apart either. */
    private fun styleButton(
        button: android.widget.Button,
        color: Int,
        border: Int,
        radius: Int,
        density: Float
    ) {
        button.setTextColor(color)
        button.background = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke((border * density).toInt(), color)
            cornerRadius = radius * density
        }
        val padH = (14 * density).toInt()
        val padV = (6 * density).toInt()
        button.setPadding(padH, padV, padH, padV)
        button.minWidth = 0
        // Material's button bar packs the buttons flush together; without a gap the two borders
        // touch and read as one wide box.
        (button.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.marginStart = (6 * density).toInt()
            button.layoutParams = lp
        }
    }

    /**
     * The house OK dialog — black, yellow-bordered, single OK button.
     * [onOk] fires when OK is pressed **or** the dialog is dismissed any other way, so callers that
     * close a chain behind it (see the export flow) always get their callback exactly once.
     */
    fun ok(
        context: Context,
        title: String,
        message: String,
        okLabel: String? = null,
        onOk: (() -> Unit)? = null
    ) {
        var fired = false
        fun once() {
            if (!fired) {
                fired = true
                onOk?.invoke()
            }
        }
        AlertDialog.Builder(context, R.style.Theme_Shiroikuma_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(okLabel ?: context.getString(android.R.string.ok)) { _, _ -> once() }
            .setOnDismissListener { once() }
            .show()
            .also { style(it) }
    }

    /** A two-button house dialog (used by the import result: "Restart now" / "Later"). */
    fun choice(
        context: Context,
        title: String,
        message: String,
        positive: String,
        negative: String,
        onPositive: () -> Unit,
        onNegative: () -> Unit
    ) {
        var handled = false
        AlertDialog.Builder(context, R.style.Theme_Shiroikuma_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(positive) { _, _ -> if (!handled) { handled = true; onPositive() } }
            .setNegativeButton(negative) { _, _ -> if (!handled) { handled = true; onNegative() } }
            .show()
            .also { style(it) }
    }

    private object DialogInterfaceButton {
        const val POSITIVE = android.content.DialogInterface.BUTTON_POSITIVE
        const val NEGATIVE = android.content.DialogInterface.BUTTON_NEGATIVE
        const val NEUTRAL = android.content.DialogInterface.BUTTON_NEUTRAL
    }
}

/**
 * Show a Material dialog with the house look — black fill, **yellow border**.
 *
 * Use this instead of `.show()` for any dialog raised **directly from an Activity or a
 * non-`BaseSettingsFragment`**. `DialogFragment`s are covered automatically by
 * [ShiroikumaDialogs.installGlobalStyling], and `BaseSettingsFragment.showDialog()` styles its own;
 * everything else has no hook, because `MaterialAlertDialogBuilder` overwrites the themed window
 * background during `show()` and Material exposes no stroke attribute to set it from a theme.
 */
fun com.google.android.material.dialog.MaterialAlertDialogBuilder.showHouse():
    androidx.appcompat.app.AlertDialog = show().also { ShiroikumaDialogs.style(it) }
