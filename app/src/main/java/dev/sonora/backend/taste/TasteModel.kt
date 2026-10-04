package dev.sonora.backend.taste

import kotlinx.serialization.Serializable

/** One track's identity and what has been learned about it. */
@Serializable
data class TrackEntry(
    val ref: TrackRef,
    val stats: TrackStats = TrackStats(),
)

/**
 * Everything the taste engine has learned, as one serializable document.
 *
 * The shape mirrors how it is scored rather than how it is recorded: [edges] answers "what follows
 * this", [artists] and [tags] answer "what does this listener like", and [tracks] holds the counts
 * and the identity needed to play any of it. [recent] is the exclusion window Autoplay reads.
 *
 * [lastKey]/[lastAt]/[lastPositive] are the pending half of a transition. A transition is only known
 * when the *next* track starts, so the previous start has to survive process death — a model that
 * kept it in memory would lose the edge across every app switch.
 */
@Serializable
data class TasteModel(
    val schemaVersion: Int = SCHEMA_VERSION,
    val tracks: Map<String, TrackEntry> = emptyMap(),
    /** `prev -> cur -> weight`, the core of "what follows what". */
    val edges: Map<String, Map<String, Decayed>> = emptyMap(),
    /** `prev -> cur -> times`, so a transition can be shown as "×14" rather than a weight. */
    val edgeCounts: Map<String, Map<String, Int>> = emptyMap(),
    val artists: Map<String, Decayed> = emptyMap(),
    /** Signal split across a track's tags, keyed by the tag itself. */
    val tags: Map<String, Decayed> = emptyMap(),
    /** Keys in play order, newest last, capped at [TasteModel.RECENT_CAP]. */
    val recent: List<String> = emptyList(),
    val lastKey: String? = null,
    val lastAt: Long = 0L,
    val lastPositive: Boolean = false,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val SCHEMA_VERSION = 1

        /**
         * How many recent keys are kept.
         *
         * Larger than the 40 Autoplay excludes so the window is still full when a listener deletes
         * something from the library and a key drops out of the model.
         */
        const val RECENT_CAP = 60

        val EMPTY = TasteModel()
    }
}
