package dev.sonora.ui

import dev.sonora.backend.SearchHit
import dev.sonora.backend.SearchState
import dev.sonora.protocol.peer.FileAttributes
import dev.sonora.ytm.YtmTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The line under the search controls.
 *
 * Worth testing because it is the one place the screen can contradict itself: the results are three
 * independent sources answering at different speeds, and a line about one of them sitting above the
 * others reads as wrong.
 */
class SearchStatusTest {

    @Test
    fun `nothing is said before anything has been searched for`() {
        assertNull(note(SearchState(), showSoulseek = true, showYoutube = true))
    }

    @Test
    fun `no counts are given while a source is still outstanding`() {
        val state = SearchState(query = "kid a", searching = true, matched = 12, youtube = listOf(track()))

        // Half the numbers would be final and half provisional, which reads as a total.
        assertEquals("Searching…", note(state))
    }

    @Test
    fun `YouTube alone is still waiting, so the line says so`() {
        val state = SearchState(query = "kid a", hits = listOf(hit()), youtubeLoading = true)

        assertEquals("Searching…", note(state))
    }

    @Test
    fun `no results is not said over a row of albums or artists`() {
        assertNull(note(SearchState(query = "kid a"), albumsAndArtistsShown = true))
    }

    @Test
    fun `no results is said when nothing came back from anywhere`() {
        assertEquals("No results for “kid a”.", note(SearchState(query = "kid a")))
    }

    @Test
    fun `both sources are counted when both answered`() {
        val state = SearchState(
            query = "kid a",
            youtube = listOf(track(), track("other")),
            hits = listOf(hit()),
            matched = 3,
            peers = 2,
        )

        assertEquals("2 on YouTube Music · 3 file(s) from 2 Soulseek peer(s)", note(state))
    }

    @Test
    fun `a source that is switched off is not counted`() {
        val state = SearchState(
            query = "kid a",
            youtube = listOf(track()),
            hits = listOf(hit()),
            matched = 3,
            peers = 2,
        )

        assertEquals("3 file(s) from 2 Soulseek peer(s)", note(state, showYoutube = false))
        assertEquals("1 on YouTube Music", note(state, showSoulseek = false))
    }

    @Test
    fun `the peer count is not reported while the peer results are hidden`() {
        val state = SearchState(query = "kid a", hits = listOf(hit()), matched = 3, peers = 2)

        assertNull(note(state, showSoulseek = false, showYoutube = false, albumsAndArtistsShown = true))
    }

    private fun note(
        state: SearchState,
        showSoulseek: Boolean = true,
        showYoutube: Boolean = true,
        albumsAndArtistsShown: Boolean = false,
    ) = statusNote(state, showSoulseek, showYoutube, albumsAndArtistsShown)

    private fun track(title: String = "Let It Happen") =
        YtmTrack(videoId = "v_$title", title = title, artist = "Tame Impala")

    private fun hit() = SearchHit(
        peer = "peer_a",
        filename = "@@peer_a\\Music\\Kid A\\01.flac",
        size = 1_000L,
        attributes = FileAttributes(),
        averageSpeed = 0,
        hasFreeUploadSlot = true,
        queueLength = 0,
    )
}
