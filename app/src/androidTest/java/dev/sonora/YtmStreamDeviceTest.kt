package dev.sonora

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sonora.ytm.YtmStream
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether YouTube will actually serve a stream to this device.
 *
 * Not a unit test because the answer depends on the network, the client identity and the cipher
 * configuration as they are right now — all of which a JVM fixture would only agree with. A green
 * run here means a track plays; a red one says which stage refused.
 */
@RunWith(AndroidJUnit4::class)
class YtmStreamDeviceTest {

    @Test
    fun resolvesAndFetchesRealAudio() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YtmStream.init(context)

        val audio = YtmStream.resolve("PvM79DJ2PmM")
        println("RESOLVED ${audio?.clientName} ${audio?.kbps}kbps ${audio?.sampleRateHz}Hz ${audio?.mimeType}")
        println("URL ${audio?.url?.take(90)}...")
        println("HEADERS ${audio?.headers}")
        assertTrue("no stream resolved", audio != null)
        requireNotNull(audio)

        // The point of resolving: bytes. A URL that 403s on the media fetch is not a stream.
        val request = Request.Builder()
            .url(audio.url)
            .apply { audio.headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=0-65535")
            .build()
        YtmHttpProbe.get(request) { code, bytes, type ->
            println("FETCH http=$code bytes=$bytes type=$type")
            assertTrue("media fetch refused: $code", code in 200..299)
            assertTrue("no bytes came back", bytes > 0)
        }
    }
}
