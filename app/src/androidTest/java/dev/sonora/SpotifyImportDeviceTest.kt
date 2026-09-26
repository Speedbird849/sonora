package dev.sonora

import androidx.test.ext.junit.runners.AndroidJUnit4
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
