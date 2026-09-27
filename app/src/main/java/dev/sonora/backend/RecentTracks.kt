package dev.sonora.backend

import android.content.Context
import kotlinx.serialization.Serializable
import java.io.File

/**
 * A track that was played from a search, kept so the search page can offer it again.
 *
 * Not a kept track and not a play-history entry, and the difference is the point. A kept track is
 * something in the library; a play-history entry is a record that something was played, ordered by
 * when. This is neither: it is the *thing* that was found, so that the search page can show what it
 * found last time as a row with its own cover rather than as the words that were typed to find it.
 *
 * The video id is kept so the row can be played again directly. A search that returns the same
 * recording under a different spelling is the same track, and matching on the id rather than on the
 * title is what stops "chill" and "Chill" being two entries.
 */
@Serializable
data class RecentTrack(
    val key: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
)

/**
 * The recent-track store, as one JSON document.
 *
 * Bounded on write rather than on read, so the file cannot grow without limit: a listener who
 * searches every day for a year should not end up with a list that has to be parsed on every
 * launch to show the eight most recent things.
 */
class RecentTrackStore(private val file: File) {

    private val json = kotlinx.serialization.json.Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun load(): List<RecentTrack> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<RecentTrack>>(file.readText()) }
            .getOrDefault(emptyList())
    }

    fun save(tracks: List<RecentTrack>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(tracks.take(MAX)))
        }
    }

    private companion object {
        /** How many to keep. Enough to fill the shelf, few enough that the file stays small. */
        const val MAX = 40
    }
}
