package dev.sonora.backend

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.sonora.ytm.YtmTrack

/**
 * The saved YouTube Music tracks — the ones added to the library, liked, or put in a playlist that
 * have not been downloaded.
 *
 * A separate store from the library scan, because the scan is the truth about what is *on the
 * device* and this is the truth about what has been *kept*. A file that is deleted outside the app
 * drops out of the library on the next scan; a saved track has no file to lose, so it has to be
 * written down or it would vanish on the next launch.
 *
 * This is the only place a streamed track exists outside a search. A playlist entry, a like and a
 * line of listening history are all stored as a [LibraryTrack.key], and a key is not a track: it
 * cannot be drawn, played, or shown in a shelf. So every one of those paths has to *keep* the track
 * on the way past, or the thing the listener did becomes a reference to nothing — a like that does
 * not appear in Liked Songs, a play that does not appear in Recently played.
 *
 * The list is read as a set against the library rather than appended to it, so a track that has
 * since been downloaded is not shown twice — once as a file and once as a saved stream.
 */
@Serializable
data class SavedTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val playlistIds: List<String> = emptyList(),
    /**
     * When this track was last kept, for the order the cap drops them in.
     *
     * A track that has been downloaded drops out of this list on the next scan, so the only rows
     * that reach a cap are the ones a listener keeps arriving at: played, liked, or added again.
     * Zero for a row written before this field existed, which sorts it as oldest — the safe way
     * round, because the alternative is dropping a track the listener has not touched in a while.
     */
    val keptAt: Long = 0L,
)

/**
 * Folds one track into the kept list, most recently kept first.
 *
 * Re-keeping a track refreshes it in place rather than adding a second row, so a search that found
 * the same recording twice — or a track that is played, liked and added to a playlist — leaves one
 * row and not three that all play the same audio.
 *
 * [playlistIds] are unioned rather than replaced: a track already in a playlist keeps that
 * membership when it is played again, which is what the cap uses to decide what it may drop.
 */
fun keep(
    saved: List<SavedTrack>,
    track: YtmTrack,
    at: Long,
    playlistIds: List<String> = emptyList(),
): List<SavedTrack> {
    val previous = saved.firstOrNull { it.videoId == track.videoId }
    val row = SavedTrack(
        videoId = track.videoId,
        title = track.title,
        artist = track.artist,
        album = track.album,
        artworkUrl = track.artworkUrl,
        playlistIds = (previous?.playlistIds.orEmpty() + playlistIds).distinct(),
        keptAt = at,
    )
    return (listOf(row) + saved.filterNot { it.videoId == track.videoId }).let(::capped)
}

/**
 * Drops the least recently kept tracks over [cap], and never one a playlist holds.
 *
 * The cap is what stops a listener who plays a lot from growing this document forever, and it is a
 * plain cap rather than a scheme because a playlist is the one thing here a listener asked for
 * deliberately. Something they put in a playlist by hand is not theirs to have aged out of the
 * app's memory; something they merely played is.
 */
fun capped(saved: List<SavedTrack>, cap: Int = MAX_KEPT): List<SavedTrack> {
    if (saved.size <= cap) return saved
    return saved
        .sortedByDescending { it.keptAt }
        .take(cap)
        // A playlist member that did not make the cut is put back, which can leave the list over
        // the cap by however many playlists exist. That is the intended bargain: the cap bounds
        // play history, and a playlist is a promise the listener made to themselves.
        .plus(saved.filter { it.playlistIds.isNotEmpty() && it.keptAt < saved[cap].keptAt })
        .distinctBy { it.videoId }
}

/** Records that a track is in a playlist, for the cap above. */
fun withPlaylist(saved: List<SavedTrack>, videoId: String, playlistId: String): List<SavedTrack> =
    saved.map {
        if (it.videoId != videoId || playlistId in it.playlistIds) it
        else it.copy(playlistIds = it.playlistIds + playlistId)
    }

/** Forgets a playlist membership, so a track removed from it can be aged out like any other play. */
fun withoutPlaylist(saved: List<SavedTrack>, videoId: String, playlistId: String): List<SavedTrack> =
    saved.map {
        if (it.videoId != videoId) it else it.copy(playlistIds = it.playlistIds - playlistId)
    }

/** Enough kept tracks to cover a long listening history without the document growing forever. */
const val MAX_KEPT = 500

/**
 * Saved tracks, as one JSON document.
 *
 * The same shape of store as [JsonFile] and for the same reason: a few hundred rows of text, one
 * writer, read once. Kept deliberately dull — the only judgement in here is which rows survive a
 * scan, and that is [withSaved].
 */
class SavedTrackStore(private val file: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): List<SavedTrack> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<SavedTrack>>(file.readText())
        }.getOrDefault(emptyList())
    }

    fun save(tracks: List<SavedTrack>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(tracks))
        }
    }
}

/** The catalogue shape a saved row is stored as. */
private fun SavedTrack.toTrack() = YtmTrack(
    videoId = videoId,
    title = title,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
)

/**
 * Folds saved tracks into a scanned library.
 *
 * A saved track is left out when the library already holds its [LibraryTrack.key], which is what
 * stops a downloaded track appearing twice. Everything else is added as a streaming entry, so the
 * library is one list of tracks rather than two lists a caller has to merge.
 */
fun List<LibraryTrack>.withSaved(saved: List<SavedTrack>): List<LibraryTrack> {
    if (saved.isEmpty()) return this
    val present = mapTo(HashSet()) { it.key }
    return this + saved
        .filter { "ytm:${it.videoId}" !in present }
        .map { LibraryTrack.fromRemote(it.toTrack()).copy(arrivedAt = it.keptAt) }
}
