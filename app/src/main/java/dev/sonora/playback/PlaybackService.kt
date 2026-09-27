package dev.sonora.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.sonora.ytm.YtmStream

/**
 * Owns playback so it survives the UI.
 *
 * A `MediaSessionService` rather than a plain service: it is what gives background playback,
 * lock-screen and notification transport controls, and media-button and Bluetooth handling, and it
 * declares the `mediaPlayback` foreground type itself.
 *
 * Separate from `SonoraService`, which keeps the P2P connection alive under `dataSync`. Two
 * services, two foreground types, two independent lifecycles — playback should not stop because
 * the network dropped, or vice versa.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // Pause when audio focus is lost rather than ducking: this is music, not a voice
                // prompt over something else.
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            // A googlevideo URL must be fetched with the headers of the client that minted it, and
            // which client that was differs per stream — so the source asks the resolver which one
            // minted this particular URL rather than being built with a single set of them.
            //
            // The source is installed for every item rather than only for streams, because
            // choosing per item is not something this factory can express. What the source can do
            // is hand anything that is not an HTTP URL back to the platform's own data source —
            // see [StreamDataSource] — which is what a downloaded track needs.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    StreamDataSource.factory(this) { url -> YtmStream.headersFor(url).orEmpty() },
                ),
            )
            .build()

        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
