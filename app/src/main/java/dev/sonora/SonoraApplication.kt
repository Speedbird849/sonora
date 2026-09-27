package dev.sonora

import android.app.Application
import dev.sonora.ytm.YtmStream

/**
 * Process-wide setup.
 *
 * Exists for one thing: preparing the YouTube Music resolver. The resolver needs a Context — for the
 * on-disk cache of the player configuration — and holds it behind an assertion, so a caller that
 * reaches for a stream before anything has prepared it gets a crash rather than a refusal. Doing
 * that preparation here rather than at each call site is what makes it impossible to forget: the
 * playback service can be started by a notification without the UI ever being drawn, and
 * `MainActivity` is not somewhere a background resolve can rely on having gone.
 */
class SonoraApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        YtmStream.init(this)
    }
}
