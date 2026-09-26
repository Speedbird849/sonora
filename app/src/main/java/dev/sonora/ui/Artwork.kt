package dev.sonora.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.SonoraBackend
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Embedded cover art, decoded once per file and kept in memory.
 *
 * Covers are downscaled on decode: a full-size image per row would dwarf everything else in the
 * app. The cache is unbounded, which is fine for a hand-built library and would not be for a large
 * one — that is the point at which an image-loading library earns its dependency.
 */
private val artworkCache = ConcurrentHashMap<String, ImageBitmap?>()

/** Catalogue covers, held the same way and for the same reason: the row re-composes as it scrolls. */
private val coverArtCache = ConcurrentHashMap<String, ImageBitmap?>()

/** Remote artwork, keyed by URL. Same reasoning as the two above. */
private val remoteArtworkCache = ConcurrentHashMap<String, ImageBitmap?>()

@Composable
fun rememberArtwork(file: File): ImageBitmap? {
    var artwork by remember(file) { mutableStateOf(artworkCache[file.absolutePath]) }

    LaunchedEffect(file) {
        if (artworkCache.containsKey(file.absolutePath)) return@LaunchedEffect

        val loaded = withContext(Dispatchers.IO) { decodeArtwork(file) }
        if (loaded != null) {
            artworkCache[file.absolutePath] = loaded
        }
        artwork = loaded
    }

    return artwork
}

/**
 * Cover art for any library track, whether it is on the device or not.
 *
 * A downloaded track has its art embedded in the file; a YouTube Music one has a URL and no file, so
 * it is fetched instead. Nothing is drawn until it arrives, so a row with no art yet shows the
 * caller's background rather than flashing a placeholder and replacing it.
 */
@Composable
fun rememberTrackArtwork(track: LibraryTrack): ImageBitmap? {
    val remote = track.remote
    if (remote == null) {
        val file = track.file ?: return null
        return rememberArtwork(file)
    }

    val url = remote.artworkUrl ?: return null
    var artwork by remember(url) { mutableStateOf(remoteArtworkCache[url]) }

    LaunchedEffect(url) {
        if (remoteArtworkCache.containsKey(url)) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { fetchRemoteArtwork(url) }
        remoteArtworkCache[url] = loaded
        artwork = loaded
    }

    return artwork
}

/**
 * A remote image for a row that has only a URL — a search result, which is not in the library yet
 * and so has no [rememberTrackArtwork] to go through.
 */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier) {
    var image by remember(url) { mutableStateOf(url?.let { remoteArtworkCache[it] }) }

    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        if (remoteArtworkCache.containsKey(url)) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { fetchRemoteArtwork(url) }
        remoteArtworkCache[url] = loaded
        image = loaded
    }

    Box(modifier) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Downloads and decodes one piece of remote artwork.
 *
 * Decoded at half resolution, which is what a list row wants and half of what a player screen does.
 * A URL that fails is remembered as absent rather than retried on every recomposition, which is what
 * an unbounded cache of misses would otherwise cause.
 */
private fun fetchRemoteArtwork(url: String): ImageBitmap? {
    val request = okhttp3.Request.Builder().url(url).build()
    return runCatching {
        dev.sonora.ytm.YtmHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.bytes() ?: return@use null
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }
    }.getOrNull()
}

/**
 * Cover art for a catalogue release, fetched once and kept in memory.
 *
 * Separate from [rememberArtwork] because there is no file to read: this is a release MusicBrainz
 * knows about and nothing has been downloaded. Nothing is drawn until it arrives, so a card without
 * a cover shows the placeholder rather than flashing one and replacing it.
 */
@Composable
internal fun rememberCoverArt(releaseGroupId: String): ImageBitmap? {
    val context = LocalContext.current
    var art by remember(releaseGroupId) { mutableStateOf(coverArtCache[releaseGroupId]) }

    LaunchedEffect(releaseGroupId) {
        if (coverArtCache.containsKey(releaseGroupId)) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            SonoraBackend.coverArt(context, releaseGroupId)
        }
        coverArtCache[releaseGroupId] = loaded
        art = loaded
    }

    return art
}

private fun decodeArtwork(file: File): ImageBitmap? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        val bytes = retriever.embeddedPicture ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = max(1, max(bytes.size / (256 * 256 * 4), 1))
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } finally {
        retriever.release()
    }
}.getOrNull()
