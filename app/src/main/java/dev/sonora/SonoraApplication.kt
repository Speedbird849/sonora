package dev.sonora

import android.app.Application
import dev.sonora.ui.RemoteArtworkCache
import dev.sonora.ytm.YtmStream

/**
 * Process-wide setup.
 *
 * Exists for one thing: pointing the two on-disk caches at the app's own files directory, and
 * preparing the YouTube Music resolver. The resolver needs a Context — for the on-disk cache of the
 * player configuration — and holds it behind an assertion, so a caller that reaches for a stream
 * before anything has prepared it gets a crash rather than a refusal. Doing that preparation here
 * rather than at each call site is what makes it impossible to forget: the playback service can be
 * started by a notification without the UI ever being drawn, and `MainActivity` is not somewhere a
 * background resolve can rely on having gone.
 *
 * The artwork cache is the same story in miniature, and failing to point it anywhere is invisible
 * until something asks for a cover by path — which is the notification, drawn by the system from a
 * file, with no bitmap to fall back on. Covers were being fetched into memory on every launch and
 * written to a directory that did not exist.
 */
class SonoraApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        RemoteArtworkCache.open(filesDir)
        YtmStream.init(this)
    }
}
