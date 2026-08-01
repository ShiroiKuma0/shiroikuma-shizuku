package af.shizuku.manager.home

import android.view.animation.OvershootInterpolator
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.HomeItemContainerBinding

object HomeEditMode {
    var isActive: Boolean = false
        private set

    var onChanged: (() -> Unit)? = null
    var startDragCallback: ((RecyclerView.ViewHolder) -> Unit)? = null
    var removeCardCallback: ((Long) -> Unit)? = null

    fun enter() {
        if (!isActive) {
            isActive = true
            io.sentry.Sentry.addBreadcrumb("HomeEditMode: enter()")
            onChanged?.invoke()
        }
    }

    fun exit() {
        if (isActive) {
            isActive = false
            onChanged?.invoke()
        }
    }

    fun toggle() {
        if (isActive) exit() else enter()
    }

    /** Toggle drag handle / remove button visibility AND reserve end-padding so
     *  the overlay icons don't sit on top of card title/summary text. */
    fun applyOverlay(binding: HomeItemContainerBinding) {
        io.sentry.Sentry.addBreadcrumb("HomeEditMode: applyOverlay() isActive=$isActive")
        val wasVisible = binding.removeBtn.isVisible
        binding.removeBtn.isVisible = isActive
        binding.dragHandle.isVisible = isActive

        // Spring-in animation when controls first appear (edit mode just entered).
        if (isActive && !wasVisible) {
            val dur = ShizukuSettings.scaledAnimationDuration(240)
            val interp = OvershootInterpolator(1.8f)
            listOf(binding.dragHandle, binding.removeBtn).forEachIndexed { i, view ->
                view.scaleX = 0.5f
                view.scaleY = 0.5f
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(dur)
                    .setStartDelay(ShizukuSettings.scaledAnimationDuration(i * 40L))
                    .setInterpolator(interp)
                    .start()
            }
        }

        val isHidden = binding.root.tag as? Boolean ?: false
        val ctx = binding.root.context
        val density = binding.root.resources.displayMetrics.density

        fun attrColor(attr: Int): Int {
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(attr, tv, true)
            return tv.data
        }

        // Build a rounded-rectangle background using M3 container colors so the button chip
        // always reads as part of the theme, not a raw error/primary splash on any card color.
        fun containerChip(bgColor: Int): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(bgColor)
            }

        if (isActive && isHidden) {
            binding.cardContent.alpha = 0.45f
            binding.dragHandle.alpha = 0.35f
            binding.removeBtn.setImageResource(R.drawable.ic_add_24)
            val bg = attrColor(com.google.android.material.R.attr.colorPrimaryContainer)
            val fg = attrColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
            binding.removeBtn.background = containerChip(bg)
            binding.removeBtn.imageTintList = android.content.res.ColorStateList.valueOf(fg)
        } else {
            binding.cardContent.alpha = 1.0f
            binding.dragHandle.alpha = 0.85f
            binding.removeBtn.setImageResource(R.drawable.ic_close_24)
            val bg = attrColor(com.google.android.material.R.attr.colorErrorContainer)
            val fg = attrColor(com.google.android.material.R.attr.colorOnErrorContainer)
            binding.removeBtn.background = containerChip(bg)
            binding.removeBtn.imageTintList = android.content.res.ColorStateList.valueOf(fg)
        }

        // Drag handle: explicit on-surface-variant tint so it reads clearly against any card bg
        binding.dragHandle.imageTintList = android.content.res.ColorStateList.valueOf(
            attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        )

        val res = binding.cardContent.resources
        val base = res.getDimensionPixelSize(R.dimen.card_content_padding)
        // drag_handle and remove_btn now sit side-by-side in a single top-end row (40dp each +
        // 4dp gap + 4dp row margin) instead of stacked/overlapping on the same corner; reserve
        // clearance for the whole row so content never sits under either control.
        val overlayClearance = if (isActive)
            (92 * res.displayMetrics.density).toInt() else 0
        // The fold chevron sits in the same top-end row and is visible in BOTH modes, so its
        // clearance is unconditional. HomeCardFold applies the identical base + chevron figure on
        // cards whose holder never calls this, which is why the two can never disagree.
        val chevronClearance = HomeCardFold.chevronClearancePx(binding.cardContent)
        binding.cardContent.updatePaddingRelative(end = base + chevronClearance + overlayClearance)
        binding.foldTitle.updatePaddingRelative(end = base + chevronClearance + overlayClearance)
    }
}
