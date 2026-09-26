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

/**
 * Cover art for any library track, whether it is on the device or not.
 *
 * A downloaded track has its art embedded in the file; a YouTube Music one has a URL and no file, so
 * it is fetched instead. Both go through [rememberArtwork] or a single decode-and-cache, and neither
 * blocks composition — a row with no art yet shows the placeholder rather than flashing one and
 * replacing it.
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
 * Downloads and decodes one piece of remote artwork.
 *
 * Decoded down to the same size as [decodeArtwork] produces, so a remote row and a downloaded one
 * are the same visual weight in a list. A URL that fails is remembered as absent rather than
 * retried on every recomposition, which is what an unbounded cache of misses would otherwise cause.
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
 * Cover art for a catalogue release, fetched once and kept in memory.
 *
 * Separate from [rememberArtwork] because there is no file to read: this is a release MusicBrainz
 * knows about and nothing has been downloaded. Nothing is drawn until it arrives, so a card without
 * a cover shows the placeholder rather than flashing one and replacing it.
 */
@Composable
internal fun rememberCoverArt(releaseGroupId: String): ImageBitmap? {
    val context = LocalContext.current
    var artwork by remember(releaseGroupId) { mutableStateOf(coverArtCache[releaseGroupId]) }

    LaunchedEffect(releaseGroupId) {
        if (releaseGroupId.isEmpty()) return@LaunchedEffect
        if (coverArtCache.containsKey(releaseGroupId)) return@LaunchedEffect

        val loaded = withContext(Dispatchers.IO) {
            SonoraBackend.coverArt(context, releaseGroupId)
        }

        // Only images are remembered here: a ConcurrentHashMap has no room for a null, and a release
        // with no cover is already remembered on disk, so asking again costs a file check and no
        // network.
        if (loaded != null) {
            coverArtCache[releaseGroupId] = loaded
        }
        artwork = loaded
    }

    return artwork
}

private fun decodeArtwork(file: File, targetPx: Int = 256): ImageBitmap? {
    val retriever = MediaMetadataRetriever()

    return try {
        retriever.setDataSource(file.absolutePath)
        val bytes = retriever.embeddedPicture ?: return null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

        val longest = max(decoded.width, decoded.height)
        val scaled = if (longest > targetPx) {
            val ratio = targetPx.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt(),
                (decoded.height * ratio).toInt(),
                true,
            )
        } else {
            decoded
        }

        scaled.asImageBitmap()
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}
