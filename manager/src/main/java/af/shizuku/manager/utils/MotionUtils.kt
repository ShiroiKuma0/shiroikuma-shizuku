package af.shizuku.manager.utils

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs

object MotionUtils {

    /**
     * Applies a spring-based scale down/up effect on touch.
     *
     * ⛔ **The press must never fire on ACTION_DOWN.** Every gesture on the home list starts with
     * a finger landing on a card, scrolls included — so feedback on DOWN means the list buzzes and
     * every card visibly shrinks each time you flick the screen, which reads as the card being
     * dragged (白い熊, 2026-09-05). ACTION_CANCEL from the RecyclerView's own interception arrives
     * far too late to undo either.
     *
     * So the press is *deferred* by one tap timeout and armed only if the finger stays put:
     *
     * - past the touch slop before it fires → this is a scroll; nothing ever happens;
     * - still down after the timeout → a real press: haptic + scale down, released on lift;
     * - lifted before the timeout → too quick to have rendered, so the haptic alone fires on
     *   release, otherwise a fast tap would give no feedback at all.
     */
    fun View.applySpringTouch(scale: Float = 0.97f) {
        // Single spring per axis; animateToFinalPosition() composes smoothly mid-animation
        // (no ViewPropertyAnimator discontinuity when the finger lifts before the press-in completes).
        val springX = SpringAnimation(this, SpringAnimation.SCALE_X).apply {
            spring = SpringForce(1.0f).also {
                it.stiffness = SpringForce.STIFFNESS_MEDIUM
                it.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }
        }
        val springY = SpringAnimation(this, SpringAnimation.SCALE_Y).apply {
            spring = SpringForce(1.0f).also {
                it.stiffness = SpringForce.STIFFNESS_MEDIUM
                it.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }
        }

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val tapTimeout = ViewConfiguration.getTapTimeout().toLong()

        var downX = 0f
        var downY = 0f
        var pressShown = false
        var abandoned = false

        fun release() {
            springX.animateToFinalPosition(1.0f)
            springY.animateToFinalPosition(1.0f)
        }

        val showPress = Runnable {
            pressShown = true
            HapticUtils.tap(this)
            springX.animateToFinalPosition(scale)
            springY.animateToFinalPosition(scale)
        }

        setOnTouchListener { v, event ->
            // Unlike every other animation/haptic call site in the app, this touch feedback
            // used to fire unconditionally on every card tap, ignoring the user's Expressive
            // Animations toggle.
            if (!af.shizuku.manager.ShizukuSettings.isExpressiveAnimationsEnabled()) {
                return@setOnTouchListener false
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    pressShown = false
                    abandoned = false
                    v?.postDelayed(showPress, tapTimeout)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!abandoned &&
                        (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                    ) {
                        // The finger is travelling: this is a scroll, not a press.
                        abandoned = true
                        v?.removeCallbacks(showPress)
                        if (pressShown) release()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v?.removeCallbacks(showPress)
                    when {
                        pressShown -> release()
                        // Lifted inside the tap timeout: the press never rendered, so the haptic
                        // goes here instead of being lost.
                        !abandoned -> v?.let { HapticUtils.tap(it) }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v?.removeCallbacks(showPress)
                    if (pressShown) release()
                }
            }
            false
        }
    }
}
