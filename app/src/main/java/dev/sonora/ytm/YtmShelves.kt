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
 * One of YouTube Music's own categories: a mood, or a genre.
 *
 * A category is a button rather than a page — it holds no tracks of its own and answers only with
 * a list of playlists somebody else made. So the whole of it is the name, the colour YouTube
 * paints it with, and the pair of ids that open it.
 */
data class YtmCategory(
    val title: String,
    val browseId: String,
    val params: String?,
    /** The stripe colour, as an ARGB int. Used for the category's own chip. */
    val color: Int,
    /** Which grid it came from: "Moods & moments" or "Genres". */
    val group: String,
)

/** One of somebody's playlists, as a shelf entry. */
data class YtmPlaylistRef(
    val browseId: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
)

/**
 * YouTube Music's own shelves, for when there is nothing of the listener's to show.
 *
 * A page whose whole content is "you have no music yet" is a page that teaches a new install that
 * the app is empty. These are the shelves YouTube Music puts on its own front page — moods, genres,
 * and the playlists behind them — so a listener with nothing has somewhere to go that is not a
 * message.
 *
 * Same client and key as everything else here, for the same reason.
 */
object YtmShelves {

    private const val TAG = "YtmShelves"

    private const val ENDPOINT = "https://music.youtube.com/youtubei/v1/browse"

    /** YouTube's own published web-client key. See the note on the same constant in [YtmSearch]. */
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

    private const val CLIENT_VERSION = "1.20260707.12.00"

    /** The page that holds the mood and genre grids. */
    private const val MOODS_AND_GENRES = "FEmusic_moods_and_genres"

    /**
     * How many of each kind to keep.
     *
     * The moods only, and ten of them. A grid of forty-nine is a catalogue to scroll through
     * before reaching anything, and the second grid — every genre YouTube Music has — is fifty
     * alphabetical rows of a page that already has a search field on it: anything a listener can
     * name they can type, and typing it lands on a better answer than a tile.
     */
    private const val MAX_MOODS = 10

    /** The one group the grid shows. The other is fetched and then not drawn. */
    private const val MOOD_GROUP = "Moods & moments"

    /**
     * The moods with no picture shipped for them, so they would open as a flat rectangle.
     *
     * Dropped rather than fetched: a grid is read at a glance and one tile without a picture among
     * ten with them is the one that gets looked at, and a grid of nine is a grid of nine.
     */
    private val EXCLUDED = setOf("Feel good")

    /** How many playlists to keep from a category. One shelf's worth. */
    private const val MAX_PLAYLISTS = 18

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Every mood and genre YouTube lists, or an empty list when there is no answer.
     *
     * Never throws. A page that cannot be reached shows the listener nothing to browse, which is
     * better than a page that has taken the screen down trying.
     */
    suspend fun categories(): List<YtmCategory> = runCatching {
        val root = json.parseToJsonElement(post(MOODS_AND_GENRES))
        val found = mutableListOf<YtmCategory>()

        for (grid in grids(root)) {
            val group = grid.descend("header", "gridHeaderRenderer", "title", "runs")
                .joined()
                ?.trim()
                ?.ifBlank { null }
                ?: continue

            for (item in grid.descend("items").arr()) {
                val button = item.obj()?.get("musicNavigationButtonRenderer") ?: continue
                val title = button.descend("buttonText", "runs").joined()?.trim().orEmpty()
                if (title.isEmpty()) continue

                val endpoint = button.descend("clickCommand", "browseEndpoint").obj() ?: continue
                val browseId = endpoint["browseId"].plain() ?: continue

                found += YtmCategory(
                    title = title,
                    browseId = browseId,
                    params = endpoint["params"].plain(),
                    // Left as the number YouTube sent: it is already an ARGB int, and re-deriving it
                    // from a colour picker would be one more way for the chip to differ from the
                    // page it came from.
                    color = (button.descend("solid", "leftStripeColor") as? JsonPrimitive)
                        ?.content
                        ?.toLongOrNull()
                        ?.toInt()
                        ?: 0xFF6B6B6B.toInt(),
                    group = group,
                )
            }
        }

        found
            .filter { it.group == MOOD_GROUP && it.title !in EXCLUDED }
            .take(MAX_MOODS)
    }.onFailure {
        Log.w(TAG, "categories failed: ${it.message}")
    }.getOrDefault(emptyList())

    /**
     * The playlists behind one category, or an empty list.
     *
     * One request for the whole category: its page is a run of carousels, and a category is a shelf
     * rather than a page, so taking the first two carousels is the whole of it.
     */
    suspend fun categoryPlaylists(category: YtmCategory): List<YtmPlaylistRef> = runCatching {
        val root = json.parseToJsonElement(post(category.browseId, category.params))

        val found = mutableListOf<YtmPlaylistRef>()
        for (carousel in carousels(root)) {
            // The cards sit directly in the carousel's `contents` array, each wrapped in its own
            // renderer. Reading through them as a nested object finds nothing at all, which is a
            // category that reports as empty.
            for (row in carousel.descend("contents").arr()) {
                val card = row.obj()?.get("musicTwoRowItemRenderer") ?: continue
                val browseId = card.descend("navigationEndpoint", "browseEndpoint", "browseId")
                    .plain() ?: continue
                val title = card.descend("title", "runs").joined()?.trim().orEmpty()
                if (title.isEmpty()) continue

                found += YtmPlaylistRef(
                    browseId = browseId,
                    title = title,
                    subtitle = card.descend("subtitle", "runs").joined()
                        ?.trim()
                        ?.ifBlank { null },
                    artworkUrl = card
                        .descend("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails")
                        .arr()
                        .lastOrNull()
                        ?.obj()
                        ?.get("url")
                        ?.plain(),
                )
            }
        }

        found.distinctBy { it.browseId }.take(MAX_PLAYLISTS)
    }.onFailure {
        Log.w(TAG, "category '${category.title}' failed: ${it.message}")
    }.getOrDefault(emptyList())

    private suspend fun post(browseId: String, params: String? = null): String {
        val body = buildJsonObject {
            put("context", context())
            put("browseId", JsonPrimitive(browseId))
            if (params != null) put("params", JsonPrimitive(params))
        }
        return YtmHttp.ktor.post(ENDPOINT) {
            parameter("key", KEY)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.body()
    }

    /** The category grids, in the order the page states them. */
    private fun grids(root: JsonElement): List<JsonElement> = root
        .descend(
            "contents",
            "singleColumnBrowseResultsRenderer",
            "tabs",
        )
        ?.arr()
        .orEmpty()
        .flatMap { tab -> tab.descend("tabRenderer", "content", "sectionListRenderer", "contents").arr() }
        .mapNotNull { it.obj()?.get("gridRenderer") }

    /** Every carousel on a page, whichever wrapper it arrived in. */
    private fun carousels(root: JsonElement): List<JsonElement> = root
        .descend("contents", "singleColumnBrowseResultsRenderer", "tabs")
        ?.arr()
        .orEmpty()
        .flatMap { tab -> tab.descend("tabRenderer", "content", "sectionListRenderer", "contents").arr() }
        .mapNotNull { it.obj()?.get("musicCarouselShelfRenderer") }

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

    private fun JsonElement?.joined(): String? {
        if (this is JsonPrimitive) return content
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
