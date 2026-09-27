package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.UpNext

/**
 * What is playing and what comes next, in place of the player.
 *
 * A panel rather than a sheet, and in place rather than pushed over: the queue is a thing you look
 * at *while* the record keeps playing, and a page that covers the player hides the position and the
 * controls that are still doing something. The track that is sounding stays at the top, marked,
 * and everything below it is one tap away.
 *
 * Reorderable by holding a row and sliding it. The handle is at the row's own end rather than over
 * the whole row, so a hold that means "read this" is not a hold that means "move it" — but a hold
 * anywhere on the row does move it, because that is what a hand expects and the alternative is a
 * thirty-pixel target.
 */
@Composable
internal fun QueuePanel(
    upNext: UpNext,
    onPlayFrom: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which of the *following* rows is being held, and how far it has been carried. Null until a
    // hold starts, so a drag that has not begun costs nothing.
    var held by remember { mutableStateOf<Int?>(null) }
    var carried by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(0f) }

    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Up next",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            CircleGlyph(
                icon = Icons.Filled.Close,
                contentDescription = "Close the queue",
                onClick = onClose,
            )
        }

        val following = upNext.following
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            val current = upNext.current
            if (current != null) {
                item(key = "now-playing") {
                    QueueHeading("Now playing")
                    QueueRow(
                        track = current,
                        isCurrent = true,
                        isPlaying = true,
                        onClick = { onPlayFrom(upNext.index) },
                        onRemove = null,
                    )
                }
            }

            if (following.isNotEmpty()) {
                item(key = "next-heading") { QueueHeading("Next in queue") }
            }

            itemsIndexed(following, key = { _, t -> t.key }) { position, track ->
                val absolute = upNext.index + 1 + position
                val dragging = held == position

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, if (dragging) carried.roundToInt() else 0) }
                        .zIndex(if (dragging) 1f else 0f)
                        .pointerInput(position) {
                            var offsetY = 0f
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    held = position
                                    carried = 0f
                                    rowHeight = size.height.toFloat()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    offsetY += amount.y
                                    carried = offsetY
                                },
                                onDragEnd = {
                                    val row = rowHeight.takeIf { it > 0f } ?: 1f
                                    // Rounded rather than measured against the live list: a drag
                                    // that crosses half a row is a deliberate move, and one that
                                    // wobbles is not.
                                    val steps = (offsetY / row).toInt()
                                    if (steps != 0) {
                                        onMove(absolute, (absolute + steps).coerceIn(0, following.size))
                                    }
                                    held = null
                                    carried = 0f
                                },
                                onDragCancel = {
                                    held = null
                                    carried = 0f
                                },
                            )
                        },
                ) {
                    QueueRow(
                        track = track,
                        isCurrent = false,
                        isPlaying = false,
                        onClick = { onPlayFrom(absolute) },
                        onRemove = { onRemove(absolute) },
                        onDragHandle = { delta ->
                            held = position
                            carried += delta
                        },
                        isHeld = dragging,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = 0.75f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
    )
}

/**
 * One entry in the queue.
 *
 * Smaller artwork than a list row — 44 rather than 52 — because this is a long list read at a
 * glance, and a queue that only shows six of its own tracks is not much of a queue. The row that is
 * sounding is told three ways: full-strength title, an equaliser mark, and no remove button, because
 * removing the thing that is playing is not a thing anyone means.
 */
@Composable
private fun QueueRow(
    track: LibraryTrack,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    onDragHandle: ((Float) -> Unit)? = null,
    isHeld: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHeld) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(6.dp)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            val artwork = rememberTrackArtwork(track, px = ROW_ART_PX)
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!track.artist.isNullOrBlank()) {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isCurrent) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow,
                contentDescription = "Now playing",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
        }

        if (onDragHandle != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDrag = { change, amount ->
                                change.consume()
                                onDragHandle(amount.y)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Hold and slide to reorder",
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
        }

        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove from queue",
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
