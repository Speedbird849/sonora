package dev.sonora.backend

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.sonora.ytm.YtmTrack

/**
 * The saved YouTube Music tracks — the ones added to the library or to a playlist that have not
 * been downloaded.
 *
 * A separate store from the library scan, because the scan is the truth about what is *on the
 * device* and this is the truth about what has been *kept*. A file that is deleted outside the app
 * drops out of the library on the next scan; a saved track has no file to lose, so it has to be
 * written down or it would vanish on the next launch.
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
)

/**
 * Saved tracks, as one JSON document.
 *
 * The same shape of store as [JsonFile] and for the same reason: a few hundred rows of text, one
 * writer, read once. Kept deliberately dull — the only judgement in here is which rows survive a
 * scan, and that is [mergedInto].
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
internal fun List<LibraryTrack>.withSaved(saved: List<SavedTrack>): List<LibraryTrack> {
    if (saved.isEmpty()) return this
    val present = mapTo(HashSet()) { it.key }
    return this + saved
        .filter { "ytm:${it.videoId}" !in present }
        .map { LibraryTrack.fromRemote(it.toTrack()) }
}
