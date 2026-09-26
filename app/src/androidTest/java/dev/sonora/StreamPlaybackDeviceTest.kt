package dev.sonora

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sonora.backend.LibraryTrack
import dev.sonora.playback.StreamDataSource
import dev.sonora.ytm.YtmSearch
import dev.sonora.ytm.YtmStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether a YouTube Music track survives the whole path: search, resolve, and hand the bytes to the
 * same source the player is given.
 *
 * The last part is the point. A resolved URL proves only that a URL came back; what matters is
 * whether fetching it — with the headers that URL's own client identity requires — yields audio.
 */
@RunWith(AndroidJUnit4::class)
class StreamPlaybackDeviceTest {

    @Test
    fun aSearchResultBecomesAPlayableStream() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YtmStream.init(context)

        val found = YtmSearch.search("tame impala let it happen").firstOrNull()
        assertNotNull("search returned nothing", found)
        println("FOUND ${found!!.videoId} ${found.title} / ${found.artist}")

        // The track as the library and the player will see it.
        val track = LibraryTrack.fromRemote(found)
        assertNull("a remote track has no file", track.file)
        assertTrue("a remote track is not downloaded", !track.isDownloaded)
        assertTrue("key should be stable and not a path: ${track.key}", track.key.startsWith("ytm:"))

        val audio = YtmStream.resolve(found.videoId)
        assertNotNull("nothing resolved", audio)
        println("RESOLVED ${audio!!.clientName} ${audio.kbps}kbps ${audio.mimeType}")

        val headers = YtmStream.headersFor(audio.url)
        assertNotNull("no headers recorded for a minted URL", headers)
        println("HEADERS ${headers!!.keys}")

        // Read it through the source the player is handed, not through a fresh client.
        val length = 98_304
        val source: DataSource = StreamDataSource.factory { YtmStream.headersFor(it).orEmpty() }
            .createDataSource()
        try {
            val declared = source.open(
                DataSpec.Builder().setUri(audio.url).setPosition(0).setLength(length.toLong()).build(),
            )
            val buffer = ByteArray(length)
            var total = 0
            while (total < buffer.size) {
                val read = source.read(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
            println("SOURCE read=$total declared=$declared")
            assertTrue("the source delivered no bytes", total > 0)
        } finally {
            source.close()
        }
    }
}
