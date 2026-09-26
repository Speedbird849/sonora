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
     */
    fun record(history: List<PlayedTrack>, key: String, at: Long): List<PlayedTrack> {
        if (key.isBlank()) return history

        return (listOf(PlayedTrack(key, at)) + history.filterNot { it.key == key }).take(MAX)
    }
}

/**
 * One track that was played, and when it started.
 *
 * The field keeps its stored name, so a history written before streaming existed still reads.
 */
@Serializable
data class PlayedTrack(
    @SerialName("path") val key: String,
    val playedAt: Long,
)
