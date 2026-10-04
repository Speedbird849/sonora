package dev.sonora.backend.taste

import kotlinx.serialization.Serializable

/**
 * What the engine knows about one track, apart from its identity.
 *
 * The counts are plain and never decay — "played 14 times" is a fact about the track, not a taste
 * that fades. [weight] is the decaying sum of the signals, which is what actually ranks it: a track
 * loved a year ago and one loved yesterday both show a count of one, and only the weight tells them
 * apart.
 */
@Serializable
data class TrackStats(
    val plays: Int = 0,
    val completes: Int = 0,
    val skips: Int = 0,
    val lastPlayed: Long = 0L,
    val liked: Boolean = false,
    val weight: Decayed = Decayed(),
) {
    /** The fraction of plays that ran to completion, or 0 when it has never been played. */
    fun completionRate(): Double = if (plays == 0) 0.0 else completes.toDouble() / plays.toDouble()

    /** The fraction of plays that were skipped, or 0 when it has never been played. */
    fun skipRate(): Double = if (plays == 0) 0.0 else skips.toDouble() / plays.toDouble()
}
