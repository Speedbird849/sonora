package dev.sonora.ui

import android.media.MediaMetadataRetriever
import android.util.LruCache
import dev.sonora.ytm.YtmHttp
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Remote artwork, kept as bytes in memory for the session and on disk for the next one.
 *
 * ### Why on disk
 *
 * A search page is the same forty covers every time it is opened, and a shelf of playlists is the
 * same eighteen every time. Fetching them again on every visit is dozens of requests for pictures
 * that have not changed, and on a phone on mobile data that is the whole plan's data. A cover does
 * not change: a YouTube Music playlist's artwork is uploaded once and is the same bytes for the life
 * of the playlist, so there is nothing to revalidate.
 *
 * Keyed by the URL *as it was fetched*, which carries the size it was fetched at: the same cover is
 * kept at row size and at card size and one is not a substitute for the other — decoding a 52dp
 * thumbnail across a full-screen sleeve is what makes a cover go soft.
 *
 * ### Bytes, not bitmaps
 *
 * A bitmap at card size is a megabyte and a half, and there are forty of them on a search page.
 * Decoding is left to whoever asked, because only they know the size the picture is about to be
 * drawn at. What is stored is what was fetched, once, and shared by every reader.
 *
 * Capped, and the oldest entries go first. Without a ceiling a long session of browsing is a few
 * hundred megabytes of pictures, and a search page is not worth that.
 */
internal object RemoteArtworkCache {

    /** A few dozen covers is more than any one page draws at once and less than a runaway. */
    private const val DIRECTORY = "artwork"
    private const val MAX_BYTES = 64L * 1024L * 1024L
    private const val KEPT_IN_MEMORY = 48

    private val memory = LruCache<String, ByteArray>(KEPT_IN_MEMORY)

    private val known = ConcurrentHashMap<String, String>()

    /**
     * Keys that were asked for and are not there.
     *
     * A URL that fails is remembered as absent rather than asked for again on every recomposition,
     * which is what an unbounded trail of misses would otherwise turn into.
     */
    private val absent = Collections.synchronizedSet(HashSet<String>())

    private var directory: File? = null

    /** Pointed at the app's own files directory. Called once, at startup. */
    fun open(filesDir: File) {
        directory = File(filesDir, DIRECTORY).apply { mkdirs() }
    }

    /**
     * The key for a picture, and the URL that serves it: the same string, on purpose.
     *
     * The size lives in the path, so the same cover is kept at row size and at card size. The key is
     * the URL *as fetched*, so what is on disk, what is in memory, and what was asked for are the
     * same picture rather than three versions of it — and a cover fetched for the notification is the
     * cover the sleeve shows.
     */
    fun keyFor(url: String, px: Int): String {
        val wanted = px.coerceIn(64, 544)
        val sized = Regex("=w\\d+-h\\d+").replace(url, "=w$wanted-h$wanted")
        // A URL with no size segment, or one whose size segment did not match, keeps its own.
        return if (sized == url && !url.contains("=w")) url else sized
    }

    /**
     * The bytes for [key], from memory or from disk, or null when nothing is there.
     *
     * Never fetches: a reader that can draw a placeholder can wait for a composable to do the
     * asking.
     */
    fun bytes(key: String): ByteArray? {
        if (absent.contains(key)) return null
        memory.get(key)?.let { return it }
        val file = fileFor(key) ?: return null
        return runCatching { file.readBytes() }.getOrNull()?.also { memory.put(key, it) }
    }

    /** A file holding [key]'s bytes, or null when nothing has been fetched for it yet. */
    private fun fileFor(key: String): File? {
        if (absent.contains(key)) return null
        val file = directory?.let { File(it, nameFor(key)) } ?: return null
        if (!file.isFile) return null
        known[key] = file.path
        return file
    }

    /**
     * A file holding the picture at [url] as it was fetched, written if it is not there yet.
     *
     * For the one reader that is handed a *path* rather than a bitmap: the notification's artwork,
     * which the library loads in-process from whatever URI the metadata names. Blocking, so the
     * caller has to be off the main thread.
     */
    fun fetchedFile(url: String, px: Int): File? {
        val key = keyFor(url, px)
        fileFor(key)?.let { return it }

        val bytes = runCatching {
            YtmHttp.client.newCall(okhttp3.Request.Builder().url(key).build()).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        }.getOrNull()
        if (bytes == null) {
            absent.add(key)
            return null
        }
        put(key, bytes)
        return fileFor(key)
    }

    /**
     * A file holding the picture already extracted from [audio], or null when nothing has.
     *
     * Never extracts, and never fetches: this is the reader that stands between a slow picture and
     * the play path, and a question that costs a read of a whole lossless file cannot be asked on
     * the way to playing something. [embedded] is the one that does the work; the player screen is
     * what calls it, because a cover is wanted once the song is playing.
     */
    fun cachedArtwork(audio: File): File? = fileFor("embedded|${audio.absolutePath}|${audio.length()}")

    /**
     * Remembers the picture that came out of [audio], so [cachedArtwork] can name it afterwards.
     *
     * The write and not the extraction, deliberately: the caller already has the bytes because it
     * was drawing them, and asking it to hand them over is free where asking it to go and find them
     * again is not.
     */
    fun rememberEmbedded(audio: File, bytes: ByteArray) {
        put("embedded|${audio.absolutePath}|${audio.length()}", bytes)
    }

    /**
     * A file holding the picture embedded in [audio], written out once.
     *
     * A downloaded track keeps its cover inside the file, and the notification is handed a URI and
     * asked to load it rather than handed the file itself. Cached by path and size, so it costs one
     * extraction per track rather than one per track change. Blocking: reading it means reading the
     * file, so the caller has to be off the main thread and off the play path.
     */
    fun embedded(audio: File): File? {
        val key = "embedded|${audio.absolutePath}|${audio.length()}"
        fileFor(key)?.let { return it }

        val retriever = MediaMetadataRetriever()
        val picture = try {
            retriever.setDataSource(audio.absolutePath)
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        } ?: return null

        put(key, picture)
        return fileFor(key)
    }

    /** A file name for a key, without the extension so a reader can find it without knowing. */
    private fun nameFor(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Writes [bytes] for [key], if there is room. */
    fun put(key: String, bytes: ByteArray) {
        memory.put(key, bytes)
        absent.remove(key)
        val dir = directory ?: return
        if (known.containsKey(key)) return

        runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            if (usedBy(dir) + bytes.size > MAX_BYTES) trim(dir, bytes.size)
            val file = File(dir, nameFor(key))
            file.writeBytes(bytes)
            known[key] = file.path
        }
    }

    private fun usedBy(dir: File): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Frees room for [incoming], oldest first.
     *
     * Ordered by when the file was last written, not by its name, because the name is a hash and
     * says nothing about age.
     */
    private fun trim(dir: File, incoming: Int) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var freed = 0L
        for (file in files) {
            if (freed >= incoming) break
            val length = file.length()
            if (file.delete()) freed += length
        }
    }
}
