package dev.sonora.backend.taste

import dev.sonora.backend.LibraryTrack
import dev.sonora.ytm.YtmTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class TasteTracksTest {

    @Test
    fun `a streamed track carries its artwork into the model`() {
        val track = LibraryTrack.fromRemote(
            YtmTrack(
                videoId = "abc",
                title = "Song",
                artist = "Artist",
                artworkUrl = "https://example.test/cover.jpg",
            ),
        )

        assertEquals("https://example.test/cover.jpg", track.toTrackRef().artworkUrl)
    }

    @Test
    fun `an autoplay pick keeps its artwork on the way back to the queue`() {
        val ref = TrackRef.of(
            title = "Song",
            artist = "Artist",
            ytmId = "abc",
            artworkUrl = "https://example.test/cover.jpg",
        )

        val track = ref.toLibraryTrack()

        assertEquals("https://example.test/cover.jpg", track?.artworkUrl)
        assertEquals("https://example.test/cover.jpg", track?.remote?.artworkUrl)
    }
}
