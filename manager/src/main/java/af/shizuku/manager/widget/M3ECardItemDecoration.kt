package af.shizuku.manager.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import af.shizuku.manager.ktx.themeColor
import af.shizuku.manager.ktx.themeCornerSizePx
import af.shizuku.manager.shiroikuma.ShiroikumaUiPrefs

/**
 * Base ItemDecoration for Material 3 Expressive card-style lists.
 * Handles background card drawing and dividers with consistent spacing.
 */
abstract class M3ECardItemDecoration(context: Context) : RecyclerView.ItemDecoration() {
    protected val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    // Matches every other 28dp/ExtraLarge card in the app (see #333) and follows the Shape
    // Style setting (Modern/Classic/Squircle) instead of a fixed radius.
    protected val cornerRadius = context.themeCornerSizePx(com.google.android.material.R.attr.shapeAppearanceCornerExtraLarge)
    protected val cardMargin = context.resources.getDimension(R.dimen.m3e_spacing_medium)
    protected val density = context.resources.displayMetrics.density

    /**
     * Whether each card gets a visible rounded border.
     *
     * Opt-in rather than always-on, because a subclass with no headers draws **one** card spanning
     * the visible children — a box whose edges sit at the viewport rather than at the list's ends,
     * which would slide around while scrolling. Only decorations that group by header should set it.
     */
    protected open val drawsBorder: Boolean get() = false

    /** Border colour, width and radius, re-read per frame so the UI page's sliders apply live. */
    private var borderWidthPx = 0f
    private var houseRadiusPx = 0f

    init {
        cardPaint.color = context.themeColor(R.attr.colorSurfaceContainerHigh)
        dividerPaint.color = context.themeColor(R.attr.colorOutlineVariant)
        dividerPaint.strokeWidth = 1f * density
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = parent.childCount
        if (count == 0) return

        if (drawsBorder) {
            // The house knobs, not the theme attributes: these are the same sliders the home cards
            // and every hand-drawn panel read, so one setting moves all of them together.
            val p = ShiroikumaUiPrefs
            val ctx = parent.context
            cardPaint.color = p.getInt(ctx, p.KEY_CARD_FILL)
            borderPaint.color = p.getInt(ctx, p.KEY_COLOR_BORDER)
            borderWidthPx = p.getInt(ctx, p.KEY_CARD_BORDER) * density
            houseRadiusPx = p.getInt(ctx, p.KEY_CARD_RADIUS) * density
            borderPaint.strokeWidth = borderWidthPx
        }

        var currentCardTop = Float.MIN_VALUE
        var lastItemBottom = Float.MIN_VALUE
        // A card whose first row is the first ATTACHED child, rather than a header, began above the
        // viewport — its top edge is not a real edge and must not be drawn, or scrolling a long
        // group would paint a line across it. Same for the bottom, resolved after the loop.
        var topIsRealEdge = true
        var lastChild: View? = null

        for (i in 0 until count) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            lastChild = child

            if (isHeader(child)) {
                if (currentCardTop != Float.MIN_VALUE) {
                    // Closed by the next header, so this bottom is a real edge.
                    drawCard(c, parent, currentCardTop, lastItemBottom, topIsRealEdge, true)
                }
                currentCardTop = child.top.toFloat()
                lastItemBottom = child.bottom.toFloat()
                topIsRealEdge = true

                // Draw a divider under the header if it's expanded (i.e. has visible children)
                if (shouldDrawDivider(parent, i, count)) {
                    val left = child.left.toFloat() + getDividerInset(child)
                    val right = child.right.toFloat() - getDividerEndInset(child)
                    val y = child.bottom.toFloat()
                    c.drawLine(left, y, right, y, dividerPaint)
                }
            } else {
                if (currentCardTop == Float.MIN_VALUE) {
                    currentCardTop = child.top.toFloat()
                    // Started mid-group unless this really is the list's first row.
                    topIsRealEdge = parent.getChildAdapterPosition(child) == 0
                }
                lastItemBottom = child.bottom.toFloat()

                if (shouldDrawDivider(parent, i, count)) {
                    val left = child.left.toFloat() + getDividerInset(child)
                    val right = child.right.toFloat() - getDividerEndInset(child)
                    val y = child.bottom.toFloat()
                    c.drawLine(left, y, right, y, dividerPaint)
                }
            }
        }

        if (currentCardTop != Float.MIN_VALUE) {
            // The last card ran out of attached children rather than hitting the next header, so
            // its bottom is only a real edge if that child is genuinely the last row in the list.
            val bottomIsRealEdge = lastChild != null &&
                parent.getChildAdapterPosition(lastChild) == state.itemCount - 1
            drawCard(c, parent, currentCardTop, lastItemBottom, topIsRealEdge, bottomIsRealEdge)
        }
    }

    protected open fun isHeader(view: View): Boolean = false

    protected open fun getDividerInset(view: View): Float = 56f * density

    protected open fun getDividerEndInset(view: View): Float = 16f * density

    protected open fun shouldDrawDivider(parent: RecyclerView, index: Int, count: Int): Boolean {
        return false
    }

    /**
     * One rounded box per group — header plus whichever of its rows are visible.
     *
     * The fill alone is not enough to see it. Every `surface*` role in this theme is the same pure
     * black as the page, so a card told apart only by tonal lift (upstream's assumption) is
     * genuinely invisible, not merely flat. The yellow stroke is what makes a group a group — and
     * when a category is folded the box shrinks to just its title row, which is what makes the
     * folded state readable at a glance rather than leaving the title floating on the page.
     */
    @JvmOverloads
    protected fun drawCard(
        c: Canvas,
        parent: RecyclerView,
        top: Float,
        bottom: Float,
        topIsRealEdge: Boolean = true,
        bottomIsRealEdge: Boolean = true
    ) {
        val left = cardMargin
        val right = parent.width - cardMargin
        val radius = if (drawsBorder) houseRadiusPx else cornerRadius

        c.drawRoundRect(left, top, right, bottom, radius, radius, cardPaint)

        // The width slider reaches 0, so "no border" stays reachable.
        if (!drawsBorder || borderWidthPx <= 0f) return

        // Inset by half the stroke: a stroke is centred on the path, so drawing it on the fill's own
        // rect would put half of it outside the card and let the RecyclerView clip it.
        val inset = borderWidthPx / 2f
        // An edge the viewport cut rather than the group ending is pushed far enough out that its
        // line and its two corners fall off-screen, so a part-scrolled group reads as continuing
        // instead of being closed off by a stroke that is not a real boundary.
        val overshoot = radius + borderWidthPx + 1f
        c.drawRoundRect(
            left + inset,
            if (topIsRealEdge) top + inset else top - overshoot,
            right - inset,
            if (bottomIsRealEdge) bottom - inset else bottom + overshoot,
            radius, radius, borderPaint
        )
    }
}
