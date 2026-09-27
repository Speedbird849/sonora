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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    /**
     * Runtime in whole seconds.
     *
     * Read out of the credit column, where YouTube prints it after the album as a plain "M:SS" with
     * no link on it — which is what tells it apart from the artist and album around it, and the
     * only place a search response states a length at all. Null when the shape does not carry one,
     * which is the normal case for a continuation page.
     */
    val durationSec: Int? = null,
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
     * YouTube's own published web-client key — the same constant that appears in yt-dlp, NewPipe and
     * the web player itself. It identifies the calling client, not an account, and carries no access
     * to anyone's data; a scanner flagging it is a true positive about the string and a false
     * positive about the risk.
     *
     * Kept rather than dropped because search answers without it too, which was verified, but only
     * for a single request: whether the keyless path is metered more tightly was *not* established,
     * and a catalogue that starts refusing searches under load is a worse failure than a
     * permanently recurring scanner warning.
     */
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

    private const val CLIENT_VERSION = "1.20260707.12.00"

    /**
     * The `params` that ask for the songs tab rather than the mixed default. */

    private const val SONGS_PARAMS = "EgWKAQIIAWoKEAkQChAFEAMQBA=="

    /**
     * How many rows a search returns, and how many pages it walks to get them.
     *
     * A page is 20. Forty is two pages, which is more than a listener scrolls and less than would
     * cost a round trip per keystroke for rows nobody reaches.
     */
    private const val MAX_RESULTS = 40
    private const val MAX_PAGES = 2

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

            var first: JsonElement = json.parseToJsonElement(response)
            val tracks = parse(first).toMutableList()

            // The shelf carries a continuation token rather than a URL, so a second page is asked
            // for by handing the token back. Stopping at one page would cap every search at 20
            // results however many the query matched, which reads as "that is all there is".
            var page = 1
            while (tracks.size < MAX_RESULTS && page < MAX_PAGES) {
                val token = continuationOf(first) ?: break
                val next = continuation(token, term) ?: break
                first = next
                tracks += parse(next)
                page++
            }

            tracks.distinctBy { it.videoId }
        }.onFailure {
            Log.w(TAG, "search for '$term' failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * The token that asks for the page after this one, or null when there isn't one.
     *
     * Read out of `musicShelfRenderer.continuations[].nextContinuationData`, which is where this
     * client puts it. The other shape — a `continuationItemRenderer` row at the end of the shelf —
     * is what the ordinary web client sends, and reading only that one is a search that silently
     * stops at twenty results however many the query matched.
     */
    private fun continuationOf(root: JsonElement): String? {
        for (shelf in shelves(root)) {
            shelf["continuations"].arr()
                .firstNotNullOfOrNull { it.descend("nextContinuationData", "continuation").str() }
                ?.let { return it }
        }
        return null
    }

    private suspend fun continuation(token: String, term: String): JsonElement? = runCatching {
        val body = buildJsonObject {
            put("context", context())
            put("query", JsonPrimitive(term))
            put("params", JsonPrimitive(SONGS_PARAMS))
            put("continuation", JsonPrimitive(token))
        }
        YtmHttp.ktor.post(ENDPOINT) {
            parameter("key", KEY)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.body<String>().let { json.parseToJsonElement(it) }
    }.getOrNull()

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
    /**
     * The tracks in a response.
     *
     * [defaultArtist] and [defaultAlbum] are for an album's own page, whose rows carry a title, a
     * length and a picture and *nothing else* — the credits are on the album, not repeated on
     * every one of its twelve tracks. Without them such a row has no artist and is thrown away, and
     * the album reports that it is empty.
     */
    internal fun parse(
        root: JsonElement,
        defaultArtist: String? = null,
        defaultAlbum: String? = null,
    ): List<YtmTrack> {
        val tracks = mutableListOf<YtmTrack>()
        for (shelf in shelves(root)) {
            for (row in shelf["contents"].arr()) {
                parseRow(
                    element = row.obj()?.get("musicResponsiveListItemRenderer"),
                    defaultArtist = defaultArtist,
                    defaultAlbum = defaultAlbum,
                )?.let(tracks::add)
            }
        }
        return tracks.distinctBy { it.videoId }
    }

    /**
     * Every song shelf in a response, whichever page shape it arrived in.
     *
     * Two shapes, because the first page and the pages after it are not built alike: the first is
     * nested under the songs tab, and a continuation arrives on its own at
     * `continuationContents.musicShelfContinuation` with no tab around it. Walking one path and
     * expecting the other is a second page that is requested and then never read.
     */
    /**
     * Every music shelf in a response, whichever wrapper it arrived in.
     *
     * Shared with [YtmBrowse] because a browse response wraps its shelves somewhere else again and
     * the wrapper is the only thing that differs — the shelves themselves, and the rows in them,
     * are the same objects in both answers.
     */
    internal fun shelves(root: JsonElement): List<JsonObject> {
        val found = mutableListOf<JsonObject>()

        // Two wrappers, because search and browse answer differently: a search nests its shelves
        // under `tabbedSearchResultsRenderer`, a page under `singleColumnBrowseResultsRenderer`.
        // Reading only the first is a browse that finds no songs and reports an empty artist.
        val tabRoots = root.descend("contents", "tabbedSearchResultsRenderer", "tabs").arr() +
            root.descend("contents", "singleColumnBrowseResultsRenderer", "tabs").arr()

        for (tab in tabRoots) {
            val sections = tab.descend("tabRenderer", "content", "sectionListRenderer", "contents").arr()
            for (section in sections) {
                // Two depths, because the two kinds of search put the shelf in different places: an
                // unfiltered answer nests one row per `itemSectionRenderer`, while a filtered one
                // — albums, artists, playlists — hands back the shelf itself. Reading only the
                // deeper one is a shelf of albums that silently finds nothing.
                section.obj()?.get("musicShelfRenderer").obj()?.let(found::add)
                section.descend("itemSectionRenderer", "contents").arr().forEach { row ->
                    row.obj()?.get("musicShelfRenderer").obj()?.let(found::add)
                }
            }
        }

        // An album's page is the odd one out: two columns, no tabs, and its shelf in the second
        // one. Read only the two tabbed shapes and every album reports that it has no tracks.
        for (section in root.descend(
            "contents",
            "twoColumnBrowseResultsRenderer",
            "secondaryContents",
            "sectionListRenderer",
            "contents",
        ).arr()) {
            section.obj()?.get("musicShelfRenderer").obj()?.let(found::add)
        }

        root.descend("continuationContents", "musicShelfContinuation").obj()?.let(found::add)

        return found
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
    internal fun parseRow(
        element: JsonElement?,
        defaultArtist: String? = null,
        defaultAlbum: String? = null,
    ): YtmTrack? {
        val row = element.obj() ?: return null
        val videoId = row.descend("playlistItemData", "videoId").str() ?: return null

        val columns = row["flexColumns"].arr()
        val title = columns.firstOrNull().flexText()
        if (title.isNullOrBlank()) return null

        val credits = columns.flatMap { it.runs() }
        val artistRun = credits.firstOrNull { it.pageType == "MUSIC_PAGE_TYPE_ARTIST" }
        val albumRun = credits.firstOrNull { it.pageType == "MUSIC_PAGE_TYPE_ALBUM" }
        val artist = artistRun?.text?.takeIf { it.isNotBlank() } ?: defaultArtist ?: return null

        // The unlinked run that parses as a runtime, and nothing else: the separators between the
        // credits are unlinked too, and neither " • " nor a year is going to parse as "M:SS". An
        // album's page keeps the length in a pinned column instead of in the credits, so that is
        // read too — it is the only length such a row carries.
        val runtime: Runtime? = credits
            .firstOrNull { it.pageType == null && it.text.secondsOrNull() != null }
            ?.let { Runtime(it.text, it.text) }
            ?: row["fixedColumns"].arr().firstNotNullOfOrNull { column ->
                column.descend("musicResponsiveListItemFixedColumnRenderer", "text", "simpleText")
                    .str()
                    ?.takeIf { it.secondsOrNull() != null }
                    ?.let { Runtime(it, it) }
            }

        return YtmTrack(
            videoId = videoId,
            title = title.trim(),
            artist = artist.trim(),
            artistId = artistRun?.browseId,
            album = albumRun?.text?.trim() ?: defaultAlbum,
            albumId = albumRun?.browseId,
            artworkUrl = row.descend(
                "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
            ).arr().mapNotNull { it.obj()?.get("url").str() }.lastOrNull(),
            durationSec = runtime?.let { it.text.secondsOrNull() ?: it.raw.secondsOrNull() },
        )
    }

    private data class Run(val text: String, val pageType: String?, val browseId: String?)

    /**
     * A length, however it was written.
     *
     * [raw] is the text as it stands and [text] is the run it came from, which for an album's page
     * is a bare string in a pinned column rather than a run in the credits. Both are the same
     * number; keeping them apart is what lets one reader take either without the other guessing.
     */
    private data class Runtime(val raw: String, val text: String)

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

    /**
     * "3:48" as 228 seconds, or null when the text is not a runtime.
     *
     * Null rather than zero for a parse failure, because a zero would be read as an instant track
     * and compared against — see the importer's duration check, where an absent length is a
     * "cannot tell" and a zero is a confident wrong answer.
     */
    private fun String.secondsOrNull(): Int? {
        val parts = trim().split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return when (numbers.size) {
            2 -> numbers[0] * 60 + numbers[1]
            // Hours appear only on a live stream, which is not something to import.
            3 -> if (numbers[0] == 0) numbers[1] * 60 + numbers[2] else null
            else -> null
        }
    }
}
