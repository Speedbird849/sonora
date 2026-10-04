package dev.sonora.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Watches the player and reports how much of each track was actually listened to.
 *
 * A [Player.Listener] rather than polling: transitions, pause, resume and end are the events that
 * divide one play from the next, and the player already emits all of them.
 *
 * Played time is banked while the player is *playing* and frozen while it is not, which is what
 * makes a track paused for an hour the same as one paused for a minute. A seek does not change the
 * banked total — the listener was still listening — so only real elapsed play time is counted.
 *
 * The tracker is attached to whichever `Player` it is given: the `MediaController` the app holds,
 * which is the in-process handle to the player built by [PlaybackService].
 */
class PlaybackTracker(
    private val player: Player,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onFinished: (mediaId: String, listenedMs: Long, durationMs: Long?, endedNaturally: Boolean) -> Unit,
) : Player.Listener {

    private var mediaId: String? = null
    private var playingSince: Long = 0L
    private var banked: Long = 0L
    private var playing: Boolean = false

    /** Starts following the player. Idempotent enough: call once, when the controller connects. */
    fun start() {
        player.addListener(this)
        begin(player.currentMediaItem?.mediaId)
    }

    /** Stops following and finalizes whatever was in progress. */
    fun stop() {
        finalize(endedNaturally = false)
        player.removeListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val natural = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        finalize(endedNaturally = natural)
        begin(mediaItem?.mediaId)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) resume() else pause()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            // The end of the queue has no following transition, so the play would otherwise never
            // be finalized and the last track of every session would teach nothing.
            finalize(endedNaturally = true)
            begin(null)
        }
    }

    private fun begin(id: String?) {
        mediaId = id
        banked = 0L
        playing = false
        if (player.isPlaying) resume()
    }

    private fun pause() {
        if (!playing) return
        banked += (clock() - playingSince).coerceAtLeast(0L)
        playing = false
    }

    private fun resume() {
        if (playing) return
        playingSince = clock()
        playing = true
    }

    private fun finalize(endedNaturally: Boolean) {
        val id = mediaId
        pause()
        mediaId = null
        val listened = banked
        banked = 0L
        playing = false

        if (id == null) return
        val duration = player.duration.takeIf { it > 0L }
        onFinished(id, listened, duration, endedNaturally)
    }
}
