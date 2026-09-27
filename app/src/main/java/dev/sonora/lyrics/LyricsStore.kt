package dev.sonora.lyrics

import android.content.Context
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.SonoraBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The lyrics of whatever is playing, fetched once and kept.
 *
 * Published rather than returned so the pane can be drawn before the answer is in: the lyrics
 * arrive from a network with no idea how long it will take, and a pane that shows a shimmer until
 * then is the honest thing — one that shows nothing at all is a blank screen.
 *
 * Cached by track key, because the same track comes round again within a session every time a queue
 * is reshuffled, and a listener who has already waited for the words should not wait again.
 */
object LyricsStore {

    private val cache = HashMap<String, Lyrics>()
    private val _current = MutableStateFlow(Lyrics())
    val current: StateFlow<Lyrics> = _current.asStateFlow()

    /** Remembered so two asks for the same track in the same instant are one request. */
    private var inFlight: String? = null

    /**
     * The lyrics for [track], published to [current] as they arrive.
     *
     * Called from a composable's effect and nowhere else, because it publishes to shared state and
     * a composable that is recomposed would publish again for the same track.
     */
    fun request(track: LibraryTrack?, durationMs: Long) {
        val key = track?.key
        if (key == null) {
            _current.value = Lyrics()
            return
        }

        cache[key]?.let { cached ->
            _current.value = cached
            return
        }

        _current.value = Lyrics(loading = true)
        if (inFlight == key) return
        inFlight = key

        val title = track.title
        val artist = track.artist.orEmpty()

        SonoraBackend.lyricsScope.launch {
            val lines = LrcLib.lyrics(title, artist, durationMs)
            val value = if (lines.isEmpty()) {
                Lyrics(reason = "No lyrics for this track")
            } else {
                Lyrics(lines = lines)
            }
            cache[key] = value
            if (inFlight == key) {
                inFlight = null
                _current.value = value
            }
        }
    }

    /**
     * The words for the track, or null when they are not in hand.
     *
     * Read once per track and discarded on the next, so switching tracks does not show the previous
     * one's words for the frame before the new ones land.
     */
    fun forget() {
        _current.value = Lyrics()
    }
}
