package dev.sonora.backend

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.sonora.playback.PlaybackService
import dev.sonora.ytm.YtmAudio
import dev.sonora.ytm.YtmStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Drives playback through the [PlaybackService].
 *
 * The player itself lives in that service, not here: it has to outlive the UI, and a session
 * service is what earns background playback and lock-screen controls. This holds a
 * [MediaController] — a connection to the service — and mirrors its state for the UI.
 */
object SonoraPlayer {

    private const val TAG = "SonoraPlayer"

    private val _state = MutableStateFlow(PlaybackState())

    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null

    private var connecting = false

    /** Set when playback is requested before the controller has finished connecting. */
    private var pendingQueue: List<LibraryTrack>? = null
    private var pendingIndex = 0

    /** Mirrors the local queue because the service exposes media items, not LibraryTrack values. */
    private var queue: List<LibraryTrack> = emptyList()

    /** Resolved streams, by track key. A YouTube URL expires, so this is a cache and not a store. */
    private val resolved = HashMap<String, YtmAudio>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Called whenever a track begins, whoever started it.
     *
     * Set by the backend, which keeps the record. A hook rather than a call at each play site
     * because the player is the only thing that sees next, previous, shuffle and auto-advance, so
     * this is the one place that knows everything that was actually listened to.
     */
    var onTrackStarted: ((LibraryTrack) -> Unit)? = null

    /** Starts connecting to the playback service. Safe to call repeatedly. */
    fun connect(context: Context) {
        if (controller != null || connecting) return

        connecting = true
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()

        future.addListener(
            {
                connecting = false

                controller = runCatching { future.get() }
                    .onFailure { Log.w(TAG, "could not connect to playback service", it) }
                    .getOrNull()

                controller?.addListener(listener)

                // Shuffle survives on the service across a UI restart, so the mirrored state is
                // read back rather than assumed to start off.
                controller?.let { active ->
                    _state.update {
                        it.copy(
                            isShuffled = active.shuffleModeEnabled,
                            repeatMode = repeatModeOf(active.repeatMode),
                        )
                    }
                }

                pendingQueue?.let { tracks ->
                    pendingQueue = null
                    controller?.let { playNow(it, tracks, pendingIndex) }
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    fun play(context: Context, track: LibraryTrack) {
        play(context, listOf(track), 0)
    }

    fun play(context: Context, tracks: List<LibraryTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return

        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val active = controller
        if (active == null) {
            pendingQueue = tracks
            pendingIndex = safeIndex
            connect(context)
        } else {
            playNow(active, tracks, safeIndex)
        }
    }

    fun togglePlayPause() {
        val active = controller ?: return
        if (active.isPlaying) active.pause() else active.play()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    fun next() {
        val active = controller ?: return

        // With repeat-one the queue is effectively this one track, so advancing would mean the
        // mode only ever took effect at the end of the track. Restarting is what "loop this song"
        // implies when the forward control is pressed.
        if (active.repeatMode == Player.REPEAT_MODE_ONE) {
            active.seekTo(0L)
            return
        }

        active.seekToNextMediaItem()
    }

    /**
     * Shuffle is delegated to the player rather than reordering our copy of the queue: Media3
     * already shuffles traversal while keeping the current item, so reordering would duplicate
     * that and lose the place of the track playing.
     */
    fun toggleShuffle() {
        val active = controller ?: return
        active.shuffleModeEnabled = !active.shuffleModeEnabled
    }

    /**
     * Stops playback and clears the queue.
     *
     * Needed when the file being played is deleted underneath the player: the queue would otherwise
     * hold items that can no longer be opened.
     */
    fun stop() {
        val active = controller ?: return

        active.stop()
        active.clearMediaItems()
        queue = emptyList()
        pendingQueue = null
        _state.value = PlaybackState()
    }

    /**
     * Jumps to a position in the track being played.
     *
     * Seeking only moves; it does not start playback, so scrubbing while paused leaves you paused
     * at the point you chose.
     */
    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /**
     * Cycles off → loop queue → loop track.
     *
     * Repeat is a Media3 mode, so the player decides what happens at the end of the queue; this
     * only picks the next mode. See [next] for how the forward control behaves while looping one
     * track.
     */
    fun cycleRepeat() {
        val active = controller ?: return
        active.repeatMode = when (active.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Keeps the Compose progress bar in step with the service without moving playback ownership. */
    fun syncPosition() {
        controller?.let { active ->
            _state.update {
                it.copy(
                    positionMs = active.currentPosition.coerceAtLeast(0L),
                    durationMs = active.duration.takeIf { duration -> duration > 0L } ?: 0L,
                )
            }
        }
    }

    /**
     * Hands the queue to the player, resolving anything that is not a file first.
     *
     * A streaming track has no file to open, so its audio has to be asked for before Media3 can be
     * given anything. That is a network round trip per track, so it happens here — once, up front —
     * rather than inside the player, which would stall the queue on the first track that needs it.
     *
     * A track that will not resolve is dropped from the queue rather than added as something that
     * cannot play. The alternative is a silent gap in the middle of an album, and a queue that skips
     * a track is a far smaller failure than one that appears to hang on it.
     */
    private fun playNow(active: MediaController, tracks: List<LibraryTrack>, startIndex: Int) {
        val playable = tracks.filter { it.isDownloaded || it.isRemote }
        if (playable.isEmpty()) return

        scope.launch {
            val mediaItems = playable.mapNotNull { track ->
                itemFor(track)?.let { track to it }
            }.toMap()

            val items = mediaItems.keys.mapNotNull { mediaItems[it] }
            if (items.isEmpty()) {
                Log.w(TAG, "nothing in the queue could be resolved")
                return@launch
            }

            // The index the caller asked for may have been dropped along with an unresolvable
            // track, so it is re-found by identity rather than reused as a position.
            val index = playable.indexOfFirst { it.key == tracks.getOrNull(startIndex)?.key }
                .takeIf { it >= 0 && mediaItems.containsKey(playable[it]) }
                ?: 0

            queue = playable
            active.setMediaItems(items, index.coerceIn(0, items.lastIndex), 0L)
            active.prepare()
            active.play()
            updateTrack(active, index.coerceIn(0, items.lastIndex))
        }
    }

    /**
     * A media item for one track: the file itself, or a resolved stream with the headers its fetch
     * has to carry.
     */
    private suspend fun itemFor(track: LibraryTrack): MediaItem? {
        track.file?.let { return MediaItem.fromUri(Uri.fromFile(it)) }

        val audio = resolved[track.key] ?: resolve(track) ?: return null
        return MediaItem.Builder()
            .setUri(audio.url)
            .setMediaId(track.key)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .build(),
            )
            .build()
    }

    /**
     * Turns a video id into a stream, reusing one already resolved this session.
     *
     * The cache is bounded and dropped wholesale when it is full rather than evicted one at a time:
     * a YouTube URL is good for about an hour, so anything cached from long ago is dead anyway and
     * there is nothing worth ordering by age.
     */
    private suspend fun resolve(track: LibraryTrack): YtmAudio? {
        val videoId = track.remote?.videoId ?: return null
        if (resolved.size >= MAX_RESOLVED) resolved.clear()

        return runCatching { YtmStream.resolve(videoId) }
            .onFailure { Log.w(TAG, "could not resolve ${track.title}: ${it.message}") }
            .getOrNull()
            ?.also { resolved[track.key] = it }
    }



    private fun updateTrack(active: MediaController, index: Int = active.currentMediaItemIndex) {
        val track = queue.getOrNull(index) ?: return
        _state.value = PlaybackState(
            track = track,
            isPlaying = active.isPlaying,
            positionMs = active.currentPosition.coerceAtLeast(0L),
            durationMs = active.duration.takeIf { it > 0L } ?: 0L,
            isShuffled = active.shuffleModeEnabled,
            repeatMode = repeatModeOf(active.repeatMode),
        )

        onTrackStarted?.invoke(track)
    }

    /** Media3 reports the repeat mode as an int; this keeps that detail out of the state. */
    private fun repeatModeOf(playerMode: Int): RepeatMode = when (playerMode) {
        Player.REPEAT_MODE_ONE -> RepeatMode.One
        Player.REPEAT_MODE_ALL -> RepeatMode.All
        else -> RepeatMode.Off
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Pause/play from the notification arrives here too, so the UI stays in step with
            // controls the app never saw.
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            controller?.let { updateTrack(it) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.update { it.copy(isShuffled = shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.update { it.copy(repeatMode = repeatModeOf(repeatMode)) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _state.update { it.copy(isPlaying = false) }
            }
        }
    }

    private const val MAX_RESOLVED = 32
}
