package dev.sonora.spotify

import dev.sonora.backend.LibraryTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A Spotify import, from a pasted link to a playlist on this device.
 *
 * Held apart from the rest of the backend because it is a sequence with stages rather than a
 * request: paste, read, match, then write. The stages are a state flow because a listener who
 * cancels has to be able to come back to the start, and a coroutine cannot be un-started.
 */
sealed interface SpotifyImportState {

    /** Nothing started, or abandoned. The form is showing. */
    data object Idle : SpotifyImportState

    /** Fetching the track list. One request, so this is brief. */
    data object Reading : SpotifyImportState

    /** Asking the catalogue about each track, which is the long part. */
    data class Matching(val done: Int, val total: Int) : SpotifyImportState

    /** Matched and waiting to be confirmed. Nothing has been written yet. */
    data class Ready(val draft: SpotifyDraft) : SpotifyImportState

    /** Writing the playlist. */
    data class Writing(val done: Int, val total: Int) : SpotifyImportState

    /** Finished. [added] is below [requested] when some were dropped rather than written. */
    data class Done(val playlistId: String, val added: Int, val requested: Int) : SpotifyImportState

    data class Failed(val reason: Reason) : SpotifyImportState

    /**
     * Why an import stopped.
     *
     * A reason rather than a message, because the wording belongs to whichever surface shows it.
     */
    enum class Reason {
        /** The text pasted is not a Spotify link. */
        BadLink,

        /** A link naming nothing, or something not public. */
        Unreadable,

        /** The page could not be reached. */
        Unreachable,

        /** Not one of the tracks could be found in a catalogue that will serve it. */
        NothingMatched,
    }
}

/** A read playlist, matched, before anything is written. */
data class SpotifyDraft(
    val title: String,
    val owner: String?,
    val matches: List<SpotifyMatch>,
    /**
     * That Spotify's page returned the most tracks it gives, so there are probably more behind
     * them. Carried so it can be said rather than left for the listener to discover.
     */
    val atTrackLimit: Boolean,
) {
    val matched: List<LibraryTrack> get() = matches.mapNotNull { it.track }.map(LibraryTrack::fromRemote)

    /** Kept so the shortfall can be shown instead of silently vanishing. */
    val missed: List<SpotifyMatch> get() = matches.filter { it.track == null }
}

/**
 * Runs a Spotify import.
 *
 * Owns the state and the coroutine, and nothing else: the reading is [SpotifyEmbed]'s, the
 * matching is [SpotifyImporter]'s, and the writing is the caller's — it needs a Context for the
 * playlist store, and a caller that already has one should not be handed a callback to supply it
 * twice.
 */
class SpotifyImportRunner(private val onWrite: suspend (SpotifyDraft, Boolean) -> String) {

    private val _state = MutableStateFlow<SpotifyImportState>(SpotifyImportState.Idle)

    val state: StateFlow<SpotifyImportState> = _state.asStateFlow()

    private var running: Job? = null

    /** Reads and matches [link]. A run already in progress is abandoned first. */
    fun start(scope: CoroutineScope, link: String) {
        val ref = SpotifyLink.parse(link)
        if (ref == null) {
            _state.value = SpotifyImportState.Failed(SpotifyImportState.Reason.BadLink)
            return
        }

        running?.cancel()
        running = scope.launch(Dispatchers.IO) {
            _state.value = SpotifyImportState.Reading

            val read = SpotifyEmbed.fetch(ref)
            if (read !is SpotifyFetch.Loaded) {
                _state.value = SpotifyImportState.Failed(
                    when (read) {
                        SpotifyFetch.Unreadable -> SpotifyImportState.Reason.Unreadable
                        else -> SpotifyImportState.Reason.Unreachable
                    },
                )
                return@launch
            }

            val collection = read.collection
            val matches = SpotifyImporter.match(collection.tracks) { done, total ->
                // Replaced rather than merged: the stages are a progression, and a report from a
                // run that has since been abandoned must not walk this one backwards.
                if (_state.value is SpotifyImportState.Matching) {
                    _state.value = SpotifyImportState.Matching(done, total)
                }
            }

            val draft = SpotifyDraft(
                title = collection.title,
                owner = collection.owner,
                matches = matches,
                atTrackLimit = collection.atTrackLimit,
            )
            _state.value = if (draft.matched.isEmpty()) {
                SpotifyImportState.Failed(SpotifyImportState.Reason.NothingMatched)
            } else {
                SpotifyImportState.Ready(draft)
            }
        }
    }

    /**
     * Writes the draft out, reporting progress as tracks are added.
     *
     * Returns the new playlist's id, or null when there was nothing to write. The caller supplies
     * that half because creating a playlist needs a Context this does not hold.
     *
     * @param seedTaste whether the caller should also fold these tracks into the taste model.
     */
    suspend fun confirm(draft: SpotifyDraft, seedTaste: Boolean = false): String? {
        val tracks = draft.matched
        if (tracks.isEmpty()) return null

        _state.value = SpotifyImportState.Writing(0, tracks.size)
        val id = onWrite(draft, seedTaste)
        _state.value = SpotifyImportState.Done(id, tracks.size, tracks.size)
        return id
    }

    /** Abandons whatever stage the import had reached. */
    fun dismiss() {
        running?.cancel()
        running = null
        _state.value = SpotifyImportState.Idle
    }
}

/** A playlist name for a draft, when the caller has not supplied one. */
internal fun SpotifyDraft.defaultName(): String = title.trim().ifBlank { "Imported from Spotify" }
