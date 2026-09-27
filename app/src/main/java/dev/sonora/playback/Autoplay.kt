package dev.sonora.playback

import dev.sonora.ytm.YtmTrack

/** How many suggested tracks are kept queued ahead of the sounding one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/**
 * Whether the queue should be topped up right now.
 *
 * Only when the sounding track is the last thing in the queue. Topping up earlier would mean
 * deciding what to play next long before the listener gets there, and the answer goes stale: a
 * track that made sense twenty minutes ago is not obviously the one to follow.
 *
 * Not while a load is running. Two loads racing each other both read the queue as short, both ask
 * for the same suggestions, and the listener hears the same three songs twice.
 *
 * Not when repeat-all is on. That already has a plan for what comes next — the queue it is about to
 * come round again — and a second plan beside it means the two disagree at the moment the repeat
 * takes effect.
 */
fun shouldTopUp(
    enabled: Boolean,
    repeatAll: Boolean,
    currentIndex: Int,
    itemCount: Int,
    loadInProgress: Boolean,
): Boolean = enabled &&
    !repeatAll &&
    !loadInProgress &&
    currentIndex >= 0 &&
    currentIndex == itemCount - 1

/**
 * The suggestions to add, given what is already queued and what was just heard.
 *
 * Ordered so the answer is the same for the same inputs: the network returns whatever order it
 * likes, and a queue whose contents depend on that ordering is a queue that reshuffles itself every
 * time it is topped up.
 *
 * [recent] is the tail of what has already played rather than the whole history. A track the
 * listener heard twenty songs ago is welcome back; the one that ended a moment ago is the one
 * that must not.
 */
fun extend(
    queued: List<YtmTrack>,
    candidates: List<YtmTrack>,
    recent: List<String>,
    limit: Int = MAX_QUEUED_AUTOPLAY,
): List<YtmTrack> {
    val seen = HashSet<String>(queued.size + candidates.size)
    queued.forEach { seen += it.videoId }

    // The newest exclusion wins: the list is written oldest-first, so walking it backwards means
    // only the last [limit] of history is ever consulted and a very long history costs nothing.
    val recentIds = recent.asReversed().take(limit * 2).toHashSet()

    val added = LinkedHashMap<String, YtmTrack>()
    for (candidate in candidates) {
        if (added.size >= limit) break
        if (candidate.videoId in seen) continue
        if (candidate.videoId in recentIds) continue
        if (candidate.durationSec == 0) continue

        seen += candidate.videoId
        added[candidate.videoId] = candidate
    }

    return added.values.toList()
}

/**
 * What to ask the network for, given the track that just finished.
 *
 * The artist rather than the song. A search for a song's own title returns that song and whatever
 * version of it somebody has uploaded, which is a queue of the same recording; a search for the
 * artist returns what they actually made, which is what "keep going" means.
 *
 * Falls back to the title when there is no artist to search for, and to nothing at all when there
 * is neither — because a query built from an empty string is not a search, it is a request for
 * whatever YouTube thinks is most popular that hour.
 */
fun seedQueryFor(track: YtmTrack?): String? {
    val artist = track?.artist?.trim().orEmpty()
    if (artist.isNotEmpty()) return artist

    val title = track?.title?.trim().orEmpty()
    return title.ifEmpty { null }
}

/**
 * The next seed to try, given that the last one came back with nothing.
 *
 * A queue cannot run out because a search came back empty: it goes back one step and asks again,
 * and only gives up when there is nothing left to go back to. That is the whole of what "infinite"
 * has to mean here — the listener is never handed an empty queue and a silence, because there was
 * a gap in one search rather than an absence of music.
 */
fun nextSeed(
    tried: List<String>,
    history: List<YtmTrack>,
): YtmTrack? {
    val asked = tried.toHashSet()
    return history.asReversed().firstOrNull { track ->
        seedQueryFor(track)?.let { it !in asked } == true
    }
}
