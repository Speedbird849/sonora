package dev.sonora.backend.taste

import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Who put a track on. An autoplay pick teaches the model less than a deliberate one. */
enum class Origin { USER, AUTOPLAY }

/**
 * Learns a listener's taste from what they play, on device.
 *
 * Everything is derived from signals the player already produces — a transition, a played ratio, a
 * pause — so there is no tracking beyond what the app needs to play music. The model outlives the
 * process (see [TasteStore]) and is scored by [next] to refill the queue.
 *
 * The engine is pure Kotlin: no Android types, an injected clock and [Random], which is what lets
 * every rule and the whole scoring loop be tested on the JVM.
 */
class TasteEngine(
    private val store: TasteStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {

    @Volatile
    private var model: TasteModel = store.load()

    /** One lock for every mutation, so a save never observes a half-written model. */
    private val mutex = Mutex()

    /** The model as it stands. For tests and the Taste screen; not a live view. */
    fun snapshot(): TasteModel = model

    /** The decayed weight of `from -> to`, or 0. For tests and diagnostics. */
    fun edgeWeight(from: String, to: String, now: Long = clock()): Double =
        model.edges[from]?.get(to)?.at(now, HALF_LIFE_EDGE) ?: 0.0

    /**
     * Records one play and folds it into the model.
     *
     * @param listenedMs real played time, not wall-clock — the caller banks it across pauses.
     * @param durationMs the track's length, or null when it is not known.
     * @param liked whether the track is liked right now.
     */
    suspend fun recordPlay(
        ref: TrackRef,
        listenedMs: Long,
        durationMs: Long?,
        origin: Origin = Origin.USER,
        liked: Boolean = false,
        now: Long = clock(),
    ) {
        val ratio = durationMs
            ?.takeIf { it > 0L }
            ?.let { (listenedMs.toDouble() / it.toDouble()).coerceIn(0.0, 1.0) }
        val signal = PlaySignal.signal(ratio, listenedMs, liked)
        val completed = ratio != null && ratio >= PlaySignal.COMPLETE_RATIO

        // An autoplay pick scoring a hit is the engine rewarding itself; a positive one counts
        // half, so the queue drifts outward instead of collapsing onto its own last few picks.
        val weighted = if (origin == Origin.AUTOPLAY && signal > 0.0) {
            signal * AUTOPLAY_POSITIVE_WEIGHT
        } else {
            signal
        }

        mutex.withLock {
            model = apply(
                model = model,
                ref = ref,
                signal = signal,
                weighted = weighted,
                completed = completed,
                liked = liked,
                now = now,
            )
            store.scheduleSave(model)
        }
    }

    /** Sets the like flag without recording a play, so a like teaches the artist and the tags too. */
    suspend fun setLiked(key: String, liked: Boolean, now: Long = clock()) {
        mutex.withLock {
            val entry = model.tracks[key] ?: return@withLock
            if (entry.stats.liked == liked) return@withLock

            val delta = if (liked) 0.5 else -0.5
            val stats = entry.stats.copy(liked = liked, weight = entry.stats.weight.add(delta, now, HALF_LIFE_TRACK))
            var updated = model.copy(
                tracks = model.tracks + (key to entry.copy(stats = stats)),
                updatedAt = now,
            )
            updated = updated.copy(
                artists = bump(updated.artists, entry.ref.artistKey, delta, now, HALF_LIFE_ARTIST),
            )
            if (entry.ref.tags.isNotEmpty()) {
                val each = delta / entry.ref.tags.size
                var tags = updated.tags
                for (tag in entry.ref.tags.distinct()) tags = bump(tags, tag, each, now, HALF_LIFE_TAG)
                updated = updated.copy(tags = tags)
            }
            model = updated
            store.scheduleSave(updated)
        }
    }

    /** Wipes everything learned. */
    suspend fun reset(now: Long = clock()) {
        mutex.withLock {
            val empty = TasteModel(updatedAt = now)
            model = empty
            store.writeNow(empty)
        }
    }

    /** Writes any queued model immediately. */
    suspend fun flush() = store.flush()

    private fun apply(
        model: TasteModel,
        ref: TrackRef,
        signal: Double,
        weighted: Double,
        completed: Boolean,
        liked: Boolean,
        now: Long,
    ): TasteModel {
        val existing = model.tracks[ref.key]
        val oldStats = existing?.stats ?: TrackStats()

        val stats = oldStats.copy(
            plays = oldStats.plays + 1,
            completes = oldStats.completes + if (completed) 1 else 0,
            skips = oldStats.skips + if (signal < 0.0) 1 else 0,
            lastPlayed = now,
            liked = liked,
            weight = oldStats.weight.add(weighted, now, HALF_LIFE_TRACK),
        )

        var updated = model.copy(
            tracks = model.tracks + (ref.key to TrackEntry(merge(existing?.ref, ref), stats)),
            artists = bump(model.artists, ref.artistKey, weighted, now, HALF_LIFE_ARTIST),
        )

        if (ref.tags.isNotEmpty()) {
            val each = weighted / ref.tags.size
            var tags = updated.tags
            for (tag in ref.tags.distinct()) tags = bump(tags, tag, each, now, HALF_LIFE_TAG)
            updated = updated.copy(tags = tags)
        }

        updated = learnEdge(updated, ref, weighted, signal, now)
        updated = updated.copy(
            recent = (updated.recent + ref.key).takeLast(TasteModel.RECENT_CAP),
            lastKey = ref.key,
            lastAt = now,
            lastPositive = signal > 0.0,
            updatedAt = now,
        )

        return enforceTrackCap(updated, now)
    }

    /**
     * Writes `previous -> current` when it is a real transition.
     *
     * Four conditions, each of which has a failure it prevents: the same track twice is not a
     * transition; a previous track that was skipped taught nothing worth following; a current track
     * with no signal (the 0.25–0.5 band) is not evidence of anything; and a gap over 30 minutes is a
     * different session, so this morning's track should not claim to precede tonight's.
     */
    private fun learnEdge(
        model: TasteModel,
        ref: TrackRef,
        weighted: Double,
        signal: Double,
        now: Long,
    ): TasteModel {
        val previous = model.lastKey ?: return model
        if (previous == ref.key) return model
        if (!model.lastPositive) return model
        if (signal == 0.0) return model
        if (now <= model.lastAt || now - model.lastAt >= SESSION_GAP_MS) return model

        val nodes = model.edges[previous].orEmpty()
        val updated = nodes[ref.key]?.add(weighted, now, HALF_LIFE_EDGE)
            ?: Decayed().add(weighted, now, HALF_LIFE_EDGE)

        return model.copy(
            edges = model.edges + (previous to pruneNode(nodes + (ref.key to updated), now)),
        )
    }

    /** Keeps the strongest edges out of a node, dropping the ones that have decayed to noise. */
    private fun pruneNode(nodes: Map<String, Decayed>, now: Long): Map<String, Decayed> =
        nodes.asSequence()
            .map { (to, weight) -> Triple(to, weight, weight.at(now, HALF_LIFE_EDGE)) }
            .filter { (_, _, decayed) -> kotlin.math.abs(decayed) > MIN_EDGE_WEIGHT }
            .sortedByDescending { (_, _, decayed) -> kotlin.math.abs(decayed) }
            .take(MAX_EDGES_PER_NODE)
            .associate { (to, weight, decayed) -> to to Decayed(decayed, now) }

    /**
     * Bounds the model by dropping the least-recently-played, low-signal tracks first.
     *
     * Hit only by a listening history that would otherwise grow without limit; a real model of a few
     * thousand tracks never reaches it. Edges to and from a dropped track are removed with it so the
     * graph cannot point at a track that is no longer there.
     */
    private fun enforceTrackCap(model: TasteModel, now: Long): TasteModel {
        if (model.tracks.size <= MAX_TRACKS) return model

        val dropped = model.tracks.entries
            .sortedWith(
                compareBy({ it.value.stats.lastPlayed }, { it.value.stats.weight.at(now, HALF_LIFE_TRACK) }),
            )
            .take(model.tracks.size - MAX_TRACKS)
            .mapTo(HashSet()) { it.key }

        return model.copy(
            tracks = model.tracks.filterKeys { it !in dropped },
            edges = model.edges
                .filterKeys { it !in dropped }
                .mapValues { (_, nodes) -> nodes.filterKeys { it !in dropped } },
        )
    }

    private fun merge(old: TrackRef?, new: TrackRef): TrackRef {
        if (old == null) return new
        return new.copy(
            title = new.title.ifBlank { old.title },
            artist = new.artist.ifBlank { old.artist },
            ytmId = new.ytmId ?: old.ytmId,
            localPath = new.localPath ?: old.localPath,
            durationMs = new.durationMs ?: old.durationMs,
            tags = (old.tags + new.tags).distinct(),
        )
    }

    private fun bump(
        map: Map<String, Decayed>,
        key: String,
        delta: Double,
        now: Long,
        halfLifeMs: Long,
    ): Map<String, Decayed> {
        if (key.isBlank()) return map
        val updated = map[key]?.add(delta, now, halfLifeMs) ?: Decayed().add(delta, now, halfLifeMs)
        return map + (key to updated)
    }

    companion object {
        private const val DAY = 24L * 60L * 60L * 1000L
        private const val MINUTE = 60_000L

        /** How fast a track's own accumulated signal fades. */
        val HALF_LIFE_TRACK = 30 * DAY

        /** How fast an artist's affinity fades. */
        val HALF_LIFE_ARTIST = 30 * DAY

        /** How fast a tag's affinity fades. */
        val HALF_LIFE_TAG = 45 * DAY

        /** How fast a learned transition fades. */
        val HALF_LIFE_EDGE = 60 * DAY

        /** Beyond this gap, two plays belong to different sessions and form no edge. */
        const val SESSION_GAP_MS = 30 * MINUTE

        /** A positive autoplay pick counts this much. */
        const val AUTOPLAY_POSITIVE_WEIGHT = 0.5

        /** Edges kept per node. */
        const val MAX_EDGES_PER_NODE = 30

        /** Below this decayed magnitude an edge is noise and is dropped. */
        const val MIN_EDGE_WEIGHT = 0.05

        /** The most tracks the model will hold. */
        const val MAX_TRACKS = 20_000
    }
}
