package dev.sonora.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sonora.lyrics.LyricLine
import dev.sonora.lyrics.Lyrics
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay

/** The sung words, in Sonora's player type scale. */
private val LINE_FONT_SIZE = 22.sp
private val LINE_LINE_HEIGHT = 30.sp

/** How long a line takes to animate its highlight transition. */
private const val SETTLE_MILLIS = 220

/** How much of the pane's height the top and bottom edge fades take. */
private const val FADE = 0.16f

/** The dots shown through an instrumental break. */
private const val GAP_DOTS = 3
private val GAP_DOT_SIZE = 5.dp
private val GAP_DOT_GAP = 7.dp
private const val GAP_DOT_REST_ALPHA = 0.3f

/**
 * The lyrics pane displaying synced lines following the current playhead.
 *
 * Supports:
 * - Following playback smoothly without judder or jumping.
 * - Free manual scrolling by user (resumes auto-following after 4 seconds of idle).
 * - Tap on any line to seek playback to that timestamp.
 */
@Composable
internal fun LyricsPane(
    lyrics: Lyrics,
    positionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paneSide = LocalConfiguration.current.screenWidthDp.dp
    val listState = rememberLazyListState()

    var userScrolled by remember { mutableStateOf(false) }
    var lastUserInteractionTime by remember { mutableLongStateOf(0L) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    val current = remember(lyrics, positionMs) { lyrics.currentIndex(positionMs) }
    val playhead = rememberPlayhead(reportedMs = positionMs, isPlaying = isPlaying)

    // Detect user-initiated drag/scroll on the list (ignoring programmatic scrolls)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { inProgress ->
                if (inProgress && !isProgrammaticScroll) {
                    userScrolled = true
                    lastUserInteractionTime = System.currentTimeMillis()
                }
            }
    }

    // Auto-resume following after 4 seconds of user inactivity
    LaunchedEffect(userScrolled, lastUserInteractionTime) {
        if (userScrolled) {
            while (true) {
                val elapsed = System.currentTimeMillis() - lastUserInteractionTime
                if (elapsed >= 4_000L) {
                    userScrolled = false
                    break
                }
                delay(500L)
            }
        }
    }

    // Smoothly scroll to keep current line centred
    LaunchedEffect(current, userScrolled) {
        if (!userScrolled && current >= 0 && current < lyrics.lines.size) {
            val visibleInfo = listState.layoutInfo.visibleItemsInfo
            val item = visibleInfo.firstOrNull { it.index == current }
            val itemSize = item?.size ?: 0
            val viewportHeight = listState.layoutInfo.viewportSize.height
            val targetOffset = if (viewportHeight > 0 && itemSize > 0) {
                -(viewportHeight / 2 - itemSize / 2)
            } else {
                0
            }
            isProgrammaticScroll = true
            try {
                listState.animateScrollToItem(
                    index = current,
                    scrollOffset = targetOffset,
                )
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    when {
        lyrics.loading -> LyricsSkeleton(Modifier.fillMaxSize())

        lyrics.lines.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = lyrics.reason ?: "No lyrics",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }

        else -> Box(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            FADE to Color.Black,
                            1f - FADE to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = paneSide / 2),
            ) {
                itemsIndexed(lyrics.lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
                    val isCurrent = index == current
                    val distance = abs(index - current)
                    val nextStart = lyrics.lines.getOrNull(index + 1)?.timeMs

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                userScrolled = false
                                onSeek(line.timeMs)
                            }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (line.isGap) {
                            GapDots(
                                fromMs = line.timeMs,
                                untilMs = nextStart ?: line.endMs ?: line.timeMs,
                                playhead = playhead,
                            )
                        } else {
                            SyncedLyricLine(
                                text = line.text,
                                isCurrent = isCurrent,
                                distance = distance,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A clean, distinct highlighted lyric line.
 *
 * When active/current:
 * - Bright, pure white (1f alpha)
 * - SemiBold (W600) font weight
 * - Subtle scale (1.04)
 *
 * When inactive:
 * - Dimmed (alpha ~0.38 for neighbors, ~0.24 further away)
 * - Normal (W400) font weight
 * - Default scale (1.0)
 */
@Composable
private fun SyncedLyricLine(
    text: String,
    isCurrent: Boolean,
    distance: Int,
) {
    val targetAlpha = when {
        isCurrent -> 1f
        distance == 1 -> 0.38f
        distance == 2 -> 0.28f
        else -> 0.20f
    }
    val targetScale = if (isCurrent) 1.04f else 1.0f

    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(SETTLE_MILLIS, easing = FastOutSlowInEasing),
        label = "lyricAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(SETTLE_MILLIS, easing = FastOutSlowInEasing),
        label = "lyricScale",
    )

    Text(
        text = text,
        style = TextStyle(
            fontSize = LINE_FONT_SIZE,
            lineHeight = LINE_LINE_HEIGHT,
            fontWeight = if (isCurrent) FontWeight.W600 else FontWeight.W400,
            letterSpacing = (-0.2).sp,
            textAlign = TextAlign.Center,
        ),
        color = Color.White.copy(alpha = alpha),
        textAlign = TextAlign.Center,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    )
}

/**
 * How far the running playhead may drift from the player's before the player's wins.
 */
private const val PLAYHEAD_TOLERANCE_MS = 500L

/**
 * Whether the position the player reports should replace the one being extrapolated.
 */
internal fun shouldAdoptReportedPosition(reportedMs: Long, runningMs: Long): Boolean =
    kotlin.math.abs(reportedMs - runningMs) > PLAYHEAD_TOLERANCE_MS

/**
 * A playhead that runs on the frame clock, corrected by the player's reported position.
 */
@Composable
private fun rememberPlayhead(reportedMs: Long, isPlaying: Boolean): () -> Long {
    val running = remember { mutableLongStateOf(reportedMs) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            running.longValue = reportedMs
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            running.longValue += (now - last) / 1_000_000L
            last = now
        }
    }

    LaunchedEffect(reportedMs) {
        if (shouldAdoptReportedPosition(reportedMs, running.longValue)) {
            running.longValue = reportedMs
        }
    }

    return { running.longValue }
}

/**
 * Three dots for an instrumental break.
 */
@Composable
private fun GapDots(fromMs: Long, untilMs: Long, playhead: () -> Long) {
    val filled = remember(fromMs, untilMs) {
        derivedStateOf {
            val span = (untilMs - fromMs).coerceAtLeast(1L)
            ((playhead() - fromMs).toFloat() / span).coerceIn(0f, 1f)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP_DOT_GAP),
    ) {
        repeat(GAP_DOTS) { index ->
            val lit = (filled.value * GAP_DOTS - index).coerceIn(0f, 1f)
            val dotAlpha = GAP_DOT_REST_ALPHA + (1f - GAP_DOT_REST_ALPHA) * lit

            Box(
                modifier = Modifier
                    .size(GAP_DOT_SIZE)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = dotAlpha)),
            )
        }
    }
}

/**
 * Where the playhead is, as an index into the lines.
 */
private fun Lyrics.currentIndex(positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var index = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= positionMs) index = i else break
    }
    return index
}

/**
 * The shimmer shown while the words are still being fetched.
 */
@Composable
private fun LyricsSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "lyricsSkeleton")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeletonPhase",
    )

    Column(
        modifier = modifier.padding(horizontal = 30.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        repeat(2) { Spacer(Modifier.height(34.dp)) }
        repeat(5) { index ->
            val lit = 0.4f + 0.35f * sin((phase * 2f * Math.PI + index * 0.7f).toFloat())
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (index % 2 == 0) 0.84f else 0.66f)
                    .height(24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.05f + 0.05f * lit)),
            )
        }
    }
}
