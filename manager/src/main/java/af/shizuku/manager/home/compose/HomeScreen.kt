package af.shizuku.manager.home.compose

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.*
import androidx.compose.runtime.getValue
import af.shizuku.core.ui.compose.Button
import af.shizuku.core.ui.compose.ButtonSize
import af.shizuku.manager.ShizukuSettings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    isEditMode: Boolean,
    isOneHanded: Boolean,
    showEmptyState: Boolean,
    onStopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSettingsLongClick: () -> Unit,
    onHelpClick: () -> Unit,
    onDoneClick: () -> Unit,
    onRestoreHomeCards: () -> Unit,
    recyclerViewProvider: (Context, PaddingValues) -> RecyclerView
) {
    // ⛔ Fork: a FIXED header — not LargeTopAppBar, and no scroll behaviour (白い熊, 2026-09-05).
    //
    // Upstream's large collapsing title had never actually collapsed here, because an AndroidView
    // does not participate in Compose's nestedScroll. r2431's `604a394a` "fixed" that by driving
    // scrollBehavior.state.heightOffset straight from RecyclerView.onScrolled — symmetrically, on
    // every delta. The real exitUntilCollapsed connection deliberately refuses to expand while the
    // list is scrolled ("don't intercept if scrolling down"), and re-expands only once the content
    // is back at the top; the hand-rolled listener has none of that, so any downward drag anywhere
    // in the list grew the title back to full height and shoved the cards down the screen.
    //
    // A two-height title that animates while you read is what was objected to, so it is gone
    // rather than re-gated: the name sits top-left, one row, always. Restoring the large variant
    // means restoring the gating with it.
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isEditMode) stringResource(R.string.home_edit_mode_title)
                        else stringResource(R.string.app_name)
                    )
                },
                actions = {
                    if (isEditMode) {
                        TextButton(onClick = onDoneClick) {
                            Text(
                                text = stringResource(R.string.home_edit_mode_done),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        IconButton(onClick = onStopClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close_24),
                                contentDescription = stringResource(id = R.string.action_stop)
                            )
                        }
                        // Fork: the cog takes a LONG-PRESS straight to the 白い熊 雫 UI page.
                        // An IconButton has no long-press, so the cog is a combinedClickable box.
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = LocalIndication.current,
                                    onClick = onSettingsClick,
                                    onLongClick = onSettingsLongClick
                                )
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings_outline_24),
                                contentDescription = stringResource(id = R.string.settings_title)
                            )
                        }
                        IconButton(onClick = onHelpClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_help_outline_24),
                                contentDescription = stringResource(id = R.string.settings_plus_learn_more)
                            )
                        }
                    }
                },
                // Fork: the container is OPAQUE. Upstream leaves it transparent so its gradient
                // shows through, which let the cards scroll visibly under the title. On this theme
                // the bar colour equals the page colour anyway, so an opaque ground costs nothing
                // and keeps the header a header.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = if (ShizukuSettings.isBlurUiEnabled())
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                    else
                        MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        // Samsung One UI one-handed mode: translate the entire content downward so the action
        // zone stays in the comfortable thumb area without scaling (scaling shrinks side
        // margins, which the user explicitly doesn't want). Spring animation gives the same
        // "snap to lower half" feel as Samsung Settings. Full-width content + vertical shift
        // only = thumb-reachable without forcing the user to reposition their hand.
        val screenHeightDp = LocalConfiguration.current.screenHeightDp
        val oneHandedOffset by animateDpAsState(
            targetValue = if (isOneHanded) (screenHeightDp * 0.38f).dp else 0.dp,
            animationSpec = if (ShizukuSettings.isExpressiveAnimationsEnabled())
                spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            else
                snap(),
            label = "oneHandedOffset"
        )
        val adjustedPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + 72.dp
        )
        AnimatedGradientBackground {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // padding shifts layout position (and touch targets) correctly;
                    // offset {} is draw-only for AndroidView and causes touch misalignment.
                    .padding(top = oneHandedOffset)
            ) {
                if (showEmptyState) {
                    Box(modifier = Modifier.padding(adjustedPadding)) {
                        HomeEmptyState(onRestoreHomeCards)
                    }
                } else {
                    AndroidView(
                        factory = { context ->
                            recyclerViewProvider(context, adjustedPadding).also { rv ->
                                (rv.parent as? android.view.ViewGroup)?.removeView(rv)
                            }
                        },
                        update = { view -> recyclerViewProvider(view.context, adjustedPadding) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun AnimatedGradientBackground(content: @Composable () -> Unit) {
    val animationsEnabled = ShizukuSettings.isExpressiveAnimationsEnabled()
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        // Clamp to a static value when expressive animations are disabled, matching every
        // other animated element in the app and avoiding a perpetual recomposition/battery cost.
        targetValue = if (animationsEnabled) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val color1 = MaterialTheme.colorScheme.primary.copy(alpha = 0.03f + 0.05f * alpha)
    val color2 = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.03f + 0.05f * (1f - alpha))
    val color3 = MaterialTheme.colorScheme.secondary.copy(alpha = 0.03f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.sweepGradient(
                    colors = listOf(color1, color2, color3, color1),
                    center = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY) // Sweep from bottom corner
                )
            )
    ) {
        content()
    }
}

@Composable
fun HomeEmptyState(onRestoreHomeCards: () -> Unit) {
    val animationsEnabled = ShizukuSettings.isExpressiveAnimationsEnabled()
    val infiniteTransition = rememberInfiniteTransition()
    val floatAnim by infiniteTransition.animateFloat(
        initialValue = -8f,
        // Clamp to a static value when expressive animations are disabled, matching every
        // other animated element in the app and avoiding a perpetual recomposition/battery cost.
        targetValue = if (animationsEnabled) 8f else -8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_empty_home_24),
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .offset(y = floatAnim.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_state_title_no_home_cards),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_state_description_no_home_cards),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            size = ButtonSize.Medium,
            onClick = onRestoreHomeCards
        ) {
            Text(stringResource(R.string.empty_state_action_restore_home_cards))
        }
    }
}
