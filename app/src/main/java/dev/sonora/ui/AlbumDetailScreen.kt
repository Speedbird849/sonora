package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryGrouping
import dev.sonora.backend.SearchQueries

/** One album's tracks, reached from the Albums list. */
@Composable
fun AlbumDetailScreen(
    album: LibraryGrouping.Album,
    onBack: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onFindMore: (String) -> Unit,
    /**
     * The album's own cover, where it is known.
     *
     * Passed in rather than taken off the first track because a page fetched from YouTube Music
     * has no local file behind it, and the search that found the album already has the right
     * picture — which is the album's, not whichever track happened to be listed first.
     */
    artworkUrl: String? = null,
    /**
     * Whether anything is playing, so the list can stop short of the mini player.
     *
     * Not asked for here: the page is a plain function of its arguments, and every list that has to
     * clear the bars takes this as a flag rather than reaching for the player.
     */
    playing: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val artwork = rememberArtworkAt(artworkUrl, px = CARD_ART_PX)
                    ?: album.tracks.firstOrNull()?.let { rememberTrackArtwork(it, px = CARD_ART_PX) }
                if (artwork != null) {
                    Image(
                        bitmap = artwork,
                        contentDescription = "Album artwork",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }

            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (album.tracks.size == 1) {
                        "1 track"
                    } else {
                        "${album.tracks.size} tracks"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.padding(start = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onPlayFrom(0) },
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play album",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }

            // The library is the only catalogue this app has, so "find more" is the bridge from
            // what you own to what the network holds.
            TextButton(
                onClick = { onFindMore(SearchQueries.forAlbum(album.name, album.artist)) },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(text = "Find more", modifier = Modifier.padding(start = 8.dp))
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            // Room for the tab bar, and for the mini player when one is up. Without it the last
            // track of an album sits underneath them, which on a page opened from a search result
            // looks like the page is cut off rather than like a list that continues.
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = listBottomPadding(playing) + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(album.tracks, key = { _, track -> track.key }) { index, track ->
                TrackListRow(
                    track = track,
                    // The album has one picture and the listing has twelve rows, so the rows are
                    // numbered rather than each carrying a copy of the same cover.
                    trackNumber = index + 1,
                    meta = listOfNotNull(track.artist, track.album).joinToString("  \u00b7  "),
                    onClick = { onPlayFrom(index) },
                )
            }
        }
    }
}
