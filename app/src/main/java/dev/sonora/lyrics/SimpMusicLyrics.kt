package dev.sonora.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Lyrics from SimpMusic database, keyed on the YouTube video ID.
 */
object SimpMusicLyrics {

    private const val BASE = "https://api-lyrics.simpmusic.org/v1/"
    private const val DURATION_TOLERANCE_SECONDS = 10

    suspend fun lyrics(videoId: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            if (videoId.isBlank()) return@withContext null
            val body = lyricsGet(BASE + videoId) ?: return@withContext null
            val response = runCatching { lyricsJson.decodeFromString<Response>(body) }.getOrNull()
            if (response == null || !response.success) return@withContext null

            val seconds = (durationMs / 1000).toInt()
            val track = response.data.orEmpty()
                .filter { seconds <= 0 || abs((it.duration ?: 0) - seconds) <= DURATION_TOLERANCE_SECONDS }
                .minByOrNull { abs((it.duration ?: 0) - seconds) }
                ?: return@withContext null

            track.richSyncLyrics?.takeIf { it.isNotBlank() }
                ?.let { EnhancedLrc.parse(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: track.syncedLyrics?.takeIf { it.isNotBlank() }
                    ?.let { Lrc.parse(it) }
                    ?.takeIf { it.isNotEmpty() }
        }

    @Serializable
    internal data class Response(
        val success: Boolean = false,
        val data: List<Track>? = null,
    )

    @Serializable
    internal data class Track(
        val duration: Int? = null,
        val richSyncLyrics: String? = null,
        val syncedLyrics: String? = null,
        val plainLyrics: String? = null,
    )
}
