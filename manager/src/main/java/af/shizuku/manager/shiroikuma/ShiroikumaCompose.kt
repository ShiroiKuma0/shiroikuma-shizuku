package af.shizuku.manager.shiroikuma

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Borders for Compose containers.
 *
 * **Why these exist at all:** in this theme every surface role — `surface`, `surfaceVariant` and all
 * five `surfaceContainer*` — is the *same* pure black as the page. Upstream's dark theme told
 * containers apart by tonal lift, and flattening that away means a `Card` with no border is simply
 * invisible. So a container filled with a `surface*` role **must** carry one of these.
 *
 * Two tiers, matching how the page reads:
 * - [majorBorder] — the accent (yellow). Groups, sections, and anything that is a heading in its own
 *   right.
 * - [minorBorder] — the grey `outlineVariant`. Ordinary items: list rows, metric tiles, search
 *   results. Separates them from the ground without shouting.
 *
 * Both honour the card-border width slider, and both return `null` at width 0 — "off" stays
 * reachable, exactly like every other border slider on the page.
 */

@Composable
fun majorBorder(): BorderStroke? = borderOf(MaterialTheme.colorScheme.outline)

@Composable
fun minorBorder(): BorderStroke? = borderOf(MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun borderOf(color: androidx.compose.ui.graphics.Color): BorderStroke? {
    val context = LocalContext.current
    val width = ShiroikumaUiPrefs.getInt(context, ShiroikumaUiPrefs.KEY_CARD_BORDER)
    return if (width <= 0) null else BorderStroke(width.dp, color)
}
