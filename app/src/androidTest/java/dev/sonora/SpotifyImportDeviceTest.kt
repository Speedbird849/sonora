package dev.sonora

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sonora.backend.PlaylistStore
import dev.sonora.backend.SavedTrackStore
import dev.sonora.backend.SonoraBackend
import java.io.File
import dev.sonora.backend.LibraryTrack
import dev.sonora.spotify.SpotifyEmbed
import dev.sonora.spotify.SpotifyFetch
import dev.sonora.spotify.SpotifyImporter
import dev.sonora.spotify.SpotifyLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether a real Spotify playlist can be read and matched against the catalogue.
 *
 * The embed page is undocumented, so the only thing that establishes it still answers is asking it.
 */
@RunWith(AndroidJUnit4::class)
class SpotifyImportDeviceTest {

    private val link = "https://open.spotify.com/playlist/0rhj3YhzLUz8zqg1wQX7nf"

    @Test
    fun readsAndMatchesALivePlaylist() = runBlocking {
        val ref = SpotifyLink.parse(link)
        assertNotNull("the link should parse", ref)

        val read = SpotifyEmbed.fetch(ref!!)
        assertTrue("expected a playlist, got $read", read is SpotifyFetch.Loaded)
        val collection = (read as SpotifyFetch.Loaded).collection
        println("READ '${collection.title}' by ${collection.owner}: ${collection.tracks.size} tracks")
        assertTrue("a playlist with no tracks is not an import", collection.tracks.isNotEmpty())

        val matches = SpotifyImporter.match(collection.tracks)
        val hit = matches.count { it.track != null }
        println("MATCHED $hit/${matches.size}")
        matches.filter { it.track == null }.forEach {
            println("  MISSED ${it.spotify.title} | ${it.spotify.artist}")
        }
        matches.filter { it.track != null }.take(3).forEach {
            println("  HIT ${it.spotify.title} -> ${it.track!!.title} / ${it.track!!.artist} (${it.track!!.durationSec}s)")
        }
        assertTrue("nothing matched at all, so the matcher is broken", hit > 0)
    }

    @Test
    fun aMatchedTrackBecomesALibraryEntry() = runBlocking {
        val collection = (SpotifyEmbed.fetch(SpotifyLink.parse(link)!!) as SpotifyFetch.Loaded).collection
        val matches = SpotifyImporter.match(collection.tracks)
        val first = matches.first { it.track != null }

        val track = LibraryTrack.fromRemote(first.track!!)
        assertTrue("a remote track has no file", track.file == null)
        assertTrue("key should be ytm-scoped", track.key.startsWith("ytm:"))
    }
}

/**
 * Writing an import out.
 *
 * Separate from the reading above because the two fail in completely different ways, and this one
 * failed on a device while every offline test passed: thirty-six tracks were each saved by their own
 * read-modify-write of a whole-document store, so the last write won and the file ended up holding
 * one track. Nothing about the reading could have caught that.
 */
@RunWith(AndroidJUnit4::class)
class SpotifyImportWriteDeviceTest {

    @Test
    fun everyImportedTrackSurvivesBeingWritten() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val collection =
            (SpotifyEmbed.fetch(SpotifyLink.parse(LINK)!!) as SpotifyFetch.Loaded).collection
        val matches = SpotifyImporter.match(collection.tracks)
        val tracks = matches.mapNotNull { it.track }.map(LibraryTrack::fromRemote)
        assertTrue("fixture matched nothing", tracks.size > 20)

        // Start from a known state, so a leftover from a previous run cannot carry the test.
        context.deleteFile("saved-tracks.json")
        context.deleteFile("playlists.json")

        val id = SonoraBackend.importSpotifyPlaylist(context, "chill", tracks)
        assertNotNull("the import should produce a playlist", id)

        // Read the file rather than the state flow: the bug was in what reached the disk, and the
        // in-memory list was briefly correct.
        val stored = SavedTrackStore(File(context.filesDir, "saved-tracks.json")).load()
        val storedIds = stored.map { it.videoId }.toSet()
        val expectedIds = tracks.mapNotNull { it.remote?.videoId }.toSet()

        println("WROTE ${tracks.size} tracks; store holds ${stored.size}")
        assertEquals(
            "every imported track should have reached the store",
            expectedIds,
            storedIds,
        )

        val playlist = PlaylistStore(File(context.filesDir, "playlists.json")).load()
            .first { it.id == id }
        assertEquals(
            "the playlist should hold every track it was given",
            tracks.map { it.key }.toSet(),
            playlist.trackKeys.toSet(),
        )
    }

    private companion object {
        const val LINK = "https://open.spotify.com/playlist/0rhj3YhzLUz8zqg1wQX7nf"
    }
}
