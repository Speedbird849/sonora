package dev.sonora.ui

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sonora.lyrics.LyricLine
import dev.sonora.lyrics.Lyrics
import kotlin.math.abs
import kotlin.math.sin

/**
 * How far the part of a line that has not been sung yet is held back.
 *
 * Not lower than this: a dim half that is too faint stops reading as "not yet" and starts reading
 * as a printing fault, and the words ahead of the highlight are the ones a listener reads ahead to.
 */
private const val UNSUNG_ALPHA = 0.45f

/**
 * The bloom behind the line being sung, at its very strongest.
 *
 * Well under half strength, because the glow is drawn from the same white as the words: at full
 * alpha it stops being light and starts being a second, badly printed copy of them.
 */
private const val GLOW_ALPHA = 0.42f

/**
 * How far the bloom spreads off a letter, and the room kept for it.
 *
 * Tight, and paired with the room: a blur is computed on its layer's own bitmap, so a glow with
 * nowhere to go is clipped flat at the edge of the text — which reads as a hard outline rather than
 * as light coming off it.
 */
private val GLOW_RADIUS = 5.dp
private val GLOW_ROOM = 8.dp

/** How wide the edge of the sweep is, and therefore how far ahead of the words it has to start. */
private val WIPE_FEATHER = 30.dp

/** The sung words, in the player's own type. */
private val LINE_FONT_SIZE = 23.sp
private val LINE_LINE_HEIGHT = 29.sp

/**
 * How bright and how sharp the lines away from the one being sung are, indexed by distance.
 *
 * Blur as well as alpha, because dimming alone leaves a lyric sheet rather than a song: the lines
 * you are not reading have to be *behind* the one you are, and a soft edge is what says behind. The
 * first entry is the current line and is deliberately sharp — a blurred lyric being sung is a lyric
 * nobody can read.
 */
private val LINE_FALLOFF_ALPHA = floatArrayOf(1f, 0.8f, 0.7f, 0.58f, 0.46f)
private val LINE_FALLOFF_BLUR = floatArrayOf(0f, 0.5f, 1f, 1.6f, 2.2f)

/** How large a line that is not the current one is drawn. */
private const val INACTIVE_SCALE = 0.98f

/**
 * How long the list takes to settle on a new line.
 *
 * Short enough to be finished before the line is sung, and long enough to be seen: a list that
 * jumps is not a list that is following anything, it is a list that is being rebuilt.
 */
private const val SCROLL_MILLIS = 420f

/** The dots shown through an instrumental break. */
private const val GAP_DOTS = 3
private val GAP_DOT_SIZE = 5.dp
private val GAP_DOT_GAP = 7.dp
private const val GAP_DOT_REST_ALPHA = 0.3f

/**
 * The lyrics of the track being played, following the playhead.
 *
 * Three things happen here, and they are the whole of what makes a lyric pane feel like one:
 *
 * - **The list follows.** The current line settles above the middle of the screen a moment *before*
 *   it is sung, eased, so the words are readable at the pace they are sung rather than arriving with
 *   the voice.
 * - **The current line is swept.** Its sung part is drawn at full strength over a dimmer copy of the
 *   whole line, and the boundary between them is a feather rather than an edge. Per word where the
 *   source carries word timing, per line where it does not.
 * - **The other lines fall away.** Alpha and blur by distance, so the line being sung is the only
 *   sharp thing on the screen.
 *
 * Tapping switches it to browsing, and the list stops following until it is tapped again. A pane
 * that snaps back the instant a finger lands on it cannot be read from.
 */
@Composable
internal fun LyricsPane(
    lyrics: Lyrics,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    var following by remember { mutableStateOf(true) }
    // Whether *we* are the ones moving the list. Without it the pane cannot tell a finger from its
    // own animation, so the very first line change switches the following off and the words stop
    // moving for the rest of the song.
    var animating by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val current = remember(lyrics, positionMs) { lyrics.currentIndex(positionMs) }

    // A drag is what counts as reading, not the mere fact that the list is not at the top. Watched
    // rather than read off the scroll position, because a programmatic scroll moves that too and
    // would stop the following on every line.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to animating }
            .collect { (moving, ours) -> if (moving && !ours) following = false }
    }

    LaunchedEffect(lyrics, current, following) {
        if (!following) return@LaunchedEffect
        val target = current
        if (target < 0 || lyrics.lines.isEmpty()) return@LaunchedEffect

        // The current line settles a little above the middle, so the words being sung are the ones
        // nearest the thumb rather than the ones behind the hand.
        val targetOffset = -(listState.layoutInfo.viewportSize.height * 0.42f).toInt()
        val from = listState.firstVisibleItemIndex
        val fromOffset = listState.firstVisibleItemScrollOffset

        // A jump between far-apart lines is not animated: easing across forty lines is a scrollbar
        // moving, and none of the words it passes are the ones being sung.
        if (abs(from - target) > listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)) {
            listState.scrollToItem(target, targetOffset)
            return@LaunchedEffect
        }

        // The list's own animated scroll is gone from this version of the library, so the movement
        // is driven here instead: a hard start and a long tail over about a third of a second, so
        // the words arrive and then glide to rest. The other way round reads as a drag, and a drag
        // is something the listener just did to a list that is meant to be following the song.
        animating = true
        val step = 20L
        var elapsed = 0f
        try {
            while (elapsed < SCROLL_MILLIS) {
                elapsed += step
                val t = (elapsed / SCROLL_MILLIS).coerceIn(0f, 1f)
                val eased = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f).transform(t)

                val item = (from + (target - from) * eased).let { it.toInt() }
                val within = ((from + (target - from) * eased) - item)
                    .coerceIn(0f, 1f) * (listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == item }?.size ?: 1)
                val offset = (fromOffset + (targetOffset - fromOffset) * eased +
                    within).toInt()

                listState.scrollToItem(item, offset)
                withFrameMillis { }
            }
        } finally {
            listState.scrollToItem(target, targetOffset)
            animating = false
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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { following = !following },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 120.dp),
            ) {
                itemsIndexed(lyrics.lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
                    val distance = abs(index - current).coerceIn(0, LINE_FALLOFF_ALPHA.lastIndex)
                    val nextStart = lyrics.lines.getOrNull(index + 1)?.timeMs

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (line.isGap) {
                            GapDots()
                        } else {
                            SungLine(
                                line = line,
                                nextStartMs = nextStart,
                                positionMs = positionMs,
                                isCurrent = index == current,
                                alpha = LINE_FALLOFF_ALPHA[distance],
                                blur = LINE_FALLOFF_BLUR[distance],
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One line, with the sung part swept over the whole of it.
 *
 * Three copies of the same text, layered: a blurred one that is the light coming off the words, a
 * dim one that is the whole line, and a bright one clipped to what has been sung. Drawn three times
 * rather than as a moving box, because a box has edges and a sung line does not.
 */
@Composable
private fun SungLine(
    line: LyricLine,
    nextStartMs: Long?,
    positionMs: Long,
    isCurrent: Boolean,
    alpha: Float,
    blur: Float,
) {
    val style = remember {
        TextStyle(
            fontSize = LINE_FONT_SIZE,
            lineHeight = LINE_LINE_HEIGHT,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.3).sp,
            textAlign = TextAlign.Center,
        )
    }

    // A finished line stays lit, so scrolling back through the verse shows what has been sung rather
    // than a page of dim words with no memory of the song.
    val fraction = if (isCurrent) {
        line.sungFraction(positionMs, nextStartMs)
    } else {
        val end = line.endMs ?: nextStartMs ?: line.timeMs
        if (positionMs >= end) 1f else 0f
    }

    // A blur needs a real RenderEffect, and silently draws nothing below API 31 — which would leave
    // the fall-off looking like a mistake rather than like a missing effect.
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val bloom = isCurrent && fraction > 0f && canBlur

    Box(
        modifier = Modifier
            .padding(GLOW_ROOM)
            .graphicsLayer {
                if (!isCurrent) {
                    val scale = INACTIVE_SCALE
                    scaleX = scale
                    scaleY = scale
                }
            },
    ) {
        if (bloom) {
            Text(
                text = line.text,
                style = style,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .graphicsLayer { this.alpha = GLOW_ALPHA }
                    .blur(GLOW_RADIUS, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded),
            )
        }

        Column(
            modifier = Modifier
                .graphicsLayer {
                    // Offscreen, because the sweep masks with a blend mode and a blend mode with no
                    // layer of its own reaches through the copy and erases the window behind it.
                    compositingStrategy = CompositingStrategy.Offscreen
                    this.alpha = alpha
                }
                .then(
                    if (blur > 0f && canBlur) {
                        Modifier.blur(
                            blur.dp,
                            androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            // The two copies are in one Box so they are guaranteed to be laid out identically: the
            // dim one sets the size, the bright one is measured against it. A mask a hair out of
            // line with the text it is masking is a visible smear, and two separate Texts in a
            // Column are not guaranteed to line up — a wrap point moved by one word is enough.
            Box {
                // The whole line, dim: the part the listener is reading ahead to.
                Text(
                    text = line.text,
                    style = style.copy(color = Color.White.copy(alpha = UNSUNG_ALPHA)),
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )

                // The sung part over it, masked to a feather rather than clipped to an edge.
                if (fraction > 0f) {
                    Text(
                        text = line.text,
                        style = style.copy(color = Color.White),
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .matchParentSize()
                            .drawWithContent {
                                drawContent()
                                val edge = WIPE_FEATHER.toPx()
                                val cut = (fraction * size.width - edge).coerceAtLeast(0f)
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        0f to Color.Black,
                                        cut to Color.Black,
                                        (fraction * size.width).coerceAtLeast(0f) to Color.Transparent,
                                        size.width to Color.Transparent,
                                    ),
                                    // The gradient's own first and last stops cover everything
                                    // outside them, so the whole mask is one rectangle rather than
                                    // two clips with a gap between them.
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    )
                }
            }
        }
    }
}

/**
 * Three dots for an instrumental break.
 *
 * A pause has to be visible as a pause. A blank gap in a lyric sheet reads as a line that failed to
 * load, and the dots are the one thing on the pane that says *nothing is being sung here* on
 * purpose.
 */
@Composable
private fun GapDots() {
    val transition = rememberInfiniteTransition(label = "gap")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "gapPhase",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP_DOT_GAP),
    ) {
        repeat(GAP_DOTS) { index ->
            // Each dot's own slice of the cycle, so they light in turn rather than together.
            val local = (phase + index.toFloat() / GAP_DOTS) % 1f
            val lit = 1f - (local * GAP_DOTS).coerceIn(0f, 1f) * 0.7f
            val dotAlpha = GAP_DOT_REST_ALPHA + (1f - GAP_DOT_REST_ALPHA) * lit

            Box(
                modifier = Modifier
                    .size(GAP_DOT_SIZE)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = dotAlpha.coerceIn(0f, 1f))),
            )
        }
    }
}

/**
 * Where the playhead is, as an index into the lines.
 *
 * The last line whose stamp has passed, which is what makes a line stay lit through the gap before
 * the next one begins rather than going out early.
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
 *
 * At the real line metrics, and moving, because a static placeholder reads as empty and a moving one
 * reads as working. The period is slow: a fast shimmer is a progress bar, and this is not one.
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
        // Two blocks above where the current line will sit and four below, so the shimmer is in
        // roughly the places the words are about to be.
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
