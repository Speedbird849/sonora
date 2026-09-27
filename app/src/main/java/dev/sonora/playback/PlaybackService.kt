package dev.sonora.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.sonora.R
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
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .also { createChannel(it) },
        )
    }

    /**
     * The channel the playback notification lives in.
     *
     * Quiet, and named. The library's own default is a channel called after the app at default
     * importance, which means every track change makes the phone make a sound — the notification is
     * the *state* of playback, and a state that announces itself is a state you start ignoring.
     */
    private fun createChannel(provider: DefaultMediaNotificationProvider) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.playback_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
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

    private companion object {
        const val CHANNEL_ID = "playback"
    }
}
