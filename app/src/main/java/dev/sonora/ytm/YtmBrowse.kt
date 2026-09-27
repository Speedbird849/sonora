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
 * Reads an artist's or an album's page off YouTube Music.
 *
 * The other half of [YtmSearch]. A search says a name and gets back rows; a browse is given the id
 * those rows carry and gets back the page they were on. Without it every artist link in the app is
 * a dead end for anyone who does not already own the artist's records, which is most of the people
 * who follow one.
 *
 * One request per page, with no `params`: asked plainly, YouTube answers with the whole page at
 * once — the header, the top songs, and the carousels of albums and singles underneath. Asking for
 * a filtered slice of it instead returns the same page, so there is nothing to gain by being
 * cleverer and a second round trip to lose.
 *
 * The same client and the same key as search, and for the same reason: the client in the request
 * decides the shape of the answer, and only WEB_REMIX answers with music rows.
 */
object YtmBrowse {

    private const val TAG = "YtmBrowse"

    private const val ENDPOINT = "https://music.youtube.com/youtubei/v1/browse"

    /** YouTube's own published web-client key. See the note on the same constant in [YtmSearch]. */
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

    private const val CLIENT_VERSION = "1.20260707.12.00"

    /** How many rows to keep from each part of a page. */
    private const val MAX_TRACKS = 40
    private const val MAX_RELEASES = 20

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** What a browse found out about the page it was sent to. */
    data class Page(
        val title: String? = null,
        val subtitle: String? = null,
        /** The header's own picture, which is the artist's or album's, not any one record's. */
        val artworkUrl: String? = null,
        val tracks: List<YtmTrack> = emptyList(),
        /** Full-length records, from the page's own "Albums" shelf. */
        val albums: List<YtmEntity> = emptyList(),
        /** The rest, from "Singles & EPs". Kept apart because YouTube keeps them apart. */
        val singles: List<YtmEntity> = emptyList(),
    ) {
        val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && singles.isEmpty()
    }

    /**
     * The page behind [browseId], or an empty one when YouTube has nothing to say or refuses.
     *
     * Never throws. A page that cannot be reached is a page with no tracks, because the alternative
     * is taking down whichever screen opened it — and that screen is usually showing something else
     * worth keeping.
     */
    suspend fun page(browseId: String): Page {
        if (browseId.isBlank()) return Page()

        return runCatching {
            val body = buildJsonObject {
                put("context", context())
                put("browseId", JsonPrimitive(browseId))
            }

            val response: String = YtmHttp.ktor.post(ENDPOINT) {
                parameter("key", KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.body()

            val root = json.parseToJsonElement(response)
            val carousels = carousels(root)

            Page(
                title = headerText(root, "title"),
                subtitle = headerText(root, "subtitle"),
                artworkUrl = headerArtwork(root),
                tracks = YtmSearch.parse(root).distinctBy { it.videoId }.take(MAX_TRACKS),
                albums = releases(carousels["Albums"]),
                singles = releases(carousels["Singles & EPs"]),
            )
        }.onFailure {
            Log.w(TAG, "browse of '$browseId' failed: ${it.message}")
        }.getOrDefault(Page())
    }

    /**
     * The shelves of the page, by their own heading.
     *
     * A page is one `musicShelfRenderer` of songs and a run of `musicCarouselShelfRenderer`s, each
     * with a heading that says what it holds. Keying by that heading rather than by position is
     * what lets the albums be found wherever YouTube decides to put them, and what keeps a carousel
     * of "Fans might also like" from being mistaken for the artist's own records.
     */
    private fun carousels(root: JsonElement): Map<String, List<JsonElement>> {
        val found = mutableMapOf<String, List<JsonElement>>()

        for (section in sectionLists(root)) {
            // A section is the carousel itself here, where a search nests one inside an
            // `itemSectionRenderer`. Reading only the nested one is a page whose albums silently
            // do not exist.
            val carousel = section.obj()?.get("musicCarouselShelfRenderer")?.let { listOf(it) }
                ?: listOfNotNull(section)
            for (carousel in carousel) {
                val title = carousel
                    .descend("header", "musicCarouselShelfBasicHeaderRenderer", "title", "runs")
                    .joined()
                    ?.trim()
                    ?.ifBlank { null }
                    ?: continue
                found.putIfAbsent(title, carousel.descend("contents").arr())
            }
        }

        return found
    }

    /**
     * Every section on the page, whichever wrapper it is behind.
     *
     * An artist page puts its contents under `singleColumnBrowseResultsRenderer`; other pages answer
     * with the section list at the top level. Reading only the first is a page that finds nothing.
     */
    private fun sectionLists(root: JsonElement): List<JsonElement> = listOfNotNull(
        root.descend("contents", "singleColumnBrowseResultsRenderer", "tabs")
            ?.arr()
            ?.firstNotNullOfOrNull {
                it.descend("tabRenderer", "content", "sectionListRenderer", "contents")
            },
        root.descend("contents", "sectionListRenderer", "contents"),
    ).flatMap { it.arr() }

    /**
     * The records on a carousel shelf.
     *
     * Each is a `musicTwoRowItemRenderer` carrying a card and the heading above it, so the card is
     * one level in — and the card's own endpoint is where the id is, because an album's name is its
     * title rather than a link to itself.
     */
    private fun releases(rows: List<JsonElement>?): List<YtmEntity> {
        val found = mutableListOf<YtmEntity>()

        for (row in rows.orEmpty()) {
            // The card and the link are the same object: the renderer holds the picture *and* the
            // endpoint, and descending into the picture to find the endpoint looks in the one
            // place it is not.
            val card = row.descend("musicTwoRowItemRenderer") ?: continue
            val endpoint = card.descend("navigationEndpoint", "browseEndpoint").obj() ?: continue
            val pageType = endpoint
                .descend(
                    "browseEndpointContextSupportedConfigs",
                    "browseEndpointContextMusicConfig",
                    "pageType",
                )
                .plain()
            if (pageType != "MUSIC_PAGE_TYPE_ALBUM") continue

            val browseId = endpoint["browseId"].plain() ?: continue
            val title = card.descend("title", "runs").joined()?.trim().orEmpty()
            if (title.isEmpty()) continue

            found += YtmEntity(
                browseId = browseId,
                title = title,
                subtitle = card.descend("subtitle", "runs").joined()?.trim()?.ifBlank { null },
                artworkUrl = card
                    .descend("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails")
                    .arr()
                    .lastOrNull()
                    ?.obj()
                    ?.get("url")
                    ?.plain(),
                kind = YtmEntity.Kind.ALBUM,
            )
        }

        return found.distinctBy { it.browseId }.take(MAX_RELEASES)
    }

    /**
     * The one-line summary under the title.
     *
     * YouTube splits it into runs and the separators between them are unlinked, so the runs are
     * joined as they come: "Artist", " • ", "1.2M subscribers" reads as three pieces of one line
     * and anything that picks only the linked runs drops the subscriber count.
     */
    private fun headerText(root: JsonElement, key: String): String? = listOfNotNull(
        root.descend("header", "musicImmersiveHeaderRenderer"),
        root.descend("header", "musicDetailHeaderRenderer"),
        root.descend("header", "musicResponsiveHeaderRenderer"),
        root.descend("header", "musicEditablePlaylistDetailHeaderRenderer"),
    ).firstNotNullOfOrNull { it.obj()?.get(key) }.joined()?.trim()?.ifBlank { null }

    /** The biggest picture on the page, which is the one in the header rather than a row's. */
    private fun headerArtwork(root: JsonElement): String? = listOfNotNull(
        root.descend("header", "musicImmersiveHeaderRenderer"),
        root.descend("header", "musicDetailHeaderRenderer"),
        root.descend("header", "musicResponsiveHeaderRenderer"),
    ).firstNotNullOfOrNull { header ->
        header.descend("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
            .arr()
            .lastOrNull()
    }?.obj()?.get("url")?.plain()

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
        // A heading is written either as a run of text or as one bare string, and this reads both.
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
