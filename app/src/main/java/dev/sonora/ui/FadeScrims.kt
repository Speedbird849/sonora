package dev.sonora.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * How far the flat floor under the floating bars reaches up the page, and how much further it has
 * to reach when a mini player is stacked above the tab bar.
 */
private val FADE_HEIGHT = 180.dp

private val FADE_HEIGHT_WITH_MINI_PLAYER = 254.dp

/**
 * How many samples the ramp is drawn from.
 *
 * Sixteen, and not the two a linear gradient would take by default. This is a near-flat colour
 * stretched over a strip this tall, which is the case 8-bit output bands visibly — the steps read
 * as rings across the floor. More stops cost nothing and are not seeable.
 */
private const val STOPS = 16

/**
 * The flat floor the floating bars stand on: the page's own colour faded in from nothing at the top
 * to solid at the bottom.
 *
 * A gradient rather than a blur, and that is a deliberate trade. A blur here has to sample the feed
 * scrolling underneath it, and the cost is paid on every frame of that scroll; a gradient is one
 * shader over a fixed rectangle. The bars themselves are frosted, so the glass still shows the page
 * moving — what this is for is the *floor* beneath them, which a blur would not fix anyway.
 */
@Composable
internal fun BottomFadeScrim(
    modifier: Modifier = Modifier,
    withMiniPlayer: Boolean = false,
    pageColor: Color = MaterialTheme.colorScheme.background,
) {
    val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Animated rather than switched, because the mini player appears and disappears with playback
    // and a floor that jumps between two heights moves everything above it by a frame.
    val height by animateDpAsState(
        targetValue = navigation + if (withMiniPlayer) FADE_HEIGHT_WITH_MINI_PLAYER else FADE_HEIGHT,
        animationSpec = tween(220),
        label = "bottomScrimHeight",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(floorBrush(pageColor)),
    )
}

/**
 * The same floor, turned to sit at the top of a page.
 *
 * A rotation rather than a second gradient so there is one ramp in the app: a top fade and a bottom
 * fade that disagree by a stop are two different greys meeting in the middle of a page.
 */
@Composable
internal fun TopFadeScrim(
    modifier: Modifier = Modifier,
    pageColor: Color = MaterialTheme.colorScheme.background,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FADE_HEIGHT)
            .rotate(180f)
            .background(floorBrush(pageColor)),
    )
}

/**
 * Transparent at the top, solid at the bottom, easing in.
 *
 * The easing matters more than it looks: a linear ramp spends most of its length nearly invisible,
 * which puts the visible part of the fade hard against the bars and leaves the content above them
 * unprotected. Cubic in puts the transition where the eye is.
 */
private fun floorBrush(pageColor: Color): Brush = Brush.verticalGradient(
    colorStops = Array(STOPS) { index ->
        val t = index.toFloat() / (STOPS - 1)
        t to pageColor.copy(alpha = easeInCubic(t))
    },
)

/** Slow to start, then firm: the opposite of a linear ramp, and the point of the whole thing. */
private fun easeInCubic(t: Float): Float = t * t * t
