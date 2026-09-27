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

/**
 * Remote artwork, keyed by URL and the size it was fetched for.
 *
 * The size is part of the key because the fetch asks YouTube for a cover of that size: a 120px
 * decode cached under the bare URL would be handed to a full-screen sleeve that needs four times
 * the pixels, and the sleeve would be soft with no way to tell why.
 */
private fun artKey(url: String, px: Int) = "$px|$url"
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
fun rememberTrackArtwork(track: LibraryTrack, px: Int = dev.sonora.ui.ROW_ART_PX): ImageBitmap? {
    val remote = track.remote
    if (remote == null) {
        val file = track.file ?: return null
        return rememberArtwork(file)
    }

    val url = remote.artworkUrl ?: return null
    val key = artKey(url, px)
    var artwork by remember(key) { mutableStateOf(remoteArtworkCache[key]) }

    LaunchedEffect(key) {
        if (remoteArtworkCache.containsKey(key)) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { fetchRemoteArtwork(url, px) }
        remoteArtworkCache[key] = loaded
        artwork = loaded
    }

    return artwork
}

/**
 * A remote image as a bitmap, for a card that has only a URL and no track behind it — an album or
 * an artist from a search, which is a page rather than a row and has no [LibraryTrack] of its own.
 *
 * Same cache and same size-rewriting as [rememberTrackArtwork], because it is the same pictures: a
 * second path onto them would decode every cover on the page twice.
 */
@Composable
fun rememberArtworkAt(url: String?, px: Int): ImageBitmap? {
    val key = url?.let { artKey(it, px) }
    var artwork by remember(key) { mutableStateOf(key?.let { remoteArtworkCache[it] }) }

    LaunchedEffect(key) {
        if (key == null || remoteArtworkCache.containsKey(key)) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { fetchRemoteArtwork(url!!, px) }
        remoteArtworkCache[key] = loaded
        artwork = loaded
    }

    return artwork
}

/**
 * A remote image for a row that has only a URL — a search result, which is not in the library yet
 * and so has no [rememberTrackArtwork] to go through.
 */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, px: Int = dev.sonora.ui.ROW_ART_PX) {
    val url = url
    val key = url?.let { artKey(it, px) }
    var image by remember(key) { mutableStateOf(key?.let { remoteArtworkCache[it] }) }

    LaunchedEffect(key) {
        if (url == null || key == null) return@LaunchedEffect
        if (remoteArtworkCache.containsKey(key)) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { fetchRemoteArtwork(url, px) }
        remoteArtworkCache[key] = loaded
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
private fun fetchRemoteArtwork(url: String, px: Int): ImageBitmap? {
    val request = okhttp3.Request.Builder().url(atSize(url, px)).build()
    return runCatching {
        dev.sonora.ytm.YtmHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.bytes() ?: return@use null

            // Written to disk before it is decoded, so the next launch reads bytes rather than
            // asking again. A shelf of covers is the same shelf every time the page is opened, and
            // fetching it again is forty requests for pictures that have not changed.
            RemoteArtworkCache.put(request.url.toString(), bytes)

            // Sampled against what the image will be *drawn* at, not halved by habit. A flat 2 is
            // right for a 52dp row and ruinous for a full-screen sleeve — the cover comes back at
            // half the pixels it is about to be stretched across, and there is no way to get the
            // rest back without asking for the image again.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(bounds.outWidth, px)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }
    }.getOrNull()
}

/** The largest power-of-two step that still leaves the bitmap at least [px] wide. */
private fun sampleFor(sourceWidth: Int, px: Int): Int {
    if (sourceWidth <= 0 || px <= 0) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= px) sample *= 2
    return sample
}

/**
 * Asks YouTube for a cover of about [px] pixels across.
 *
 * The size is in the URL's path, so a 120px thumbnail stretched over a full-screen sleeve is a
 * blurry sleeve, and no amount of decoding well can put those pixels back. Rewriting the segment is
 * the only fix; a service that does not put a size in its URLs simply gets the original.
 */
private fun atSize(url: String, px: Int): String {
    // w544 is the largest square YouTube serves for music artwork, so there is nothing to gain by
    // asking for more and a request for it is answered with a 404.
    val wanted = px.coerceIn(64, 544)
    val sized = Regex("=w\\d+-h\\d+").replace(url, "=w$wanted-h$wanted")
    // A URL with no size segment, or one whose size segment did not match, keeps its own.
    return if (sized == url && !url.contains("=w")) url else sized
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
