package af.shizuku.manager.home

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

/**
 * Folding for the home cards, the same gesture the settings categories have.
 *
 * Wired from a single place — [HomeAdapter.onBindViewHolder], which is the only code that knows
 * both the card's view and its stable id — rather than from each of the thirteen view holders. A
 * card added later is foldable with no extra work.
 *
 * **The folded label is taken from the card itself.** The inner layouts do not share a structure
 * (most are a vertical `LinearLayout` opening with an icon+title row, but three are horizontal and
 * two are `ConstraintLayout`s), so there is no child index that means "the title" everywhere. A
 * hand-kept map of card id → string resource would work today and go stale the first time someone
 * adds a card, so the title is read out of the card's own view tree instead: the first non-blank
 * `TextView`, which in every one of these layouts is the heading.
 *
 * Fold state is per card id and survives relaunches — a card folded away should stay folded, or
 * folding is just a way to lose things until the next cold start.
 */
object HomeCardFold {

    /** Clearance so card content never runs under the chevron (40dp control + 4dp row margin). */
    private const val CHEVRON_CLEARANCE_DP = 48

    fun chevronClearancePx(view: View): Int =
        (CHEVRON_CLEARANCE_DP * view.resources.displayMetrics.density).toInt()

    fun isFolded(cardId: Long): Boolean = cardId.toString() in ShizukuSettings.getFoldedHomeCards()

    /**
     * Bind the fold affordance for [cardId] onto a `home_item_container` root.
     *
     * Safe to call on every bind: it only reads state and re-applies it, and the click listener is
     * replaced rather than accumulated.
     */
    fun apply(itemView: View, cardId: Long) {
        val chevron = itemView.findViewById<ImageView>(R.id.fold_chevron) ?: return
        val content = itemView.findViewById<View>(R.id.card_content) ?: return
        val foldTitle = itemView.findViewById<TextView>(R.id.fold_title) ?: return

        render(chevron, content, foldTitle, isFolded(cardId))

        chevron.setOnClickListener {
            val nowFolded = !isFolded(cardId)
            val folded = ShizukuSettings.getFoldedHomeCards().toMutableSet()
            if (nowFolded) folded.add(cardId.toString()) else folded.remove(cardId.toString())
            ShizukuSettings.setFoldedHomeCards(folded)
            render(chevron, content, foldTitle, nowFolded)
            chevron.animate()
                .rotation(rotationFor(nowFolded))
                .setDuration(ShizukuSettings.scaledAnimationDuration(220))
                .start()
        }

        // Tapping the folded title unfolds too — the card has no other content to tap by then, and
        // hunting for a 40dp glyph is a poor way to get a card back.
        foldTitle.setOnClickListener { chevron.performClick() }
    }

    private fun render(chevron: ImageView, content: View, foldTitle: TextView, folded: Boolean) {
        // Recomputed on every render, never cached: holders are recycled, so a title kept from the
        // last card this view showed would be a plausible-looking lie about what is folded here.
        if (folded) {
            foldTitle.text = firstTitle(content) ?: chevron.context.getString(R.string.app_name)
        }
        foldTitle.isVisible = folded
        content.isVisible = !folded
        chevron.rotation = rotationFor(folded)

        // Reserve room for the chevron whichever half is showing. HomeEditMode.applyOverlay owns
        // this same padding on the cards whose holders call it and adds the edit-mode controls'
        // clearance on top of the identical base, so the two never disagree.
        val base = content.resources.getDimensionPixelSize(R.dimen.card_content_padding)
        val clearance = chevronClearancePx(chevron)
        if (!folded) content.updatePaddingRelative(end = base + clearance)
        foldTitle.updatePaddingRelative(end = base + clearance)
    }

    /** Right when folded, down when unfolded. See CollapsiblePreferenceCategory for the rationale. */
    private fun rotationFor(folded: Boolean): Float = if (folded) -90f else 0f

    private fun firstTitle(root: View): String? {
        if (root is TextView) return root.text?.toString()?.takeIf { it.isNotBlank() }
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            firstTitle(root.getChildAt(i))?.let { return it }
        }
        return null
    }
}
