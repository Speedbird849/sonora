package dev.sonora.ytm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class YtmRadioTest {

    /** The radio `next` response, with two panel shapes and a duplicate row. */
    private val fixture: String = checkNotNull(
        javaClass.getResourceAsStream("/ytm/radio-next.json"),
    ).bufferedReader().readText()

    private fun parse(limit: Int = YtmRadio.DEFAULT_LIMIT) =
        YtmRadio.parse(Json.parseToJsonElement(fixture), limit)

    @Test
    fun `reads the panel rows in order and drops the duplicate`() {
        val tracks = parse()

        assertEquals(
            listOf("seed0000001", "rel00000002", "rel00000003", "rel00000004"),
            tracks.map { it.videoId },
        )
    }

    @Test
    fun `reads title credits artwork and runtime from a wrapped row`() {
        val seed = parse().first()

        assertEquals("Seed Song", seed.title)
        assertEquals("Seed Artist", seed.artist)
        assertEquals("UCseedartist", seed.artistId)
        assertEquals("Seed Album", seed.album)
        assertEquals("MPREseedalbum", seed.albumId)
        assertEquals("https://example.test/seed-large.jpg", seed.artworkUrl)
        assertEquals(238, seed.durationSec)
    }

    @Test
    fun `reads a bare simpleText title and a short byline`() {
        val related = parse()[2]

        assertEquals("Related Two", related.title)
        assertEquals("Another Artist", related.artist)
        assertEquals(125, related.durationSec)
    }

    @Test
    fun `an unlinked byline still yields an artist`() {
        val row = parse().first { it.videoId == "rel00000004" }

        assertEquals("DECADR", row.artist)
        assertEquals("https://example.test/rel3-large.jpg", row.artworkUrl)
        assertEquals(213, row.durationSec)
    }

    @Test
    fun `an empty response is an empty list`() {
        val empty = YtmRadio.parse(Json.parseToJsonElement("{}"))
        assertEquals(emptyList<YtmTrack>(), empty)
    }

    @Test
    fun `the limit bounds the answer`() {
        assertEquals(2, parse(limit = 2).size)
    }
}
