package dev.sonora.ui

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sonora.lyrics.LyricLine
import dev.sonora.lyrics.Lyrics
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.flow.first

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
 * How many lines of a lyric are drawn before the rest is dropped.
 *
 * Two short of the screen, because the pane is a square and the lines are centred in it: four lines
 * of 29sp do not fit inside a square on a phone with the words inset, and a fourth line is clipped
 * through the middle of its own text. A line that does not fit is a line that is not there.
 */
private const val MAX_LINES = 2

/**
 * How bright and how sharp the lines away from the one being sung are, indexed by distance.
 *
 * Blur as well as alpha, because dimming alone leaves a lyric sheet rather than a song: the lines
 * you are not reading have to be *behind* the one you are, and a soft edge is what says behind. The
 * first entry is the current line and is deliberately sharp — a blurred lyric being sung is a lyric
 * nobody can read.
 *
 * The drop after the first is a cliff rather than a slope, on purpose. The words being sung are the
 * only thing on this pane that is being read, and everything else exists to be read *past* on the way
 * to them; a stack that fades gently leaves all of it legible at once, and the eye has four equally
 * bright lines to choose between. Dimmed this far, the sung line is the only one anybody looks at,
 * and the ones around it are still there to be found.
 *
 * Both arrays are *animated* toward the entry for the line's distance rather than applied as it
 * stands. Applied as it stands they snap, and a snap on a blurred line is the most visible kind: the
 * blur is recomputed, the layer is rebuilt, and the line jumps a pixel and a half while the one being
 * sung is gliding. See [SungLine].
 */
private val LINE_FALLOFF_ALPHA = floatArrayOf(1f, 0.52f, 0.36f, 0.25f, 0.18f)
private val LINE_FALLOFF_BLUR = floatArrayOf(0f, 0.6f, 1.1f, 1.8f, 2.4f)

/**
 * How many lines either side of the one being sung are blurred at all.
 *
 * Every blurred line is a real `RenderEffect` over a layer of its own, recomputed as its radius
 * moves, and a phone cannot afford one per visible line: past the first couple the effect is not
 * read as depth, it is read as the frame dropping. Further out the lines are dimmed only, which is
 * enough to put them behind and costs nothing.
 */
private const val BLURRED_LINES = 2

/**
 * How long a line takes to reach the brightness and softness of its new distance.
 *
 * Long enough to cover the swap so it never shows as a jump, short enough to finish before the line
 * it is following is sung. A tenth of a second is the floor below which it reads as a snap anyway.
 */
private const val SETTLE_MILLIS = 180

/** Eased so the settle starts and ends softly rather than at a constant rate. */
private val SETTLE_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * How large the line being sung is drawn against the ones around it.
 *
 * Six per cent, which is small enough that the line does not appear to lunge when the song moves on
 * and large enough to be seen in peripheral vision while your eyes are elsewhere. Animated rather
 * than switched, so the handover is the outgoing line settling back as the incoming one grows.
 */
private const val ACTIVE_SCALE = 1.06f
private const val INACTIVE_SCALE = 0.95f

/**
 * How long the list takes to settle on a new line.
 *
 * Short enough to be finished before the line is sung, and long enough to be seen: a list that
 * jumps is not a list that is following anything, it is a list that is being rebuilt.
 */
private const val SCROLL_MILLIS = 420f

/** How much of the pane's height the top and bottom fades each take. */
private const val FADE = 0.16f

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
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    // The pane is a square as wide as the screen, so its side is the screen's width — and half of
    // that is the distance from the top of the pane to the line the list puts in the middle of it.
    val paneSide = LocalConfiguration.current.screenWidthDp.dp

    var following by remember { mutableStateOf(true) }
    // Whether *we* are the ones moving the list. Without it the pane cannot tell a finger from its
    // own animation, so the very first line change switches the following off and the words stop
    // moving for the rest of the song.
    var animating by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // What the wipe is drawn against: a playhead on the frame clock, corrected by the player's own
    // reported position. The reported position decides which line is current and when the list
    // moves; the running one decides where the bright part of a line stops. See [rememberPlayhead].
    val playhead = rememberPlayhead(reportedMs = positionMs, isPlaying = isPlaying)

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

        val from = listState.firstVisibleItemIndex
        val fromOffset = listState.firstVisibleItemScrollOffset

        // The current line sits in the middle of the pane — its *middle*, not its top. A lyric of a
        // song is often two rows at this size, and putting the top of it in the centre pushes the
        // words being sung below the centre and leaves the line above sitting in it.
        //
        // Which is a height, and a line's height is not known until it has been laid out — so it is
        // measured on a second pass. The first pass puts the line at the top of the pane, which
        // guarantees it is laid out; the second centres it on what it turned out to be. Reading the
        // height out of the layout info before the line has ever been on screen silently gets zero
        // and leaves the top of every line in the middle, which is how the words being sung end up
        // below the words already sung.
        // Puts the line in the middle of the pane, and does it by measurement rather than by
        // arithmetic.
        //
        // Where a row lands for a given scroll offset depends on how the list accounts for its own
        // content padding, which is the library's business and not something worth re-deriving here
        // — and getting it wrong does not look wrong, it looks like the words being sung sitting
        // below the words already sung. So the row is scrolled, its real offset is read back, and
        // the difference is scrolled away. Two passes is always enough; the loop stops when there is
        // nothing left to correct.
        suspend fun centreOn(target: Int): Int {
            val wanted = { height: Int ->
                listState.layoutInfo.viewportSize.height / 2 - height / 2
            }
            suspend fun measured(): Int? = snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.offset
            }.first { it != null }

            // And the row has to exist before it can be measured, which is a layout away: a frame
            // callback runs before the layout that frame goes on to do.
            listState.scrollToItem(target, 0)
            var height = 0
            repeat(3) {
                val seen = snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.size
                }.first { it != null && it > 0 }
                height = seen ?: 0
                val here = measured() ?: return@repeat
                val error = here - wanted(height)
                if (abs(error) <= 1) return@repeat
                listState.scrollToItem(target, listState.firstVisibleItemScrollOffset - error)
            }
            measured() ?: 0
            return listState.firstVisibleItemScrollOffset
        }

        // A jump between far-apart lines is not animated: easing across forty lines is a scrollbar
        // moving, and none of the words it passes are the ones being sung.
        if (abs(from - target) > listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)) {
            listState.scrollToItem(target, centreOn(target))
            return@LaunchedEffect
        }

        // Otherwise measure where the line belongs, put the list back where it was, and glide to it.
        val targetOffset = centreOn(target)
        listState.scrollToItem(from, fromOffset)
        withFrameNanos { }

        // The list's own animated scroll is gone from this version of the library, so the movement
        // is driven here instead: a hard start and a long tail over about a third of a second, so
        // the words arrive and then glide to rest. The other way round reads as a drag, and a drag
        // is something the listener just did to a list that is meant to be following the song.
        //
        // Timed off the frame clock rather than counted in frames. A step of frames per step of
        // progress makes the same movement take half as long on a fast phone and twice as long on a
        // slow one, which is the opposite of what "the words arrive and then glide to rest" means.
        animating = true
        val ease = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)
        val totalNanos = (SCROLL_MILLIS * 1_000_000L).toLong()
        try {
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val t = ((now - start).toFloat() / totalNanos).coerceIn(0f, 1f)
                val eased = ease.transform(t)

                val item = (from + (target - from) * eased).let { it.toInt() }
                val within = ((from + (target - from) * eased) - item)
                    .coerceIn(0f, 1f) * (listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == item }?.size ?: 1)
                val offset = (fromOffset + (targetOffset - fromOffset) * eased +
                    within).toInt()

                listState.scrollToItem(item, offset)
                if (t >= 1f) break
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
                ) { following = !following }
                // The list runs past the top and the bottom of the square and is faded where it
                // leaves, rather than cut. A lyric sliced through the middle of its own words at the
                // edge of the pane reads as a drawing fault; a lyric fading out reads as more song.
                // The fade is on the lyrics themselves and not on the pane's background, so it is
                // the same over the cover, over the backdrop and over whatever the page behind it is.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Transparent at the two edges and opaque through the middle: a destination-in
                    // keeps what the source covers, so a mask that fades *from* the middle *to* the
                    // edges is the one that erases the words and leaves the margins.
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
                // Half the pane of room at each end, so the *first* line can reach the middle of the
                // pane as well as the last — and so "the middle" is a place in the content rather
                // than an offset that has to be recomputed for every line and every pane size.
                contentPadding = PaddingValues(vertical = paneSide / 2),
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
                            GapDots(
                                fromMs = line.timeMs,
                                untilMs = nextStart ?: line.endMs ?: line.timeMs,
                                playhead = playhead,
                            )
                        } else {
                            SungLine(
                                line = line,
                                nextStartMs = nextStart,
                                playhead = playhead,
                                isCurrent = index == current,
                                alpha = LINE_FALLOFF_ALPHA[distance],
                                // Only the lines right around the one being sung are blurred. Past
                                // that the dimming alone puts them behind, and a `RenderEffect` per
                                // visible line is what a lyric pane does not need to be paying for.
                                blur = if (distance in 1..BLURRED_LINES) {
                                    LINE_FALLOFF_BLUR[distance].dp
                                } else {
                                    0.dp
                                },
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
 *
 * The sweep reads the playhead inside the draw, not in composition. That is the difference between
 * the words lighting up smoothly along a line and the words lighting up four times in a line: a
 * playhead read during composition turns every frame of the sweep into a recomposition, a re-measure
 * of two pieces of text and a rebuild of an offscreen layer, and the frame budget is gone before
 * anything is drawn. Read in the draw phase, the same movement costs one rectangle.
 */
@Composable
private fun SungLine(
    line: LyricLine,
    nextStartMs: Long?,
    playhead: () -> Long,
    isCurrent: Boolean,
    alpha: Float,
    blur: Dp,
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
    fun sungFractionAt(nowMs: Long): Float = if (isCurrent) {
        line.sungFraction(nowMs, nextStartMs)
    } else {
        val end = line.endMs ?: nextStartMs ?: line.timeMs
        if (nowMs >= end) 1f else 0f
    }

    // Only the part of the line that is definitely sung when it is composed; the wipe itself is
    // drawn against the playhead. Read once here, so the glow does not flicker on and off across the
    // few frames around a line's first word.
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val bloom = isCurrent && sungFractionAt(playhead()) > 0f && canBlur

    // Eased toward the line's place in the stack rather than set to it, for the two reasons that
    // matter: an alpha that jumps is visible, and a blur radius that jumps rebuilds a RenderEffect
    // on the frame it jumps — which is the frame the list is also scrolling, so the two stutter
    // together. Eased, the radius changes a handful of times across the settle and the line slides
    // into its place while the words go on being sung.
    val settledAlpha by animateFloatAsState(
        targetValue = alpha,
        animationSpec = tween(SETTLE_MILLIS, easing = SETTLE_EASING),
        label = "lineAlpha",
    )
    val settledBlur by animateDpAsState(
        targetValue = blur,
        animationSpec = tween(SETTLE_MILLIS, easing = SETTLE_EASING),
        label = "lineBlur",
    )

    val settledScale by animateFloatAsState(
        targetValue = if (isCurrent) ACTIVE_SCALE else INACTIVE_SCALE,
        animationSpec = tween(SETTLE_MILLIS, easing = SETTLE_EASING),
        label = "lineScale",
    )

    Box(
        modifier = Modifier
            .padding(GLOW_ROOM)
            .graphicsLayer {
                scaleX = settledScale
                scaleY = settledScale
            },
    ) {
        if (bloom) {
            Text(
                text = line.text,
                style = style,
                textAlign = TextAlign.Center,
                maxLines = MAX_LINES,
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
                    this.alpha = settledAlpha
                }
                .then(
                    if (settledBlur > 0.dp) {
                        Modifier.blur(
                            settledBlur,
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
                // The whole line, dim: the part the listener is reading ahead to. This is also
                // where the line is measured, because a wipe can only be drawn across a line that
                // fits on one line of its own.
                var wraps by remember(line.text) { mutableStateOf(false) }
                // Where the per-frame read of the playhead is left for the draw block to find. One
                // element rather than a field so nothing can mutate it but the two lambdas above.
                val sweep = remember(line.text) { FloatArray(1) }
                Text(
                    text = line.text,
                    style = style.copy(color = Color.White.copy(alpha = UNSUNG_ALPHA)),
                    textAlign = TextAlign.Center,
                    maxLines = MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { wraps = it.lineCount > 1 },
                )

                // The sung part over it, masked to a feather rather than clipped to an edge. Drawn
                // unconditionally — including before a single word is sung, when it is masked away
                // entirely — because whether the line is sung at all is a per-frame question here,
                // and a composable that comes and goes with it is the recomposition this is all
                // arranged to avoid.
                Text(
                    text = line.text,
                    style = style.copy(color = Color.White),
                    textAlign = TextAlign.Center,
                    maxLines = MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .matchParentSize()
                        // The playhead is read *here*, in a graphics layer, and not in the draw
                        // block below. `drawWithContent` runs its block in the cache phase — once —
                        // so a read in there is never observed and the wipe never moves. A graphics
                        // layer's block runs per draw, so this is the read that repaints the line;
                        // the value is handed to the mask through [sweep] rather than recomposed in.
                        .graphicsLayer {
                            val sung = sungFractionAt(playhead())
                            sweep[0] = sung
                            // A wrapped line lights as a whole, because a wipe across two rows of
                            // different widths has no meaning: by the time the boundary has crossed
                            // the wider row it has not reached the narrower one, so a half-sung
                            // sentence appears with the bright part in an arbitrary place.
                            this.alpha = if (wraps) sung else 1f
                        }
                        .drawWithContent {
                            drawContent()
                            val fraction = sweep[0]
                            if (fraction <= 0f || wraps) return@drawWithContent
                            val edge = WIPE_FEATHER.toPx()
                            val at = (fraction * size.width).coerceIn(0f, size.width)
                            val cut = (at - edge).coerceAtLeast(0f)
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0f to Color.Black,
                                    cut to Color.Black,
                                    at to Color.Transparent,
                                    size.width to Color.Transparent,
                                ),
                                // The gradient's own first and last stops cover everything outside
                                // them, so the whole mask is one rectangle rather than two clips
                                // with a gap between them.
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
            }
        }
    }
    }

/**
 * How far the running playhead may drift from the player's before the player's wins.
 *
 * The running one is a per-frame extrapolation, and it drifts for the ordinary reasons: the polling
 * that reports the real position is not on the frame clock, a track change restarts it, and a seek
 * moves it by a minute at once. Half a second is chosen to sit above the drift of a frame counter
 * and below anything a listener would call the words late — and the only thing it is ever used for
 * is the karaoke wipe, where a quarter of a line of error is invisible.
 */
private const val PLAYHEAD_TOLERANCE_MS = 500L

/**
 * Whether the position the player reports should replace the one being extrapolated.
 *
 * A plain disagreement test rather than "always follow", because always following is what made the
 * wipe move in half-second steps: the reported position only changes twice a second, and re-seeding
 * from it every time snapped the wipe back to a staircase.
 */
internal fun shouldAdoptReportedPosition(reportedMs: Long, runningMs: Long): Boolean =
    kotlin.math.abs(reportedMs - runningMs) > PLAYHEAD_TOLERANCE_MS

/**
 * A playhead that runs on the frame clock, corrected by the player's own position.
 *
 * The position the player reports is polled a couple of times a second, which is the right cadence
 * for a progress bar and the wrong one for a wipe moving along a line of text: sampled that coarsely
 * the words light up in visible steps. So the position is extrapolated per frame between reports and
 * a report only replaces it when it disagrees by more than it could have drifted.
 *
 * Returns a getter rather than a value, because the point is that reading it does not recompose
 * anything: the sweep is drawn in the draw phase, so moving it costs a redraw of one rectangle and
 * not a re-measure of the text under it.
 */
@Composable
private fun rememberPlayhead(reportedMs: Long, isPlaying: Boolean): () -> Long {
    val running = remember { mutableLongStateOf(reportedMs) }

    // The frame loop, and nothing else: it only runs while there is sound to be counting, so a paused
    // pane is not drawing sixty times a second to say nothing.
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

    // And the correction, from whichever of the two is telling the truth at the moment.
    LaunchedEffect(reportedMs) {
        if (shouldAdoptReportedPosition(reportedMs, running.longValue)) {
            running.longValue = reportedMs
        }
    }

    return { running.longValue }
}

/**
 * Three dots for an instrumental break.
 *
 * A pause has to be visible as a pause. A blank gap in a lyric sheet reads as a line that failed to
 * load, and the dots are the one thing on the pane that says *nothing is being sung here* on
 * purpose.
 *
 * Phase comes from the song rather than from a clock of its own, so a break fills in time with the
 * music and stops dead when the music does. An animation on its own timer keeps pulsing over a
 * paused pane, which says the song is still happening when it is not.
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
            // Each dot's own slice of the break, so they light in turn rather than together.
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
