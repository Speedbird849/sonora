package dev.sonora.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

/**
 * Multi-provider concurrent lyrics resolver.
 *
 * Races Apple Music TTML providers (BiniLyrics, BetterLyrics), syllable-timed LyricsPlus,
 * video-keyed SimpMusic, and LRCLIB in parallel. Word-synced results are prioritized,
 * while line-synced results serve as fallback.
 */
object LyricsRepository {

    data class Result(val source: LyricsSource, val lines: List<LyricLine>)

    suspend fun lyrics(
        videoId: String = "",
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        order: List<LyricsSource> = LyricsSource.entries,
        prioritizeSyllableSync: Boolean = true,
        isrc: String? = null,
    ): Result? = coroutineScope {
        val sequence = order.filter { it in sources } +
            LyricsSource.entries.filter { it in sources && it !in order }

        val searchTitle = title.forLyricsSearch()
        val searchArtist = artist.artistForLyricsSearch()

        val known = isrc?.takeIf { it.isNotBlank() } ?: isrcs[videoId]
        val hit = if (known == null) {
            identify(videoId, searchTitle, searchArtist, durationMs, album, sequence)
        } else {
            null
        }
        val recording = known ?: hit?.isrc?.takeIf { it.isNotBlank() }

        val racing: List<Pair<LyricsSource, Deferred<Result?>>> = sequence.map { source ->
            source to async(Dispatchers.IO) {
                try {
                    val found = fetch(
                        source,
                        videoId,
                        searchTitle,
                        searchArtist,
                        durationMs,
                        album,
                        recording,
                        hit,
                    )?.let { result(source, it) }
                    found
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }

        try {
            var lineSynced: Result? = null
            for ((_, job) in racing) {
                val found = runCatching { job.await() }.getOrNull() ?: continue
                if (found.lines.any { it.isWordSynced }) return@coroutineScope found
                if (!prioritizeSyllableSync && found.lines.any { it.timeMs > 0 }) {
                    return@coroutineScope found
                }
                if (lineSynced == null) lineSynced = found
            }
            lineSynced
        } finally {
            racing.forEach { it.second.cancel() }
        }
    }

    private suspend fun fetch(
        source: LyricsSource,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        isrc: String?,
        hit: BiniLyrics.Hit?,
    ): List<LyricLine>? {
        return when (source) {
            LyricsSource.BINI_LYRICS ->
                (hit?.let { BiniLyrics.lyricsFor(it) }
                    ?: BiniLyrics.lyrics(title, artist, durationMs, album, isrc))
                    ?.also { remember(videoId, it.isrc) }
                    ?.lines
            LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, durationMs, album)
            LyricsSource.LYRICS_PLUS -> LyricsPlus.lyrics(title, artist, durationMs, album, isrc)
            LyricsSource.SIMP_MUSIC -> if (videoId.isNotBlank()) SimpMusicLyrics.lyrics(videoId, durationMs) else null
            LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, durationMs).takeIf { it.isNotEmpty() }
        }
    }

    private fun result(source: LyricsSource, lines: List<LyricLine>) =
        Result(source, lines.withBackgroundVocals())

    private const val IDENTIFY_TIMEOUT_MS = 2_500L

    private suspend fun identify(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sequence: List<LyricsSource>,
    ): BiniLyrics.Hit? {
        if (LyricsSource.BINI_LYRICS !in sequence) return null
        val hit = withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
            runCatching { BiniLyrics.identify(title, artist, durationMs, album) }.getOrNull()
        }
        if (hit == null) return null
        remember(videoId, hit.isrc)
        return hit
    }

    private const val REMEMBERED = 100

    private val isrcs: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(REMEMBERED, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > REMEMBERED
        },
    )

    private fun remember(videoId: String, isrc: String?) {
        if (isrc.isNullOrBlank() || videoId.isEmpty()) return
        isrcs[videoId] = isrc
    }
}
