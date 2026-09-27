package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryTrack

/**
 * A playlist's cover, built out of the covers of the tracks in it.
 *
 * Four of them, in a grid, the way a playlist with no artwork of its own is drawn elsewhere: a
 * playlist is a set of records and has no picture, and four of its own is the only honest way to
 * draw it. A single track's cover stands for a playlist that holds one, because four copies of one
 * picture is a pattern rather than a cover.
 *
 * Short lists fall back on their own covers rather than leaving cells empty: three tracks give two
 * and the last cell takes the first again, which is what the familiar grid does, and an empty square
 * reads as a cover that failed to load.
 *
 * One composable rather than a rule, because the drawing and the rules are the same thing here: what
 * goes in each cell *is* the mosaic.
 */
@Composable
internal fun PlaylistMosaic(
    tracks: List<LibraryTrack>,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(4.dp),
    icon: ImageVector? = null,
) {
    // Asked for at tile size, not row size: a 52dp thumbnail stretched across a card is what makes
    // every cover in a grid look the same shade of soft.
    // The ones that are not here yet are dropped rather than left as holes, so the grid is a grid
    // from the first frame and fills in as they arrive instead of showing a quarter of itself — and
    // so a cell can never be asked for a picture that is not there.
    val covers = tracks.take(MOSAIC_TRACKS).mapNotNull { track ->
        track.artworkUrl?.let { rememberArtworkAt(it, px = CARD_ART_PX) }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val drawn = covers.size
        when {
            drawn == 0 -> icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }

            // One picture is a picture, not a pattern: four copies of it would read as a mistake.
            drawn == 1 -> Image(
                bitmap = covers.first(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            else -> Column(modifier = Modifier.fillMaxSize()) {
                repeat(MOSAIC_ROWS) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        repeat(MOSAIC_COLUMNS) { column ->
                            Image(
                                // Rounded with the covers there are, so three tracks give two and
                                // the last cell takes the first again rather than staying empty.
                                bitmap = covers[(row * MOSAIC_COLUMNS + column) % drawn],
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * How many of a playlist's covers a cover is built from.
 *
 * Four, and in a two-by-two: the number is a convention rather than a measurement, and it is the
 * smallest number that stops a playlist looking like one album.
 */
internal val MOSAIC_TRACKS = 4
private const val MOSAIC_ROWS = 2
private const val MOSAIC_COLUMNS = 2

/**
 * The icon a playlist with no picture of its own is drawn with, for the screens that pass one in.
 */
internal fun playlistIcon(reserved: Boolean): ImageVector =
    if (reserved) Icons.Filled.Favorite else Icons.AutoMirrored.Filled.QueueMusic
