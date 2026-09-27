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
import dev.sonora.playback.extend
import dev.sonora.playback.nextSeed
import dev.sonora.playback.seedQueryFor
import dev.sonora.playback.shouldTopUp
import dev.sonora.ytm.YtmAudio
import dev.sonora.ytm.YtmSearch
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

    /**
     * What is playing and what comes next, for the player's own queue panel.
     *
     * Published rather than read on demand because the service only exposes media items and the
     * panel has to show the tracks — their covers, their artists, which one is the one playing — and
     * a media item cannot answer any of that. Mirrors [queue] for the same reason.
     */
    private val _upNext = MutableStateFlow<UpNext>(UpNext.Empty)

    val upNext: StateFlow<UpNext> = _upNext.asStateFlow()

    /** The same list, for the paths that only need to look something up. */
    private val queue: List<LibraryTrack> get() = _upNext.value.queue

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

    /**
     * Puts one track straight after whatever is sounding.
     *
     * "Play next" and "add to queue" are the same operation at different distances, and they are
     * worth having separately: the first is for the song you have just thought of while this one is
     * still going, and the second is for the one you will want in a few minutes' time. Both are
     * applied to the service rather than to a local list, so the panel cannot disagree with the
     * player about the order.
     */
    fun playNext(context: Context, track: LibraryTrack) = insertAt(track, offset = 1)

    fun addToQueue(context: Context, track: LibraryTrack) = insertAt(track, offset = 0)

    private fun insertAt(track: LibraryTrack, offset: Int) {
        val active = controller ?: run {
            // Nothing is playing, so the queue is the only queue. Held rather than dropped: a
            // track queued before the service finishes starting is the one the listener is waiting
            // for, and silently playing nothing would look like the tap did nothing.
            pendingQueue = listOf(track)
            pendingIndex = 0
            return
        }

        scope.launch {
            val items = _upNext.value.queue.mapNotNull { itemFor(it) }
            val target = (active.currentMediaItemIndex + offset).coerceIn(0, items.size)
            active.addMediaItems(target, listOfNotNull(itemFor(track)))

            // The mirror moves with the service rather than waiting to be told. A queue panel that
            // is a frame behind a tap reads as the tap having failed, which is the one thing it
            // definitely did not do.
            val current = _upNext.value.index.coerceAtLeast(0)
            val queue = _upNext.value.queue.toMutableList()
            queue.add((current + offset).coerceIn(0, queue.size), track)
            _upNext.value = UpNext(queue = queue, index = current)
        }
    }

    /**
     * Moves one track to a new place in the queue.
     *
     * Applied to the service rather than to a local list, for the same reason removal is: a panel
     * that reorders itself and a player that does not means the next track after the one the
     * listener just moved is the wrong one, and nothing on screen would say so.
     *
     * What is playing does not move. A reorder that carried the sounding track with it would change
     * which index counts as "current" and rewind the position along with it — the track is still
     * the same track, and it is still being listened to.
     */
    fun moveInQueue(from: Int, to: Int) {
        val active = controller ?: return
        val queue = _upNext.value.queue
        if (from !in queue.indices || to !in queue.indices || from == to) return
        if (from == _upNext.value.index) return

        scope.launch {
            val reordered = queue.toMutableList()
            val moved = reordered.removeAt(from)
            reordered.add(to.coerceIn(0, reordered.size), moved)

            // The sounding track's index moves with whatever crossed it.
            val current = _upNext.value.index
            val index = when {
                current == from -> to
                from < current && to >= current -> current - 1
                from > current && to <= current -> current + 1
                else -> current
            }

            _upNext.value = UpNext(queue = reordered, index = index)

            val items = reordered.mapNotNull { itemFor(it) }
            val keep = active.currentPosition
            val at = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            active.setMediaItems(items, at, keep)
            active.prepare()
        }
    }

    /**
     * Takes one track out of the queue.
     *
     * Done on the service rather than in a local copy, because a queue that disagrees with the
     * player about what is in it will put the removed track back the moment anything else
     * triggers a refresh — and the listener has just been told it is gone.
     *
     * The track currently sounding is not removed: taking out what is playing would either stop
     * the music or jump to a different song, and neither is what pressing remove on the thing you
     * are listening to can reasonably mean.
     */
    fun removeFromQueue(index: Int) {
        val active = controller ?: return
        scope.launch { removeFromQueueNow(active, index) }
    }

    private suspend fun removeFromQueueNow(active: MediaController, index: Int) {
        if (index == _upNext.value.index) return

        val remaining = _upNext.value.queue.filterIndexed { position, _ -> position != index }
        // The index of whatever was sounding shifts down by one when something before it goes.
        val nowPlaying = (_upNext.value.index - if (index < _upNext.value.index) 1 else 0)
            .coerceIn(0, (remaining.size - 1).coerceAtLeast(0))

        val items = remaining.mapNotNull { itemFor(it) }
        if (items.isEmpty()) {
            active.stop()
            active.clearMediaItems()
            _upNext.value = UpNext.Empty
            _state.value = PlaybackState()
            return
        }

        _upNext.value = UpNext(queue = remaining, index = nowPlaying)
        val target = items.indexOfFirst { it.mediaId == remaining[nowPlaying].key }
            .let { if (it >= 0) it else 0 }

        // Kept on the same track where it can be, so removing something from further along the
        // queue does not interrupt what is playing.
        val keepPosition = active.currentPosition
        active.setMediaItems(items, target, keepPosition)
        active.prepare()
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
    /**
     * Turns the queue's self-refill on or off.
     *
     * Held here rather than in the player because the refill is a property of the queue and has to
     * survive leaving the player: turning it on and then closing the player must not turn it back
     * off, which is what a flag in the screen would do.
     */
    fun toggleAutoplay() {
        val next = !_state.value.autoplay
        _state.update { it.copy(autoplay = next) }
        if (next) {
            // Turning it on while the queue is already short has to fill it now, or the switch
            // reads as broken until the next track happens to end.
            scope.launch { topUp() }
        }
    }

    /**
     * Fills the queue with suggestions, if the rules say it is time.
     *
     * The rules live in [shouldTopUp] so they can be read without a player; this is the half that
     * asks the network and puts the answer in the queue.
     */
    private suspend fun topUp() {
        if (autoplayLoading) return
        val active = controller ?: return
        if (!shouldTopUp(
                enabled = _state.value.autoplay,
                repeatAll = active.repeatMode == Player.REPEAT_MODE_ALL,
                currentIndex = active.currentMediaItemIndex,
                itemCount = active.mediaItemCount,
                loadInProgress = autoplayLoading,
            )
        ) {
            return
        }

        autoplayLoading = true
        try {
            var tried = listOf<String>()
            repeat(SEED_ATTEMPTS) {
                val history = _upNext.value.queue
                val seed = nextSeed(tried, history.filter { it.remote != null }.mapNotNull { it.remote })
                    ?: history.firstNotNullOfOrNull { it.remote }
                    ?: return@repeat
                val query = seedQueryFor(seed) ?: return@repeat
                tried = tried + query

                val candidates = YtmSearch.search(query)
                val added = extend(
                    queued = _upNext.value.queue.mapNotNull { it.remote },
                    candidates = candidates,
                    recent = recentIds(),
                )
                if (added.isEmpty()) return@repeat

                // Asked for the same order the panel shows them in, because a queue whose contents
                // differ from the panel is a queue the listener cannot see.
                val current = _upNext.value.queue.toMutableList()
                current.addAll(added.map { LibraryTrack.fromRemote(it) })
                _upNext.value = UpNext(queue = current, index = active.currentMediaItemIndex)

                val items = current.mapNotNull { itemFor(it) }
                val keep = active.currentPosition
                val at = active.currentMediaItemIndex
                active.setMediaItems(items, at, keep)
                active.prepare()
                Log.d(TAG, "autoplay: queued ${added.size} more")
                return
            }
        } finally {
            autoplayLoading = false
        }
    }

    /**
     * The video ids of what has just played, newest first.
     *
     * Read off the queue rather than kept in a history of its own, so there is only one record of
     * what has been through: a second list would be a second thing to forget to update.
     */
    private fun recentIds(): List<String> = _upNext.value.queue
        .take(_upNext.value.index + 1)
        .mapNotNull { it.remote?.videoId }
        .reversed()

    private var autoplayLoading = false

    /**
     * How many seeds one refill will try before giving up.
     *
     * More than one because a single search can come back with nothing — an artist with one
     * obscure track, a network hiccup — and a queue that emptied because of one empty answer would
     * be the exact failure this exists to prevent. Three is enough to get past a bad query without
     * enough to turn one gap in the catalogue into a long silence.
     */
    private const val SEED_ATTEMPTS = 3

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
        _upNext.value = UpNext.Empty
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
        _state.value = PlaybackState(track = playable[index], isResolving = true)
        _upNext.value = UpNext(queue = playable, index = index)

        scope.launch {
            // The tapped track is resolved first and on its own, because it is the only one whose
            // latency is felt — resolving the rest alongside it would put them back in front of it.
            // Everything else then resolves together, because nothing is waiting on any of it.
            val byKey = HashMap<String, MediaItem>()
            itemFor(playable[index])?.let { byKey[playable[index].key] = it }

            if (byKey.isEmpty()) {
                Log.w(TAG, "could not resolve ${playable[index].title}")
                // The track stays, with a reason. Dropping it removes the player from the screen,
                // and a listener who tapped a song and watched the player disappear has learned
                // that the button does nothing — which is the one thing this must not do.
                _state.value = PlaybackState(
                    track = playable[index],
                    isResolving = false,
                    problem = if (playable[index].file == null) {
                        "Couldn't play this one. YouTube would not serve it just now."
                    } else {
                        null
                    },
                )
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

        // The panel shows which entry is sounding, so the index travels with the queue rather than
        // being read from the controller at the moment it is drawn — a shuffle reorders the
        // controller's own list, and a position in that list is not a position in this one.
        _upNext.value = UpNext(queue = queue, index = index)
        _state.value = PlaybackState(
            track = track,
            isPlaying = active.isPlaying,
            positionMs = active.currentPosition.coerceAtLeast(0L),
            durationMs = active.duration.takeIf { it > 0L } ?: 0L,
            isShuffled = active.shuffleModeEnabled,
            repeatMode = repeatModeOf(active.repeatMode),
            // A track that is playing has no problem, whatever the last one had.
            problem = null,
            autoplay = _state.value.autoplay,
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

            // Every player callback caused by one queue edit lands here, and topping up on each of
            // them would ask for several batches for one transition. The rules refuse the second
            // one while the first is running, so only the transition that leaves the queue short
            // does any work.
            scope.launch { topUp() }
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
