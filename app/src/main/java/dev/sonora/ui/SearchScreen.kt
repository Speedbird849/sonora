package dev.sonora.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import dev.sonora.backend.DownloadState
import dev.sonora.backend.SearchHit
import dev.sonora.backend.SearchFolders
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.SearchQueries
import dev.sonora.ytm.YtmTrack
import dev.sonora.backend.SonoraPlayer
import dev.sonora.backend.SearchSource
import dev.sonora.backend.SearchState
import dev.sonora.backend.toYtm
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SortMode
import dev.sonora.ui.theme.accentText
import dev.sonora.ui.theme.onSurfaceFaint

@Composable
fun SearchScreen() {
    val context = LocalContext.current
    val searchState by SonoraBackend.search.collectAsState()
    val download by SonoraBackend.download.collectAsState()
    val playing = SonoraPlayer.state.collectAsState().value.track != null
    val settings by SonoraBackend.settings.collectAsState()
    val history by SonoraBackend.searchHistory.collectAsState()
    val catalogue by SonoraBackend.catalogue.collectAsState()
    val sources by SonoraBackend.searchSources.collectAsState()

    // Keyed on the committed query so clearing the search clears the box with it, rather than
    // leaving stale text above an empty result list.
    var query by remember(searchState.query) { mutableStateOf(searchState.query) }
    fun runSearch(term: String) {
        query = term
        if (term.isNotBlank()) SonoraBackend.search(context, term.trim())
    }

    // YT Music's sort control, and a *fastest* source is exactly what a P2P result list needs:
    // without it you pick a peer at random and wait.
    val sort = searchState.sort
    fun onSort(mode: SortMode) = SonoraBackend.setSort(mode)

    // Nothing searched yet: the recent queries are the useful thing to show, rather than an
    // instruction to go and do something.
    val showingHistory = searchState.query.isBlank() && !searchState.searching

    // What has been played out of a search, so the page can offer the answer rather than the
    // question. Read here rather than remembered so it survives leaving the tab.
    val recentTracks by SonoraBackend.recentTracks.collectAsState()

    // Either source can be turned off. The search itself still asks both: the catalogue answer is
    // cached, and switching back should not mean waiting for it again.
    val showSoulseek = SearchSource.SOULSEEK in sources
    val showYoutube = SearchSource.YOUTUBE_MUSIC in sources
    val catalogueShown = SearchSource.CATALOGUE in sources && catalogue.isNotEmpty()

    // Asked once, before the first download. Where the files land decides whether they survive
    // uninstalling the app, so it is worth one question rather than a silent default.
    var askingWhere by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf<SearchHit?>(null) }

    val pickFolder = rememberLauncherForActivityResult(        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            SonoraBackend.setDownloadTree(context, uri.toString())
        }

        // Cancelling the picker cancels the download: the question is still unanswered, and the
        // tap is easy to repeat.
        if (uri != null) waiting?.let { SonoraBackend.download(context, it) }
        waiting = null
    }

    /**
     * Looks for one YouTube Music track on the peer network.
     *
     * The whole point of the two sources side by side: YouTube can play a track immediately but
     * only ever at its own bitrate, while the network may be holding a lossless rip of the same
     * recording. So the artist and title go back out as a network search, rather than a download
     * being offered for a stream — the two are different things and only one of them is a file.
     */
    fun peerSearch(track: YtmTrack) {
        SonoraBackend.search(context, SearchQueries.forTrack(track.title, track.artist))
    }

    fun startDownload(hit: SearchHit) {
        if (settings.downloadTreeUri == null && !settings.promptedForDownloadFolder) {
            waiting = hit
            askingWhere = true
        } else {
            SonoraBackend.download(context, hit)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = PAGE_GUTTER, top = 8.dp, bottom = 4.dp),
        )

        SearchField(
            query = query,
            onQueryChange = { query = it },
            onSubmit = { SonoraBackend.search(context, query.trim()) },
        )

        // Ordering only means anything for the peer results, so it is offered only when those are
        // the ones on show.
        if (showSoulseek && (searchState.hits.isNotEmpty() || searchState.searching)) {
            ChipRow {
                SortMode.entries.forEach { mode ->
                    SearchChip(
                        label = mode.label,
                        selected = sort == mode,
                        onClick = { onSort(mode) },
                    )
                }
            }
        }

        if (catalogue.isNotEmpty() || searchState.hits.isNotEmpty() ||
            searchState.youtube.isNotEmpty() || searchState.searching || searchState.youtubeLoading) {
            ChipRow {
                SearchSource.entries.forEach { source ->
                    SearchChip(
                        label = source.label,
                        selected = source in sources,
                        onClick = { SonoraBackend.setSearchSource(source, source !in sources) },
                    )
                }
            }
        }

        DownloadStatus(
            state = download,
            onCancel = { SonoraBackend.cancelDownload() },
            onCancelRemaining = { SonoraBackend.cancelPendingDownloads() },
        )

        statusNote(searchState, showSoulseek, showYoutube, catalogueShown)?.let { Note(it) }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = listContentPadding(withMiniPlayer = playing),
        ) {
            // The tracks played out of a search come first, because they are what a listener
            // coming back to this page is most likely to want: the thing they already found, with
            // its own cover, one tap from playing again. The queries they typed are the fallback
            // for when there is nothing yet.
            if (showingHistory && recentTracks.isNotEmpty()) {
                item(key = "recent-tracks") {
                    ShelfHeader(
                        title = "Recently played",
                        subtitle = "Found here, and still one tap away",
                    )
                }

                items(recentTracks, key = { it.key }) { recent ->
                    val track = LibraryTrack(
                        file = null,
                        title = recent.title,
                        artist = recent.artist,
                        album = recent.album,
                        size = 0,
                        remote = recent.toYtm(),
                        artworkUrl = recent.artworkUrl,
                    )

                    SongRow(
                        track = track,
                        onClick = { SonoraPlayer.play(context, track) },
                        meta = listOfNotNull(recent.artist).joinToString("  ·  "),
                    )
                }

                item(key = "recent-tracks-gap") { Spacer(Modifier.height(24.dp)) }
            }

            if (showingHistory) {
                if (history.isEmpty()) {
                    if (recentTracks.isEmpty()) {
                        item { Note("Search to find music, on YouTube Music or the peer network.") }
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = PAGE_GUTTER, end = 8.dp, top = 8.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Recent searches",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            TextButton(
                                onClick = { SonoraBackend.clearSearchHistory(context) },
                            ) {
                                Text(
                                    text = "Clear all",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    items(history, key = { it }) { term ->
                        RecentSearchRow(
                            term = term,
                            onClick = { runSearch(term) },
                            onRemove = { SonoraBackend.removeSearchQuery(context, term) },
                        )
                    }
                }
            } else {
                // Registered even while empty. A row added to the top of a list that has already
                // been laid out makes the list keep what was on top in place, which pushes the new
                // row above the viewport — and this row always arrives after the search has begun.
                item(key = "catalogue") {
                    if (catalogueShown) {
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            SectionHeader(
                                title = "In the catalogue",
                                subtitle = "From MusicBrainz; tap to look for it",
                            )

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(catalogue, key = { it.id }) { album ->
                                    MediaCard(
                                        artwork = rememberCoverArt(album.id),
                                        title = album.title,
                                        // The artist is not decoration: one album title belongs to
                                        // several different artists, and only this says which one
                                        // this is.
                                        subtitle = listOfNotNull(
                                            album.artistName.ifEmpty { null },
                                            album.year,
                                        ).joinToString("  \u00b7  "),
                                        shape = RoundedCornerShape(8.dp),
                                        // Nothing here has been downloaded, so there is nothing to
                                        // play; tapping looks for it on the network instead.
                                        onClick = {
                                            runSearch(
                                                SearchQueries.forAlbum(
                                                    album.title,
                                                    album.artistName,
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // YouTube Music first, because it is the one source that can be heard the moment it
                // is tapped: a stream resolves and plays, where a peer result is a file that has to
                // finish downloading before anything comes out of it.
                if (showYoutube && (searchState.youtube.isNotEmpty() || searchState.youtubeLoading)) {
                    item(key = "youtube") {
                        SectionHeader(
                            title = "On YouTube Music",
                            subtitle = "Plays now; search the network for a lossless copy",
                        )
                    }
                    // Placeholders for the wait, in place of a section header and nothing under it.
                    // This is a network search behind another one, and a header with an empty body
                    // underneath reads as a failure rather than as work in progress.
                    if (searchState.youtube.isEmpty()) {
                        songListSkeleton(count = 6, keyPrefix = "skeleton:ytm")
                    }

                    items(
                        searchState.youtube,
                        key = { "ytm:" + it.videoId },
                    ) { track ->
                        SongRow(
                            track = LibraryTrack.fromRemote(track),
                            onClick = {
                                // Remembered because the listener chose it, not because they typed
                                // it. The query is already in the history; what is missing from
                                // the history is the answer they picked.
                                val picked = LibraryTrack.fromRemote(track)
                                SonoraPlayer.play(context, picked)
                                SonoraBackend.recordRecentTrack(context, picked)
                            },
                            onMore = { peerSearch(track) },
                            meta = listOfNotNull(track.artist, track.album).joinToString("  ·  "),
                        )
                    }
                }

                if (showSoulseek) {
                    items(searchState.hits, key = { it.peer + it.filename }) { hit ->
                        ResultRow(
                            hit = hit,
                            folderSize = SearchFolders.folderOf(searchState.hits, hit).size,
                            onDownload = { startDownload(hit) },
                            onDownloadFolder = {
                                SearchFolders.folderOf(searchState.hits, hit)
                                    .forEach { startDownload(it) }
                            },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }

    if (askingWhere) {
        AlertDialog(
            onDismissRequest = {
                askingWhere = false
                waiting = null
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Where should downloads go?") },
            text = {
                Text(
                    "Files Sonora creates in shared storage are deleted if you uninstall the " +
                        "app. Choose a folder and the files are yours to keep.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingWhere = false
                        pickFolder.launch(null)
                    },
                ) {
                    Text("Choose folder")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        askingWhere = false
                        SonoraBackend.useDefaultDownloadFolder(context)
                        waiting?.let { SonoraBackend.download(context, it) }
                        waiting = null
                    },
                ) {
                    Text("Use default", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

/**
 * One of the chips above the results: how they are ordered, and which sources are shown.
 *
 * Shared by both rows so the two read as one set of controls rather than two styles of button.
 */
@Composable
private fun SearchChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            // The accent as a tint rather than as a fill. Three of these side by side is already a
            // lot of chip; filling them all in the accent turns the row into the loudest thing on
            // the screen and leaves nothing for the results below it to be louder than.
            selectedContainerColor = MaterialTheme.colorScheme.accentText.copy(alpha = 0.16f),
            selectedLabelColor = MaterialTheme.colorScheme.accentText,
        ),
        border = null,
    )
}

/** A row of chips that scrolls sideways when there are more than fit across. */
@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * The line under the controls, or null when there is nothing worth saying.
 *
 * It only ever describes the peer results, so it stays quiet while those are hidden. "No results"
 * especially has to mean no results from anywhere: said above a row of catalogue matches it would
 * read as a flat contradiction.
 */
internal fun statusNote(
    state: SearchState,
    showSoulseek: Boolean,
    showYoutube: Boolean,
    catalogueShown: Boolean,
): String? {
    val youtubeCount = state.youtube.size
    val anyResults = state.hits.isNotEmpty() || youtubeCount > 0 || catalogueShown
    val stillLooking = state.searching || state.youtubeLoading
    val peerLine = "${state.matched} file(s) from ${state.peers} peer(s)"
    val youtubeLine = "$youtubeCount on YouTube Music"

    return when {
        // Nothing has been searched for, so there is no result to describe.
        state.query.isBlank() -> null

        // Says nothing about counts while a source is still outstanding: half the numbers would be
        // final and half provisional, and a total that is quietly wrong is worse than none.
        stillLooking -> "Searching\u2026"

        !anyResults -> "No results for \u201c${state.query}\u201d."

        // Catalogue rows are showing but neither playable source answered, so there is nothing to
        // count. Saying "0 on YouTube Music" over them would read as a result, not an absence.
        state.hits.isEmpty() && youtubeCount == 0 -> null

        showSoulseek && state.hits.isNotEmpty() && showYoutube -> "$youtubeLine \u00b7 $peerLine"

        showSoulseek && state.hits.isNotEmpty() -> peerLine

        showYoutube -> youtubeLine

        else -> null
    }
}

@Composable
private fun RecentSearchRow(term: String, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = PAGE_GUTTER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = term,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Remove ${term}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ResultRow(
    hit: SearchHit,
    folderSize: Int,
    onDownload: () -> Unit,
    onDownloadFolder: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDownload)
            .padding(horizontal = PAGE_GUTTER, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Remote files carry no artwork — the search response has no such field — so this is a
        // deliberate placeholder rather than a missing image.
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = hit.filename.substringAfterLast('\\'),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Size is deliberately not part of this line: it was being truncated away
                    // behind the peer name, and it decides whether a download is worth starting.
                    text = listOfNotNull(hit.peer, quality(hit).ifEmpty { null })
                        .joinToString("  \u00b7  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatSize(hit.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Text(
                text = hit.filename.substringBeforeLast('\\', ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(end = 2.dp),
        ) {
            Text(
                text = if (hit.hasFreeUploadSlot) "Ready" else "Queued ${hit.queueLength}",
                style = MaterialTheme.typography.labelSmall,
                color = if (hit.hasFreeUploadSlot) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                maxLines = 1,
            )
            hit.averageSpeed.takeIf { it > 0 }?.let {
                Text(
                    text = "${formatSize(it)}/s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceFaint,
                    maxLines = 1,
                )
            }
        }

        IconButton(onClick = onDownload) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = "Download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Bulk download sits behind a menu: it acts on other results too, so it should not look
        // like the button that fetches this one.
        var menuOpen by remember { mutableStateOf(false) }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Result options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Download this file") },
                    onClick = {
                        menuOpen = false
                        onDownload()
                    },
                )

                if (folderSize > 1) {
                    DropdownMenuItem(
                        text = { Text("Download folder ($folderSize files)") },
                        onClick = {
                            menuOpen = false
                            onDownloadFolder()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadStatus(
    state: DownloadState,
    onCancel: () -> Unit,
    onCancelRemaining: () -> Unit,
) {
    when (state) {
        DownloadState.Idle -> Unit

        is DownloadState.Downloading -> Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = buildString {
                        append("Downloading ${state.filename} \u2014 ")
                        append(
                            state.fraction?.let { "${(it * 100).toInt()}%" }
                                ?: formatSize(state.bytes),
                        )
                        if (state.remaining > 0) append("  \u00b7  ${state.remaining} queued")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                )

                if (state.remaining > 0) {
                    TextButton(onClick = onCancelRemaining) { Text("Cancel all") }
                }

                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cancel download",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { state.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is DownloadState.Completed -> Text(
            text = "Saved ${state.filename} (${formatSize(state.bytes)})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.accentText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        is DownloadState.Failed -> Text(
            text = "Download failed: ${state.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Best-effort quality summary; which attributes exist depends on the format and the peer. */
private fun quality(hit: SearchHit): String {
    val attributes = hit.attributes

    val parts = buildList {
        attributes.bitrateKbps?.let { add("${it}kbps") }
        attributes.sampleRateHz?.let { add("${it / 1000}kHz") }
        attributes.bitDepth?.let { add("${it}bit") }
        attributes.durationSeconds?.let { add("${it / 60}:%02d".format(it % 60)) }
    }

    return parts.joinToString(" ")
}
