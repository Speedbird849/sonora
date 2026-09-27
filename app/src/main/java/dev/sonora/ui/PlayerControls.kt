package dev.sonora.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A progress bar with no thumb, that grows while it is being dragged.
 *
 * Two decisions, both about what the bar is for. It has no knob, because a knob is a thing to aim
 * at and this is a thing to drag — a knob on a full-width bar spends 8dp of every hundred on
 * something the finger is already covering. And it thickens under the finger rather than staying
 * the same, because a 7dp line is a hairline at the moment you most need to see exactly where you
 * are.
 *
 * The drawn bar is 7dp but the touch target is 34dp, because the two are different jobs: one is
 * what you see and the other is what you can hit. A 7dp target is unhittable.
 */
@Composable
internal fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    idleHeight: Dp = 7.dp,
    activeHeight: Dp = 12.dp,
    activeColor: Color = Color.White.copy(alpha = 0.92f),
    inactiveColor: Color = Color.White.copy(alpha = 0.26f),
) {
    var dragging by remember { mutableStateOf(false) }
    val height by animateDpAsState(
        targetValue = if (dragging) activeHeight else idleHeight,
        animationSpec = spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        ),
        label = "sliderHeight",
    )

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(activeHeight + 22.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(Unit) {
                    // One gesture loop rather than a tap detector and a drag detector: two of them
                    // race, and the tap usually loses, which is how a bar ends up ignoring the tap
                    // that scrubs straight to a point.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        currentOnValueChange((down.position.x / size.width).coerceIn(0f, 1f))

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            currentOnChangeScrub(change.position.x / size.width, currentOnValueChange)
                            change.consume()
                        }

                        dragging = false
                        currentOnFinished?.invoke()
                    }
                },
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = inactiveColor,
                size = size,
                cornerRadius = radius,
            )
            // At least one full-height dot at zero, so an unstarted track reads as "not begun"
            // rather than as "nothing here at all" — and so the bar has a leading edge to grab.
            val filled = (size.width * value.coerceIn(0f, 1f)).coerceAtLeast(size.height)
            drawRoundRect(
                color = activeColor,
                size = Size(filled, size.height),
                cornerRadius = radius,
            )
        }
    }
}

private fun currentOnChangeScrub(fraction: Float, onChange: (Float) -> Unit) =
    onChange(fraction.coerceIn(0f, 1f))

/** `1:23` — and `0:00` rather than a blank, because a blank reads as a missing value. */
internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

/**
 * The scrubber and its two timestamps.
 *
 * Elapsed on the left, remaining on the right, and remaining is written as a *minus* — "‑1:12"
 * rather than "1:12". The two numbers are the same length and mean opposite things, and a listener
 * glancing at the right-hand one should not have to work out which it is.
 *
 * The labels sit 9dp above the bar's own box because the slider's touch target is 22dp taller than
 * the line it draws; centring the labels in that box would put them inside the invisible target,
 * where they read as overlapping the bar.
 */
@Composable
internal fun PlayerScrubber(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    centerLabel: @Composable () -> Unit = {},
) {
    val fraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(modifier = modifier.fillMaxWidth()) {
        ThinSlider(
            value = fraction,
            onValueChange = { onSeek((it * durationMs).toLong()) },
        )

        Box(
            Modifier
                .fillMaxWidth()
                .offset(y = (-9).dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(positionMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                )
                Text(
                    text = "-" + formatTime(durationMs - positionMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
            Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                centerLabel()
            }
        }
    }
}

/**
 * The three transport buttons.
 *
 * Play is much bigger than the skips, and the skips are flattened vertically. Both are about the
 * same gesture being different in size: play is pressed on purpose, often, and the skips are
 * reached for in the middle of something else — a flattened triangle and bar reads as "skip" at a
 * glance in a way a symmetric one does not.
 *
 * No ripple on any of them. The feedback is the play/pause swap itself, and a ripple on top of a
 * button that is already changing is two answers to one tap.
 */
@Composable
internal fun TransportRow(
    isPlaying: Boolean,
    previousEnabled: Boolean = true,
    nextEnabled: Boolean = true,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val playSize = if (compact) 58.dp else 74.dp
    val playTouch = if (compact) 76.dp else 92.dp
    val skipSize = if (compact) 44.dp else 53.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        // SpaceAround rather than SpaceEvenly: the outer margins get half a gap, so the three
        // spread a little wider between themselves and sit further from the screen's edges.
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(
            onClick = onPrevious,
            contentDescription = "Previous",
            enabled = previousEnabled,
            touchSize = skipSize,
            iconSize = skipSize,
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = null,
                modifier = Modifier.size(skipSize),
            )
        }

        TransportButton(
            onClick = onPlayPause,
            contentDescription = if (isPlaying) "Pause" else "Play",
            touchSize = playTouch,
            iconSize = playSize,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(playSize),
            )
        }

        TransportButton(
            onClick = onNext,
            contentDescription = "Next",
            enabled = nextEnabled,
            touchSize = skipSize,
            iconSize = skipSize,
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = null,
                modifier = Modifier.size(skipSize),
            )
        }
    }
}

/** Vertical squash on the skip glyphs only — the touch box is left square. */
private val SKIP_HEIGHT_SCALE = 0.85f

@Composable
private fun TransportButton(
    onClick: () -> Unit,
    contentDescription: String,
    touchSize: Dp,
    iconSize: Dp,
    enabled: Boolean = true,
    glyph: @Composable () -> Unit,
) {
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (enabled) 1f else 0.3f,
        label = "transportAlpha",
    )

    Box(
        modifier = Modifier
            .size(touchSize)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(iconSize)
                .then(
                    if (touchSize == iconSize && contentDescription != "Play" && contentDescription != "Pause") {
                        Modifier.graphicsLayerScaleY(SKIP_HEIGHT_SCALE)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides Color.White.copy(alpha = alpha),
            ) {
                glyph()
            }
        }
    }
}

private fun Modifier.graphicsLayerScaleY(scale: Float): Modifier =
    this.then(
        Modifier.graphicsLayer { scaleY = scale },
    )

/** A volume slider with the same shape as the scrubber, so the two read as one control. */
@Composable
internal fun VolumeRow(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.VolumeDown,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        ThinSlider(
            value = volume,
            onValueChange = onVolumeChange,
            idleHeight = 6.dp,
            activeHeight = 10.dp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}
