package dev.sonora.backend

import kotlinx.serialization.Serializable

/**
 * User preferences.
 *
 * Defaults are the behaviour the app had before each setting existed, so a missing or unreadable
 * document behaves exactly like a fresh install.
 */
@Serializable
data class Settings(
    /**
     * Whether the Library lists music from the rest of the device as well as Sonora's own
     * downloads. Some users want the app to stay a download manager; others want a player.
     */
    val includeDeviceMusic: Boolean = true,

    /**
     * A folder the user picked for downloads, as a persisted tree URI.
     *
     * Preferred over the default because files Sonora creates itself are deleted when the app is
     * uninstalled, whereas files the system's document provider creates are not.
     */
    val downloadTreeUri: String? = null,

    /**
     * Whether the user has already been asked where downloads should go.
     *
     * Without this the question would be asked again on every download after they chose the
     * default, which is nagging rather than helping.
     */
    val promptedForDownloadFolder: Boolean = false,

    /**
     * A folder to reshare, as a persisted tree URI.
     *
     * Null means the download folder, which is the sensible default: the files are already there
     * and already the user's. Set only when someone wants to share something else.
     */
    val shareTreeUri: String? = null,

    /**
     * Whether the queue tops itself up from the taste model when it runs low.
     *
     * On by default: the point of the model is that the music keeps going. Turning it off leaves the
     * listening history and the Taste screen in place, so the feature can be paused without being
     * erased.
     */
    val autoplay: Boolean = true,

    /**
     * Whether playback is played but *not* learned from.
     *
     * Separate from [autoplay]: a listener may want the queue to keep going without this session
     * shaping what it plays next, which is what listening to something unrepresentative on purpose
     * needs.
     */
    val pauseLearning: Boolean = false,
)
