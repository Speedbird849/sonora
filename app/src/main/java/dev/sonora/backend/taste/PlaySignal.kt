package dev.sonora.backend.taste

/**
 * Turns one play into the number the taste model learns from.
 *
 * Pure, so the thresholds can be tested as a table without a player or a clock. The sign is the
 * part that matters: a completed track teaches the model what to serve again, and a skip teaches it
 * what not to — which is why a skipped pick is allowed to push its own edge negative.
 */
object PlaySignal {

    /** Below this fraction of the track, a short play is a skip rather than a listen. */
    const val EARLY_RATIO = 0.25

    /** Played at least this long and it is a listen even if the track is long. */
    const val EARLY_MS = 30_000L

    /** At or above this fraction counts as a completed play. */
    const val COMPLETE_RATIO = 0.8

    /** At or above this fraction is a partial listen worth some credit. */
    const val PARTIAL_RATIO = 0.5

    /** A play that touched almost none of the track and was over quickly. */
    const val SKIP = -0.8

    /** A play that touched little of the track but ran on: paused, or abandoned mid-song. */
    const val ABANDONED = -0.5

    /** A completed play. */
    const val COMPLETE = 1.0

    /** A play that got through more than half. */
    const val PARTIAL = 0.4

    /**
     * The signal for one play.
     *
     * @param playedRatio the real played fraction of the track, or null when the duration is not
     *   known — in which case only the absolute time can distinguish a skip from a listen.
     */
    fun signal(playedRatio: Double?, listenedMs: Long, liked: Boolean): Double {
        val base = when {
            playedRatio != null && playedRatio >= COMPLETE_RATIO -> COMPLETE
            playedRatio != null && playedRatio >= PARTIAL_RATIO -> PARTIAL
            playedRatio != null && playedRatio >= EARLY_RATIO -> 0.0
            listenedMs < EARLY_MS -> SKIP
            else -> ABANDONED
        }

        // A like is a deliberate statement, so it lifts even a play that scored badly: liking a
        // track you skipped means the skip was about the moment, not the music.
        return if (liked) maxOf(base, COMPLETE) + 0.5 else base
    }
}
