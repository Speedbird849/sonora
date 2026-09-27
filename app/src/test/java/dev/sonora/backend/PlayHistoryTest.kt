package dev.sonora.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.sonora.ytm.YtmTrack

/**
 * The recently-played list, including the streamed tracks it now carries.
 *
 * A key is not a track. Before this, a play of streamed music wrote down a key and nothing else, so
 * the row that exists to be the fastest way back into a song was a row that could not be drawn —
 * no title, no cover, and nothing to play.
 */
class PlayHistoryTest {

    private fun streamed(id: String) = LibraryTrack.fromRemote(
        YtmTrack(
            videoId = id,
            title = "Let It Happen",
            artist = "Tame Impala",
            album = "Currents",
            artworkUrl = "https://example.com/$id.jpg",
        ),
    )

    @Test
    fun aStreamedPlayCarriesTheTrack() {
        val history = PlayHistory.record(emptyList(), streamed("a"), at = 1_000L)

        val entry = history.single()
        assertEquals("ytm:a", entry.key)
        assertTrue(entry.isStreamed)
        assertEquals("Let It Happen", entry.title)
        assertEquals("Tame Impala", entry.artist)
        assertEquals("Currents", entry.album)
    }

    @Test
    fun theEntryResolvesToAPlayableTrackOnItsOwn() {
        val entry = PlayHistory.record(emptyList(), streamed("a"), at = 1_000L).single()
        val track = entry.asLibraryTrack()

        assertEquals("Let It Happen", track?.title)
        assertTrue(track?.isRemote == true)
        assertEquals("a", track?.remote?.videoId)
        assertTrue(track?.playableUri == null || track.playableUri!!.startsWith("ytm:"))
    }

    @Test
    fun aDownloadedPlayIsResolvedAgainstTheLibraryInstead() {
        // Its own file metadata is better than anything recorded at play time, and the file may
        // since have been deleted — in which case the caller drops the row rather than offering a
        // track that cannot be played.
        val downloaded = LibraryTrack(
            file = java.io.File("/music/Let It Happen.flac"),
            title = "Let It Happen",
            artist = "Tame Impala",
            album = "Currents",
            size = 4_096L,
        )

        val entry = PlayHistory.record(emptyList(), downloaded, at = 1_000L).single()

        assertFalse(entry.isStreamed)
        assertNull(entry.asLibraryTrack())
        assertEquals(downloaded.key, entry.key)
    }

    @Test
    fun playingSomethingAgainMovesItRatherThanAddingASecondCopy() {
        // One album on repeat would otherwise fill the list with that album and push out everything
        // else, which is what "Recently played" is for.
        var history = PlayHistory.record(emptyList(), streamed("a"), at = 1L)
        history = PlayHistory.record(history, streamed("b"), at = 2L)
        history = PlayHistory.record(history, streamed("a"), at = 3L)

        assertEquals(listOf("ytm:a", "ytm:b"), history.map { it.key })
        assertEquals(3L, history.first().playedAt)
    }

    @Test
    fun theListStaysBounded() {
        var history = emptyList<PlayedTrack>()
        repeat(PlayHistory.MAX + 20) { history = PlayHistory.record(history, streamed("v$it"), at = it.toLong()) }

        assertEquals(PlayHistory.MAX, history.size)
        assertEquals("ytm:v${PlayHistory.MAX + 19}", history.first().key)
    }

    @Test
    fun aTrackWithNoKeyIsNotRecorded() {
        val nameless = LibraryTrack(file = null, title = "", artist = null, album = null, size = 0L)

        assertTrue(PlayHistory.record(emptyList(), nameless, at = 1L).isEmpty())
    }
}
