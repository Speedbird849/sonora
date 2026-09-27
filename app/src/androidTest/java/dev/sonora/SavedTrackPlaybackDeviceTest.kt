package dev.sonora

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.SavedTrackStore
import dev.sonora.backend.withSaved
import dev.sonora.playback.StreamDataSource
import dev.sonora.ytm.YtmSearch
import dev.sonora.ytm.YtmStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether a kept track still plays after the app has forgotten everything about it.
 *
 * The library is two things joined: files on disk, and rows of metadata that name streams. A track
 * in the second half only exists as a row, and a row that has lost its video id — or that reads back
 * under a different key, so the playlist entry pointing at it no longer resolves — is a track that
 * appears in the library and cannot be played. Nothing about that shows up in a test that starts
 * from a live search, because there the video id is still in memory.
 *
 * So this goes the long way round: write the row, read the file back as a fresh launch would, fold it
 * into the library the way the backend does, and play what comes out.
 */
@RunWith(AndroidJUnit4::class)
class SavedTrackPlaybackDeviceTest {

    @Test
    fun aRestoredTrackResolvesAndStreams() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YtmStream.init(context)

        val found = YtmSearch.search("tame impala let it happen").firstOrNull()
        assertNotNull("search returned nothing", found)

        // Written to disk and read back, rather than carried in a variable.
        val file = File(context.cacheDir, "restored-tracks.json")
        SavedTrackStore(file).save(
            listOf(
                dev.sonora.backend.SavedTrack(
                    videoId = found!!.videoId,
                    title = found.title,
                    artist = found.artist,
                    album = found.album,
                    artworkUrl = found.artworkUrl,
                ),
            ),
        )

        val restored = SavedTrackStore(file).load()
        assertEquals("one row should have survived the round trip", 1, restored.size)
        assertEquals("the video id must survive", found.videoId, restored.first().videoId)

        // Folded in the way the backend does when it merges saved rows into a scanned library.
        val library = emptyList<LibraryTrack>().withSaved(restored)
        val track = library.firstOrNull()
        assertNotNull("a saved row should become a library track", track)
        assertNull("it is not a file", track!!.file)
        assertEquals("the key must be the one the playlist stored", "ytm:${found.videoId}", track.key)

        val audio = YtmStream.resolve(track.key.removePrefix("ytm:"))
        assertNotNull("a restored track did not resolve: ${track.key}", audio)
        println("RESTORED ${track.key} -> ${audio!!.clientName} ${audio.kbps}kbps")

        val source: DataSource = StreamDataSource.factory { YtmStream.headersFor(it).orEmpty() }
            .createDataSource()
        val length = 98_304
        try {
            val declared = source.open(
                DataSpec.Builder()
                    .setUri(audio.url)
                    .setPosition(0)
                    .setLength(length.toLong())
                    .build(),
            )
            val buffer = ByteArray(length)
            var total = 0
            while (total < length) {
                val read = source.read(buffer, total, length - total)
                if (read <= 0) break
                total += read
            }
            println("RESTORED read=$total declared=$declared")
            source.close()

            assertTrue("no bytes came back from a restored track", total > 0)
        } finally {
            file.delete()
        }
    }
}
