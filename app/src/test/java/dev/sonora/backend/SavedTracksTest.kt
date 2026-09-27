package dev.sonora.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.sonora.ytm.YtmTrack

/**
 * The kept-track list: what stays, what is refreshed, and what the cap may drop.
 *
 * This is the only record a streamed track has. A playlist entry, a like and a line of listening
 * history are all stored as a key, and if the track is not kept here then every one of them points
 * at nothing — which is exactly how a liked song fails to appear in Liked Songs.
 */
class SavedTracksTest {

    private fun track(id: String) = YtmTrack(
        videoId = id,
        title = "Title $id",
        artist = "Artist $id",
        album = "Album $id",
        artworkUrl = "https://example.com/$id.jpg",
    )

    private fun saved(id: String, keptAt: Long = 0L, playlists: List<String> = emptyList()) =
        SavedTrack(
            videoId = id,
            title = "Title $id",
            artist = "Artist $id",
            playlistIds = playlists,
            keptAt = keptAt,
        )

    @Test
    fun aKeptTrackIsFirstAndCarriesItsDetails() {
        val updated = keep(listOf(saved("a", keptAt = 1L)), track("b"), at = 2L)

        assertEquals(listOf("b", "a"), updated.map { it.videoId })
        assertEquals("Title b", updated.first().title)
    }

    @Test
    fun keepingATrackAgainRefreshesItRatherThanDuplicatingIt() {
        // Playing a track, liking it and adding it to a playlist is three keeps of one recording.
        // Three rows would put the same song in a playlist three times over.
        var saved = keep(emptyList(), track("a"), at = 1L)
        saved = keep(saved, track("a"), at = 2L)
        saved = keep(saved, track("a"), at = 3L, playlistIds = listOf("liked-songs"))

        assertEquals(1, saved.size)
        assertEquals(3L, saved.single().keptAt)
    }

    @Test
    fun aPlaylistMembershipSurvivesTheTrackBeingPlayedAgain() {
        // This is what the cap reads, so a membership that is overwritten by a play is a membership
        // that does not protect anything.
        var saved = keep(emptyList(), track("a"), at = 1L, playlistIds = listOf("road-trip"))
        saved = keep(saved, track("a"), at = 9L)

        assertEquals(listOf("road-trip"), saved.single().playlistIds)
    }

    @Test
    fun aRemovedMembershipLetsTheTrackBeAgedOut() {
        val saved = withoutPlaylist(
            listOf(saved("a", keptAt = 1L, playlists = listOf("road-trip"))),
            videoId = "a",
            playlistId = "road-trip",
        )

        assertTrue(saved.single().playlistIds.isEmpty())
    }

    @Test
    fun theCapDropsTheLeastRecentlyKept() {
        val kept = (1..5L).map { saved("v$it", keptAt = it) }
        val capped = capped(kept, cap = 3)

        assertEquals(listOf("v5", "v4", "v3"), capped.map { it.videoId })
    }

    @Test
    fun theCapNeverDropsSomethingAPlaylistHolds() {
        // A listener put it in a playlist by hand. Ageing it out of the app's memory because they
        // have since played a great many other things is not a decision this app gets to make.
        val saved = listOf(saved("old", keptAt = 1L, playlists = listOf("road-trip"))) +
            (2..6L).map { saved("v$it", keptAt = it) }

        val capped = capped(saved, cap = 3)

        assertTrue(capped.any { it.videoId == "old" })
        assertEquals(3, capped.count { it.playlistIds.isEmpty() })
    }

    @Test
    fun aListUnderTheCapIsLeftExactlyAsItIs() {
        val saved = listOf(saved("a", keptAt = 1L), saved("b", keptAt = 2L))

        assertEquals(saved, capped(saved, cap = 10))
    }

    @Test
    fun keptTracksJoinTheLibraryAsStreams() {
        // The whole point: the library is a scan of files, and a kept stream has to appear in it or
        // nothing that stores a key can be drawn.
        val library = listOf(
            LibraryTrack(
                file = null,
                title = "Downloaded",
                artist = "Artist",
                album = null,
                size = 1024L,
            ),
        )

        val joined = library.withSaved(listOf(saved("a", keptAt = 42L)))

        assertEquals(2, joined.size)
        val stream = joined.first { it.isRemote }
        assertEquals("Title a", stream.title)
        assertTrue(stream.isRemote)
        assertTrue(!stream.isDownloaded)
        assertEquals(42L, stream.arrivedAt)
    }

    @Test
    fun aDownloadedTrackAndItsStreamAreTwoRowsAndSaySo() {
        // A file's key is its path and a stream's is its video id, and nothing in either says they
        // are the same recording — a file that arrived over the network is named by whoever sent
        // it. So both are listed, and the stream is the one that knows it has no file.
        val downloaded = LibraryTrack.from(java.io.File("/music/Let It Happen.flac"))
        val joined = listOf(downloaded).withSaved(listOf(saved("a")))

        assertEquals(2, joined.size)
        assertTrue(joined.any { it.isDownloaded })
        assertTrue(joined.any { it.isRemote && !it.isDownloaded })
    }
}
