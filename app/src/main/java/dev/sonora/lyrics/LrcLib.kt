package dev.sonora.lyrics

import android.util.Log
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import dev.sonora.ytm.YtmHttp
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs

/**
 * Lyrics from LRCLIB — a free, key-less, community lyrics database.
 *
 * Two calls: an exact `get` keyed on artist, title and duration, and a fuzzy `search` when that
 * misses. The exact call is the only one that can be trusted outright, because a title carrying
 * "(From …)" or "| Official Video" will not match it — and a database matched on a title nobody
 * typed is a database that answers about a different song.
 *
 * The fuzzy fallback prefers whichever hit is closest in length to what is playing. Same song,
 * different edit, would otherwise drift: a line that is four seconds late for the whole of a verse
 * reads as bad timing rather than as the wrong recording.
 */
object LrcLib {

    private const val TAG = "LrcLib"

    private const val BASE = "https://lrclib.net/api"

    /**
     * Who is asking, which the database asks for and which it rate-limits without.
     *
     * A real name and a real place to read about it, because a bare user agent is indistinguishable
     * from a scraper and gets treated like one.
     */
    private const val AGENT = "Sonora (https://github.com/sonora)"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The lines for a track, or an empty list when nothing usable is published. */
    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            val cleanTitle = clean(title)
            val cleanArtist = clean(artist)
            if (cleanTitle.isEmpty()) return@withContext emptyList()
            val seconds = (durationMs / 1000L).toInt().coerceAtLeast(1)

            val exact = runCatching { exactMatch(cleanTitle, cleanArtist, seconds) }
                .onFailure { Log.w(TAG, "exact lookup for '$cleanArtist - $cleanTitle' failed: ${it.message}") }
                .getOrNull()

            val raw = exact ?: runCatching { bestSearchHit(cleanTitle, cleanArtist, seconds) }
                .onFailure { Log.w(TAG, "search for '$cleanArtist - $cleanTitle' failed: ${it.message}") }
                .getOrNull()

            if (raw == null) return@withContext emptyList()

            val parsed = Lrc.parse(raw)
            // A gap with no end is a stray stamp rather than an instrumental stretch, and drawn as
            // one it is a row of dots in the middle of a verse.
            parsed.filter { !it.isGap || it.endMs != null }
        }

    private suspend fun exactMatch(title: String, artist: String, seconds: Int): String? {
        val body = get(
            "$BASE/get",
            mapOf("track_name" to title, "artist_name" to artist, "duration" to seconds.toString()),
        ) ?: return null
        return (json.parseToJsonElement(body) as? JsonObject)?.synced()
    }

    private suspend fun bestSearchHit(title: String, artist: String, seconds: Int): String? {
        val body = get("$BASE/search", mapOf("track_name" to title, "artist_name" to artist))
            ?: return null
        val hits = json.parseToJsonElement(body) as? JsonArray ?: return null

        return hits.mapNotNull { it as? JsonObject }
            .filter { it.synced()?.isNotBlank() == true }
            .minByOrNull { entry -> abs(((entry["duration"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) - seconds) }
            ?.synced()
    }

    private suspend fun get(url: String, params: Map<String, String>): String? = runCatching {
        YtmHttp.ktor.get(url) {
            parameter("track_name", params["track_name"])
            params["artist_name"]?.let { parameter("artist_name", it) }
            params["duration"]?.let { parameter("duration", it) }
            header(HttpHeaders.UserAgent, AGENT)
        }.body<String>()
    }.getOrNull()

    /** The synced text, or null when this entry has none. */
    private fun JsonObject.synced(): String? =
        (this["syncedLyrics"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /**
     * The noise a catalogue wraps a title in.
     *
     * Stripped for the lookup only — the track keeps the name it has. A database stores the name
     * the song was released under, and the extra words are the video uploader's, not the artist's.
     */
    private fun clean(value: String): String = value
        .replace(Regex("""\s*[\(\[][^\)\]]*(remaster|remix|live|version|edit|mono|stereo|official|audio|video|lyrics?|hd|hq|explicit|feat\.?)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\s*[-–—]\s*(official\s+)?(music\s+)?video\s*$""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
