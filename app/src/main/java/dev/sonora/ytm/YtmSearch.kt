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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One track YouTube Music knows about, as far as a search can describe it. */
data class YtmTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artworkUrl: String? = null,
)

/**
 * Searches YouTube Music's catalogue.
 *
 * A plain InnerTube call against `music.youtube.com` — no client library, no credential, and
 * nothing that has to be kept in step with a moving extractor. The response is read field by field
 * rather than modelled, because it is a page of renderers rather than a documented contract and a
 * generated model over it would break on the next field YouTube renames.
 *
 * ### Why WEB_REMIX
 *
 * The client in the request decides the shape of the answer, not just the identity. The ordinary
 * WEB client answers a music query with generic video rows; WEB_REMIX is the web *player's* client
 * and answers with the music rows this wants — title, artist, album and artwork, all with the ids
 * needed to open an artist or an album later. Both work signed out; only one of them is useful here.
 */
object YtmSearch {

    private const val TAG = "YtmSearch"

    private const val ENDPOINT = "https://music.youtube.com/youtubei/v1/search"

    /**
     * YouTube's own published web key. It is not a secret and not an account: it identifies the
     * client, and the request it is allowed to make is bounded by what that client may see, which
     * for this one is public data.
     */
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

    private const val CLIENT_VERSION = "1.20260707.12.00"

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    /** The `params` that ask for the songs tab rather than the mixed default. */
    private const val SONGS_PARAMS = "EgWKAQIIAWoKEAkQChAFEAMQBA=="

    /** A page is 20 rows; asking for more than two is not worth the round trips. */
    private const val MAX_RESULTS = 40

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Tracks matching [query], best match first, or an empty list when YouTube has nothing or
     * refuses.
     *
     * Never throws: a catalogue that cannot be reached is an empty result, because the alternative
     * is taking down a search screen that has other sources on it.
     */
    suspend fun search(query: String): List<YtmTrack> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()

        return runCatching {
            val body = buildJsonObject {
                put("context", context())
                put("query", JsonPrimitive(term))
                put("params", JsonPrimitive(SONGS_PARAMS))
            }
            val response: String = YtmHttp.ktor.post(ENDPOINT) {
                parameter("key", KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.body()

            parse(json.parseToJsonElement(response).jsonObject)
        }.onFailure {
            Log.w(TAG, "search for '$term' failed: ${it.message}")
        }.getOrDefault(emptyList())
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

    /** Reads the songs shelf out of the tabs and off the rows. */
    private fun parse(root: JsonElement): List<YtmTrack> {
        val tracks = mutableListOf<YtmTrack>()
        for (tab in root.descend("contents", "tabbedSearchResultsRenderer", "tabs").arr()) {
            val sections = tab.descend("tabRenderer", "content", "sectionListRenderer", "contents").arr()
            for (section in sections) {
                val rows = section.obj()?.get("musicShelfRenderer").obj()
                    ?.get("contents").arr()
                for (row in rows) {
                    parseRow(row.obj()?.get("musicResponsiveListItemRenderer"))?.let(tracks::add)
                    if (tracks.size >= MAX_RESULTS) return tracks
                }
            }
        }
        return tracks
    }

    /**
     * One row, or null when it is not a track — the shelf also carries "shuffle" and ad rows.
     *
     * Artist and album are taken from the runs that carry a browse endpoint rather than by splitting
     * the column on its bullet separators: the separators are unlinked runs, and a linked run is by
     * definition a real entity, so the split survives however YouTube decorates the rest.
     *
     * Every column is scanned for them rather than a fixed index, because there is no fixed index:
     * a search row, an album page and a playlist row all lay the same fields out differently, and
     * a spacer column is sometimes sitting where the credits were in the last shape seen.
     */
    private fun parseRow(element: JsonElement?): YtmTrack? {
        val row = element.obj() ?: return null
        val videoId = row.descend("playlistItemData", "videoId").str() ?: return null

        val columns = row["flexColumns"].arr()
        val title = columns.firstOrNull().flexText()
        if (title.isNullOrBlank()) return null

        val credits = columns.flatMap { it.runs() }
        val artistRun = credits.firstOrNull { it.pageType == "MUSIC_PAGE_TYPE_ARTIST" }
        val albumRun = credits.firstOrNull { it.pageType == "MUSIC_PAGE_TYPE_ALBUM" }
        if (artistRun == null || artistRun.text.isBlank()) return null

        return YtmTrack(
            videoId = videoId,
            title = title.trim(),
            artist = artistRun.text.trim(),
            artistId = artistRun.browseId,
            album = albumRun?.text?.trim(),
            albumId = albumRun?.browseId,
            artworkUrl = row.descend(
                "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
            ).arr().mapNotNull { it.obj()?.get("url").str() }.lastOrNull(),
        )
    }

    private data class Run(val text: String, val pageType: String?, val browseId: String?)

    /**
     * The text runs of one flex column.
     *
     * Takes the column itself, not its runs, so a caller can hand over every column it has without
     * having to know which of them is the one carrying the credits.
     */
    private fun JsonElement?.runs(): List<Run> =
        this.descend("musicResponsiveListItemFlexColumnRenderer", "text", "runs").arr().mapNotNull { element ->
        val run = element.obj() ?: return@mapNotNull null
        val endpoint = run.descend("navigationEndpoint", "browseEndpoint").obj()
        Run(
            text = run["text"].str().orEmpty(),
            pageType = endpoint
                ?.descend("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType")
                .str(),
            browseId = endpoint?.get("browseId").str(),
        )
    }

    private fun JsonElement?.flexText(): String? {
        val text = this.obj()?.get("musicResponsiveListItemFlexColumnRenderer")
            ?.obj()?.get("text")
            ?.obj()
            ?: return null
        return text["runs"].arr().joinToString("") { it.obj()?.get("text").str().orEmpty() }
    }

    /**
     * Walks a chain of object keys, stopping at the first one that is absent.
     *
     * The response nests six levels deep through renderers YouTube renames freely, so every level is
     * optional and a missing one is an empty result rather than an exception.
     */
    private fun JsonElement?.descend(vararg keys: String): JsonElement? {
        var current: JsonElement? = this
        for (key in keys) {
            current = current.obj()?.get(key) ?: return null
        }
        return current
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray).orEmpty()

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
