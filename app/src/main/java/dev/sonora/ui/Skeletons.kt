package dev.sonora.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Grey stand-ins for content that is still on the wire, laid out to the same metrics as the real
 * rows so nothing jumps when the data lands.
 *
 * A search against a remote catalogue and a library that has to scan a folder both spend long
 * enough on the wire that a blank screen reads as broken, which is the whole reason these exist.
 * The shelf and carousel placeholders that would go with the rest of the layout are not here yet:
 * the rows are what a search and a library actually show while they are waiting.
 */
private const val SHIMMER_PERIOD_MS = 1400

private val BlockShape = RoundedCornerShape(4.dp)
private val LineShape = RoundedCornerShape(4.dp)

// Ragged widths, so a run of rows reads as text rather than as a barcode. Without this a screen of
// identical bars looks like a rendering fault rather than like something loading.
private val TitleWidths = listOf(0.68f, 0.46f, 0.58f, 0.74f, 0.52f)
private val SubtitleWidths = listOf(0.34f, 0.44f, 0.27f, 0.38f, 0.31f)

/**
 * One placeholder block, with a highlight sweeping across it.
 *
 * The sweep is read inside the draw block rather than the composable body: a screenful of these
 * would otherwise recompose on every animation frame, and all any of them needs per frame is a
 * fresh gradient.
 */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: Shape = BlockShape) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurfaceVariant
        .copy(alpha = 0.16f)
        .compositeOver(base)

    val sweep = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SHIMMER_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    Box(
        modifier
            .clip(shape)
            .drawWithCache {
                // The band travels from fully off one edge to fully off the other, which leaves a
                // beat of flat grey between passes rather than a highlight permanently parked
                // somewhere on the block.
                val band = size.width * 0.5f
                val startX = -band + sweep.value * (size.width + band * 2)
                val brush = Brush.horizontalGradient(
                    colors = listOf(base, highlight, base),
                    startX = startX,
                    endX = startX + band,
                )
                onDrawBehind { drawRect(brush) }
            },
    )
}

/** A placeholder for one line of text, sized as a fraction of its parent. */
@Composable
private fun SkeletonLine(fraction: Float, height: Dp, modifier: Modifier = Modifier) {
    ShimmerBox(
        modifier = modifier.fillMaxWidth(fraction).height(height),
        shape = LineShape,
    )
}

/**
 * Stands in for one result row, down to the 56dp of artwork, the 12dp of text inset and the 7dp
 * either side vertically — the same numbers [YoutubeRow] and [ResultRow] use, so the rows that
 * replace these land without the list shifting under a thumb.
 */
@Composable
fun SongRowSkeleton(index: Int = 0, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShimmerBox(Modifier.size(56.dp), BlockShape)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SkeletonLine(fraction = TitleWidths[index % TitleWidths.size], height = 14.dp)
            Spacer(Modifier.height(7.dp))
            SkeletonLine(fraction = SubtitleWidths[index % SubtitleWidths.size], height = 11.dp)
        }
    }
}

/** A run of row placeholders, for a result list that has been asked for but has not answered. */
fun LazyListScope.songListSkeleton(
    count: Int = 8,
    keyPrefix: String = "skeleton:song",
) {
    items(count, key = { "$keyPrefix:$it" }) { index ->
        SongRowSkeleton(index = index)
    }
}
