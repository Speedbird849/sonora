package dev.sonora.backend.taste

import kotlin.math.pow
import kotlinx.serialization.Serializable

/**
 * A counter that decays with a half-life, stored as its value at the moment it was last touched.
 *
 * Nothing is recomputed on a timer: the stored [value] is whatever it was when [t] was stamped, and
 * every read uses the elapsed time since. That makes a write O(1) and, more importantly, means the
 * model does not have to be walked and rewritten to stay current — a model that sat on disk for a
 * week reads exactly as if it had decayed for a week.
 *
 * The half-life is passed per call rather than stored, because the same value type is used for
 * track, artist, tag and edge signals and those decay at different rates.
 */
@Serializable
data class Decayed(val value: Double = 0.0, val t: Long = 0L) {

    /** The value as of [now], decayed by `0.5 ^ ((now - t) / halfLife)`. */
    fun at(now: Long, halfLifeMs: Long): Double {
        if (value == 0.0 || halfLifeMs <= 0L) return value

        val elapsed = now - t
        // A clock that moved backwards (or a value stamped in the future) is treated as "no time has
        // passed" rather than growing the value; a decay is never a gain.
        if (elapsed <= 0L) return value

        return value * 0.5.pow(elapsed.toDouble() / halfLifeMs.toDouble())
    }

    /**
     * Decays to [now] and adds [x], stamping the result at [now].
     *
     * Decaying before adding is what makes repeated small additions accumulate correctly instead of
     * each one restarting from a stale, undecayed total.
     */
    fun add(x: Double, now: Long, halfLifeMs: Long): Decayed = Decayed(at(now, halfLifeMs) + x, now)
}
