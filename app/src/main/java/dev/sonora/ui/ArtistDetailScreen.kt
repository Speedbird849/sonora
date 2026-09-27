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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import dev.sonora.ytm.YtmEntity
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryGrouping
import dev.sonora.backend.SearchQueries
import dev.sonora.backend.SonoraBackend

/**
 * One artist: their songs, and their albums.
 *
 * Reached from the Artists list, from a search result, and from the artist line on the player — all
 * three, which is why the page is the same whatever opened it. The tracks are whatever the page has:
 * the ones on the device for a local artist, the ones YouTube Music listed for one that was opened
 * from a search or the player, and both for an artist who is half of each.
 *
 * The albums come from YouTube Music rather than from a release database, because the tracks on
 * this page come from YouTube Music, and a shelf of albums whose titles do not match the songs
 * above it reads as two pages stapled together.
 */
@Composable
fun ArtistDetailScreen(
    artist: LibraryGrouping.Artist,
    /** The artist's own albums and singles, once the page has answered. Empty until it has. */
    remoteAlbums: List<YtmEntity> = emptyList(),
    remoteSingles: List<YtmEntity> = emptyList(),
    /** The artist's own picture, which is theirs rather than any one record's. */
    artworkUrl: String? = null,
    onBack: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onFindMore: (String) -> Unit,
) {
    // Counted over the whole page rather than over the tracks: an artist's five top songs span four
    // albums, and "4 albums" beside a shelf of seven is a contradiction the listener can see.
    val releaseCount = remoteAlbums.size + remoteSingles.size
    val albumCount = if (releaseCount > 0) releaseCount else LibraryGrouping.albums(artist.tracks).size

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
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val artwork = artist.tracks.firstOrNull()?.let { rememberTrackArtwork(it) }
                if (artwork != null) {
                    Image(
                        bitmap = artwork,
                        contentDescription = "Artist artwork",
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
                    text = artist.name,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(
                        if (albumCount == 1) "1 album" else "$albumCount albums",
                        if (artist.tracks.size == 1) "1 track" else "${artist.tracks.size} tracks",
                    ).joinToString("  \u00b7  "),
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
                    contentDescription = "Play artist",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }

            // One album from an artist is exactly when you want the rest of them, so this is the
            // most useful thing the screen can offer next to play.
            TextButton(
                onClick = { onFindMore(SearchQueries.forArtist(artist.name)) },
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

        // The albums, as a lazy item so they scroll with the tracks — but registered from the
        // start, even when there is nothing to show. Adding a new first item to a list that has
        // already been laid out makes the list keep the item that was on top in place, which pushes
        // the new one above the viewport: the shelf was built, and invisible.
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "albums") {
                if (remoteAlbums.isNotEmpty()) {
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        SectionHeader(
                            title = "Albums",
                            subtitle = "From YouTube Music; tap to open",
                        )

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(remoteAlbums, key = { "album:" + it.browseId }) { album ->
                                EntityCard(
                                    entity = album,
                                    onClick = {
                                        onFindMore(SearchQueries.forAlbum(album.title, artist.name))
                                    },
                                )
                            }

                            items(remoteSingles, key = { "single:" + it.browseId }) { single ->
                                EntityCard(
                                    entity = single,
                                    onClick = {
                                        onFindMore(SearchQueries.forAlbum(single.title, artist.name))
                                    },
                                )
                            }
                        }
                    }
                }
            }

            itemsIndexed(artist.tracks, key = { _, track -> track.key }) { index, track ->
                TrackListRow(
                    track = track,
                    meta = listOfNotNull(track.album, track.artist).joinToString("  \u00b7  "),
                    onClick = { onPlayFrom(index) },
                )
            }
        }
    }
}
