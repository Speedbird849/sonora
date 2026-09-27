package dev.sonora.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The rules for the recently-played list.
 *
 * Pure, so the ordering and de-duplication are decided here and can be tested without a device.
 */
object PlayHistory {

    /** Long enough to hold a real listening history, short enough to stay a small document. */
    const val MAX = 50

    /**
     * Records a play as the most recent, returning the new list.
     *
     * Keyed by [LibraryTrack.key] because that is what identifies a track whether or not it has been
     * downloaded: playing something again moves it rather than adding a second copy. Without that,
     * putting one album on repeat would fill the list with that album and push out everything else.
     *
     * A streamed track's details travel with the entry. A key on its own is a reference to
     * something, and a "Recently played" row drawn from a reference is a row with no title, no cover
     * and nothing to play — which is not a row at all. Carrying the details costs a few hundred
     * bytes on fifty entries and makes the list the one place that can name a track the library scan
     * has never seen.
     */
    fun record(history: List<PlayedTrack>, track: LibraryTrack, at: Long): List<PlayedTrack> {
        if (track.key.isBlank()) return history

        val played = PlayedTrack(
            key = track.key,
            playedAt = at,
            videoId = track.remote?.videoId,
            title = track.title,
            artist = track.artist,
            album = track.album,
            artworkUrl = track.artworkUrl,
        )
        return (listOf(played) + history.filterNot { it.key == track.key }).take(MAX)
    }
}

/**
 * One track that was played, and when it started.
 *
 * The field keeps its stored name, so a history written before streaming existed still reads: a
 * downloaded track's entry has no [videoId] and is resolved against the library instead.
 */
@Serializable
data class PlayedTrack(
    @SerialName("path") val key: String,
    val playedAt: Long,
    val videoId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
) {
    /** True for an entry that can be drawn without consulting the library. */
    val isStreamed: Boolean get() = videoId != null
}

/**
 * The track an entry stands for, from the entry itself when it can be.
 *
 * Null for a downloaded track, which has to be looked up in the library scan — the file's own
 * metadata is better than anything recorded when it was played, and the file may since have been
 * deleted, in which case the caller drops the row rather than showing a track that cannot be played.
 */
fun PlayedTrack.asLibraryTrack(): LibraryTrack? {
    val id = videoId ?: return null
    val name = title?.takeIf { it.isNotBlank() } ?: return null
    return LibraryTrack(
        file = null,
        title = name,
        artist = artist,
        album = album,
        size = 0L,
        remote = dev.sonora.ytm.YtmTrack(
            videoId = id,
            title = name,
            artist = artist.orEmpty(),
            album = album,
            artworkUrl = artworkUrl,
        ),
        artworkUrl = artworkUrl,
        arrivedAt = playedAt,
    )
}
