package dev.sonora.backend

import dev.sonora.ytm.YtmTrack
import java.io.File

/**
 * A track the library knows about, whether or not it is on this device.
 *
 * Two kinds of thing answer to this one type, because the app treats them as interchangeable in
 * almost every place it matters — both can be queued, played, liked and put in a playlist, and the
 * difference between them is only where the audio comes from.
 *
 *  - **Downloaded** ([file] set): a file on disk, found by scanning the download folder. [key] is
 *    its path.
 *  - **Streaming** ([remote] set): a YouTube Music track that has not been downloaded. [key] is
 *    `ytm:<videoId>`, which is stable across launches in a way a resolved stream URL is not — those
 *    expire within the hour.
 *
 * [file] is null for a streaming track and [remote] null for a downloaded one, so a caller that only
 * needs the artwork, the credits or the identity never has to care which it has.
 */
data class LibraryTrack(
    val file: File?,
    val title: String,
    val artist: String?,
    val album: String?,
    val size: Long,
    val remote: YtmTrack? = null,
    /** Where the audio comes from, for the player and for anything that needs a URI. */
    val artworkUrl: String? = null,
    /**
     * A `content://` URI for [file], when one exists.
     *
     * The path is not enough on its own. A library built from the media index knows a track by its
     * `DATA` column, which is a real path, and opening that path directly fails on any device with
     * scoped storage: the app is not allowed to read a file it did not create and cannot name
     * through the provider. The provider's own URI is the only one that opens, and a track found
     * that way has to carry it.
     *
     * Null for a file the app can read outright — one in its own folder, or one reached through a
     * granted tree — where the path is the cheaper answer.
     */
    val contentUri: String? = null,
    /**
     * When this track arrived, for anything ordered by recency.
     *
     * A file's own timestamp is the answer and is not stored twice; a streamed track has no file and
     * therefore no timestamp, so this is where the moment it was kept goes. Without it every
     * streamed track arrives at the same instant — the moment the shelf is asked to sort — and
     * "recently added" becomes "in whatever order they happened to be in".
     */
    val arrivedAt: Long? = null,
) {
    /** Stable identity, and what a playlist or a like stores instead of an object. */
    val key: String
        get() = file?.absolutePath ?: remote?.let { "$REMOTE_PREFIX${it.videoId}" } ?: title

    val isRemote: Boolean get() = remote != null

    /** True when the audio is somewhere on this device and can be opened without a network. */
    val isDownloaded: Boolean get() = file != null

    /**
     * What the player should open.
     *
     * The provider's URI when there is one, because a path that MediaStore knows about is a path
     * this process may not be allowed to read. A file the app can open itself needs no URI at all,
     * and handing the player a `file://` for it would be a second way of saying the same thing.
     */
    val playableUri: String?
        get() = when {
            remote != null -> null
            contentUri != null -> contentUri
            file != null -> file.toURI().toString()
            else -> null
        }

    companion object {
        /** What a remote track's [key] starts with. Also used to read one back. */
        const val REMOTE_PREFIX = "ytm:"

        /** Leading track numbers: `07. `, `07 - `, `07_`, `1-04 `. */
        private val LEADING_TRACK_NUMBER = Regex("^\\d{1,3}\\s*[-._)]\\s*")

        fun from(file: File, metadata: TrackMetadata = TrackMetadata()): LibraryTrack {
            val size = file.length()
            val fallback = fromFilename(file)

            val title = metadata.title?.takeIf { it.isNotBlank() } ?: fallback.first
            val artist = metadata.artist?.takeIf { it.isNotBlank() } ?: fallback.second

            return LibraryTrack(
                file = file,
                title = title,
                artist = artist,
                album = metadata.album?.takeIf { it.isNotBlank() },
                size = size,
            )
        }

        /**
         * A YouTube Music track as a library entry.
         *
         * The size is the one thing that cannot be known without resolving the stream, and it is
         * left at zero rather than guessed: a download-availability check that reads a made-up size
         * would be worse than one that admits it does not have one.
         */
        fun fromRemote(track: YtmTrack): LibraryTrack = LibraryTrack(
            file = null,
            title = track.title,
            artist = track.artist,
            album = track.album,
            size = 0L,
            remote = track,
            artworkUrl = track.artworkUrl,
        )

        /** Best guess from the name: `Artist - Title`, with any track number stripped. */
        private fun fromFilename(file: File): Pair<String, String?> {
            val stem = file.nameWithoutExtension
            val cleaned = stem.replace(LEADING_TRACK_NUMBER, "").trim()
            val parts = cleaned.split(" - ", limit = 2)

            return if (parts.size == 2) {
                parts[1].trim() to parts[0].trim()
            } else {
                cleaned to null
            }
        }
    }
}
