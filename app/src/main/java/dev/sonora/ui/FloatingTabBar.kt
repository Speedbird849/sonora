package dev.sonora.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.sonora.ui.theme.accentText
import kotlin.math.abs
import kotlin.math.roundToInt

/** One tab in the bar. */
data class BottomTab(val label: String, val icon: ImageVector)

/**
 * The gap between the pill's glass edge and the tabs inside it.
 *
 * Tighter than a Material bar's, which shows up as a selection indicator reaching closer to the
 * edge on every side rather than floating in the middle of a wide margin.
 */
internal val PILL_INSET = 6.dp

/**
 * Each tab's own vertical padding, and the counterweight to [PILL_INSET].
 *
 * The pill has no height of its own — it is whatever its contents come to — so taking 2dp off the
 * inset would shorten the whole bar by 4. The same 2dp is added back inside instead, which leaves
 * the bar's outer height exactly where it was and moves the boundary rather than the bar. The two
 * numbers are a pair: change one and the bar's height moves unless the other moves against it.
 */
internal val TAB_VERTICAL_PADDING = 9.dp

/** The gap between a tab's glyph and its label. */
internal val TAB_ICON_LABEL_GAP = 2.dp

/** The gap between tabs, which is also the gap the indicator travels across. */
internal val TAB_GAP = 6.dp

/**
 * The spring the selection indicator and the tab glyphs both travel on.
 *
 * Damping 0.72 rather than a stiffer value: half-damped overshoots two or three times, and a run of
 * diminishing bounces is what makes a control read as a spring rather than as a material. This
 * settles on the second approach — one soft pass beyond the mark and done — which is the difference
 * between bouncy and alive.
 *
 * Stiffness 320 puts the whole movement at roughly a third of a second, quick enough that the tap
 * and the arrival feel like one event.
 */
internal val GlassSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 320f)

/**
 * How far the indicator elongates along its travel, at full stride.
 *
 * This is the part that reads as liquid rather than as a sliding rectangle. A shape crossing a gap
 * under its own momentum does not stay the shape it was: it draws out along the direction it is
 * going and gathers itself back at the end. Driven off how far there is still to go, so it is
 * widest in the middle of the trip and exactly itself once it arrives — no state to keep, and it
 * falls out of a drag for free, since dragging is nothing but a long way still to go.
 *
 * Sixteen percent is enough to be felt and not enough to be caught at: past about a fifth the pill
 * starts reading as a stretched image of itself.
 */
internal const val STRETCH = 0.16f

/**
 * How much of the stretch is taken back out of the indicator's height.
 *
 * Half, not all. Conserving area exactly is what a drop of water does, and it is too much here — the
 * indicator sits behind a glyph that is not deforming with it, and a full counter-squash reads as
 * the pill being crushed rather than drawn.
 */
internal const val SQUASH = 0.5f

/** The hairline that reads as the lit edge of the glass. */
internal val GLASS_EDGE_WIDTH = 0.5.dp

internal val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.10f)

/**
 * A floating pill of tabs that blurs the page scrolling under it, riding above the system bar.
 *
 * Two things make it read as glass rather than as a rounded rectangle: the Haze effect, which is
 * what actually lets the page show through, and the indicator that deforms while it travels. The
 * indicator alone is enough to look like a sliding block; deforming it is what makes it look like
 * something with surface tension.
 *
 * The whole bar is draggable as well as tappable, so a thumb that lands on the pill and slides does
 * not have to lift and come back to change tab.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun FloatingTabBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    // percent rather than a dp figure, so the corner stays exactly half the height if the contents
    // ever change it — which is what keeps a pill a pill instead of a rounded rectangle.
    val pillShape = RoundedCornerShape(percent = 50)
    val container = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)

    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }

    val gapPx = with(density) { TAB_GAP.toPx() }
    val n = tabs.size

    // With weight(1f) + spacedBy(TAB_GAP):
    //   tabWidth = (rowWidth - gap*(n-1)) / n
    //   tab left edge = i * (tabWidth + gap) = i * (rowWidth + gap) / n
    val tabWidthPx = if (rowSize.width > 0 && n > 0) {
        (rowSize.width - gapPx * (n - 1)) / n
    } else {
        0f
    }
    val tabStepPx = if (rowSize.width > 0 && n > 0) {
        (rowSize.width + gapPx) / n
    } else {
        0f
    }

    val pillTargetPx = if (tabStepPx > 0f) {
        selectedIndex * tabStepPx + dragOffset
    } else {
        0f
    }

    val animatedPillOffset by animateFloatAsState(
        targetValue = pillTargetPx,
        animationSpec = GlassSpring,
        label = "pillOffset",
    )

    // How much of a tab's stride is still ahead of the indicator: 0 at rest, toward 1 in the middle
    // of a move or under a drag that has run away from it. The stretch is a function of this and
    // nothing else, which keeps it honest — the shape can only be deformed while it is actually
    // behind where it is going.
    val lag = if (tabStepPx > 0f) {
        (abs(pillTargetPx - animatedPillOffset) / tabStepPx).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = 2.dp)
            .fillMaxWidth()
            .clip(pillShape)
            .optimizedHazeEffect(
                state = hazeState,
                style = HazeMaterials.regular(container),
            )
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, pillShape)
            .padding(horizontal = PILL_INSET, vertical = PILL_INSET),
    ) {
        if (tabWidthPx > 0f) {
            Box(
                modifier = Modifier
                    .width(with(density) { tabWidthPx.toDp() })
                    .height(with(density) { rowSize.height.toDp() })
                    .graphicsLayer {
                        translationX = animatedPillOffset
                        // Around its own centre, so the indicator draws out both ways rather than
                        // growing a tail off one edge — a leading edge that ran ahead of the glyph
                        // it is meant to be behind would read as two things moving, not one thing
                        // stretching.
                        scaleX = 1f + lag * STRETCH
                        scaleY = 1f - lag * STRETCH * SQUASH
                    }
                    .clip(pillShape)
                    .background(MaterialTheme.colorScheme.accentText.copy(alpha = 0.14f)),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowSize = it }
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragCancel = { dragOffset = 0f },
                        onDragEnd = {
                            if (tabStepPx > 0f) {
                                val ratio = totalDrag / tabStepPx
                                val shift = when {
                                    ratio > 0.35f -> maxOf(1, ratio.roundToInt())
                                    ratio < -0.35f -> minOf(-1, ratio.roundToInt())
                                    else -> 0
                                }
                                val newIndex =
                                    (currentSelectedIndex + shift).coerceIn(0, tabs.lastIndex)
                                if (newIndex != currentSelectedIndex) onTabSelected(newIndex)
                            }
                            dragOffset = 0f
                        },
                        onHorizontalDrag = { _, delta ->
                            totalDrag += delta
                            // Resistance at either end, so running out of tabs feels like hitting
                            // something rather than like the bar having stopped tracking the finger.
                            dragOffset = when {
                                totalDrag > 0 && currentSelectedIndex == tabs.lastIndex ->
                                    totalDrag * 0.25f

                                totalDrag < 0 && currentSelectedIndex == 0 ->
                                    totalDrag * 0.25f

                                else -> totalDrag
                            }
                        },
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(TAB_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                BottomBarItem(
                    tab = tab,
                    selected = index == selectedIndex,
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    tab: BottomTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The same spring the indicator rides, so the glyph arriving and the glass arriving are one
    // movement rather than two that nearly agree.
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = GlassSpring,
        label = "tabScale",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.accentText
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "tabTint",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            // No ripple: the indicator behind the tab already says which one is selected, and a
            // ripple on top of it is two answers to one question.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = TAB_VERTICAL_PADDING),
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier
                .size(25.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
        Spacer(Modifier.height(TAB_ICON_LABEL_GAP))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
