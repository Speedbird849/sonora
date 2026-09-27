package dev.sonora.backend

/** What the player is doing, as far as the UI is concerned. */
/** What happens when the queue runs out. */
enum class RepeatMode {
    /** Stop at the end. */
    Off,

    /** Loop the whole queue. */
    All,

    /** Loop the current track. */
    One,
}

data class PlaybackState(
    val track: LibraryTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isShuffled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    /**
     * Whether the track is known and its audio is still being fetched.
     *
     * A streaming track has no URL until one is minted for it, and that is a round trip to a
     * service that decides whether it will serve the track at all. Publishing the track first and
     * this flag with it is what lets the mini player appear on the tap rather than a second later
     * when the fetch lands — the listener gets the title, the cover and a spinner immediately, and
     * the sound when it is actually available.
     */
    val isResolving: Boolean = false,
)
