package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
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
 */
@Composable
internal fun QueuePanel(
    upNext: UpNext,
    onPlayFrom: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Inset for the status bar, because this panel replaces the player outright and the player's
    // own inset went with it — a queue whose heading sits under the clock looks like a bug in the
    // panel rather than like one in whatever hid the player.
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
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

            if (upNext.following.isNotEmpty()) {
                item(key = "next-heading") { QueueHeading("Next in queue") }

                itemsIndexed(upNext.following, key = { _, t -> t.key }) { position, track ->
                    QueueRow(
                        track = track,
                        isCurrent = false,
                        isPlaying = false,
                        onClick = { onPlayFrom(upNext.index + 1 + position) },
                        onRemove = { onRemove(upNext.index + 1 + position) },
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
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
