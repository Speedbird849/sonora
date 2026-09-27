package dev.sonora.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/** The bar's own height, below the status bar. */
val TopBarContentHeight: Dp = 52.dp

/** How far below the bar a page's content starts. */
val TopBarContentGap: Dp = 12.dp

@Composable
internal fun topBarHeight(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarContentHeight

/**
 * Where a page's content begins: the height of the bar plus a gap.
 *
 * Passed to every list rather than applied once by a parent, because a LazyColumn that does not
 * know how tall the chrome above it is will scroll its first row under the bar.
 */
@Composable
internal fun topBarContentPadding(): Dp = topBarHeight() + TopBarContentGap

/**
 * A top bar that blurs the page scrolling under it, with a hairline and nothing else.
 *
 * The bar paints no background of its own — a sibling pane drawn underneath does the frosting. That
 * split is what lets the bar's contents sit *on* the blur rather than being blurred along with the
 * page: a bar that blurred itself would take its own glyphs and its own hairline through the same
 * filter as the content behind it, and the text would go soft.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun FrostedTopBar(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val dividerColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        animationSpec = tween(220),
        label = "topBarDivider",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(TopBarContentHeight),
        ) {
            // A back arrow in the same place a wordmark would be, and nothing else. The page's own
            // large heading names it, so a title here would be the second one — and the two do not
            // agree, because the heading scrolls away and this does not.
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leading?.invoke()
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = dividerColor)
    }
}

/**
 * The frosted pane a [FrostedTopBar] is drawn over.
 *
 * Separate from the bar because the blur has to sample the page *underneath* it, and a pane that is
 * part of the bar cannot sample a sibling that is part of the same subtree. Exactly the bar's height,
 * so there is no frosted strip below the hairline where the page shows through unblurred.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun TopBarBlur(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(topBarHeight())
            .optimizedHazeEffect(
                state = hazeState,
                style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
            ),
    )
}

/**
 * A circular control that floats over a page, frosted like the bars.
 *
 * Used for the back button and the actions on a page with artwork behind it, where a control drawn
 * straight onto the page has no edge of its own to sit against.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun GlassCircle(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = CircleShape
    Box(
        modifier = modifier
            .clip(shape)
            .optimizedHazeEffect(
                state = hazeState,
                style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
            )
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** A frosted pill that grows to fit however many controls are in it. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun GlassPill(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .clip(shape)
            .optimizedHazeEffect(
                state = hazeState,
                style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
            )
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
            .padding(horizontal = PILL_INSET, vertical = PILL_INSET),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A flat fill behind a frosted control, for the case where there is no page to sample. */
@Composable
internal fun FlatGlass(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surface) =
    Box(modifier = modifier.background(color))

/**
 * The app's mark: a note and the name, at wordmark height.
 *
 * 18dp tall and sitting on the baseline of the bar rather than filling it. A mark is meant to be
 * read at a glance from arm's length while a thumb is doing something else, which is the opposite
 * of a title — a title has to be legible, a mark only has to be recognisable, and at 18dp in a
 * lighter weight it stops competing with the page underneath for attention.
 */
@Composable
internal fun SonoraWordmark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Sonora",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
