package dev.sonora.ytm

import android.util.Log
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The tracks YouTube Music plays after [related]'s seed.
 *
 * The "next" endpoint is what the player itself asks when a radio or a mix starts, and it is the
 * only catalogue answer that is *seeded by a track* rather than by a query or a browse id. That is
 * exactly what a cold-start Autoplay needs: an empty taste model can say nothing about what follows,
 * and this gives it a starting point without inventing one.
 *
 * Plain InnerTube against `music.youtube.com`, same client and key as [YtmSearch], for the same
 * reason: WEB_REMIX is the client that answers a music request with music rows.
 */
object YtmRadio {

    private const val TAG = "YtmRadio"

    private const val ENDPOINT = "https://music.youtube.com/youtubei/v1/next"

    /** YouTube's own published web-client key. See the note on the same constant in [YtmSearch]. */
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

    private const val CLIENT_VERSION = "1.20260707.12.00"

    /** How many rows the radio answer is allowed to contribute. */
    const val DEFAULT_LIMIT = 25

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Tracks related to [videoId], or an empty list when YouTube has nothing or refuses.
     *
     * Never throws: a radio endpoint that cannot be reached must degrade to "no exploration
     * candidates", not take down a queue refill.
     */
    suspend fun related(videoId: String, limit: Int = DEFAULT_LIMIT): List<YtmTrack> {
        if (videoId.isBlank()) return emptyList()

        return runCatching {
            val body = buildJsonObject {
                put("context", context())
                put("videoId", JsonPrimitive(videoId))
                put("isAudioOnly", JsonPrimitive(true))
                // Ask for the radio rather than the plain up-next queue; without it the answer is
                // an auto-generated continuation that is mostly the same artist's videos.
                put("params", JsonPrimitive("wAEB"))
            }
            val response: String = YtmHttp.ktor.post(ENDPOINT) {
                parameter("key", KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.body()

            parse(json.parseToJsonElement(response), limit)
        }.onFailure {
            Log.w(TAG, "radio for '$videoId' failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * Every `playlistPanelVideoRenderer` in a response, in order.
     *
     * A recursive scan rather than the fixed path, because the panel is nested differently in a
     * watch-next answer than in a search or browse one and YouTube renames the wrappers freely — the
     * renderer that carries the row is stable, the six levels above it are not.
     */
    internal fun parse(root: JsonElement, limit: Int = DEFAULT_LIMIT): List<YtmTrack> {
        val rows = mutableListOf<JsonObject>()
        collect(root, "playlistPanelVideoRenderer", rows)

        return rows.mapNotNull(::parseRow)
            .distinctBy { it.videoId }
            .take(limit)
    }

    private fun collect(element: JsonElement, key: String, into: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> {
                (element[key] as? JsonObject)?.let(into::add)
                element.values.forEach { collect(it, key, into) }
            }

            is JsonArray -> element.forEach { collect(it, key, into) }
            else -> Unit
        }
    }

    /**
     * One panel row.
     *
     * The credits are laid out as runs and the separators between them are unlinked, so the artist
     * is the run that points at an artist page rather than the first run — a panel row often opens
     * with a "Song" or a year badge that is not a credit at all.
     */
    private fun parseRow(row: JsonObject): YtmTrack? {
        val videoId = row["videoId"].plain() ?: return null
        val title = row.descend("title").joined()?.trim().orEmpty()
        if (title.isEmpty()) return null

        val credits = row.descend("longBylineText").runs() +
            row.descend("shortBylineText").runs()

        val artistRun = credits.firstOrNull { it.pageType == "MUSIC_PAGE_TYPE_ARTIST" }
        val albumRun = credits.firstOrNull { it.pageType == "MUSIC_PAGE_TYPE_ALBUM" }
        val artist = artistRun?.text?.trim().orEmpty()

        return YtmTrack(
            videoId = videoId,
            title = title,
            artist = artist,
            artistId = artistRun?.browseId,
            album = albumRun?.text?.trim(),
            albumId = albumRun?.browseId,
            artworkUrl = row.descend("thumbnail", "thumbnails").arr()
                .lastOrNull()
                ?.obj()
                ?.get("url")
                ?.plain(),
            durationSec = runtimeSeconds(row),
        )
    }

    private fun runtimeSeconds(row: JsonObject): Int? = listOfNotNull(
        row.descend("lengthText"),
        row.descend("lengthSeconds"),
    ).firstNotNullOfOrNull {
        it.joined()?.secondsOrNull() ?: (it as? JsonPrimitive)?.content?.toIntOrNull()
    }

    private data class Run(val text: String, val pageType: String?, val browseId: String?)

    private fun JsonElement?.runs(): List<Run> =
        (this.obj()?.get("runs") ?: this).arr().mapNotNull { element ->
            val run = element.obj() ?: return@mapNotNull null
            val endpoint = run.descend("navigationEndpoint", "browseEndpoint").obj()
            Run(
                text = run["text"].plain().orEmpty(),
                pageType = endpoint
                    ?.descend("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType")
                    ?.plain(),
                browseId = endpoint?.get("browseId")?.plain(),
            )
        }

    private fun context() = buildJsonObject {
        put(
            "client",
            buildJsonObject {
                put("clientName", JsonPrimitive("WEB_REMIX"))
                put("clientVersion", JsonPrimitive(CLIENT_VERSION))
                put("hl", JsonPrimitive("en"))
                put("gl", JsonPrimitive("US"))
            },
        )
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray).orEmpty()

    private fun JsonElement?.plain(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Runs joined, or a bare `simpleText`, whichever a field uses. */
    private fun JsonElement?.joined(): String? {
        if (this is JsonPrimitive) return content
        this.obj()?.get("simpleText")?.plain()?.let { return it }
        val runs = (this.obj()?.get("runs") ?: this).arr()
        if (runs.isEmpty()) return null
        return runs.mapNotNull { it.obj()?.get("text")?.plain() }.joinToString("")
    }

    private fun JsonElement?.descend(vararg keys: String): JsonElement? {
        var current: JsonElement? = this
        for (key in keys) current = current.obj()?.get(key) ?: return null
        return current
    }

    private fun String.secondsOrNull(): Int? {
        val parts = trim().split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return when (numbers.size) {
            2 -> numbers[0] * 60 + numbers[1]
            3 -> if (numbers[0] == 0) numbers[1] * 60 + numbers[2] else null
            else -> null
        }
    }
}
