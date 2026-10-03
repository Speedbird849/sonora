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
 * Remote artwork, decoded and keyed by the size it was fetched for.
 *
 * The size is part of the key because the fetch asks YouTube for a cover of that size: a 120px
 * decode cached under the bare URL would be handed to a full-screen sleeve that needs four times
 * the pixels, and the sleeve would be soft with no way to tell why.
 *
 * The bytes behind these are [RemoteArtworkCache]'s, on disk as well as in memory, so a cover
 * fetched for a shelf on one launch is the cover the shelf draws on the next. What is held here is
 * the decode, because that is per size and the bytes are not.
 */
private fun artKey(url: String, px: Int) = RemoteArtworkCache.keyFor(url, px)
private val decodedArtwork = ConcurrentHashMap<String, ImageBitmap?>()

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
    val url = track.remote?.artworkUrl ?: track.artworkUrl
    if (!url.isNullOrBlank()) {
        return rememberRemoteArtwork(url, px)
    }
    val file = track.file ?: return null
    return rememberArtwork(file)
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
    if (url.isNullOrBlank()) return null
    if (url.startsWith("/") || url.startsWith("file://")) {
        val file = File(url.removePrefix("file://"))
        return rememberArtwork(file)
    }
    return rememberRemoteArtwork(url, px)
}

/**
 * A remote image for a row that has only a URL — a search result, which is not in the library yet
 * and so has no [rememberTrackArtwork] to go through.
 */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, px: Int = dev.sonora.ui.ROW_ART_PX) {
    Box(modifier) {
        val bitmap = rememberArtworkAt(url, px)
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * One place every remote picture comes through, so a cover is fetched once and drawn from wherever
 * it was last left.
 *
 * The first frame reads memory and nothing else: drawing a placeholder and replacing it a moment
 * later is a visible jump on every row of a scrolling list. The fetch happens after, off the main
 * thread, and its result is held in the composition so the row settles on the picture.
 */
@Composable
private fun rememberRemoteArtwork(url: String?, px: Int): ImageBitmap? {
    if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) return null
    val key = artKey(url, px)
    var image by remember(key) { mutableStateOf(decodedArtwork[key]) }

    LaunchedEffect(key, image) {
        if (image != null) return@LaunchedEffect
        image = withContext(Dispatchers.IO) { fetchRemoteArtwork(url, px) }
    }

    return image
}

/**
 * The bytes for a cover, from the cache or from the network, written to the cache either way.
 *
 * One place every remote picture comes through, so a cover is fetched once — for the launch, and for
 * every launch after it — and drawn from wherever it was last left.
 */
private fun bytesFor(url: String, px: Int): ByteArray? {
    val key = artKey(url, px)
    RemoteArtworkCache.bytes(key)?.let { return it }
    val request = runCatching { okhttp3.Request.Builder().url(key).build() }.getOrNull() ?: return null
    val bytes = runCatching {
        dev.sonora.ytm.YtmHttp.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    }.getOrNull()
    if (bytes == null) return null
    RemoteArtworkCache.put(key, bytes)
    return bytes
}

/**
 * Decodes one cover at the size it is about to be drawn.
 *
 * Decoded against what the image will be *drawn* at, not halved by habit. A flat 2 is right for a
 * 52dp row and ruinous for a full-screen sleeve — the cover comes back at half the pixels it is about
 * to be stretched across, and there is no way to get the rest back without asking for the image
 * again.
 */
private fun fetchRemoteArtwork(url: String, px: Int): ImageBitmap? {
    val bytes = bytesFor(url, px) ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, px) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        ?.also { decodedArtwork[artKey(url, px)] = it }
}

/** The largest power-of-two step that still leaves the bitmap at least [px] wide. */
private fun sampleFor(sourceWidth: Int, px: Int): Int {
    if (sourceWidth <= 0 || px <= 0) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= px) sample *= 2
    return sample
}


private fun decodeArtwork(file: File): ImageBitmap? {
    val bytes = embeddedPicture(file) ?: return null
    // And kept, because the one reader that cannot have a bitmap — the notification, which is handed
    // a URI by a library that has long since stopped running — is told where the picture is rather
    // than being given it. Written here, where a cover is being looked for anyway, so the extraction
    // is paid for once and never on the way to playing something.
    RemoteArtworkCache.rememberEmbedded(file, bytes)

    val options = BitmapFactory.Options().apply {
        inSampleSize = max(1, max(bytes.size / (256 * 256 * 4), 1))
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/** The bytes of the picture inside [file], or null when it has none or cannot be read. */
private fun embeddedPicture(file: File): ByteArray? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        retriever.embeddedPicture
    } finally {
        retriever.release()
    }
}.getOrNull()
