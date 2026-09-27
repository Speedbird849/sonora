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

        val wanted = tracks.getOrNull(startIndex)?.key
        val index = playable.indexOfFirst { it.key == wanted }.coerceAtLeast(0)

        // The queue and the state are published before anything is resolved.
        //
        // Resolving first is what made a tap feel broken: every item in the queue is a network
        // round trip, and a page of search results is dozens of them, so the mini player did not
        // appear until all of them had answered — seconds of nothing at all, on a screen where the
        // listener has just pressed something. The queue is known immediately; only the audio is
        // late.
        queue = playable
        _state.value = PlaybackState(track = playable[index], isResolving = true)

        scope.launch {
            // The tapped track is resolved first and on its own, because it is the only one whose
            // latency is felt — resolving the rest alongside it would put them back in front of it.
            // Everything else then resolves together, because nothing is waiting on any of it.
            val byKey = HashMap<String, MediaItem>()
            itemFor(playable[index])?.let { byKey[playable[index].key] = it }

            if (byKey.isEmpty()) {
                Log.w(TAG, "could not resolve ${playable[index].title}")
                _state.value = PlaybackState(track = null, isResolving = false)
                return@launch
            }

            playable.forEachIndexed { position, track ->
                if (position != index && track.key !in byKey) {
                    itemFor(track)?.let { byKey[track.key] = it }
                }
            }

            // Reassembled in the order they were handed over, not in the order they resolved. A
            // queue that starts with whatever happened to be asked for first is not a queue.
            val items = playable.mapNotNull { byKey[it.key] }
            if (items.isEmpty()) {
                Log.w(TAG, "nothing in the queue could be resolved")
                _state.value = PlaybackState(track = null, isResolving = false)
                return@launch
            }

            // The index asked for may have moved, because a track that would not resolve is dropped
            // rather than handed to the player as something it cannot play.
            val resolvedIndex = playable.indexOfFirst { it.key == playable[index].key }
                .let { wanted -> items.indexOfFirst { it.mediaId == playable[wanted].key } }
                .let { if (it >= 0) it else 0 }

            active.setMediaItems(items, resolvedIndex, 0L)
            active.prepare()
            active.play()
            updateTrack(active, resolvedIndex)
        }
    }

    /**
     * A media item for one track: the file itself, or a resolved stream with the headers its fetch
     * has to carry.
     */
    private suspend fun itemFor(track: LibraryTrack): MediaItem? {
        track.playableUri?.let { uri ->
            // The id is set here too, not only on the streaming branch. It is what the queue is
            // searched by to work out where playback actually started, and an item without one
            // never matches — which silently sent every local tap to the top of the list.
            return MediaItem.Builder()
                .setUri(Uri.parse(uri))
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
            // The type and the stack, not just the message: a resolution failure from a library
            // whose exceptions mostly carry no message at all is otherwise indistinguishable from a
            // network refusal, and those need completely different fixes.
            .onFailure {
                Log.w(TAG, "could not resolve ${track.title}", it)
            }
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
