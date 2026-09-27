package dev.sonora.ui

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.sonora.ytm.YtmHttp
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Remote artwork, kept in memory for the session and on disk for the next one.
 *
 * ### Why on disk
 *
 * A search page is the same forty covers every time it is opened, and a shelf of playlists is the
 * same eighteen every time. Fetching them again on every visit is dozens of requests for pictures
 * that have not changed, and on a phone on mobile data that is the whole plan's data. A cover does
 * not change: a YouTube Music playlist's artwork is uploaded once and is the same bytes for the life
 * of the playlist, so there is nothing to revalidate.
 *
 * Keyed by the URL *at the size it was fetched for*, because the same cover is kept at row size and
 * at card size and one is not a substitute for the other — decoding a 52dp thumbnail across a
 * full-screen sleeve is what makes a cover go soft.
 *
 * Capped, and the oldest entries go first. Without a ceiling a long session of browsing is a few
 * hundred megabytes of pictures, and a search page is not worth that.
 */
internal object RemoteArtworkCache {

    /** A few hundred covers is more than any one page holds and less than a runaway. */
    private const val DIRECTORY = "artwork"
    private const val MAX_BYTES = 64L * 1024L * 1024L

    private val memory = LruCache<String, ImageBitmap>(256)

    private val known = ConcurrentHashMap<String, String>()

    private var directory: File? = null

    /** Pointed at the app's own files directory. Called once, at startup. */
    fun open(filesDir: File) {
        directory = File(filesDir, DIRECTORY).apply { mkdirs() }
    }

    /** A file name for a key, without the extension so a reader can find it without knowing. */
    private fun fileFor(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * The bitmap for [key], fetched if it has to be.
     *
     * Blocking: every caller is already on a coroutine that is meant to be off the main thread, and
     * returning a [androidx.compose.runtime.Composable]'s lazy would mean the page renders twice —
     * once with placeholders and once when the pictures land — which is the jump this is here to
     * avoid.
     */
    fun get(key: String, fetch: (String) -> ByteArray?): ImageBitmap? {
        memory.get(key)?.let { return it }
        if (known.containsKey(key)) return null

        val bytes = read(key) ?: fetch(key)?.also { put(key, it) }
        return bytes?.let { decode(it, key) }
    }

    /** What is on disk for [key], without fetching. */
    private fun read(key: String): ByteArray? {
        val file = directory?.let { File(it, fileFor(key)) } ?: return null
        if (!file.isFile) return null
        known[key] = file.path
        return runCatching { file.readBytes() }.getOrNull()
    }

    /** Writes [bytes] for [key], if there is room. */
    fun put(key: String, bytes: ByteArray) {
        val dir = directory ?: return
        if (known.containsKey(key)) return

        runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            if (usedBy(dir) + bytes.size > MAX_BYTES) trim(dir, bytes.size)
            val file = File(dir, fileFor(key))
            file.writeBytes(bytes)
            known[key] = file.path
        }
    }

    private fun decode(bytes: ByteArray, key: String): ImageBitmap? {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return bitmap.asImageBitmap().also { memory.put(key, it) }
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
