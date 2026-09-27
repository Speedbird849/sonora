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
 * One thing a search turned up that is not a song.
 *
 * Albums and artists are the same shape because they come from the same kind of row: a title, a
 * line of credits underneath, a picture, and the browse id that opens them. Splitting them into
 * two types would mean duplicating the parser to keep them apart for no gain.
 */
data class YtmEntity(
    val browseId: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    /** `MUSIC_PAGE_TYPE_ALBUM` or `MUSIC_PAGE_TYPE_ARTIST`, kept so the caller can tell them apart. */
    val kind: Kind,
) {
    enum class Kind { ALBUM, ARTIST }
}

/** Everything a search found, by what it is. */
data class YtmCatalog(
    val tracks: List<YtmTrack> = emptyList(),
    val albums: List<YtmEntity> = emptyList(),
    val artists: List<YtmEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

/**
 * Searches YouTube Music for albums and artists.
 *
 * A separate call per kind, and separate from [YtmSearch]'s, because the mixed response is not a
 * mixed response: asked for without a filter this client answers with songs only, so an album or
 * an artist has to be asked for by name — the same `params` a filter tab uses, which is what puts
 * the right kind of row in the shelf.
 *
 * Three requests for one query rather than one, and worth it: the songs request is already made,
 * these two answer in about a second, and each shelf is shown the moment its own answer lands
 * rather than waiting for the slowest of the three.
 */
object YtmCatalogSearch {

    private const val TAG = "YtmCatalogSearch"

    private const val ENDPOINT = "https://music.youtube.com/youtubei/v1/search"

    /** YouTube's own published web-client key. See the note on the same constant in [YtmSearch]. */
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

    private const val CLIENT_VERSION = "1.20260707.12.00"

    /** The `params` that put albums in the shelf. */
    private const val ALBUMS_PARAMS = "EgWKAQIYAWoKEAkQChAFEAMQBA=="

    /** The `params` that put artists in the shelf. */
    private const val ARTISTS_PARAMS = "EgWKAQIgAWoKEAkQChAFEAMQBA=="

    /** How many of each kind to keep. Enough to fill a shelf, few enough not to page forever. */
    private const val PER_KIND = 12

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The albums [query] matches, or an empty list when YouTube has nothing or refuses.
     *
     * Never throws. A catalogue that cannot be reached is an empty one, because the alternative is
     * taking down a search screen that has other sources on it.
     */
    suspend fun albums(query: String): List<YtmEntity> = shelf(query, ALBUMS_PARAMS, YtmEntity.Kind.ALBUM)

    /** The artists [query] matches, or an empty list on the same terms. */
    suspend fun artists(query: String): List<YtmEntity> = shelf(query, ARTISTS_PARAMS, YtmEntity.Kind.ARTIST)

    private suspend fun shelf(
        query: String,
        params: String,
        kind: YtmEntity.Kind,
    ): List<YtmEntity> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()

        return runCatching {
            val body = buildJsonObject {
                put("context", context())
                put("query", JsonPrimitive(term))
                put("params", JsonPrimitive(params))
            }
            val response: String = YtmHttp.ktor.post(ENDPOINT) {
                parameter("key", KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.body()

            entities(json.parseToJsonElement(response), kind)
        }.onFailure {
            Log.w(TAG, "$kind search for '$term' failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * Every row in the shelf that is the kind asked for.
     *
     * The rows are the same rows a song search returns, in the same shape — a title in the first
     * column, credits in the second — so they go through the same parser. What differs is that the
     * id comes off the row itself rather than off a linked run: an album's own name is its title,
     * not a link to itself, so there is no run to read an id from and the row's endpoint is the
     * only place one is written down.
     */
    private fun entities(root: JsonElement, kind: YtmEntity.Kind): List<YtmEntity> {
        val found = mutableListOf<YtmEntity>()
        for (shelf in YtmSearch.shelves(root)) {
            for (element in shelf["contents"].arr()) {
                // A row is a one-key wrapper around its renderer, so the fields are one level in.
                // Reading the wrapper is reading a row with no title, no picture and no endpoint —
                // which is a shelf of nothing, silently.
                val wrapper = element.obj() ?: continue
                val row = wrapper.values.firstOrNull()?.obj() ?: continue

                val endpoint = row.descend("navigationEndpoint", "browseEndpoint").obj()
                val pageType = endpoint
                    ?.descend(
                        "browseEndpointContextSupportedConfigs",
                        "browseEndpointContextMusicConfig",
                        "pageType",
                    )
                    ?.plain()
                val wanted = if (kind == YtmEntity.Kind.ALBUM) {
                    "MUSIC_PAGE_TYPE_ALBUM"
                } else {
                    "MUSIC_PAGE_TYPE_ARTIST"
                }
                if (pageType != wanted) continue

                val browseId = endpoint["browseId"].plain() ?: continue

                // Read here rather than through the song parser, because the song parser insists
                // on a video id and an album row has none: it is a page, not a recording. The two
                // fields it would have supplied are read the same way instead — the title is the
                // first column's runs, the picture is the row's own thumbnail.
                val title = row.firstColumn()?.trim().orEmpty()
                if (title.isEmpty()) continue

                val credits = row.columnRuns(1)
                found += YtmEntity(
                    browseId = browseId,
                    title = title,
                    // For an album the artist is the only credit there is; for an artist it is the
                    // audience, which is what the row itself prints under the name.
                    subtitle = if (kind == YtmEntity.Kind.ALBUM) {
                        credits.firstOrNull { it.pageType == "MUSIC_PAGE_TYPE_ARTIST" }?.text
                            ?.takeIf { it.isNotBlank() }
                    } else {
                        row.descend("subtitle", "runs").joined()?.trim()?.ifBlank { null }
                    },
                    artworkUrl = row.descend("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
                        .arr()
                        .lastOrNull()
                        ?.obj()
                        ?.get("url")
                        ?.plain(),
                    kind = kind,
                )
            }
        }

        return found.distinctBy { it.browseId }.take(PER_KIND)
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

    private fun JsonElement?.arr(): List<JsonElement> = this as? JsonArray ?: emptyList()

    private fun JsonElement?.plain(): String? = (this as? JsonPrimitive)?.content

    /** One run of a credit column: its text, and what it links to if anything. */
    private data class Credit(val text: String, val pageType: String?)

    /** The runs of a row's nth flex column, with what each of them links to. */
    private fun JsonObject?.columnRuns(index: Int): List<Credit> = this
        ?.get("flexColumns")
        ?.arr()
        ?.getOrNull(index)
        ?.descend("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.arr()
        ?.mapNotNull { run ->
            val text = run.obj()?.get("text")?.plain() ?: return@mapNotNull null
            Credit(
                text = text,
                pageType = run.descend(
                    "navigationEndpoint",
                    "browseEndpoint",
                    "browseEndpointContextSupportedConfigs",
                    "browseEndpointContextMusicConfig",
                    "pageType",
                ).plain(),
            )
        }
        .orEmpty()

    /** The joined text of a row's first flex column, which is its title. */
    private fun JsonObject?.firstColumn(): String? = this.columnRuns(0)
        .joinToString("") { it.text }
        .ifBlank { null }

    private fun JsonElement?.joined(): String? {
        val runs = arr()
        if (runs.isEmpty()) return null
        return runs.mapNotNull { it.obj()?.get("text")?.plain() }.joinToString("")
    }

    private fun JsonElement?.descend(vararg keys: String): JsonElement? {
        var current: JsonElement? = this
        for (key in keys) current = current.obj()?.get(key) ?: return null
        return current
    }
}
